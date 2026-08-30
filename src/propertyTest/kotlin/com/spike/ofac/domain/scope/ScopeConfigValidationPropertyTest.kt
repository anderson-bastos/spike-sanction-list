package com.spike.ofac.domain.scope

import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Tag

/**
 * Property 16: Scope configuration validation (task 14.2, Req 12.1, 12.4, 12.5).
 *
 * The pipeline accepts a scope configuration whose only valid values are exactly
 * `SDN_ONLY` and `SDN_AND_CONSOLIDATED` (Req 12.1). Every other value — the
 * reserved `CONSOLIDATED_ONLY` (Req 12.4), and any absent / empty / unrecognized
 * value (Req 12.5) — must be rejected, ingesting nothing.
 *
 * The property drives [ScopeConfigValidator.validate] with arbitrary raw scope
 * values spanning all of these categories (the two valid names, the reserved
 * `CONSOLIDATED_ONLY`, empty / blank strings, `null`, and random strings) and
 * asserts the acceptance biconditional: the result is [ScopeValidationResult.Valid]
 * **iff** the trimmed value is exactly one of the two valid names, and is
 * [ScopeValidationResult.Invalid] otherwise. On acceptance the wrapped scope must
 * be the one named by the (trimmed) value; on rejection nothing is ingested, which
 * for this pure-logic validator means no [ScopeConfig] is produced.
 */
@Tag(PropertyTests.FEATURE_TAG)
class ScopeConfigValidationPropertyTest {

    @Property(tries = PropertyTests.MIN_TRIES)
    fun scopeConfigurationValidation(
        @ForAll @From("rawScopeValues") raw: String?,
    ) {
        val result = ScopeConfigValidator.validate(raw)

        // The canonical set of accepted values (Req 12.1): the exact enum names.
        val validNames = ScopeConfig.entries.map { it.name }.toSet()
        val trimmed = raw?.trim()
        val shouldAccept = trimmed != null && trimmed in validNames

        when (result) {
            is ScopeValidationResult.Valid -> {
                // Accepted iff the trimmed value is exactly a valid scope name.
                shouldAccept shouldBe true
                // The wrapped scope is precisely the one the value names.
                result.scope shouldBe ScopeConfig.valueOf(trimmed!!)
            }

            is ScopeValidationResult.Invalid -> {
                // Rejected for everything else; nothing is ingested (no scope).
                shouldAccept shouldBe false
            }
        }
    }

    /**
     * Arbitrary raw scope values spanning every category the validator must
     * distinguish: the two valid names, the reserved `CONSOLIDATED_ONLY`, empty
     * and whitespace-only strings, `null`, and arbitrary random strings. Valid
     * names are also emitted with surrounding whitespace so the trimming contract
     * is exercised on the accepting side as well as the rejecting side.
     */
    @Provide
    fun rawScopeValues(): Arbitrary<String?> {
        val validNames: Arbitrary<String> =
            Arbitraries.of(*ScopeConfig.entries.map { it.name }.toTypedArray())

        // Valid names wrapped in arbitrary surrounding whitespace.
        val whitespace: Arbitrary<String> =
            Arbitraries.of(" ", "  ", "\t", "\n", " \t\n ", "")
        val validNamesPadded: Arbitrary<String> =
            Combinators.combine(whitespace, validNames, whitespace)
                .`as` { pre, name, post -> "$pre$name$post" }

        val consolidatedOnly: Arbitrary<String> =
            Arbitraries.of(ScopeConfigValidator.CONSOLIDATED_ONLY)

        // Empty / whitespace-only strings (absent-equivalent, Req 12.5).
        val blanks: Arbitrary<String> =
            Arbitraries.of("", " ", "   ", "\t", "\n", " \t \n ")

        // Arbitrary random strings — the vast majority are unrecognized values.
        val randoms: Arbitrary<String> = Arbitraries.strings().ofMaxLength(20)

        val nonNull: Arbitrary<String> =
            Arbitraries.oneOf(
                validNames,
                validNamesPadded,
                consolidatedOnly,
                blanks,
                randoms,
            )

        // Inject null (~1 in 6) to cover the absent case (Req 12.5).
        @Suppress("UNCHECKED_CAST")
        return nonNull.injectNull(1.0 / 6.0) as Arbitrary<String?>
    }
}
