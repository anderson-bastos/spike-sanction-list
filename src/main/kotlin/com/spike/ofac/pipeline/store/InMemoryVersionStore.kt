package com.spike.ofac.pipeline.store

import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.SourceList
import com.spike.ofac.pipeline.models.VersionId
import com.spike.ofac.pipeline.models.VersionMetadata
import com.spike.ofac.pipeline.models.VersionState
import java.nio.file.Path
import java.time.Instant

/**
 * In-memory reference implementation of [VersionStore].
 *
 * This is the **reference model** for the version-pointer state machine and the
 * stateful, model-based property tests (Properties 10–14, tasks 8.4–8.7). The
 * concrete PostgreSQL-backed store (`PgVersionStore`, task 13) is built against
 * the same [VersionStore] contract and is expected to behave identically; the
 * database provides atomicity through a transaction where this model provides it
 * through a monitor lock.
 *
 * It upholds the two contract invariants directly:
 *
 *  - **Atomic pointer swap** — every mutation runs under a single monitor
 *    ([lock]). [atomicSetCurrent] computes the whole new pointer trio and swaps
 *    it in one guarded assignment, so no reader can observe a partially rotated
 *    window or a zeroed CURRENT (Req 9.1, 9.2).
 *  - **Immutable version records** — a version's records and identity are stored
 *    once by [putIsolated] and never rewritten. Rotation only edits a version's
 *    HOT/COLD [state][VersionMetadata.state] and the pointer trio; the record
 *    list handed back is a defensive copy (Req 7.5, 7.6).
 *
 * The in-memory [associateRawPath] and [verifyIntegrity] are stubs: the model
 * holds no real raw files, so it records the path onto the metadata (for the
 * `PgVersionStore` parity checks) and treats integrity verification as trivially
 * satisfied. The real behavior lives in `FsRawSnapshotStore` (task 13).
 *
 * @param clock supplies the [Instant] stamped as `ingestedAt`; injectable so
 *   tests can order versions deterministically.
 */
class InMemoryVersionStore(
    private val clock: () -> Instant = Instant::now,
) : VersionStore {

    /** Guards every read and write so the pointer swap is observably atomic. */
    private val lock = Any()

    /**
     * All persisted versions keyed by identity. Insert-only: an entry is added by
     * [putIsolated] and thereafter only its [StoredVersion.metadata] `state` /
     * `rawSnapshotPath` may change — never its records or identity (Req 7.5).
     */
    private val versions = mutableMapOf<VersionId, StoredVersion>()

    /** The pointer trio per list, absent until a list's first activation. */
    private val pointers = mutableMapOf<SourceList, VersionPointerTrio>()

    /**
     * Monotonic ingest sequence, used to break ties when two versions share an
     * `ingestedAt` instant (a clock with coarse resolution) so [lastIngested] and
     * COLD ordering stay deterministic.
     */
    private var ingestSequence = 0L

    override fun putIsolated(versionId: VersionId, records: List<InternalModelEntry>) {
        // A bare putIsolated defaults to SDN, which reads naturally for the common
        // single-list stateful tests; multi-list tests use [putIsolatedFor].
        synchronized(lock) { putIsolatedLocked(SourceList.SDN, versionId, records) }
    }

    override fun associateRawPath(versionId: VersionId, rawPath: Path) {
        synchronized(lock) {
            val stored = requireStored(versionId)
            // No-op on real bytes (the model holds none); record the path so the
            // metadata mirrors what PgVersionStore would carry.
            versions[versionId] = stored.copy(
                metadata = stored.metadata.copy(rawSnapshotPath = rawPath, integrityOk = true),
            )
        }
    }

    override fun atomicSetCurrent(sourceList: SourceList, versionId: VersionId): Boolean {
        synchronized(lock) {
            val stored = versions[versionId] ?: return false
            if (stored.metadata.sourceList != sourceList) return false

            val existing = pointers[sourceList]

            // Compute the whole new trio first, then swap in one guarded step so no
            // reader observes a half-rotated window (Req 9.1). CURRENT is always set
            // to a fully-persisted version, so it is never zeroed (Req 9.2).
            val rotated = VersionPointerTrio(
                current = versionId,
                previous = existing?.current,
                nMinus2 = existing?.previous,
            )
            pointers[sourceList] = rotated

            // Any version displaced past N_MINUS_2 leaves the HOT window.
            markHot(rotated)
            reclassifyColdLocked(sourceList)
            return true
        }
    }

    override fun getPointer(sourceList: SourceList, pointer: PointerKind): VersionId? =
        synchronized(lock) {
            val trio = pointers[sourceList] ?: return null
            when (pointer) {
                PointerKind.CURRENT -> trio.current
                PointerKind.PREVIOUS -> trio.previous
                PointerKind.N_MINUS_2 -> trio.nMinus2
            }
        }

    override fun reclassifyCold(sourceList: SourceList) {
        synchronized(lock) { reclassifyColdLocked(sourceList) }
    }

    override fun coldVersions(sourceList: SourceList): List<VersionId> =
        synchronized(lock) {
            versions.values
                .filter {
                    it.metadata.sourceList == sourceList && it.metadata.state == VersionState.COLD
                }
                .map { it.metadata.versionId }
        }

    override fun lastIngested(sourceList: SourceList): VersionMetadata? =
        synchronized(lock) {
            versions.values
                .filter { it.metadata.sourceList == sourceList }
                .maxByOrNull { it.ingestSeq }
                ?.metadata
        }

    override fun verifyIntegrity(versionId: VersionId): Boolean =
        synchronized(lock) {
            // Reference model holds no raw bytes; a persisted version is treated as
            // intact. The real integrity check lives in FsRawSnapshotStore (task 13).
            versionId in versions
        }

    override fun markUnusable(versionId: VersionId) {
        synchronized(lock) {
            // Flip integrityOk to false without touching identity or records: the
            // version stays present and auditable, only flagged unusable (Req 14.5).
            versions[versionId]?.let { stored ->
                versions[versionId] = stored.copy(
                    metadata = stored.metadata.copy(integrityOk = false),
                )
            }
        }
    }

    /** Test/inspection helper: the immutable metadata of a version, if persisted. */
    fun metadataOf(versionId: VersionId): VersionMetadata? =
        synchronized(lock) { versions[versionId]?.metadata }

    /** Test/inspection helper: the immutable records of a version, if persisted. */
    fun recordsOf(versionId: VersionId): List<InternalModelEntry>? =
        synchronized(lock) { versions[versionId]?.records }

    // --- internals (all callers already hold [lock]) ---

    private fun requireStored(versionId: VersionId): StoredVersion =
        versions[versionId]
            ?: error("version $versionId was never persisted via putIsolated")

    /** Marks the three versions addressed by [trio] HOT (idempotent). */
    private fun markHot(trio: VersionPointerTrio) {
        listOfNotNull(trio.current, trio.previous, trio.nMinus2).forEach { id ->
            versions[id]?.let { versions[id] = it.withState(VersionState.HOT) }
        }
    }

    /**
     * Marks every version of [sourceList] that is **not** one of its current three
     * pointers as COLD, retaining it without mutation of records/identity (Req 10.5).
     */
    private fun reclassifyColdLocked(sourceList: SourceList) {
        val trio = pointers[sourceList]
        val hot = setOfNotNull(trio?.current, trio?.previous, trio?.nMinus2)
        versions.values
            .filter { it.metadata.sourceList == sourceList && it.metadata.versionId !in hot }
            .forEach { stored ->
                if (stored.metadata.state != VersionState.COLD) {
                    versions[stored.metadata.versionId] = stored.withState(VersionState.COLD)
                }
            }
    }

    /**
     * Persists an isolated version explicitly attributed to [sourceList].
     *
     * The [VersionStore] contract's [putIsolated] takes only records (which, in
     * this model, carry no source list), so this convenience pins the list for the
     * per-list independence property test (Property 13). Real records in
     * `PgVersionStore` are inserted against a known `source_list` column.
     */
    fun putIsolatedFor(
        sourceList: SourceList,
        versionId: VersionId,
        records: List<InternalModelEntry>,
    ) {
        synchronized(lock) { putIsolatedLocked(sourceList, versionId, records) }
    }

    private fun putIsolatedLocked(
        sourceList: SourceList,
        versionId: VersionId,
        records: List<InternalModelEntry>,
    ) {
        require(versionId !in versions) {
            "version $versionId is already persisted; versions are immutable (Req 7.5)"
        }
        // Counts are supplied by the persist stage in the real store; the reference
        // model only tracks identity, state, and pointers, so it records placeholder
        // counts it never reasons about.
        versions[versionId] = StoredVersion(
            metadata = VersionMetadata(
                versionId = versionId,
                sourceList = sourceList,
                recordCount = records.size,
                outOfScopeCount = 0,
                overlapCount = 0,
                expectedCount = records.size,
                persistedCount = records.size,
                state = VersionState.HOT,
                ingestedAt = clock(),
            ),
            records = records.toList(),
            ingestSeq = ++ingestSequence,
        )
    }

    /** An immutable stored version: metadata + records + ingest order. */
    private data class StoredVersion(
        val metadata: VersionMetadata,
        val records: List<InternalModelEntry>,
        val ingestSeq: Long,
    ) {
        fun withState(state: VersionState): StoredVersion =
            if (metadata.state == state) this else copy(metadata = metadata.copy(state = state))
    }

    /** The mutable pointer trio held per list (swapped atomically as a whole). */
    private data class VersionPointerTrio(
        val current: VersionId,
        val previous: VersionId?,
        val nMinus2: VersionId?,
    )
}
