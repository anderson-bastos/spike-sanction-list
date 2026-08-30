package com.spike.ofac.application.port.out

import com.spike.ofac.domain.model.VersionId
import java.nio.file.Path

/**
 * Storage contract for the raw snapshot files, kept deliberately distinct from
 * the [VersionStore] (design.md "RawSnapshotStore").
 *
 * The raw snapshot is stored **only** as a file in the local `Raw_Snapshot_Store`
 * folder, never in the `Data_Store` (Req 15.8). The contract guarantees:
 *
 *  - **Write-once, immutable files** named from the (`Publish_Date`, `Digest`)
 *    pair, so two publications sharing a `Publish_Date` but differing in content
 *    map to two distinct files and neither overwrites the other (Req 15.1, 15.2,
 *    15.4).
 *  - **Atomic visibility** — a partially written file is never visible as a
 *    persisted snapshot; only a fully written file ever appears under its final
 *    name (Req 15.3).
 *  - **Integrity** — the stored file's SHA-256 can be recomputed and compared
 *    against the recorded `Digest` for reconstruction confidence (Req 15.5, 14.5).
 *
 * Design contract (`design.md` "RawSnapshotStore"):
 * ```
 * interface RawSnapshotStore:
 *   put(version_id, bytes) -> stored_path
 *   get(version_id) -> bytes
 *   verify_integrity(version_id) -> bool
 *   delete(version_id) -> bool
 * ```
 */
interface RawSnapshotStore {

    /**
     * Writes [bytes] as a **write-once immutable** raw snapshot file for
     * [versionId] and returns the resolved file path (Req 15.1, 15.2, 15.4).
     *
     * The file name is derived from the version's (`Publish_Date`, `Digest`)
     * pair, so distinct pairs never collide — two same-day publications with
     * different digests produce two distinct files (Req 15.2). Implementations
     * write to a temporary file and atomically move it into place, so a partial
     * write is never visible under the final name (Req 15.3).
     *
     * Once a file exists for [versionId] it is immutable: a second `put` for the
     * same identity does not overwrite it and returns the existing path (Req 15.4).
     *
     * @return the filesystem [Path] of the stored (or already-present) file.
     */
    fun put(versionId: VersionId, bytes: ByteArray): Path

    /**
     * Reads the stored raw snapshot file bytes for [versionId], used for faithful
     * reconstruction (Req 14.3).
     *
     * @return the exact bytes previously stored by [put].
     * @throws java.nio.file.NoSuchFileException when no file is stored for
     *   [versionId].
     */
    fun get(versionId: VersionId): ByteArray

    /**
     * Recomputes SHA-256 over the stored raw **file** bytes for [versionId] and
     * compares it against the digest recorded in the [versionId] (Req 15.5, 14.5).
     *
     * @return `true` when the recomputed digest equals the recorded `Digest`;
     *   `false` otherwise (including when no file is stored for [versionId]).
     */
    fun verifyIntegrity(versionId: VersionId): Boolean

    /**
     * Discards the stored raw snapshot file for [versionId] (Req 14.4).
     *
     * Used by the `RetentionManager` on the retention-**disabled** path: when a
     * version is displaced past the HOT window and retention is off, its raw
     * snapshot file is removed from the local `Raw_Snapshot_Store`. The operation
     * is idempotent — deleting a snapshot that is not (or is no longer) present is
     * not an error.
     *
     * @return `true` when a stored file existed and was removed; `false` when no
     *   file was present for [versionId].
     */
    fun delete(versionId: VersionId): Boolean
}
