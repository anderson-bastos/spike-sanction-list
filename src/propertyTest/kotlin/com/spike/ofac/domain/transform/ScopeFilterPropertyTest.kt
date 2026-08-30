package com.spike.ofac.domain.transform

import com.spike.ofac.domain.model.Diagnostic
import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.transform.ScopeFilter.RawProfile
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.Size
import org.junit.jupiter.api.Tag

/**
 * Property 3: Scope filter yields zero vessels and aircraft (task 3.2).
 *
 * Validates: Requirements 5.1, 5.2, 5.3.
 *
 * Generates arbitrary mixes of raw profiles across every classification path —
 * in-scope (`Entity`/`Individual`), recognized-out-of-scope (`Vessel`/`Aircraft`),
 * and missing / empty / unrecognized `PartySubTypeID` — then asserts the four
 * scope-filter invariants against [ScopeFilter.filter]:
 *
 *  - the output contains **zero** Vessel/Aircraft records (Req 5.2): every kept
 *    record is `Individual` or `Entity`;
 *  - the kept set is **exactly** the in-scope input records, in input order,
 *    with their correctly mapped [EntityType] (Req 5.1);
 *  - **one diagnostic per excluded record whose type is missing/empty/unrecognized**
 *    (Req 5.3), and **no** diagnostic for a recognized Vessel/Aircraft exclusion
 *    — the design's "one diagnostic per excluded record" resolves to one per
 *    unrecognized-type exclusion (design.md / Req 5.3);
 *  - `outOfScopeCount` equals the total number of excluded records (recognized
 *    out-of-scope + unrecognized), i.e. `input − kept`.
 */
@Tag(PropertyTests.FEATURE_TAG)
class ScopeFilterPropertyTest {

    /**
     * The ground-truth classification the generator intended for a profile, so
     * the test can compute the expected kept set and diagnostic count
     * independently of the implementation.
     *
     * jqwik constructs / inspects these while generating `@ForAll List<Case>`,
     * so they must not be private-in-class.
     */
    enum class Expected { IN_SCOPE, RECOGNIZED_OUT_OF_SCOPE, UNRECOGNIZED }

    data class Case(val profile: RawProfile, val expected: Expected)

    @Property(tries = PropertyTests.MIN_TRIES)
    fun scopeFilterYieldsZeroVesselsAndAircraft(
        @ForAll("caseMixes") @Size(min = 0, max = 60) cases: List<Case>,
    ) {
        val profiles = cases.map { it.profile }

        val result = ScopeFilter.filter(profiles)

        // Req 5.2: zero vessels/aircraft in the output. Every kept record carries
        // an in-scope EntityType (Individual/Entity only).
        result.kept.forEach { kept ->
            (kept.entityType == EntityType.Individual || kept.entityType == EntityType.Entity)
                .shouldBe(true)
        }

        // Req 5.1: the kept set is exactly the in-scope inputs, in input order,
        // each mapped to the expected EntityType.
        val expectedKept = cases
            .filter { it.expected == Expected.IN_SCOPE }
            .map { case ->
                ScopeFilter.ScopedRecord(
                    fixedRef = case.profile.fixedRef,
                    entityType = expectedEntityType(case.profile.partySubTypeId),
                )
            }
        result.kept.shouldContainExactly(expectedKept)

        // Req 5.3: exactly one diagnostic per unrecognized-type exclusion, and no
        // diagnostic for a recognized Vessel/Aircraft exclusion.
        val unrecognizedCount = cases.count { it.expected == Expected.UNRECOGNIZED }
        result.diagnostics.size shouldBe unrecognizedCount
        result.diagnostics.forEach { it.kind shouldBe Diagnostic.Kind.UNRECOGNIZED_TYPE }
        // Every unrecognized input record must be represented by a diagnostic.
        val diagnosedRefs = result.diagnostics.mapNotNull { it.fixedRef }.toSet()
        val unrecognizedRefs = cases
            .filter { it.expected == Expected.UNRECOGNIZED }
            .map { it.profile.fixedRef }
            .toSet()
        diagnosedRefs shouldBe unrecognizedRefs

        // outOfScopeCount counts every excluded record (recognized OOS + unrecognized).
        val expectedOutOfScope = cases.count { it.expected != Expected.IN_SCOPE }
        result.outOfScopeCount shouldBe expectedOutOfScope
        // Sanity: kept + out-of-scope partitions the whole input.
        (result.kept.size + result.outOfScopeCount) shouldBe profiles.size
    }

    private fun expectedEntityType(partySubTypeId: String?): EntityType =
        when (partySubTypeId) {
            "3" -> EntityType.Entity
            "4" -> EntityType.Individual
            else -> error("generator produced a non-in-scope id for an in-scope case: $partySubTypeId")
        }

    // --- Generators -------------------------------------------------------

    @Provide
    fun caseMixes(): Arbitrary<List<Case>> = caseArbitrary().list().ofMaxSize(60)

    private fun caseArbitrary(): Arbitrary<Case> =
        fixedRefs().flatMap { ref ->
            classificationBuckets().map { bucketed ->
                Case(RawProfile(ref, bucketed.second), bucketed.first)
            }
        }

    /** Non-empty FixedRef values (the value class rejects empty strings). */
    private fun fixedRefs(): Arbitrary<FixedRef> =
        Arbitraries.strings()
            .withCharRange('a', 'z')
            .numeric()
            .ofMinLength(1)
            .ofMaxLength(12)
            .map { FixedRef(it) }

    /**
     * Produce a `PartySubTypeID` value together with the classification the test
     * expects for it, spanning every branch of [ScopeFilter.classify]:
     *  - in-scope recognized ids "3"/"4";
     *  - out-of-scope recognized ids "1"/"2";
     *  - unrecognized: null (missing), blank/empty, and arbitrary unknown values
     *    (numeric-but-unmapped ids and non-numeric noise).
     */
    private fun classificationBuckets(): Arbitrary<Pair<Expected, String?>> {
        val inScope: Arbitrary<Pair<Expected, String?>> =
            Arbitraries.of("3", "4").map { Expected.IN_SCOPE to (it as String?) }

        val recognizedOutOfScope: Arbitrary<Pair<Expected, String?>> =
            Arbitraries.of("1", "2").map { Expected.RECOGNIZED_OUT_OF_SCOPE to (it as String?) }

        val missing: Arbitrary<Pair<Expected, String?>> =
            Arbitraries.just(Expected.UNRECOGNIZED to null)

        val blank: Arbitrary<Pair<Expected, String?>> =
            Arbitraries.of("", " ", "   ", "\t").map { Expected.UNRECOGNIZED to (it as String?) }

        val unknown: Arbitrary<Pair<Expected, String?>> =
            Arbitraries.oneOf(
                // numeric ids outside the recognized {1,2,3,4} set
                Arbitraries.integers().between(5, 99).map { it.toString() },
                // arbitrary non-blank noise that is not a recognized id
                Arbitraries.strings().ofMinLength(1).ofMaxLength(6)
                    .filter { it.isNotBlank() && it !in ScopeFilter.PARTY_SUBTYPE.keys },
            ).map { Expected.UNRECOGNIZED to (it as String?) }

        // Union every branch so all classification paths appear across 100+ iterations.
        return Arbitraries.oneOf(
            inScope,
            recognizedOutOfScope,
            missing,
            blank,
            unknown,
        )
    }
}
