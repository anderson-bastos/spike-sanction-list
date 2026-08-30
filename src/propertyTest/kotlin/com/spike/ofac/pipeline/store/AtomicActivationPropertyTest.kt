package com.spike.ofac.pipeline.store

import com.spike.ofac.pipeline.models.EntityType
import com.spike.ofac.pipeline.models.FixedRef
import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.models.SourceList
import com.spike.ofac.pipeline.models.VersionId
import com.spike.ofac.pipeline.stages.VersionPlan
import com.spike.ofac.pipeline.stages.publish.ActivationResult
import com.spike.ofac.pipeline.stages.publish.Publish
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.stateful.Action
import net.jqwik.api.stateful.ActionSequence
import org.junit.jupiter.api.Tag
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Property 10: Atomic activation never yields zero CURRENT.
 *
 * *For any* sequence of activation operations on a `Source_List`, at every
 * observable point `CURRENT` resolves to exactly one fully-persisted version and
 * is never zero or partial; a newly persisted (isolated) version is **not**
 * consumer-resolvable before its activation completes; and after an activation
 * attempt `CURRENT` resolves either fully to the new version (on success) or
 * fully to the prior version (on rejection) (Req 7.6, 9.1, 9.2).
 *
 * This is a **stateful / model-based** test in jqwik's `net.jqwik.api.stateful`
 * mode. The model threaded through the [ActionSequence] is a live [Harness]
 * wrapping the system under test — an [InMemoryVersionStore] driven by
 * [Publish.activate]. jqwik generates random sequences of two kinds of action:
 *
 *  - [PutIsolatedAction] — persist a brand-new version via
 *    [InMemoryVersionStore.putIsolated]. The version exists in the store but is
 *    not addressed by any pointer, so it must not be resolvable as `CURRENT`.
 *  - [ActivateAction] — run [Publish.activate] for a previously-isolated version.
 *    Half the time it feeds a **matching** persisted count (activation succeeds
 *    and `CURRENT` must resolve fully to the new version) and half the time a
 *    **mismatched** count (activation is rejected and `CURRENT` must be left
 *    exactly as it was — the prior version, or absent if none was ever active).
 *
 * A jqwik invariant re-checks the core "exactly one fully-persisted CURRENT"
 * property after **every** step, and each action additionally asserts the
 * pre/post relationship specific to it. Both together give the full Property 10
 * coverage across all generated interleavings.
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 10: Atomic activation never
 * yields zero CURRENT`.
 *
 * **Validates: Requirements 7.6, 9.1, 9.2**
 */
@Tag(PropertyTests.FEATURE_TAG)
class AtomicActivationPropertyTest {

    /** The single list these sequences operate on (per-list independence is Property 13). */
    private val list = SourceList.SDN

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 10: Atomic activation never yields zero CURRENT")
    fun atomicActivationNeverYieldsZeroCurrent(
        @ForAll @From("activationSequences") sequence: ActionSequence<Harness>,
    ) {
        // Fresh system under test per generated sequence. The model IS the harness,
        // so every action drives the real store and reads back through it.
        sequence
            .withInvariant("CURRENT always resolves to exactly one fully-persisted version") { h ->
                h.assertCurrentFullyPersistedOrAbsent()
            }
            .run(Harness())
    }

    /**
     * A generated sequence mixes new-version persists with activation attempts.
     *
     * Sizing the sequence to a good number of actions makes it likely that
     * activations find an isolated version to promote and that the window sees a
     * prior `CURRENT`, so the "resolves fully to the prior version on rejection"
     * branch is actually exercised.
     */
    @Provide
    fun activationSequences(): Arbitrary<ActionSequence<Harness>> {
        val actions: Arbitrary<Action<Harness>> = Arbitraries.oneOf(
            listOf(
                putIsolatedActions(),
                activateActions(),
            ),
        )
        return Arbitraries.sequences(actions).ofSize(30)
    }

    /** Generates a persist of a fresh, content-distinct version. */
    private fun putIsolatedActions(): Arbitrary<Action<Harness>> {
        val publishDates = Arbitraries.integers().between(0, 3650)
            .map { EPOCH_START.plusDays(it.toLong()) }
        val recordCounts = Arbitraries.integers().between(1, 4)
        return publishDates.flatMap { date ->
            recordCounts.map { count -> PutIsolatedAction(date, count) }
        }
    }

    /**
     * Generates an activation attempt. [succeed] chooses whether the persisted
     * count matches the plan's expected count (success) or is deliberately off by
     * one (rejection). [pick] selects which isolated version to target.
     */
    private fun activateActions(): Arbitrary<Action<Harness>> {
        val succeed = Arbitraries.of(true, false)
        val pick = Arbitraries.integers().between(0, 1_000_000)
        return succeed.flatMap { ok ->
            pick.map { p -> ActivateAction(ok, p) }
        }
    }

    /**
     * Persists a brand-new isolated version.
     *
     * Precondition: always applicable — a store can always take another version.
     *
     * Post-checks: the version is stored (metadata + records non-null) yet is
     * **not** resolvable as `CURRENT` merely by having been persisted; `CURRENT`
     * stays whatever it was before this persist (Req 7.6). A persist never zeroes
     * or changes `CURRENT`.
     */
    inner class PutIsolatedAction(
        private val publishDate: LocalDate,
        private val recordCount: Int,
    ) : Action<Harness> {

        override fun run(model: Harness): Harness {
            val before = model.currentVersion()

            val versionId = model.freshVersionId(publishDate)
            val records = model.recordsFor(versionId, recordCount)
            model.store.putIsolated(versionId, records)
            model.isolated += versionId
            model.expectedCountFor[versionId] = records.size

            // The version is fully persisted in the store...
            model.store.metadataOf(versionId).shouldNotBeNull()
            model.store.recordsOf(versionId).shouldNotBeNull()

            // ...but persisting does NOT make it resolvable as CURRENT (Req 7.6):
            // an isolated version is invisible to consumers until activation.
            model.store.getPointer(list, PointerKind.CURRENT) shouldBe before
            if (before != null) {
                (model.store.getPointer(list, PointerKind.CURRENT) == versionId).shouldBe(false)
            } else {
                (model.store.getPointer(list, PointerKind.CURRENT) == null).shouldBeTrue()
            }

            return model
        }

        override fun toString(): String = "putIsolated(publishDate=$publishDate, records=$recordCount)"
    }

    /**
     * Runs [Publish.activate] against a previously-isolated version.
     *
     * Precondition: there is at least one isolated version to promote.
     *
     * On the success branch (`persistedCount == expectedCount`) activation must
     * succeed and `CURRENT` must resolve **fully** to the new version — metadata
     * and records both present (Req 9.1). On the rejection branch (count off by
     * one) activation must be rejected and `CURRENT` must be left **exactly** as
     * it was before the attempt — the prior version, or absent when none was ever
     * active (Req 9.2). Either way `CURRENT` is never zeroed or left partial.
     */
    inner class ActivateAction(
        private val makeCountMatch: Boolean,
        private val pick: Int,
    ) : Action<Harness> {

        override fun precondition(model: Harness): Boolean = model.isolated.isNotEmpty()

        override fun run(model: Harness): Harness {
            val candidates = model.isolated.toList()
            val target = candidates[pick % candidates.size]
            val expected = model.expectedCountFor.getValue(target)

            val before = model.currentVersion()

            val persistedCount = if (makeCountMatch) expected else expected + 1
            val plan = VersionPlan.Accepted(versionId = target, expectedCount = expected)

            val result = Publish.activate(list, plan, persistedCount, model.store)

            if (makeCountMatch) {
                // Success: CURRENT must resolve FULLY to the new version (Req 9.1).
                (result is ActivationResult.Activated).shouldBeTrue()
                model.store.getPointer(list, PointerKind.CURRENT) shouldBe target
                model.assertFullyPersisted(target)
                model.isolated -= target
            } else {
                // Rejected: CURRENT must be left EXACTLY as before (Req 9.2, 8.3).
                (result is ActivationResult.RejectedCountMismatch).shouldBeTrue()
                model.store.getPointer(list, PointerKind.CURRENT) shouldBe before
                // The rejected version stays isolated (still not resolvable as CURRENT).
                if (before != null) {
                    model.assertFullyPersisted(before)
                }
                (model.store.getPointer(list, PointerKind.CURRENT) == target && before != target)
                    .shouldBe(false)
            }

            return model
        }

        override fun toString(): String =
            "activate(match=$makeCountMatch, pick=$pick)"
    }

    /**
     * The live model threaded through the [ActionSequence]: the system under test
     * (an [InMemoryVersionStore]) plus the bookkeeping the actions need (which
     * versions were persisted-but-isolated and each one's expected count).
     */
    inner class Harness {
        val store = InMemoryVersionStore()

        /** Versions persisted via putIsolated that have not yet been activated. */
        val isolated: MutableSet<VersionId> = mutableSetOf()

        /** Expected reconciliation count per persisted version. */
        val expectedCountFor: MutableMap<VersionId, Int> = mutableMapOf()

        /** Monotonic seed so successive fresh versions get distinct content/digests. */
        private var seed = 0

        fun currentVersion(): VersionId? = store.getPointer(list, PointerKind.CURRENT)

        /** A brand-new [VersionId] on [publishDate] with a content-derived, unique digest. */
        fun freshVersionId(publishDate: LocalDate): VersionId {
            val content = "content-${seed++}-${System.nanoTime()}".toByteArray()
            return VersionId(publishDate, sha256(content))
        }

        /** [count] distinct in-scope records all stamped with [versionId]. */
        fun recordsFor(versionId: VersionId, count: Int): List<InternalModelEntry> =
            (0 until count).map { i ->
                InternalModelEntry(
                    fixedRef = FixedRef("FR-${versionId.digest.value.take(8)}-$i"),
                    entityType = if (i % 2 == 0) EntityType.Individual else EntityType.Entity,
                    primaryName = "name-$i",
                    sanctionPrograms = listOf("program-$i"),
                    versionId = versionId,
                )
            }

        /**
         * Core Property 10 invariant (checked after every step): `CURRENT` is
         * either absent (no activation has happened yet) or resolves to exactly
         * one fully-persisted version — never zero-but-expected, never partial.
         */
        fun assertCurrentFullyPersistedOrAbsent() {
            val current = store.getPointer(list, PointerKind.CURRENT)
            if (current != null) {
                assertFullyPersisted(current)
            }
        }

        /** A version resolves fully when both its metadata and its records exist. */
        fun assertFullyPersisted(versionId: VersionId) {
            store.metadataOf(versionId).shouldNotBeNull()
            store.recordsOf(versionId).shouldNotBeNull()
        }
    }

    private fun sha256(bytes: ByteArray): Sha256Digest {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Sha256Digest.ofHex(digest.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        val EPOCH_START: LocalDate = LocalDate.of(2020, 1, 1)
    }
}
