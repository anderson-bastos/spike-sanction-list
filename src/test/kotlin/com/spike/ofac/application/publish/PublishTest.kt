package com.spike.ofac.application.publish

import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.version.VersionPlan
import com.spike.ofac.adapter.out.persistence.InMemoryVersionStore
import com.spike.ofac.application.port.out.PointerKind
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Example-level smoke tests for [Publish.activate] (task 8.2).
 *
 * These pin the three [ActivationResult] outcomes against the in-memory reference
 * store: exact-count activation, count-mismatch rejection (Req 8.3), and
 * repoint-failed rejection with pointers unchanged (Req 9.4). Window rotation is
 * exercised across successive activations. The exhaustive model-based coverage
 * (Properties 10–14) arrives in tasks 8.4–8.7.
 */
class PublishTest {

    private val date = LocalDate.of(2024, 1, 15)
    private fun digest(c: Char) = Sha256Digest(c.toString().repeat(64))
    private fun vid(c: Char) = VersionId(date, digest(c))
    private fun plan(c: Char, expected: Int) = VersionPlan.Accepted(vid(c), expected)

    private fun entries(n: Int): List<InternalModelEntry> =
        (1..n).map {
            InternalModelEntry(
                fixedRef = FixedRef(it.toString()),
                entityType = EntityType.Individual,
                primaryName = "Name $it",
                sanctionPrograms = listOf("PROGRAM"),
            )
        }

    @Test
    fun `activate repoints CURRENT when the persisted count matches expected exactly`() {
        val store = InMemoryVersionStore()
        val records = entries(3)
        store.putIsolated(vid('a'), records)

        val result = Publish.activate(SourceList.SDN, plan('a', expected = 3), persistedCount = 3, store)

        result shouldBe ActivationResult.Activated(vid('a'))
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe vid('a')
    }

    @Test
    fun `activate rejects with COUNT_MISMATCH and leaves CURRENT unchanged when counts differ`() {
        val store = InMemoryVersionStore()
        store.putIsolated(vid('a'), entries(3))

        val result = Publish.activate(SourceList.SDN, plan('a', expected = 3), persistedCount = 2, store)

        result shouldBe ActivationResult.RejectedCountMismatch
        // No repoint was attempted: CURRENT is still empty (Req 8.3).
        store.getPointer(SourceList.SDN, PointerKind.CURRENT).shouldBeNull()
    }

    @Test
    fun `activate rejects with REPOINT_FAILED and leaves pointers unchanged when the store cannot repoint`() {
        val store = InMemoryVersionStore()
        // Seed a real CURRENT so we can prove the trio is untouched on failure.
        store.putIsolated(vid('a'), entries(1))
        Publish.activate(SourceList.SDN, plan('a', expected = 1), persistedCount = 1, store)

        // vid('f') was never persisted, so atomicSetCurrent returns false (Req 9.4).
        val result = Publish.activate(SourceList.SDN, plan('f', expected = 1), persistedCount = 1, store)

        result shouldBe ActivationResult.RejectedRepointFailed
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe vid('a')
    }

    @Test
    fun `successive activations rotate the window keeping at most three HOT versions`() {
        val store = InMemoryVersionStore()
        val ids = "abcd".toList()
        ids.forEach { store.putIsolated(vid(it), entries(1)) }

        ids.forEach {
            Publish.activate(SourceList.SDN, plan(it, expected = 1), persistedCount = 1, store) shouldBe
                ActivationResult.Activated(vid(it))
        }

        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe vid('d')
        store.getPointer(SourceList.SDN, PointerKind.PREVIOUS) shouldBe vid('c')
        store.getPointer(SourceList.SDN, PointerKind.N_MINUS_2) shouldBe vid('b')
    }
}
