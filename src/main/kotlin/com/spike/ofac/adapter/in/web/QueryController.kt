package com.spike.ofac.adapter.`in`.web

import com.spike.ofac.adapter.web.generated.api.QueryContractApi
import com.spike.ofac.adapter.web.generated.model.Page as PageDto
import com.spike.ofac.application.port.`in`.EmptyQueryException
import com.spike.ofac.application.port.`in`.InvalidPaginationException
import com.spike.ofac.application.port.`in`.QueryApi
import com.spike.ofac.domain.model.SourceList
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * Read-only HTTP surface over the [QueryApi] port (task 17.1) — now realized
 * **spec-first** (task 24.6): it **implements the interface generated from
 * `openapi.yaml`** ([QueryContractApi]) and returns the contract's generated
 * DTOs, mapping them from the domain via [QueryDtoMapper].
 *
 * Because the routes, parameters, and response shapes come from the generated
 * interface + DTOs, `openapi.yaml` is the **compile-time authority**: if the code
 * drifts from the contract this class no longer compiles. That complements the
 * runtime `OpenApiContractTest`, which compares the springdoc-served document to
 * the committed contract.
 *
 * Behavior is unchanged from the original controller (Req 16):
 *  - `GET /api/{sourceList}/records` — paginated list over `CURRENT` (Req 16.1, 16.2).
 *  - `GET /api/{sourceList}/records/search?q=` — case-insensitive contains name
 *    search over primary name + aliases, over `CURRENT` (Req 16.3).
 *  - Empty/no-CURRENT → `200` empty page, `total` 0 (Req 16.4); missing/blank `q`
 *    (Req 16.7), out-of-bounds pagination (Req 16.8), and an unknown `{sourceList}`
 *    are `400` client errors. The API only reads — it never mutates (Req 16.9).
 *
 * The generated interface types `sourceList` as a `String`, so this controller
 * parses it into the [SourceList] enum and maps an unknown value to the same
 * client-error path (Req 16.8).
 */
@RestController
class QueryController(
    private val queryApi: QueryApi,
) : QueryContractApi {

    override fun list(sourceList: String, offset: Int, limit: Int): ResponseEntity<PageDto> {
        val page = queryApi.list(parseSourceList(sourceList), offset, limit)
        return ResponseEntity.ok(QueryDtoMapper.toDto(page))
    }

    override fun search(sourceList: String, q: String?, offset: Int, limit: Int): ResponseEntity<PageDto> {
        // A missing `q` is a client error (Req 16.7); a blank `q` is rejected by the
        // QueryApi port itself (also Req 16.7).
        if (q == null) throw EmptyQueryException("search query parameter 'q' is required")
        val page = queryApi.searchByName(q, parseSourceList(sourceList), offset, limit)
        return ResponseEntity.ok(QueryDtoMapper.toDto(page))
    }

    /**
     * Parses the contract's `String` path segment into the [SourceList] enum;
     * an unknown value is a client error (`400`), consistent with the previous
     * enum-typed binding.
     */
    private fun parseSourceList(raw: String): SourceList =
        runCatching { SourceList.valueOf(raw) }
            .getOrElse { throw UnknownSourceListException(raw) }

    // --- client-error mapping (Req 16.7, 16.8) ---

    /** Missing/empty search query -> 400 (Req 16.7). */
    @ExceptionHandler(EmptyQueryException::class)
    fun onEmptyQuery(e: EmptyQueryException): ResponseEntity<ApiError> =
        badRequest(e.message ?: "search query must not be empty")

    /** Out-of-bounds pagination (negative offset, non-positive limit, limit>max) -> 400 (Req 16.8). */
    @ExceptionHandler(InvalidPaginationException::class)
    fun onInvalidPagination(e: InvalidPaginationException): ResponseEntity<ApiError> =
        badRequest(e.message ?: "invalid pagination parameters")

    /** Unknown `{sourceList}` -> 400. */
    @ExceptionHandler(UnknownSourceListException::class)
    fun onUnknownSourceList(e: UnknownSourceListException): ResponseEntity<ApiError> =
        badRequest(e.message ?: "unknown source list")

    /** Non-numeric `offset`/`limit` fail Spring's type conversion -> 400 (Req 16.8). */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun onTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ApiError> =
        badRequest("invalid value for parameter '${e.name}': ${e.value}")

    private fun badRequest(message: String): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError(message))

    /** Minimal client-error body returned with a `400` (matches the contract's ApiError schema). */
    data class ApiError(val error: String)
}

/** Raised when the `{sourceList}` path segment is not a known [SourceList] (client error). */
class UnknownSourceListException(raw: String) :
    IllegalArgumentException("unknown sourceList '$raw' (expected SDN or CONSOLIDATED)")
