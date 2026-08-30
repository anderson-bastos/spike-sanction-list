package com.spike.ofac.pipeline.stages

import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Tag
import java.security.MessageDigest

/**
 * Property 2: Validation accepts exactly the intact, well-formed snapshots.
 *
 * `Feature: ofac-sanctions-ingestion, Property 2: Validation accepts exactly the
 * intact, well-formed snapshots` (Req 3.1, 3.2, 3.3, 3.4, 3.5).
 *
 * [Validate.check] is the pure integrity + well-formedness gate. The single
 * universal property it must satisfy is:
 *
 *   `check(bytes, advertised) == OK`  **iff**
 *     `advertised != null` **and** `sha256(bytes) == advertised` **and** `bytes`
 *     are well-formed XML;
 *   otherwise it is `Rejected` with exactly the distinct cause of the *first*
 *   failing check in the fixed order (Req 3.1 -> 3.4):
 *     1. `advertised == null`                 -> ABSENT_DIGEST   (Req 3.2)
 *     2. `sha256(bytes) != advertised`        -> DIGEST_MISMATCH (Req 3.3)
 *     3. bytes not well-formed XML            -> MALFORMED_XML   (Req 3.5)
 *
 * The generators below realize every branch of that specification, including
 * single-byte mutations of otherwise-valid snapshots — mutations are the
 * adversarial case that exercises both DIGEST_MISMATCH (mutate the bytes, keep
 * the old digest) and MALFORMED_XML (mutate the bytes, advertise the *new*
 * digest so integrity passes but well-formedness can break).
 */
@Tag(PropertyTests.FEATURE_TAG)
class ValidatePropertyTest {

    // ------------------------------------------------------------------
    // The one universal property, driven over a labeled space of cases.
    // ------------------------------------------------------------------

    /**
     * The single correctness property: for every generated (bytes, advertised
     * digest) pair, [Validate.check] returns `Ok` exactly when the digest is
     * present, matches the SHA-256 of the bytes, and the bytes are well-formed
     * XML — and otherwise returns `Rejected` with the distinct cause of the
     * first failing check in the fixed order.
     */
    @Property(tries = PropertyTests.MIN_TRIES)
    fun validationAcceptsExactlyTheIntactWellFormedSnapshots(
        @ForAll @From("validationCases") case: ValidationCase,
    ) {
        val result = Validate.check(case.bytes, case.advertisedDigest)

        result shouldBe case.expected()
    }

    /**
     * Independent cross-check of the "iff" from the *reference* predicates
     * rather than from the generator's declared intent. Recomputes the digest
     * and well-formedness here (in the test) and asserts the outcome matches the
     * first-failing-check semantics. This guards against a generator that
     * mislabels a case (e.g. a "mismatch" mutation that happens to collide).
     */
    @Property(tries = PropertyTests.MIN_TRIES)
    fun outcomeMatchesFirstFailingCheck(
        @ForAll @From("validationCases") case: ValidationCase,
    ) {
        val advertised = case.advertisedDigest
        val expected: ValidationResult = when {
            advertised == null ->
                ValidationResult.Rejected(ValidationResult.Cause.ABSENT_DIGEST)
            sha256Hex(case.bytes) != advertised.value ->
                ValidationResult.Rejected(ValidationResult.Cause.DIGEST_MISMATCH)
            !isWellFormedXml(case.bytes) ->
                ValidationResult.Rejected(ValidationResult.Cause.MALFORMED_XML)
            else -> ValidationResult.Ok
        }

        Validate.check(case.bytes, advertised) shouldBe expected
    }

    // ------------------------------------------------------------------
    // Case model
    // ------------------------------------------------------------------

    /**
     * A single generated validation scenario: the [bytes] handed to the stage,
     * the [advertisedDigest] the source claimed (possibly `null`), and the
     * [kind] the generator intended so [expected] can name the outcome without
     * re-deriving it. The `outcomeMatchesFirstFailingCheck` property
     * independently re-derives the outcome to catch any mislabeling.
     */
    data class ValidationCase(
        val kind: Kind,
        val bytes: ByteArray,
        val advertisedDigest: Sha256Digest?,
    ) {
        enum class Kind { OK, ABSENT_DIGEST, DIGEST_MISMATCH, MALFORMED_XML }

        fun expected(): ValidationResult = when (kind) {
            Kind.OK -> ValidationResult.Ok
            Kind.ABSENT_DIGEST -> ValidationResult.Rejected(ValidationResult.Cause.ABSENT_DIGEST)
            Kind.DIGEST_MISMATCH -> ValidationResult.Rejected(ValidationResult.Cause.DIGEST_MISMATCH)
            Kind.MALFORMED_XML -> ValidationResult.Rejected(ValidationResult.Cause.MALFORMED_XML)
        }

        // data class with a ByteArray needs value-based equals/hashCode so
        // shrinking/reporting behaves; jqwik only reads the fields, but be safe.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ValidationCase) return false
            return kind == other.kind &&
                bytes.contentEquals(other.bytes) &&
                advertisedDigest == other.advertisedDigest
        }

        override fun hashCode(): Int {
            var h = kind.hashCode()
            h = 31 * h + bytes.contentHashCode()
            h = 31 * h + (advertisedDigest?.hashCode() ?: 0)
            return h
        }
    }

    // ------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------

    /**
     * The full space of validation cases, mixing all four intended outcomes so a
     * single property covers OK acceptance and each distinct rejection cause.
     */
    @Provide
    fun validationCases(): Arbitrary<ValidationCase> =
        Arbitraries.oneOf(
            okCases(),
            absentDigestCases(),
            digestMismatchCases(),
            malformedXmlCases(),
        )

    /**
     * Well-formed XML bytes advertised with their *correct* SHA-256 -> `Ok`.
     */
    private fun okCases(): Arbitrary<ValidationCase> =
        wellFormedXml().map { bytes ->
            ValidationCase(
                kind = ValidationCase.Kind.OK,
                bytes = bytes,
                advertisedDigest = Sha256Digest(sha256Hex(bytes)),
            )
        }

    /**
     * Any bytes (well-formed or not) advertised with a `null` digest ->
     * ABSENT_DIGEST. The absent-digest check comes first, so well-formedness is
     * irrelevant here — both are generated to prove that.
     */
    private fun absentDigestCases(): Arbitrary<ValidationCase> =
        Arbitraries.oneOf(wellFormedXml(), arbitraryBytes()).map { bytes ->
            ValidationCase(
                kind = ValidationCase.Kind.ABSENT_DIGEST,
                bytes = bytes,
                advertisedDigest = null,
            )
        }

    /**
     * Well-formed XML bytes advertised with a digest that does **not** match
     * their content -> DIGEST_MISMATCH. Two sources of mismatch are mixed:
     *  - an independent random digest (astronomically unlikely to collide), and
     *  - a **single-byte mutation** of the bytes while keeping the *original*
     *    digest — the realistic corruption-in-transit case.
     */
    private fun digestMismatchCases(): Arbitrary<ValidationCase> =
        Arbitraries.oneOf(mismatchRandomDigest(), mismatchSingleByteMutation())

    private fun mismatchRandomDigest(): Arbitrary<ValidationCase> =
        wellFormedXml().flatMap { bytes ->
            randomDigest()
                // Guard against the vanishingly rare case where the random digest
                // equals the real one: filter it out so the label stays honest.
                .filter { digest -> digest.value != sha256Hex(bytes) }
                .map { digest ->
                    ValidationCase(
                        kind = ValidationCase.Kind.DIGEST_MISMATCH,
                        bytes = bytes,
                        advertisedDigest = digest,
                    )
                }
        }

    private fun mismatchSingleByteMutation(): Arbitrary<ValidationCase> =
        wellFormedXml()
            .filter { it.isNotEmpty() }
            .flatMap { original ->
                Arbitraries.integers().between(0, Int.MAX_VALUE).flatMap { posSeed ->
                    Arbitraries.integers().between(1, 255).map { delta ->
                        val originalDigest = Sha256Digest(sha256Hex(original))
                        val mutated = original.copyOf()
                        val idx = posSeed % mutated.size
                        // delta in 1..255 guarantees the byte actually changes.
                        mutated[idx] = (mutated[idx].toInt() xor delta).toByte()
                        ValidationCase(
                            kind = ValidationCase.Kind.DIGEST_MISMATCH,
                            bytes = mutated,
                            // Advertise the ORIGINAL digest: the mutated bytes no
                            // longer hash to it, so integrity fails before parsing.
                            advertisedDigest = originalDigest,
                        )
                    }
                }
            }
            // The mutated bytes must not accidentally re-hash to the original
            // digest (impossible for a single-byte flip, but keep the invariant
            // explicit and robust).
            .filter { case ->
                sha256Hex(case.bytes) != case.advertisedDigest!!.value
            }

    /**
     * Bytes that are **not** well-formed XML, advertised with their *own*
     * correct digest so integrity passes and the failure is attributable purely
     * to well-formedness -> MALFORMED_XML.
     */
    private fun malformedXmlCases(): Arbitrary<ValidationCase> =
        malformedXml().map { bytes ->
            ValidationCase(
                kind = ValidationCase.Kind.MALFORMED_XML,
                // Advertise the correct digest of the malformed bytes so the
                // digest check passes and only well-formedness can reject.
                bytes = bytes,
                advertisedDigest = Sha256Digest(sha256Hex(bytes)),
            )
        }

    // ---- byte / xml building blocks ----

    /** Arbitrary raw bytes (may or may not be valid XML). */
    private fun arbitraryBytes(): Arbitrary<ByteArray> =
        Arbitraries.bytes().array(ByteArray::class.java).ofMinSize(0).ofMaxSize(64)

    /** An independently-random 64-char lowercase-hex digest. */
    private fun randomDigest(): Arbitrary<Sha256Digest> =
        Arbitraries.strings()
            .withChars(*"0123456789abcdef".toCharArray())
            .ofLength(64)
            .map { Sha256Digest(it) }

    /**
     * Generates small but genuinely well-formed XML documents whose textual
     * content varies (including non-ASCII, matching the UTF-8 requirement the
     * pipeline relies on). Kept structurally simple so well-formedness is a
     * property of construction, not of luck.
     */
    private fun wellFormedXml(): Arbitrary<ByteArray> {
        val name: Arbitrary<String> = Arbitraries.strings()
            .withChars(*("abcdefghijklmnopqrstuvwxyz".toCharArray()))
            .ofMinLength(1).ofMaxLength(8)
        val text: Arbitrary<String> = Arbitraries.oneOf(
            Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(12),
            // A few non-ASCII values to keep the UTF-8 path exercised.
            Arbitraries.of("Hải Phòng", "Skořepka", "北京", "Müller", ""),
        )
        val childCount: Arbitrary<Int> = Arbitraries.integers().between(0, 4)

        return name.flatMap { tag ->
            text.flatMap { body ->
                childCount.map { n ->
                    val children = (0 until n).joinToString("") { "<c>$body</c>" }
                    val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                        "<$tag>$children<leaf/></$tag>"
                    xml.toByteArray(Charsets.UTF_8)
                }
            }
        }
    }

    /**
     * Generates byte sequences that are **not** well-formed XML: several
     * structurally-distinct flavors so the malformed case is not a single shape.
     */
    private fun malformedXml(): Arbitrary<ByteArray> {
        val flavors: Arbitrary<String> = Arbitraries.oneOf(
            // Unclosed root tag.
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6)
                .map { "<$it>" },
            // Mismatched open/close tags.
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6).flatMap { a ->
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(6).map { b ->
                    "<$a></${b}X>"
                }
            },
            // Two root elements (not a single document element).
            Arbitraries.just("<a/><b/>"),
            // Stray unescaped markup.
            Arbitraries.just("<a> < </a>"),
            // Plain, non-XML text with no elements at all.
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20),
            // Completely empty input (no document element).
            Arbitraries.just(""),
        )
        // Keep only the flavors that are genuinely not well-formed; the "plain
        // text" branch could in principle be empty and empty is separately
        // covered, so the filter also drops any accidental well-formed shape.
        return flavors
            .map { it.toByteArray(Charsets.UTF_8) }
            .filter { !isWellFormedXml(it) }
    }

    // ------------------------------------------------------------------
    // Reference helpers (independent of the implementation under test)
    // ------------------------------------------------------------------

    /** Lowercase-hex SHA-256, computed independently of the production code. */
    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
    }

    /**
     * Independent well-formedness oracle used only by the cross-check property
     * and by generator filters. Mirrors the streaming approach the stage uses.
     */
    private fun isWellFormedXml(bytes: ByteArray): Boolean {
        val factory = javax.xml.stream.XMLInputFactory.newInstance().apply {
            setProperty(javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(javax.xml.stream.XMLInputFactory.SUPPORT_DTD, false)
        }
        var reader: javax.xml.stream.XMLStreamReader? = null
        return try {
            reader = factory.createXMLStreamReader(java.io.ByteArrayInputStream(bytes))
            while (reader.hasNext()) reader.next()
            reader.eventType == javax.xml.stream.XMLStreamConstants.END_DOCUMENT
        } catch (_: javax.xml.stream.XMLStreamException) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            try {
                reader?.close()
            } catch (_: javax.xml.stream.XMLStreamException) {
                // ignore
            }
        }
    }
}
