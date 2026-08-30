package com.spike.ofac.pipeline.stages.transform

import com.spike.ofac.pipeline.models.EntityType
import com.spike.ofac.pipeline.models.FixedRef
import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.ScopeConfig
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Tag

/**
 * Property 4: Deduplication produces the distinct union.
 *
 * Validates: Requirements 6.1, 6.2, 6.3
 *
 * Feature: ofac-sanctions-ingestion, Property 4: Deduplication produces the distinct union
 *
 * This property exercises [CrossListDedup.deduplicate] with
 * [ScopeConfig.SDN_AND_CONSOLIDATED] over generated SDN/Consolidated record-set
 * pairs that share a *controlled* number of `FixedRef`s. To tell apart the SDN
 * and Consolidated "representation" of the same `FixedRef`, the two lists give
 * the same `FixedRef` a *different* `primaryName`, so retaining the SDN form
 * (Req 6.2) is observable.
 *
 * The property asserts:
 *  - **6.1** exactly one record per distinct `FixedRef` (no duplicate keys), and
 *  - **6.2** every shared `FixedRef` resolves to its SDN representation, and
 *  - **6.3** the persisted count equals the distinct-union size, which is
 *    `<=` the naive sum of the two list sizes and equal only when there is no
 *    overlap.
 */
@Tag(PropertyTests.FEATURE_TAG)
class CrossListDedupPropertyTest {

    @Property(tries = PropertyTests.MIN_TRIES)
    fun deduplicationProducesTheDistinctUnion(
        @ForAll @From("recordSetPairs") pair: RecordSetPair,
    ) {
        val sdn = pair.sdnRecords
        val consolidated = pair.consolidatedRecords

        val result =
            CrossListDedup.deduplicate(
                sdnRecords = sdn,
                consolidatedRecords = consolidated,
                scope = ScopeConfig.SDN_AND_CONSOLIDATED,
            )

        val resultKeys = result.map { it.fixedRef }

        // Req 6.1 - exactly one record per distinct FixedRef (no duplicate keys).
        resultKeys.toSet().size shouldBe resultKeys.size

        // Req 6.1 / 6.3 - the key set is exactly the distinct union of both lists.
        val distinctUnion = (sdn.map { it.fixedRef } + consolidated.map { it.fixedRef }).toSet()
        resultKeys.toSet() shouldBe distinctUnion

        // Req 6.3 - persisted count == distinct-union size, which is <= the naive
        // sum, and equal to the sum only when the two lists do not overlap.
        val naiveSum = sdn.size + consolidated.size
        result.size shouldBe distinctUnion.size
        result.size shouldBeLessThanOrEqual naiveSum
        val overlapCount = sdn.map { it.fixedRef }.toSet().intersect(consolidated.map { it.fixedRef }.toSet()).size
        (result.size == naiveSum) shouldBe (overlapCount == 0)

        // Req 6.2 - every record in the result equals its SDN form when the
        // FixedRef exists in SDN; Consolidated-exclusive FixedRefs keep their
        // Consolidated form. Because shared FixedRefs are given a *different*
        // primaryName in each list, retaining the SDN representation is
        // observable field-by-field.
        val sdnByRef = sdn.associateBy { it.fixedRef }
        val consolidatedByRef = consolidated.associateBy { it.fixedRef }
        result.forEach { record ->
            val expected = sdnByRef[record.fixedRef] ?: consolidatedByRef.getValue(record.fixedRef)
            record shouldBe expected
        }
    }

    /**
     * A generated SDN/Consolidated pair with a controlled overlap. Both lists are
     * within-list distinct by `FixedRef`; overlapping keys carry a *different*
     * `primaryName` between the two lists so SDN precedence is observable.
     */
    data class RecordSetPair(
        val sdnRecords: List<InternalModelEntry>,
        val consolidatedRecords: List<InternalModelEntry>,
    )

    @Provide
    fun recordSetPairs(): Arbitrary<RecordSetPair> {
        // A pool of distinct FixedRef keys to partition into SDN-only,
        // Consolidated-only, and shared buckets, guaranteeing controlled overlap.
        val keyPool: Arbitrary<List<Int>> =
            Arbitraries.integers().between(0, 200)
                .list().ofMinSize(0).ofMaxSize(30).uniqueElements()

        return keyPool.flatMap { keys ->
            // Split the shuffled pool into three disjoint buckets:
            // [0, sdnOnlyEnd) SDN-only, [sdnOnlyEnd, sharedEnd) shared,
            // [sharedEnd, size) Consolidated-only.
            val size = keys.size
            splitPoints(size).map { (sdnOnlyEnd, sharedEnd) ->
                val sdnOnlyKeys = keys.subList(0, sdnOnlyEnd)
                val sharedKeys = keys.subList(sdnOnlyEnd, sharedEnd)
                val consolidatedOnlyKeys = keys.subList(sharedEnd, size)

                val sdnRecords =
                    (sdnOnlyKeys + sharedKeys).map { key -> entry(key, source = "SDN") }
                val consolidatedRecords =
                    (sharedKeys + consolidatedOnlyKeys).map { key -> entry(key, source = "CONS") }

                RecordSetPair(sdnRecords, consolidatedRecords)
            }
        }
    }

    /**
     * Pick two split points `0 <= a <= b <= size` partitioning `[0, size)` into
     * three disjoint contiguous buckets. `a` is the SDN-only/shared boundary and
     * `b` is the shared/Consolidated-only boundary, so `[a, b)` are the shared
     * keys — the controlled overlap.
     */
    private fun splitPoints(size: Int): Arbitrary<Pair<Int, Int>> =
        Arbitraries.integers().between(0, size).flatMap { x ->
            Arbitraries.integers().between(0, size).map { y ->
                if (x <= y) x to y else y to x
            }
        }

    /**
     * Build an in-scope entry for [key]. The [source] tag is baked into the
     * `primaryName` so the SDN and Consolidated representation of a shared
     * `FixedRef` differ, making SDN precedence (Req 6.2) observable.
     */
    private fun entry(key: Int, source: String): InternalModelEntry =
        InternalModelEntry(
            fixedRef = FixedRef("FR-$key"),
            entityType = if (key % 2 == 0) EntityType.Individual else EntityType.Entity,
            primaryName = "$source-name-$key",
            sanctionPrograms = listOf("$source-program-$key"),
        )
}
