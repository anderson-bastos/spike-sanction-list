package com.spike.ofac.adapter.out.source

import com.spike.ofac.application.port.out.HeadResponse
import com.spike.ofac.application.port.out.HttpResponse
import com.spike.ofac.application.port.out.MappingResult
import com.spike.ofac.application.port.out.SourceAdapter
import com.spike.ofac.application.port.out.SourceEntityType
import com.spike.ofac.domain.transform.ProfileEntryBuilder
import com.spike.ofac.domain.transform.RawParsedProfile
import com.spike.ofac.domain.transform.RawReferenceTables
import java.net.URI

/**
 * The EU [SourceAdapter] scaffolding (task 20.1).
 *
 * The EU consolidated list publishes a full XML snapshot with no delta — the
 * same shape as OFAC and UN — but, unlike them, the EU **download requires an
 * authentication token** (`spike-ofac.md` §12). This adapter is the one place
 * that token lives: it supplies the token on [head] / [get] by attaching an
 * `Authorization` bearer header (Req 13.3), so the six core stages
 * (`obtain → validate → transform → version → persist → publish`) never learn
 * that EU is token-based (Req 13.1). Field mapping and entity classification are
 * shared with the other sources via [SourceAdapterSupport] (Req 13.2, 13.4).
 *
 * ## Missing / invalid token (Req 13.5)
 * A **missing** token (null/blank) is caught **before** any request goes out:
 * [head] / [get] throw [MissingAuthTokenException] instead of issuing an
 * unauthenticated request. The obtain stage, which is source-independent, already
 * treats a thrown exception from the adapter as a failed download — it discards
 * the (never-started) download and leaves `CURRENT` untouched, i.e. the last
 * successfully persisted version is retained (Req 13.5). The exception message
 * names the source (`EU`) and the authentication failure so the error is
 * actionable. An **invalid** token (server rejects with `401`/`403`) needs no
 * special handling here: the adapter attaches the token and the server's non-2xx
 * response flows through the same source-independent failed-download path, again
 * retaining the last good version (Req 2.5, 13.5).
 *
 * This is scaffolding: it fixes the source seam (token-based auth + the
 * missing-token abort) and reuses the common mapping support. The concrete EU
 * reference-value set and any EU-specific field quirks are left for the full EU
 * implementation.
 *
 * @property tokenProvider supplies the current EU auth token, or `null` when none
 *   is configured. It is a function (not a constant) so a rotated/expired token
 *   is re-read per request; a `null`/blank result aborts obtain (Req 13.5).
 * @property transport the HTTP transport used by [head] / [get]; injectable so
 *   tests can assert the token is attached and that a missing token never reaches
 *   the transport.
 * @property support the shared mapping/classification helper (Req 13.2, 13.4).
 */
class EuAdapter(
    private val tokenProvider: () -> String?,
    private val transport: OfacAdapter.Transport = JdkHttpTransport(),
    private val support: SourceAdapterSupport = SourceAdapterSupport(
        source = SOURCE,
        entryBuilder = ProfileEntryBuilder(),
        references = RawReferenceTables(),
    ),
) : SourceAdapter {

    /**
     * Issues a HEAD with the EU token attached (Req 13.3), or aborts with a
     * [MissingAuthTokenException] when no token is configured (Req 13.5).
     */
    override fun head(url: URI): HeadResponse = transport.head(url, authHeaders())

    /**
     * Issues a GET with the EU token attached (Req 13.3), or aborts with a
     * [MissingAuthTokenException] when no token is configured (Req 13.5).
     */
    override fun get(url: URI): HttpResponse = transport.get(url, authHeaders())

    override fun mapRecord(rawProfile: RawParsedProfile): MappingResult =
        support.mapRecord(rawProfile)

    override fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType =
        support.entityTypeOf(rawProfile)

    /**
     * Builds the credential headers for an EU request: a single `Authorization:
     * Bearer <token>` header (Req 13.3). A missing (null/blank) token aborts here
     * — no request is issued — so obtain retains the last good version (Req 13.5).
     */
    private fun authHeaders(): Map<String, String> {
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw MissingAuthTokenException(SOURCE)
        return mapOf("Authorization" to "Bearer $token")
    }

    companion object {
        /** The source name recorded in the auth-failure error (Req 13.5). */
        const val SOURCE: String = "EU"
    }
}

/**
 * Signals that a token-based source (e.g. EU) had **no** authentication token to
 * attach, so its obtain stage must abort while the last good version is retained
 * (Req 13.5).
 *
 * The obtain stage is source-independent: it catches any exception thrown by the
 * adapter's `get`/`head` and reports a failed download, discarding the
 * (never-issued) download and leaving `CURRENT` untouched. This exception's
 * [message] names the [source] and the authentication failure so the resulting
 * error indication is actionable (Req 13.5).
 *
 * @property source the source name whose token was missing (e.g. `"EU"`).
 */
class MissingAuthTokenException(val source: String) :
    RuntimeException("[$source] authentication token is missing; aborting obtain and retaining the last good version")
