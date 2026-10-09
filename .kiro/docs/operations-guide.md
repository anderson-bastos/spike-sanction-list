# Guia do Operador — OFAC Sanctions Ingestion

Público: quem vai **operar** o sistema (subir, acompanhar os imports, consultar, diagnosticar problemas). Não exige conhecer o código. Para a visão geral e o glossário, veja o [README](../../README.md); para detalhes de arquitetura/design, veja `.kiro/specs/ofac-sanctions-ingestion/design.md`.

---

## 1. O que o sistema faz (resumo operacional)

A cada intervalo (padrão **6 horas**), o sistema verifica cada lista da OFAC e, **só se o conteúdo mudou**, baixa o snapshot, valida, transforma e publica uma nova **versão** como `CURRENT`. Uma **API somente leitura** serve a versão `CURRENT` para consulta (listagem e busca por nome/alias). Nada é apagado: as 3 versões mais recentes ficam quentes (HOT) e o snapshot bruto é guardado em arquivo.

**Pontos que o operador precisa saber:**
- Se a OFAC não publicou nada novo, o ciclo termina em `SKIPPED_NO_CHANGE` — isso é normal, não é erro.
- Se um ciclo falhar, a versão `CURRENT` **não é afetada** e o próximo ciclo tenta de novo.
- A consulta sempre reflete a última versão ativada (`CURRENT`).

---

## 2. Subir o sistema

### Pré-requisitos
- **JDK 21** e **Docker** instalados.

### Passo 1 — PostgreSQL
```bash
docker run -d --name ofac-pg \
  -e POSTGRES_DB=ofac -e POSTGRES_USER=ofac -e POSTGRES_PASSWORD=ofac \
  -p 5432:5432 postgres:16
```

### Passo 2 — aplicar o schema (só na primeira vez, ou após mudança de schema)
```bash
docker exec -i ofac-pg psql -U ofac -d ofac < src/main/resources/db/schema.sql
```

### Passo 3 — subir a aplicação

**Operação normal** (o scheduler conduz os imports no intervalo configurado):
```bash
./gradlew bootRun
```

**Com um import imediato** (dispara um ciclo no startup, útil para a primeira carga ou um import manual):
```bash
./gradlew bootRun --args='--spring.profiles.active=bootstrap'
```

A aplicação sobe na porta **8080**. Quando aparecer `Started OfacSanctionsIngestionApplicationKt`, está no ar.

> **Memória:** o parse do snapshot completo da SDN usa um pico de ~400 MB. Em máquinas apertadas, suba com `JAVA_TOOL_OPTIONS="-Xmx2g" ./gradlew bootRun`.

---

## 3. Credenciais e configuração

**Banco local (desenvolvimento):** host `localhost`, porta `5432`, database `ofac`, usuário `ofac`, senha `ofac` (JDBC: `jdbc:postgresql://localhost:5432/ofac`).

> Essas credenciais são triviais, só para uso local. Em ambiente compartilhado/produção, **não** use as do `application.yml` — forneça via variável de ambiente/secret (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`).

**Ajustes comuns** (`src/main/resources/application.yml`, sobrescrevíveis por variável de ambiente):

| Chave | Padrão | Para quê |
| ----- | ------ | -------- |
| `ofac.scheduler.interval` | `6h` | de quanto em quanto tempo verifica a OFAC (entre `1m` e `1d`) |
| `ofac.source.sdn.enabled` | `true` | liga/desliga a ingestão da SDN |
| `ofac.source.sdn.url` | endpoint SLS | de onde baixa o snapshot da SDN |
| `ofac.raw-snapshot-store.folder` | `./data/raw-snapshot-store` | onde ficam os snapshots brutos |

Exemplo — rodar verificando a cada 1 hora:
```bash
./gradlew bootRun --args='--ofac.scheduler.interval=1h'
```

---

## 4. Consultar os dados (API)

A API é somente leitura e serve a versão `CURRENT`. Base: `http://localhost:8080`. `{sourceList}` = `SDN` (ou `CONSOLIDATED`, quando habilitada).

- **Listagem paginada:**
  `GET /api/SDN/records?offset=0&limit=50`
- **Busca por nome ou alias** (um só parâmetro `q`, case-insensitive, "contém"):
  `GET /api/SDN/records/search?q=ivan`
- **Swagger UI** (explorar no navegador): `http://localhost:8080/swagger-ui.html`
- **Contrato OpenAPI:** `http://localhost:8080/openapi.yaml`

Cada registro traz nome, aliases (com **category** `strong`/`weak`), endereços, documentos, nacionalidade/cidadania, nascimento, programas, `title`/`placeOfBirth`/`gender`, e `features[]` (demais campos da lista para triagem/match). Detalhes de campos e exemplos: `.kiro/docs/api-reference.md`.

Paginação: `offset >= 0`, `limit` entre 1 e 1000. `q` vazio, paginação inválida ou lista desconhecida retornam **400**. Sem resultado → **200** com página vazia e `total: 0`.

---

## 5. Acompanhar os imports (o que olhar no log)

Cada ciclo registra uma linha por lista. Os três desfechos:

| No log aparece | Significa | Ação |
| -------------- | --------- | ---- |
| `Cycle for SDN activated version ...` | Nova versão importada e publicada como `CURRENT` | nada; sucesso |
| `Cycle for SDN skipped: no change` | OFAC não publicou novidade | nada; normal |
| `Cycle for SDN FAILED at stage X: <causa>` | Falhou no estágio X; `CURRENT` **intacto** | investigar a causa (seção 7); o próximo ciclo re-tenta |

Os estágios, em ordem, são: `OBTAIN -> VALIDATE -> TRANSFORM -> VERSION -> PERSIST -> PUBLISH`. A causa da falha sempre nomeia o estágio.

---

## 6. Verificar o estado no banco

Quantos registros há no `CURRENT` e qual versão está ativa:
```bash
docker exec ofac-pg psql -U ofac -d ofac -c \
  "SELECT source_list, publish_date, state, record_count, persisted_count, integrity_ok FROM versions ORDER BY ingested_at DESC;"
```

Ponteiros (qual versão é CURRENT/PREVIOUS/N_MINUS_2):
```bash
docker exec ofac-pg psql -U ofac -d ofac -c "SELECT * FROM pointers;"
```

Total de registros servidos:
```bash
docker exec ofac-pg psql -U ofac -d ofac -c "SELECT count(*) FROM records;"
```

---

## 7. Diagnóstico de problemas comuns

| Sintoma | Causa provável | O que fazer |
| ------- | -------------- | ----------- |
| App não sobe, erro de conexão com o banco | PostgreSQL não está no ar | `docker ps` para conferir o container `ofac-pg`; se parado, suba (seção 2, passo 1) |
| App não sobe, erro de tabela/coluna inexistente | schema não aplicado ou desatualizado | reaplique o schema (seção 2, passo 2) |
| Ciclo `FAILED at stage OBTAIN` | rede/indisponibilidade da OFAC, ou timeout | transitório; o próximo ciclo re-tenta. Se persistir, verifique conectividade com `sanctionslistservice.ofac.treas.gov` |
| Ciclo `FAILED at stage VALIDATE: ABSENT_DIGEST` | a origem não trouxe o digest esperado | ver "particularidade da OFAC" no README; se a OFAC mudou o formato, abrir chamado para o time |
| Consulta retorna `total: 0` para tudo | ainda não houve import bem-sucedido (sem `CURRENT`) | rode um import com o profile `bootstrap` (seção 2) e verifique o log |
| `OutOfMemoryError` ao importar | heap pequeno | suba com `JAVA_TOOL_OPTIONS="-Xmx2g"` |

> Regra de ouro: enquanto um problema é investigado, a versão `CURRENT` **continua servindo** normalmente — nenhuma falha de ciclo derruba a consulta.

---

## 8. Forçar um reimport (quando necessário)

Normalmente **não** é preciso: o sistema reimporta sozinho quando a OFAC publica. Mas se precisar reprocessar do zero (ex.: após mudança de schema), em ambiente local:

```bash
# 1. recria as tabelas (apaga os dados locais — dados são reimportáveis da OFAC)
docker exec ofac-pg psql -U ofac -d ofac -c "DROP TABLE IF EXISTS records, versions, pointers CASCADE;"
docker exec -i ofac-pg psql -U ofac -d ofac < src/main/resources/db/schema.sql
# 2. remove o snapshot bruto local (para re-baixar limpo)
rm -f data/raw-snapshot-store/*.xml
# 3. sobe com import imediato
./gradlew bootRun --args='--spring.profiles.active=bootstrap'
```

> Em produção isso seria uma migração de schema (ALTER TABLE) + reimport, nunca um DROP.

---

## 9. Parar / encerrar

- **Parar a aplicação:** `Ctrl+C` no terminal onde ela roda.
- **Parar o banco (mantendo os dados do container):** `docker stop ofac-pg` — e `docker start ofac-pg` para religar.
- **Remover o banco (apaga os dados):** `docker rm -f ofac-pg`.

---

## 10. Referências

- [README](../../README.md) — visão geral, listas, domínio.
- `.kiro/docs/api-reference.md` — referência completa da API (campos, exemplos, erros).
- `.kiro/docs/versioning-and-diff.md` — versões, frequência, proposta de diff entre versões.
- `.kiro/steering/` — guias de produto, estrutura, tecnologia, operação, convenções.
