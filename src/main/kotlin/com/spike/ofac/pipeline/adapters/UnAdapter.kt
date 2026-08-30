package com.spike.ofac.pipeline.adapters

import com.spike.ofac.pipeline.stages.transform.ProfileEntryBuilder
import com.spike.ofac.pipeline.stages.transform.RawParsedProfile
import com.spike.ofac.pipeline.stages.transform.RawReferenceTables
import java.net.URI

/**
 * The UN [SourceAdapter] scaffolding (task 20.1).
 *
 * The UN consolidated list follows the same publication pattern as OFAC — a full
 * XML snapshot with no delta — and, like OFAC, is served **anonymously**: the UN
 * download requires **no** authentication token (`spike-ofac.md` §12). This
 * adapter therefore realizes the source-specific half of the pipeline (obtain
 * I/O + field mapping) for UN while the six core stages
 * (`obtain → validate → transform → version → persist → publish`) stay entirely
 * source-independent (Req 13.1): adding UN is a new adapter, not a stage change.
 *
 * Because UN sends no credentials, [head] / [get] hand the injectable
 * [OfacAdapter.Transport] an **empty** header map — the same credential-free
 * policy as OFAC (contrast with [EuAdapter], which attaches a token, Req 13.3).
 * Field mapping ([mapRecord]) and entity classification ([entityTypeOf]) delegate
 * to a shared [SourceAdapterSupport] so the mapping semantics (a required-field
 * failure names the field and record, Req 13.2, 13.4) match across sources.
 *
 * This is scaffolding: it fixes the source seam (no token) and reuses the common
 * mapping support. The concrete UN reference-value set and any UN-specific field
 * quirks are left for the full UN implementation; until then it maps UN profiles
 * through the same normalization the OFAC adapter uses.
 *
 * @property transport the HTTP transport used by [head] / [get]; defaults to the
 *   JDK-backed transport and is injectable so tests can assert the no-credentials
 *   behavior without real I/O.
 * @property support the shared mapping/classification helper (Req 13.2, 13.4).
 */
class UnAdapter(
    private val transport: OfacAdapter.Transport = JdkHttpTransport(),
    private val support: SourceAdapterSupport = SourceAdapterSupport(
        source = "UN",
        entryBuilder = ProfileEntryBuilder(),
        references = RawReferenceTables(),
    ),
) : SourceAdapter {

    /** UN sends no credentials: the credential-free header set is empty (`spike` §12). */
    override fun head(url: URI): HeadResponse = transport.head(url, NO_CREDENTIAL_HEADERS)

    /** UN sends no credentials: the credential-free header set is empty (`spike` §12). */
    override fun get(url: URI): HttpResponse = transport.get(url, NO_CREDENTIAL_HEADERS)

    override fun mapRecord(rawProfile: RawParsedProfile): MappingResult =
        support.mapRecord(rawProfile)

    override fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType =
        support.entityTypeOf(rawProfile)

    companion object {
        /** UN sends no credentials: the credential-free header set is empty. */
        private val NO_CREDENTIAL_HEADERS: Map<String, String> = emptyMap()
    }
}
