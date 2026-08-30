package com.spike.ofac.application.port.`in`

import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.SourceList

/**
 * Read-only query contract over the `Data_Store`, serving **only** the `CURRENT`
 * version of each configured [SourceList] (Req 16).
 *
 * Both operations resolve `CURRENT` through the `VersionStore`/`Data_Store`
 * pointer and read only from it — never `PREVIOUS`, `N_MINUS_2`, or any `COLD`
 * version (Req 16.5) — and observe activation atomically: a read is served fully
 * from the old or the new `CURRENT`, never a partial dataset (Req 16.6). The
 * contract never mutates any `Version`, pointer, or record (Req 16.9).
 *
 * Design contract (`design.md` "QueryApi"):
 * ```
 * interface QueryApi:
 *   list(source_list?, offset = 0, limit = 50) -> Page
 *   search_by_name(query, source_list?, offset = 0, limit = 50) -> Page
 * Page = { records: [InternalModelEntry], total: int, offset: int, limit: int }
 * ```
 */
interface QueryApi {

    /**
     * Returns `In_Scope_Records` from the `CURRENT` version of [sourceList] using
     * offset/limit pagination, in a deterministic, stable order by `FixedRef`
     * (Req 16.1, 16.2).
     *
     * When [sourceList] has no `CURRENT` version yet, or the requested page is past
     * the end of the data, the returned [Page] is empty with a `total` of 0 — a
     * success, not an error (Req 16.4).
     *
     * @param sourceList the list to read `CURRENT` from.
     * @param offset zero-based row offset; must be `>= 0` (Req 16.8).
     * @param limit page size; must be `> 0` and `<= `[MAX_LIMIT] (Req 16.8).
     * @throws InvalidPaginationException when [offset] or [limit] is out of bounds
     *   (Req 16.8).
     */
    fun list(
        sourceList: SourceList,
        offset: Int = DEFAULT_OFFSET,
        limit: Int = DEFAULT_LIMIT,
    ): Page

    /**
     * Returns `In_Scope_Records` from the `CURRENT` version of [sourceList] whose
     * primary name **or** any alias contains [query] (case-insensitive), with the
     * same pagination, bounds, ordering, and metadata as [list] (Req 16.3).
     *
     * @param query the non-blank search string (Req 16.7).
     * @param sourceList the list to read `CURRENT` from.
     * @param offset zero-based row offset; must be `>= 0` (Req 16.8).
     * @param limit page size; must be `> 0` and `<= `[MAX_LIMIT] (Req 16.8).
     * @throws EmptyQueryException when [query] is blank/empty (Req 16.7).
     * @throws InvalidPaginationException when [offset] or [limit] is out of bounds
     *   (Req 16.8).
     */
    fun searchByName(
        query: String,
        sourceList: SourceList,
        offset: Int = DEFAULT_OFFSET,
        limit: Int = DEFAULT_LIMIT,
    ): Page

    companion object {
        /** Default row offset when the client omits it. */
        const val DEFAULT_OFFSET: Int = 0

        /** Configurable default page size (Req 16.1). */
        const val DEFAULT_LIMIT: Int = 50

        /** Bounded maximum page size; a larger `limit` is a client error (Req 16.1, 16.8). */
        const val MAX_LIMIT: Int = 1000
    }
}

/**
 * A single page of query results plus its pagination metadata (`design.md` `Page`).
 *
 * An empty page with [total] `0` is the success response when nothing matches or
 * a list has no `CURRENT` version yet (Req 16.4).
 *
 * @property records the `In_Scope_Records` on this page, in stable `FixedRef`
 *   order (Req 16.2).
 * @property total the full count of matching records in `CURRENT` (not just this
 *   page), so a client can compute how many pages exist (Req 16.1).
 * @property offset the row offset this page starts at (echoes the request).
 * @property limit the page size this page was bounded by (echoes the request).
 */
data class Page(
    val records: List<InternalModelEntry>,
    val total: Long,
    val offset: Int,
    val limit: Int,
) {
    companion object {
        /** An empty page with `total` 0 (Req 16.4). */
        fun empty(offset: Int, limit: Int): Page = Page(emptyList(), 0, offset, limit)
    }
}

/**
 * Raised when the name-search query is missing or blank (Req 16.7). Mapped to a
 * `400 Bad Request` by the controller.
 */
class EmptyQueryException(message: String = "search query must not be empty") :
    IllegalArgumentException(message)

/**
 * Raised when pagination parameters are out of bounds — negative offset, non-positive
 * limit, or a limit exceeding [QueryApi.MAX_LIMIT] (Req 16.8). Mapped to a
 * `400 Bad Request` by the controller. Non-numeric params are rejected earlier by
 * Spring's type conversion (also a `400`).
 */
class InvalidPaginationException(message: String) : IllegalArgumentException(message)
