# Tecnologia

Propósito: registrar a stack técnica e os comandos comuns do projeto.

## Stack

- **Linguagem:** Kotlin (JVM), toolchain **JDK 21**.
- **Framework:** Spring Boot 3.3.5 (Spring Web para a Query API; Spring Data JDBC para PostgreSQL; `@Scheduled` para o scheduler).
- **Build:** Gradle com Kotlin DSL (`build.gradle.kts`).
- **Banco de dados:** PostgreSQL (Data_Store: modelo interno + metadados de versão + ponteiros). Schema em `src/main/resources/db/schema.sql`.
- **Parsing XML:** StAX (`javax.xml.stream.XMLStreamReader`) — parse em streaming, com memória limitada.
- **Serialização:** Jackson (módulo Kotlin) para atributos multivalorados em JSONB.

## Testes e qualidade

- **jqwik** — testes baseados em propriedade (inclui modo stateful/model-based).
- **JUnit 5 + kotest** (asserções) — testes de exemplo/unitários.
- **MockK** — mocking de colaboradores em testes unitários.
- **Testcontainers (PostgreSQL)** — integração de persistência contra um Postgres real.
- **MockWebServer** — testes de `obtain` (HEAD/GET).
- **ArchUnit** — teste de arquitetura (regra de dependência Hexagonal), roda no `check`.
- **JMH** — microbenchmark de parse+transform (guarda não funcional).
- **Gatling** — carga/latência da Query API (guarda não funcional).
- **PITest** — teste de mutação sobre os pacotes de lógica pura (efetividade da suíte).

## Comandos comuns

```bash
./gradlew test            # testes unitários (exemplo) + ArchUnit
./gradlew propertyTest    # testes de propriedade (jqwik)
./gradlew integrationTest # integração (Testcontainers + MockWebServer)
./gradlew check           # test + propertyTest + integrationTest + ArchUnit
./gradlew pitest          # teste de mutação (lógica pura)
./gradlew jmh             # microbenchmark parse+transform
./gradlew gatlingRun      # carga/latência da Query API (precisa do app no ar)
```

## Nota

As guardas **não funcionais** (`jmh`, `gatlingRun`, `pitest`) são **opt-in** e ficam **fora do `check`**: são lentas e/ou exigem um servidor no ar. Rode-as explicitamente quando necessário.
