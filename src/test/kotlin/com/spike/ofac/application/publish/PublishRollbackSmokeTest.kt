package com.spike.ofac.application.publish

import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.adapter.out.persistence.InMemoryVersionStore
import com.spike.ofac.application.port.out.PointerKind
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Example-level smoke check for [Publish.rollback] and per-list independence
 * (task 8.3). This is the compile guard and happy/edge-path check; the exhaustive
 * stateful coverage arrives with Properties 12 and 13 (tasks 8.6/8.7) and the
 * rollback/repoint unit tests (task 8.8), which this deliberately does not
 * duplicate.
 */
class PublishRollbackSmokeTest {

    private val date = LocalDate.of(2024, 3, 1)
    private fun digest(c: Char) = Sha256Digest(c.toString().repeat(64))
    private fun vid(c: Char) = VersionId(date, digest(c))
    private fun entry(ref: String) = InternalModelEntry(
        fixedRef = FixedRef(ref),
        entityType = EntityType.Individual,
        primaryName = "Name $ref",
        sanctionPrograms = listOf("PROGRAM"),
    )

    @Test
    fun `rollback moves CURRENT back to PREVIOUS by pointer only`() {
        val store = InMemoryVersionStore()
        val v1 = vid('a')
        val v2 = vid('b')
        store.putIsolatedFor(SourceList.SDN, v1, listOf(entry("1")))
        store.putIsolatedFor(SourceList.SDN, v2, listOf(entry("2")))

        // Activate v1 then v2 so v1 becomes PREVIOUS.
        store.atomicSetCurrent(SourceList.SDN, v1) shouldBe true
        store.atomicSetCurrent(SourceList.SDN, v2) shouldBe true
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe v2
        store.getPointer(SourceList.SDN, PointerKind.PREVIOUS) shouldBe v1

        val result = Publish.rollback(SourceList.SDN, store)

        result shouldBe RollbackResult.RolledBack(v1)
        // CURRENT now points at the prior version; the version content is reused,
        // never reprocessed (records unchanged).
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe v1
        store.recordsOf(v1) shouldBe listOf(entry("1"))
    }

    @Test
    fun `rollback with no PREVIOUS is rejected with NO_PREVIOUS and leaves CURRENT unchanged`() {
        val store = InMemoryVersionStore()
        val v1 = vid('a')
        store.putIsolatedFor(SourceList.SDN, v1, listOf(entry("1")))
        store.atomicSetCurrent(SourceList.SDN, v1) shouldBe true

        val result = Publish.rollback(SourceList.SDN, store)

        result shouldBe RollbackResult.RejectedNoPrevious
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe v1
    }

    @Test
    fun `rollback on a list that was never activated is rejected`() {
        val store = InMemoryVersionStore()

        val result = Publish.rollback(SourceList.SDN, store)

        result.shouldBeInstanceOf<RollbackResult.RejectedNoPrevious>()
    }

    @Test
    fun `rollback on one list leaves the other list's pointers untouched`() {
        val store = InMemoryVersionStore()
        val sdn1 = vid('a')
        val sdn2 = vid('b')
        val cons1 = vid('c')
        store.putIsolatedFor(SourceList.SDN, sdn1, listOf(entry("s1")))
        store.putIsolatedFor(SourceList.SDN, sdn2, listOf(entry("s2")))
        store.putIsolatedFor(SourceList.CONSOLIDATED, cons1, listOf(entry("c1")))

        store.atomicSetCurrent(SourceList.SDN, sdn1) shouldBe true
        store.atomicSetCurrent(SourceList.SDN, sdn2) shouldBe true
        store.atomicSetCurrent(SourceList.CONSOLIDATED, cons1) shouldBe true

        Publish.rollback(SourceList.SDN, store) shouldBe RollbackResult.RolledBack(sdn1)

        // The SDN rollback did not touch the CONSOLIDATED line at all.
        store.getPointer(SourceList.CONSOLIDATED, PointerKind.CURRENT) shouldBe cons1
        store.getPointer(SourceList.CONSOLIDATED, PointerKind.PREVIOUS) shouldBe null
        store.getPointer(SourceList.CONSOLIDATED, PointerKind.N_MINUS_2) shouldBe null
    }
}
