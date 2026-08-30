package com.spike.ofac.application.obtain

import com.spike.ofac.application.port.out.HeadResponse
import com.spike.ofac.application.port.out.HttpResponse
import com.spike.ofac.application.port.out.SourceAdapter
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.VersionMetadata
import java.net.URI
import java.time.LocalDate

/**
 * The `obtain` stage: HEAD change-check followed, only on a change, by a GET of
 * the full snapshot (Req 1, Req 2, Req 13).
 *
 * This object owns both halves of obtain:
 *
 *  - [checkChange] issues a HEAD via the [SourceAdapter], reads `Last-Modified`
 *    and the advertised `Digest`, and decides `NO_CHANGE` vs `CHANGED` against
 *    the most recently ingested version (Req 1.2, 1.3, 1.4). When the source
 *    advertises no `Digest`, it falls back to comparing `Publish_Date` +
 *    `Record_Count` (Req 1.5). Any connect error / timeout / non-2xx maps to
 *    [ChangeDecision.HeadFailed] so the cycle ends with `CURRENT` unchanged and
 *    the failure is recorded for the next scheduled retry (Req 1.6).
 *  - [download] issues a GET over HTTPS following at most five redirects, checks
 *    the download is complete (`Content-Length` vs bytes received), and returns
 *    the snapshot bytes; any failure discards the partial download and leaves
 *    `CURRENT` unchanged (Req 2.1, 2.2, 2.4, 2.5). Credentials are the adapter's
 *    concern — OFAC sends none (Req 2.3), a token-based source supplies one
 *    (Req 13.3) — so this stage stays source-independent.
 *
 * Design contract (`design.md` "obtain"):
 * ```
 * obtain.check_change(adapter, last_ingested: VersionRef?) -> ChangeDecision
 * ChangeDecision = NO_CHANGE | CHANGED(advertised_digest?, last_modified)
 *                | HEAD_FAILED(cause)
 *
 * obtain.download(adapter) -> DownloadResult
 * DownloadResult = SNAPSHOT(bytes, advertised_digest?, content_length?)
 *                | DOWNLOAD_FAILED(cause)
 * ```
 *
 * Both operations are pure with respect to the store: they never mutate a
 * pointer or a version. Acting on their result (ending the cycle, recording the
 * failure, proceeding to `validate`) is the caller's responsibility.
 */
object Obtain {

    /** Bounded 2xx range for a successful HTTP response. */
    private val SUCCESS_RANGE = 200..299

    /** Maximum redirects a download may follow before it is rejected (Req 2.2). */
    const val MAX_REDIRECTS: Int = 5

    /**
     * Issues a HEAD via [adapter] for [url] and decides whether the source has a
     * new publication relative to [lastIngested] (Req 1.2, 1.3, 1.4, 1.5, 1.6).
     *
     * Decision order:
     *  1. The HEAD itself must succeed — the adapter is invoked with a bounded
     *     timeout (30s, enforced by the adapter's transport). Any thrown
     *     exception (connect error / timeout) or a non-2xx status maps to
     *     [ChangeDecision.HeadFailed] with the cause, ending the cycle with
     *     `CURRENT` unchanged and recording the failure for retry (Req 1.6).
     *  2. With no prior version, anything the source advertises is new →
     *     [ChangeDecision.Changed].
     *  3. **Digest path** (Req 1.3, 1.4): when the source advertises a `Digest`,
     *     compare it to the last-ingested version's digest. Equal → `NO_CHANGE`;
     *     different → `CHANGED`.
     *  4. **Fallback path** (Req 1.5): when the `Digest` header is absent, compare
     *     the source-advertised [advertisedPublishDate] + [advertisedRecordCount]
     *     to the last-ingested version's publish date + record count. Any
     *     difference is a change. If the fallback inputs are themselves absent,
     *     the change cannot be ruled out, so the snapshot is treated as
     *     `CHANGED` (fail-open toward re-download, which `validate`/`version`
     *     will still gate — never fail-closed into missing an update).
     *
     * @param adapter the source adapter performing the HEAD with its own
     *   (credential) policy and 30s timeout (Req 1.2, 2.3, 13.3).
     * @param url the snapshot URL to HEAD.
     * @param lastIngested metadata of the most recently ingested version, or
     *   `null` when the list has never been ingested (Req 1.3).
     * @param advertisedPublishDate the source-advertised `Publish_Date` used only
     *   for the absent-digest fallback (Req 1.5); `null` when unknown.
     * @param advertisedRecordCount the source-advertised `Record_Count` used only
     *   for the absent-digest fallback (Req 1.5); `null` when unknown.
     */
    fun checkChange(
        adapter: SourceAdapter,
        url: URI,
        lastIngested: VersionMetadata?,
        advertisedPublishDate: LocalDate? = null,
        advertisedRecordCount: Int? = null,
    ): ChangeDecision {
        // Step 1 — the HEAD must succeed. A connect error / timeout surfaces as a
        // thrown exception; a reachable-but-error server surfaces as a non-2xx
        // status. Both end the cycle with CURRENT unchanged (Req 1.6).
        val head: HeadResponse = try {
            adapter.head(url)
        } catch (e: Exception) {
            return ChangeDecision.HeadFailed(
                cause = e.message ?: e.javaClass.simpleName,
            )
        }
        if (head.statusCode !in SUCCESS_RANGE) {
            return ChangeDecision.HeadFailed(
                cause = "HEAD returned non-success status ${head.statusCode}",
            )
        }

        // Step 2 — no prior version: whatever the source has is new (Req 1.3).
        if (lastIngested == null) {
            return ChangeDecision.Changed(head.digest, head.lastModified)
        }

        val advertisedDigest = head.digest
        return if (advertisedDigest != null) {
            // Step 3 — digest path (Req 1.3, 1.4).
            if (advertisedDigest == lastIngested.versionId.digest) {
                ChangeDecision.NoChange
            } else {
                ChangeDecision.Changed(advertisedDigest, head.lastModified)
            }
        } else {
            // Step 4 — absent-digest fallback: compare Publish_Date + Record_Count
            // to the last-ingested version's (Req 1.5).
            val samePublishDate = advertisedPublishDate != null &&
                advertisedPublishDate == lastIngested.versionId.publishDate
            val sameRecordCount = advertisedRecordCount != null &&
                advertisedRecordCount == lastIngested.recordCount
            if (samePublishDate && sameRecordCount) {
                ChangeDecision.NoChange
            } else {
                // Either the fallback inputs differ, or they are unavailable and a
                // change cannot be ruled out — treat as changed rather than risk
                // silently skipping a real update.
                ChangeDecision.Changed(null, head.lastModified)
            }
        }
    }

    /**
     * Issues a GET via [adapter] for [url] and returns the full snapshot bytes,
     * or a [DownloadResult.DownloadFailed] carrying the cause (Req 2.1, 2.2,
     * 2.4, 2.5).
     *
     * A download is accepted only when **all** of these hold:
     *  - the GET completed without a thrown exception (no connect error / timeout);
     *  - the final status is 2xx (a non-2xx is a failure);
     *  - at most [MAX_REDIRECTS] redirects were followed (Req 2.2);
     *  - the download is complete — when the server advertised a `Content-Length`,
     *    the received body length must equal it exactly (Req 2.4).
     *
     * On any failure the partial download is discarded (the bytes are simply not
     * returned) and `CURRENT` is left unchanged (Req 2.5). The adapter attaches
     * whatever credentials the source requires — OFAC none (Req 2.3), a
     * token-based source its token (Req 13.3) — so this stage never branches on
     * the source.
     *
     * @param adapter the source adapter performing the GET with its own credential
     *   policy and 120s timeout (Req 2.1, 2.3, 13.3).
     * @param url the snapshot URL to GET.
     */
    fun download(adapter: SourceAdapter, url: URI): DownloadResult {
        // Connect error / timeout surfaces as a thrown exception (Req 2.5).
        val response: HttpResponse = try {
            adapter.get(url)
        } catch (e: Exception) {
            return DownloadResult.DownloadFailed(
                cause = e.message ?: e.javaClass.simpleName,
            )
        }

        // A non-2xx status is a failed download; discard and leave CURRENT put.
        if (response.statusCode !in SUCCESS_RANGE) {
            return DownloadResult.DownloadFailed(
                cause = "GET returned non-success status ${response.statusCode}",
            )
        }

        // Too many redirects (Req 2.2): 5 accepted, 6+ rejected.
        if (response.redirectCount > MAX_REDIRECTS) {
            return DownloadResult.DownloadFailed(
                cause = "download followed ${response.redirectCount} redirects, " +
                    "exceeding the limit of $MAX_REDIRECTS",
            )
        }

        // Completeness (Req 2.4): when a Content-Length was advertised, the received
        // body must match it exactly; a mismatch means a truncated/incomplete
        // download, which is discarded.
        val contentLength = response.contentLength
        if (contentLength != null && response.body.size.toLong() != contentLength) {
            return DownloadResult.DownloadFailed(
                cause = "incomplete download: received ${response.body.size} bytes " +
                    "but Content-Length advertised $contentLength",
            )
        }

        return DownloadResult.Snapshot(
            bytes = response.body,
            advertisedDigest = response.digest,
            contentLength = contentLength,
        )
    }
}

/**
 * Outcome of [Obtain.checkChange] (`design.md` `ChangeDecision`).
 *
 * Exactly one of: the source has no new publication ([NoChange]); the source has
 * a new publication that should be downloaded ([Changed]); or the HEAD failed so
 * the cycle ends with `CURRENT` unchanged ([HeadFailed], Req 1.6).
 */
sealed interface ChangeDecision {

    /**
     * The source's current publication matches the last-ingested version, so
     * nothing is downloaded and the cycle ends with `CURRENT` unchanged (Req 1.4).
     */
    data object NoChange : ChangeDecision

    /**
     * The source has a new publication relative to the last-ingested version, so
     * the cycle proceeds to [Obtain.download] (Req 1.3).
     *
     * @property advertisedDigest the advertised SHA-256 `Digest`, or `null` when
     *   the source advertised none and the change was decided via the
     *   `Publish_Date` + `Record_Count` fallback (Req 1.5).
     * @property lastModified the `Last-Modified` header the HEAD reported, or
     *   `null` when absent.
     */
    data class Changed(
        val advertisedDigest: Sha256Digest?,
        val lastModified: java.time.Instant?,
    ) : ChangeDecision

    /**
     * The HEAD request failed (connect error / timeout / non-2xx). The cycle ends
     * with `CURRENT` unchanged and the failure is recorded for the next scheduled
     * retry (Req 1.6).
     *
     * @property cause a human-readable description of the failure.
     */
    data class HeadFailed(val cause: String) : ChangeDecision
}

/**
 * Outcome of [Obtain.download] (`design.md` `DownloadResult`).
 *
 * Exactly one of: the full snapshot was downloaded and verified complete
 * ([Snapshot]); or the download failed and the partial bytes were discarded with
 * `CURRENT` unchanged ([DownloadFailed], Req 2.5).
 */
sealed interface DownloadResult {

    /**
     * The full snapshot was downloaded over HTTPS within the redirect and
     * completeness bounds (Req 2.1, 2.2, 2.4).
     *
     * @property bytes the raw snapshot bytes handed to `validate`.
     * @property advertisedDigest the advertised SHA-256 `Digest`, or `null` if
     *   absent; `validate` compares the recomputed hash against it (Req 3.3).
     * @property contentLength the advertised `Content-Length`, or `null` if the
     *   server sent none.
     */
    data class Snapshot(
        val bytes: ByteArray,
        val advertisedDigest: Sha256Digest?,
        val contentLength: Long?,
    ) : DownloadResult {
        // ByteArray needs structural equals/hashCode for a data class to behave.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Snapshot) return false
            return bytes.contentEquals(other.bytes) &&
                advertisedDigest == other.advertisedDigest &&
                contentLength == other.contentLength
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + (advertisedDigest?.hashCode() ?: 0)
            result = 31 * result + (contentLength?.hashCode() ?: 0)
            return result
        }
    }

    /**
     * The download failed (connect error / http error / too many redirects /
     * timeout / incomplete). The partial download is discarded and `CURRENT` is
     * left unchanged (Req 2.5).
     *
     * @property cause a human-readable description of the failure.
     */
    data class DownloadFailed(val cause: String) : DownloadResult
}
