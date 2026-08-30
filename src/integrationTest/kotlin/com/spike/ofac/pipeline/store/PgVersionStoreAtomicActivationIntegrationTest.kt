package com.spike.ofac.pipeline.store

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.spike.ofac.pipeline.models.EntityType
import com.spike.ofac.pipeline.models.FixedRef
import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.models.SourceList
import com.spike.ofac.pipeline.models.VersionId
import com.spike.ofac.pipeline.models.VersionState
import io.kotest.matchers.nulls.shouldBeNull
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
import kotlin.system.measureTimeMillis

/**
 * Integration test for real-transaction atomic activation (task 13.5).
 *
 * Exercises [PgVersionStore] against a real PostgreSQL instance (Testcontainers)
 * so the atomic-activation guarantees are verified against the concrete Spring
 * `@Transactional` transaction rather than the in-memory reference model. It
 * asserts, against the live database, the Property 10 / Property 11 semantics:
 *
 *  - an isolated version is **not** resolvable as CURRENT before activation
 *    (Req 9.2);
 *  - [PgVersionStore.atomicSetCurrent] makes CURRENT resolve to exactly that
 *    fully-persisted version, and it is re-readable within 5s (Req 9.1, 9.5);
 *  - window rotation over several activations keeps exactly three HOT versions
 *    (CURRENT / PREVIOUS / N_MINUS_2) and colds the displaced ones (Req 10.5);
 *  - a rejected activation (a never-persisted version id) returns `false` and
 *    leaves the pointer trio entirely unchanged (Req 9.1, 9.2).
 *
 * The test is **skipped gracefully** when Docker is unavailable, so it compiles
 * and runs cleanly in environments without a container runtime.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgVersionStoreAtomicActivationIntegrationTest {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var store: PgVersionStore

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

        // Apply the production schema DDL (creates pg_trgm extension + tables).
        val schema = readSchemaSql()
        jdbc.jdbcTemplate.execute(schema)

        // A stub raw store: this test verifies transaction/pointer semantics, not
        // raw-file integrity, so a trivially-passing verifyIntegrity is sufficient.
        store = PgVersionStore(jdbc, AlwaysValidRawSnapshotStore(), ObjectMapper().registerKotlinModule())
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
        // TRUNCATE ... CASCADE clears records + versions + pointers (FK-linked).
        jdbc.jdbcTemplate.execute("TRUNCATE TABLE records, versions, pointers CASCADE")
    }

    // --- (1) isolation before activation ---------------------------------

    @Test
    fun `an isolated version is not resolvable as CURRENT before activation`() {
        val v = vid('a')
        store.putIsolatedFor(
            sourceList = SourceList.SDN,
            versionId = v,
            records = listOf(entry("1")),
            recordCount = 1,
            outOfScopeCount = 0,
            overlapCount = 0,
            expectedCount = 1,
            persistedCount = 1,
        )

        // Persisted but addressed by no pointer -> invisible to consumers (Req 9.2).
        store.getPointer(SourceList.SDN, PointerKind.CURRENT).shouldBeNull()
        // But the immutable metadata exists in the store.
        store.metadataOf(v)!!.versionId shouldBe v
    }

    // --- (2) atomic activation makes CURRENT resolve, re-readable within 5s

    @Test
    fun `atomicSetCurrent makes CURRENT resolve to the fully-persisted version, re-readable within 5s`() {
        val v = vid('a')
        putVersion(v, ref = "1")

        store.atomicSetCurrent(SourceList.SDN, v) shouldBe true

        // CURRENT now resolves to exactly that version (Req 9.1).
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe v
        // ... and the version is fully persisted (record row is present).
        recordCountOf(v) shouldBe 1

        // The new CURRENT is resolvable within 5s of activation (Req 9.5).
        val elapsedMs = measureTimeMillis {
            store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe v
        }
        assert(elapsedMs < 5_000) { "CURRENT re-read took ${elapsedMs}ms, exceeding the 5s bound (Req 9.5)" }
    }

    // --- (3) window rotation keeps 3 HOT + displaced COLD (Property 11) ----

    @Test
    fun `window rotation over several activations keeps three HOT versions and colds the displaced ones`() {
        val ids = "abcd".map { vid(it) }
        ids.forEachIndexed { i, id -> putVersion(id, ref = i.toString()) }

        ids.forEach { store.atomicSetCurrent(SourceList.SDN, it) shouldBe true }

        // The trio holds the three most recent, in order (Req 10.1, 10.5).
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe ids[3]
        store.getPointer(SourceList.SDN, PointerKind.PREVIOUS) shouldBe ids[2]
        store.getPointer(SourceList.SDN, PointerKind.N_MINUS_2) shouldBe ids[1]

        // At most three HOT versions per list; the displaced oldest is retained COLD.
        hotCount(SourceList.SDN) shouldBe 3
        store.metadataOf(ids[0])!!.state shouldBe VersionState.COLD
        store.metadataOf(ids[1])!!.state shouldBe VersionState.HOT
        store.metadataOf(ids[2])!!.state shouldBe VersionState.HOT
        store.metadataOf(ids[3])!!.state shouldBe VersionState.HOT
    }

    // --- (4) rejected activation leaves the pointer trio unchanged --------

    @Test
    fun `activating a never-persisted version returns false and leaves the pointer trio unchanged`() {
        val current = vid('a')
        val previous = vid('b')
        putVersion(previous, ref = "b")
        putVersion(current, ref = "a")
        store.atomicSetCurrent(SourceList.SDN, previous) shouldBe true
        store.atomicSetCurrent(SourceList.SDN, current) shouldBe true

        val trioBefore = trioOf(SourceList.SDN)

        // A bogus, never-persisted version id cannot be activated (Req 9.4).
        // 'f' is a valid hex char but was never putIsolated, so it does not exist.
        store.atomicSetCurrent(SourceList.SDN, vid('f')) shouldBe false

        // The pointer trio is entirely unchanged — the repoint is all-or-nothing.
        trioOf(SourceList.SDN) shouldBe trioBefore
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe current
        store.getPointer(SourceList.SDN, PointerKind.PREVIOUS) shouldBe previous
    }

    // --- helpers ----------------------------------------------------------

    private fun putVersion(v: VersionId, ref: String) =
        store.putIsolatedFor(
            sourceList = SourceList.SDN,
            versionId = v,
            records = listOf(entry(ref)),
            recordCount = 1,
            outOfScopeCount = 0,
            overlapCount = 0,
            expectedCount = 1,
            persistedCount = 1,
        )

    private fun recordCountOf(v: VersionId): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM records WHERE publish_date = :pd AND digest = :dg",
            MapSqlParameterSource("pd", java.sql.Date.valueOf(v.publishDate)).addValue("dg", v.digest.value),
            Int::class.java,
        )!!

    private fun hotCount(sourceList: SourceList): Int =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM versions WHERE source_list = :sl AND state = 'HOT'",
            MapSqlParameterSource("sl", sourceList.name),
            Int::class.java,
        )!!

    private fun trioOf(sourceList: SourceList): Triple<VersionId?, VersionId?, VersionId?> =
        Triple(
            store.getPointer(sourceList, PointerKind.CURRENT),
            store.getPointer(sourceList, PointerKind.PREVIOUS),
            store.getPointer(sourceList, PointerKind.N_MINUS_2),
        )

    private fun digest(c: Char) = Sha256Digest(c.toString().repeat(64))

    private fun vid(c: Char) = VersionId(date, digest(c))

    private fun entry(ref: String) = InternalModelEntry(
        fixedRef = FixedRef(ref),
        entityType = EntityType.Individual,
        primaryName = "Name $ref",
        sanctionPrograms = listOf("PROGRAM"),
    )

    private fun readSchemaSql(): String {
        val stream = javaClass.classLoader.getResourceAsStream("db/schema.sql")
            ?: error("db/schema.sql not found on the classpath")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * A [RawSnapshotStore] stub whose [verifyIntegrity] always succeeds. This test
     * exercises the DB transaction / pointer semantics; raw-file integrity is
     * covered by the [FsRawSnapshotStore] unit/property tests (tasks 13.4, 13.7).
     */
    private class AlwaysValidRawSnapshotStore : RawSnapshotStore {
        override fun put(versionId: VersionId, bytes: ByteArray): Path = Paths.get("/dev/null")
        override fun get(versionId: VersionId): ByteArray = ByteArray(0)
        override fun verifyIntegrity(versionId: VersionId): Boolean = true
        override fun delete(versionId: VersionId): Boolean = false
    }
}
