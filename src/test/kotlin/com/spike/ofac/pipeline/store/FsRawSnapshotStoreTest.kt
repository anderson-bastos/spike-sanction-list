package com.spike.ofac.pipeline.store

import com.spike.ofac.config.RawSnapshotStoreProperties
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.models.VersionId
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Unit tests for [FsRawSnapshotStore] — the filesystem-backed raw snapshot store
 * (task 13.4).
 *
 * These pin the behaviour required by Requirement 15 onto concrete, readable
 * scenarios:
 *
 *  - **Versioned naming (Req 15.2):** two publications sharing a `Publish_Date`
 *    but differing in `Digest` map to two distinct files and neither overwrites
 *    the other.
 *  - **Immutability (Req 15.4):** a second `put` for the same `VersionId` never
 *    overwrites the already-written file; the original bytes survive.
 *  - **Round-trip + integrity (Req 15.5):** `get` returns exactly the bytes that
 *    were `put`, `verifyIntegrity` is `true` when the recorded digest matches the
 *    stored bytes and `false` for a version whose recorded digest differs.
 *  - **Fail-closed (Req 15.9):** a failed write leaves no visible/partial file
 *    behind and a subsequent `get` fails rather than returning junk.
 *  - **File-only, never the DB (Req 15.8):** the store's only side effect is a
 *    file under the configured folder; it holds no database collaborator.
 *
 * Each test runs against a JUnit [TempDir] so raw-store operations never touch
 * the operational folder. Advertised digests are computed here with an
 * independent SHA-256 so expectations do not depend on the production hashing.
 */
class FsRawSnapshotStoreTest {

    private val publishDate = LocalDate.of(2024, 1, 15)

    private fun storeIn(folder: Path): FsRawSnapshotStore =
        FsRawSnapshotStore(RawSnapshotStoreProperties(folder = folder))

    /** Lowercase-hex SHA-256 of [bytes], computed independently of production code. */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

    /** A [VersionId] whose recorded digest is the real SHA-256 of [bytes]. */
    private fun versionOf(date: LocalDate, bytes: ByteArray): VersionId =
        VersionId(date, Sha256Digest.ofHex(sha256Hex(bytes)))

    private fun listXmlFiles(folder: Path): List<Path> =
        Files.list(folder).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".xml") }.toList()
        }

    // --- Req 15.2: versioned naming, no overwrite between same-day publications ---

    @Test
    fun `two same-day publications with different digests produce two distinct files, neither overwriting the other`(
        @TempDir folder: Path,
    ) {
        val store = storeIn(folder)

        val bytesA = "<snapshot>A</snapshot>".toByteArray()
        val bytesB = "<snapshot>B</snapshot>".toByteArray()
        val versionA = versionOf(publishDate, bytesA)
        val versionB = versionOf(publishDate, bytesB)

        // Same Publish_Date but different content -> different Digest.
        versionA.publishDate shouldBe versionB.publishDate
        versionA.digest shouldNotBe versionB.digest

        val pathA = store.put(versionA, bytesA)
        val pathB = store.put(versionB, bytesB)

        // Two distinct file names, both present, neither clobbered.
        pathA shouldNotBe pathB
        pathA.toFile().shouldExist()
        pathB.toFile().shouldExist()

        Files.readAllBytes(pathA) shouldBe bytesA
        Files.readAllBytes(pathB) shouldBe bytesB

        listXmlFiles(folder) shouldHaveSize 2
        listXmlFiles(folder).map { it.fileName.toString() }
            .shouldContainExactlyInAnyOrder(
                pathA.fileName.toString(),
                pathB.fileName.toString(),
            )
    }

    @Test
    fun `the file name is derived from the Publish_Date and Digest pair`(
        @TempDir folder: Path,
    ) {
        val store = storeIn(folder)
        val bytes = "<snapshot/>".toByteArray()
        val version = versionOf(publishDate, bytes)

        val path = store.put(version, bytes)

        val name = path.fileName.toString()
        name shouldBe "2024-01-15_${version.digest.value}.xml"
    }

    // --- Req 15.4: immutability — a second put never overwrites the file ---

    @Test
    fun `a second put for the same versionId does not overwrite the existing file`(
        @TempDir folder: Path,
    ) {
        val store = storeIn(folder)
        val original = "<snapshot>original</snapshot>".toByteArray()
        val version = versionOf(publishDate, original)

        val firstPath = store.put(version, original)

        // A later put for the same identity must not modify the stored bytes,
        // even if handed different content. Same identity -> same path returned.
        val tampered = "<snapshot>tampered</snapshot>".toByteArray()
        val secondPath = store.put(version, tampered)

        secondPath shouldBe firstPath
        Files.readAllBytes(firstPath) shouldBe original
        listXmlFiles(folder) shouldHaveSize 1
    }

    // --- Req 15.5: round-trip get + integrity verification ---

    @Test
    fun `round-trip put then get returns the exact bytes and verifyIntegrity is true`(
        @TempDir folder: Path,
    ) {
        val store = storeIn(folder)
        val bytes = "<snapshot>\u00e9\u00fc\u4e2d\u6587</snapshot>".toByteArray()
        val version = versionOf(publishDate, bytes)

        store.put(version, bytes)

        store.get(version) shouldBe bytes
        store.verifyIntegrity(version) shouldBe true
    }

    @Test
    fun `verifyIntegrity is false when the recorded digest does not match the stored bytes`(
        @TempDir folder: Path,
    ) {
        val store = storeIn(folder)
        val bytes = "<snapshot>data</snapshot>".toByteArray()

        // Store the bytes under a version whose recorded digest is a *different*
        // (well-formed) SHA-256 than the actual content — a tampered/mismatched
        // digest. verifyIntegrity recomputes over the file and must report false.
        val wrongDigest = Sha256Digest("a".repeat(64))
        val mismatched = VersionId(publishDate, wrongDigest)

        store.put(mismatched, bytes)

        store.verifyIntegrity(mismatched) shouldBe false
    }

    @Test
    fun `verifyIntegrity is false when no file is stored for the version`(
        @TempDir folder: Path,
    ) {
        val store = storeIn(folder)
        val absent = versionOf(publishDate, "never stored".toByteArray())

        store.verifyIntegrity(absent) shouldBe false
    }

    // --- Req 15.9: fail-closed — a failed write leaves no visible/partial file ---

    @Test
    @DisabledOnOs(OS.WINDOWS) // POSIX read-only directory permissions
    fun `a failed write leaves no visible or partial file and get fails`(
        @TempDir folder: Path,
    ) {
        val store = storeIn(folder)
        val bytes = "<snapshot>data</snapshot>".toByteArray()
        val version = versionOf(publishDate, bytes)

        // Make the base folder read-only so the temp-file write / atomic rename
        // cannot succeed. The store must fail rather than leave a partial file.
        val folderFile = folder.toFile()
        check(folderFile.setWritable(false)) { "could not make temp folder read-only" }
        try {
            var threw = false
            try {
                store.put(version, bytes)
            } catch (_: Exception) {
                threw = true
            }
            threw shouldBe true

            // No .xml (persisted) file and no leftover .tmp (partial) file.
            Files.list(folder).use { stream ->
                stream.filter { p ->
                    val n = p.fileName.toString()
                    n.endsWith(".xml") || n.endsWith(".tmp")
                }.toList()
            } shouldHaveSize 0
        } finally {
            // Restore writability so JUnit can clean up the TempDir.
            folderFile.setWritable(true)
        }

        // Fail-closed on read: nothing to reconstruct from.
        var getThrew = false
        try {
            store.get(version)
        } catch (_: NoSuchFileException) {
            getThrew = true
        }
        getThrew shouldBe true
        store.verifyIntegrity(version) shouldBe false
    }

    // --- Req 15.8: the raw snapshot is a file under the folder, never in the DB ---

    @Test
    fun `the store writes only a file under the configured folder and has no DB dependency`(
        @TempDir folder: Path,
    ) {
        val store = storeIn(folder)
        val bytes = "<snapshot>data</snapshot>".toByteArray()
        val version = versionOf(publishDate, bytes)

        val path = store.put(version, bytes)

        // The stored artifact lives under the operational folder, as a file.
        path.parent shouldBe folder
        path.toFile().shouldExist()
        Files.isRegularFile(path) shouldBe true

        // The only visible effect is that single file — no DB row, no other
        // artifacts. The store's constructor takes only the folder properties,
        // so it structurally cannot depend on the Data_Store.
        listXmlFiles(folder) shouldHaveSize 1

        val constructorParamTypes =
            FsRawSnapshotStore::class.java.declaredConstructors.single().parameterTypes.toList()
        constructorParamTypes shouldBe listOf(RawSnapshotStoreProperties::class.java)
    }
}
