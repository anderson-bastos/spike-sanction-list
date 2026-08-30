package com.spike.ofac.testsupport

/**
 * Shared tagging convention for the 20 correctness-property tests.
 *
 * The design's Testing Strategy requires every property-based test to be tagged
 * with a reference to its design property in the format:
 *
 *   `Feature: ofac-sanctions-ingestion, Property {number}: {property_text}`
 *
 * Each property is implemented as a single jqwik `@Property` with a minimum of
 * 100 iterations (the jqwik default is set to 100 in `junit-platform.properties`;
 * tests may also be explicit with `@Property(tries = 100)`).
 *
 * Usage: annotate the property method or its containing class with
 * `@Tag(FEATURE_TAG)` for JUnit-platform filtering, and put the full property
 * text in a doc comment / `@Label`, e.g.:
 *
 * ```
 * @Property(tries = MIN_TRIES)
 * @Label("Property 3: Scope filter yields zero vessels and aircraft")
 * @Tag(FEATURE_TAG)
 * fun scopeFilterYieldsZeroVesselsAndAircraft(...) { ... }
 * ```
 */
object PropertyTests {
    /** JUnit-platform tag applied to every property test for this feature. */
    const val FEATURE_TAG: String = "ofac-sanctions-ingestion"

    /** Minimum iterations per property test (design Testing Strategy). */
    const val MIN_TRIES: Int = 100

    /** Builds the canonical property label for property [number]. */
    fun label(number: Int, text: String): String =
        "Feature: $FEATURE_TAG, Property $number: $text"
}
