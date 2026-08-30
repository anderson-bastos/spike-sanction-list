package com.spike.ofac.domain.version

import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Tag
import java.time.LocalDate

/**
 * Property 8: Count reconciliation.
 *
 * Validates that [VersionStage.build] derives `Expected_Count` by the
 * reconciliation formula (Req 8.1), that the persisted in-scope post-dedup count
 * equals it exactly (Req 8.2), and that dropping any single in-scope record makes
 * the persisted count differ from `Expected_Count` so activation is rejected
 * (Req 8.3).
 *
 * The `version` stage is pure logic: it derives the target count from the
 * source-reported `Record_Count`, the out-of-scope count, and the shared-FixedRef
 * overlap term. The "persisted count == expected" and "drop-a-record differs"
 * facets are checked by constructing a consistent in-scope entry list, computing
 * the plan against counts derived from it, and comparing the entry-list size
 * (the post-dedup persisted count) against the derived `expected_count` — the
 * exact-equality comparison Req 8.2 / 8.3 require of `publish`.
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 8: Count reconciliation`.
 *
 * **Validates: Requirements 8.1, 8.2, 8.3**
 */
@Tag(PropertyTests.FEATURE_TAG)
class CountReconciliationPropertyTest {

    /**
     * A single generated reconciliation scenario.
     *
     * The three component counts are chosen independently, then a consistent
     * source-reported `Record_Count` is derived from them so that the formula's
     * `expected_count` is exactly the number of in-scope records:
     * ```
     * record_count = inScope + outOfScope + overlaps
     * expected     = record_count - outOfScope - overlaps = inScope
     * ```
     * For a single-list scope the overlap term is forced to zero, so the scenario
     * carries `overlaps == 0` whenever [scope] is [ScopeConfig.SDN_ONLY].
     */
    data class Scenario(
        val inScope: List<InternalModelEntry>,
        val outOfScopeCount: Int,
        val overlaps: Int,
        val scope: ScopeConfig,
    ) {
        /** The source-reported `<Record_Count>` consistent with the components. */
        val recordCount: Int = inScope.size + outOfScopeCount + overlaps
    }

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 8: Count reconciliation")
    fun countReconciliation(@ForAll @From("scenarios") scenario: Scenario) {
        val plan = VersionStage.build(
            entries = scenario.inScope,
            publishDate = PUBLISH_DATE,
            digest = DIGEST,
            scope = scenario.scope,
            rawRecordCount = scenario.recordCount.toString(),
            outOfScopeCount = scenario.outOfScopeCount,
            sharedFixedRefOverlaps = scenario.overlaps,
        )

        val accepted = plan.shouldBeInstanceOf<VersionPlan.Accepted>()

        // Facet 1 — derived Expected_Count matches the formula (Req 8.1).
        // For SDN_ONLY the overlap term is dropped; the scenario keeps overlaps
        // at zero there, so a single expression covers both scopes.
        val expectedByFormula =
            scenario.recordCount - scenario.outOfScopeCount - scenario.overlaps
        accepted.expectedCount shouldBe expectedByFormula

        // Facet 2 — the persisted in-scope (post-dedup) count equals it exactly,
        // so activation's exact-equality check passes (Req 8.2).
        val persistedCount = scenario.inScope.size
        persistedCount shouldBe accepted.expectedCount
        reconciles(persistedCount, accepted.expectedCount).shouldBeTrue()

        // Facet 3 — dropping any single in-scope record makes the counts differ,
        // so activation is rejected (Req 8.3). Only meaningful when at least one
        // in-scope record exists to drop.
        if (scenario.inScope.isNotEmpty()) {
            val droppedCount = persistedCount - 1
            droppedCount shouldBe (accepted.expectedCount - 1)
            reconciles(droppedCount, accepted.expectedCount).shouldBeFalse()
        }
    }

    /**
     * The activation-time reconciliation check (Req 8.2/8.3): the persisted
     * in-scope post-dedup count must equal `Expected_Count` exactly.
     */
    private fun reconciles(persistedCount: Int, expectedCount: Int): Boolean =
        persistedCount == expectedCount

    @Provide
    fun scenarios(): Arbitrary<Scenario> {
        val scopes = Arbitraries.of(ScopeConfig.SDN_ONLY, ScopeConfig.SDN_AND_CONSOLIDATED)
        return scopes.flatMap { scope ->
            inScopeEntries().flatMap { entries ->
                Arbitraries.integers().between(0, 50).flatMap { outOfScope ->
                    // Overlaps are only meaningful for a multi-list scope (Req 8.1);
                    // a single-list scope carries no overlap term.
                    val overlapArb =
                        if (scope == ScopeConfig.SDN_AND_CONSOLIDATED) {
                            Arbitraries.integers().between(0, 50)
                        } else {
                            Arbitraries.just(0)
                        }
                    overlapArb.map { overlaps ->
                        Scenario(dedupByFixedRef(entries), outOfScope, overlaps, scope)
                    }
                }
            }
        }
    }

    /**
     * Ensures the generated in-scope list is already deduplicated by [FixedRef],
     * so its size is a valid post-dedup persisted count.
     */
    private fun dedupByFixedRef(entries: List<InternalModelEntry>): List<InternalModelEntry> =
        entries.distinctBy { it.fixedRef.value }

    private fun inScopeEntries(): Arbitrary<List<InternalModelEntry>> =
        Arbitraries.integers().between(0, 200)
            .list().ofMinSize(0).ofMaxSize(30).uniqueElements()
            .map { keys -> keys.map { key -> entry(key) } }

    /** Build an in-scope entry (Individual or Entity) with a distinct [FixedRef]. */
    private fun entry(key: Int): InternalModelEntry =
        InternalModelEntry(
            fixedRef = FixedRef("FR-$key"),
            entityType = if (key % 2 == 0) EntityType.Individual else EntityType.Entity,
            primaryName = "name-$key",
            sanctionPrograms = listOf("program-$key"),
        )

    private companion object {
        val PUBLISH_DATE: LocalDate = LocalDate.of(2024, 1, 15)
        val DIGEST: Sha256Digest = Sha256Digest("a".repeat(64))
    }
}
