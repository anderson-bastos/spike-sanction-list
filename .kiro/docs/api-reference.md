# API de Consulta — Referência (analista + desenvolvedor)

Propósito: descrever como **consultar um sancionado** na API real que roda hoje, e registrar a postura de **API-first** (atual e recomendada). Esta referência descreve o que já está implementado no `QueryController`/`PgQueryApi` — nada aqui é aspiracional.

> Escopo: a Query API é **somente leitura** e serve **exclusivamente a versão `CURRENT`** de cada `Source_List` (nunca `PREVIOUS`, `N_MINUS_2` ou `COLD`). Ela nunca altera versão, ponteiro ou registro.

---

## Visão para o analista (o essencial)

- **O que dá para perguntar:** "quais sancionados estão na lista SDN agora?" (listagem paginada) e "existe alguém cujo nome ou apelido contém _X_?" (busca por nome).
- **Sempre sobre a foto vigente:** toda resposta vem da versão `CURRENT` — a última importação ativada. Consultas históricas (versões anteriores) e "quem entrou/saiu" **não** são atendidas por esta API hoje (ver `versioning-and-diff.md`).
- **Busca por nome/alias** (`/records/search?q=`) é _contains_ (contém), **sem diferenciar maiúsculas/minúsculas**, e cobre o **nome principal e todos os apelidos (aliases)** em um só parâmetro `q` — paginada como a listagem. Ex.: `q=ivan` acha "Ivanov" (nome) e "Big Ivan" (alias). Não há endpoint separado por nome vs alias: a busca unificada já atende os dois.
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
  "title": "Chief Ideological Figure of ...",   // 0..1; cargo/título (FeatureType 26)
  "placeOfBirth": "Jabaliyah, Gaza Strip",       // 0..1 (FeatureType 9)
  "gender": "Male",                              // 0..1; "Male" | "Female" (FeatureType 224)
  "aliases": [ { "name": "Big Ivan", "type": "A.K.A.", "isPrimary": false, "category": "weak" } ],
  "addresses": [ { "raw": "Skořepka 1058/8 Staré Město", "country": "CZ", "parts": {} } ],
  "documents": [ { "type": "Passport", "number": "X12345", "issuer": "RU" } ],
  "nationalities": ["RU"],
  "citizenships": ["Mexico"],
  "birthDates": [ { "year": 1970, "month": null, "day": null, "period": null } ],
  "sanctionPrograms": ["SDGT"],         // 1..N — sempre ao menos um
  "features": [                          // 0..N — demais campos da lista, preservados p/ triagem/match
    { "type": "SWIFT/BIC", "value": "HAVIGB2L" },
    { "type": "Digital Currency Address - XBT", "value": "12QtD5BFwRsdNsAZY76UVE1xyCGNTojH9h" },
    { "type": "Phone Number", "value": "+55 11 5555-5555" }
  ],
  "remarks": ["..."],                   // apenas remarks reais (não mais o dump de features)
  "relationships": [ { "toFixedRef": "67890", "relationType": "linked-to" } ],
  "versionId": { "publishDate": "2026-08-28", "digest": "ec9b2e0c…<64 hex>" }
}
```

O campo `versionId` diz de **qual versão** o registro veio (a `CURRENT` no momento da leitura) — útil para auditoria e para casar com o `versioning-and-diff.md`.

**Campos promovidos e `features[]` (para o analista).** Todos os campos relevantes da lista da OFAC são persistidos e consultáveis:
- **`title`, `placeOfBirth`, `gender`** — campos próprios (0..1). `gender` sai como `Male`/`Female` (resolvido da tabela de referência da OFAC).
- **`aliases[].category`** — `strong` ou `weak` (a coluna *Category* da tela da OFAC; deriva de `LowQuality`). Um `weak` alias, sozinho, tipicamente não deve disparar match.
- **`features[]`** — lista tipada `{type, value}` que **preserva todos os demais campos** da lista (Phone, Email, Website, SWIFT/BIC, Digital Currency Address, D-U-N-S, Organization Type, Additional/Secondary sanctions info, etc.). É o catch-all consultável para triagem/match, robusto a novos tipos que a OFAC adicione. Campos de vessel/aircraft ficam fora (perfis fora de escopo). O snapshot bruto no `Raw_Snapshot_Store` continua preservando 100% do XML para reconstrução fiel.

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

## API-first: OpenAPI spec-first (IMPLEMENTADO)

A API adota **spec-first**: o contrato **gera** o código, e o código só compila enquanto adere ao contrato.

- **Fonte de verdade:** `src/main/resources/static/openapi.yaml` (OpenAPI 3), versionado e **curado à mão**. É a autoridade do contrato — não é mais um dump gerado pelo springdoc.
- **Geração de código (compile-time):** o **openapi-generator** (Gradle plugin, generator `kotlin-spring`, `interfaceOnly=true`, `useSpringBoot3=true`) gera, a partir do `openapi.yaml`, uma **interface Kotlin** (`QueryContractApi`) e os **DTOs** do contrato (em `com.spike.ofac.adapter.web.generated.*`). O `QueryController` **implementa essa interface** e mapeia o domínio (`Page`/`InternalModelEntry`) para os DTOs gerados via `QueryDtoMapper`. Se o contrato mudar (rota/param/shape), a interface muda e **o controller não compila** até se adequar — o `openapi.yaml` é a autoridade em tempo de compilação.
- **Contrato servido + Swagger UI:** a app serve o próprio `openapi.yaml` curado em `GET /openapi.yaml`, e a **Swagger UI** (`GET /swagger-ui.html`) é apontada para ele via `springdoc.swagger-ui.url=/openapi.yaml` — então a UI renderiza o **contrato curado** (a fonte de verdade), não um doc gerado do código. O springdoc fica com `api-docs.enabled=true` apenas para registrar os recursos da UI e o `swagger-config` que ela precisa para inicializar.
- **Testes de contrato (guardas no `check`):**
  - `OpenApiContractTest` (integrationTest): valida que o `openapi.yaml` é um OpenAPI 3 bem-formado (versão, paths, operationIds `list`/`search`) e que **toda rota declarada no contrato existe** entre os handlers registrados da app.
  - `QueryControllerHttpIntegrationTest` (integrationTest): sobe a app + PostgreSQL (Testcontainers), insere uma versão CURRENT real e exercita os endpoints **HTTP de verdade**, exigindo `200 application/json` com o shape dos DTOs gerados (mais os 400 de erro). É o teste que fecha a lacuna de serialização.

**Nota de content-type:** o contrato declara `application/json` nas respostas (corrigido de um `*/*` que vinha do dump inicial do springdoc e causava falha de serialização do DTO). Ao editar o `openapi.yaml`, mantenha `application/json`.

**Fluxo ao evoluir a API (spec-first):** edite o `openapi.yaml` curado → rode o build (o generator regenera a interface/DTOs; o controller precisa se adequar para compilar) → ajuste o `QueryController`/`QueryDtoMapper` → os testes de contrato validam. O `.yaml` é o que se publica.

> **Swagger UI:** disponível em `http://localhost:8080/swagger-ui.html` — carrega o contrato curado (`/openapi.yaml`). Um teste de integração (`OpenApiContractTest`) verifica que a UI sobe e que o `swagger-config` aponta para `/openapi.yaml`, evitando regressão silenciosa.

**Possível evolução futura (não implementada):** geração de **clientes** a partir do `openapi.yaml` via `openapi-generator`, e restrição da Swagger UI a um profile (ex.: só `dev`) conforme a postura de exposição.

## Estabilidade e versionamento da API

- **Não há versionamento de rota** (`/v1/...`) hoje. Se a API for exposta externamente, recomenda-se prefixo de versão antes do primeiro consumidor externo.
- O shape do `Page` e do `InternalModelEntry` reflete o modelo de domínio; mudanças nele são breaking-changes para consumidores e devem passar por contrato (ver API-first acima).
