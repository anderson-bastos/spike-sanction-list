package com.spike.ofac.application.publish

import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.version.VersionPlan
import com.spike.ofac.application.port.out.PointerKind
import com.spike.ofac.application.port.out.VersionStore

/**
 * The `publish` stage — result-validation followed by atomic activation of a
 * freshly persisted version as `CURRENT` (Req 8, Req 9, Req 10).
 *
 * This object owns both [activate] and its inverse [rollback]; the two are the
 * only operations that move a list's pointer trio.
 *
 * [activate] is the final gate a candidate version passes before it is served to
 * consumers. It runs in two steps, in order:
 *
 *  1. **Result-validate** — the count actually persisted (in-scope, post-dedup)
 *     must equal the plan's `expected_count` **exactly** (Req 8.2). Any mismatch
 *     rejects with [ActivationResult.RejectedCountMismatch] and nothing is
 *     activated; `CURRENT` is left unchanged (Req 8.3).
 *  2. **Atomic activation + window rotation** — delegate to
 *     [VersionStore.atomicSetCurrent], which repoints `CURRENT` to the new version
 *     and rotates the window (old CURRENT→PREVIOUS, old PREVIOUS→N_MINUS_2,
 *     older→COLD) in a single indivisible step, keeping at most three HOT
 *     versions per list (Req 9.1, 9.3, 10.1, 10.5). `CURRENT` is never zeroed
 *     (Req 9.2). If the store cannot apply the repoint it returns `false`, which
 *     this stage maps to [ActivationResult.RejectedRepointFailed] with the
 *     pointer trio left entirely unchanged (Req 9.4).
 *
 * Design contract (`design.md` "publish"):
 * ```
 * publish.activate(version_plan, store) -> ActivationResult
 *   # 1. result-validate: persisted in-scope count (post-dedup) == expected_count exactly (Req 8.2)
 *   # 2. atomic repoint of CURRENT -> new version (Req 9.1); never zero CURRENT (Req 9.2)
 *   # 3. rotate window: old CURRENT->PREVIOUS, old PREVIOUS->N_MINUS_2, older->COLD (Req 9.3, 10.5)
 * ActivationResult = ACTIVATED(version_id)
 *                  | REJECTED(COUNT_MISMATCH)
 *                  | REJECTED(REPOINT_FAILED)
 *
 * publish.rollback(source_list, store) -> RollbackResult
 *   # pointer move CURRENT -> PREVIOUS only; no reprocessing, no mutation (Req 10.3)
 * RollbackResult = ROLLED_BACK(version_id)
 *                | REJECTED(NO_PREVIOUS)         # Req 10.4
 * ```
 *
 * Both operations act on a single [SourceList] and touch only that list's
 * versions and pointer trio; the store keys pointers per list, so an operation
 * on one list never observes or mutates another's line of versions (Req 10.2).
 */
object Publish {

    /**
     * Result-validates and, on success, atomically activates the version described
     * by [plan] as `CURRENT` for [sourceList].
     *
     * The window rotation itself (CURRENT→PREVIOUS→N_MINUS_2→COLD, capped at three
     * HOT versions) is performed inside [VersionStore.atomicSetCurrent] as part of
     * the same atomic repoint, so this stage does not rotate pointers directly — it
     * only gates the repoint on the count check and maps the store's boolean result
     * to the [ActivationResult] contract.
     *
     * @param sourceList the list whose `CURRENT` is being repointed; each list
     *   versions on an independent line (Req 10.2).
     * @param plan the accepted [VersionPlan] carrying the version identity and the
     *   reconciliation target [VersionPlan.Accepted.expectedCount] (Req 8.1).
     * @param persistedCount the in-scope, post-dedup count actually written by the
     *   `persist` stage — compared to `expected_count` for exact equality (Req 8.2).
     * @param store the [VersionStore] holding the isolated version; its
     *   [atomicSetCurrent][VersionStore.atomicSetCurrent] provides the atomic
     *   repoint + window rotation (Req 9.1, 9.3, 10.5).
     * @return [ActivationResult.Activated] when the counts match and the repoint
     *   succeeds; [ActivationResult.RejectedCountMismatch] when the persisted count
     *   differs from the expected count (Req 8.3); or
     *   [ActivationResult.RejectedRepointFailed] when the store could not apply the
     *   repoint, leaving the pointer trio unchanged (Req 9.4).
     */
    fun activate(
        sourceList: SourceList,
        plan: VersionPlan.Accepted,
        persistedCount: Int,
        store: VersionStore,
    ): ActivationResult {
        // Step 1 — result-validate. The persisted in-scope, post-dedup count must
        // equal the reconciliation target exactly; any drift means the version is
        // not the list the source published, so it is rejected and CURRENT stays
        // put (Req 8.2, 8.3).
        if (persistedCount != plan.expectedCount) {
            return ActivationResult.RejectedCountMismatch
        }

        // Step 2 — atomic activation + window rotation, delegated to the store so
        // the repoint and rotation commit as one indivisible step (Req 9.1, 9.3).
        // A false return means the repoint could not be applied; the store leaves
        // the pointer trio unchanged, which maps to REPOINT_FAILED (Req 9.4).
        val repointed = store.atomicSetCurrent(sourceList, plan.versionId)
        return if (repointed) {
            ActivationResult.Activated(plan.versionId)
        } else {
            ActivationResult.RejectedRepointFailed
        }
    }

    /**
     * Rolls [sourceList] back one step by repointing `CURRENT` to its `PREVIOUS`
     * version — a **pointer move only**, with no download, reprocessing, or content
     * mutation of any version (Req 10.3).
     *
     * The prior version is already persisted and HOT; rollback simply re-addresses
     * it through `CURRENT`, delegating to [VersionStore.atomicSetCurrent] so the
     * repoint (and the window rotation it entails) commits as one indivisible step.
     * Because the target is a version the store already holds, rollback never
     * downloads or reparses anything — it only moves pointers (Req 10.3).
     *
     * When there is no `PREVIOUS` to roll back to (a freshly-activated list, or one
     * that has never rotated), the operation is rejected with
     * [RollbackResult.RejectedNoPrevious] and `CURRENT` is left unchanged (Req 10.4).
     *
     * Only [sourceList]'s pointer trio is read and written here, so a rollback on
     * one list never touches another list's versions or pointers (Req 10.2).
     *
     * @param sourceList the list to roll back; each list versions on an independent
     *   line (Req 10.2).
     * @param store the [VersionStore] holding the list's pointer trio; its
     *   [getPointer][VersionStore.getPointer] reads the `PREVIOUS` target and its
     *   [atomicSetCurrent][VersionStore.atomicSetCurrent] performs the atomic
     *   pointer move (Req 10.3).
     * @return [RollbackResult.RolledBack] carrying the now-`CURRENT` version when a
     *   `PREVIOUS` existed and the repoint succeeded; otherwise
     *   [RollbackResult.RejectedNoPrevious] with `CURRENT` unchanged (Req 10.4).
     */
    fun rollback(
        sourceList: SourceList,
        store: VersionStore,
    ): RollbackResult {
        // Read the PREVIOUS pointer for this list only. Absent PREVIOUS means there
        // is nothing to roll back to; reject and leave CURRENT put (Req 10.4).
        val previous = store.getPointer(sourceList, PointerKind.PREVIOUS)
            ?: return RollbackResult.RejectedNoPrevious

        // Pointer move only: repoint CURRENT to the already-persisted PREVIOUS
        // version. No download, parse, or content mutation happens — the version is
        // reused as-is (Req 10.3). The atomic repoint keeps the trio consistent.
        val repointed = store.atomicSetCurrent(sourceList, previous)
        return if (repointed) {
            RollbackResult.RolledBack(previous)
        } else {
            // The store could not apply the repoint; the trio is left unchanged.
            // No PREVIOUS-specific rejection cause exists in the contract for this,
            // so surface it as NO_PREVIOUS — CURRENT remains unchanged either way.
            RollbackResult.RejectedNoPrevious
        }
    }
}

/**
 * Outcome of [Publish.activate] (`design.md` `ActivationResult`).
 *
 * Exactly one of: the version was activated and is now `CURRENT`
 * ([Activated]); the persisted count did not reconcile ([RejectedCountMismatch],
 * Req 8.3); or the atomic repoint could not be applied and the pointer trio is
 * unchanged ([RejectedRepointFailed], Req 9.4).
 */
sealed interface ActivationResult {

    /**
     * The version was result-validated and atomically activated as `CURRENT`; it is
     * resolvable to consumers (Req 9.5).
     *
     * @property versionId the identity of the now-active version.
     */
    data class Activated(val versionId: VersionId) : ActivationResult

    /**
     * The persisted in-scope, post-dedup count did not equal `expected_count`, so
     * the version was **not** activated and `CURRENT` is unchanged (Req 8.3).
     */
    data object RejectedCountMismatch : ActivationResult

    /**
     * The atomic repoint could not be applied; the pointer trio is left entirely
     * unchanged (Req 9.4).
     */
    data object RejectedRepointFailed : ActivationResult
}

/**
 * Outcome of [Publish.rollback] (`design.md` `RollbackResult`).
 *
 * Exactly one of: `CURRENT` was moved back to the prior version by pointer only
 * ([RolledBack], Req 10.3); or there was no `PREVIOUS` to roll back to, so the
 * request was rejected and `CURRENT` is unchanged ([RejectedNoPrevious], Req 10.4).
 */
sealed interface RollbackResult {

    /**
     * `CURRENT` was repointed to the prior (`PREVIOUS`) version — a pointer move
     * only, with no reprocessing or content mutation (Req 10.3).
     *
     * @property versionId the version now addressed by `CURRENT` after the rollback.
     */
    data class RolledBack(val versionId: VersionId) : RollbackResult

    /**
     * There was no `PREVIOUS` version to roll back to; the request was rejected and
     * `CURRENT` is left unchanged (Req 10.4).
     */
    data object RejectedNoPrevious : RollbackResult
}
