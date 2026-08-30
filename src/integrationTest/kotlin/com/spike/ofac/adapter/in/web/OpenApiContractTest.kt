package com.spike.ofac.adapter.`in`.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.yaml.snakeyaml.Yaml

/**
 * API-first **contract test**: the versioned `src/main/resources/openapi.yaml` is
 * the **source of truth**, and this test asserts the OpenAPI document springdoc
 * generates from the live, annotated code still matches it. If the code drifts
 * from the published contract (a new/renamed endpoint or param, a changed schema),
 * `check` fails here — the same fitness-function discipline as the ArchUnit test.
 *
 * The comparison is **semantic**, not string-equality: both documents are parsed
 * to YAML trees and the environment-specific `servers` block is stripped from the
 * generated one (the committed contract has none), so formatting/host differences
 * never cause false failures — only real structural drift does.
 *
 * Boots the full Spring context on a random port (springdoc must be active to
 * generate the doc), backed by a Testcontainers PostgreSQL so the persistence
 * beans wire. Docker-guarded: skips cleanly where Docker is unavailable, matching
 * the other integration tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var rest: TestRestTemplate

    @Test
    fun `generated OpenAPI document matches the versioned openapi_yaml source of truth`() {
        assumeTrue(dockerAvailable, "Docker not available — skipping OpenAPI contract test.")

        val generatedYaml = rest.getForObject(
            "http://localhost:$port/v3/api-docs.yaml",
            String::class.java,
        )!!

        val committedYaml = javaClass.classLoader
            .getResourceAsStream("openapi.yaml")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        val yaml = Yaml()
        val generated = normalize(yaml.load<Map<String, Any?>>(generatedYaml))
        val committed = normalize(yaml.load<Map<String, Any?>>(committedYaml))

        // Semantic equality of the OpenAPI trees (servers stripped from both).
        generated shouldBe committed
    }

    /**
     * Drops the environment-specific `servers` block so the committed contract
     * (which has none) and the generated doc (which springdoc fills with the live
     * host/port) compare equal on everything that actually defines the contract.
     */
    private fun normalize(doc: Map<String, Any?>): Map<String, Any?> =
        doc.filterKeys { it != "servers" }

    companion object {
        private val dockerAvailable: Boolean =
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

        private val postgres: PostgreSQLContainer<*>? =
            if (dockerAvailable) PostgreSQLContainer("postgres:16").apply { start() } else null

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            // Only wire the datasource when a container is up; when Docker is absent
            // the single test method assumeTrue-skips before using the context.
            postgres?.let { pg ->
                registry.add("spring.datasource.url") { pg.jdbcUrl }
                registry.add("spring.datasource.username") { pg.username }
                registry.add("spring.datasource.password") { pg.password }
            }
            // Keep the scheduled import from firing during the test.
            registry.add("ofac.source.sdn.enabled") { "false" }
        }
    }
}
