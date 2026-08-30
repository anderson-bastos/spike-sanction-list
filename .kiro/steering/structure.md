# Estrutura

Propósito: explicar a arquitetura Hexagonal (Ports & Adapters), a regra de dependência e onde código novo deve ser colocado.

## Camadas e regra de dependência

O código está organizado em três camadas concêntricas. **As dependências apontam somente para dentro**: `adapter → application → domain`. A `application` **nunca** depende do `adapter` (fala apenas com suas próprias portas), e o `domain` é **livre de framework** (sem Spring, JDBC, Jackson ou cliente HTTP).

Essa regra é garantida pelo teste de arquitetura **ArchUnit** `HexagonalArchitectureTest` (no source set `test`, roda no `check`): quatro regras — camadas só dependem para dentro; domínio não depende de application/adapter; application não depende de adapter; domínio sem Spring/JDBC/Jackson/HTTP.

## Árvore de pacotes

```
com.spike.ofac
├── domain/                 # núcleo puro, sem framework
│   ├── model/              # VersionId, VersionMetadata, VersionPointers,
│   │                       #   ScopeConfig, RetentionPolicy, InternalModelEntry
│   ├── transform/          # AdvancedXmlStreamParser, ScopeFilter, CrossListDedup,
│   │                       #   ProfileEntryBuilder, Transform
│   ├── version/            # Validate, VersionStage
│   └── scope/              # ScopeConfigValidator
├── application/            # orquestração de casos de uso
│   ├── Scheduler.kt        #   (+ SourceListConfig, CycleOutcome)
│   ├── obtain/ persist/ publish/ retention/
│   └── port/
│       ├── in/             # QueryApi, Page, exceções
│       └── out/            # VersionStore (PointerKind), RawSnapshotStore,
│                           #   SourceAdapter (HeadResponse/HttpResponse/...)
└── adapter/                # IO concreto + wiring Spring
    ├── in/web/             # QueryController
    ├── in/scheduling/      # SchedulerTrigger, SchedulerConfiguration,
    │                       #   OfacSourceListWiring, BootstrapImportRunner
    ├── out/persistence/    # PgVersionStore, FsRawSnapshotStore,
    │                       #   InMemoryVersionStore, PgQueryApi
    ├── out/source/         # OfacAdapter, UnAdapter, EuAdapter,
    │                       #   SourceAdapterSupport, JdkHttpTransport
    └── config/             # RawSnapshotStoreProperties, SchedulerProperties
```

## Onde colocar código novo

- **Nova fonte de dados** (ex.: UN, EU): um novo adapter em `adapter.out.source` que implementa a porta `SourceAdapter`, mais um bean `SourceListConfig` (ao estilo de `OfacSourceListWiring`). Não altere os seis estágios do núcleo.
- **Nova lógica pura** (parsing, filtro de escopo, dedup, reconciliação, identidade de versão): em `domain`.
- **Nova orquestração** (coordenação de estágios / ciclo): em `application`.
- **Novas portas** (contratos): `application.port.in` (drivers, ex.: consulta) ou `application.port.out` (dependências, ex.: stores/fontes).

Mantenha a disciplina da regra de dependência — o ArchUnit falha o build se ela for invertida.

## Source sets do Gradle

- `main` — código de produção.
- `test` — testes unitários (exemplo) + o teste ArchUnit.
- `propertyTest` — testes baseados em propriedade (jqwik).
- `integrationTest` — integração (Testcontainers PostgreSQL, MockWebServer).
- `jmh` — microbenchmarks JMH (guarda não funcional, opt-in).
- `gatling` — testes de carga/latência da API (guarda não funcional, opt-in).
