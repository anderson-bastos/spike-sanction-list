package com.spike.ofac.adapter.out.persistence

import com.spike.ofac.application.port.out.PointerKind

import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.version.VersionPlan
import com.spike.ofac.application.publish.ActivationResult
import com.spike.ofac.application.publish.Publish
import com.spike.ofac.application.publish.RollbackResult
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Tag
import java.time.LocalDate

/**
 * Property 12: Activate-then-rollback restores the prior CURRENT (round-trip).
 *
 * When a list already has a CURRENT version (`vPrev`) and a new version (`vNew`)
 * is activated, `vPrev` moves to PREVIOUS and `vNew` becomes CURRENT. Rolling
 * back must then repoint CURRENT to *exactly* `vPrev` — the same version, byte
 * for byte — using a **pointer move only**: no download, no reprocessing, no
 * mutation of either version's records or identity (Req 10.3).
 *
 * This is a stateful, model-based property driven over the reference
 * [InMemoryVersionStore] and the real [Publish] activate/rollback operations.
 * Each trial builds a state with a genuine PREVIOUS by activating a randomized
 * warm-up sequence `v0..vPrev` (so PREVIOUS can be any prior version, including
 * one displaced through a rotation), then performs the round-trip:
 *
 *  1. build the pre-activation state and capture CURRENT (`vPrev`);
 *  2. activate `vNew` — assert CURRENT == vNew and PREVIOUS == vPrev;
 *  3. snapshot both versions' records and metadata;
 *  4. rollback — assert CURRENT is restored to *exactly* vPrev;
 *  5. assert vNew and vPrev records + identity are unchanged (pointer-move only).
 *
 * Activation is driven through [Publish.activate] with a
 * [VersionPlan.Accepted] whose `expectedCount` equals the `persistedCount`
 * passed in, so the result-validation gate always passes and we exercise the
 * real activation path (Req 8.2) rather than seeding pointers directly.
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 12: Activate-then-rollback
 * restores the prior CURRENT (round-trip)`.
 *
 * **Validates: Requirements 10.3**
 */
@Tag(PropertyTests.FEATURE_TAG)
class ActivateRollbackRoundTripPropertyTest {

    /**
     * A single generated round-trip scenario.
     *
     * @property warmUpCount how many versions to activate to reach the
     *   pre-activation state (>= 1, so a CURRENT — the eventual PREVIOUS —
     *   always exists before `vNew` is activated).
     * @property recordCounts the number of records held by each version, indexed
     *   in activation order; the last warm-up entry sizes `vPrev` and the final
     *   entry sizes `vNew`.
     */
    data class Scenario(
        val warmUpCount: Int,
        val recordCounts: List<Int>,
    )

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 12: Activate-then-rollback restores the prior CURRENT (round-trip)")
    fun activateThenRollbackRestoresPriorCurrent(
        @ForAll @From("scenarios") scenario: Scenario,
    ) {
        val store = InMemoryVersionStore()

        // --- Build the pre-activation state: activate v0..vPrev so a genuine
        // CURRENT exists (the version rollback must restore to). Each activation
        // goes through the real Publish.activate gate with matching counts.
        val warmUp = (0 until scenario.warmUpCount).map { i ->
            val versionId = versionId(index = i)
            val records = recordsFor(versionId, count = scenario.recordCounts[i])
            store.putIsolated(versionId, records)
            val plan = VersionPlan.Accepted(versionId, expectedCount = records.size)
            val result = Publish.activate(SourceList.SDN, plan, persistedCount = records.size, store)
            result shouldBe ActivationResult.Activated(versionId)
            versionId
        }

        // vPrev is the CURRENT of the pre-activation state — the round-trip target.
        val vPrev = warmUp.last()
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe vPrev
        // Records/identity of vPrev as they stand *before* the new activation.
        val vPrevRecordsBefore = store.recordsOf(vPrev).shouldNotBeNull()
        val vPrevMetadataBefore = store.metadataOf(vPrev).shouldNotBeNull()

        // --- Step 2: activate vNew. vPrev must slide to PREVIOUS, vNew to CURRENT.
        val vNew = versionId(index = scenario.warmUpCount)
        val vNewRecords = recordsFor(vNew, count = scenario.recordCounts.last())
        store.putIsolated(vNew, vNewRecords)
        val activatePlan = VersionPlan.Accepted(vNew, expectedCount = vNewRecords.size)
        val activation =
            Publish.activate(SourceList.SDN, activatePlan, persistedCount = vNewRecords.size, store)

        activation shouldBe ActivationResult.Activated(vNew)
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe vNew
        store.getPointer(SourceList.SDN, PointerKind.PREVIOUS) shouldBe vPrev

        // Snapshot both versions right after activation to prove rollback mutates
        // nothing (pointer-move only, Req 10.3).
        val vNewRecordsAfterActivate = store.recordsOf(vNew).shouldNotBeNull()
        val vPrevRecordsAfterActivate = store.recordsOf(vPrev).shouldNotBeNull()

        // --- Step 4: rollback. CURRENT must be restored to *exactly* vPrev.
        val rollback = Publish.rollback(SourceList.SDN, store)

        rollback shouldBe RollbackResult.RolledBack(vPrev)
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe vPrev

        // --- Step 5: round-trip fidelity. vPrev is restored to the exact same
        // version it was pre-activation — same identity, byte-identical records
        // (no reprocessing, no mutation). vNew is likewise untouched: rollback
        // moved pointers only, it did not rewrite or drop any version (Req 10.3).
        val vPrevRecordsAfterRollback = store.recordsOf(vPrev).shouldNotBeNull()
        val vPrevMetadataAfterRollback = store.metadataOf(vPrev).shouldNotBeNull()

        vPrevRecordsAfterRollback shouldContainExactly vPrevRecordsBefore
        vPrevRecordsAfterRollback shouldContainExactly vPrevRecordsAfterActivate
        // Identity (versionId) and record counts are immutable across the trip.
        vPrevMetadataAfterRollback.versionId shouldBe vPrevMetadataBefore.versionId
        vPrevMetadataAfterRollback.recordCount shouldBe vPrevMetadataBefore.recordCount

        // vNew's records are unchanged — the version rolled *away from* is not
        // deleted or mutated, it is retained exactly as activated.
        val vNewRecordsAfterRollback = store.recordsOf(vNew).shouldNotBeNull()
        vNewRecordsAfterRollback shouldContainExactly vNewRecordsAfterActivate
        store.metadataOf(vNew).shouldNotBeNull().versionId shouldBe vNew
    }

    /**
     * Generates round-trip scenarios.
     *
     * `warmUpCount` is 1..4 so the pre-activation CURRENT can be a version that
     * itself passed through window rotations (exercising a PREVIOUS that is not
     * merely the very first activation). Record counts are small and per-version
     * so vPrev and vNew can differ in size, and each is >= 1 (a persisted
     * in-scope entry must exist).
     */
    @Provide
    fun scenarios(): Arbitrary<Scenario> =
        Arbitraries.integers().between(1, 4).flatMap { warmUpCount ->
            // One count per warm-up version plus one for vNew.
            Arbitraries.integers().between(1, 5)
                .list().ofSize(warmUpCount + 1)
                .map { counts -> Scenario(warmUpCount = warmUpCount, recordCounts = counts) }
        }

    /**
     * Builds a distinct [VersionId] for the activation at [index]. All versions
     * share one publish date (activation order, not date, drives rotation) and
     * differ only by a digest derived from [index], so identities never collide.
     */
    private fun versionId(index: Int): VersionId {
        val hex = "%064x".format(java.math.BigInteger.valueOf(index.toLong() + 1))
        return VersionId(PUBLISH_DATE, Sha256Digest.ofHex(hex))
    }

    /** Builds [count] distinct in-scope records all stamped with [versionId]. */
    private fun recordsFor(versionId: VersionId, count: Int): List<InternalModelEntry> =
        (0 until count).map { i ->
            InternalModelEntry(
                fixedRef = FixedRef("FR-${versionId.digest.value.takeLast(6)}-$i"),
                entityType = if (i % 2 == 0) EntityType.Individual else EntityType.Entity,
                primaryName = "name-$i",
                sanctionPrograms = listOf("program-$i"),
                versionId = versionId,
            )
        }

    private companion object {
        val PUBLISH_DATE: LocalDate = LocalDate.of(2021, 6, 15)
    }
}
