package com.spike.ofac.pipeline.stages

import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.ScopeConfig
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.models.VersionId
import java.time.LocalDate

/**
 * The `version` stage: build the [VersionId] identity and derive the
 * `Expected_Count` for a validated snapshot (Req 7, Req 8).
 *
 * This is **pure logic** — no I/O, no persistence. It consumes the counts
 * observed upstream (`transform`'s `record_count` / `out_of_scope_count` and the
 * dedup overlap term) plus the snapshot's identity fields, and produces a
 * [VersionPlan] describing what a later `persist`/`publish` should expect.
 *
 * Design contract (`design.md` "version"):
 * ```
 * version.build(entries, snapshot, publish_date, digest, scope) -> VersionPlan
 * VersionPlan = { version_id, expected_count }
 *             | REJECTED(RECORD_COUNT_MISSING_OR_INVALID)
 *
 * Expected_Count = Record_Count - out_of_scope_count - shared_fixedref_overlaps
 *                  (overlap term zero for single-list scope)
 * ```
 */
object VersionStage {

    /**
     * Builds the [VersionPlan] for a validated snapshot.
     *
     * The [rawRecordCount] is the source-reported `<Record_Count>` **as read from
     * the snapshot body**, before any interpretation — it is a raw string (or
     * `null` when the field is absent) precisely so this stage can enforce the
     * "missing or non-numeric" rejection (Req 8.4). A numeric, non-negative value
     * is required; anything else is rejected with
     * [VersionPlan.Rejected] carrying [RejectionReason.RECORD_COUNT_MISSING_OR_INVALID].
     *
     * When the plan is accepted, [VersionPlan.Accepted.expectedCount] is derived by
     * the reconciliation formula (Req 8.1):
     * ```
     * expected_count = record_count - out_of_scope_count - shared_fixedref_overlaps
     * ```
     * The overlap term is zero for a single-list scope ([ScopeConfig.SDN_ONLY]); it
     * is only meaningful when the scope includes both lists
     * ([ScopeConfig.SDN_AND_CONSOLIDATED]) where cross-list dedup can remove shared
     * `FixedRef`s (Req 6, Req 8.1).
     *
     * @param entries the transformed in-scope entries (used only for [stampVersion]
     *   at persist time; not needed to derive the plan itself).
     * @param publishDate the `<Publish_Date>` read from the snapshot body (Req 7.2).
     * @param digest the SHA-256 of the raw snapshot bytes (Req 7.2, 7.3).
     * @param scope the configured list scope; decides whether the overlap term is
     *   applied (Req 6, Req 12).
     * @param rawRecordCount the source-reported `<Record_Count>` verbatim, or `null`
     *   when the field is absent (Req 8.4).
     * @param outOfScopeCount vessels + aircraft excluded by the scope filter (Req 5, 8.1).
     * @param sharedFixedRefOverlaps shared `FixedRef`s removed by cross-list dedup;
     *   ignored (forced to zero) for a single-list scope (Req 6, 8.1).
     */
    fun build(
        entries: List<InternalModelEntry>,
        publishDate: LocalDate,
        digest: Sha256Digest,
        scope: ScopeConfig,
        rawRecordCount: String?,
        outOfScopeCount: Int,
        sharedFixedRefOverlaps: Int = 0,
    ): VersionPlan {
        val recordCount = parseRecordCount(rawRecordCount)
            ?: return VersionPlan.Rejected(RejectionReason.RECORD_COUNT_MISSING_OR_INVALID)

        // The overlap term is only meaningful when the scope spans both lists;
        // for a single-list scope it is zero regardless of what was passed (Req 8.1).
        val overlap = if (scope == ScopeConfig.SDN_AND_CONSOLIDATED) sharedFixedRefOverlaps else 0

        val expectedCount = recordCount - outOfScopeCount - overlap

        return VersionPlan.Accepted(
            versionId = VersionId(publishDate, digest),
            expectedCount = expectedCount,
        )
    }

    /**
     * Stamps [versionId] onto every entry (Req 7.4). `transform` produces entries
     * with a `null` [InternalModelEntry.versionId]; the `persist` stage calls this
     * to bind each record to the version it is written under.
     */
    fun stampVersion(
        entries: List<InternalModelEntry>,
        versionId: VersionId,
    ): List<InternalModelEntry> = entries.map { it.copy(versionId = versionId) }

    /**
     * Interprets the raw `<Record_Count>` field.
     *
     * Returns the numeric value when [raw] is present and parses to a non-negative
     * integer; returns `null` (signalling rejection) when the field is absent,
     * blank, or non-numeric (Req 8.4). A negative count is treated as invalid — a
     * record count cannot be negative.
     */
    private fun parseRecordCount(raw: String?): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        return if (value >= 0) value else null
    }
}

/**
 * Outcome of [VersionStage.build] (`design.md` `VersionPlan`).
 *
 * Either an [Accepted] plan carrying the version identity and derived
 * `expected_count`, or a [Rejected] outcome naming why the plan could not be
 * built (Req 8.4).
 */
sealed interface VersionPlan {

    /**
     * An accepted plan: the snapshot has a valid `Record_Count` and a derived
     * reconciliation target.
     *
     * @property versionId the (Publish_Date, Digest) identity (Req 7.2).
     * @property expectedCount `Record_Count - out_of_scope_count - overlaps` (Req 8.1).
     */
    data class Accepted(
        val versionId: VersionId,
        val expectedCount: Int,
    ) : VersionPlan

    /**
     * A rejected plan: the version could not be built. Nothing is persisted and
     * CURRENT is left unchanged (Req 11).
     *
     * @property reason the distinct cause of rejection.
     */
    data class Rejected(
        val reason: RejectionReason,
    ) : VersionPlan
}

/** Distinct reasons [VersionStage.build] can reject a snapshot. */
enum class RejectionReason {
    /** `Record_Count` was absent or non-numeric (Req 8.4). */
    RECORD_COUNT_MISSING_OR_INVALID,
}
