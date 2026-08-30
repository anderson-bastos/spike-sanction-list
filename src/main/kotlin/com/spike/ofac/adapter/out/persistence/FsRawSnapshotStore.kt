package com.spike.ofac.adapter.out.persistence

import com.spike.ofac.adapter.config.RawSnapshotStoreProperties
import com.spike.ofac.application.port.out.RawSnapshotStore
import com.spike.ofac.domain.model.VersionId
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.format.DateTimeFormatter

/**
 * Filesystem-backed [RawSnapshotStore] over the local versioned folder
 * (design.md "RawSnapshotStore", task 13.3).
 *
 * Each raw snapshot is stored as a single **write-once immutable** file in
 * [RawSnapshotStoreProperties.folder], never in the `Data_Store` (Req 15.8). The
 * file name is derived from the version's (`Publish_Date`, `Digest`) pair, so two
 * publications sharing a `Publish_Date` but differing in content map to two
 * distinct files and neither overwrites the other (Req 15.1, 15.2, 15.4).
 *
 * **Atomic visibility.** [put] first writes the bytes to a temporary file in the
 * same directory, then atomically renames it onto the final name via
 * [Files.move] with [StandardCopyOption.ATOMIC_MOVE]. A partially written file
 * only ever exists under the temporary name and is cleaned up on failure, so it
 * is never visible as a persisted snapshot (Req 15.3). Because the target
 * directory is the same filesystem as the temp file, the rename is a metadata
 * operation and stays atomic.
 *
 * **Immutability.** If a file already exists for the (`Publish_Date`, `Digest`)
 * pair, [put] does not overwrite it — it returns the existing path (Req 15.4).
 *
 * @param properties supplies the operational folder; tests point it at a
 *   separate temporary directory so raw-store operations never touch the
 *   operational folder.
 */
@Component
class FsRawSnapshotStore(
    private val properties: RawSnapshotStoreProperties,
) : RawSnapshotStore {

    private val baseFolder: Path get() = properties.folder

    /**
     * Writes [bytes] as the immutable raw snapshot file for [versionId] using a
     * temp-file + atomic rename, and returns the resolved path (Req 15.1–15.4).
     *
     * When a file already exists for the (`Publish_Date`, `Digest`) pair the
     * existing file is kept untouched and its path returned (write-once, Req 15.4).
     */
    override fun put(versionId: VersionId, bytes: ByteArray): Path {
        Files.createDirectories(baseFolder)
        val target = fileFor(versionId)

        // Write-once: an existing file is immutable and must never be overwritten
        // (Req 15.2, 15.4). Same identity -> same file -> return it unchanged.
        if (Files.exists(target)) {
            return target
        }

        val temp = Files.createTempFile(baseFolder, "raw-", ".tmp")
        try {
            Files.write(temp, bytes)
            moveIntoPlace(temp, target)
        } catch (e: Throwable) {
            // Fail-closed: never leave a visible partial file behind (Req 15.3).
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
        return target
    }

    /**
     * Reads the stored raw file bytes for [versionId] (Req 14.3).
     *
     * @throws java.nio.file.NoSuchFileException when no file is stored.
     */
    override fun get(versionId: VersionId): ByteArray = Files.readAllBytes(fileFor(versionId))

    /**
     * Recomputes SHA-256 over the stored file bytes and compares it against the
     * recorded [VersionId.digest] (Req 15.5, 14.5).
     *
     * Returns `false` when no file is stored or the file cannot be read, so a
     * missing/unreadable snapshot is treated as an integrity failure rather than
     * throwing.
     */
    override fun verifyIntegrity(versionId: VersionId): Boolean {
        val file = fileFor(versionId)
        val bytes =
            try {
                Files.readAllBytes(file)
            } catch (_: IOException) {
                return false
            }
        return sha256Hex(bytes) == versionId.digest.value
    }

    /**
     * Discards the stored raw snapshot file for [versionId] (Req 14.4).
     *
     * Idempotent: returns `false` when no file is present. Used by the
     * `RetentionManager` when retention is disabled and a displaced version must
     * be dropped together with its raw file.
     */
    override fun delete(versionId: VersionId): Boolean = Files.deleteIfExists(fileFor(versionId))

    /**
     * Atomically renames the fully written [temp] file onto [target].
     *
     * Falls back to a non-atomic replace only if the platform does not support
     * atomic moves; the target never pre-exists here (checked in [put]), so no
     * durable file is clobbered.
     */
    private fun moveIntoPlace(temp: Path, target: Path) {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Resolves the immutable file name for [versionId] from its (`Publish_Date`,
     * `Digest`) pair (Req 15.1, 15.2). Distinct pairs yield distinct names, so
     * same-day publications with different digests never collide.
     */
    private fun fileFor(versionId: VersionId): Path {
        val date = DATE_FORMAT.format(versionId.publishDate)
        return baseFolder.resolve("${date}_${versionId.digest.value}$FILE_EXTENSION")
    }

    private companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private const val FILE_EXTENSION = ".xml"

        /** Lowercase-hex SHA-256 of [bytes], matching the Sha256Digest encoding. */
        private fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }
}
