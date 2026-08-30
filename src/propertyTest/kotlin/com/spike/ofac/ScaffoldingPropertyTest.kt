package com.spike.ofac

import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.shouldBe
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Tag

/**
 * Scaffolding-only property test.
 *
 * Task 1 sets up the property-test source set, jqwik, kotest assertions, and the
 * shared tagging convention. This placeholder proves the wiring compiles and runs
 * a property with the 100-iteration default. It is replaced by the real
 * correctness-property tests in tasks 3+.
 */
@Tag(PropertyTests.FEATURE_TAG)
class ScaffoldingPropertyTest {

    @Property(tries = PropertyTests.MIN_TRIES)
    fun tagConventionIsWellFormed(@ForAll @IntRange(min = 1, max = 20) n: Int) {
        val label = PropertyTests.label(n, "placeholder")
        label shouldBe "Feature: ofac-sanctions-ingestion, Property $n: placeholder"
    }
}
