package com.spike.ofac.adapter.out.persistence

import com.spike.ofac.adapter.config.RawSnapshotStoreProperties
import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.version.VersionPlan
import com.spike.ofac.application.persist.Persist
import com.spike.ofac.application.persist.PersistResult
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Property 18: Raw snapshot file naming, immutability, and integrity association.
 *
 * The raw snapshot store ([FsRawSnapshotStore]) and the `persist` stage
 * ([Persist.write]) together must uphold Requirement 15's guarantees over the
 * local versioned `Raw_Snapshot_Store`:
 *
 *  - **Distinct naming, no overwrite (Req 15.2):** each distinct
 *    (`Publish_Date`, `Digest`) pair maps to exactly one file. Two publications
 *    sharing a `Publish_Date` but differing in content have different digests, so
 *    they map to two distinct files and neither overwrites the other.
 *  - **Immutability of a fully written file (Req 15.3):** once a file is written
 *    for a `VersionId`, a later `put` for that same identity — even with
 *    different bytes — never changes the stored bytes.
 *  - **Stored-file integrity (Req 15.5):** the SHA-256 recomputed over the stored
 *    file bytes equals the recorded `Digest` when the recorded digest is the real
 *    hash, and differs when it is not.
 *  - **Association only after integrity holds (Req 15.6):** [Persist.write]
 *    associates the version's `raw_snapshot_path` **only** once the stored file's
 *    integrity matches. With a mismatched digest it returns
 *    [PersistResult.FailedRawIntegrity] and the version's `rawSnapshotPath` stays
 *    `null`.
 *
 * Each `@Property` try gets its own temp folder (via [Files.createTempDirectory])
 * so files never collide across iterations, and the folder is cleaned up
 * afterwards. Publications are generated as content byte arrays; the real SHA-256
 * of each content is computed to build its [VersionId], and same-`Publish_Date` /
 * different-content pairs are generated to exercise the no-overwrite guarantee.
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 18: Raw snapshot file
 * naming, immutability, and integrity association`.
 *
 * **Validates: Requirements 15.2, 15.3, 15.5, 15.6**
 */
@Tag(PropertyTests.FEATURE_TAG)
class RawSnapshotFileIntegrityPropertyTest {

    /**
     * A same-`Publish_Date` pair of distinct contents plus an unrelated
     * "tamper" byte array used to attempt (and be denied) an overwrite.
     *
     * `contentB` is derived from `contentA` by appending a non-empty suffix so
     * the two contents — and therefore their SHA-256 digests — always differ.
     */
    data class SameDayPublications(
        val publishDate: LocalDate,
        val contentA: ByteArray,
        val contentB: ByteArray,
        val tamper: ByteArray,
    )

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 18: Raw snapshot file naming, immutability, and integrity association")
    fun rawFileNamingImmutabilityAndIntegrityAssociation(
        @ForAll @From("sameDayPublications") pubs: SameDayPublications,
    ) {
        val folder = Files.createTempDirectory("raw-snapshot-prop-")
        try {
            val store = FsRawSnapshotStore(RawSnapshotStoreProperties(folder = folder))

            // Each version's recorded digest is the *real* SHA-256 of its content.
            val versionA = VersionId(pubs.publishDate, sha256(pubs.contentA))
            val versionB = VersionId(pubs.publishDate, sha256(pubs.contentB))

            // Same day, different content => different digest => different identity.
            versionA.publishDate shouldBe versionB.publishDate
            (versionA.digest == versionB.digest) shouldBe false
            (versionA == versionB) shouldBe false

            // --- Req 15.2: exactly one file per distinct (Publish_Date, Digest);
            // same-day-different-digest => two distinct files, neither overwrites. ---
            val pathA = store.put(versionA, pubs.contentA)
            val pathB = store.put(versionB, pubs.contentB)

            (pathA == pathB) shouldBe false
            Files.readAllBytes(pathA) shouldBe pubs.contentA
            Files.readAllBytes(pathB) shouldBe pubs.contentB
            xmlFiles(folder) shouldHaveSize 2

            // --- Req 15.3/15.4: a fully written file's bytes never change after a
            // second put with different bytes for the same versionId. ---
            val secondPath = store.put(versionA, pubs.tamper)
            secondPath shouldBe pathA
            Files.readAllBytes(pathA) shouldBe pubs.contentA
            // No extra file materialized by the denied overwrite.
            xmlFiles(folder) shouldHaveSize 2

            // --- Req 15.5: stored-file SHA-256 equals the recorded Digest when the
            // recorded digest is the real hash; differs for a mismatched digest. ---
            store.verifyIntegrity(versionA) shouldBe true
            store.verifyIntegrity(versionB) shouldBe true

            val mismatched = VersionId(pubs.publishDate, mismatchedDigest(versionA.digest))
            store.put(mismatched, pubs.contentA)
            store.verifyIntegrity(mismatched) shouldBe false

            // --- Req 15.6: Persist.write associates raw_snapshot_path only after
            // integrity holds. Matching digest => Persisted + path associated;
            // mismatched digest => FailedRawIntegrity + rawSnapshotPath stays null. ---
            assertPersistAssociatesOnlyWhenIntegrityHolds(pubs)
        } finally {
            deleteRecursively(folder)
        }
    }

    /**
     * Runs [Persist.write] on a fresh store pair (both in one temp-backed store):
     * once with a matching recorded digest (integrity holds) and once with a
     * mismatched digest (integrity fails), asserting the raw path is associated
     * with the version metadata only in the first case (Req 15.6).
     */
    private fun assertPersistAssociatesOnlyWhenIntegrityHolds(pubs: SameDayPublications) {
        val rawFolder = Files.createTempDirectory("raw-snapshot-persist-")
        try {
            val rawStore = FsRawSnapshotStore(RawSnapshotStoreProperties(folder = rawFolder))
            val versionStore = InMemoryVersionStore()

            // Case 1 — recorded digest IS the real hash: integrity holds, so the
            // records persist and the raw path is associated (Req 15.6).
            val okVersion = VersionId(pubs.publishDate, sha256(pubs.contentA))
            val okPlan = VersionPlan.Accepted(versionId = okVersion, expectedCount = 1)
            val okResult = Persist.write(
                versionPlan = okPlan,
                entries = entriesFor(okVersion),
                rawBytes = pubs.contentA,
                store = versionStore,
                rawStore = rawStore,
            )

            (okResult is PersistResult.Persisted) shouldBe true
            val associatedPath = versionStore.metadataOf(okVersion).shouldNotBeNull().rawSnapshotPath
            associatedPath.shouldNotBeNull()

            // Case 2 — recorded digest does NOT match the stored bytes: integrity
            // fails, so Persist.write returns FailedRawIntegrity and the version's
            // rawSnapshotPath stays null (never associated) (Req 15.6).
            val badVersion = VersionId(pubs.publishDate, mismatchedDigest(okVersion.digest))
            val badPlan = VersionPlan.Accepted(versionId = badVersion, expectedCount = 1)
            val badResult = Persist.write(
                versionPlan = badPlan,
                entries = entriesFor(badVersion),
                rawBytes = pubs.contentA,
                store = versionStore,
                rawStore = rawStore,
            )

            (badResult is PersistResult.FailedRawIntegrity) shouldBe true
            // The failed version was never persisted, so it has no metadata and
            // therefore no associated raw path.
            versionStore.metadataOf(badVersion).shouldBeNull()
        } finally {
            deleteRecursively(rawFolder)
        }
    }

    /**
     * Generates two distinct contents sharing one `Publish_Date`, plus a
     * non-matching "tamper" content used to attempt a denied overwrite.
     */
    @Provide
    fun sameDayPublications(): Arbitrary<SameDayPublications> {
        val publishDates = Arbitraries.integers().between(0, 3650)
            .map { EPOCH_START.plusDays(it.toLong()) }
        val contents = Arbitraries.integers().between(0, 64)
            .flatMap { size -> Arbitraries.bytes().array(ByteArray::class.java).ofSize(size) }
        // Non-empty differing suffix so B always differs from A (bytes 1..127).
        val suffixes = Arbitraries.bytes().between(1, Byte.MAX_VALUE)
            .array(ByteArray::class.java).ofMinSize(1).ofMaxSize(8)

        return publishDates.flatMap { date ->
            contents.flatMap { a ->
                suffixes.map { suffix ->
                    SameDayPublications(
                        publishDate = date,
                        contentA = a,
                        contentB = a + suffix,
                        // A distinct tamper payload for the immutability attempt.
                        tamper = a + suffix + suffix,
                    )
                }
            }
        }
    }

    /** A single in-scope entry stamped with [versionId] (satisfies model cardinalities). */
    private fun entriesFor(versionId: VersionId): List<InternalModelEntry> =
        listOf(
            InternalModelEntry(
                fixedRef = FixedRef("FR-${versionId.digest.value.take(8)}"),
                entityType = EntityType.Individual,
                primaryName = "name",
                sanctionPrograms = listOf("program"),
                versionId = versionId,
            ),
        )

    /** Lowercase-hex SHA-256 [Sha256Digest] of [bytes], computed independently. */
    private fun sha256(bytes: ByteArray): Sha256Digest {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Sha256Digest.ofHex(digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
    }

    /**
     * Returns a well-formed 64-hex digest guaranteed different from [real] by
     * flipping the first hex character. Used to fabricate a recorded digest that
     * does not match the stored bytes.
     */
    private fun mismatchedDigest(real: Sha256Digest): Sha256Digest {
        val hex = real.value
        val head = if (hex[0] == '0') '1' else '0'
        return Sha256Digest.ofHex(head + hex.substring(1))
    }

    private fun xmlFiles(folder: Path): List<Path> =
        Files.list(folder).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".xml") }.toList()
        }

    private fun deleteRecursively(folder: Path) {
        if (!Files.exists(folder)) return
        Files.walk(folder).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        val EPOCH_START: LocalDate = LocalDate.of(2020, 1, 1)
    }
}
