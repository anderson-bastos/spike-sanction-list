package com.spike.ofac.pipeline.adapters

import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.stages.transform.RawParsedProfile
import java.net.URI
import java.time.Instant

/**
 * The single seam that varies per source (Req 13).
 *
 * A `SourceAdapter` encapsulates the two things that genuinely differ between
 * OFAC, UN, EU, and any future list: the **obtain I/O** (endpoint + auth) and
 * the **field mapping** from a source-shaped profile to the common
 * [InternalModelEntry]. The six source-independent core stages
 * (`obtain → validate → transform → version → persist → publish`) consume this
 * contract and never branch on the concrete source (Req 13.1): to add a source
 * you write a new adapter, you do not touch a stage.
 *
 * Design contract (`design.md` "SourceAdapter"):
 * ```
 * interface SourceAdapter:
 *   head(url) -> HeadResponse            # exposes Last-Modified, Digest
 *   get(url) -> HttpResponse             # GET with source-specific auth (Req 13.3)
 *   map_record(raw_profile) -> InternalModelEntry | MappingError(field)   # Req 13.4
 *   entity_type_of(raw_profile) -> "Individual"|"Entity"|"Vessel"|"Aircraft"|Unknown
 * ```
 *
 * Responsibilities split cleanly:
 *  - [head] / [get] are the only place source-specific **auth** lives. OFAC
 *    sends no credentials (Req 2.3); a future EU adapter would attach its token
 *    here (Req 13.3), and a missing/invalid token would abort obtain while the
 *    last good version is retained (Req 13.5).
 *  - [mapRecord] maps source fields to the common model, returning
 *    [MappingResult.MappingError] (naming the offending field) when a required
 *    field cannot be mapped (Req 13.2, 13.4).
 *  - [entityTypeOf] classifies a raw profile into one of the source's entity
 *    types (or [SourceEntityType.Unknown]) using that source's own reference
 *    value set, so the scope filter stays source-independent.
 */
interface SourceAdapter {

    /**
     * Issues a HEAD request for [url], exposing the change-detection headers the
     * `obtain` stage reads: `Last-Modified` and the advertised `Digest` (Req
     * 1.2). Source-specific auth (if any) is attached by the adapter; OFAC sends
     * none (Req 2.3).
     */
    fun head(url: URI): HeadResponse

    /**
     * Issues a GET request for [url], returning the full response the `obtain`
     * stage validates and downloads (Req 2.1). The adapter attaches any
     * source-specific auth (Req 13.3); OFAC sends none (Req 2.3).
     */
    fun get(url: URI): HttpResponse

    /**
     * Maps one source-shaped [RawParsedProfile] to the common
     * [InternalModelEntry], or reports the first required field it could not map
     * (Req 13.2, 13.4).
     *
     * A [MappingResult.MappingError] names the offending field so the transform
     * stage can reject the source and retain the last good version (Req 13.4);
     * it never partially maps.
     */
    fun mapRecord(rawProfile: RawParsedProfile): MappingResult

    /**
     * Classifies a raw profile into one of the source's entity types, using that
     * source's own reference value set. A missing / empty / unrecognized value
     * yields [SourceEntityType.Unknown] rather than throwing, so the scope filter
     * can exclude it with a diagnostic without aborting (Req 5.3).
     */
    fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType
}

/**
 * The five entity classes a source can report, mirroring the design's
 * `Individual|Entity|Vessel|Aircraft|Unknown`.
 *
 * This is intentionally broader than the in-scope
 * [com.spike.ofac.pipeline.models.EntityType] (which is `Individual|Entity`
 * only): an adapter must be able to *report* Vessel/Aircraft and Unknown so the
 * source-independent scope filter can exclude them (Req 5.2, 5.3). Only
 * [Individual] and [Entity] are in scope ([inScope]).
 */
enum class SourceEntityType {
    Individual,
    Entity,
    Vessel,
    Aircraft,
    Unknown;

    /** Whether this type is in scope for persistence (`Individual`/`Entity` only, Req 5). */
    val inScope: Boolean
        get() = this == Individual || this == Entity
}

/**
 * Response of [SourceAdapter.head] — the change-detection metadata the `obtain`
 * stage compares against the last-ingested version (Req 1.2, 1.3).
 *
 * @property statusCode the HTTP status code of the HEAD response.
 * @property lastModified the parsed `Last-Modified` header, or `null` if absent.
 * @property digest the advertised SHA-256 `Digest`, or `null` if the source
 *   advertised none (the `obtain` stage then falls back to Publish_Date +
 *   Record_Count, Req 1.5).
 */
data class HeadResponse(
    val statusCode: Int,
    val lastModified: Instant? = null,
    val digest: Sha256Digest? = null,
)

/**
 * Response of [SourceAdapter.get] — the downloaded snapshot plus the metadata
 * the `obtain` and `validate` stages need (Req 2.1, 2.2, 2.4).
 *
 * @property statusCode the final HTTP status code (after any redirects).
 * @property body the raw response bytes (the snapshot on success).
 * @property digest the advertised SHA-256 `Digest`, or `null` if absent.
 * @property contentLength the `Content-Length` header value, or `null` if the
 *   server did not send one; the `obtain` stage uses it to check download
 *   completeness before acceptance (Req 2.4).
 * @property redirectCount how many redirects were followed to reach [finalUri];
 *   the `obtain` stage rejects a download that exceeded the 5-redirect bound
 *   (Req 2.2).
 * @property finalUri the URI the response was ultimately served from (after
 *   following redirects), useful for diagnostics.
 */
data class HttpResponse(
    val statusCode: Int,
    val body: ByteArray,
    val digest: Sha256Digest? = null,
    val contentLength: Long? = null,
    val redirectCount: Int = 0,
    val finalUri: URI? = null,
) {
    // ByteArray needs structural equals/hashCode for a data class to behave.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponse) return false
        return statusCode == other.statusCode &&
            body.contentEquals(other.body) &&
            digest == other.digest &&
            contentLength == other.contentLength &&
            redirectCount == other.redirectCount &&
            finalUri == other.finalUri
    }

    override fun hashCode(): Int {
        var result = statusCode
        result = 31 * result + body.contentHashCode()
        result = 31 * result + (digest?.hashCode() ?: 0)
        result = 31 * result + (contentLength?.hashCode() ?: 0)
        result = 31 * result + redirectCount
        result = 31 * result + (finalUri?.hashCode() ?: 0)
        return result
    }
}

/**
 * Result of [SourceAdapter.mapRecord]: either a fully mapped entry or a named
 * required-field mapping failure (Req 13.4).
 *
 * The mapping is all-or-nothing: on the first required field it cannot map the
 * adapter returns [MappingError] (carrying the field name) instead of a
 * partially built entry, so the transform stage can reject the whole source and
 * keep the last good version (Req 13.4).
 */
sealed interface MappingResult {
    /** The profile mapped cleanly to [entry]. */
    data class Success(val entry: InternalModelEntry) : MappingResult

    /**
     * A required field could not be mapped. [field] names the offending field
     * (e.g. `"primary_name"`, `"sanction_programs"`) and [fixedRef] identifies
     * the source record when known, so the failure is actionable (Req 13.4).
     */
    data class MappingError(
        val field: String,
        val fixedRef: String? = null,
        val detail: String? = null,
    ) : MappingResult
}
