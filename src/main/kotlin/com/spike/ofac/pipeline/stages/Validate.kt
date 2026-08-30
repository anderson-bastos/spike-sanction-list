package com.spike.ofac.pipeline.stages

import com.spike.ofac.pipeline.models.Sha256Digest
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * The `validate` stage: integrity + well-formedness of a downloaded snapshot
 * (Req 3).
 *
 * Contract (design.md "validate"):
 *
 * ```
 * validate.check(snapshot: bytes, advertised_digest?: Sha256) -> ValidationResult
 * ValidationResult = OK
 *                  | REJECTED(ABSENT_DIGEST)      # source advertised no digest (Req 3.2)
 *                  | REJECTED(DIGEST_MISMATCH)     # computed != advertised (Req 3.3)
 *                  | REJECTED(MALFORMED_XML)       # not well-formed Advanced XML (Req 3.5)
 * ```
 *
 * Order is fixed (Req 3.1 -> 3.4): compute the SHA-256 of the raw bytes and
 * compare it to the advertised `Digest` **before any parsing**; only on a match
 * is well-formedness verified. The three failure causes are distinct and
 * recorded (Req 3.2, 3.3, 3.5). Every rejection leaves `CURRENT` unchanged —
 * this stage is pure and never mutates any pointer or store (Req 3.4); acting
 * on its result is the caller's responsibility.
 */

/**
 * Outcome of [Validate.check].
 *
 * `OK` means the bytes hashed to exactly the advertised digest and parsed as
 * well-formed XML. Every other outcome is a [ValidationResult.Rejected] naming a
 * single distinct [cause][ValidationResult.Rejected.cause].
 */
sealed interface ValidationResult {
    /** The snapshot is intact (digest matched) and well-formed. */
    data object Ok : ValidationResult

    /**
     * The snapshot was rejected. [cause] names exactly which check failed; the
     * three causes are mutually exclusive and reported distinctly (Req 3.2,
     * 3.3, 3.5).
     */
    data class Rejected(val cause: Cause) : ValidationResult

    /** The distinct, mutually-exclusive rejection causes (Req 3.2, 3.3, 3.5). */
    enum class Cause {
        /** The source advertised no digest, so integrity cannot be verified (Req 3.2). */
        ABSENT_DIGEST,

        /** The computed SHA-256 did not equal the advertised digest (Req 3.3). */
        DIGEST_MISMATCH,

        /** The bytes are not well-formed Advanced XML (Req 3.5). */
        MALFORMED_XML,
    }
}

/**
 * Pure integrity + well-formedness validator for downloaded snapshots (Req 3).
 *
 * Stateless and side-effect free: it reads only the bytes it is given and
 * returns a [ValidationResult]. It performs no I/O, holds no state, and never
 * touches any pointer or store.
 */
object Validate {

    /**
     * A namespace-aware, non-validating [XMLInputFactory] hardened against
     * external entity resolution (XXE) and DTD processing so untrusted snapshot
     * bytes cannot trigger network fetches or entity-expansion attacks. It is
     * still a *well-formedness* check only — no schema validation is performed,
     * matching the "well-formed Advanced XML" wording of Req 3.5.
     *
     * `XMLInputFactory` is not guaranteed thread-safe, so a fresh instance is
     * created per call; [check] is invoked once per cycle, well off any hot
     * path, so this is not a concern.
     */
    private fun hardenedInputFactory(): XMLInputFactory =
        XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            // Coalesce so text runs are delivered whole; irrelevant to
            // well-formedness but avoids surprises for any later reader reuse.
            setProperty(XMLInputFactory.IS_COALESCING, true)
        }

    /**
     * Validates a downloaded [snapshot] against its [advertisedDigest].
     *
     * Steps, in the fixed order required by Req 3.1 -> 3.4:
     *  1. If no digest was advertised, reject with [ABSENT_DIGEST][ValidationResult.Cause.ABSENT_DIGEST] (Req 3.2).
     *  2. Compute SHA-256 over [snapshot] and compare to [advertisedDigest]
     *     **before any parsing**; on inequality reject with
     *     [DIGEST_MISMATCH][ValidationResult.Cause.DIGEST_MISMATCH] (Req 3.3).
     *  3. Only on a digest match, verify the bytes are well-formed XML via a
     *     streaming [XMLStreamReader]; on any parse error reject with
     *     [MALFORMED_XML][ValidationResult.Cause.MALFORMED_XML] (Req 3.5).
     *  4. Otherwise return [OK][ValidationResult.Ok].
     *
     * @param snapshot the raw downloaded bytes.
     * @param advertisedDigest the digest the source advertised, or `null` when
     *   the source advertised none (Req 3.2).
     */
    fun check(snapshot: ByteArray, advertisedDigest: Sha256Digest?): ValidationResult {
        // Req 3.2: an absent advertised digest is rejected before anything else
        // — with no digest there is nothing to verify integrity against.
        if (advertisedDigest == null) {
            return ValidationResult.Rejected(ValidationResult.Cause.ABSENT_DIGEST)
        }

        // Req 3.1 / 3.4: integrity is checked BEFORE any parsing. Compute the
        // SHA-256 of the raw bytes and compare to the advertised digest.
        val computed = sha256Hex(snapshot)
        if (computed != advertisedDigest) {
            return ValidationResult.Rejected(ValidationResult.Cause.DIGEST_MISMATCH)
        }

        // Req 3.5: only once integrity holds do we verify well-formed XML.
        if (!isWellFormedXml(snapshot)) {
            return ValidationResult.Rejected(ValidationResult.Cause.MALFORMED_XML)
        }

        return ValidationResult.Ok
    }

    /** Computes the lowercase-hex SHA-256 of [bytes] as a [Sha256Digest]. */
    private fun sha256Hex(bytes: ByteArray): Sha256Digest {
        val digestBytes = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = buildString(digestBytes.size * 2) {
            for (b in digestBytes) {
                val v = b.toInt() and 0xFF
                append(HEX_DIGITS[v ushr 4])
                append(HEX_DIGITS[v and 0x0F])
            }
        }
        return Sha256Digest(hex)
    }

    /**
     * Streams [bytes] through an [XMLStreamReader] to the end of the document,
     * returning `true` iff the whole input is well-formed XML.
     *
     * The reader advances token-by-token without materializing a DOM, matching
     * the streaming strategy the pipeline uses in `transform`. Any
     * [XMLStreamException] (or a reader/factory configuration error surfacing as
     * a runtime exception) means the bytes are not well-formed.
     */
    private fun isWellFormedXml(bytes: ByteArray): Boolean {
        var reader: XMLStreamReader? = null
        return try {
            reader = hardenedInputFactory().createXMLStreamReader(ByteArrayInputStream(bytes))
            while (reader.hasNext()) {
                reader.next()
            }
            // Reaching END_DOCUMENT without an exception means well-formed.
            reader.eventType == XMLStreamConstants.END_DOCUMENT
        } catch (_: XMLStreamException) {
            false
        } catch (_: RuntimeException) {
            // Some malformed inputs surface as factory/reader RuntimeExceptions
            // rather than XMLStreamException; treat them as not well-formed.
            false
        } finally {
            try {
                reader?.close()
            } catch (_: XMLStreamException) {
                // Closing a reader over malformed input can itself throw; ignore.
            }
        }
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}
