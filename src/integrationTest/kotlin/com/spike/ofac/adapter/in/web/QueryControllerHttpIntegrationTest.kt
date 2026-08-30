package com.spike.ofac.adapter.`in`.web

import com.spike.ofac.adapter.out.persistence.PgVersionStore
import com.spike.ofac.application.port.out.RawSnapshotStore
import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate

/**
 * End-to-end **HTTP** integration test for the spec-first [QueryController]
 * (task 24.6) — the real controller (implementing the generated
 * [com.spike.ofac.adapter.web.generated.api.QueryContractApi]) over a full Spring
 * context and a Testcontainers PostgreSQL `Data_Store`.
 *
 * This is the check that was missing when the spec-first switch first shipped: it
 * drives the actual HTTP endpoints with real data and asserts a `200` with a JSON
 * body. A regression like "no `HttpMessageConverter` for the generated `Page` DTO"
 * (which the earlier route-only contract test did not exercise) fails here.
 *
 * Verifies: list + search return `200 application/json` with the generated DTO
 * shape (Req 16.1, 16.3); a no-CURRENT list is an empty page with `total` 0
 * (Req 16.4); invalid pagination (Req 16.8), missing `q` (Req 16.7), and an
 * unknown `{sourceList}` are `400`.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QueryControllerHttpIntegrationTest {

    @Autowired private lateinit var rest: TestRestTemplate
    @Autowired private lateinit var jdbc: NamedParameterJdbcTemplate
    @Autowired private lateinit var rawSnapshotStore: RawSnapshotStore

    private val date = LocalDate.of(2024, 1, 15)
    private fun vid(c: Char) = VersionId(date, Sha256Digest(c.toString().repeat(64)))

    @BeforeEach
    fun setUp() {
        assumeTrue(dockerAvailable, "Docker not available — skipping HTTP integration test.")
        // Ensure schema, then start from an empty store per test.
        jdbc.jdbcTemplate.execute(readSchemaSql())
        jdbc.jdbcTemplate.execute("TRUNCATE TABLE records, versions, pointers CASCADE")
    }

    private fun store() = PgVersionStore(jdbc, rawSnapshotStore)

    private fun entry(ref: String, name: String) = InternalModelEntry(
        fixedRef = FixedRef(ref),
        entityType = EntityType.Individual,
        primaryName = name,
        sanctionPrograms = listOf("SDGT"),
    )

    private fun activate(vararg entries: InternalModelEntry) {
        val s = store()
        val v = vid('a')
        s.putIsolatedFor(
            sourceList = SourceList.SDN, versionId = v, records = entries.toList(),
            recordCount = entries.size, outOfScopeCount = 0, overlapCount = 0,
            expectedCount = entries.size, persistedCount = entries.size,
        )
        s.atomicSetCurrent(SourceList.SDN, v) shouldBe true
    }

    @Test
    fun `list returns 200 application_json with the contract Page shape`() {
        activate(entry("1", "Ivan Ivanov"), entry("2", "Acme Corp"))

        val resp = rest.getForEntity("/api/SDN/records?offset=0&limit=50", String::class.java)

        resp.statusCode shouldBe HttpStatus.OK
        resp.headers.contentType!!.isCompatibleWith(MediaType.APPLICATION_JSON) shouldBe true
        // Generated DTO shape: records[], total, offset, limit.
        resp.body!!.shouldContain("\"total\":2")
        resp.body!!.shouldContain("\"primaryName\":\"Ivan Ivanov\"")
        resp.body!!.shouldContain("\"entityType\":\"Individual\"")
    }

    @Test
    fun `search returns 200 with only the matching record`() {
        activate(entry("1", "Ivan Ivanov"), entry("2", "Acme Corp"))

        val resp = rest.getForEntity("/api/SDN/records/search?q=ivan", String::class.java)

        resp.statusCode shouldBe HttpStatus.OK
        resp.body!!.shouldContain("\"total\":1")
        resp.body!!.shouldContain("Ivan Ivanov")
    }

    @Test
    fun `no CURRENT yields 200 empty page with total 0`() {
        val resp = rest.getForEntity("/api/SDN/records", String::class.java)
        resp.statusCode shouldBe HttpStatus.OK
        resp.body!!.shouldContain("\"total\":0")
    }

    @Test
    fun `invalid pagination is a 400`() {
        val resp = rest.getForEntity("/api/SDN/records?limit=5000", String::class.java)
        resp.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `missing search query is a 400`() {
        val resp = rest.getForEntity("/api/SDN/records/search", String::class.java)
        resp.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    @Test
    fun `unknown sourceList is a 400`() {
        val resp = rest.getForEntity("/api/FOO/records", String::class.java)
        resp.statusCode shouldBe HttpStatus.BAD_REQUEST
    }

    private fun readSchemaSql(): String =
        javaClass.classLoader.getResourceAsStream("db/schema.sql")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    companion object {
        private val dockerAvailable: Boolean =
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)

        private val postgres: PostgreSQLContainer<*>? =
            if (dockerAvailable) PostgreSQLContainer("postgres:16").apply { start() } else null

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            postgres?.let { pg ->
                registry.add("spring.datasource.url") { pg.jdbcUrl }
                registry.add("spring.datasource.username") { pg.username }
                registry.add("spring.datasource.password") { pg.password }
            }
            registry.add("ofac.source.sdn.enabled") { "false" }
        }
    }
}
