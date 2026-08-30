package com.spike.ofac.pipeline.store

import com.spike.ofac.pipeline.models.EntityType
import com.spike.ofac.pipeline.models.FixedRef
import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.models.SourceList
import com.spike.ofac.pipeline.models.VersionId
import com.spike.ofac.pipeline.models.VersionState
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Tag
import java.time.LocalDate

/**
 * Property 11: Version window rotation keeps the three most recent HOT versions.
 *
 * A [SourceList] maintains a fixed window of at most three HOT operational
 * versions — CURRENT, PREVIOUS, N_MINUS_2 — the three most recently activated
 * versions in order (Req 10.1). Each activation rotates the window: the freshly
 * activated version becomes CURRENT, the old CURRENT becomes PREVIOUS, the old
 * PREVIOUS becomes N_MINUS_2, and any version displaced past N_MINUS_2 leaves
 * the HOT window and is reclassified COLD (Req 9.3, 10.5). COLD versions are
 * retained without any mutation of their identity or records (Req 7.5, 10.5).
 *
 * This is a stateful/model-based property: it generates a sequence of N distinct
 * versions for a single source list, persists each in isolation, then activates
 * them one at a time. **After every activation** it re-checks the whole set of
 * window invariants against the model of "the versions activated so far", so a
 * violation surfaces at the exact step it first appears rather than only at the
 * end:
 *
 *  - the pointer trio (CURRENT, PREVIOUS, N_MINUS_2) is exactly the three
 *    most-recently-activated versions, newest first, with empty slots (null) when
 *    fewer than three have been activated (Req 10.1);
 *  - the number of HOT versions equals `min(activatedSoFar, 3)` and never exceeds
 *    three (Req 9.3, 10.5);
 *  - every displaced version (activated but no longer in the current three) is
 *    COLD (Req 10.5);
 *  - every displaced version's persisted records are byte-for-byte unchanged from
 *    when they were written — rotation only flips HOT/COLD state and moves
 *    pointers, it never mutates or deletes records (Req 7.5, 10.5).
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 11: Version window rotation
 * keeps the three most recent HOT versions`.
 *
 * **Validates: Requirements 7.5, 9.3, 10.1, 10.5**
 */
@Tag(PropertyTests.FEATURE_TAG)
class WindowRotationPropertyTest {

    /**
     * A single generated version to activate: its identity plus the immutable
     * records persisted with it, captured so the test can prove they never change
     * once the version is displaced to COLD.
     */
    data class Activation(
        val versionId: VersionId,
        val records: List<InternalModelEntry>,
    )

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 11: Version window rotation keeps the three most recent HOT versions")
    fun versionWindowRotationKeepsThreeMostRecentHot(
        @ForAll @From("activationSequences") activations: List<Activation>,
    ) {
        val store = InMemoryVersionStore()
        val list = SourceList.SDN

        // Persist every version in isolation up front; none is resolvable through a
        // pointer until it is activated (Req 7.6). Snapshot the records as written
        // so the immutability check compares against the exact persisted content.
        val persistedRecords = HashMap<VersionId, List<InternalModelEntry>>()
        activations.forEach { a ->
            store.putIsolated(a.versionId, a.records)
            persistedRecords[a.versionId] = store.recordsOf(a.versionId).shouldNotBeNull()
        }

        // The versions activated so far, most-recent LAST (append order = activation
        // order). The model of the expected window is derived purely from this list.
        val activatedInOrder = mutableListOf<VersionId>()

        activations.forEach { a ->
            store.atomicSetCurrent(list, a.versionId) shouldBe true
            activatedInOrder += a.versionId

            // The three most-recently-activated versions, newest first.
            val expectedHot = activatedInOrder.asReversed().take(3)

            // --- Pointer trio is exactly the three most recent, in order (Req 10.1) ---
            store.getPointer(list, PointerKind.CURRENT) shouldBe expectedHot.getOrNull(0)
            store.getPointer(list, PointerKind.PREVIOUS) shouldBe expectedHot.getOrNull(1)
            store.getPointer(list, PointerKind.N_MINUS_2) shouldBe expectedHot.getOrNull(2)

            // --- HOT count is exactly min(activatedSoFar, 3) and never > 3 (Req 9.3, 10.5) ---
            val hotIds = activatedInOrder
                .filter { store.metadataOf(it).shouldNotBeNull().state == VersionState.HOT }
            hotIds.size shouldBeLessThanOrEqual 3
            hotIds.size shouldBe minOf(activatedInOrder.distinctCount(), 3)

            // The HOT set is exactly the expected three most-recent (as a set).
            store.hotVersionsOf(list) shouldContainExactlyInAnyOrder expectedHot.toSet()

            // --- Displaced versions are COLD with content unchanged (Req 7.5, 10.5) ---
            val displaced = activatedInOrder.toSet() - expectedHot.toSet()
            displaced.forEach { id ->
                store.metadataOf(id).shouldNotBeNull().state shouldBe VersionState.COLD
                // Records are byte-for-byte the ones written; rotation never mutates
                // or deletes a displaced version's records (immutability, Req 7.5).
                store.recordsOf(id).shouldNotBeNull() shouldContainExactly persistedRecords.getValue(id)
            }
        }
    }

    /** All versions this list currently classifies HOT (via [InMemoryVersionStore.metadataOf]). */
    private fun InMemoryVersionStore.hotVersionsOf(list: SourceList): Set<VersionId> =
        setOfNotNull(
            getPointer(list, PointerKind.CURRENT),
            getPointer(list, PointerKind.PREVIOUS),
            getPointer(list, PointerKind.N_MINUS_2),
        ).filter { metadataOf(it)?.state == VersionState.HOT }.toSet()

    /** Distinct count — generated versions are already distinct, so this equals size. */
    private fun List<VersionId>.distinctCount(): Int = toSet().size

    /**
     * Generates a sequence of 1..12 distinct versions for one source list.
     *
     * Distinctness is what makes the window model well-defined (each activation
     * addresses a new version), so identities are drawn by index: every version
     * gets a unique 64-hex digest derived from its position. Publish dates are
     * generated freely (and may repeat across versions) to exercise same-day
     * publications, which the digest still disambiguates.
     */
    @Provide
    fun activationSequences(): Arbitrary<List<Activation>> =
        counts().flatMap { n ->
            publishDates().list().ofSize(n).map { dates ->
                (0 until n).map { i ->
                    val versionId = VersionId(dates[i], digestForIndex(i))
                    Activation(versionId, recordsFor(versionId, count = (i % 3) + 1))
                }
            }
        }

    @Provide
    fun counts(): Arbitrary<@IntRange(min = 1, max = 12) Int> =
        Arbitraries.integers().between(1, 12)

    private fun publishDates(): Arbitrary<LocalDate> =
        Arbitraries.integers().between(0, 3650).map { EPOCH_START.plusDays(it.toLong()) }

    /** A distinct, valid 64-char lowercase-hex digest keyed by activation index. */
    private fun digestForIndex(index: Int): Sha256Digest {
        val hex = index.toString(16).padStart(64, '0')
        return Sha256Digest.ofHex(hex)
    }

    /** Builds [count] distinct in-scope records all stamped with [versionId]. */
    private fun recordsFor(versionId: VersionId, count: Int): List<InternalModelEntry> =
        (0 until count).map { i ->
            InternalModelEntry(
                fixedRef = FixedRef("FR-${versionId.digest.value.takeLast(8)}-$i"),
                entityType = if (i % 2 == 0) EntityType.Individual else EntityType.Entity,
                primaryName = "name-$i",
                sanctionPrograms = listOf("program-$i"),
                versionId = versionId,
            )
        }

    private companion object {
        val EPOCH_START: LocalDate = LocalDate.of(2020, 1, 1)
    }
}
