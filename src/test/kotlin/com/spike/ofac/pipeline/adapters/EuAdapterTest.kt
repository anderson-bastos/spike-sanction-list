package com.spike.ofac.pipeline.adapters

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Unit tests for [EuAdapter] token-based obtain behavior (task 20.2).
 *
 * The EU list is the one source that authenticates its download, so two
 * adapter guarantees are pinned here with focused example tests:
 *
 *  - **Req 13.3 — EU attaches a token.** [EuAdapter.head] / [EuAdapter.get] must
 *    hand the [OfacAdapter.Transport] a single `Authorization: Bearer <token>`
 *    header carrying the token from the injected provider. A mocked transport
 *    captures the exact header map it was called with so the assertion is on the
 *    real value the adapter passes, not on a stub's shape (contrast with the
 *    OFAC / UN adapters, which pass an empty credential-free map).
 *  - **Req 13.5 — a missing/blank token aborts obtain, retaining the last good
 *    version.** When the provider yields `null` or a blank token, [head] / [get]
 *    throw [MissingAuthTokenException] **before** any request reaches the
 *    transport (the source-independent obtain stage treats a thrown adapter
 *    exception as a failed download and leaves `CURRENT` untouched). The
 *    exception names the source (`EU`) so the resulting error is actionable, and
 *    the transport is never invoked.
 *
 * The transport is faked with MockK so the token policy is exercised without
 * real network I/O, exactly as [OfacAdapterTest] does for the no-credentials
 * case.
 */
class EuAdapterTest {

    private val url = URI.create("https://example.test/eu_consolidated.xml")

    // ------------------------------------------------------------------
    // Req 13.3 — EU attaches an Authorization: Bearer <token> header
    // ------------------------------------------------------------------

    /**
     * Req 13.3: a HEAD change-check must carry the EU token as a single
     * `Authorization: Bearer <token>` header. The adapter is given a mocked
     * transport that captures the header map it receives; the captured map must
     * contain exactly that bearer header with the provider's token.
     */
    @Test
    fun `head attaches an Authorization Bearer token header`() {
        val transport = mockk<OfacAdapter.Transport>()
        val capturedHeaders = slot<Map<String, String>>()
        every { transport.head(any(), capture(capturedHeaders)) } returns
            HeadResponse(statusCode = 200)

        val adapter = EuAdapter(tokenProvider = { "eu-secret-token" }, transport = transport)
        adapter.head(url)

        capturedHeaders.captured shouldBe mapOf("Authorization" to "Bearer eu-secret-token")
        verify(exactly = 1) { transport.head(any(), any()) }
    }

    /**
     * Req 13.3: a GET download must likewise carry the EU token. Same capture
     * strategy, asserting the header map the adapter passes to `get` is the
     * single bearer header with the provider's token.
     */
    @Test
    fun `get attaches an Authorization Bearer token header`() {
        val transport = mockk<OfacAdapter.Transport>()
        val capturedHeaders = slot<Map<String, String>>()
        every { transport.get(any(), capture(capturedHeaders)) } returns
            HttpResponse(statusCode = 200, body = ByteArray(0))

        val adapter = EuAdapter(tokenProvider = { "eu-secret-token" }, transport = transport)
        adapter.get(url)

        capturedHeaders.captured shouldBe mapOf("Authorization" to "Bearer eu-secret-token")
        verify(exactly = 1) { transport.get(any(), any()) }
    }

    /**
     * Req 13.3: the token is read from the provider **per request**, so a
     * rotated token is picked up on the next call rather than captured once at
     * construction.
     */
    @Test
    fun `get re-reads the token from the provider on each request`() {
        val transport = mockk<OfacAdapter.Transport>()
        val capturedHeaders = mutableListOf<Map<String, String>>()
        every { transport.get(any(), capture(capturedHeaders)) } returns
            HttpResponse(statusCode = 200, body = ByteArray(0))

        val tokens = ArrayDeque(listOf("token-1", "token-2"))
        val adapter = EuAdapter(tokenProvider = { tokens.removeFirst() }, transport = transport)

        adapter.get(url)
        adapter.get(url)

        capturedHeaders shouldBe listOf(
            mapOf("Authorization" to "Bearer token-1"),
            mapOf("Authorization" to "Bearer token-2"),
        )
    }

    // ------------------------------------------------------------------
    // Req 13.5 — missing/blank token aborts obtain, retaining last good version
    // ------------------------------------------------------------------

    /**
     * Req 13.5: a `null` token aborts the obtain I/O. [get] throws
     * [MissingAuthTokenException] naming the source (`EU`), and the transport is
     * never invoked — the source-independent obtain stage sees a failed download
     * and leaves the last successfully persisted version (`CURRENT`) untouched.
     */
    @Test
    fun `get with a null token throws MissingAuthTokenException and never calls the transport`() {
        val transport = mockk<OfacAdapter.Transport>()

        val adapter = EuAdapter(tokenProvider = { null }, transport = transport)

        val ex = shouldThrow<MissingAuthTokenException> { adapter.get(url) }
        ex.source shouldBe EuAdapter.SOURCE
        ex.message shouldContain "EU"
        // The abort happens before any request goes out (Req 13.5).
        verify(exactly = 0) { transport.get(any(), any()) }
        verify(exactly = 0) { transport.head(any(), any()) }
    }

    /**
     * Req 13.5: a **blank** token is treated the same as a missing one — a blank
     * string is not a usable credential — so [head] aborts with
     * [MissingAuthTokenException] and never issues an unauthenticated request.
     */
    @Test
    fun `head with a blank token throws MissingAuthTokenException and never calls the transport`() {
        val transport = mockk<OfacAdapter.Transport>()

        val adapter = EuAdapter(tokenProvider = { "   " }, transport = transport)

        val ex = shouldThrow<MissingAuthTokenException> { adapter.head(url) }
        ex.source shouldBe EuAdapter.SOURCE
        ex.message shouldContain "EU"
        verify(exactly = 0) { transport.head(any(), any()) }
        verify(exactly = 0) { transport.get(any(), any()) }
    }
}
