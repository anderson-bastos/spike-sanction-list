package com.spike.ofac.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Architecture fitness test enforcing the Hexagonal (Ports & Adapters) dependency
 * rule over the production code, so the layering we refactored to stays intact and
 * a future accidental import cannot silently invert it.
 *
 * The rule, from the inside out (dependencies point INWARD only):
 *
 * ```
 *   domain      <-  application  <-  adapter
 *   (pure core)     (use cases +     (concrete IO +
 *                    ports)           Spring wiring)
 * ```
 *
 *  - **domain** (`com.spike.ofac.domain..`) is the pure core: model, transform,
 *    version, scope. It must depend on NOTHING in `application` or `adapter`, and
 *    on no framework (no Spring, no JDBC, no Jackson, no HTTP client).
 *  - **application** (`com.spike.ofac.application..`) holds the use-case
 *    orchestration (the `Scheduler` and the obtain/persist/publish/retention
 *    steps) plus the **ports** (`application.port.in` / `application.port.out`).
 *    It may depend on `domain` but MUST NOT depend on `adapter` — the concrete
 *    adapters are plugged in from the outside, so the application only ever talks
 *    to its own port interfaces.
 *  - **adapter** (`com.spike.ofac.adapter..`) holds the driving adapters
 *    (`adapter.in.web`, `adapter.in.scheduling`) and driven adapters
 *    (`adapter.out.persistence`, `adapter.out.source`) plus Spring config
 *    (`adapter.config`). It may depend on both `application` and `domain`.
 *
 * These tests analyse only `src/main` production classes (test/jmh/gatling are
 * excluded), and only this project's own classes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HexagonalArchitectureTest {

    private lateinit var productionClasses: JavaClasses

    @BeforeAll
    fun importProductionClasses() {
        productionClasses = ClassFileImporter()
            // Analyse only main production bytecode — never the test/jmh/gatling sets.
            .withImportOption(ImportOption.DoNotIncludeTests())
            .withImportOption(ImportOption.DoNotIncludeJars())
            .importPackages("com.spike.ofac")
    }

    /**
     * The layered dependency rule: dependencies may only point inward
     * (adapter -> application -> domain), never outward.
     */
    @Test
    fun `hexagonal layers only depend inward`() {
        layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("com.spike.ofac.domain..")
            .layer("Application").definedBy("com.spike.ofac.application..")
            .layer("Adapter").definedBy("com.spike.ofac.adapter..")

            // Nothing inner may be accessed in a way that violates the direction:
            // - Domain may be accessed by Application and Adapter (and itself).
            // - Application may be accessed by Adapter (and itself).
            // - Adapter may be accessed by no one (it is the outermost ring).
            .whereLayer("Adapter").mayNotBeAccessedByAnyLayer()
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapter")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapter")

            .check(productionClasses)
    }

    /**
     * Belt-and-braces, phrased as a plain "no classes that ... should ..." rule so a
     * violation message is explicit: the domain core must not reach out to the
     * application or adapter rings.
     */
    @Test
    fun `domain does not depend on application or adapter`() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.spike.ofac.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.spike.ofac.application..", "com.spike.ofac.adapter..")
            .check(productionClasses)
    }

    /**
     * The application ring (use cases + ports) must not depend on any concrete
     * adapter. This is the core Ports & Adapters guarantee: the application talks
     * only to its own port interfaces, and the adapters are wired in from outside.
     */
    @Test
    fun `application does not depend on adapter`() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.spike.ofac.application..")
            .should().dependOnClassesThat()
            .resideInAPackage("com.spike.ofac.adapter..")
            .check(productionClasses)
    }

    /**
     * The pure domain must stay framework-free: no Spring, JDBC, Jackson, or HTTP
     * client leaking into the core. (Kotlin/JDK stdlib and the domain itself are
     * fine.) This keeps the domain unit-testable without any container/IO.
     */
    @Test
    fun `domain is free of framework and IO dependencies`() {
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
            .that().resideInAPackage("com.spike.ofac.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta.persistence..",
                "javax.persistence..",
                "com.fasterxml.jackson..",
                "java.sql..",
                "javax.sql..",
                "org.postgresql..",
                "java.net.http..",
            )
            .check(productionClasses)
    }
}
