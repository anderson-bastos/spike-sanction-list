package com.spike.ofac.pipeline.adapters

import com.spike.ofac.pipeline.stages.transform.RawAlias
import com.spike.ofac.pipeline.stages.transform.RawParsedProfile
import com.spike.ofac.pipeline.stages.transform.RawReferenceTables
import com.spike.ofac.pipeline.stages.transform.RawSanctionsEntry
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Unit tests for [OfacAdapter] behavior (task 11.2).
 *
 * Two adapter guarantees are pinned here with focused example tests:
 *
 *  - **Req 2.3 — OFAC sends no credentials.** [OfacAdapter.head] / [OfacAdapter.get]
 *    must hand the [OfacAdapter.Transport] an **empty** header map: no
 *    `Authorization` header, no credential header of any kind. A mocked
 *    transport captures the exact headers it was called with so the assertion is
 *    on the real value the adapter passes, not on a stub's shape.
 *  - **Req 13.4 — a required-field mapping failure names the source + field.**
 *    [OfacAdapter.mapRecord] must return a [MappingResult.MappingError] that
 *    names the offending field and identifies the record (its FixedRef) when a
 *    required field cannot be mapped, and a [MappingResult.Success] for a
 *    well-formed in-scope profile that carries a name and a resolvable sanction
 *    program.
 *
 * The mapping tests resolve the profile's ID references against a
 * [RawReferenceTables] handed to the adapter (the same tables the transform
 * stage gathers in one streaming pass); the program a profile "has" is carried
 * by a linked [RawSanctionsEntry], exactly as in the running pipeline.
 */
class OfacAdapterTest {

    // ------------------------------------------------------------------
    // Req 2.3 — no credentials on HEAD / GET
    // ------------------------------------------------------------------

    /**
     * Req 2.3: a HEAD change-check must carry no credentials. The adapter is
     * given a mocked transport that captures the header map it receives; the
     * captured map must be empty (no Authorization / credential header).
     */
    @Test
    fun `head sends no credential headers`() {
        val transport = mockk<OfacAdapter.Transport>()
        val capturedHeaders = slot<Map<String, String>>()
        every { transport.head(any(), capture(capturedHeaders)) } returns
            HeadResponse(statusCode = 200)

        val adapter = OfacAdapter(transport = transport)
        adapter.head(URI.create("https://example.test/sdn_advanced.xml"))

        capturedHeaders.captured.shouldBeEmpty()
        // Belt and braces: no auth/credential header under any common name.
        assertNoCredentialHeader(capturedHeaders.captured)
        verify(exactly = 1) { transport.head(any(), any()) }
    }

    /**
     * Req 2.3: a GET download must likewise carry no credentials. Same capture
     * strategy, asserting the header map the adapter passes to `get` is empty.
     */
    @Test
    fun `get sends no credential headers`() {
        val transport = mockk<OfacAdapter.Transport>()
        val capturedHeaders = slot<Map<String, String>>()
        every { transport.get(any(), capture(capturedHeaders)) } returns
            HttpResponse(statusCode = 200, body = ByteArray(0))

        val adapter = OfacAdapter(transport = transport)
        adapter.get(URI.create("https://example.test/sdn_advanced.xml"))

        capturedHeaders.captured.shouldBeEmpty()
        assertNoCredentialHeader(capturedHeaders.captured)
        verify(exactly = 1) { transport.get(any(), any()) }
    }

    // ------------------------------------------------------------------
    // Req 13.4 — required-field mapping failure names source + field
    // ------------------------------------------------------------------

    /**
     * Req 13.4: an in-scope profile with **zero aliases** has no name, so the
     * required `primary_name` field cannot be mapped. The adapter must return a
     * [MappingResult.MappingError] that names the field (`primary_name`) and
     * identifies the source record by its FixedRef so the failure is actionable.
     */
    @Test
    fun `mapRecord reports a named required-field failure with the record identity`() {
        val adapter = OfacAdapter(transport = mockk(relaxed = true))

        // In-scope Individual (PartySubTypeID "4") but with no aliases at all:
        // there is no name to set as primary_name (Req 4.5), a required field.
        val profile = RawParsedProfile(
            fixedRef = "OFAC-12345",
            profileId = "P-1",
            identityId = "ID-1",
            partySubTypeId = "4",
            aliases = emptyList(),
            features = emptyList(),
        )

        val result = adapter.mapRecord(profile)

        val error = result.shouldBeInstanceOf<MappingResult.MappingError>()
        error.field shouldBe "primary_name"
        // The record identity accompanies the failure (source record, Req 13.4).
        error.fixedRef shouldBe "OFAC-12345"
        // The detail names the record so the error is actionable end to end.
        error.detail.shouldNotBeNullContaining("OFAC-12345")
    }

    /**
     * Req 13.4 (companion): an out-of-scope entity type is itself an unmappable
     * required field — the common model's `entity_type` is `Individual|Entity`
     * only. A Vessel (PartySubTypeID "1") must fail on the `entity_type` field.
     */
    @Test
    fun `mapRecord rejects an out-of-scope entity type on the entity_type field`() {
        val adapter = OfacAdapter(transport = mockk(relaxed = true))

        val vessel = RawParsedProfile(
            fixedRef = "OFAC-VESSEL-1",
            profileId = "P-9",
            identityId = "ID-9",
            partySubTypeId = "1", // Vessel — out of scope
            aliases = listOf(RawAlias(aliasTypeId = null, primary = true, fullName = "Ship")),
            features = emptyList(),
        )

        val error = adapter.mapRecord(vessel).shouldBeInstanceOf<MappingResult.MappingError>()
        error.field shouldBe "entity_type"
        error.fixedRef shouldBe "OFAC-VESSEL-1"
    }

    /**
     * The mapping success path: a well-formed **in-scope** profile with a name
     * and at least one resolvable sanction program maps cleanly to a
     * [MappingResult.Success]. The program is carried by a [RawSanctionsEntry]
     * linked to the profile by `profileId`, resolved via the reference tables the
     * adapter maps against (as the transform stage supplies them).
     */
    @Test
    fun `mapRecord maps a well-formed in-scope profile with a name and program to Success`() {
        val profile = RawParsedProfile(
            fixedRef = "OFAC-777",
            profileId = "P-7",
            identityId = "ID-7",
            partySubTypeId = "4", // Individual — in scope
            aliases = listOf(
                RawAlias(aliasTypeId = null, primary = true, fullName = "Jane Doe"),
            ),
            features = emptyList(),
        )
        // The profile "has a program" via a linked SanctionsEntry (Req 4.4).
        val references = RawReferenceTables(
            sanctionsEntries = listOf(
                RawSanctionsEntry(
                    id = "SE-1",
                    profileId = "P-7",
                    programNames = listOf("SDGT"),
                ),
            ),
        )
        val adapter = OfacAdapter(transport = mockk(relaxed = true), references = references)

        val success = adapter.mapRecord(profile).shouldBeInstanceOf<MappingResult.Success>()
        success.entry.fixedRef.value shouldBe "OFAC-777"
        success.entry.primaryName shouldBe "Jane Doe"
        success.entry.sanctionPrograms shouldBe listOf("SDGT")
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Fails if any header key looks like an authentication/credential header. */
    private fun assertNoCredentialHeader(headers: Map<String, String>) {
        val credentialish = headers.keys.filter { key ->
            val lower = key.lowercase()
            lower == "authorization" ||
                lower == "proxy-authorization" ||
                lower == "cookie" ||
                lower.contains("token") ||
                lower.contains("api-key") ||
                lower.contains("apikey")
        }
        credentialish shouldBe emptyList()
    }

    private fun String?.shouldNotBeNullContaining(substring: String) {
        (this ?: error("expected a non-null detail")).shouldContain(substring)
    }
}
