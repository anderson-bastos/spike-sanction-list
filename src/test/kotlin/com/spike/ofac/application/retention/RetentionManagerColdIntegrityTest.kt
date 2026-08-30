package com.spike.ofac.application.retention

import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.application.port.out.RawSnapshotStore
import com.spike.ofac.application.port.out.VersionStore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.LocalDate

/**
 * Unit tests for [RetentionManager.checkColdIntegrity] (task 19.2, Req 14.5).
 *
 * The integrity check delegates to [RawSnapshotStore.verifyIntegrity] (SHA-256 over
 * the stored **file** bytes vs the recorded `Digest`) and, on mismatch, flags the
 * version unusable via [VersionStore.markUnusable] while preserving the recorded
 * `Digest` in the returned [IntegrityOutcome].
 */
class RetentionManagerColdIntegrityTest {

    private val digest = Sha256Digest("a".repeat(64))
    private val versionId = VersionId(LocalDate.of(2024, 1, 15), digest)

    @Test
    fun `returns OK and never flags when the stored raw file digest matches (Req 14-5)`() {
        val rawStore = FakeRawSnapshotStore(integrityOk = true)
        val versionStore = RecordingVersionStore()
        val manager = RetentionManager(versionStore, rawStore)

        val outcome = manager.checkColdIntegrity(versionId)

        outcome shouldBe IntegrityOutcome.Ok
        // A passing check must not touch the version's usability flag.
        versionStore.markedUnusable shouldBe emptyList()
    }

    @Test
    fun `flags unusable and preserves the recorded digest on mismatch (Req 14-5)`() {
        val rawStore = FakeRawSnapshotStore(integrityOk = false)
        val versionStore = RecordingVersionStore()
        val manager = RetentionManager(versionStore, rawStore)

        val outcome = manager.checkColdIntegrity(versionId)

        val flagged = outcome.shouldBeInstanceOf<IntegrityOutcome.FlaggedUnusable>()
        // Recorded Digest is preserved for audit even though the file no longer matches.
        flagged.recordedDigest shouldBe digest
        // The version was flagged unusable exactly once, by identity.
        versionStore.markedUnusable shouldBe listOf(versionId)
    }

    /** [RawSnapshotStore] stub whose [verifyIntegrity] returns a fixed result. */
    private class FakeRawSnapshotStore(private val integrityOk: Boolean) : RawSnapshotStore {
        override fun put(versionId: VersionId, bytes: ByteArray): Path =
            throw UnsupportedOperationException()

        override fun get(versionId: VersionId): ByteArray = throw UnsupportedOperationException()

        override fun verifyIntegrity(versionId: VersionId): Boolean = integrityOk

        override fun delete(versionId: VersionId): Boolean = throw UnsupportedOperationException()
    }

    /**
     * [VersionStore] stub that records which versions were flagged unusable so the
     * test can assert the flag was (or was not) applied. Only the methods exercised
     * by [RetentionManager.checkColdIntegrity] are implemented.
     */
    private class RecordingVersionStore : VersionStore {
        val markedUnusable = mutableListOf<VersionId>()

        override fun markUnusable(versionId: VersionId) {
            markedUnusable += versionId
        }

        override fun putIsolated(
            versionId: VersionId,
            records: List<com.spike.ofac.domain.model.InternalModelEntry>,
        ) = throw UnsupportedOperationException()

        override fun associateRawPath(versionId: VersionId, rawPath: Path) =
            throw UnsupportedOperationException()

        override fun atomicSetCurrent(
            sourceList: com.spike.ofac.domain.model.SourceList,
            versionId: VersionId,
        ): Boolean = throw UnsupportedOperationException()

        override fun getPointer(
            sourceList: com.spike.ofac.domain.model.SourceList,
            pointer: com.spike.ofac.application.port.out.PointerKind,
        ): VersionId? = throw UnsupportedOperationException()

        override fun reclassifyCold(sourceList: com.spike.ofac.domain.model.SourceList) =
            throw UnsupportedOperationException()

        override fun coldVersions(
            sourceList: com.spike.ofac.domain.model.SourceList,
        ): List<VersionId> = throw UnsupportedOperationException()

        override fun lastIngested(
            sourceList: com.spike.ofac.domain.model.SourceList,
        ): com.spike.ofac.domain.model.VersionMetadata? = throw UnsupportedOperationException()

        override fun verifyIntegrity(versionId: VersionId): Boolean =
            throw UnsupportedOperationException()
    }
}
