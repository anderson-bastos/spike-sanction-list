package com.spike.ofac.adapter.out.persistence

import com.spike.ofac.application.port.`in`.EmptyQueryException
import com.spike.ofac.application.port.`in`.InvalidPaginationException
import com.spike.ofac.application.port.`in`.QueryApi

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.spike.ofac.domain.model.Alias
import com.spike.ofac.domain.model.AliasCategory
import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.adapter.out.persistence.PgVersionStore
import com.spike.ofac.application.port.out.PointerKind
import com.spike.ofac.application.port.out.RawSnapshotStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Query API integration test (task 17.4).
 *
 * Exercises the real [PgQueryApi] against a Testcontainers PostgreSQL `Data_Store`
 * with the real CURRENT pointer resolved through the concrete [PgVersionStore] —
 * no mocking of the component under integration. It asserts the Requirement 16
 * guarantees against the live database:
 *
 *  - a missing/empty search query is rejected as a client error (Req 16.7);
 *  - invalid pagination (negative offset, non-positive limit, limit > max) is
 *    rejected as a client error (Req 16.8);
 *  - a request that matches nothing — or a `Source_List` with no CURRENT — returns
 *    a success empty page with `total` 0, not an error (Req 16.4);
 *  - reads stay consistent while an activation repoints CURRENT: every read
 *    resolves fully to either the old or the new CURRENT, never a partial dataset
 *    (Req 16.6);
 *  - only the CURRENT version is served — never PREVIOUS, N_MINUS_2, or COLD
 *    (Req 16.5);
 *  - the API is read-only: querying never mutates any Version, pointer, or record
 *    (Req 16.9).
 *
 * The test is **skipped gracefully** when Docker is unavailable, so it compiles
 * and runs cleanly in environments without a container runtime (parity with
 * [com.spike.ofac.adapter.out.persistence.PgVersionStoreAtomicActivationIntegrationTest]).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgQueryApiIntegrationTest {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var store: PgVersionStore
    private lateinit var queryApi: PgQueryApi

    private val date = LocalDate.of(2024, 1, 15)

    @BeforeAll
    fun startContainer() {
        assumeTrue(
            DockerClientFactory.instance().isDockerAvailable,
            "Docker is not available — skipping Testcontainers PostgreSQL integration test.",
        )

        postgres = PostgreSQLContainer("postgres:16")
        postgres.start()

        val dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        jdbc = NamedParameterJdbcTemplate(dataSource)

        // Apply the production schema DDL (creates pg_trgm extension + tables + indexes).
        val schema = readSchemaSql()
        jdbc.jdbcTemplate.execute(schema)

        val mapper = ObjectMapper().registerKotlinModule()
        store = PgVersionStore(jdbc, AlwaysValidRawSnapshotStore(), mapper)
        // The QueryApi under integration reads the real CURRENT pointer through the
        // real store — no mocking of the store or the DB.
        queryApi = PgQueryApi(jdbc, store, mapper)
    }

    @AfterAll
    fun stopContainer() {
        if (::postgres.isInitialized && postgres.isRunning) {
            postgres.stop()
        }
    }

    @BeforeEach
    fun cleanDatabase() {
        // Each test starts from an empty store so pointer/version state is isolated.
        jdbc.jdbcTemplate.execute("TRUNCATE TABLE records, versions, pointers CASCADE")
    }

    // --- (16.7) empty/missing search query rejected as a client error ----

    @Test
    fun `search with a blank query is rejected as a client error`() {
        // A version is CURRENT, so the rejection is about the query, not missing data.
        activate(vid('a'), entry("1", "Alice"))

        shouldThrow<EmptyQueryException> { queryApi.searchByName("", SourceList.SDN) }
        shouldThrow<EmptyQueryException> { queryApi.searchByName("   ", SourceList.SDN) }
    }

    // --- (16.8) invalid pagination rejected as a client error ------------

    @Test
    fun `invalid pagination is rejected as a client error`() {
        activate(vid('a'), entry("1", "Alice"))

        // Negative offset (Req 16.8).
        shouldThrow<InvalidPaginationException> { queryApi.list(SourceList.SDN, offset = -1, limit = 50) }
        // Non-positive limit (Req 16.8).
        shouldThrow<InvalidPaginationException> { queryApi.list(SourceList.SDN, offset = 0, limit = 0) }
        // Limit exceeding the bounded maximum (Req 16.8).
        shouldThrow<InvalidPaginationException> {
            queryApi.list(SourceList.SDN, offset = 0, limit = QueryApi.MAX_LIMIT + 1)
        }
        // The same bounds apply to search_by_name (Req 16.8, 16.3).
        shouldThrow<InvalidPaginationException> {
            queryApi.searchByName("Alice", SourceList.SDN, offset = 0, limit = -5)
        }
    }

    // --- (16.4) empty-but-valid page with total 0 -----------------------

    @Test
    fun `no CURRENT version yields a success empty page with total 0`() {
        // Nothing has been activated for SDN, so it has no CURRENT (Req 16.4).
        val listed = queryApi.list(SourceList.SDN)
        listed.records.shouldContainExactly()
        listed.total shouldBe 0L

        val searched = queryApi.searchByName("anything", SourceList.SDN)
        searched.records.shouldContainExactly()
        searched.total shouldBe 0L
    }

    @Test
    fun `a search matching no records yields a success empty page with total 0`() {
        activate(vid('a'), entry("1", "Alice"), entry("2", "Bob"))

        val page = queryApi.searchByName("no-such-name", SourceList.SDN)
        page.records.shouldContainExactly()
        page.total shouldBe 0L
    }

    // --- (16.5) only CURRENT is served, never PREVIOUS/N_MINUS_2/COLD ----

    @Test
    fun `only the CURRENT version is served, never PREVIOUS N_MINUS_2 or COLD`() {
        // Four activations: 'd' is CURRENT; 'c' PREVIOUS; 'b' N_MINUS_2; 'a' displaced COLD.
        activate(vid('a'), entry("1", "Alice-A"))
        activate(vid('b'), entry("1", "Alice-B"))
        activate(vid('c'), entry("1", "Alice-C"))
        activate(vid('d'), entry("1", "Alice-D"))

        // Sanity: the pointer trio is what we expect.
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe vid('d')
        store.getPointer(SourceList.SDN, PointerKind.PREVIOUS) shouldBe vid('c')
        store.getPointer(SourceList.SDN, PointerKind.N_MINUS_2) shouldBe vid('b')

        // list serves only the CURRENT ('d') record — never the older versions' rows.
        val listed = queryApi.list(SourceList.SDN)
        listed.total shouldBe 1L
        listed.records.map { it.primaryName } shouldContainExactly listOf("Alice-D")
        listed.records.single().versionId shouldBe vid('d')

        // search returns the CURRENT record and none of the older versions' names.
        val searched = queryApi.searchByName("Alice", SourceList.SDN)
        searched.total shouldBe 1L
        searched.records.map { it.primaryName } shouldContainExactly listOf("Alice-D")

        // The older versions' distinctive names are never returned, even though their
        // rows still physically exist in the records table (PREVIOUS/N_MINUS_2/COLD).
        queryApi.searchByName("Alice-A", SourceList.SDN).total shouldBe 0L
        queryApi.searchByName("Alice-B", SourceList.SDN).total shouldBe 0L
        queryApi.searchByName("Alice-C", SourceList.SDN).total shouldBe 0L
    }

    // --- (16.6) reads stay consistent during an activation ---------------

    @Test
    fun `reads during an activation resolve to old or new CURRENT only, never a partial dataset`() {
        // Old CURRENT: two records tagged OLD. New version: three records tagged NEW.
        val old = vid('a')
        val new = vid('b')
        activate(old, entry("1", "OLD-1"), entry("2", "OLD-2"))
        putIsolated(new, entry("1", "NEW-1"), entry("2", "NEW-2"), entry("3", "NEW-3"))

        val inconsistencySeen = AtomicBoolean(false)
        val readerErred = AtomicBoolean(false)
        val stop = AtomicBoolean(false)
        val started = CountDownLatch(1)

        val pool = Executors.newSingleThreadExecutor()
        val reader = pool.submit {
            started.countDown()
            while (!stop.get()) {
                try {
                    val page = queryApi.list(SourceList.SDN, offset = 0, limit = 1000)
                    val names = page.records.map { it.primaryName }.toSet()
                    val version = page.records.map { it.versionId }.toSet()
                    val allOld = names.all { it.startsWith("OLD") }
                    val allNew = names.all { it.startsWith("NEW") }
                    // A consistent read is fully the old CURRENT (2 OLD rows, total 2)
                    // or fully the new CURRENT (3 NEW rows, total 3) — never a mix,
                    // and total must agree with the returned record count/version.
                    val consistentOld = allOld && page.total == 2L && version == setOf(old)
                    val consistentNew = allNew && page.total == 3L && version == setOf(new)
                    if (!(consistentOld || consistentNew)) {
                        inconsistencySeen.set(true)
                    }
                } catch (t: Throwable) {
                    readerErred.set(true)
                }
            }
        }

        started.await(5, TimeUnit.SECONDS)
        // Repeatedly flip CURRENT between old and new while the reader hammers list().
        repeat(50) { i ->
            store.atomicSetCurrent(SourceList.SDN, if (i % 2 == 0) new else old) shouldBe true
        }
        stop.set(true)
        reader.get(10, TimeUnit.SECONDS)
        pool.shutdownNow()

        readerErred.get() shouldBe false
        inconsistencySeen.get() shouldBe false
    }

    // --- (16.9) the API is read-only -------------------------------------

    @Test
    fun `querying never mutates any version pointer or record`() {
        activate(vid('a'), entry("1", "Alice"), entry("2", "Bob"))

        val versionsBefore = versionRowCount()
        val recordsBefore = recordRowCount()
        val trioBefore = trioOf(SourceList.SDN)

        // Exercise both endpoints, including empty-page and no-match paths.
        queryApi.list(SourceList.SDN)
        queryApi.list(SourceList.SDN, offset = 1, limit = 1)
        queryApi.searchByName("alice", SourceList.SDN)
        queryApi.searchByName("no-match", SourceList.SDN)

        // Nothing changed: no rows added/removed, pointer trio identical (Req 16.9).
        versionRowCount() shouldBe versionsBefore
        recordRowCount() shouldBe recordsBefore
        trioOf(SourceList.SDN) shouldBe trioBefore
    }

    // --- name-search + pagination smoke over the real DB -----------------

    @Test
    fun `search matches primary name and aliases case-insensitively over CURRENT`() {
        activate(
            vid('a'),
            entry("1", "Vladimir Petrov"),
            entry("2", "Ivan Ivanov", aliases = listOf("Johnny Foreigner")),
            entry("3", "Unrelated Person"),
        )

        // Primary-name contains, case-insensitive (Req 16.3).
        queryApi.searchByName("petrov", SourceList.SDN).records
            .map { it.fixedRef.value } shouldContainExactlyInAnyOrder listOf("1")
        // Alias contains, case-insensitive (Req 16.3).
        queryApi.searchByName("FOREIGNER", SourceList.SDN).records
            .map { it.fixedRef.value } shouldContainExactlyInAnyOrder listOf("2")
    }

    @Test
    fun `alias category (strong or weak) round-trips through JSONB persistence`() {
        val record = InternalModelEntry(
            fixedRef = FixedRef("15252"),
            entityType = EntityType.Individual,
            primaryName = "FLORES PACHECO Cenobio",
            aliases = listOf(
                Alias(name = "CHECO", category = AliasCategory.WEAK),
                Alias(name = "CHEKO", category = AliasCategory.WEAK),
                Alias(name = "CASTRO VILLA", category = AliasCategory.STRONG),
            ),
            sanctionPrograms = listOf("PROGRAM"),
        )
        activate(vid('a'), record)

        val readBack = queryApi.list(SourceList.SDN, offset = 0, limit = 50).records.single()
        readBack.aliases.associate { it.name to it.category } shouldBe mapOf(
            "CHECO" to AliasCategory.WEAK,
            "CHEKO" to AliasCategory.WEAK,
            "CASTRO VILLA" to AliasCategory.STRONG,
        )
    }

    @Test
    fun `title placeOfBirth gender and the features list round-trip through JSONB persistence`() {
        val record = InternalModelEntry(
            fixedRef = FixedRef("777"),
            entityType = EntityType.Individual,
            primaryName = "Jane Doe",
            sanctionPrograms = listOf("PROGRAM"),
            title = "Minister of Defense",
            placeOfBirth = "Tehran, Iran",
            gender = "Female",
            features = listOf(
                com.spike.ofac.domain.model.SourceFeature("Phone Number", "+1-202-555-0147"),
                com.spike.ofac.domain.model.SourceFeature("Digital Currency Address - XBT", "1abc...xyz"),
            ),
        )
        activate(vid('a'), record)

        val readBack = queryApi.list(SourceList.SDN, offset = 0, limit = 50).records.single()
        readBack.title shouldBe "Minister of Defense"
        readBack.placeOfBirth shouldBe "Tehran, Iran"
        readBack.gender shouldBe "Female"
        readBack.features shouldContainExactly listOf(
            com.spike.ofac.domain.model.SourceFeature("Phone Number", "+1-202-555-0147"),
            com.spike.ofac.domain.model.SourceFeature("Digital Currency Address - XBT", "1abc...xyz"),
        )
    }

    @Test
    fun `absent title placeOfBirth gender persist as null and features defaults to empty`() {
        activate(vid('a'), entry("1", "Alice"))

        val readBack = queryApi.list(SourceList.SDN, offset = 0, limit = 50).records.single()
        readBack.title shouldBe null
        readBack.placeOfBirth shouldBe null
        readBack.gender shouldBe null
        readBack.features shouldContainExactly emptyList()
    }

    @Test
    fun `list pagination is deterministic and ordered by FixedRef over CURRENT`() {
        activate(
            vid('a'),
            entry("30", "C"),
            entry("10", "A"),
            entry("20", "B"),
        )

        val all = queryApi.list(SourceList.SDN, offset = 0, limit = 50)
        all.total shouldBe 3L
        // Stable ordering by fixed_ref (lexicographic on the string values, Req 16.2).
        all.records.map { it.fixedRef.value } shouldContainExactly listOf("10", "20", "30")

        // A windowed page respects offset/limit and stays in the same order.
        val page = queryApi.list(SourceList.SDN, offset = 1, limit = 1)
        page.total shouldBe 3L
        page.offset shouldBe 1
        page.limit shouldBe 1
        page.records.map { it.fixedRef.value } shouldContainExactly listOf("20")

        recordRowCount() shouldBeGreaterThan 0
    }

    // --- helpers ----------------------------------------------------------

    /** Persists an isolated (not-yet-active) version with the given records. */
    private fun putIsolated(v: VersionId, vararg records: InternalModelEntry) =
        store.putIsolatedFor(
            sourceList = SourceList.SDN,
            versionId = v,
            records = records.toList(),
            recordCount = records.size,
            outOfScopeCount = 0,
            overlapCount = 0,
            expectedCount = records.size,
            persistedCount = records.size,
        )

    /** Persists then activates a version as CURRENT for SDN. */
    private fun activate(v: VersionId, vararg records: InternalModelEntry) {
        putIsolated(v, *records)
        store.atomicSetCurrent(SourceList.SDN, v) shouldBe true
    }

    private fun versionRowCount(): Int =
        jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM versions", Int::class.java)!!

    private fun recordRowCount(): Int =
        jdbc.jdbcTemplate.queryForObject("SELECT COUNT(*) FROM records", Int::class.java)!!

    private fun trioOf(sourceList: SourceList): Triple<VersionId?, VersionId?, VersionId?> =
        Triple(
            store.getPointer(sourceList, PointerKind.CURRENT),
            store.getPointer(sourceList, PointerKind.PREVIOUS),
            store.getPointer(sourceList, PointerKind.N_MINUS_2),
        )

    private fun digest(c: Char) = Sha256Digest(c.toString().repeat(64))

    private fun vid(c: Char) = VersionId(date, digest(c))

    private fun entry(ref: String, name: String, aliases: List<String> = emptyList()) =
        InternalModelEntry(
            fixedRef = FixedRef(ref),
            entityType = EntityType.Individual,
            primaryName = name,
            aliases = aliases.map { Alias(name = it) },
            sanctionPrograms = listOf("PROGRAM"),
        )

    private fun readSchemaSql(): String {
        val stream = javaClass.classLoader.getResourceAsStream("db/schema.sql")
            ?: error("db/schema.sql not found on the classpath")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * A [RawSnapshotStore] stub whose [verifyIntegrity] always succeeds. This test
     * exercises the read-only Query_API over the real DB; raw-file integrity is
     * covered elsewhere (tasks 13.4, 13.7).
     */
    private class AlwaysValidRawSnapshotStore : RawSnapshotStore {
        override fun put(versionId: VersionId, bytes: ByteArray): Path = Paths.get("/dev/null")
        override fun get(versionId: VersionId): ByteArray = ByteArray(0)
        override fun verifyIntegrity(versionId: VersionId): Boolean = true
        override fun delete(versionId: VersionId): Boolean = false
    }
}
