package com.spike.ofac.pipeline.store

import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.SourceList
import com.spike.ofac.pipeline.models.VersionId
import com.spike.ofac.pipeline.models.VersionMetadata
import java.nio.file.Path

/**
 * Persistence contract for immutable versions and their per-list pointer trio.
 *
 * The concrete engine is a deployment choice (an in-memory reference model here,
 * a PostgreSQL-backed [VersionStore] later — task 13). Whatever the engine, it
 * must guarantee two invariants that the version-pointer state machine and the
 * stateful property tests (Properties 10–14) rely on:
 *
 *  - **Atomic pointer swap** — [atomicSetCurrent] repoints CURRENT (and rotates
 *    the window) in a single, indivisible step, so consumers never observe a
 *    window with no active version or a half-rotated trio (Req 9.1, 9.2).
 *  - **Immutable version records** — once written by [putIsolated] a version's
 *    identity and records never change; the window rotation only reclassifies a
 *    version's HOT/COLD [state][VersionMetadata.state] and moves pointers, it
 *    never mutates or deletes the underlying rows (Req 7.5, 7.6).
 *
 * Design contract (`design.md` "VersionStore"):
 * ```
 * interface VersionStore:
 *   put_isolated(version_id, records) -> void
 *   associate_raw_path(version_id, raw_path) -> void
 *   atomic_set_current(source_list, version_id) -> bool
 *   get_pointer(source_list, ptr: CURRENT|PREVIOUS|N_MINUS_2) -> VersionId?
 *   reclassify_cold(source_list) -> void
 *   cold_versions(source_list) -> [VersionId]
 *   last_ingested(source_list) -> VersionRef?
 *   verify_integrity(version_id) -> bool
 *   mark_unusable(version_id) -> void
 * ```
 */
interface VersionStore {

    /**
     * Writes a **not-yet-active** version and its records (Req 7.6).
     *
     * The version is persisted in isolation: it exists in the store but is not
     * resolvable through any pointer, so consumers cannot observe it until
     * [atomicSetCurrent] activates it. The write is immutable — a version's
     * records never change once written (Req 7.5).
     *
     * @param versionId the (Publish_Date, Digest) identity of the version (Req 7.2).
     * @param records the persisted in-scope entries, each already stamped with
     *   [versionId] (Req 7.4).
     */
    fun putIsolated(versionId: VersionId, records: List<InternalModelEntry>)

    /**
     * Links the stored raw-snapshot file path onto the version metadata (Req 15.6).
     *
     * Called only **after** the stored file's SHA-256 has been confirmed to match
     * the recorded `Digest` (Req 15.5, 15.6). Recording the path does not change
     * the version's identity or records — it only fills in the previously-null
     * [VersionMetadata.rawSnapshotPath].
     *
     * @param versionId the version to associate the raw file with.
     * @param rawPath the filesystem path into the local Raw_Snapshot_Store.
     */
    fun associateRawPath(versionId: VersionId, rawPath: Path)

    /**
     * Atomically repoints CURRENT for [sourceList] to [versionId] and rotates the
     * window in a single indivisible step (Req 9.1, 9.2).
     *
     * On success: old CURRENT becomes PREVIOUS, old PREVIOUS becomes N_MINUS_2,
     * and any version displaced past N_MINUS_2 is later reclassified COLD by
     * [reclassifyCold]; at most three HOT versions are kept per list (Req 10.1,
     * 10.5). CURRENT is never zeroed (Req 9.2).
     *
     * On failure the pointer trio is left **entirely unchanged** — the repoint is
     * all-or-nothing (Req 9.4).
     *
     * @return `true` when the repoint succeeded and CURRENT now resolves to
     *   [versionId]; `false` when it could not be applied (e.g. the version was
     *   never persisted via [putIsolated]), leaving the trio unchanged.
     */
    fun atomicSetCurrent(sourceList: SourceList, versionId: VersionId): Boolean

    /**
     * Resolves one of the three pointers for [sourceList] (Req 9, Req 10.1).
     *
     * @return the [VersionId] the pointer currently addresses, or `null` when that
     *   slot is empty (e.g. no PREVIOUS yet) or the list has no versions.
     */
    fun getPointer(sourceList: SourceList, pointer: PointerKind): VersionId?

    /**
     * Reclassifies every HOT version of [sourceList] that has been displaced past
     * N_MINUS_2 as COLD (Req 10.5).
     *
     * COLD versions are retained for audit — their records and identity are never
     * deleted or mutated, only the [VersionMetadata.state] flips to
     * [COLD][com.spike.ofac.pipeline.models.VersionState.COLD] (Req 10.5, Req 14).
     */
    fun reclassifyCold(sourceList: SourceList)

    /**
     * Lists every version of [sourceList] currently classified
     * [COLD][com.spike.ofac.pipeline.models.VersionState.COLD] (Req 14).
     *
     * The `RetentionManager` uses this to identify the versions displaced past the
     * HOT window after a rotation: when retention is disabled it discards their raw
     * snapshot files (Req 14.4); when enabled they are the retained-for-period set
     * (Req 14.1, 14.2). Scoped per [sourceList], so one list's COLD set never
     * includes another's (Req 14, per-list independence).
     *
     * @return the identities of the list's COLD versions; empty when the list has
     *   no displaced versions. Order is unspecified.
     */
    fun coldVersions(sourceList: SourceList): List<VersionId>

    /**
     * Returns metadata for the most recently ingested version of [sourceList], or
     * `null` when the list has no versions yet (Req 1.3).
     *
     * `obtain.check_change` reads this version's [digest][VersionId.digest] to
     * decide NO_CHANGE vs CHANGED. The full [VersionMetadata] is returned so the
     * caller can reach both the digest and the publish date for the fallback
     * comparison.
     */
    fun lastIngested(sourceList: SourceList): VersionMetadata?

    /**
     * Verifies the integrity of the stored raw snapshot for [versionId] (Req 14.5).
     *
     * Delegates to `RawSnapshotStore.verify_integrity`, which recomputes SHA-256
     * over the stored raw **file** bytes and compares it against the recorded
     * `Digest` — the raw bytes are never a database column.
     *
     * @return `true` when the recomputed digest matches the recorded one; `false`
     *   otherwise (or when no raw file is associated).
     */
    fun verifyIntegrity(versionId: VersionId): Boolean

    /**
     * Records that [versionId]'s retained raw snapshot failed its integrity check,
     * flagging the version **unusable for reconstruction** while preserving its
     * recorded `Digest` for audit (Req 14.5).
     *
     * This only flips the version's
     * [integrityOk][com.spike.ofac.pipeline.models.VersionMetadata.integrityOk] to
     * `false`; it never mutates or deletes the version's identity or records — the
     * (`Publish_Date`, `Digest`) stays intact so the failure remains auditable. The
     * `RetentionManager.checkColdIntegrity` calls it on a digest mismatch.
     *
     * The default implementation is a no-op so lightweight reference/test stores
     * (which hold no real raw files) satisfy the contract without persistence; the
     * concrete stores override it to persist the flag.
     */
    fun markUnusable(versionId: VersionId) {
        // Default no-op: reference/test stores hold no persisted integrity flag.
    }
}

/**
 * The three pointer slots of a list's HOT window (Req 9, Req 10.1).
 *
 * [CURRENT] is the active version served to consumers; [PREVIOUS] and [N_MINUS_2]
 * are the two prior versions retained for rollback (Req 10.3) and the window
 * bound of at most three HOT versions (Req 10.5).
 */
enum class PointerKind {
    CURRENT,
    PREVIOUS,
    N_MINUS_2,
}
