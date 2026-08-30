package com.spike.ofac.application.retention

import com.spike.ofac.domain.model.RetentionPolicy
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.application.port.out.RawSnapshotStore
import com.spike.ofac.application.port.out.VersionStore
import org.springframework.stereotype.Component

/**
 * Owns the retention lifecycle (Req 14), design.md "### RetentionManager".
 *
 * It is the single component that **applies** the [RetentionPolicy] after each
 * successful activation / window rotation. It sits between the [VersionStore]
 * (which reclassifies versions displaced past `N_MINUS_2` as
 * [COLD][com.spike.ofac.domain.model.VersionState.COLD]) and the
 * [RawSnapshotStore] (which holds the raw snapshot files), and holds **no policy
 * state of its own** — the [RetentionPolicy] is injected configuration handed to
 * each call.
 *
 * **Separation of concerns (Req 10.5 vs Req 14).** Window rotation always demotes
 * versions older than `N_MINUS_2` to `COLD` — that is owned by `publish` /
 * [VersionStore.reclassifyCold] (Req 10.5). Retention then decides whether those
 * `COLD` versions are kept for the configured `retention_period` or discarded
 * outright (Req 14). Rotation demotes; retention keeps-or-drops.
 *
 * This class implements [applyAfterActivation] (task 19.1) and [checkColdIntegrity]
 * (task 19.2).
 *
 * **Preserve form (Req 14.3).** When retention is enabled and `RAW` is preserved,
 * the retained raw snapshot for a `COLD` version is the immutable local **versioned
 * file** already written to the `Raw_Snapshot_Store` (task 13.3), keyed by
 * (`Publish_Date`, `Digest`) — never a PostgreSQL `bytea`/large object. Faithful
 * reconstruction of a past list state relies on that file, since OFAC never
 * republishes past versions.
 *
 * Design contract (`design.md` "RetentionManager"):
 * ```
 * interface RetentionManager:
 *   apply_after_activation(source_list, policy: RetentionPolicy) -> void
 *   check_cold_integrity(version_id) -> IntegrityOutcome
 *
 * IntegrityOutcome = OK | FLAGGED_UNUSABLE(recorded_digest)
 * ```
 *
 * @param versionStore reclassifies displaced versions as `COLD`, enumerates the
 *   `COLD` set per list, and flags a version unusable on integrity failure
 *   (Req 10.5, Req 14).
 * @param rawSnapshotStore holds the raw files; used to discard them when retention
 *   is disabled (Req 14.4) and to verify a `COLD` version's stored file (Req 14.5).
 */
@Component
class RetentionManager(
    private val versionStore: VersionStore,
    private val rawSnapshotStore: RawSnapshotStore,
) {

    /**
     * Applies [policy] to [sourceList] after `publish` completes a window rotation
     * (Req 14.1, 14.2, 14.4).
     *
     * The step always begins by reclassifying versions displaced past `N_MINUS_2`
     * as `COLD` via [VersionStore.reclassifyCold] (Req 10.5), so the retain-vs-drop
     * decision operates on the up-to-date `COLD` set. Then:
     *
     *  - **Retention ENABLED** — every version displaced past `N_MINUS_2` is left
     *    classified `COLD` and retained together with its raw snapshot file in the
     *    `Raw_Snapshot_Store` for the configured `retention_period` (read from the
     *    injected [policy], no fixed default). Nothing is deleted (Req 14.1, 14.2).
     *  - **Retention DISABLED** — every displaced (`COLD`) version is discarded,
     *    including its raw snapshot file in the `Raw_Snapshot_Store` via
     *    [RawSnapshotStore.delete] (Req 14.4).
     *
     * The whole operation is scoped to [sourceList] — [VersionStore] reads and
     * writes are per-list, so retaining or discarding on one list never affects
     * another (Req 14.1, 14.2, per-list independence).
     *
     * @param sourceList the list whose displaced versions the policy applies to.
     * @param policy the injected retention configuration (enabled flag, period,
     *   preserve form); this component reads it rather than assuming values.
     */
    fun applyAfterActivation(sourceList: SourceList, policy: RetentionPolicy) {
        // Rotation-owned demotion (Req 10.5): make sure everything displaced past
        // N_MINUS_2 is COLD before we decide retain-vs-drop for this list.
        versionStore.reclassifyCold(sourceList)

        if (policy.enabled) {
            // ENABLED: the displaced versions stay COLD and are retained together
            // with their raw snapshot files for the configured retention_period
            // (Req 14.1, 14.2). Nothing is discarded; the raw files are kept.
            return
        }

        // DISABLED: discard every displaced (COLD) version's raw snapshot file
        // from the local Raw_Snapshot_Store (Req 14.4). delete is idempotent, so a
        // file already gone (e.g. discarded on a prior activation) is a no-op.
        versionStore.coldVersions(sourceList).forEach { versionId ->
            rawSnapshotStore.delete(versionId)
        }
    }

    /**
     * Verifies a retained `COLD` version's stored raw snapshot **file** and reports
     * the outcome (Req 14.5).
     *
     * The check delegates to [RawSnapshotStore.verifyIntegrity], which recomputes
     * SHA-256 over the stored **file** bytes (never a database `bytea`) and compares
     * it against the `Digest` recorded in [versionId]:
     *
     *  - **match** — the retained file still reconstructs faithfully, so the outcome
     *    is [IntegrityOutcome.Ok].
     *  - **mismatch** (or the file is missing / unreadable) — the version can no
     *    longer be reconstructed, so it is flagged **unusable** via
     *    [VersionStore.markUnusable] (which only flips the integrity flag, leaving
     *    identity and records intact) and the outcome is
     *    [IntegrityOutcome.FlaggedUnusable] carrying the **recorded** `Digest` so the
     *    failure stays auditable.
     *
     * **Trigger is intentionally open.** This is exposed as an on-demand /
     * where-scheduled operation; the requirements do not fix *when* it runs, and this
     * component invents no schedule. Whether it fires on read, on a periodic sweep,
     * or on demand is a deployment/configuration decision (design note).
     *
     * @param versionId the (`Publish_Date`, `Digest`) identity of the `COLD` version
     *   whose stored raw file to verify.
     * @return [IntegrityOutcome.Ok] on a digest match, otherwise
     *   [IntegrityOutcome.FlaggedUnusable] preserving the recorded `Digest`.
     */
    fun checkColdIntegrity(versionId: VersionId): IntegrityOutcome {
        val intact = rawSnapshotStore.verifyIntegrity(versionId)
        if (intact) {
            return IntegrityOutcome.Ok
        }

        // Mismatch (or missing/unreadable file): the version is no longer
        // reconstructable. Flag it unusable while preserving the recorded Digest for
        // audit (Req 14.5); markUnusable never touches identity or records.
        versionStore.markUnusable(versionId)
        return IntegrityOutcome.FlaggedUnusable(versionId.digest)
    }
}

/**
 * Result of a `COLD` version integrity check (design.md "RetentionManager"):
 * ```
 * IntegrityOutcome = OK | FLAGGED_UNUSABLE(recorded_digest)
 * ```
 *
 * Reported by [RetentionManager.checkColdIntegrity] (Req 14.5).
 */
sealed interface IntegrityOutcome {

    /** The stored raw file's SHA-256 matches the recorded `Digest`; still usable. */
    data object Ok : IntegrityOutcome

    /**
     * The stored raw file failed verification; the version is unusable for
     * reconstruction. Carries the [recordedDigest] — the `Digest` originally
     * recorded for the version — so the failure remains auditable (Req 14.5).
     *
     * @property recordedDigest the version's recorded SHA-256 `Digest`, preserved
     *   for audit even though the stored file no longer reproduces it.
     */
    data class FlaggedUnusable(val recordedDigest: Sha256Digest) : IntegrityOutcome
}
