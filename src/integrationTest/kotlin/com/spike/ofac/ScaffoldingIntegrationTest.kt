package com.spike.ofac

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Scaffolding-only integration test.
 *
 * Task 1 sets up the integration-test source set (Testcontainers PostgreSQL and
 * MockWebServer are on its classpath). This placeholder proves the source set
 * compiles and runs without requiring Docker or a database. Real DB/HTTP
 * integration tests (Testcontainers, MockWebServer) arrive in tasks 12-13.
 */
class ScaffoldingIntegrationTest {

    @Test
    fun integrationSourceSetIsWired() {
        (1 + 1) shouldBe 2
    }
}
