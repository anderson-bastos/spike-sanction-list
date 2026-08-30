package com.spike.ofac.pipeline.models

import java.nio.file.Path
import java.time.Instant

/**
 * Immutable metadata describing one persisted version of a [SourceList].
 *
 * One row per version in the `versions` table; rows are insert-only and never
 * mutated (Req 7.5). Carries the reconciliation counts (Req 8), the HOT/COLD
 * lifecycle [state] (Req 10, 14), and the optional filesystem path to the stored
 * raw snapshot file (Req 15).
 *
 * @property versionId the (Publish_Date, Digest) identity of this version (Req 7.2).
 * @property sourceList the list this version belongs to; each list versions on an
 *   independent line (Req 10.2).
 * @property recordCount source-reported `<Record_Count>` from the snapshot body (Req 8).
 * @property outOfScopeCount vessels + aircraft excluded by the scope filter (Req 5, 8.1).
 * @property overlapCount shared FixedRefs removed by dedup when multi-list; zero for
 *   single-list scope (Req 6, 8.1).
 * @property expectedCount `recordCount - outOfScopeCount - overlapCount` (Req 8.1).
 * @property persistedCount in-scope, post-dedup count actually persisted; must equal
 *   [expectedCount] for activation (Req 8.2).
 * @property state HOT (CURRENT/PREVIOUS/N_MINUS_2) or COLD (displaced) (Req 10, 14).
 * @property ingestedAt when this version was persisted.
 * @property rawSnapshotPath filesystem path into the local Raw_Snapshot_Store for the
 *   stored raw file, derived from (Publish_Date, Digest); NOT a database bytea column.
 *   Populated only after the stored file's SHA-256 matches the recorded Digest
 *   (Req 15.1, 15.2, 15.5, 15.6). Null until that association is made.
 * @property integrityOk result of the last integrity check over the stored raw FILE
 *   bytes vs the recorded Digest; null when never checked (Req 14.5, 15.5).
 */
data class VersionMetadata(
    val versionId: VersionId,
    val sourceList: SourceList,
    val recordCount: Int,
    val outOfScopeCount: Int,
    val overlapCount: Int,
    val expectedCount: Int,
    val persistedCount: Int,
    val state: VersionState,
    val ingestedAt: Instant,
    val rawSnapshotPath: Path? = null,
    val integrityOk: Boolean? = null,
)

/**
 * The source list a version belongs to. Each list versions on an independent line,
 * so operations on one never touch another's versions or pointers (Req 10.2).
 */
enum class SourceList {
    SDN,
    CONSOLIDATED,
}

/**
 * Lifecycle state of a version within the operational window.
 *
 * [HOT] versions are the ones addressed by the pointer trio (CURRENT / PREVIOUS /
 * N_MINUS_2); [COLD] versions have been displaced past N_MINUS_2 and are retained
 * for audit without deletion or mutation (Req 10.5, 14).
 */
enum class VersionState {
    HOT,
    COLD,
}
