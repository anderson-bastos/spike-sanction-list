package com.spike.ofac.adapter.out.source

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Regression tests for [JdkHttpTransport]'s `Digest` header parsing (via the real
 * transport against a [MockWebServer], no production code touched by mocks).
 *
 * The live OFAC Sanctions List Service advertises the snapshot digest on the HEAD
 * as `Digest: sha-256<hex>` — the RFC-3230 algorithm token glued **directly** to
 * a lowercase hex digest, with **no `=` separator** and **hex, not base64**. An
 * earlier parser only understood the RFC form `sha-256=<base64>` and returned
 * `null` for OFAC's form, which made the whole pipeline reject every real import
 * with `ABSENT_DIGEST`. These tests pin the accepted forms so that regression
 * cannot return.
 */
class JdkHttpTransportDigestHeaderTest {

    private lateinit var server: MockWebServer
    private val transport = JdkHttpTransport()

    private val hex = "ec9b2e0c48f5bdac307b9c09eabe9afc651f8505688fe0ce35c8fb17f96ce43c"

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    /**
     * The exact live-OFAC form: `sha-256` glued to a hex digest with no separator.
     * This is the case the old parser failed on.
     */
    @Test
    fun `parses the live OFAC Digest form sha-256 glued to hex with no separator`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Digest", "sha-256$hex"))

        val head = transport.head(server.url("/SDN_ADVANCED.XML").toUri(), emptyMap())

        head.digest.shouldNotBeNull().value shouldBe hex
    }

    /** The RFC 3230 form `sha-256=<base64>` must still parse (no regression). */
    @Test
    fun `parses the RFC 3230 sha-256 equals base64 form`() {
        val bytes = ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val base64 = Base64.getEncoder().encodeToString(bytes)
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Digest", "sha-256=$base64"))

        val head = transport.head(server.url("/SDN_ADVANCED.XML").toUri(), emptyMap())

        head.digest.shouldNotBeNull().value shouldBe hex
    }

    /** A bare hex digest (no algorithm token at all) must still parse. */
    @Test
    fun `parses a bare hex digest with no algorithm token`() {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Digest", hex))

        val head = transport.head(server.url("/SDN_ADVANCED.XML").toUri(), emptyMap())

        head.digest.shouldNotBeNull().value shouldBe hex
    }

    /** No `Digest` header at all yields a null digest (the source advertised none). */
    @Test
    fun `absent Digest header yields a null digest`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val head = transport.head(server.url("/SDN_ADVANCED.XML").toUri(), emptyMap())

        head.digest shouldBe null
    }
}
