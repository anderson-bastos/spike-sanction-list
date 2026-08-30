# Operações

Propósito: descrever como executar um import real localmente e os pontos de configuração.

## Rodar um import real localmente

1. **Suba o PostgreSQL** (Docker, `postgres:16`; db/usuário/senha = `ofac`, porta 5432):

   ```bash
   docker run -d --name ofac-pg \
     -e POSTGRES_DB=ofac -e POSTGRES_USER=ofac -e POSTGRES_PASSWORD=ofac \
     -p 5432:5432 postgres:16
   ```

2. **Aplique o schema:**

   ```bash
   docker exec -i ofac-pg psql -U ofac -d ofac < src/main/resources/db/schema.sql
   ```

3. **Rode um import de uma vez** (profile `bootstrap` — dispara um `scheduler.tick()` no startup):

   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=bootstrap'
   ```

Fora do profile `bootstrap`, o gatilho agendado conduz a ingestão no intervalo configurado (padrão 6h).

## Configuração (knobs)

Definidos em `src/main/resources/application.yml` e sobrescrevíveis por propriedade/variável de ambiente:

- `ofac.source.sdn.url` — endpoint SLS da SDN (padrão: `https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN_ADVANCED.XML`).
- `ofac.source.sdn.enabled` — habilita/desabilita o `Source_List` da SDN (padrão `true`).
- `ofac.scheduler.interval` — intervalo de polling (padrão `6h`, limitado a `[1m .. 1d]`).
- `ofac.raw-snapshot-store.folder` — pasta do Raw_Snapshot_Store (padrão `./data/raw-snapshot-store`).
- `spring.datasource.url` / `username` / `password` — conexão do PostgreSQL local.

## Particularidade real da OFAC (obtain)

A OFAC anuncia o `Digest` **apenas no HEAD**. O GET **redireciona (302) para o S3**, cuja resposta final **não** repete o header `Digest` (é chunked, sem `Content-Length`). Por isso o pipeline **carrega o digest do HEAD adiante** para o estágio `validate`. O parser de header aceita o formato real `sha-256<hex>` (token colado ao hex, sem `=`), além dos formatos RFC-3230 base64 e hex puro.

## Primeiro import verificado (evidência)

Versão SDN `publish_date` 2026-08-28 **ATIVADA**, **17.439 registros** persistidos (**9.922 Entity + 7.517 Individual**). Contagens reconciliaram: `record_count = expected_count = persisted_count = 17439`, `out_of_scope = 0`, `overlap = 0`, `integrity_ok = true`. Snapshot bruto gravado em `data/raw-snapshot-store/<publish_date>_<digest>.xml`, `CURRENT` resolvido.
