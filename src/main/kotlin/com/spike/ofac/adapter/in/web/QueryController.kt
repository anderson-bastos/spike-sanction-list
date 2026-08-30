package com.spike.ofac.adapter.`in`.web

import com.spike.ofac.application.port.`in`.EmptyQueryException
import com.spike.ofac.application.port.`in`.InvalidPaginationException
import com.spike.ofac.application.port.`in`.Page
import com.spike.ofac.application.port.`in`.QueryApi
import com.spike.ofac.domain.model.SourceList
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Read-only HTTP surface over the [QueryApi] (task 17.1, Spring Web).
 *
 * Two endpoints, both serving **only** the `CURRENT` version of the addressed
 * [SourceList] (Req 16.5):
 *
 *  - `GET /api/{sourceList}/records` — paginated list (Req 16.1, 16.2).
 *  - `GET /api/{sourceList}/records/search?q=...` — case-insensitive contains
 *    name search over primary name + aliases (Req 16.3).
 *
 * Both accept `offset` (default 0) and `limit` (default 50, max 1000) query
 * params and return a [Page] as JSON with pagination metadata (Req 16.1). A page
 * that matches nothing — including a list with no `CURRENT` yet — is a `200 OK`
 * with an empty page and `total` 0, not an error (Req 16.4).
 *
 * Client errors are mapped to `400 Bad Request`: a missing/blank `q` (Req 16.7),
 * out-of-bounds pagination (Req 16.8), a non-numeric `offset`/`limit` (Spring type
 * conversion, Req 16.8), and an unknown `{sourceList}` path segment.
 *
 * The controller only ever calls the read-only [QueryApi]; it never writes, so it
 * cannot modify any `Version`, pointer, or record (Req 16.9).
 */
@RestController
@RequestMapping("/api/{sourceList}/records")
class QueryController(
    private val queryApi: QueryApi,
) {

    /** Paginated list over the CURRENT version of [sourceList] (Req 16.1, 16.2, 16.4). */
    @GetMapping
    fun list(
        @PathVariable sourceList: SourceList,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int,
    ): Page = queryApi.list(sourceList, offset, limit)

    /** Case-insensitive contains name search over the CURRENT version (Req 16.3, 16.7). */
    @GetMapping("/search")
    fun search(
        @PathVariable sourceList: SourceList,
        @RequestParam(name = "q", required = false) q: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int,
    ): Page {
        // A missing `q` param is a client error (Req 16.7); blank `q` is rejected by
        // the QueryApi itself (also Req 16.7).
        if (q == null) throw EmptyQueryException("search query parameter 'q' is required")
        return queryApi.searchByName(q, sourceList, offset, limit)
    }

    // --- client-error mapping (Req 16.7, 16.8) ---

    /** Missing/empty search query -> 400 (Req 16.7). */
    @ExceptionHandler(EmptyQueryException::class)
    fun onEmptyQuery(e: EmptyQueryException): ResponseEntity<ApiError> =
        badRequest(e.message ?: "search query must not be empty")

    /** Out-of-bounds pagination (negative offset, non-positive limit, limit>max) -> 400 (Req 16.8). */
    @ExceptionHandler(InvalidPaginationException::class)
    fun onInvalidPagination(e: InvalidPaginationException): ResponseEntity<ApiError> =
        badRequest(e.message ?: "invalid pagination parameters")

    /**
     * Non-numeric `offset`/`limit` or an unknown `{sourceList}` value fail Spring's
     * type conversion -> 400 (Req 16.8, and unknown source list as a client error).
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ApiError> =
        badRequest("invalid value for parameter '${e.name}': ${e.value}")

    private fun badRequest(message: String): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError(message))

    /** Minimal client-error body returned with a `400`. */
    data class ApiError(val error: String)
}
