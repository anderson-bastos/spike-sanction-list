package com.spike.ofac.domain.version

import com.spike.ofac.domain.model.Sha256Digest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Unit tests for [Validate.check] — the distinct validation causes (task 10.3).
 *
 * These are focused example tests that pin each of the three mutually-exclusive
 * rejection causes to a concrete, hand-built scenario, plus the accepting case:
 *
 *  - a `null` advertised digest  -> `Rejected(ABSENT_DIGEST)`   (Req 3.2)
 *  - well-formed XML advertised with the wrong digest -> `Rejected(DIGEST_MISMATCH)` (Req 3.3)
 *  - malformed XML advertised with its *own* correct digest -> `Rejected(MALFORMED_XML)` (Req 3.5)
 *  - well-formed XML advertised with its correct digest -> `Ok`
 *
 * Property 2 ([ValidatePropertyTest]) already proves the "iff" universally; this
 * file is the example-level companion that documents each labelled cause with a
 * minimal, readable case. Advertised digests are computed here (in the test)
 * with an independent SHA-256 so the expectations do not depend on the
 * production hashing code.
 */
class ValidateTest {

    /**
     * Req 3.2: with no advertised digest there is nothing to verify integrity
     * against, so the snapshot is rejected up front with the absent-digest
     * cause — even when the bytes themselves are perfectly well-formed XML.
     */
    @Test
    fun `absent advertised digest is rejected with ABSENT_DIGEST`() {
        val snapshot = WELL_FORMED_XML

        val result = Validate.check(snapshot, advertisedDigest = null)

        result shouldBe ValidationResult.Rejected(ValidationResult.Cause.ABSENT_DIGEST)
    }

    /**
     * Req 3.3: well-formed XML advertised with a digest that does not match its
     * content is rejected with the digest-mismatch cause. Integrity is checked
     * before parsing, so well-formedness never gets a say here.
     */
    @Test
    fun `well-formed XML with a wrong advertised digest is rejected with DIGEST_MISMATCH`() {
        val snapshot = WELL_FORMED_XML
        // A valid-shaped digest that is deliberately not the digest of `snapshot`.
        val wrongDigest = Sha256Digest("0".repeat(64))
        // Guard the fixture: the wrong digest must genuinely differ from the real one.
        wrongDigest.value shouldNotBe sha256Hex(snapshot)

        val result = Validate.check(snapshot, wrongDigest)

        result shouldBe ValidationResult.Rejected(ValidationResult.Cause.DIGEST_MISMATCH)
    }

    /**
     * Req 3.5: malformed XML advertised with its *own* correct digest passes the
     * integrity check (the digest matches the bytes) so the rejection is
     * attributable purely to well-formedness — the malformed-XML cause.
     */
    @Test
    fun `malformed XML with its own correct digest is rejected with MALFORMED_XML`() {
        val snapshot = MALFORMED_XML
        val correctDigest = Sha256Digest(sha256Hex(snapshot))

        val result = Validate.check(snapshot, correctDigest)

        result shouldBe ValidationResult.Rejected(ValidationResult.Cause.MALFORMED_XML)
    }

    /**
     * The accepting case: well-formed XML advertised with its correct digest
     * passes both the integrity and well-formedness checks and yields `Ok`.
     */
    @Test
    fun `well-formed XML with its correct digest is accepted`() {
        val snapshot = WELL_FORMED_XML
        val correctDigest = Sha256Digest(sha256Hex(snapshot))

        val result = Validate.check(snapshot, correctDigest)

        result shouldBe ValidationResult.Ok
    }

    // ------------------------------------------------------------------
    // Fixtures and independent helpers
    // ------------------------------------------------------------------

    private companion object {
        /** A minimal, genuinely well-formed XML document. */
        val WELL_FORMED_XML: ByteArray =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root><child>value</child></root>"
                .toByteArray(Charsets.UTF_8)

        /** Bytes that are not well-formed XML (root tag is never closed). */
        val MALFORMED_XML: ByteArray =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root><child>value</child>"
                .toByteArray(Charsets.UTF_8)

        /** Lowercase-hex SHA-256, computed independently of the production code. */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
        }
    }
}
