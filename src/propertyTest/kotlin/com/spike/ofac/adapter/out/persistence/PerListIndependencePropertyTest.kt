package com.spike.ofac.adapter.out.persistence

import com.spike.ofac.application.port.out.PointerKind

import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.model.VersionState
import com.spike.ofac.application.publish.Publish
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.Tuple
import org.junit.jupiter.api.Tag
import java.time.LocalDate

/**
 * Property 13: Per-list independence.
 *
 * Each `Source_List` versions on an independent line, so activating or rolling
 * back one list changes no version and no pointer of any other list (Req 10.2).
 * The two lists in play are [SourceList.SDN] and [SourceList.CONSOLIDATED].
 *
 * This is a stateful, model-based property. It generates an **interleaved**
 * sequence of operations, each tagged with the target [SourceList] it acts on:
 *
 *  - [Op.ActivateNew] — persist a brand-new isolated version for that list
 *    ([InMemoryVersionStore.putIsolatedFor]) and activate it via
 *    [Publish.activate] (which delegates to the store's atomic repoint).
 *  - [Op.Rollback] — roll that list back one step via [Publish.rollback]
 *    (a pointer move `CURRENT → PREVIOUS`, or a `NO_PREVIOUS` no-op).
 *
 * The whole sequence runs against a single [InMemoryVersionStore] shared by both
 * lists. Before each operation on a list `L`, the test snapshots the **other**
 * list `O`: its pointer trio (`CURRENT`/`PREVIOUS`/`N_MINUS_2`) and, for every
 * version `O` has ever touched, that version's [state][VersionState] and record
 * list. After the operation on `L` completes, it re-reads `O` and asserts nothing
 * changed — same pointer trio, same per-version state, same records. Because
 * versions are immutable and pointers are keyed per list, an operation on `L`
 * must be invisible to `O` (Req 10.2).
 *
 * `VersionId = (publishDate, Sha256Digest)` and every generated version has a
 * unique 64-hex digest, so no two versions collide and
 * [InMemoryVersionStore.putIsolatedFor]'s immutability precondition is respected.
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 13: Per-list independence`.
 *
 * **Validates: Requirements 10.2**
 */
@Tag(PropertyTests.FEATURE_TAG)
class PerListIndependencePropertyTest {

    /** A single operation in the interleaved sequence, tagged with its target list. */
    sealed interface Op {
        val target: SourceList

        /**
         * Persist a fresh version for [target] and activate it. [seed] makes the
         * version's digest and records unique; [recordCount] varies the payload.
         */
        data class ActivateNew(
            override val target: SourceList,
            val seed: Int,
            val recordCount: Int,
        ) : Op

        /** Roll [target] back one step (CURRENT → PREVIOUS), or a no-op if none. */
        data class Rollback(override val target: SourceList) : Op
    }

    /**
     * An immutable snapshot of one list's observable state: its pointer trio and,
     * per version the list has touched, that version's HOT/COLD state and records.
     * Two snapshots comparing equal means the list was left entirely unchanged.
     */
    private data class ListSnapshot(
        val current: VersionId?,
        val previous: VersionId?,
        val nMinus2: VersionId?,
        val states: Map<VersionId, VersionState?>,
        val records: Map<VersionId, List<InternalModelEntry>?>,
    )

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 13: Per-list independence")
    fun operationsOnOneListLeaveTheOtherUnchanged(
        @ForAll @From("operationSequences") ops: List<Op>,
    ) {
        val store = InMemoryVersionStore()

        // Every VersionId each list has ever been party to, so a snapshot can range
        // over the full history of the "other" list (not just its live pointers).
        val touched: MutableMap<SourceList, MutableSet<VersionId>> =
            mutableMapOf(SourceList.SDN to mutableSetOf(), SourceList.CONSOLIDATED to mutableSetOf())

        // Globally-unique digests keep VersionIds distinct across the whole run so
        // putIsolatedFor's immutability precondition is never violated.
        var digestCounter = 0

        for (op in ops) {
            val actedOn = op.target
            val other = actedOn.other()

            // Snapshot the OTHER list immediately before we touch `actedOn`.
            val before = snapshot(store, other, touched.getValue(other))

            when (op) {
                is Op.ActivateNew -> {
                    val versionId = VersionId(
                        publishDate = EPOCH_START.plusDays((op.seed % 3650).toLong()),
                        digest = uniqueDigest(digestCounter++),
                    )
                    val records = recordsFor(versionId, op.recordCount)
                    store.putIsolatedFor(actedOn, versionId, records)
                    touched.getValue(actedOn).add(versionId)

                    // Result-validate passes (persistedCount == records.size == expected),
                    // so activation atomically repoints CURRENT for `actedOn` only.
                    val result = Publish.activate(
                        sourceList = actedOn,
                        plan = acceptedPlan(versionId, expectedCount = records.size),
                        persistedCount = records.size,
                        store = store,
                    )
                    result shouldBe com.spike.ofac.application.publish.ActivationResult
                        .Activated(versionId)
                }

                is Op.Rollback -> {
                    // Pointer move only; may be a NO_PREVIOUS no-op. Either way it
                    // touches only `actedOn`'s trio.
                    Publish.rollback(actedOn, store)
                }
            }

            // The OTHER list must be byte-for-byte unchanged by the operation on
            // `actedOn`: same trio, same per-version state, same records (Req 10.2).
            val after = snapshot(store, other, touched.getValue(other))
            after shouldBe before
        }
    }

    /** Captures [list]'s pointer trio and the state/records of every [known] version. */
    private fun snapshot(
        store: InMemoryVersionStore,
        list: SourceList,
        known: Set<VersionId>,
    ): ListSnapshot =
        ListSnapshot(
            current = store.getPointer(list, PointerKind.CURRENT),
            previous = store.getPointer(list, PointerKind.PREVIOUS),
            nMinus2 = store.getPointer(list, PointerKind.N_MINUS_2),
            states = known.associateWith { store.metadataOf(it)?.state },
            records = known.associateWith { store.recordsOf(it) },
        )

    /** Builds [count] distinct in-scope records all stamped with [versionId]. */
    private fun recordsFor(versionId: VersionId, count: Int): List<InternalModelEntry> =
        (0 until count).map { i ->
            InternalModelEntry(
                fixedRef = FixedRef("FR-${versionId.digest.value.take(10)}-$i"),
                entityType = if (i % 2 == 0) EntityType.Individual else EntityType.Entity,
                primaryName = "name-${versionId.digest.value.take(6)}-$i",
                sanctionPrograms = listOf("program-$i"),
                versionId = versionId,
            )
        }

    private fun acceptedPlan(
        versionId: VersionId,
        expectedCount: Int,
    ): com.spike.ofac.domain.version.VersionPlan.Accepted =
        com.spike.ofac.domain.version.VersionPlan.Accepted(
            versionId = versionId,
            expectedCount = expectedCount,
        )

    /**
     * Generates an interleaved sequence of operations across the two lists.
     *
     * Each element independently picks a target list and an operation kind
     * (activate-new or rollback), so activations and rollbacks freely interleave
     * across SDN and Consolidated. `ActivateNew` carries a per-op seed/record-count
     * used to synthesize a unique version; `Rollback` needs no payload.
     */
    @Provide
    fun operationSequences(): Arbitrary<List<Op>> = ops().list().ofMinSize(1).ofMaxSize(40)

    private fun ops(): Arbitrary<Op> {
        val targets = Arbitraries.of(SourceList.SDN, SourceList.CONSOLIDATED)

        val activates: Arbitrary<Op> = targets.flatMap { target ->
            Arbitraries.integers().between(0, 100_000).flatMap { seed ->
                Arbitraries.integers().between(0, 6).map { recordCount ->
                    Op.ActivateNew(target, seed, recordCount) as Op
                }
            }
        }

        val rollbacks: Arbitrary<Op> = targets.map { target -> Op.Rollback(target) as Op }

        // Bias toward activations so the window and pointer trios actually populate,
        // giving rollbacks something to move and snapshots something to compare.
        return Arbitraries.frequencyOf(
            Tuple.of(3, activates),
            Tuple.of(1, rollbacks),
        )
    }

    private fun uniqueDigest(counter: Int): Sha256Digest {
        // A deterministic, collision-free 64-hex string derived from the counter.
        val hex = counter.toString(16).padStart(64, '0')
        return Sha256Digest.ofHex(hex)
    }

    private fun SourceList.other(): SourceList =
        when (this) {
            SourceList.SDN -> SourceList.CONSOLIDATED
            SourceList.CONSOLIDATED -> SourceList.SDN
        }

    private companion object {
        val EPOCH_START: LocalDate = LocalDate.of(2020, 1, 1)
    }
}
