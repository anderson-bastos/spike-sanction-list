package com.spike.ofac.application.publish

import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.model.VersionMetadata
import com.spike.ofac.domain.version.VersionPlan
import com.spike.ofac.adapter.out.persistence.InMemoryVersionStore
import com.spike.ofac.application.port.out.PointerKind
import com.spike.ofac.application.port.out.VersionStore
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate

/**
 * Unit tests for the two boundary cases of the version-pointer state machine
 * (task 8.8):
 *
 *  - **Req 10.4** — a rollback requested for a list with no `PREVIOUS` (freshly
 *    activated once, or never activated at all) is rejected and leaves `CURRENT`
 *    unchanged.
 *  - **Req 9.4** — when the atomic repoint fails during [Publish.activate], the
 *    list's pointer trio is left entirely unchanged and the result is
 *    [ActivationResult.RejectedRepointFailed].
 *
 * The happy paths and per-list independence live in [PublishRollbackSmokeTest]
 * (task 8.3); this file deliberately isolates the two rejection edges named by
 * the requirements above.
 */
class PublishRollbackRepointEdgeCasesTest {

    private val date = LocalDate.of(2024, 3, 1)
    private fun digest(c: Char) = Sha256Digest(c.toString().repeat(64))
    private fun vid(c: Char) = VersionId(date, digest(c))
    private fun entry(ref: String) = InternalModelEntry(
        fixedRef = FixedRef(ref),
        entityType = EntityType.Individual,
        primaryName = "Name $ref",
        sanctionPrograms = listOf("PROGRAM"),
    )

    // --- Req 10.4: rollback with no PREVIOUS ---

    @Test
    fun `rollback after a single activation is rejected and leaves CURRENT unchanged`() {
        // A list activated exactly once has a CURRENT but no PREVIOUS to fall back
        // to (Req 10.4).
        val store = InMemoryVersionStore()
        val v1 = vid('a')
        store.putIsolatedFor(SourceList.SDN, v1, listOf(entry("1")))
        store.atomicSetCurrent(SourceList.SDN, v1) shouldBe true

        val result = Publish.rollback(SourceList.SDN, store)

        result shouldBe RollbackResult.RejectedNoPrevious
        // CURRENT stays put; PREVIOUS/N_MINUS_2 remain empty.
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe v1
        store.getPointer(SourceList.SDN, PointerKind.PREVIOUS) shouldBe null
        store.getPointer(SourceList.SDN, PointerKind.N_MINUS_2) shouldBe null
    }

    @Test
    fun `rollback on a list that was never activated is rejected with no pointers set`() {
        // No activation ever happened, so the whole trio is empty (Req 10.4).
        val store = InMemoryVersionStore()

        val result = Publish.rollback(SourceList.SDN, store)

        result shouldBe RollbackResult.RejectedNoPrevious
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
        store.getPointer(SourceList.SDN, PointerKind.PREVIOUS) shouldBe null
        store.getPointer(SourceList.SDN, PointerKind.N_MINUS_2) shouldBe null
    }

    // --- Req 9.4: repoint failure leaves the pointer trio unchanged ---

    @Test
    fun `activate maps a repoint failure to RejectedRepointFailed and leaves the pointer trio unchanged`() {
        // Set up a prior activated version so the list has a non-empty CURRENT to
        // preserve across the failed repoint.
        val store = InMemoryVersionStore()
        val current = vid('a')
        store.putIsolatedFor(SourceList.SDN, current, listOf(entry("1")))
        store.atomicSetCurrent(SourceList.SDN, current) shouldBe true

        // Build an Accepted plan for a version that was NEVER putIsolated. Its
        // expected_count equals the persisted count so the count gate passes and
        // execution reaches the repoint step — where the store returns false
        // because it holds no such version.
        val unknown = vid('f')
        val plan = VersionPlan.Accepted(versionId = unknown, expectedCount = 5)

        val result = Publish.activate(
            sourceList = SourceList.SDN,
            plan = plan,
            persistedCount = 5,
            store = store,
        )

        result shouldBe ActivationResult.RejectedRepointFailed
        // The prior trio is entirely unchanged: CURRENT still resolves to the
        // previously activated version and the unknown version never entered it.
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe current
        store.getPointer(SourceList.SDN, PointerKind.PREVIOUS) shouldBe null
        store.getPointer(SourceList.SDN, PointerKind.N_MINUS_2) shouldBe null
    }

    @Test
    fun `activate surfaces a store repoint failure deterministically via a stub store`() {
        // A stub whose atomicSetCurrent always returns false forces the
        // repoint-failed path regardless of what has been persisted, and records
        // whether any pointer write was attempted. The count gate is satisfied so
        // control reaches the repoint step.
        val stub = RepointFailingVersionStore()
        val plan = VersionPlan.Accepted(versionId = vid('b'), expectedCount = 3)

        val result = Publish.activate(
            sourceList = SourceList.SDN,
            plan = plan,
            persistedCount = 3,
            store = stub,
        )

        result shouldBe ActivationResult.RejectedRepointFailed
        // The store reported failure, so no pointer was ever swapped in — the trio
        // the store would expose is untouched (Req 9.4).
        stub.repointAttempts shouldBe 1
        stub.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    /**
     * A minimal [VersionStore] stub whose [atomicSetCurrent] always fails, used to
     * force the [ActivationResult.RejectedRepointFailed] path deterministically
     * without depending on which versions happen to be persisted. It never mutates
     * any pointer, mirroring the contract that a failed repoint leaves the trio
     * unchanged (Req 9.4).
     */
    private class RepointFailingVersionStore : VersionStore {
        var repointAttempts = 0
            private set

        override fun putIsolated(versionId: VersionId, records: List<InternalModelEntry>) = Unit

        override fun associateRawPath(versionId: VersionId, rawPath: Path) = Unit

        override fun atomicSetCurrent(sourceList: SourceList, versionId: VersionId): Boolean {
            repointAttempts++
            return false
        }

        override fun getPointer(sourceList: SourceList, pointer: PointerKind): VersionId? = null

        override fun reclassifyCold(sourceList: SourceList) = Unit

        override fun coldVersions(sourceList: SourceList): List<VersionId> = emptyList()

        override fun lastIngested(sourceList: SourceList): VersionMetadata? = null

        override fun verifyIntegrity(versionId: VersionId): Boolean = false
    }
}
