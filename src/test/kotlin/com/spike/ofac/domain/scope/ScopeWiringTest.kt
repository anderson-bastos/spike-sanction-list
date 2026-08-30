package com.spike.ofac.domain.scope

import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.transform.CrossListDedup
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for scope wiring (task 14.3).
 *
 * These pin down how a validated [ScopeConfig] wires into concrete pipeline
 * behavior, using focused examples:
 *  - [ScopeConfig.SDN_ONLY] ingests only the SDN list and never engages the
 *    cross-list dedup path, so no Consolidated-sourced record is ever persisted
 *    (Req 12.2).
 *  - [ScopeConfig.SDN_AND_CONSOLIDATED] ingests both lists and runs the dedup
 *    path, which merges by `FixedRef` with SDN precedence (Req 12.3, Req 6).
 *
 * The wiring is checked at two seams: [ScopeConfigValidator.sourceListsFor] /
 * [ScopeConfigValidator.runsDedup] (what the pipeline is told to ingest and
 * whether dedup runs), and [CrossListDedup.deduplicate] itself (the behavior
 * that wiring engages or bypasses).
 */
class ScopeWiringTest {

    private fun entry(key: String, source: String): InternalModelEntry =
        InternalModelEntry(
            fixedRef = FixedRef("FR-$key"),
            entityType = EntityType.Entity,
            primaryName = "$source-name-$key",
            sanctionPrograms = listOf("$source-program-$key"),
        )

    // --- Req 12.2: SDN_ONLY persists no Consolidated record ---

    @Test
    fun `SDN_ONLY sources only the SDN list - no Consolidated (Req 12_2)`() {
        val sources = ScopeConfigValidator.sourceListsFor(ScopeConfig.SDN_ONLY)

        sources shouldContainExactly listOf(SourceList.SDN)
        sources shouldNotContain SourceList.CONSOLIDATED
    }

    @Test
    fun `SDN_ONLY does not run the dedup path (Req 12_2)`() {
        ScopeConfigValidator.runsDedup(ScopeConfig.SDN_ONLY).shouldBeFalse()
    }

    @Test
    fun `SDN_ONLY dedup ignores the Consolidated list and persists no Consolidated record (Req 12_2)`() {
        val sdn = listOf(entry("1", source = "SDN"), entry("2", source = "SDN"))
        // A Consolidated set is supplied but must be ignored under SDN_ONLY.
        val consolidated = listOf(entry("2", source = "CONS"), entry("3", source = "CONS"))

        val result = CrossListDedup.deduplicate(
            sdnRecords = sdn,
            consolidatedRecords = consolidated,
            scope = ScopeConfig.SDN_ONLY,
        )

        // Only the SDN records survive; the Consolidated-exclusive FixedRef is absent.
        result shouldContainExactly sdn
        result.map { it.fixedRef } shouldNotContain FixedRef("FR-3")
        // No record retains a Consolidated representation (every name is SDN-sourced).
        result.all { it.primaryName.startsWith("SDN-") }.shouldBeTrue()
    }

    // --- Req 12.3: SDN_AND_CONSOLIDATED exercises the dedup path ---

    @Test
    fun `SDN_AND_CONSOLIDATED sources both lists with SDN first (Req 12_3)`() {
        val sources = ScopeConfigValidator.sourceListsFor(ScopeConfig.SDN_AND_CONSOLIDATED)

        sources shouldContainExactly listOf(SourceList.SDN, SourceList.CONSOLIDATED)
    }

    @Test
    fun `SDN_AND_CONSOLIDATED runs the dedup path (Req 12_3)`() {
        ScopeConfigValidator.runsDedup(ScopeConfig.SDN_AND_CONSOLIDATED).shouldBeTrue()
    }

    @Test
    fun `SDN_AND_CONSOLIDATED merges by FixedRef with SDN precedence (Req 12_3, Req 6)`() {
        // FR-2 is shared between both lists; FR-1 is SDN-only, FR-3 Consolidated-only.
        val sdn = listOf(entry("1", source = "SDN"), entry("2", source = "SDN"))
        val consolidated = listOf(entry("2", source = "CONS"), entry("3", source = "CONS"))

        val result = CrossListDedup.deduplicate(
            sdnRecords = sdn,
            consolidatedRecords = consolidated,
            scope = ScopeConfig.SDN_AND_CONSOLIDATED,
        )

        // Distinct union of FixedRefs, one record each (Req 6.1, 6.3).
        result.map { it.fixedRef } shouldContainExactly
            listOf(FixedRef("FR-1"), FixedRef("FR-2"), FixedRef("FR-3"))

        // Shared FR-2 keeps its SDN representation, not the Consolidated one (Req 6.2).
        val shared = result.single { it.fixedRef == FixedRef("FR-2") }
        shared.primaryName shouldBe "SDN-name-2"

        // The Consolidated-exclusive FR-3 still contributes its own record (Req 12.3).
        val consolidatedOnly = result.single { it.fixedRef == FixedRef("FR-3") }
        consolidatedOnly.primaryName shouldBe "CONS-name-3"
    }
}
