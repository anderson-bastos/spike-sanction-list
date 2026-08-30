package com.spike.ofac.pipeline.stages.persist

import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.VersionId
import com.spike.ofac.pipeline.stages.VersionPlan
import com.spike.ofac.pipeline.stages.VersionStage
import com.spike.ofac.pipeline.store.RawSnapshotStore
import com.spike.ofac.pipeline.store.VersionStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `persist` stage — write a validated snapshot as a new **isolated,
 * immutable** version (Req 7, Req 15).
 *
 * `persist` runs after `version` has produced an accepted [VersionPlan] and
 * before `publish` activates anything. It writes two artifacts and then links
 * them, in a strict, fail-closed order so that a failure at any step leaves
 * `CURRENT` and the pointer trio entirely unchanged (Req 7.7, 11.1):
 *
 *  1. **Raw snapshot file** — the raw bytes are written **once** to the
 *     [RawSnapshotStore] under a name derived from the version's
 *     (`Publish_Date`, `Digest`) pair (Req 15.1, 15.2). If the write throws, the
 *     store has already cleaned up its own partial temp file, and this stage
 *     returns [PersistResult.FailedRawWrite] with nothing else touched (Req 15.9).
 *  2. **Stored-file integrity** — SHA-256 is recomputed over the *stored file*
 *     bytes and compared against the recorded `Digest` (Req 15.5). Only when this
 *     matches may the file's path be associated with the version metadata later
 *     (step 4, Req 15.6). If it does not match the stored file is discarded and
 *     [PersistResult.FailedRawIntegrity] is returned, `CURRENT` unchanged
 *     (Req 15.7).
 *  3. **Records as an isolated version** — every entry is stamped with the
 *     [VersionId] (Req 7.4) via [VersionStage.stampVersion] and written through
 *     [VersionStore.putIsolated]. The version is persisted invisible to consumers
 *     — it is resolvable through no pointer until `publish` activates it (Req 7.6).
 *     If the record write throws, the partial version is discarded and
 *     [PersistResult.FailedPersist] is returned, `CURRENT` unchanged (Req 7.7).
 *  4. **Associate the raw path** — only after both the integrity check passed
 *     (step 2) *and* the records were written (step 3) is the stored file's path
 *     linked onto the version metadata via [VersionStore.associateRawPath]
 *     (Req 15.6). The version stays isolated; associating the path does not make
 *     it `CURRENT`.
 *
 * Ordering rationale (raw-write → integrity → record-write → associate):
 *  - The raw file is written and integrity-verified **before** any records, so a
 *    corrupt download never yields a persisted record set (fail before writing to
 *    the `Data_Store`).
 *  - The path is associated **last**, so the metadata only ever points at a raw
 *    file whose stored bytes have been confirmed to match the `Digest` (Req 15.6);
 *    a version can never advertise a raw path that failed integrity.
 *
 * Design contract (`design.md` "persist"):
 * ```
 * persist.write(version_plan, entries, raw_bytes, store, raw_store) -> PersistResult
 *   # 1. write raw_bytes to raw_store under a name derived from (Publish_Date, Digest)
 *   # 2. verify stored-file SHA-256 == recorded Digest before association
 *   # 3. write all records as an isolated, immutable Version to the Data_Store
 *   # 4. associate the stored raw file path with the Version metadata
 * PersistResult = PERSISTED(version_id, raw_path)
 *               | FAILED(RAW_WRITE)        # raw file write failed; discard partial file, CURRENT unchanged
 *               | FAILED(RAW_INTEGRITY)    # stored-file SHA-256 != Digest; discard file, CURRENT unchanged
 *               | FAILED(PERSIST)          # record write failed; discard partial version, CURRENT unchanged
 * ```
 */
object Persist {

    /**
     * Writes the raw snapshot and records for [versionPlan] as an isolated,
     * immutable version, then associates the verified raw file path.
     *
     * The new version is **never** made `CURRENT` here — that is `publish`'s job.
     * Every failure path leaves `CURRENT` and the pointer trio unchanged
     * (fail-closed, Req 7.7, 11.1).
     *
     * @param versionPlan the accepted plan carrying the [VersionId] to persist
     *   under (Req 7.2). Its `expected_count` is not consulted here; the count
     *   reconciliation gate lives in `publish` (Req 8.2).
     * @param entries the transformed in-scope entries to persist; each is stamped
     *   with [versionPlan]'s version id before the write (Req 7.4).
     * @param rawBytes the raw snapshot bytes to write to the [rawStore] (Req 15.1).
     * @param store the [VersionStore] the isolated version and its records are
     *   written to (Req 7.6).
     * @param rawStore the [RawSnapshotStore] the raw bytes are written to and whose
     *   stored-file integrity is verified (Req 15.5).
     * @return [PersistResult.Persisted] with the version id and stored raw path on
     *   success; otherwise [PersistResult.FailedRawWrite],
     *   [PersistResult.FailedRawIntegrity], or [PersistResult.FailedPersist],
     *   each leaving `CURRENT` unchanged.
     */
    fun write(
        versionPlan: VersionPlan.Accepted,
        entries: List<InternalModelEntry>,
        rawBytes: ByteArray,
        store: VersionStore,
        rawStore: RawSnapshotStore,
    ): PersistResult {
        val versionId = versionPlan.versionId

        // Step 1 — write the raw snapshot file (Req 15.1, 15.2). On any failure the
        // store cleans up its own partial temp file; we treat it as RAW_WRITE and
        // leave everything else untouched (Req 15.9).
        val rawPath =
            try {
                rawStore.put(versionId, rawBytes)
            } catch (_: Throwable) {
                return PersistResult.FailedRawWrite
            }

        // Step 2 — verify the *stored file's* SHA-256 against the recorded Digest
        // (Req 15.5). If it does not match, discard the file and fail closed; the
        // path must never be associated with a version (Req 15.7).
        val integrityOk =
            try {
                rawStore.verifyIntegrity(versionId)
            } catch (_: Throwable) {
                false
            }
        if (!integrityOk) {
            discardQuietly(rawPath)
            return PersistResult.FailedRawIntegrity
        }

        // Step 3 — stamp each record with its version id (Req 7.4) and write the
        // records as an isolated, immutable version (Req 7.6). A failure discards
        // the partial version and leaves CURRENT unchanged (Req 7.7).
        val stamped = VersionStage.stampVersion(entries, versionId)
        try {
            store.putIsolated(versionId, stamped)
        } catch (_: Throwable) {
            // The record write failed. The raw file was verified but has no version
            // to belong to, so discard it too and report a persist failure. The
            // pointer trio was never touched, so CURRENT is unchanged (Req 7.7).
            discardQuietly(rawPath)
            return PersistResult.FailedPersist
        }

        // Step 4 — associate the verified raw path with the version metadata, only
        // now that both the integrity check passed and the records are written
        // (Req 15.6). The version stays isolated; this does not activate it.
        try {
            store.associateRawPath(versionId, rawPath)
        } catch (_: Throwable) {
            // Records and raw file are both persisted and verified; only the linking
            // metadata write failed. That is a Data_Store write failure like the
            // record write, so it is fail-closed as PERSIST — the version is left
            // isolated (never CURRENT) and can be re-associated on reprocessing.
            return PersistResult.FailedPersist
        }

        return PersistResult.Persisted(versionId, rawPath)
    }

    /**
     * Best-effort discard of a stored raw file after a post-write failure
     * (Req 15.7, 15.9). Never throws: a cleanup failure must not mask the original
     * failure cause, and the file — if it survives — remains an orphan that no
     * version points at, so it can never be served.
     */
    private fun discardQuietly(rawPath: Path) {
        runCatching { Files.deleteIfExists(rawPath) }
    }
}

/**
 * Outcome of [Persist.write] (`design.md` `PersistResult`).
 *
 * Exactly one of: the raw file and records were written and verified and the
 * path associated ([Persisted]); or a fail-closed rejection at one of the three
 * write/verify steps, each leaving `CURRENT` and the pointer trio unchanged
 * ([FailedRawWrite], [FailedRawIntegrity], [FailedPersist]).
 */
sealed interface PersistResult {

    /**
     * The raw snapshot file and all records were written, the stored file's
     * SHA-256 matched the recorded `Digest`, and the raw path was associated with
     * the version metadata (Req 7.1, 7.6, 15.6). The version is persisted but
     * **isolated** — it becomes `CURRENT` only when `publish` activates it.
     *
     * @property versionId the identity of the newly persisted, isolated version.
     * @property rawPath the filesystem path of the stored, integrity-verified raw
     *   snapshot file.
     */
    data class Persisted(val versionId: VersionId, val rawPath: Path) : PersistResult

    /**
     * The raw file write failed; any partial file was discarded and `CURRENT` is
     * left unchanged (Req 15.9).
     */
    data object FailedRawWrite : PersistResult

    /**
     * The stored file's recomputed SHA-256 did not equal the recorded `Digest`;
     * the file was discarded and `CURRENT` is left unchanged (Req 15.7).
     */
    data object FailedRawIntegrity : PersistResult

    /**
     * The record write to the `Data_Store` failed; the partial version was
     * discarded and `CURRENT` is left unchanged (Req 7.7).
     */
    data object FailedPersist : PersistResult
}
