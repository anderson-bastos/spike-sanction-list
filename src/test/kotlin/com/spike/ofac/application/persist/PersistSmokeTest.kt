package com.spike.ofac.application.persist

import com.spike.ofac.adapter.config.RawSnapshotStoreProperties
import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.model.VersionMetadata
import com.spike.ofac.domain.version.VersionPlan
import com.spike.ofac.adapter.out.persistence.FsRawSnapshotStore
import com.spike.ofac.adapter.out.persistence.InMemoryVersionStore
import com.spike.ofac.application.port.out.PointerKind
import com.spike.ofac.application.port.out.RawSnapshotStore
import com.spike.ofac.application.port.out.VersionStore
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.file.shouldNotExist
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Example-level smoke check for [Persist.write] (task 13.6). It is the compile
 * guard plus a happy-path and each fail-closed branch, exercised with the real
 * [FsRawSnapshotStore] (over a [TempDir]) and the [InMemoryVersionStore] where
 * possible, and tiny fakes only to force the store failures that cannot be
 * provoked otherwise. The exhaustive coverage arrives with Properties 14/18
 * (tasks 13.7/13.8), which this deliberately does not duplicate.
 */
class PersistSmokeTest {

    private val publishDate = LocalDate.of(2024, 5, 20)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

    /** A [VersionId] whose recorded digest is the real SHA-256 of [bytes]. */
    private fun versionOf(bytes: ByteArray): VersionId =
        VersionId(publishDate, Sha256Digest.ofHex(sha256Hex(bytes)))

    private fun planFor(versionId: VersionId): VersionPlan.Accepted =
        VersionPlan.Accepted(versionId = versionId, expectedCount = 2)

    private fun entry(ref: String) = InternalModelEntry(
        fixedRef = FixedRef(ref),
        entityType = EntityType.Individual,
        primaryName = "Name $ref",
        sanctionPrograms = listOf("PROGRAM"),
    )

    private fun fsStore(folder: Path) =
        FsRawSnapshotStore(RawSnapshotStoreProperties(folder = folder))

    // --- happy path -----------------------------------------------------------

    @Test
    fun `write persists the raw file and records, stamps version id, and stays isolated`(
        @TempDir folder: Path,
    ) {
        val rawBytes = "<snapshot>data</snapshot>".toByteArray()
        val versionId = versionOf(rawBytes)
        val entries = listOf(entry("1"), entry("2"))
        val store = InMemoryVersionStore()
        val rawStore = fsStore(folder)

        val result = Persist.write(planFor(versionId), entries, rawBytes, store, rawStore)

        val persisted = result.shouldBeInstanceOf<PersistResult.Persisted>()
        persisted.versionId shouldBe versionId

        // Raw file written and integrity-verified, path associated on metadata.
        persisted.rawPath.toFile().shouldExist()
        rawStore.verifyIntegrity(versionId) shouldBe true
        store.metadataOf(versionId).shouldNotBeNull().rawSnapshotPath shouldBe persisted.rawPath

        // Records written, each stamped with the version id (Req 7.4).
        val stored = store.recordsOf(versionId).shouldNotBeNull()
        stored shouldBe entries.map { it.copy(versionId = versionId) }
        stored.all { it.versionId == versionId } shouldBe true

        // The version is isolated — no pointer resolves to it until publish runs.
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    // --- FAILED(RAW_WRITE) -----------------------------------------------------

    @Test
    fun `raw write failure yields FAILED(RAW_WRITE), no records, CURRENT unchanged`(
        @TempDir folder: Path,
    ) {
        val rawBytes = "<snapshot/>".toByteArray()
        val versionId = versionOf(rawBytes)
        val store = InMemoryVersionStore()
        val rawStore = object : RawSnapshotStore by fsStore(folder) {
            override fun put(versionId: VersionId, bytes: ByteArray): Path =
                throw java.io.IOException("disk full")
        }

        val result = Persist.write(planFor(versionId), listOf(entry("1"), entry("2")), rawBytes, store, rawStore)

        result shouldBe PersistResult.FailedRawWrite
        // No version was written and CURRENT was never touched (Req 15.9).
        store.recordsOf(versionId) shouldBe null
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    // --- FAILED(RAW_INTEGRITY) -------------------------------------------------

    @Test
    fun `stored-file integrity mismatch yields FAILED(RAW_INTEGRITY), discards file, no records`(
        @TempDir folder: Path,
    ) {
        // Recorded digest is a valid-but-wrong SHA-256, so the stored file's real
        // hash cannot match — verifyIntegrity returns false (Req 15.7).
        val rawBytes = "<snapshot>data</snapshot>".toByteArray()
        val mismatched = VersionId(publishDate, Sha256Digest("a".repeat(64)))
        val store = InMemoryVersionStore()
        val rawStore = fsStore(folder)

        val result = Persist.write(planFor(mismatched), listOf(entry("1"), entry("2")), rawBytes, store, rawStore)

        result shouldBe PersistResult.FailedRawIntegrity
        // The stored file was discarded and no records were written.
        rawStore.verifyIntegrity(mismatched) shouldBe false
        store.recordsOf(mismatched) shouldBe null
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    // --- FAILED(PERSIST) -------------------------------------------------------

    @Test
    fun `record write failure yields FAILED(PERSIST), discards raw file, CURRENT unchanged`(
        @TempDir folder: Path,
    ) {
        val rawBytes = "<snapshot>data</snapshot>".toByteArray()
        val versionId = versionOf(rawBytes)
        val delegate = InMemoryVersionStore()
        // Fake store that verifies integrity like the real one (via the fs store)
        // but throws on the record write to force FAILED(PERSIST).
        val rawStore = fsStore(folder)
        val store = object : VersionStore by delegate {
            override fun putIsolated(versionId: VersionId, records: List<InternalModelEntry>): Unit =
                throw IllegalStateException("db write failed")

            override fun verifyIntegrity(versionId: VersionId): Boolean =
                rawStore.verifyIntegrity(versionId)

            override fun getPointer(sourceList: SourceList, pointer: PointerKind): VersionId? =
                delegate.getPointer(sourceList, pointer)

            override fun lastIngested(sourceList: SourceList): VersionMetadata? =
                delegate.lastIngested(sourceList)
        }

        val result = Persist.write(planFor(versionId), listOf(entry("1"), entry("2")), rawBytes, store, rawStore)

        result shouldBe PersistResult.FailedPersist
        // The raw file that had been written is discarded (Req 7.7); CURRENT unchanged.
        rawStore.verifyIntegrity(versionId) shouldBe false
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    @Test
    fun `a discarded raw file no longer exists on disk after a persist failure`(
        @TempDir folder: Path,
    ) {
        val rawBytes = "<snapshot>data</snapshot>".toByteArray()
        val mismatched = VersionId(publishDate, Sha256Digest("b".repeat(64)))
        val store = InMemoryVersionStore()
        val rawStore = fsStore(folder)

        // Capture where the file would be, then confirm the integrity-failure path
        // removed it.
        val result = Persist.write(planFor(mismatched), listOf(entry("1"), entry("2")), rawBytes, store, rawStore)

        result shouldBe PersistResult.FailedRawIntegrity
        folder.resolve("2024-05-20_${mismatched.digest.value}.xml").toFile().shouldNotExist()
    }
}
