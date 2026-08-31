# OFAC Sanctions Ingestion

Pipeline de ingestão de listas de sanções da **OFAC** (Office of Foreign Assets Control, do Tesouro dos EUA). Consulta periodicamente cada lista, baixa o snapshot completo **só quando o conteúdo muda**, valida integridade (SHA-256) e boa-formação do XML, transforma o *Advanced XML* em um modelo interno normalizado (escopo em pessoas e entidades), persiste como uma **versão imutável** e — após reconciliar a contagem — a **ativa atomicamente** como `CURRENT`. Mantém as 3 versões operacionais mais recentes por lista (rollback instantâneo por ponteiro) e expõe uma **API de consulta somente leitura** (listagem paginada + busca por nome) sobre a versão `CURRENT`.

> **Status:** spike/MVP funcional. Import real da SDN já validado de ponta a ponta (ver [Evidência](#evidência-primeiro-import-real)). Núcleo independente de fonte: adicionar UN/EU é escrever um adapter, não reescrever o pipeline.

---

## Índice

- [O que faz](#o-que-faz)
- [Quais listas](#quais-listas)
- [Domínio (glossário)](#domínio-glossário)
- [Arquitetura em 30s](#arquitetura-em-30s)
- [De quanto em quanto tempo roda](#de-quanto-em-quanto-tempo-roda)
- [Como usar](#como-usar)
- [Configuração](#configuração)
- [Particularidade real da OFAC (obtain)](#particularidade-real-da-ofac-obtain)
- [Versões e diff entre listas](#versões-e-diff-entre-listas)
- [Documentação completa](#documentação-completa)
- [Evidência: primeiro import real](#evidência-primeiro-import-real)

---

## O que faz

Um ciclo de ingestão, por lista, executa **seis estágios independentes de fonte**:

```
obtain -> validate -> transform -> version -> persist -> publish
```

- **obtain** — `HEAD` para detectar mudança (compara `Digest`); `GET` do snapshot completo só se mudou.
- **validate** — confere o SHA-256 contra o `Digest` anunciado (antes de parsear) e a boa-formação do XML.
- **transform** — parse em streaming (StAX), resolve referências, filtra escopo (só `Individual`/`Entity`), deduplica por `FixedRef`, normaliza.
- **version** — calcula a identidade `(Publish_Date, Digest)` e o `Expected_Count`.
- **persist** — grava o snapshot bruto como arquivo imutável + os registros como uma versão isolada e imutável no banco.
- **publish** — reconcilia a contagem, ativa `CURRENT` **atomicamente** e rotaciona a janela (CURRENT -> PREVIOUS -> N_MINUS_2 -> COLD).

Qualquer falha **antes** da ativação atômica deixa o `CURRENT` intacto (fail-closed); a próxima execução simplesmente reprocessa.

## Quais listas

| Source_List | Descrição | Endpoint (SLS Advanced XML) | Status |
| ----------- | --------- | --------------------------- | ------ |
| **SDN** | Specially Designated Nationals | `https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN_ADVANCED.XML` | ligado por padrão (`ofac.source.sdn.*`) |
| **CONSOLIDATED** | Consolidated (non-SDN) | idem, `CONS_ADVANCED.XML` | modelado (enum `SourceList.CONSOLIDATED`); wiring de fonte é futuro |
| UN / EU | Nações Unidas / União Europeia | — | previstos via novos adapters (UN sem token; EU com token). Não ligados. |

Cada `Source_List` tem sua **própria linha de versões**, independente das demais.

## Domínio (glossário)

- **Source_List** — uma lista da OFAC (SDN, Consolidated).
- **Version** — import imutável de um snapshot; identidade = (`Publish_Date`, `Digest` SHA-256). Duas publicações no mesmo dia com conteúdo diferente são versões distintas (o digest desempata).
- **CURRENT / PREVIOUS / N_MINUS_2** — as 3 versões operacionais (HOT) mais recentes de uma lista. A ativação repointa `CURRENT` atomicamente; versões deslocadas além de `N_MINUS_2` viram **COLD**.
- **Raw_Snapshot_Store** — pasta local versionada com o snapshot bruto (nome derivado de `Publish_Date`+`Digest`); **nunca** gravado no banco. Base para reconstrução fiel.
- **In_Scope_Records** — registros no escopo: apenas `Individual` e `Entity` (vessels e aircraft excluídos). É o que a API serve a partir de `CURRENT`.
- **Campos do registro** — cada registro traz nome principal, aliases (com **category** `strong`/`weak`), endereços, documentos, nacionalidade/cidadania, datas de nascimento, programas de sanção, e os campos promovidos **`title` / `placeOfBirth` / `gender`**. Todos os demais campos da lista (Phone, Email, Website, SWIFT/BIC, Digital Currency Address, D-U-N-S, etc.) são preservados numa lista tipada **`features[] = {type, value}`** para triagem/match, robusta a novos tipos da OFAC.
- **Data_Store** — PostgreSQL local: modelo interno + metadados de versão + ponteiros.

## Arquitetura em 30s

**Hexagonal (Ports & Adapters)** — dependências apontam só para dentro: `adapter -> application -> domain`. Garantida por um teste ArchUnit (`HexagonalArchitectureTest`) que roda no `check`.

```
com.spike.ofac
|-- domain/       # núcleo puro, sem framework (model, transform, version, scope)
|-- application/  # orquestração (Scheduler + obtain/persist/publish/retention) + portas (port.in/out)
`-- adapter/      # IO concreto + Spring (in.web, in.scheduling, out.persistence, out.source, config)
```

Detalhes em [`.kiro/steering/structure.md`](.kiro/steering/structure.md).

## De quanto em quanto tempo roda

- O `Scheduler` dispara **um ciclo por `Source_List`** a cada intervalo configurado (Spring `@Scheduled`, `fixedDelay` — sem sobreposição).
- **Padrão: a cada 6 horas.** Ajustável via `ofac.scheduler.interval`, **limitado a `[1m .. 1d]`** (validado na subida).
- Detecção de mudança é barata (`HEAD` + `Digest`): **só baixa/reprocessa quando a OFAC publica algo novo**; do contrário o ciclo termina como `SKIPPED_NO_CHANGE`, sem download.
- **Import sob demanda:** o profile `bootstrap` dispara um ciclo no startup (ver abaixo).

## Como usar

### Pré-requisitos

- **JDK 21**, Docker (PostgreSQL local + testes de integração).
- Stack: Kotlin + Spring Boot 3.3.5, Gradle (Kotlin DSL). Use o wrapper `./gradlew` (não precisa instalar Gradle).

### Rodar um import real localmente

1. **Suba o PostgreSQL** (db/usuário/senha = `ofac`, porta 5432):

   ```bash
   docker run -d --name ofac-pg \
     -e POSTGRES_DB=ofac -e POSTGRES_USER=ofac -e POSTGRES_PASSWORD=ofac \
     -p 5432:5432 postgres:16
   ```

2. **Aplique o schema:**

   ```bash
   docker exec -i ofac-pg psql -U ofac -d ofac < src/main/resources/db/schema.sql
   ```

3. **Rode um import de uma vez** (profile `bootstrap` — dispara um ciclo no startup):

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=bootstrap'
   ```

   Sem o profile `bootstrap`, a app sobe normal e o scheduler conduz a ingestão no intervalo configurado (padrão 6h).

Runbook detalhado em [`.kiro/steering/operations.md`](.kiro/steering/operations.md).

### Consultar um sancionado (API)

Somente leitura, serve **apenas a versão `CURRENT`**. Base: `http://localhost:8080`.

- **Listar** (paginado, ordenado por `fixedRef`):

  ```bash
  curl "http://localhost:8080/api/SDN/records?offset=0&limit=50"
  ```

- **Buscar por nome ou alias** (paginado; case-insensitive, *contains* sobre nome principal **e** apelidos, num só parâmetro `q`):

  ```bash
  curl "http://localhost:8080/api/SDN/records/search?q=ivan"
  ```

Resposta (`Page`): `{ "records": [...], "total": N, "offset": 0, "limit": 50 }`. Sem `CURRENT` ou sem match -> `200` com página vazia e `total: 0`. Erros de cliente (`400`): `q` ausente/vazio, paginação inválida (`limit` fora de `1..1000`, `offset` negativo), `sourceList` desconhecido.

**Contrato OpenAPI (spec-first):** o contrato curado `src/main/resources/static/openapi.yaml` é a **fonte de verdade** e **gera** a interface que o `QueryController` implementa (o código não compila se divergir do contrato). Com a app no ar: contrato em `GET /openapi.yaml`, **Swagger UI** em `/swagger-ui.html` (carrega o contrato curado). Referência completa em [`.kiro/docs/api-reference.md`](.kiro/docs/api-reference.md).

### Testes e build

```bash
./gradlew test            # unitários (exemplo) + ArchUnit
./gradlew propertyTest    # testes de propriedade (jqwik, 20 propriedades de correção)
./gradlew integrationTest # integração (Testcontainers PostgreSQL, MockWebServer)
./gradlew check           # test + propertyTest + integrationTest + ArchUnit + contrato OpenAPI
```

Guardas não funcionais (**opt-in**, fora do `check`): `./gradlew jmh`, `./gradlew gatlingRun` (precisa do app no ar), `./gradlew pitest`. Ver [`.kiro/steering/tech.md`](.kiro/steering/tech.md).

## Configuração

Em `src/main/resources/application.yml`, sobrescrevível por propriedade/variável de ambiente:

| Chave | Padrão | Descrição |
| ----- | ------ | --------- |
| `ofac.source.sdn.url` | endpoint SLS `SDN_ADVANCED.XML` | URL da SDN |
| `ofac.source.sdn.enabled` | `true` | liga/desliga o `Source_List` da SDN |
| `ofac.scheduler.interval` | `6h` | intervalo de polling, limitado a `[1m .. 1d]` |
| `ofac.raw-snapshot-store.folder` | `./data/raw-snapshot-store` | pasta do Raw_Snapshot_Store |
| `spring.datasource.url` / `username` / `password` | `.../ofac`, `ofac`, `ofac` | conexão do PostgreSQL local |

## Particularidade real da OFAC (obtain)

A OFAC anuncia o `Digest` **apenas no HEAD**. O `GET` **redireciona (302) para o S3** (GovCloud), cuja resposta final **não** repete o header `Digest` (chunked, sem `Content-Length`). Por isso o pipeline **carrega o digest do HEAD adiante** para o `validate`. O parser aceita o formato real `sha-256<hex>` (token colado ao hex, sem `=`), além de RFC-3230 base64 e hex puro. Sem isso, todo import real seria rejeitado com `ABSENT_DIGEST`.

## Versões e diff entre listas

O sistema **guarda** as versões (CURRENT/PREVIOUS/N_MINUS_2 + COLD retido), então os dados para comparar versões já coexistem no banco. **O diff "quem entrou / saiu / mudou" ainda não está implementado** — está projetado em [`.kiro/docs/versioning-and-diff.md`](.kiro/docs/versioning-and-diff.md).

## Documentação completa

| Documento | Para quê |
| --------- | -------- |
| [`.kiro/specs/ofac-sanctions-ingestion/requirements.md`](.kiro/specs/ofac-sanctions-ingestion/requirements.md) | Requisitos |
| [`.kiro/specs/ofac-sanctions-ingestion/design.md`](.kiro/specs/ofac-sanctions-ingestion/design.md) | Design técnico + 20 propriedades de correção |
| [`.kiro/specs/ofac-sanctions-ingestion/tasks.md`](.kiro/specs/ofac-sanctions-ingestion/tasks.md) | Plano de implementação |
| [`.kiro/docs/api-reference.md`](.kiro/docs/api-reference.md) | Referência da API + API-first/spec-first |
| [`.kiro/docs/versioning-and-diff.md`](.kiro/docs/versioning-and-diff.md) | Versões, frequência, proposta de diff |
| [`.kiro/steering/`](.kiro/steering/) | Guias: product, structure, tech, operations, conventions |
| [`spike-ofac.md`](spike-ofac.md) | Spike original (evidências que embasam o design) |

## Evidência: primeiro import real

Import real da SDN validado de ponta a ponta contra a URL pública da OFAC:

- Versão SDN `publish_date` **2026-08-28** ATIVADA como `CURRENT`.
- **17.439 registros** persistidos (**9.922 Entity + 7.517 Individual**).
- Contagens reconciliaram: `record_count = expected_count = persisted_count = 17439`, `out_of_scope = 0`, `overlap = 0`, `integrity_ok = true`.
- Snapshot bruto gravado em `data/raw-snapshot-store/<publish_date>_<digest>.xml`.
- Ciclo completo (download ~126 MB + parse + transform + persist + publish) em ~34s.

---

*Projeto desenvolvido com Kiro. Regras de arquitetura, testes e operação vivem em [`.kiro/steering/`](.kiro/steering/) e são aplicadas automaticamente ao trabalhar no repositório.*
