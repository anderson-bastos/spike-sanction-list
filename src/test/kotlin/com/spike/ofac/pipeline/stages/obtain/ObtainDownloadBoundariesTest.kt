package com.spike.ofac.pipeline.stages.obtain

import com.spike.ofac.pipeline.adapters.HeadResponse
import com.spike.ofac.pipeline.adapters.HttpResponse
import com.spike.ofac.pipeline.adapters.MappingResult
import com.spike.ofac.pipeline.adapters.SourceAdapter
import com.spike.ofac.pipeline.adapters.SourceEntityType
import com.spike.ofac.pipeline.stages.transform.RawParsedProfile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * Boundary unit tests for [Obtain.download] (task 12.4).
 *
 * These pin down the exact acceptance/rejection edges of the download stage:
 *  - the redirect bound (Req 2.2): [Obtain.MAX_REDIRECTS] (=5) redirects are
 *    accepted, one more (6) is rejected;
 *  - the completeness check (Req 2.4): a body whose length does not match the
 *    advertised `Content-Length` (too short *or* too long) is a truncated /
 *    incomplete download and is rejected, while a matching length — or an absent
 *    `Content-Length`, where completeness cannot be checked — is accepted.
 *
 * A hand-rolled fake [SourceAdapter] returns canned [HttpResponse]s so the logic
 * is exercised without network I/O, mirroring the pattern in `ObtainSmokeTest`.
 */
class ObtainDownloadBoundariesTest {

    private val url = URI.create("https://example.test/sdn_advanced.xml")
    private val body = "<xml/>".toByteArray()

    // --- Req 2.2: redirect boundary ---------------------------------------

    @Test
    fun `exactly 5 redirects is accepted`() {
        val adapter = FakeAdapter(
            HttpResponse(statusCode = 200, body = body, redirectCount = Obtain.MAX_REDIRECTS),
        )

        val result = Obtain.download(adapter, url)

        val snapshot = result.shouldBeInstanceOf<DownloadResult.Snapshot>()
        snapshot.bytes.contentEquals(body) shouldBe true
    }

    @Test
    fun `6 redirects is rejected`() {
        val adapter = FakeAdapter(
            HttpResponse(statusCode = 200, body = body, redirectCount = Obtain.MAX_REDIRECTS + 1),
        )

        Obtain.download(adapter, url).shouldBeInstanceOf<DownloadResult.DownloadFailed>()
    }

    // --- Req 2.4: completeness boundary -----------------------------------

    @Test
    fun `body shorter than Content-Length is a truncated download and rejected`() {
        val adapter = FakeAdapter(
            HttpResponse(statusCode = 200, body = body, contentLength = body.size.toLong() + 1),
        )

        Obtain.download(adapter, url).shouldBeInstanceOf<DownloadResult.DownloadFailed>()
    }

    @Test
    fun `body longer than Content-Length is an incomplete download and rejected`() {
        val adapter = FakeAdapter(
            HttpResponse(statusCode = 200, body = body, contentLength = body.size.toLong() - 1),
        )

        Obtain.download(adapter, url).shouldBeInstanceOf<DownloadResult.DownloadFailed>()
    }

    @Test
    fun `body length matching Content-Length is accepted`() {
        val adapter = FakeAdapter(
            HttpResponse(statusCode = 200, body = body, contentLength = body.size.toLong()),
        )

        val result = Obtain.download(adapter, url)

        val snapshot = result.shouldBeInstanceOf<DownloadResult.Snapshot>()
        snapshot.bytes.contentEquals(body) shouldBe true
        snapshot.contentLength shouldBe body.size.toLong()
    }

    @Test
    fun `absent Content-Length cannot be checked for completeness so any body is accepted`() {
        val adapter = FakeAdapter(
            HttpResponse(statusCode = 200, body = body, contentLength = null),
        )

        val result = Obtain.download(adapter, url)

        val snapshot = result.shouldBeInstanceOf<DownloadResult.Snapshot>()
        snapshot.bytes.contentEquals(body) shouldBe true
        snapshot.contentLength shouldBe null
    }

    // --- helpers -----------------------------------------------------------

    /**
     * A minimal in-memory [SourceAdapter] returning a canned GET [response]; the
     * HEAD and mapping/classification methods are unused by [Obtain.download] and
     * throw if called.
     */
    private class FakeAdapter(private val response: HttpResponse) : SourceAdapter {
        override fun head(url: URI): HeadResponse = error("head is not used by download")

        override fun get(url: URI): HttpResponse = response

        override fun mapRecord(rawProfile: RawParsedProfile): MappingResult =
            error("mapRecord is not used by Obtain")

        override fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType =
            error("entityTypeOf is not used by Obtain")
    }
}
