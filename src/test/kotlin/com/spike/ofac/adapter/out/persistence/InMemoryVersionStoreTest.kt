package com.spike.ofac.adapter.out.persistence

import com.spike.ofac.application.port.out.PointerKind
import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.model.VersionState
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate

/**
 * Unit tests for the [InMemoryVersionStore] reference model (task 8.1).
 *
 * These verify the two contract invariants the stateful property tests (tasks
 * 8.4–8.7) rely on — atomic pointer swap / window rotation and immutable version
 * records — plus the smaller helpers ([lastIngested], [associateRawPath],
 * [verifyIntegrity]). The exhaustive model-based coverage arrives in Properties
 * 10–14; this is the example-level smoke check and compile guard.
 */
class InMemoryVersionStoreTest {

    private val date = LocalDate.of(2024, 1, 15)
    private fun digest(c: Char) = Sha256Digest(c.toString().repeat(64))
    private fun vid(c: Char) = VersionId(date, digest(c))

    private fun entry(ref: String) = InternalModelEntry(
        fixedRef = FixedRef(ref),
        entityType = EntityType.Individual,
        primaryName = "Name $ref",
        sanctionPrograms = listOf("PROGRAM"),
    )

    // --- isolation before activation -------------------------------------

    @Test
    fun `an isolated version is not resolvable through any pointer until activated`() {
        val store = InMemoryVersionStore()
        val v = vid('a')
        store.putIsolated(v, listOf(entry("1")))

        store.getPointer(SourceList.SDN, PointerKind.CURRENT).shouldBeNull()

        store.atomicSetCurrent(SourceList.SDN, v) shouldBe true
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe v
    }

    @Test
    fun `activating a version that was never persisted fails and leaves pointers unchanged`() {
        val store = InMemoryVersionStore()
        val persisted = vid('a')
        store.putIsolated(persisted, listOf(entry("1")))
        store.atomicSetCurrent(SourceList.SDN, persisted) shouldBe true

        store.atomicSetCurrent(SourceList.SDN, vid('e')) shouldBe false
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe persisted
    }

    // --- window rotation --------------------------------------------------

    @Test
    fun `window rotation keeps the three most recent as CURRENT PREVIOUS N_MINUS_2 and colds the rest`() {
        val store = InMemoryVersionStore()
        val ids = "abcd".map { vid(it) }
        ids.forEach { store.putIsolated(it, listOf(entry(it.digest.value.take(4)))) }

        ids.forEach { store.atomicSetCurrent(SourceList.SDN, it) shouldBe true }

        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe ids[3]
        store.getPointer(SourceList.SDN, PointerKind.PREVIOUS) shouldBe ids[2]
        store.getPointer(SourceList.SDN, PointerKind.N_MINUS_2) shouldBe ids[1]

        // The displaced oldest version is retained COLD, never deleted.
        store.metadataOf(ids[0])!!.state shouldBe VersionState.COLD
        store.metadataOf(ids[3])!!.state shouldBe VersionState.HOT
    }

    // --- immutability -----------------------------------------------------

    @Test
    fun `version records are immutable and returned as a defensive copy`() {
        val store = InMemoryVersionStore()
        val v = vid('a')
        val original = listOf(entry("1"))
        store.putIsolated(v, original)

        store.recordsOf(v) shouldBe original
        store.atomicSetCurrent(SourceList.SDN, v)
        // Rotation only changes state, never the records.
        store.recordsOf(v) shouldBe original
    }

    @Test
    fun `re-persisting the same version id is rejected`() {
        val store = InMemoryVersionStore()
        val v = vid('a')
        store.putIsolated(v, listOf(entry("1")))
        runCatching { store.putIsolated(v, listOf(entry("2"))) }.isFailure shouldBe true
    }

    // --- per-list independence -------------------------------------------

    @Test
    fun `operations on one list do not touch another list's pointers`() {
        val store = InMemoryVersionStore()
        val sdn = vid('a')
        val cons = vid('b')
        store.putIsolatedFor(SourceList.SDN, sdn, listOf(entry("1")))
        store.putIsolatedFor(SourceList.CONSOLIDATED, cons, listOf(entry("2")))

        store.atomicSetCurrent(SourceList.SDN, sdn) shouldBe true
        store.getPointer(SourceList.CONSOLIDATED, PointerKind.CURRENT).shouldBeNull()

        store.atomicSetCurrent(SourceList.CONSOLIDATED, cons) shouldBe true
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe sdn
        store.getPointer(SourceList.CONSOLIDATED, PointerKind.CURRENT) shouldBe cons
    }

    // --- helpers ----------------------------------------------------------

    @Test
    fun `lastIngested returns the most recently persisted version of a list`() {
        val store = InMemoryVersionStore()
        store.lastIngested(SourceList.SDN).shouldBeNull()

        val first = vid('a')
        val second = vid('b')
        store.putIsolated(first, listOf(entry("1")))
        store.putIsolated(second, listOf(entry("2")))

        store.lastIngested(SourceList.SDN)!!.versionId shouldBe second
    }

    @Test
    fun `associateRawPath records the path and verifyIntegrity holds for a persisted version`() {
        val store = InMemoryVersionStore()
        val v = vid('a')
        store.putIsolated(v, listOf(entry("1")))

        store.associateRawPath(v, Path.of("/tmp/raw/2024-01-15-aaaa.xml"))
        store.metadataOf(v)!!.rawSnapshotPath shouldBe Path.of("/tmp/raw/2024-01-15-aaaa.xml")
        store.verifyIntegrity(v) shouldBe true
        store.verifyIntegrity(vid('e')) shouldBe false
    }

    @Test
    fun `markUnusable flips integrityOk to false without deleting the version (Req 14-5)`() {
        val store = InMemoryVersionStore()
        val v = vid('a')
        store.putIsolated(v, listOf(entry("1")))

        store.markUnusable(v)

        val meta = store.metadataOf(v)
        // The version is still present (identity/records intact), just flagged unusable.
        meta!!.integrityOk shouldBe false
        meta.versionId shouldBe v
    }
}
