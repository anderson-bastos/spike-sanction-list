package com.spike.ofac.testsupport

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * Scaffolding-only unit test for the default `test` source set.
 *
 * Verifies the shared test support wired in task 1: the property-test tag
 * convention, the observed ReferenceValueSet, and that MockK is on the
 * classpath. Real unit tests arrive alongside their components in later tasks.
 */
class ScaffoldingUnitTest {

    @Test
    fun tagConventionMatchesDesignFormat() {
        PropertyTests.label(3, "Scope filter yields zero vessels and aircraft") shouldBe
            "Feature: ofac-sanctions-ingestion, Property 3: Scope filter yields zero vessels and aircraft"
    }

    @Test
    fun referenceValueSetMatchesBenchmark() {
        Fixtures.PARTY_SUBTYPE shouldBe
            mapOf("1" to "Vessel", "2" to "Aircraft", "3" to "Entity", "4" to "Individual")
        Fixtures.IN_SCOPE shouldBe setOf("Entity", "Individual")
    }

    @Test
    fun mockkIsAvailable() {
        val supplier = mockk<() -> Int>()
        every { supplier() } returns 42
        supplier() shouldBe 42
    }
}
