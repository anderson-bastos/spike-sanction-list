package com.spike.ofac.adapter.out.persistence

import com.spike.ofac.application.port.`in`.EmptyQueryException
import com.spike.ofac.application.port.`in`.InvalidPaginationException
import com.spike.ofac.application.port.`in`.Page
import com.spike.ofac.application.port.`in`.QueryApi

import com.spike.ofac.domain.model.Alias
import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.Tuple
import org.junit.jupiter.api.Tag

/**
 * Property 19: Name search matches primary name and aliases (case-insensitive
 * contains) over CURRENT.
 *
 * Validates: Requirements 16.3, 16.5
 *
 * Feature: ofac-sanctions-ingestion, Property 19: Name search matches primary name and aliases (case-insensitive contains) over CURRENT
 *
 * This property exercises [QueryApi.searchByName] against a lightweight in-memory
 * [QueryApi] ([InMemoryNameSearchApi]) that mirrors the documented behavior of the
 * concrete [PgQueryApi] without needing a database:
 *
 *  - case-insensitive **CONTAINS** on `primaryName` **OR** any `alias.name`
 *    (Req 16.3), and
 *  - it holds **only** the CURRENT record set, so "only CURRENT records are ever
 *    returned" (Req 16.5) is inherent by construction — a second, non-CURRENT
 *    record set is generated and must never appear.
 *
 * The core assertion is a **biconditional** over the full (all-pages-flattened)
 * result set: a record appears in the search results **iff** its `primaryName`
 * contains the query case-insensitively **OR** some `alias.name` contains the
 * query case-insensitively.
 */
@Tag(PropertyTests.FEATURE_TAG)
class NameSearchPropertyTest {

    private val list = SourceList.SDN

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 19: Name search matches primary name and aliases (case-insensitive contains) over CURRENT")
    fun nameSearchMatchesPrimaryNameAndAliasesOverCurrent(
        @ForAll @From("scenarios") scenario: Scenario,
    ) {
        val api = InMemoryNameSearchApi(current = scenario.current, list = list)
        val query = scenario.query

        // Flatten every page so the assertion covers the whole result set, not a
        // single window (pagination correctness itself is Property 20 / task 17.3).
        val returned = drainAll(api, query)

        // Req 16.5 - only CURRENT records may ever be returned. Because the
        // in-memory store holds only the CURRENT set, a returned record whose
        // FixedRef is not in CURRENT would be impossible; we still assert it
        // explicitly against the non-current "poison" set that must never appear.
        val currentRefs = scenario.current.map { it.fixedRef }.toSet()
        val nonCurrentRefs = scenario.nonCurrent.map { it.fixedRef }.toSet()
        returned.forEach { record ->
            (record.fixedRef in currentRefs) shouldBe true
            (record.fixedRef in nonCurrentRefs) shouldBe false
        }

        // Req 16.3 - the biconditional. For every CURRENT record, it is returned
        // iff its primary name OR some alias name contains the query
        // case-insensitively.
        val returnedRefs = returned.map { it.fixedRef }.toSet()
        scenario.current.forEach { record ->
            val expectedMatch = matchesCaseInsensitive(record, query)
            (record.fixedRef in returnedRefs) shouldBe expectedMatch
        }

        // total reflects the full match count in CURRENT (Req 16.1), and equals the
        // number of matching CURRENT records under the same semantics.
        val expectedTotal = scenario.current.count { matchesCaseInsensitive(it, query) }.toLong()
        api.searchByName(query, list).total shouldBe expectedTotal
    }

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 19: Name search matches primary name and aliases (case-insensitive contains) over CURRENT")
    fun blankQueryIsRejected(
        @ForAll @From("blankQueries") blank: String,
        @ForAll @From("scenarios") scenario: Scenario,
    ) {
        val api = InMemoryNameSearchApi(current = scenario.current, list = list)
        // Req 16.7 - a missing/empty (blank) query is a client error.
        shouldThrow<EmptyQueryException> { api.searchByName(blank, list) }
    }

    // --- helpers ---

    /** The reference name-search predicate: case-insensitive CONTAINS on primary name OR any alias name. */
    private fun matchesCaseInsensitive(record: InternalModelEntry, query: String): Boolean {
        val needle = query.lowercase()
        if (record.primaryName.lowercase().contains(needle)) return true
        return record.aliases.any { it.name.lowercase().contains(needle) }
    }

    /** Reads every page of [api] for [query] and flattens the results (Req 16.1 pagination). */
    private fun drainAll(api: QueryApi, query: String): List<InternalModelEntry> {
        val limit = 50
        val out = mutableListOf<InternalModelEntry>()
        var offset = 0
        while (true) {
            val page = api.searchByName(query, list, offset = offset, limit = limit)
            out += page.records
            offset += limit
            if (offset >= page.total || page.records.isEmpty()) break
        }
        return out
    }

    /**
     * A generated scenario: a CURRENT record set (distinct FixedRefs; primary and
     * alias names spanning mixed case and non-ASCII), a disjoint non-CURRENT set
     * that must never appear (Req 16.5), and a query that is *sometimes* a
     * differently-cased substring of some CURRENT name/alias and *sometimes*
     * arbitrary.
     */
    data class Scenario(
        val current: List<InternalModelEntry>,
        val nonCurrent: List<InternalModelEntry>,
        val query: String,
    )

    @Provide
    fun scenarios(): Arbitrary<Scenario> {
        // A pool of distinct FixedRef keys split into CURRENT and non-CURRENT so
        // the two sets never share a FixedRef.
        val keyPool: Arbitrary<List<Int>> =
            Arbitraries.integers().between(0, 400)
                .list().ofMinSize(0).ofMaxSize(24).uniqueElements()

        return keyPool.flatMap { keys ->
            Arbitraries.integers().between(0, keys.size).flatMap { splitAt ->
                val currentKeys = keys.subList(0, splitAt)
                val nonCurrentKeys = keys.subList(splitAt, keys.size)

                val currentArb: Arbitrary<List<InternalModelEntry>> = entriesFor(currentKeys)
                val nonCurrentArb: Arbitrary<List<InternalModelEntry>> = entriesFor(nonCurrentKeys)

                currentArb.flatMap { current ->
                    nonCurrentArb.flatMap { nonCurrent ->
                        queryFor(current).map { query ->
                            Scenario(current, nonCurrent, query)
                        }
                    }
                }
            }
        }
    }

    /** Builds a distinct-FixedRef record list for [keys], each with a generated name + aliases. */
    private fun entriesFor(keys: List<Int>): Arbitrary<List<InternalModelEntry>> {
        if (keys.isEmpty()) return Arbitraries.just(emptyList())
        return keys
            .map { key -> entryArb(key) }
            .let { arbs -> combineList(arbs) }
    }

    private fun entryArb(key: Int): Arbitrary<InternalModelEntry> {
        val nameArb = nameArb()
        val aliasesArb: Arbitrary<List<Alias>> =
            nameArb().map { Alias(name = it) }
                .list().ofMinSize(0).ofMaxSize(3)
        return nameArb.flatMap { primary ->
            aliasesArb.map { aliases ->
                InternalModelEntry(
                    fixedRef = FixedRef("FR-$key"),
                    entityType = if (key % 2 == 0) EntityType.Individual else EntityType.Entity,
                    primaryName = primary,
                    aliases = aliases,
                    sanctionPrograms = listOf("program-$key"),
                )
            }
        }
    }

    /**
     * Names spanning mixed case and non-ASCII. A small curated alphabet keeps
     * substring hits between the query and the names reasonably likely so the
     * "returned" branch of the biconditional is exercised, not only the "absent"
     * branch.
     */
    private fun nameArb(): Arbitrary<String> =
        Arbitraries.strings()
            .withChars(*NAME_CHARS)
            .ofMinLength(1)
            .ofMaxLength(10)

    /**
     * A query that is *sometimes* a differently-cased substring of some CURRENT
     * name/alias (guaranteeing matches occur) and *sometimes* an arbitrary string
     * (guaranteeing non-matches occur). Blank queries are excluded here — they are
     * covered by [blankQueryIsRejected].
     */
    private fun queryFor(current: List<InternalModelEntry>): Arbitrary<String> {
        val names = current.flatMap { listOf(it.primaryName) + it.aliases.map { a -> a.name } }
        val randomQuery: Arbitrary<String> =
            Arbitraries.strings().withChars(*NAME_CHARS).ofMinLength(1).ofMaxLength(5)

        if (names.isEmpty()) return randomQuery

        // A substring of some existing name, re-cased, so it matches under the
        // case-insensitive predicate but is rarely byte-identical.
        val substringQuery: Arbitrary<String> =
            Arbitraries.of(names).flatMap { name ->
                substringOf(name).map { recase(it) }
            }.filter { it.isNotBlank() }

        // Weight toward substrings so matches are common; keep a random slice so
        // non-matches are also exercised.
        return Arbitraries.frequencyOf(
            Tuple.of(3, substringQuery),
            Tuple.of(1, randomQuery),
        )
    }

    /** Picks a non-empty contiguous substring of [s]. */
    private fun substringOf(s: String): Arbitrary<String> {
        if (s.length <= 1) return Arbitraries.just(s)
        return Arbitraries.integers().between(0, s.length - 1).flatMap { start ->
            Arbitraries.integers().between(start + 1, s.length).map { end ->
                s.substring(start, end)
            }
        }
    }

    /** Flips the case of each character so matching relies on case-insensitivity, not equality. */
    private fun recase(s: String): String =
        s.map { c -> if (c.isUpperCase()) c.lowercaseChar() else c.uppercaseChar() }
            .joinToString("")

    /** Blank queries: empty, spaces, tabs/newlines — all must be rejected (Req 16.7). */
    @Provide
    fun blankQueries(): Arbitrary<String> =
        Arbitraries.of("", " ", "   ", "\t", "\n", " \t\n ")

    private companion object {
        // Mixed-case ASCII plus a handful of non-ASCII letters (accented + Cyrillic
        // + CJK) so names/queries exercise UTF-8 and case-folding behavior.
        val NAME_CHARS: CharArray = (
            ('a'..'f') + ('A'..'F') +
                listOf('é', 'É', 'ø', 'Ø', 'ü', 'Ü', 'ß', 'Я', 'ж', '李', '王', ' ', '-')
            ).toCharArray()

        /** Combines a list of element arbitraries into an arbitrary of the list, preserving order. */
        fun <T> combineList(arbs: List<Arbitrary<T>>): Arbitrary<List<T>> =
            arbs.fold(Arbitraries.just(emptyList())) { accArb, elemArb ->
                accArb.flatMap { acc -> elemArb.map { acc + it } }
            }
    }
}

/**
 * A lightweight in-memory [QueryApi] that mirrors the documented behavior of the
 * concrete [PgQueryApi] for the *pure* name-search semantics, without a database.
 *
 * It holds **only** the CURRENT record set for a single [list], so "only CURRENT
 * records are ever returned" (Req 16.5) is inherent. Its [searchByName]:
 *
 *  - rejects a blank query with [EmptyQueryException] (Req 16.7),
 *  - validates pagination bounds (Req 16.8),
 *  - filters by case-insensitive CONTAINS on `primaryName` OR any `alias.name`
 *    (Req 16.3),
 *  - orders deterministically by `fixedRef` (Req 16.2), and
 *  - returns `total` as the full match count with an offset/limit window (Req 16.1).
 *
 * Defined as a top-level class here (task 17.2); task 17.3 uses its own helper, so
 * there is no duplicate-class conflict.
 */
private class InMemoryNameSearchApi(
    current: List<InternalModelEntry>,
    private val list: SourceList,
) : QueryApi {

    // Deterministic FixedRef ordering (Req 16.2), fixed once at construction.
    private val ordered: List<InternalModelEntry> = current.sortedBy { it.fixedRef.value }

    override fun list(sourceList: SourceList, offset: Int, limit: Int): Page {
        validatePagination(offset, limit)
        if (sourceList != list) return Page.empty(offset, limit)
        return page(ordered, offset, limit)
    }

    override fun searchByName(query: String, sourceList: SourceList, offset: Int, limit: Int): Page {
        if (query.isBlank()) throw EmptyQueryException()
        validatePagination(offset, limit)
        if (sourceList != list) return Page.empty(offset, limit)

        val needle = query.lowercase()
        val matches = ordered.filter { record ->
            record.primaryName.lowercase().contains(needle) ||
                record.aliases.any { it.name.lowercase().contains(needle) }
        }
        return page(matches, offset, limit)
    }

    private fun page(matches: List<InternalModelEntry>, offset: Int, limit: Int): Page {
        val total = matches.size.toLong()
        if (offset >= total) return Page(emptyList(), total, offset, limit)
        val window = matches.drop(offset).take(limit)
        return Page(window, total, offset, limit)
    }

    private fun validatePagination(offset: Int, limit: Int) {
        if (offset < 0) throw InvalidPaginationException("offset must be >= 0, was $offset")
        if (limit <= 0) throw InvalidPaginationException("limit must be > 0, was $limit")
        if (limit > QueryApi.MAX_LIMIT) {
            throw InvalidPaginationException("limit must be <= ${QueryApi.MAX_LIMIT}, was $limit")
        }
    }
}
