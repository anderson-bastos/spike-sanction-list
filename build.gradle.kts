import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    // kapt drives the JMH annotation processor over the Kotlin `jmh` source set
    // (task 22.1) so JMH's benchmark metadata is generated at compile time.
    kotlin("kapt") version "1.9.25"
    // Mutation testing (task 23): non-functional test-effectiveness guard over the
    // pure-logic packages. See the `pitest { }` block below for the scoped config.
    id("info.solidsoft.pitest") version "1.15.0"
    // HTTP load/latency testing (task 22.2): the Gatling Gradle plugin drives a
    // NON-FUNCTIONAL performance guard over the Query API list + name-search
    // endpoints. It brings its own isolated `gatling` source set
    // (`src/gatling/kotlin`) and a `gatlingRun` task, kept OUT of `test` / `check`
    // because a load run is slow and needs a running server. See the notes on the
    // `gatlingRun` wiring at the bottom of this file.
    id("io.gatling.gradle") version "3.15.1.3"
}

group = "com.spike.ofac"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

// ---------------------------------------------------------------------------
// Version constants (single source of truth for tooling versions)
// ---------------------------------------------------------------------------
val jqwikVersion = "1.9.1"
val kotestVersion = "5.9.1"
val mockkVersion = "1.13.13"
val testcontainersVersion = "1.20.4"
val mockwebserverVersion = "4.12.0"

// ArchUnit — architecture fitness test enforcing the Hexagonal (Ports &
// Adapters) dependency rule (domain <- application <- adapter). Runs in the
// normal `test` source set so `check` fails on a layering violation.
val archUnitVersion = "1.3.0"

// JMH (Java Microbenchmark Harness) — the parse+transform performance guard
// (task 22.1). Kept in its own source set/task, out of the normal test suite.
val jmhVersion = "1.37"

// PIT mutation-testing tool + its JUnit-Platform plugin (jqwik property tests and
// JUnit 5 example tests both run on the JUnit Platform, so PIT needs this plugin
// to discover and drive them). Pinned together for compatibility.
val pitestToolVersion = "1.15.3"
val pitestJUnit5PluginVersion = "1.2.1"

// ---------------------------------------------------------------------------
// Additional test source sets: `propertyTest` and `integrationTest`.
// The default `test` source set holds unit (example) tests. Property-based
// tests (jqwik) live in `propertyTest`; DB/HTTP integration tests live in
// `integrationTest`. Each set has its own `src/<name>/kotlin` + `resources`.
// All three see the main classpath and the base `test` classpath (so shared
// fixtures/helpers under src/test are reusable).
// ---------------------------------------------------------------------------
sourceSets {
    create("propertyTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
    // -----------------------------------------------------------------------
    // `jmh` — the Java Microbenchmark Harness source set (task 22.1).
    //
    // A NON-FUNCTIONAL performance guard over the parse+transform hotspot
    // (Req 4, the code under measurement — NOT a functional requirement). It is
    // deliberately its OWN source set + task and is NOT wired into `test` /
    // `check` because a JMH run is slow (multiple forks × warmup + measurement
    // iterations). It sees the `main` output (the real `AdvancedXmlStreamParser`
    // + `Transform`) and the base `test` output (reuses `testsupport.Fixtures`
    // to locate the real `ofac-data/sdn_advanced.xml` fixture).
    // -----------------------------------------------------------------------
    create("jmh") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
}

val propertyTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations["testImplementation"])
}
configurations["propertyTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

val integrationTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations["testImplementation"])
}
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

// The `jmh` set reuses the base `test` classpath so it can use `testsupport.Fixtures`.
val jmhImplementation: Configuration by configurations.getting {
    extendsFrom(configurations["testImplementation"])
}
configurations["jmhRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    // --- Spring Boot: Web (Query_API) + Spring Data JDBC (Postgres access) ---
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // --- Jackson Kotlin module: serialize the Internal_Model multi-valued
    //     attributes to JSONB (data-class aware, honours default args). Managed
    //     version comes from the Spring Boot dependency BOM. ---
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // --- PostgreSQL JDBC driver ---
    runtimeOnly("org.postgresql:postgresql")

    // --- Example / unit tests: JUnit 5, kotest assertions, MockK ---
    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        // jqwik brings its own JUnit platform engine; keep vintage out.
        exclude(group = "org.junit.vintage", module = "junit-vintage-engine")
    }
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")

    // --- Property-based testing: jqwik (incl. stateful/model-based mode) ---
    testImplementation("net.jqwik:jqwik:$jqwikVersion")
    testImplementation("net.jqwik:jqwik-kotlin:$jqwikVersion")

    // --- Testcontainers (PostgreSQL) for persistence integration tests ---
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:postgresql:$testcontainersVersion")

    // --- HTTP obtain tests: MockWebServer (OkHttp) ---
    testImplementation("com.squareup.okhttp3:mockwebserver:$mockwebserverVersion")
    // okhttp-tls provides HeldCertificate / HandshakeCertificates so MockWebServer
    // can serve the obtain HTTPS GET over a self-signed cert the test client trusts
    // (task 18.3, Req 2.1). Test-only.
    testImplementation("com.squareup.okhttp3:okhttp-tls:$mockwebserverVersion")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // --- ArchUnit: Hexagonal dependency-rule fitness test (JUnit 5 flavour) ---
    testImplementation("com.tngtech.archunit:archunit-junit5:$archUnitVersion")

    // --- JMH: parse+transform microbenchmark (task 22.1, non-functional guard) ---
    // Core annotations + runner API, plus the annotation processor (driven by
    // kapt against the `jmh` source set) that generates the benchmark metadata.
    "jmhImplementation"("org.openjdk.jmh:jmh-core:$jmhVersion")
    "kaptJmh"("org.openjdk.jmh:jmh-generator-annprocess:$jmhVersion")
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// ---------------------------------------------------------------------------
// Test task wiring. The default `test` task runs unit tests with JUnit 5.
// jqwik hooks into the JUnit Platform, so property tests use the same engine.
// ---------------------------------------------------------------------------
tasks.named<Test>("test") {
    useJUnitPlatform()
}

val propertyTest = tasks.register<Test>("propertyTest") {
    description = "Runs property-based tests (jqwik)."
    group = "verification"
    testClassesDirs = sourceSets["propertyTest"].output.classesDirs
    classpath = sourceSets["propertyTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter("test")
}

val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs integration tests (Testcontainers PostgreSQL, MockWebServer)."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter("test")
}

tasks.named("check") {
    dependsOn(propertyTest, integrationTest)
}

// ---------------------------------------------------------------------------
// JMH benchmark task (task 22.1 — NON-FUNCTIONAL performance guard, NOT a
// functional requirement). Runs the JMH runner over the compiled `jmh` source
// set, which microbenchmarks the StAX parse + transform over the real
// `ofac-data/sdn_advanced.xml` fixture (19,249 profiles) — the ~98%-of-cost
// hotspot the spike measured (Req 4 is the code under measurement).
//
// Intentionally NOT a dependency of `test` / `check`: a JMH run does multiple
// forks × warmup + measurement iterations over a ~120 MB fixture and is slow.
// It must stay opt-in: `./gradlew jmh`. The spike's `benchmark.py` (~3.9 s
// processing, ~402 MB peak) is the reference baseline for the results.
// ---------------------------------------------------------------------------
tasks.register<JavaExec>("jmh") {
    description = "Runs the JMH parse+transform microbenchmark (slow; opt-in, not in `check`)."
    group = "verification"
    dependsOn(tasks.named("jmhClasses"))
    mainClass.set("org.openjdk.jmh.Main")
    classpath = sourceSets["jmh"].runtimeClasspath
    // Forward any `-PjmhArgs="..."` (e.g. a benchmark include regex or `-f 1`)
    // straight through to the JMH runner; default runs all discovered benchmarks.
    (findProperty("jmhArgs") as String?)?.let { args(it.split(" ").filter(String::isNotBlank)) }
}

// ---------------------------------------------------------------------------
// Mutation testing (task 23 — non-functional test-effectiveness guard).
//
// PITest (https://pitest.org) measures whether the suite actually *kills* injected
// defects, beyond line coverage. This is a guard, not a correctness property: it
// validates that the 20 property tests + example tests catch real mutations.
//
// SCOPE — pure-logic packages only (the ones the properties 1–16, 18–20 cover):
//   * transform / scope filter / dedup  → pipeline.stages.transform.*
//   * count reconciliation              → pipeline.stages.VersionStage, .publish.*
//   * version-identity + pointer types  → pipeline.models.*
//   * version-pointer state machine     → pipeline.store.InMemoryVersionStore
//     (the pure in-memory reference store the stateful pointer properties drive)
//   * scope-config validation           → pipeline.config.*
//
// The I/O / adapter packages are deliberately kept OUT of scope (real-integration
// behaviour + Kotlin equivalent mutants add noise there): HTTP adapters
// (pipeline.adapters.*), the Postgres/filesystem stores (store.PgVersionStore,
// store.FsRawSnapshotStore, store.Pg*), the Query API web layer (pipeline.query.*),
// the scheduler, retention, and the obtain/persist/publish *I/O* stages.
//
// NOTE (task 23 follow-up): a Kotlin-aware PIT plugin (to suppress Kotlin-generated
// null-check / synthetic "equivalent" mutants) is intentionally NOT wired here yet.
// The free upstream `pitest-kotlin` targets a much older PIT and is incompatible
// with the modern PIT required for jqwik/JUnit5 discovery; the maintained
// arcmutate/groupcdg Kotlin plugin needs a licence key. Left as a documented
// follow-up so this config resolves and runs with zero extra setup.
// ---------------------------------------------------------------------------
pitest {
    pitestVersion.set(pitestToolVersion)

    // jqwik + JUnit 5 both run on the JUnit Platform; this plugin lets PIT drive them.
    junit5PluginVersion.set(pitestJUnit5PluginVersion)

    // Run mutation analysis against BOTH the example (`test`) and property (`propertyTest`)
    // suites — those are the tests that exercise the pure-logic packages. The
    // `integrationTest` suite (Testcontainers/MockWebServer) is excluded: it is slow
    // and covers I/O behaviour outside the mutated scope.
    testSourceSets.set(listOf(sourceSets["test"], sourceSets["propertyTest"]))
    mainSourceSets.set(listOf(sourceSets["main"]))

    // Mutate only the pure-logic classes the properties cover.
    targetClasses.set(
        listOf(
            "com.spike.ofac.domain.transform.*", // transform, scope filter, dedup
            "com.spike.ofac.application.publish.*",    // count-reconciliation gate + pointer decision
            "com.spike.ofac.domain.version.VersionStage*", // reconciliation formula (expected_count)
            "com.spike.ofac.domain.model.*",            // version-identity + version-pointer value types
            "com.spike.ofac.domain.scope.*",            // scope-config validation
            "com.spike.ofac.adapter.out.persistence.InMemoryVersionStore*", // version-pointer state machine (reference store)
        )
    )

    // Belt-and-braces: keep the I/O / adapter classes out even though they are not
    // matched above, so future package moves don't silently pull them into scope.
    excludedClasses.set(
        listOf(
            "com.spike.ofac.adapter.out.source.*",
            "com.spike.ofac.application.port.out.*",
            "com.spike.ofac.application.port.in.*",
            "com.spike.ofac.adapter.in.web.*",
            "com.spike.ofac.adapter.in.scheduling.*",
            "com.spike.ofac.application.Scheduler*",
            "com.spike.ofac.application.retention.*",
            "com.spike.ofac.application.obtain.*",
            "com.spike.ofac.application.persist.*",
            "com.spike.ofac.adapter.out.persistence.PgVersionStore*",
            "com.spike.ofac.adapter.out.persistence.PgQueryApi*",
            "com.spike.ofac.adapter.out.persistence.FsRawSnapshotStore*",
        )
    )

    // Only the tests that exercise the pure-logic scope need to run per mutant.
    targetTests.set(listOf("com.spike.ofac.*"))

    useClasspathFile.set(true)
    timestampedReports.set(false)

    // -----------------------------------------------------------------------
    // Mutation-score threshold gate (task 23.2 — non-functional
    // test-effectiveness gate over the pure-logic packages only).
    //
    // This fails the build when the suite kills fewer than `mutationThreshold`%
    // of the injected mutants over the SCOPED classes above (transform / scope
    // filter / dedup, count reconciliation, version-identity + pointer value
    // types, the in-memory version-pointer state machine, and scope-config
    // validation). The I/O / adapter packages are already kept OUT of scope via
    // `targetClasses` + `excludedClasses`, so this gate applies to the pure
    // logic only — real-integration behaviour and Kotlin equivalent mutants add
    // noise elsewhere and must NOT flake this gate.
    //
    // INITIAL, deliberately CONSERVATIVE values. Task 23.1 documented that the
    // Kotlin-aware PIT plugin (which would suppress Kotlin-generated null-check /
    // synthetic "equivalent", unkillable mutants) is deferred (version conflict /
    // licence). Until that lands, a handful of surviving equivalent mutants are
    // expected, so a high threshold would produce a flaky gate. Start at 70%
    // mutation kill / 80% line coverage as a floor that the 20 property tests +
    // example tests comfortably clear, and ratchet upward once the Kotlin plugin
    // removes the equivalent-mutant noise.
    mutationThreshold.set(70)
    coverageThreshold.set(80)
}

// ---------------------------------------------------------------------------
// HTTP load/latency testing — Gatling (task 22.2 — NON-FUNCTIONAL performance
// guard, NOT a functional requirement).
//
// The `io.gatling.gradle` plugin (applied above) contributes an isolated
// `gatling` source set at `src/gatling/kotlin` plus a `gatlingRun` task. The
// simulation there load- and latency-tests the two Query API endpoints
// (`design.md` "Performance testing" → "HTTP load/latency (Gatling)"):
//   * GET /api/{sourceList}/records              — paginated list (Req 16.1)
//   * GET /api/{sourceList}/records/search?q=...  — case-insensitive *contains*
//     name search over primary name + aliases (Req 16.3), the path that leans on
//     the trigram/GIN indexes (task 13.1).
//
// The latency bar is framed against the atomic-activation SLA context — a new
// `CURRENT` must be resolvable within 5 s (Req 9.5) — so a read served through
// the `CURRENT` pointer should sit comfortably inside that budget under load.
//
// Intentionally OPT-IN and kept OUT of `test` / `check`: a load run is slow and
// needs a running server (unlike the pure-logic suites). Run it explicitly
// against a started app:
//
//   ./gradlew gatlingRun \
//     -Dgatling.baseUrl=http://localhost:8080 \
//     -Dgatling.sourceList=SDN \
//     -Dgatling.searchTerm=IVAN
//
// (k6 is an equivalent alternative noted in the design; Gatling is the primary
// tool here.) The simulation reads its target host / list / search term / load
// shape from `gatling.*` system properties with safe local defaults, so
// `gatlingRun` compiles and registers with zero extra setup; only an actual
// load run needs the server up.
// ---------------------------------------------------------------------------
// No extra configuration block is required: the plugin's defaults (JVM
// simulations under `src/gatling/kotlin`, reports under `build/reports/gatling`)
// match the conventions used by the other opt-in guards in this build.
