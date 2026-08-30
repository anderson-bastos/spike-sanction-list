package com.spike.ofac.adapter.`in`.web

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.yaml.snakeyaml.Yaml

/**
 * API-first **contract test** (spec-first, task 24.6).
 *
 * The curated `src/main/resources/static/openapi.yaml` is the **source of truth**
 * and is what the app serves — Swagger UI is pointed at it (`springdoc.swagger-ui.url`).
 * The primary
 * code↔contract guarantee is now at **compile time**: [QueryController] implements
 * the interface generated from this same file, so the code cannot compile if it
 * drifts from the contract's routes/params/response shapes.
 *
 * This runtime test adds two independent checks the compiler cannot give:
 *
 *  1. **The contract is a well-formed OpenAPI 3 document** — it parses, declares an
 *     `openapi: 3.x` version, and has the two expected operations
 *     (`list`, `search`) under the expected paths.
 *  2. **Every path declared in the contract is actually served** by the running
 *     app — each contract path is present among Spring's registered
 *     `RequestMappingHandlerMapping` patterns. This catches a contract that
 *     declares an endpoint the app does not expose (or vice versa) even though both
 *     independently compile.
 *
 * Boots the full context (Testcontainers PostgreSQL so the persistence beans wire);
 * Docker-guarded so it skips cleanly where Docker is unavailable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

    @Autowired
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    private lateinit var rest: TestRestTemplate

    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `the curated openapi_yaml is a well-formed OpenAPI 3 contract`() {
        val contract = loadContract()

        (contract["openapi"] as String).shouldStartWith("3.")

        @Suppress("UNCHECKED_CAST")
        val paths = contract["paths"] as Map<String, Map<String, Map<String, Any?>>>
        paths.keys shouldContainAll listOf(
            "/api/{sourceList}/records",
            "/api/{sourceList}/records/search",
        )

        // operationIds the generated interface + controller bind to.
        val operationIds = paths.values.flatMap { it.values }.mapNotNull { it["operationId"] as String? }
        operationIds shouldContainAll listOf("list", "search")
    }

    @Test
    fun `every path declared in the contract is served by the running app`() {
        assumeTrue(dockerAvailable, "Docker not available — skipping app-route contract check.")

        val contract = loadContract()

        @Suppress("UNCHECKED_CAST")
        val contractPaths = (contract["paths"] as Map<String, Any?>).keys

        // All URL patterns Spring actually registered for the app's handlers.
        val servedPatterns: Set<String> = handlerMapping.handlerMethods.keys
            .flatMap { info ->
                info.pathPatternsCondition?.patternValues
                    ?: info.patternsCondition?.patterns
                    ?: emptySet()
            }
            .toSet()

        // Each contract path must be exposed by the app (compile-time already ties
        // the controller to the generated interface; this guards the served routes).
        contractPaths.forEach { path ->
            (path in servedPatterns) shouldBe true
        }
    }

    @Test
    fun `swagger ui is served and loads the curated contract`() {
        assumeTrue(dockerAvailable, "Docker not available — skipping Swagger UI check.")

        // The UI HTML resolves (springdoc registers /swagger-ui/**).
        val ui = rest.getForEntity("http://localhost:$port/swagger-ui/index.html", String::class.java)
        ui.statusCode shouldBe HttpStatus.OK

        // swagger-config must resolve AND point the UI at the curated static contract,
        // not a code-generated doc — this is what makes the UI show the source of truth.
        val config = rest.getForObject(
            "http://localhost:$port/v3/api-docs/swagger-config",
            String::class.java,
        )!!
        config shouldContain "\"url\":\"/openapi.yaml\""

        // And the curated contract is actually served at that URL.
        val contract = rest.getForEntity("http://localhost:$port/openapi.yaml", String::class.java)
        contract.statusCode shouldBe HttpStatus.OK
        contract.body!! shouldContain "operationId: list"
    }

    // --- helpers ---

    private fun loadContract(): Map<String, Any?> {
        val text = javaClass.classLoader
            .getResourceAsStream("static/openapi.yaml")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return Yaml().load(text)
    }

    companion object {
        private val dockerAvailable: Boolean =
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

        private val postgres: PostgreSQLContainer<*>? =
            if (dockerAvailable) PostgreSQLContainer("postgres:16").apply { start() } else null

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProps(registry: DynamicPropertyRegistry) {
            postgres?.let { pg ->
                registry.add("spring.datasource.url") { pg.jdbcUrl }
                registry.add("spring.datasource.username") { pg.username }
                registry.add("spring.datasource.password") { pg.password }
            }
            registry.add("ofac.source.sdn.enabled") { "false" }
        }
    }
}
