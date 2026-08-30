package com.spike.ofac.adapter.out.persistence

import com.spike.ofac.application.port.`in`.EmptyQueryException
import com.spike.ofac.application.port.`in`.InvalidPaginationException
import com.spike.ofac.application.port.`in`.Page
import com.spike.ofac.application.port.`in`.QueryApi

import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.SourceList

/**
 * A lightweight, in-memory [QueryApi] used by the pagination and name-search
 * property tests (tasks 17.2 / 17.3) to exercise the **pure** query semantics —
 * ordering, pagination bounds, `total` counting, and case-insensitive contains
 * search — without a PostgreSQL `Data_Store`.
 *
 * It mirrors the documented contract of [com.spike.ofac.adapter.out.persistence.PgQueryApi]:
 *
 *  - It serves a single `CURRENT` record set (the [current] list), standing in for
 *    the `CURRENT` version resolved through the `VersionStore` pointer (Req 16.5).
 *    A `null`/empty `CURRENT` yields an empty page with `total` 0 (Req 16.4).
 *  - Records are ordered deterministically and stably by `FixedRef` ascending
 *    (Req 16.2), matching `PgQueryApi`'s `ORDER BY fixed_ref`.
 *  - Pagination bounds are validated identically (Req 16.8): `offset >= 0`,
 *    `limit > 0`, `limit <= `[QueryApi.MAX_LIMIT], via [InvalidPaginationException].
 *  - A blank search query raises [EmptyQueryException] (Req 16.7).
 *  - [Page.total] is the **full** match count in `CURRENT`, not just the page size
 *    (Req 16.1).
 *  - `searchByName` matches when the primary name **or** any alias name contains the
 *    query, case-insensitively (Req 16.3).
 *
 * This is a test helper only; it holds no database and never mutates its input,
 * so the "read-only" contract (Req 16.9) holds trivially.
 *
 * @param currentBySource the `CURRENT` record set for each [SourceList]. A list not
 *   present here (or mapped to an empty list) is treated as "no CURRENT yet".
 */
class InMemoryQueryApi(
    private val currentBySource: Map<SourceList, List<InternalModelEntry>>,
) : QueryApi {

    /** Convenience constructor for the common single-list case. */
    constructor(sourceList: SourceList, current: List<InternalModelEntry>) :
        this(mapOf(sourceList to current))

    override fun list(sourceList: SourceList, offset: Int, limit: Int): Page {
        validatePagination(offset, limit)
        val sorted = sortedCurrent(sourceList)
        return pageOf(sorted, offset, limit)
    }

    override fun searchByName(query: String, sourceList: SourceList, offset: Int, limit: Int): Page {
        if (query.isBlank()) throw EmptyQueryException()
        validatePagination(offset, limit)
        val needle = query.lowercase()
        val matches = sortedCurrent(sourceList).filter { matchesName(it, needle) }
        return pageOf(matches, offset, limit)
    }

    /**
     * The `CURRENT` record set for [sourceList], sorted by `FixedRef` ascending —
     * the stable, deterministic ordering the contract mandates (Req 16.2). Sorting a
     * distinct-`FixedRef` set is a total order, so the order is fully determined.
     */
    private fun sortedCurrent(sourceList: SourceList): List<InternalModelEntry> =
        (currentBySource[sourceList] ?: emptyList())
            .sortedBy { it.fixedRef.value }

    /**
     * Windows [sorted] by `offset`/`limit`, echoing the request metadata. [Page.total]
     * is the full size of the matching set regardless of the page (Req 16.1). A page
     * that starts at or past the end is empty but still a success (Req 16.4).
     */
    private fun pageOf(sorted: List<InternalModelEntry>, offset: Int, limit: Int): Page {
        val total = sorted.size.toLong()
        val records = sorted.drop(offset).take(limit)
        return Page(records, total, offset, limit)
    }

    /** Case-insensitive contains on the primary name OR any alias name (Req 16.3). */
    private fun matchesName(entry: InternalModelEntry, lowerNeedle: String): Boolean =
        entry.primaryName.lowercase().contains(lowerNeedle) ||
            entry.aliases.any { it.name.lowercase().contains(lowerNeedle) }

    /**
     * Validates pagination bounds identically to [PgQueryApi]: `offset >= 0`,
     * `limit > 0`, `limit <= `[QueryApi.MAX_LIMIT] (Req 16.8).
     */
    private fun validatePagination(offset: Int, limit: Int) {
        if (offset < 0) throw InvalidPaginationException("offset must be >= 0, was $offset")
        if (limit <= 0) throw InvalidPaginationException("limit must be > 0, was $limit")
        if (limit > QueryApi.MAX_LIMIT) {
            throw InvalidPaginationException("limit must be <= ${QueryApi.MAX_LIMIT}, was $limit")
        }
    }
}
