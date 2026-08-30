package com.spike.ofac.pipeline.stages

import com.spike.ofac.pipeline.models.EntityType
import com.spike.ofac.pipeline.models.FixedRef
import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.ScopeConfig
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.models.SourceList
import com.spike.ofac.pipeline.models.VersionId
import com.spike.ofac.pipeline.stages.publish.ActivationResult
import com.spike.ofac.pipeline.stages.publish.Publish
import com.spike.ofac.pipeline.store.InMemoryVersionStore
import com.spike.ofac.pipeline.store.PointerKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Example-level unit tests for invalid `Record_Count` handling (task 7.4).
 *
 * Covers two distinct facets:
 *
 *  - **Req 8.4 (missing/invalid Record_Count):** [VersionStage.build] rejects with
 *    [RejectionReason.RECORD_COUNT_MISSING_OR_INVALID] when the source-reported
 *    `<Record_Count>` is absent, blank, non-numeric, or negative; a valid numeric
 *    value yields an [VersionPlan.Accepted] plan with the reconciled
 *    `expected_count`.
 *  - **Req 8.3 (count mismatch):** enforced at publish time —
 *    [Publish.activate] rejects with [ActivationResult.RejectedCountMismatch] when
 *    the count actually persisted differs from the plan's `expected_count` (here by
 *    dropping one record), leaving `CURRENT` unchanged.
 *
 * The exhaustive property coverage is Property 8 (task 7.2); these are the
 * focused examples and edge cases.
 */
class VersionStageInvalidRecordCountTest {

    private val publishDate = LocalDate.of(2024, 3, 1)
    private val digest = Sha256Digest("a".repeat(64))
    private val versionId = VersionId(publishDate, digest)

    private fun entries(n: Int): List<InternalModelEntry> =
        (1..n).map {
            InternalModelEntry(
                fixedRef = FixedRef(it.toString()),
                entityType = EntityType.Individual,
                primaryName = "Name $it",
                sanctionPrograms = listOf("PROGRAM"),
            )
        }

    private fun build(rawRecordCount: String?, outOfScopeCount: Int = 0): VersionPlan =
        VersionStage.build(
            entries = emptyList(),
            publishDate = publishDate,
            digest = digest,
            scope = ScopeConfig.SDN_ONLY,
            rawRecordCount = rawRecordCount,
            outOfScopeCount = outOfScopeCount,
        )

    // --- Req 8.4: absent Record_Count ---

    @Test
    fun `null Record_Count is rejected as missing or invalid`() {
        build(rawRecordCount = null) shouldBe
            VersionPlan.Rejected(RejectionReason.RECORD_COUNT_MISSING_OR_INVALID)
    }

    // --- Req 8.4: non-numeric Record_Count ---

    @Test
    fun `non-numeric Record_Count values are rejected as missing or invalid`() {
        listOf("abc", " ", "", "12x", "1.5", "1,000").forEach { raw ->
            build(rawRecordCount = raw) shouldBe
                VersionPlan.Rejected(RejectionReason.RECORD_COUNT_MISSING_OR_INVALID)
        }
    }

    @Test
    fun `negative Record_Count is rejected as invalid`() {
        build(rawRecordCount = "-1") shouldBe
            VersionPlan.Rejected(RejectionReason.RECORD_COUNT_MISSING_OR_INVALID)
    }

    // --- Req 8.4 / 8.1: valid numeric Record_Count is accepted ---

    @Test
    fun `valid numeric Record_Count is accepted with the reconciled expected count`() {
        // Record_Count = 10, out_of_scope = 3, single-list scope -> expected 7 (Req 8.1).
        val plan = build(rawRecordCount = "10", outOfScopeCount = 3)

        val accepted = plan.shouldBeInstanceOf<VersionPlan.Accepted>()
        accepted.versionId shouldBe versionId
        accepted.expectedCount shouldBe 7
    }

    @Test
    fun `valid Record_Count with surrounding whitespace is trimmed and accepted`() {
        val plan = build(rawRecordCount = "  5  ")

        val accepted = plan.shouldBeInstanceOf<VersionPlan.Accepted>()
        accepted.expectedCount shouldBe 5
    }

    // --- Req 8.3: publish-time count mismatch (drop one record) ---

    @Test
    fun `dropping one persisted record is rejected as a count mismatch at publish time`() {
        // A snapshot reporting 3 records, all in scope -> expected_count = 3 (Req 8.1).
        val plan = build(rawRecordCount = "3").shouldBeInstanceOf<VersionPlan.Accepted>()
        plan.expectedCount shouldBe 3

        // Persist only two of the three records (one was dropped during transform).
        val store = InMemoryVersionStore()
        val droppedOne = entries(2)
        store.putIsolated(plan.versionId, droppedOne)

        val result = Publish.activate(
            sourceList = SourceList.SDN,
            plan = plan,
            persistedCount = droppedOne.size,
            store = store,
        )

        // The persisted count (2) != expected_count (3): rejected, CURRENT unchanged (Req 8.3).
        result shouldBe ActivationResult.RejectedCountMismatch
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    @Test
    fun `exact persisted count activates the version`() {
        val plan = build(rawRecordCount = "3").shouldBeInstanceOf<VersionPlan.Accepted>()

        val store = InMemoryVersionStore()
        val allThree = entries(3)
        store.putIsolated(plan.versionId, allThree)

        val result = Publish.activate(
            sourceList = SourceList.SDN,
            plan = plan,
            persistedCount = allThree.size,
            store = store,
        )

        result shouldBe ActivationResult.Activated(plan.versionId)
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe plan.versionId
    }
}
