package com.spike.ofac.adapter.out.source

import com.spike.ofac.application.port.out.HeadResponse
import com.spike.ofac.application.port.out.HttpResponse
import com.spike.ofac.application.port.out.MappingResult
import com.spike.ofac.application.port.out.SourceAdapter
import com.spike.ofac.application.port.out.SourceEntityType
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.transform.ParsedSnapshot
import com.spike.ofac.domain.transform.ProfileEntryBuilder
import com.spike.ofac.domain.transform.RawParsedProfile
import com.spike.ofac.domain.transform.RawReferenceTables
import com.spike.ofac.domain.transform.ScopeFilter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The OFAC [SourceAdapter] (task 11.1).
 *
 * Realizes the source-specific half of the pipeline for the OFAC SDN /
 * Consolidated lists, leaving the six core stages source-independent (Req 13.1):
 *
 *  - **Obtain I/O with no credentials** ([head] / [get]): OFAC's Advanced XML is
 *    published anonymously over HTTPS, so this adapter attaches **no**
 *    authorization or credential headers of any kind (Req 2.3). The concrete
 *    HTTP mechanics are pushed behind an injectable [Transport] so the adapter
 *    is unit-testable without real network calls (task 11.2).
 *  - **Field mapping** ([mapRecord]): delegates the actual normalization to the
 *    existing [ProfileEntryBuilder] (which resolves references and enforces the
 *    model cardinalities), then adapts its outcome to a [MappingResult],
 *    returning [MappingResult.MappingError] naming the offending field when a
 *    required field could not be mapped (Req 13.2, 13.4).
 *  - **Entity classification** ([entityTypeOf]): maps `PartySubTypeID` through
 *    the observed ReferenceValueSet (`{"1":Vessel,"2":Aircraft,"3":Entity,
 *    "4":Individual}`) reused from [ScopeFilter], yielding
 *    [SourceEntityType.Unknown] for a missing / empty / unrecognized value.
 *
 * @property transport the HTTP transport used by [head] / [get]. Defaults to a
 *   `java.net.http.HttpClient`-backed transport that follows normal redirects
 *   with bounded timeouts; tests inject a fake to assert no-credentials
 *   behavior and to avoid real I/O.
 * @property entryBuilder the reference-resolving normalizer reused for
 *   [mapRecord]; injectable for testing.
 * @property references the cross-section reference tables (`ListID`/program,
 *   `FeatureTypeID`, `LocationID`, ... labels and the sanctions/relationship link
 *   tables) that [mapRecord] resolves a raw profile against. In the running
 *   pipeline these are the tables the streaming parser gathered in the same pass
 *   as the profiles; the transform stage sets them per snapshot. They default to
 *   empty so a profile carrying no ID references maps without extra setup.
 */
class OfacAdapter(
    private val transport: Transport = JdkHttpTransport(),
    private val entryBuilder: ProfileEntryBuilder = ProfileEntryBuilder(),
    private val references: RawReferenceTables = RawReferenceTables(),
) : SourceAdapter {

    /**
     * The minimal HTTP seam the adapter needs. Keeping HTTP details behind this
     * interface makes [OfacAdapter] testable with an in-memory fake and keeps
     * the credential policy (send none, Req 2.3) enforced in one place.
     */
    interface Transport {
        /** Perform a HEAD request for [url] with the given (credential-free) [headers]. */
        fun head(url: URI, headers: Map<String, String>): HeadResponse

        /** Perform a GET request for [url] with the given (credential-free) [headers]. */
        fun get(url: URI, headers: Map<String, String>): HttpResponse
    }

    override fun head(url: URI): HeadResponse =
        // OFAC sends no credentials (Req 2.3): no headers are supplied.
        transport.head(url, NO_CREDENTIAL_HEADERS)

    override fun get(url: URI): HttpResponse =
        // OFAC sends no credentials (Req 2.3): no headers are supplied.
        transport.get(url, NO_CREDENTIAL_HEADERS)

    override fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType =
        when (val classification = ScopeFilter.classify(rawProfile.partySubTypeId)) {
            is ScopeFilter.Classification.InScope -> when (classification.entityType) {
                com.spike.ofac.domain.model.EntityType.Individual -> SourceEntityType.Individual
                com.spike.ofac.domain.model.EntityType.Entity -> SourceEntityType.Entity
            }
            is ScopeFilter.Classification.OutOfScope -> when (classification.type) {
                ScopeFilter.PartySubType.Vessel -> SourceEntityType.Vessel
                ScopeFilter.PartySubType.Aircraft -> SourceEntityType.Aircraft
                // Entity/Individual never reach here (they are InScope above).
                ScopeFilter.PartySubType.Entity -> SourceEntityType.Entity
                ScopeFilter.PartySubType.Individual -> SourceEntityType.Individual
            }
            is ScopeFilter.Classification.Unrecognized -> SourceEntityType.Unknown
        }

    override fun mapRecord(rawProfile: RawParsedProfile): MappingResult {
        // Only in-scope profiles are mappable to an InternalModelEntry (its
        // entity_type is Individual|Entity only). Reject others as a mapping
        // failure on the entity_type field so the caller can act (Req 13.4).
        if (!entityTypeOf(rawProfile).inScope) {
            return MappingResult.MappingError(
                field = "entity_type",
                fixedRef = rawProfile.fixedRef.ifBlank { null },
                detail = "PartySubTypeID '${rawProfile.partySubTypeId}' is not an in-scope " +
                    "Individual/Entity, so no InternalModelEntry can be mapped",
            )
        }

        // Delegate the actual normalization (reference resolution + cardinality
        // enforcement) to the existing builder, feeding it a single-profile
        // snapshot carrying this profile's reference tables.
        val results = entryBuilder.build(singleProfileSnapshot(rawProfile))
        val result = results.firstOrNull()
            ?: return MappingResult.MappingError(
                field = "entity_type",
                fixedRef = rawProfile.fixedRef.ifBlank { null },
                detail = "Profile was not classified in scope by the builder",
            )

        val entry = result.entry
        return if (entry != null) {
            MappingResult.Success(entry)
        } else {
            // A required field could not be mapped (zero name, blank FixedRef,
            // no resolvable sanction program, ...). Name the field (Req 13.4).
            MappingResult.MappingError(
                field = fieldFromReason(result.unbuildableReason),
                fixedRef = rawProfile.fixedRef.ifBlank { null },
                detail = result.unbuildableReason,
            )
        }
    }

    /**
     * Wraps a single [RawParsedProfile] with its own reference tables into a
     * [ParsedSnapshot] so it can be fed to [ProfileEntryBuilder.build]. The
     * profile carries the source references it needs on itself; the adapter's
     * [references] tables (empty by default) resolve those ID references —
     * sanction programs (via the linked `SanctionsEntry`), feature-type labels,
     * locations, documents, and relationships — exactly as the transform stage
     * resolves them, so a required field that cannot be resolved surfaces as a
     * [MappingResult.MappingError] (Req 13.4) rather than a partial entry.
     */
    private fun singleProfileSnapshot(rawProfile: RawParsedProfile): ParsedSnapshot =
        ParsedSnapshot(
            publishDate = null,
            profiles = listOf(rawProfile),
            references = references,
        )

    /**
     * Derives the offending required-field name from the builder's
     * human-readable reason, so [MappingResult.MappingError.field] is a stable,
     * machine-usable token (Req 13.4).
     */
    private fun fieldFromReason(reason: String?): String = when {
        reason == null -> "unknown"
        reason.contains("FixedRef") -> "fixed_ref"
        reason.contains("primary_name") || reason.contains("zero aliases") -> "primary_name"
        reason.contains("sanction program") -> "sanction_programs"
        else -> "unknown"
    }

    companion object {
        /** OFAC sends no credentials (Req 2.3): the credential-free header set is empty. */
        private val NO_CREDENTIAL_HEADERS: Map<String, String> = emptyMap()
    }
}

/**
 * Default [OfacAdapter.Transport] backed by the JDK `java.net.http.HttpClient`.
 *
 * Follows normal redirects and applies bounded timeouts (30s for HEAD change
 * checks, 120s for GET downloads by default, matching the design's obtain-stage
 * bounds; both are constructor-overridable so tests can drive the real timeout
 * path quickly).
 * It never adds any credential/authorization header — the adapter passes only
 * the (empty) credential-free header map, and this transport adds nothing of its
 * own — so the OFAC no-credentials guarantee holds end to end (Req 2.3).
 */
class JdkHttpTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build(),
    private val headTimeout: Duration = Duration.ofSeconds(30),
    private val getTimeout: Duration = Duration.ofSeconds(120),
) : OfacAdapter.Transport {

    override fun head(url: URI, headers: Map<String, String>): HeadResponse {
        val request = baseRequest(url, headers)
            .timeout(headTimeout)
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .build()
        val response = client.send(request, BodyHandlers.discarding())
        return HeadResponse(
            statusCode = response.statusCode(),
            lastModified = response.headers().firstValue("Last-Modified")
                .map { parseHttpDate(it) }.orElse(null),
            digest = response.headers().firstValue("Digest")
                .map { parseDigestHeader(it) }.orElse(null),
        )
    }

    override fun get(url: URI, headers: Map<String, String>): HttpResponse {
        val request = baseRequest(url, headers)
            .timeout(getTimeout)
            .GET()
            .build()
        val response = client.send(request, BodyHandlers.ofByteArray())
        return HttpResponse(
            statusCode = response.statusCode(),
            body = response.body(),
            digest = response.headers().firstValue("Digest")
                .map { parseDigestHeader(it) }.orElse(null),
            contentLength = response.headers().firstValueAsLong("Content-Length").let {
                if (it.isPresent) it.asLong else null
            },
            finalUri = response.uri(),
        )
    }

    /**
     * Builds a request pre-populated with the supplied [headers] only. For OFAC
     * this map is empty, so no credential/authorization header is ever attached
     * (Req 2.3). A source that needs a token would supply it in this map via its
     * own adapter (Req 13.3).
     */
    private fun baseRequest(url: URI, headers: Map<String, String>): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(url)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder
    }

    private fun parseHttpDate(value: String): Instant? =
        runCatching { Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(value)) }.getOrNull()

    /**
     * Parses an RFC 3230 `Digest: sha-256=<base64>` header value, or a bare hex
     * digest, into a [Sha256Digest], returning `null` when it is not a valid
     * SHA-256. OFAC advertises the digest here; validation compares it to the
     * recomputed hash (Req 3.3).
     */
    private fun parseDigestHeader(value: String): Sha256Digest? {
        val raw = value.substringAfter('=', value).trim()
        // Try hex first (64 hex chars), then base64 of 32 bytes.
        val hex = when {
            raw.length == 64 && raw.all { it.lowercaseChar() in "0123456789abcdef" } -> raw.lowercase()
            else -> runCatching {
                java.util.Base64.getDecoder().decode(raw)
                    .takeIf { it.size == 32 }
                    ?.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
            }.getOrNull()
        }
        return hex?.let { runCatching { Sha256Digest.ofHex(it) }.getOrNull() }
    }
}
