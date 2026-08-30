package com.spike.ofac.adapter.out.persistence

import com.spike.ofac.domain.version.VersionPlan

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.spike.ofac.application.port.out.PointerKind
import com.spike.ofac.application.port.out.RawSnapshotStore
import com.spike.ofac.application.port.out.VersionStore
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.model.VersionMetadata
import com.spike.ofac.domain.model.VersionState
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate

/**
 * PostgreSQL-backed [VersionStore] (task 13.2), the concrete counterpart to the
 * in-memory reference model ([InMemoryVersionStore]).
 *
 * It maps the `versions`, `records`, and `pointers` tables (schema `db/schema.sql`,
 * task 13.1) and upholds the same two contract invariants the state machine relies
 * on:
 *
 *  - **Atomic pointer swap** — [atomicSetCurrent] repoints CURRENT and rotates the
 *    window inside a single Spring `@Transactional` transaction, so consumers never
 *    observe a half-rotated trio or a zeroed CURRENT (Req 9.1, 9.2). Where the
 *    in-memory model uses a monitor lock, this store uses the database transaction.
 *  - **Immutable version records** — [putIsolated] inserts the version + record
 *    rows once; thereafter only `versions.state` (HOT/COLD), `raw_snapshot_path`,
 *    and `integrity_ok` are ever updated, never the identity or the record rows
 *    (Req 7.5, 7.6).
 *
 * A freshly `putIsolated` version is **not** addressed by any pointer, so it is
 * invisible to consumers until [atomicSetCurrent] activates it (Req 7.6).
 *
 * **Multi-valued attributes → JSONB.** Each record's aliases / addresses /
 * documents / nationalities / citizenships / birthDates / sanctionPrograms /
 * remarks / relationships are Jackson-serialized to the JSONB columns; the plain
 * `alias_search` column is populated with the lowercased, newline-joined alias
 * names so the Query_API's case-insensitive CONTAINS search is index-assisted
 * (schema note, Req 16.3).
 *
 * **Interface note.** The [VersionStore] contract's [putIsolated] takes only
 * `(versionId, records)`, but the `versions` row also needs `source_list` and the
 * reconciliation counts. Mirroring [InMemoryVersionStore.putIsolatedFor], this
 * store exposes a richer [putIsolatedFor] that carries that metadata; the plain
 * [putIsolated] defaults to [SourceList.SDN] with counts derived from the record
 * list so the interface stays satisfied. The persist stage (task 13.6) is expected
 * to call [putIsolatedFor] with the real counts from the [VersionPlan].
 *
 * @param jdbc named-parameter JDBC access to the local PostgreSQL Data_Store.
 * @param rawSnapshotStore the file-based raw store; [verifyIntegrity] delegates to
 *   it so the digest is recomputed over the stored raw **file** bytes, never a DB
 *   column (Req 14.5, 15.5).
 * @param objectMapper Jackson mapper (Kotlin-module aware) used to serialize the
 *   multi-valued attributes to JSONB.
 */
@Repository
class PgVersionStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val rawSnapshotStore: RawSnapshotStore,
    objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule(),
) : VersionStore {

    // A Kotlin-aware mapper is required so data classes with default arguments
    // round-trip; if the injected bean lacks the Kotlin module we re-register it.
    private val mapper: ObjectMapper = objectMapper.copy().registerKotlinModule()

    override fun putIsolated(versionId: VersionId, records: List<InternalModelEntry>) {
        // The bare contract call carries no source list / counts; default to SDN
        // with counts derived from the record set (parity with InMemoryVersionStore).
        putIsolatedFor(
            sourceList = SourceList.SDN,
            versionId = versionId,
            records = records,
            recordCount = records.size,
            outOfScopeCount = 0,
            overlapCount = 0,
            expectedCount = records.size,
            persistedCount = records.size,
        )
    }

    /**
     * Inserts a **not-yet-active** version and its records with full metadata
     * (Req 7.4, 7.5, 7.6).
     *
     * The version row is written HOT (its lifecycle state), but it is addressed by
     * no pointer, so it is invisible to consumers until [atomicSetCurrent] activates
     * it. Records are inserted with their multi-valued attributes serialized to
     * JSONB and `alias_search` populated for name search.
     *
     * @param recordCount source-reported `<Record_Count>` (Req 8).
     * @param outOfScopeCount vessels + aircraft excluded (Req 5, 8.1).
     * @param overlapCount shared FixedRefs removed by dedup (Req 6, 8.1).
     * @param expectedCount `recordCount - outOfScope - overlap` (Req 8.1).
     * @param persistedCount in-scope, post-dedup count actually written (Req 8.2).
     * @param ingestedAt when the version was persisted; injectable for deterministic tests.
     */
    @Transactional
    fun putIsolatedFor(
        sourceList: SourceList,
        versionId: VersionId,
        records: List<InternalModelEntry>,
        recordCount: Int,
        outOfScopeCount: Int,
        overlapCount: Int,
        expectedCount: Int,
        persistedCount: Int,
        ingestedAt: Instant = Instant.now(),
    ) {
        insertVersionRow(
            versionId = versionId,
            sourceList = sourceList,
            recordCount = recordCount,
            outOfScopeCount = outOfScopeCount,
            overlapCount = overlapCount,
            expectedCount = expectedCount,
            persistedCount = persistedCount,
            ingestedAt = ingestedAt,
        )
        records.forEach { insertRecordRow(versionId, it) }
    }

    override fun associateRawPath(versionId: VersionId, rawPath: Path) {
        // Recording the path fills in the previously-null column only; it never
        // touches identity or record rows (Req 15.6). integrity_ok is set true
        // because callers associate the path only after the file's SHA-256 matched.
        val updated = jdbc.update(
            """
            UPDATE versions
               SET raw_snapshot_path = :rawPath,
                   integrity_ok      = TRUE
             WHERE publish_date = :publishDate
               AND digest       = :digest
            """.trimIndent(),
            versionKeyParams(versionId).addValue("rawPath", rawPath.toString()),
        )
        require(updated == 1) {
            "version $versionId was never persisted via putIsolated"
        }
    }

    @Transactional
    override fun atomicSetCurrent(sourceList: SourceList, versionId: VersionId): Boolean {
        // The version must exist and belong to this list; otherwise the repoint is
        // not applicable and the trio is left unchanged (Req 9.4).
        val target = readVersionMetadata(versionId) ?: return false
        if (target.sourceList != sourceList) return false

        val existing = readPointerTrio(sourceList)

        // Compute the whole new trio, then persist it in one statement so no reader
        // observes a half-rotated window (Req 9.1). CURRENT is always set to a
        // fully-persisted version, so it is never zeroed (Req 9.2).
        val rotated = PointerTrio(
            current = versionId,
            previous = existing?.current,
            nMinus2 = existing?.previous,
        )
        upsertPointerTrio(sourceList, rotated)

        // Everything addressed by the new trio is HOT; anything displaced past
        // N_MINUS_2 is reclassified COLD — all inside this same transaction so the
        // repoint + rotation commit atomically (Req 9.3, 10.5).
        markHot(rotated)
        reclassifyColdLocked(sourceList, rotated)
        return true
    }

    override fun getPointer(sourceList: SourceList, pointer: PointerKind): VersionId? {
        val trio = readPointerTrio(sourceList) ?: return null
        return when (pointer) {
            PointerKind.CURRENT -> trio.current
            PointerKind.PREVIOUS -> trio.previous
            PointerKind.N_MINUS_2 -> trio.nMinus2
        }
    }

    @Transactional
    override fun reclassifyCold(sourceList: SourceList) {
        reclassifyColdLocked(sourceList, readPointerTrio(sourceList))
    }

    override fun coldVersions(sourceList: SourceList): List<VersionId> =
        jdbc.query(
            """
            SELECT publish_date, digest FROM versions
             WHERE source_list = :sourceList
               AND state = :coldState
            """.trimIndent(),
            MapSqlParameterSource("sourceList", sourceList.name)
                .addValue("coldState", VersionState.COLD.name),
        ) { rs, _ ->
            VersionId(
                publishDate = rs.getDate("publish_date").toLocalDate(),
                digest = Sha256Digest(rs.getString("digest").trim()),
            )
        }

    override fun lastIngested(sourceList: SourceList): VersionMetadata? {
        val rows = jdbc.query(
            """
            SELECT * FROM versions
             WHERE source_list = :sourceList
             ORDER BY ingested_at DESC, publish_date DESC, digest DESC
             LIMIT 1
            """.trimIndent(),
            MapSqlParameterSource("sourceList", sourceList.name),
        ) { rs, _ -> mapVersionRow(rs) }
        return rows.firstOrNull()
    }

    override fun verifyIntegrity(versionId: VersionId): Boolean =
        // Delegate to the raw store, which recomputes SHA-256 over the stored FILE
        // bytes and compares against the recorded Digest (Req 14.5, 15.5).
        rawSnapshotStore.verifyIntegrity(versionId)

    override fun markUnusable(versionId: VersionId) {
        // Flip integrity_ok to FALSE only; identity and record rows are untouched so
        // the recorded (publish_date, digest) stays intact for audit (Req 14.5).
        jdbc.update(
            """
            UPDATE versions
               SET integrity_ok = FALSE
             WHERE publish_date = :publishDate
               AND digest       = :digest
            """.trimIndent(),
            versionKeyParams(versionId),
        )
    }

    /** Inspection helper: the immutable metadata of a version, if persisted. */
    fun metadataOf(versionId: VersionId): VersionMetadata? = readVersionMetadata(versionId)

    // --- internals ---

    private fun insertVersionRow(
        versionId: VersionId,
        sourceList: SourceList,
        recordCount: Int,
        outOfScopeCount: Int,
        overlapCount: Int,
        expectedCount: Int,
        persistedCount: Int,
        ingestedAt: Instant,
    ) {
        val params = versionKeyParams(versionId)
            .addValue("sourceList", sourceList.name)
            .addValue("recordCount", recordCount)
            .addValue("outOfScopeCount", outOfScopeCount)
            .addValue("overlapCount", overlapCount)
            .addValue("expectedCount", expectedCount)
            .addValue("persistedCount", persistedCount)
            .addValue("state", VersionState.HOT.name)
            .addValue("ingestedAt", java.sql.Timestamp.from(ingestedAt))
        jdbc.update(
            """
            INSERT INTO versions (
                publish_date, digest, source_list,
                record_count, out_of_scope_count, overlap_count,
                expected_count, persisted_count, state, ingested_at
            ) VALUES (
                :publishDate, :digest, :sourceList,
                :recordCount, :outOfScopeCount, :overlapCount,
                :expectedCount, :persistedCount, :state, :ingestedAt
            )
            """.trimIndent(),
            params,
        )
    }

    private fun insertRecordRow(versionId: VersionId, entry: InternalModelEntry) {
        val params = versionKeyParams(versionId)
            .addValue("fixedRef", entry.fixedRef.value)
            .addValue("entityType", entry.entityType.name)
            .addValue("primaryName", entry.primaryName)
            .addValue("aliases", json(entry.aliases))
            .addValue("addresses", json(entry.addresses))
            .addValue("documents", json(entry.documents))
            .addValue("nationalities", json(entry.nationalities))
            .addValue("citizenships", json(entry.citizenships))
            .addValue("birthDates", json(entry.birthDates))
            .addValue("sanctionPrograms", json(entry.sanctionPrograms))
            .addValue("remarks", json(entry.remarks))
            .addValue("relationships", json(entry.relationships))
            .addValue("aliasSearch", aliasSearch(entry))
        jdbc.update(
            """
            INSERT INTO records (
                publish_date, digest, fixed_ref, entity_type, primary_name,
                aliases, addresses, documents, nationalities, citizenships,
                birth_dates, sanction_programs, remarks, relationships, alias_search
            ) VALUES (
                :publishDate, :digest, :fixedRef, :entityType, :primaryName,
                CAST(:aliases AS jsonb), CAST(:addresses AS jsonb), CAST(:documents AS jsonb),
                CAST(:nationalities AS jsonb), CAST(:citizenships AS jsonb),
                CAST(:birthDates AS jsonb), CAST(:sanctionPrograms AS jsonb),
                CAST(:remarks AS jsonb), CAST(:relationships AS jsonb), :aliasSearch
            )
            """.trimIndent(),
            params,
        )
    }

    /**
     * Lowercased, newline-joined alias names for the plain `alias_search` column
     * (schema note, Req 16.3). Newline-joining keeps distinct aliases from forming
     * a false substring match across a boundary.
     */
    private fun aliasSearch(entry: InternalModelEntry): String =
        entry.aliases.joinToString("\n") { it.name }.lowercase()

    private fun json(value: Any): String = mapper.writeValueAsString(value)

    private fun markHot(trio: PointerTrio) {
        val ids = listOfNotNull(trio.current, trio.previous, trio.nMinus2)
        ids.forEach { setState(it, VersionState.HOT) }
    }

    /**
     * Marks every version of [sourceList] not addressed by [trio] as COLD, retaining
     * it without mutating records or identity (Req 10.5).
     */
    private fun reclassifyColdLocked(sourceList: SourceList, trio: PointerTrio?) {
        val hot = listOfNotNull(trio?.current, trio?.previous, trio?.nMinus2)
        val params = MapSqlParameterSource("sourceList", sourceList.name)
            .addValue("state", VersionState.COLD.name)
            .addValue("hotState", VersionState.HOT.name)
        val exclusion = buildString {
            hot.forEachIndexed { i, id ->
                append(" AND NOT (publish_date = :hotPd$i AND digest = :hotDg$i)")
                params.addValue("hotPd$i", java.sql.Date.valueOf(id.publishDate))
                params.addValue("hotDg$i", id.digest.value)
            }
        }
        jdbc.update(
            """
            UPDATE versions
               SET state = :state
             WHERE source_list = :sourceList
               AND state = :hotState$exclusion
            """.trimIndent(),
            params,
        )
    }

    private fun setState(versionId: VersionId, state: VersionState) {
        jdbc.update(
            """
            UPDATE versions SET state = :state
             WHERE publish_date = :publishDate AND digest = :digest
            """.trimIndent(),
            versionKeyParams(versionId).addValue("state", state.name),
        )
    }

    private fun readVersionMetadata(versionId: VersionId): VersionMetadata? {
        val rows = jdbc.query(
            """
            SELECT * FROM versions
             WHERE publish_date = :publishDate AND digest = :digest
            """.trimIndent(),
            versionKeyParams(versionId),
        ) { rs, _ -> mapVersionRow(rs) }
        return rows.firstOrNull()
    }

    private fun readPointerTrio(sourceList: SourceList): PointerTrio? {
        val rows = jdbc.query(
            "SELECT * FROM pointers WHERE source_list = :sourceList",
            MapSqlParameterSource("sourceList", sourceList.name),
        ) { rs, _ -> mapPointerRow(rs) }
        return rows.firstOrNull()
    }

    private fun upsertPointerTrio(sourceList: SourceList, trio: PointerTrio) {
        val params = MapSqlParameterSource("sourceList", sourceList.name)
            .addValue("currentPd", java.sql.Date.valueOf(trio.current.publishDate))
            .addValue("currentDg", trio.current.digest.value)
            .addValue("previousPd", trio.previous?.let { java.sql.Date.valueOf(it.publishDate) })
            .addValue("previousDg", trio.previous?.digest?.value)
            .addValue("nMinus2Pd", trio.nMinus2?.let { java.sql.Date.valueOf(it.publishDate) })
            .addValue("nMinus2Dg", trio.nMinus2?.digest?.value)
        jdbc.update(
            """
            INSERT INTO pointers (
                source_list,
                current_publish_date, current_digest,
                previous_publish_date, previous_digest,
                n_minus_2_publish_date, n_minus_2_digest
            ) VALUES (
                :sourceList,
                :currentPd, :currentDg,
                :previousPd, :previousDg,
                :nMinus2Pd, :nMinus2Dg
            )
            ON CONFLICT (source_list) DO UPDATE SET
                current_publish_date   = EXCLUDED.current_publish_date,
                current_digest         = EXCLUDED.current_digest,
                previous_publish_date  = EXCLUDED.previous_publish_date,
                previous_digest        = EXCLUDED.previous_digest,
                n_minus_2_publish_date = EXCLUDED.n_minus_2_publish_date,
                n_minus_2_digest       = EXCLUDED.n_minus_2_digest
            """.trimIndent(),
            params,
        )
    }

    private fun versionKeyParams(versionId: VersionId): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("publishDate", java.sql.Date.valueOf(versionId.publishDate))
            .addValue("digest", versionId.digest.value)

    private fun mapVersionRow(rs: ResultSet): VersionMetadata {
        val rawPath = rs.getString("raw_snapshot_path")
        val integrity = rs.getObject("integrity_ok") as Boolean?
        return VersionMetadata(
            versionId = VersionId(
                publishDate = rs.getDate("publish_date").toLocalDate(),
                digest = Sha256Digest(rs.getString("digest").trim()),
            ),
            sourceList = SourceList.valueOf(rs.getString("source_list")),
            recordCount = rs.getInt("record_count"),
            outOfScopeCount = rs.getInt("out_of_scope_count"),
            overlapCount = rs.getInt("overlap_count"),
            expectedCount = rs.getInt("expected_count"),
            persistedCount = rs.getInt("persisted_count"),
            state = VersionState.valueOf(rs.getString("state")),
            ingestedAt = rs.getTimestamp("ingested_at").toInstant(),
            rawSnapshotPath = rawPath?.let { Paths.get(it) },
            integrityOk = integrity,
        )
    }

    private fun mapPointerRow(rs: ResultSet): PointerTrio {
        fun id(pdCol: String, dgCol: String): VersionId? {
            val pd: LocalDate = rs.getDate(pdCol)?.toLocalDate() ?: return null
            val dg = rs.getString(dgCol) ?: return null
            return VersionId(pd, Sha256Digest(dg.trim()))
        }
        return PointerTrio(
            current = id("current_publish_date", "current_digest")
                ?: error("pointers row has null CURRENT (Req 9.2 violated)"),
            previous = id("previous_publish_date", "previous_digest"),
            nMinus2 = id("n_minus_2_publish_date", "n_minus_2_digest"),
        )
    }

    /** The pointer trio held per list, swapped atomically as a whole. */
    private data class PointerTrio(
        val current: VersionId,
        val previous: VersionId?,
        val nMinus2: VersionId?,
    )
}
