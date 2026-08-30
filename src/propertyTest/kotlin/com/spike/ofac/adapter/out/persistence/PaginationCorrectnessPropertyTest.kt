package com.spike.ofac.adapter.out.persistence

import com.spike.ofac.application.port.`in`.InvalidPaginationException
import com.spike.ofac.application.port.`in`.QueryApi

import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Tag

/**
 * Property 20: Pagination is deterministic, complete, and non-overlapping.
 *
 * The read-only `Query_API` (`list`) pages over the `CURRENT` record set in a
 * stable, deterministic order by `FixedRef` (Req 16.2), returning each page plus a
 * `total` equal to the full match count (Req 16.1) and rejecting out-of-bounds
 * pagination (Req 16.8). This property exercises those pure semantics against the
 * [InMemoryQueryApi] test helper, which mirrors `PgQueryApi`'s documented contract
 * (`ORDER BY fixed_ref`, `LIMIT/OFFSET`, `COUNT(*)` for `total`, identical bounds
 * validation) without needing a PostgreSQL `Data_Store`.
 *
 * For a generated `CURRENT` record set (distinct `FixedRef`s) it asserts:
 *
 *  1. **Stable slice.** Every requested `(offset, limit)` page equals the exact
 *     slice `sortedAll.drop(offset).take(limit)` of the `FixedRef`-sorted set — so
 *     the ordering is deterministic and each page respects `offset`/`limit`
 *     (page size `<= limit`), with `offset`/`limit` echoed in the metadata.
 *  2. **Complete & non-overlapping.** Walking the whole set with a fixed `limit`
 *     from `offset` 0 reconstructs exactly the sorted set — no gaps, no duplicates,
 *     pages non-overlapping (Req 16.1, 16.2).
 *  3. **Total is the full count.** Every page reports `total` == the full record
 *     count, independent of which page was requested (Req 16.1).
 *  4. **Invalid pagination rejected.** `offset < 0`, `limit <= 0`, and
 *     `limit > `[QueryApi.MAX_LIMIT] each raise [InvalidPaginationException]
 *     (Req 16.8).
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 20: Pagination is deterministic,
 * complete, and non-overlapping`. Min 100 iterations.
 *
 * **Validates: Requirements 16.1, 16.2, 16.8**
 */
@Tag(PropertyTests.FEATURE_TAG)
class PaginationCorrectnessPropertyTest {

    private val list = SourceList.SDN

    /**
     * A generated scenario: a `CURRENT` record set (distinct `FixedRef`s) and a
     * sequence of `(offset, limit)` page requests plus one fixed page size used to
     * walk the whole set.
     */
    data class Scenario(
        val current: List<InternalModelEntry>,
        val pages: List<Pair<Int, Int>>,
        val walkLimit: Int,
    )

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 20: Pagination is deterministic, complete, and non-overlapping")
    fun paginationIsDeterministicCompleteAndNonOverlapping(
        @ForAll @From("scenarios") scenario: Scenario,
    ) {
        val api = InMemoryQueryApi(list, scenario.current)

        // The single source of truth for the expected order: the CURRENT set sorted
        // by FixedRef ascending (Req 16.2). Distinct FixedRefs ⇒ a total order.
        val sortedAll = scenario.current.sortedBy { it.fixedRef.value }
        val total = sortedAll.size.toLong()

        // Facet 1 + 3 — each requested page is the exact stable slice, respects
        // offset/limit, echoes the metadata, and reports the full total (Req 16.1, 16.2).
        for ((offset, limit) in scenario.pages) {
            val page = api.list(list, offset, limit)

            page.records shouldContainExactly sortedAll.drop(offset).take(limit)
            page.records.size shouldBeLessThanOrEqual limit
            page.total shouldBe total
            page.offset shouldBe offset
            page.limit shouldBe limit
        }

        // Facet 2 — walking the whole set with a fixed limit from offset 0 covers
        // exactly the sorted set: complete, no gaps, no duplicates, non-overlapping.
        val walked = mutableListOf<InternalModelEntry>()
        var offset = 0
        while (offset < sortedAll.size) {
            val page = api.list(list, offset, scenario.walkLimit)
            // A non-final page is exactly `walkLimit` rows; the final page is the
            // remainder. Either way it never exceeds the limit.
            page.records.size shouldBeLessThanOrEqual scenario.walkLimit
            page.total shouldBe total
            walked += page.records
            offset += scenario.walkLimit
        }
        // Complete and duplicate-free: the concatenation of all pages equals the
        // sorted set exactly (order preserved ⇒ no gaps, no overlaps).
        walked shouldContainExactly sortedAll

        // A page requested at or past the end is empty but still a success with the
        // full total (Req 16.4 corollary of pagination completeness).
        val pastEnd = api.list(list, sortedAll.size, maxOf(1, scenario.walkLimit))
        pastEnd.records.size shouldBe 0
        pastEnd.total shouldBe total

        // Facet 4 — invalid pagination is a client error regardless of the data
        // (Req 16.8): negative offset, non-positive limit, and limit > MAX_LIMIT.
        shouldThrow<InvalidPaginationException> { api.list(list, -1, 50) }
        shouldThrow<InvalidPaginationException> { api.list(list, 0, 0) }
        shouldThrow<InvalidPaginationException> { api.list(list, 0, -1) }
        shouldThrow<InvalidPaginationException> { api.list(list, 0, QueryApi.MAX_LIMIT + 1) }
    }

    /**
     * Generates a `CURRENT` set of `0..40` records with **distinct** `FixedRef`s
     * (distinctness is what makes the sort a stable total order), paired with a
     * handful of in-bounds `(offset, limit)` requests and a fixed walk limit.
     *
     * Offsets are drawn to span before, within, and past the end of the data so the
     * page-slice assertions cover the boundary cases; limits stay within
     * `1..MAX_LIMIT` so the requests themselves are valid (invalid-bounds are
     * asserted separately, in-test).
     */
    @Provide
    fun scenarios(): Arbitrary<Scenario> {
        // Distinct FixedRefs: generate a set of distinct integer ids, then render
        // each as a zero-padded string so lexicographic FixedRef order is well-defined
        // and independent of numeric magnitude.
        val recordSets: Arbitrary<List<InternalModelEntry>> =
            Arbitraries.integers().between(0, 100_000)
                .set().ofMinSize(0).ofMaxSize(40)
                .map { ids -> ids.mapIndexed { index, id -> recordFor(id, index) } }

        return recordSets.flatMap { records ->
            val size = records.size
            // Offsets span [0 .. size + 2] to include past-the-end; limits in
            // [1 .. min(MAX_LIMIT, size + 5)] stay valid and small enough to page.
            val maxOffset = size + 2
            val maxLimit = minOf(QueryApi.MAX_LIMIT, size + 5).coerceAtLeast(1)
            val offsets = Arbitraries.integers().between(0, maxOffset)
            val limits = Arbitraries.integers().between(1, maxLimit)
            val pageRequests = offsets.flatMap { off -> limits.map { lim -> off to lim } }
                .list().ofMinSize(1).ofMaxSize(6)
            val walkLimits = Arbitraries.integers().between(1, maxLimit)

            pageRequests.flatMap { pages ->
                walkLimits.map { walk -> Scenario(records, pages, walk) }
            }
        }
    }

    /**
     * Builds one distinct in-scope record for a distinct [id]. [index] only varies
     * the primary name/program text so records are not identical objects; the
     * `FixedRef` (the ordering key) is derived solely from the distinct [id],
     * zero-padded for stable lexicographic ordering.
     */
    private fun recordFor(id: Int, index: Int): InternalModelEntry =
        InternalModelEntry(
            fixedRef = FixedRef("FR-%08d".format(id)),
            entityType = if (id % 2 == 0) EntityType.Individual else EntityType.Entity,
            primaryName = "name-$id-$index",
            sanctionPrograms = listOf("program-${id % 5}"),
        )

    init {
        // A guard so the constant used in facet 4 is meaningful.
        QueryApi.MAX_LIMIT shouldBeGreaterThanOrEqual 1
    }
}
