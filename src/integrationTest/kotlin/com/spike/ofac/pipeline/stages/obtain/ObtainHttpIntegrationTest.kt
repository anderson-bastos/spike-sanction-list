package com.spike.ofac.pipeline.stages.obtain

import com.spike.ofac.pipeline.adapters.JdkHttpTransport
import com.spike.ofac.pipeline.adapters.OfacAdapter
import com.spike.ofac.pipeline.models.EntityType
import com.spike.ofac.pipeline.models.FixedRef
import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.models.SourceList
import com.spike.ofac.pipeline.models.VersionId
import com.spike.ofac.pipeline.store.InMemoryVersionStore
import com.spike.ofac.pipeline.store.PointerKind
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.http.HttpClient
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.system.measureTimeMillis

/**
 * Task 18.3 — obtain HEAD / GET integration tests against a MockWebServer-served
 * fixture, over **real HTTPS** with the **real** [OfacAdapter] + [JdkHttpTransport]
 * driving the **real** [Obtain] stage (no mocking of the component under test).
 *
 * This is the first test that exercises the obtain stage end to end over an actual
 * TLS socket rather than an in-memory transport fake. It proves three things
 * against a live server:
 *
 *  1. **HEAD headers are read** (Req 1.2). MockWebServer answers a HEAD with
 *     `Last-Modified` and an RFC-3230 `Digest: sha-256=<base64>` header;
 *     [Obtain.checkChange] surfaces both — the parsed `Last-Modified` instant and
 *     the advertised digest drive the `NO_CHANGE` / `CHANGED` decision against the
 *     last-ingested version.
 *  2. **HTTPS GET with timeouts** (Req 2.1). [Obtain.download] GETs the full
 *     snapshot over `https://`, verifies completeness against `Content-Length`, and
 *     returns the exact bytes; and a server that never responds trips the GET
 *     timeout, which the stage maps to `DOWNLOAD_FAILED` leaving nothing accepted.
 *  3. **New CURRENT resolvable within 5s** (Req 9.5). After a successful obtain the
 *     downloaded version is persisted + activated in a real [InMemoryVersionStore],
 *     and the freshly-activated CURRENT resolves to exactly that version well
 *     within the 5-second atomic-activation SLA.
 *
 * ## TLS
 * MockWebServer is served over HTTPS using a self-signed [HeldCertificate] bound to
 * the loopback host; the JDK [HttpClient] the transport uses is built with an
 * [SSLContext] that trusts exactly that certificate (and SNI/endpoint verification
 * against `localhost`), so the handshake is real but self-contained — no external
 * network, no disabled verification.
 */
class ObtainHttpIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var clientCertificates: HandshakeCertificates

    /** The adapter under test, wired to a client that trusts the server's cert. */
    private lateinit var adapter: OfacAdapter

    /** Same, but with a short GET timeout so the timeout path is fast to exercise. */
    private lateinit var shortTimeoutAdapter: OfacAdapter

    @BeforeEach
    fun startServer() {
        // A self-signed cert for the loopback host the client will trust.
        val localhost = InetAddress.getByName("localhost").canonicalHostName
        val serverCertificate = HeldCertificate.Builder()
            .addSubjectAlternativeName(localhost)
            .commonName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .build()
        clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCertificate.certificate)
            .build()

        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()

        adapter = OfacAdapter(transport = JdkHttpTransport(client = trustingClient()))
        shortTimeoutAdapter = OfacAdapter(
            transport = JdkHttpTransport(
                client = trustingClient(),
                getTimeout = Duration.ofMillis(500),
                headTimeout = Duration.ofMillis(500),
            ),
        )
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    // --- (1) HEAD headers are read (Req 1.2) --------------------------------

    @Test
    fun `checkChange reads Last-Modified and Digest from the HEAD response over HTTPS`() {
        val advertisedDigest = sha256Hex(SNAPSHOT_BYTES)
        val lastModified = "Wed, 20 Aug 2025 12:34:56 GMT"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Last-Modified", lastModified)
                .setHeader("Digest", "sha-256=${base64OfHex(advertisedDigest)}"),
        )

        // No prior version: any advertised publication is a change, and the decision
        // must carry the headers the HEAD reported (Req 1.2).
        val decision = Obtain.checkChange(adapter, server.url("/sdn.xml").toUri(), lastIngested = null)

        val changed = decision.shouldBeInstanceOf<ChangeDecision.Changed>()
        changed.advertisedDigest shouldBe Sha256Digest.ofHex(advertisedDigest)
        changed.lastModified shouldBe Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(lastModified))

        // The stage really issued a HEAD over TLS.
        val recorded: RecordedRequest = server.takeRequest()
        recorded.method shouldBe "HEAD"
        recorded.requestUrl!!.scheme shouldBe "https"
    }

    @Test
    fun `checkChange reports NO_CHANGE when the advertised Digest equals the last-ingested digest`() {
        val digestHex = sha256Hex(SNAPSHOT_BYTES)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Digest", "sha-256=${base64OfHex(digestHex)}"),
        )

        val lastIngested = versionMetadata(Sha256Digest.ofHex(digestHex))
        val decision = Obtain.checkChange(adapter, server.url("/sdn.xml").toUri(), lastIngested = lastIngested)

        decision shouldBe ChangeDecision.NoChange
    }

    // --- (2) HTTPS GET with timeouts (Req 2.1) ------------------------------

    @Test
    fun `download GETs the full snapshot over HTTPS and verifies completeness`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", SNAPSHOT_BYTES.size.toString())
                .setBody(Buffer().write(SNAPSHOT_BYTES)),
        )

        val result = Obtain.download(adapter, server.url("/sdn.xml").toUri())

        val snapshot = result.shouldBeInstanceOf<DownloadResult.Snapshot>()
        snapshot.bytes.contentEquals(SNAPSHOT_BYTES) shouldBe true
        snapshot.contentLength shouldBe SNAPSHOT_BYTES.size.toLong()

        val recorded = server.takeRequest()
        recorded.method shouldBe "GET"
        recorded.requestUrl!!.scheme shouldBe "https"
    }

    @Test
    fun `download maps a GET timeout to DOWNLOAD_FAILED leaving nothing accepted`() {
        // The server accepts the connection but never sends a response, so the GET
        // exceeds its (here shortened) timeout — the same failure mode as blowing the
        // 120s production bound (Req 2.1, 2.5).
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = Obtain.download(shortTimeoutAdapter, server.url("/sdn.xml").toUri())

        val failed = result.shouldBeInstanceOf<DownloadResult.DownloadFailed>()
        failed.cause.lowercase() shouldContain "timed out"
    }

    // --- (3) New CURRENT resolvable within 5s (Req 9.5) ---------------------

    @Test
    fun `a snapshot obtained over HTTPS is persisted, activated, and CURRENT resolves within 5s`() {
        val digestHex = sha256Hex(SNAPSHOT_BYTES)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", SNAPSHOT_BYTES.size.toString())
                .setHeader("Digest", "sha-256=${base64OfHex(digestHex)}")
                .setBody(Buffer().write(SNAPSHOT_BYTES)),
        )

        // Obtain the snapshot over real HTTPS through the real stage.
        val snapshot = Obtain.download(adapter, server.url("/sdn.xml").toUri())
            .shouldBeInstanceOf<DownloadResult.Snapshot>()

        // Its recomputed digest is the version identity the pipeline would persist under.
        val recomputed = Sha256Digest.ofHex(sha256Hex(snapshot.bytes))
        recomputed shouldBe Sha256Digest.ofHex(digestHex)
        val versionId = VersionId(LocalDate.of(2025, 8, 20), recomputed)

        val store = InMemoryVersionStore()

        // Persist the isolated version, then time the atomic activation + CURRENT read.
        store.putIsolatedFor(SourceList.SDN, versionId, listOf(entry("1")))
        val elapsedMs = measureTimeMillis {
            store.atomicSetCurrent(SourceList.SDN, versionId) shouldBe true
            store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe versionId
        }

        // The freshly-activated CURRENT resolves well within the 5s SLA (Req 9.5).
        elapsedMs shouldBeLessThan 5_000L
    }

    // --- helpers ------------------------------------------------------------

    /**
     * A JDK [HttpClient] that trusts exactly the MockWebServer self-signed cert and
     * verifies the endpoint against `localhost`, so the TLS handshake is real and
     * self-contained (no disabled verification, no external trust).
     */
    private fun trustingClient(): HttpClient {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            null,
            arrayOf(clientCertificates.trustManager),
            null,
        )
        val sslParameters = SSLParameters().apply { endpointIdentificationAlgorithm = "HTTPS" }
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .sslContext(sslContext)
            .sslParameters(sslParameters)
            .build()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** Encode a hex digest as the base64 the RFC-3230 `Digest: sha-256=` header carries. */
    private fun base64OfHex(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }

    private fun versionMetadata(digest: Sha256Digest) =
        com.spike.ofac.pipeline.models.VersionMetadata(
            versionId = VersionId(LocalDate.of(2025, 8, 20), digest),
            sourceList = SourceList.SDN,
            recordCount = 1,
            outOfScopeCount = 0,
            overlapCount = 0,
            expectedCount = 1,
            persistedCount = 1,
            state = com.spike.ofac.pipeline.models.VersionState.HOT,
            ingestedAt = Instant.now(),
        )

    private fun entry(ref: String) = InternalModelEntry(
        fixedRef = FixedRef(ref),
        entityType = EntityType.Individual,
        primaryName = "Name $ref",
        sanctionPrograms = listOf("PROGRAM"),
    )

    private companion object {
        /**
         * A small, well-formed snapshot body. The obtain stage is source- and
         * format-agnostic — it moves bytes and reads headers — so an inline body is
         * sufficient here; XML well-formedness is the `validate` stage's concern.
         */
        private val SNAPSHOT_BYTES: ByteArray =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <Sanctions><Publish_Date>2025-08-20</Publish_Date><Record_Count>1</Record_Count>
            <DistinctParty FixedRef="1"/></Sanctions>
            """.trimIndent().toByteArray(Charsets.UTF_8)
    }
}
