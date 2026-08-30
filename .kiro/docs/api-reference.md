# API de Consulta — Referência (analista + desenvolvedor)

Propósito: descrever como **consultar um sancionado** na API real que roda hoje, e registrar a postura de **API-first** (atual e recomendada). Esta referência descreve o que já está implementado no `QueryController`/`PgQueryApi` — nada aqui é aspiracional.

> Escopo: a Query API é **somente leitura** e serve **exclusivamente a versão `CURRENT`** de cada `Source_List` (nunca `PREVIOUS`, `N_MINUS_2` ou `COLD`). Ela nunca altera versão, ponteiro ou registro.

---

## Visão para o analista (o essencial)

- **O que dá para perguntar:** "quais sancionados estão na lista SDN agora?" (listagem paginada) e "existe alguém cujo nome ou apelido contém _X_?" (busca por nome).
- **Sempre sobre a foto vigente:** toda resposta vem da versão `CURRENT` — a última importação ativada. Consultas históricas (versões anteriores) e "quem entrou/saiu" **não** são atendidas por esta API hoje (ver `versioning-and-diff.md`).
- **Busca por nome** é _contains_ (contém), **sem diferenciar maiúsculas/minúsculas**, e cobre o **nome principal e todos os apelidos (aliases)**. Ex.: `q=ivan` acha "Ivanov" e um alias "Big Ivan".
- **Escopo dos dados:** apenas pessoas (`Individual`) e entidades (`Entity`). Embarcações (vessels) e aeronaves (aircraft) ficam fora por definição.

---

## Endpoints

Base: `http://<host>:8080` (porta padrão do Spring Boot).

O segmento `{sourceList}` aceita os valores do enum `SourceList`: **`SDN`** ou **`CONSOLIDATED`** (case-sensitive; um valor desconhecido é erro de cliente `400`).

### 1) Listagem paginada

```
GET /api/{sourceList}/records?offset={offset}&limit={limit}
```

| Parâmetro | Tipo | Padrão | Regras |
| --------- | ---- | ------ | ------ |
| `sourceList` (path) | enum | — | `SDN` \| `CONSOLIDATED` |
| `offset` (query) | int | `0` | `>= 0` |
| `limit` (query) | int | `50` | `> 0` e `<= 1000` |

Ordenação **determinística e estável por `fixed_ref`** — a mesma página, pedida de novo com o mesmo `offset`/`limit`, retorna os mesmos registros na mesma ordem.

### 2) Busca por nome

```
GET /api/{sourceList}/records/search?q={termo}&offset={offset}&limit={limit}
```

| Parâmetro | Tipo | Padrão | Regras |
| --------- | ---- | ------ | ------ |
| `q` (query) | string | — | **obrigatório e não vazio** |
| `offset` (query) | int | `0` | `>= 0` |
| `limit` (query) | int | `50` | `> 0` e `<= 1000` |

Casa `q` (case-insensitive, _contains_) contra **`primary_name` OU qualquer alias**. Metacaracteres de `LIKE` (`%`, `_`) são escapados, então `q=50%` casa o literal "50%". Mesma paginação, limites, ordenação e metadados da listagem.

---

## Formato da resposta — `Page`

`200 OK` com corpo JSON:

```json
{
  "records": [ /* InternalModelEntry[] — a fatia desta página, ordenada por fixed_ref */ ],
  "total":  17439,   // total de registros que casam no CURRENT (não só nesta página)
  "offset": 0,
  "limit":  50
}
```

- `total` é a contagem completa dos registros que casam na versão `CURRENT` — o cliente calcula quantas páginas existem.
- Quando **nada casa**, ou a lista **ainda não tem `CURRENT`**, a resposta é `200 OK` com `records: []` e `total: 0` — **não** é erro.

### `InternalModelEntry` (registro de um sancionado)

```json
{
  "fixedRef": "12345",
  "entityType": "Individual",           // "Individual" | "Entity" (apenas in-scope)
  "primaryName": "Ivan Ivanov",
  "aliases": [ { "name": "Big Ivan", "type": "aka", "isPrimary": false } ],
  "addresses": [ { "raw": "Skořepka 1058/8 Staré Město", "country": "CZ", "parts": {} } ],
  "documents": [ { "type": "Passport", "number": "X12345", "issuer": "RU" } ],
  "nationalities": ["RU"],
  "citizenships": ["RU"],
  "birthDates": [ { "year": 1970, "month": null, "day": null, "period": null } ],
  "sanctionPrograms": ["SDGT"],         // 1..N — sempre ao menos um
  "remarks": ["..."],
  "relationships": [ { "toFixedRef": "67890", "relationType": "linked-to" } ],
  "versionId": { "publishDate": "2026-08-28", "digest": "ec9b2e0c…<64 hex>" }
}
```

O campo `versionId` diz de **qual versão** o registro veio (a `CURRENT` no momento da leitura) — útil para auditoria e para casar com o `versioning-and-diff.md`.

---

## Erros (todos `400 Bad Request`)

Corpo: `{ "error": "<mensagem>" }`.

| Situação | Requisito | Exemplo de causa |
| -------- | --------- | ---------------- |
| `q` ausente ou em branco na busca | 16.7 | `/records/search` sem `q`, ou `q=`+espaços |
| Paginação fora dos limites | 16.8 | `offset` negativo, `limit <= 0`, `limit > 1000` |
| `offset`/`limit` não numéricos | 16.8 | `?limit=abc` (falha na conversão do Spring) |
| `{sourceList}` desconhecido | — | `/api/FOO/records` |

Leituras nunca produzem `5xx` por dado ausente: sem `CURRENT` → página vazia com `total: 0` (`200`).

---

## Exemplos (`curl`)

Consultar por nome um sancionado na SDN:

```bash
curl -s "http://localhost:8080/api/SDN/records/search?q=ivan&limit=20" | jq
```

Primeira página da SDN inteira:

```bash
curl -s "http://localhost:8080/api/SDN/records?offset=0&limit=50" | jq '.total, (.records | length)'
```

Paginar (página 3, tamanho 100):

```bash
curl -s "http://localhost:8080/api/SDN/records?offset=200&limit=100" | jq
```

Erro esperado (busca sem termo):

```bash
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8080/api/SDN/records/search"   # 400
```

> Para subir a app e ter dados no `CURRENT`, veja o runbook em `operations.md` (Postgres local + profile `bootstrap`).

---

## Como a busca funciona por baixo (para o desenvolvedor)

- `PgQueryApi` resolve o ponteiro `CURRENT` via `VersionStore` e escopa **toda** consulta por `(publish_date, digest)` — garante servir só a `CURRENT` e observar a ativação atomicamente (lê tudo numa transação `@Transactional(readOnly = true)`, então uma ativação concorrente é vista como "antiga" ou "nova", nunca misturada).
- A busca usa duas colunas derivadas e minusculizadas na tabela `records`, com índices **GIN trigram** (`pg_trgm`): `primary_name_lower` e `alias_search` (todos os aliases concatenados). O predicado é `col LIKE '%needle%' ESCAPE '\'` em ambas.
- `total` vem de um `COUNT(*)` escopado; a página vem de um `SELECT ... ORDER BY fixed_ref LIMIT :limit OFFSET :offset`.

---

## API-first: OpenAPI (IMPLEMENTADO)

A API adota **spec-first (API-first)** com contrato versionado como fonte de verdade e verificação automática contra o código:

- **Fonte de verdade:** `src/main/resources/openapi.yaml` (OpenAPI 3) versionado no repositório. É a autoridade do contrato.
- **springdoc em runtime:** a app expõe o contrato gerado a partir do código anotado (`QueryController` + o bean `OpenApiConfiguration`) em:
  - `GET /v3/api-docs` (JSON) e `GET /v3/api-docs.yaml` (YAML)
  - **Swagger UI** em `GET /swagger-ui.html` — para explorar e testar a API viva.
- **Teste de contrato (guarda no `check`):** `OpenApiContractTest` (source set `integrationTest`) sobe a app, busca `/v3/api-docs.yaml`, parseia tanto o gerado quanto o `openapi.yaml` versionado como árvore YAML (ignorando o bloco `servers`, específico de ambiente) e **compara semanticamente**. Se o código divergir do contrato (endpoint/param novo ou renomeado, schema alterado), o **build falha** — a mesma disciplina de fitness function do teste de arquitetura ArchUnit.

**Fluxo ao evoluir a API:** altere as anotações no código, rode a app e capture o doc gerado (`curl -s localhost:8080/v3/api-docs.yaml`), atualize o `openapi.yaml` versionado (removendo o bloco `servers`), e confirme que o `OpenApiContractTest` passa. O `.yaml` é o que se publica para consumidores.

> Explorar o contrato localmente: suba a app (ver `operations.md`) e acesse `http://localhost:8080/swagger-ui.html`.

**Possível evolução futura (não implementada):** geração de **clientes**/DTOs a partir do `openapi.yaml` via `openapi-generator`, e restrição do Swagger UI a um profile (ex.: só `dev`) por postura de exposição. Hoje o Swagger UI fica habilitado por padrão.

## Estabilidade e versionamento da API

- **Não há versionamento de rota** (`/v1/...`) hoje. Se a API for exposta externamente, recomenda-se prefixo de versão antes do primeiro consumidor externo.
- O shape do `Page` e do `InternalModelEntry` reflete o modelo de domínio; mudanças nele são breaking-changes para consumidores e devem passar por contrato (ver API-first acima).
