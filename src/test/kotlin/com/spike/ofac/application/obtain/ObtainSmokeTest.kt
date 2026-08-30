package com.spike.ofac.application.obtain

import com.spike.ofac.application.port.out.HeadResponse
import com.spike.ofac.application.port.out.HttpResponse
import com.spike.ofac.application.port.out.MappingResult
import com.spike.ofac.application.port.out.SourceAdapter
import com.spike.ofac.application.port.out.SourceEntityType
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.model.VersionMetadata
import com.spike.ofac.domain.model.VersionState
import com.spike.ofac.domain.transform.RawParsedProfile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import java.time.LocalDate

/**
 * Compile-and-behavior smoke test for the `obtain` stage (tasks 12.1, 12.2).
 *
 * Uses a hand-rolled fake [SourceAdapter] rather than the real HTTP transport so
 * the change-detection and download logic can be exercised deterministically
 * without network I/O. This is only a smoke test that the two functions compile
 * and behave on the core paths — the exhaustive property test (12.3) and the
 * boundary unit tests (12.4) are separate tasks.
 */
class ObtainSmokeTest {

    private val url = URI.create("https://example.test/sdn_advanced.xml")

    private val digestA = Sha256Digest.ofHex("a".repeat(64))
    private val digestB = Sha256Digest.ofHex("b".repeat(64))

    // --- checkChange -------------------------------------------------------

    @Test
    fun `equal digest yields NO_CHANGE`() {
        val adapter = FakeAdapter(head = HeadResponse(statusCode = 200, digest = digestA))

        val decision = Obtain.checkChange(adapter, url, lastIngested(digestA))

        decision shouldBe ChangeDecision.NoChange
    }

    @Test
    fun `different digest yields CHANGED`() {
        val adapter = FakeAdapter(head = HeadResponse(statusCode = 200, digest = digestB))

        val decision = Obtain.checkChange(adapter, url, lastIngested(digestA))

        decision.shouldBeInstanceOf<ChangeDecision.Changed>()
    }

    @Test
    fun `no prior version yields CHANGED`() {
        val adapter = FakeAdapter(head = HeadResponse(statusCode = 200, digest = digestA))

        val decision = Obtain.checkChange(adapter, url, lastIngested = null)

        decision.shouldBeInstanceOf<ChangeDecision.Changed>()
    }

    @Test
    fun `absent digest falls back to Publish_Date plus Record_Count`() {
        val adapter = FakeAdapter(head = HeadResponse(statusCode = 200, digest = null))
        val last = lastIngested(digestA, publishDate = LocalDate.of(2024, 1, 1), recordCount = 100)

        // Same publish date + record count -> NO_CHANGE via fallback.
        Obtain.checkChange(
            adapter, url, last,
            advertisedPublishDate = LocalDate.of(2024, 1, 1),
            advertisedRecordCount = 100,
        ) shouldBe ChangeDecision.NoChange

        // Different record count -> CHANGED via fallback.
        Obtain.checkChange(
            adapter, url, last,
            advertisedPublishDate = LocalDate.of(2024, 1, 1),
            advertisedRecordCount = 101,
        ).shouldBeInstanceOf<ChangeDecision.Changed>()
    }

    @Test
    fun `HEAD exception yields HEAD_FAILED`() {
        val adapter = FakeAdapter(headThrows = RuntimeException("connect timeout"))

        val decision = Obtain.checkChange(adapter, url, lastIngested(digestA))

        decision.shouldBeInstanceOf<ChangeDecision.HeadFailed>()
    }

    @Test
    fun `non-2xx HEAD yields HEAD_FAILED`() {
        val adapter = FakeAdapter(head = HeadResponse(statusCode = 503))

        val decision = Obtain.checkChange(adapter, url, lastIngested(digestA))

        decision.shouldBeInstanceOf<ChangeDecision.HeadFailed>()
    }

    // --- download ----------------------------------------------------------

    @Test
    fun `complete download yields SNAPSHOT`() {
        val body = "<xml/>".toByteArray()
        val adapter = FakeAdapter(
            get = HttpResponse(statusCode = 200, body = body, contentLength = body.size.toLong()),
        )

        val result = Obtain.download(adapter, url)

        val snapshot = result.shouldBeInstanceOf<DownloadResult.Snapshot>()
        snapshot.bytes.contentEquals(body) shouldBe true
    }

    @Test
    fun `content-length mismatch yields DOWNLOAD_FAILED`() {
        val body = "<xml/>".toByteArray()
        val adapter = FakeAdapter(
            get = HttpResponse(statusCode = 200, body = body, contentLength = body.size.toLong() + 5),
        )

        Obtain.download(adapter, url).shouldBeInstanceOf<DownloadResult.DownloadFailed>()
    }

    @Test
    fun `six redirects yields DOWNLOAD_FAILED`() {
        val body = "<xml/>".toByteArray()
        val adapter = FakeAdapter(
            get = HttpResponse(statusCode = 200, body = body, redirectCount = 6),
        )

        Obtain.download(adapter, url).shouldBeInstanceOf<DownloadResult.DownloadFailed>()
    }

    @Test
    fun `non-2xx GET yields DOWNLOAD_FAILED`() {
        val adapter = FakeAdapter(get = HttpResponse(statusCode = 404, body = ByteArray(0)))

        Obtain.download(adapter, url).shouldBeInstanceOf<DownloadResult.DownloadFailed>()
    }

    @Test
    fun `GET exception yields DOWNLOAD_FAILED`() {
        val adapter = FakeAdapter(getThrows = RuntimeException("read timeout"))

        Obtain.download(adapter, url).shouldBeInstanceOf<DownloadResult.DownloadFailed>()
    }

    // --- helpers -----------------------------------------------------------

    private fun lastIngested(
        digest: Sha256Digest,
        publishDate: LocalDate = LocalDate.of(2024, 1, 1),
        recordCount: Int = 100,
    ): VersionMetadata =
        VersionMetadata(
            versionId = VersionId(publishDate, digest),
            sourceList = SourceList.SDN,
            recordCount = recordCount,
            outOfScopeCount = 0,
            overlapCount = 0,
            expectedCount = recordCount,
            persistedCount = recordCount,
            state = VersionState.HOT,
            ingestedAt = Instant.EPOCH,
        )

    /**
     * A minimal in-memory [SourceAdapter] that returns canned HEAD/GET responses
     * (or throws) so the obtain logic is exercised without network I/O. The
     * mapping/classification methods are unused by [Obtain] and throw if called.
     */
    private class FakeAdapter(
        private val head: HeadResponse? = null,
        private val get: HttpResponse? = null,
        private val headThrows: Exception? = null,
        private val getThrows: Exception? = null,
    ) : SourceAdapter {
        override fun head(url: URI): HeadResponse {
            headThrows?.let { throw it }
            return head ?: error("no HEAD response configured")
        }

        override fun get(url: URI): HttpResponse {
            getThrows?.let { throw it }
            return get ?: error("no GET response configured")
        }

        override fun mapRecord(rawProfile: RawParsedProfile): MappingResult =
            error("mapRecord is not used by Obtain")

        override fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType =
            error("entityTypeOf is not used by Obtain")
    }
}
