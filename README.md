# OFAC Sanctions Ingestion

Pipeline de ingestao de listas de sancoes da **OFAC** (Office of Foreign Assets Control, do Tesouro dos EUA). Consulta periodicamente cada lista, baixa o snapshot completo **so quando o conteudo muda**, valida integridade (SHA-256) e boa-formacao do XML, transforma o *Advanced XML* em um modelo interno normalizado (escopo em pessoas e entidades), persiste como uma **versao imutavel** e -- apos reconciliar a contagem -- a **ativa atomicamente** como `CURRENT`. Mantem as 3 versoes operacionais mais recentes por lista (rollback instantaneo por ponteiro) e expoe uma **API de consulta somente leitura** (listagem paginada + busca por nome) sobre a versao `CURRENT`.

> **Status:** spike/MVP funcional. Import real da SDN ja validado de ponta a ponta (ver [Evidencia](#evidencia-primeiro-import-real)). Nucleo independente de fonte: adicionar UN/EU e escrever um adapter, nao reescrever o pipeline.

---

## Indice

- [O que faz](#o-que-faz)
- [Quais listas](#quais-listas)
- [Dominio (glossario)](#dominio-glossario)
- [Arquitetura em 30s](#arquitetura-em-30s)
- [De quanto em quanto tempo roda](#de-quanto-em-quanto-tempo-roda)
- [Como usar](#como-usar)
- [Configuracao](#configuracao)
- [Particularidade real da OFAC (obtain)](#particularidade-real-da-ofac-obtain)
- [Versoes e diff entre listas](#versoes-e-diff-entre-listas)
- [Documentacao completa](#documentacao-completa)
- [Evidencia: primeiro import real](#evidencia-primeiro-import-real)

---

## O que faz

Um ciclo de ingestao, por lista, executa **seis estagios independentes de fonte**:

```
obtain -> validate -> transform -> version -> persist -> publish
```

- **obtain** -- `HEAD` para detectar mudanca (compara `Digest`); `GET` do snapshot completo so se mudou.
- **validate** -- confere o SHA-256 contra o `Digest` anunciado (antes de parsear) e a boa-formacao do XML.
- **transform** -- parse em streaming (StAX), resolve referencias, filtra escopo (so `Individual`/`Entity`), deduplica por `FixedRef`, normaliza.
- **version** -- calcula a identidade `(Publish_Date, Digest)` e o `Expected_Count`.
- **persist** -- grava o snapshot bruto como arquivo imutavel + os registros como uma versao isolada e imutavel no banco.
- **publish** -- reconcilia a contagem, ativa `CURRENT` **atomicamente** e rotaciona a janela (CURRENT -> PREVIOUS -> N_MINUS_2 -> COLD).

Qualquer falha **antes** da ativacao atomica deixa o `CURRENT` intacto (fail-closed); a proxima execucao simplesmente reprocessa.

## Quais listas

| Source_List | Descricao | Endpoint (SLS Advanced XML) | Status |
| ----------- | --------- | --------------------------- | ------ |
| **SDN** | Specially Designated Nationals | `https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN_ADVANCED.XML` | ligado por padrao (`ofac.source.sdn.*`) |
| **CONSOLIDATED** | Consolidated (non-SDN) | idem, `CONS_ADVANCED.XML` | modelado (enum `SourceList.CONSOLIDATED`); wiring de fonte e futuro |
| UN / EU | Nacoes Unidas / Uniao Europeia | -- | previstos via novos adapters (UN sem token; EU com token). Nao ligados. |

Cada `Source_List` tem sua **propria linha de versoes**, independente das demais.

## Dominio (glossario)

- **Source_List** -- uma lista da OFAC (SDN, Consolidated).
- **Version** -- import imutavel de um snapshot; identidade = (`Publish_Date`, `Digest` SHA-256). Duas publicacoes no mesmo dia com conteudo diferente sao versoes distintas (o digest desempata).
- **CURRENT / PREVIOUS / N_MINUS_2** -- as 3 versoes operacionais (HOT) mais recentes de uma lista. A ativacao repointa `CURRENT` atomicamente; versoes deslocadas alem de `N_MINUS_2` viram **COLD**.
- **Raw_Snapshot_Store** -- pasta local versionada com o snapshot bruto (nome derivado de `Publish_Date`+`Digest`); **nunca** gravado no banco. Base para reconstrucao fiel.
- **In_Scope_Records** -- registros no escopo: apenas `Individual` e `Entity` (vessels e aircraft excluidos). E o que a API serve a partir de `CURRENT`.
- **Data_Store** -- PostgreSQL local: modelo interno + metadados de versao + ponteiros.

## Arquitetura em 30s

**Hexagonal (Ports & Adapters)** -- dependencias apontam so para dentro: `adapter -> application -> domain`. Garantida por um teste ArchUnit (`HexagonalArchitectureTest`) que roda no `check`.

```
com.spike.ofac
|-- domain/       # nucleo puro, sem framework (model, transform, version, scope)
|-- application/  # orquestracao (Scheduler + obtain/persist/publish/retention) + portas (port.in/out)
`-- adapter/      # IO concreto + Spring (in.web, in.scheduling, out.persistence, out.source, config)
```

Detalhes em [`.kiro/steering/structure.md`](.kiro/steering/structure.md).

## De quanto em quanto tempo roda

- O `Scheduler` dispara **um ciclo por `Source_List`** a cada intervalo configurado (Spring `@Scheduled`, `fixedDelay` -- sem sobreposicao).
- **Padrao: a cada 6 horas.** Ajustavel via `ofac.scheduler.interval`, **limitado a `[1m .. 1d]`** (validado na subida).
- Deteccao de mudanca e barata (`HEAD` + `Digest`): **so baixa/reprocessa quando a OFAC publica algo novo**; do contrario o ciclo termina como `SKIPPED_NO_CHANGE`, sem download.
- **Import sob demanda:** o profile `bootstrap` dispara um ciclo no startup (ver abaixo).

## Como usar

### Pre-requisitos

- **JDK 21**, Docker (PostgreSQL local + testes de integracao).
- Stack: Kotlin + Spring Boot 3.3.5, Gradle (Kotlin DSL). Use o wrapper `./gradlew` (nao precisa instalar Gradle).

### Rodar um import real localmente

1. **Suba o PostgreSQL** (db/usuario/senha = `ofac`, porta 5432):

   ```bash
   docker run -d --name ofac-pg \
     -e POSTGRES_DB=ofac -e POSTGRES_USER=ofac -e POSTGRES_PASSWORD=ofac \
     -p 5432:5432 postgres:16
   ```

2. **Aplique o schema:**

   ```bash
   docker exec -i ofac-pg psql -U ofac -d ofac < src/main/resources/db/schema.sql
   ```

3. **Rode um import de uma vez** (profile `bootstrap` -- dispara um ciclo no startup):

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=bootstrap'
   ```

   Sem o profile `bootstrap`, a app sobe normal e o scheduler conduz a ingestao no intervalo configurado (padrao 6h).

Runbook detalhado em [`.kiro/steering/operations.md`](.kiro/steering/operations.md).

### Consultar um sancionado (API)

Somente leitura, serve **apenas a versao `CURRENT`**. Base: `http://localhost:8080`.

- **Listar** (paginado, ordenado por `fixedRef`):

  ```bash
  curl "http://localhost:8080/api/SDN/records?offset=0&limit=50"
  ```

- **Buscar por nome** (case-insensitive, *contains* sobre nome principal + apelidos):

  ```bash
  curl "http://localhost:8080/api/SDN/records/search?q=ivan"
  ```

Resposta (`Page`): `{ "records": [...], "total": N, "offset": 0, "limit": 50 }`. Sem `CURRENT` ou sem match -> `200` com pagina vazia e `total: 0`. Erros de cliente (`400`): `q` ausente/vazio, paginacao invalida (`limit` fora de `1..1000`, `offset` negativo), `sourceList` desconhecido.

**Contrato OpenAPI (spec-first):** o contrato curado `src/main/resources/static/openapi.yaml` e a **fonte de verdade** e **gera** a interface que o `QueryController` implementa (o codigo nao compila se divergir do contrato). Com a app no ar: contrato em `GET /openapi.yaml`, **Swagger UI** em `/swagger-ui.html` (carrega o contrato curado). Referencia completa em [`.kiro/docs/api-reference.md`](.kiro/docs/api-reference.md).

### Testes e build

```bash
./gradlew test            # unitarios (exemplo) + ArchUnit
./gradlew propertyTest    # testes de propriedade (jqwik, 20 propriedades de correcao)
./gradlew integrationTest # integracao (Testcontainers PostgreSQL, MockWebServer)
./gradlew check           # test + propertyTest + integrationTest + ArchUnit + contrato OpenAPI
```

Guardas nao funcionais (**opt-in**, fora do `check`): `./gradlew jmh`, `./gradlew gatlingRun` (precisa do app no ar), `./gradlew pitest`. Ver [`.kiro/steering/tech.md`](.kiro/steering/tech.md).

## Configuracao

Em `src/main/resources/application.yml`, sobrescrevivel por propriedade/variavel de ambiente:

| Chave | Padrao | Descricao |
| ----- | ------ | --------- |
| `ofac.source.sdn.url` | endpoint SLS `SDN_ADVANCED.XML` | URL da SDN |
| `ofac.source.sdn.enabled` | `true` | liga/desliga o `Source_List` da SDN |
| `ofac.scheduler.interval` | `6h` | intervalo de polling, limitado a `[1m .. 1d]` |
| `ofac.raw-snapshot-store.folder` | `./data/raw-snapshot-store` | pasta do Raw_Snapshot_Store |
| `spring.datasource.url` / `username` / `password` | `.../ofac`, `ofac`, `ofac` | conexao do PostgreSQL local |

## Particularidade real da OFAC (obtain)

A OFAC anuncia o `Digest` **apenas no HEAD**. O `GET` **redireciona (302) para o S3** (GovCloud), cuja resposta final **nao** repete o header `Digest` (chunked, sem `Content-Length`). Por isso o pipeline **carrega o digest do HEAD adiante** para o `validate`. O parser aceita o formato real `sha-256<hex>` (token colado ao hex, sem `=`), alem de RFC-3230 base64 e hex puro. Sem isso, todo import real seria rejeitado com `ABSENT_DIGEST`.

## Versoes e diff entre listas

O sistema **guarda** as versoes (CURRENT/PREVIOUS/N_MINUS_2 + COLD retido), entao os dados para comparar versoes ja coexistem no banco. **O diff "quem entrou / saiu / mudou" ainda nao esta implementado** -- esta projetado em [`.kiro/docs/versioning-and-diff.md`](.kiro/docs/versioning-and-diff.md).

## Documentacao completa

| Documento | Para que |
| --------- | -------- |
| [`.kiro/specs/ofac-sanctions-ingestion/requirements.md`](.kiro/specs/ofac-sanctions-ingestion/requirements.md) | Requisitos |
| [`.kiro/specs/ofac-sanctions-ingestion/design.md`](.kiro/specs/ofac-sanctions-ingestion/design.md) | Design tecnico + 20 propriedades de correcao |
| [`.kiro/specs/ofac-sanctions-ingestion/tasks.md`](.kiro/specs/ofac-sanctions-ingestion/tasks.md) | Plano de implementacao |
| [`.kiro/docs/api-reference.md`](.kiro/docs/api-reference.md) | Referencia da API + API-first/spec-first |
| [`.kiro/docs/versioning-and-diff.md`](.kiro/docs/versioning-and-diff.md) | Versoes, frequencia, proposta de diff |
| [`.kiro/steering/`](.kiro/steering/) | Guias: product, structure, tech, operations, conventions |
| [`spike-ofac.md`](spike-ofac.md) | Spike original (evidencias que embasam o design) |

## Evidencia: primeiro import real

Import real da SDN validado de ponta a ponta contra a URL publica da OFAC:

- Versao SDN `publish_date` **2026-08-28** ATIVADA como `CURRENT`.
- **17.439 registros** persistidos (**9.922 Entity + 7.517 Individual**).
- Contagens reconciliaram: `record_count = expected_count = persisted_count = 17439`, `out_of_scope = 0`, `overlap = 0`, `integrity_ok = true`.
- Snapshot bruto gravado em `data/raw-snapshot-store/<publish_date>_<digest>.xml`.
- Ciclo completo (download ~126 MB + parse + transform + persist + publish) em ~34s.

---

*Projeto desenvolvido com Kiro. Regras de arquitetura, testes e operacao vivem em [`.kiro/steering/`](.kiro/steering/) e sao aplicadas automaticamente ao trabalhar no repositorio.*
