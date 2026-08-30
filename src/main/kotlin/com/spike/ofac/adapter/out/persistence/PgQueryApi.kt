package com.spike.ofac.adapter.out.persistence

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.spike.ofac.domain.model.Address
import com.spike.ofac.domain.model.Alias
import com.spike.ofac.domain.model.Document
import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.PartialDate
import com.spike.ofac.domain.model.Relationship
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.application.port.`in`.EmptyQueryException
import com.spike.ofac.application.port.`in`.InvalidPaginationException
import com.spike.ofac.application.port.`in`.Page
import com.spike.ofac.application.port.`in`.QueryApi
import com.spike.ofac.application.port.out.PointerKind
import com.spike.ofac.application.port.out.VersionStore
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

/**
 * PostgreSQL-backed [QueryApi] (task 17.1), reading only the `CURRENT` version of
 * a [SourceList] from the `records` table of the local `Data_Store`.
 *
 * `CURRENT` is resolved through the [VersionStore] pointer — never the `PREVIOUS`,
 * `N_MINUS_2`, or any `COLD` version (Req 16.5). Every read is scoped by the
 * resolved `(publish_date, digest)` `version_id`, so:
 *
 *  - it serves only that one version (Req 16.5), and
 *  - it observes activation atomically: the pointer read resolves to exactly one
 *    fully-persisted `version_id`, and the subsequent record reads are scoped to
 *    that id — so a concurrent activation is seen as either the old or the new
 *    `CURRENT`, never a mix (Req 16.6). Wrapping the pointer resolve + reads in a
 *    single read-only transaction gives that read a consistent snapshot.
 *
 * The store is strictly **read-only**: it issues only `SELECT`s and never writes,
 * so it can never modify a `Version`, pointer, or record (Req 16.9).
 *
 * **Reconstructing entries.** Each record row's JSONB columns are Jackson-deserialized
 * back into the [InternalModelEntry] value types, mirroring how [com.spike.ofac.adapter.out.persistence.PgVersionStore]
 * serialized them. The entry is stamped with the resolved [VersionId] so a caller
 * can see which version it came from.
 *
 * @param jdbc named-parameter JDBC access to the local PostgreSQL Data_Store.
 * @param versionStore resolves the `CURRENT` pointer for a source list (Req 16.5).
 * @param objectMapper Jackson mapper (Kotlin-module aware) used to deserialize the
 *   multi-valued JSONB attributes back into the domain value types.
 */
@Service
class PgQueryApi(
    private val jdbc: NamedParameterJdbcTemplate,
    private val versionStore: VersionStore,
    objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule(),
) : QueryApi {

    // A Kotlin-aware mapper is required so data classes with default arguments
    // round-trip (parity with PgVersionStore's serialization side).
    private val mapper: ObjectMapper = objectMapper.copy().registerKotlinModule()

    @Transactional(readOnly = true)
    override fun list(sourceList: SourceList, offset: Int, limit: Int): Page {
        validatePagination(offset, limit)
        val current = currentVersion(sourceList) ?: return Page.empty(offset, limit)
        // No name predicate: page over the whole CURRENT version.
        return pageOf(current, offset, limit, filterSql = "", extraParams = emptyMap())
    }

    @Transactional(readOnly = true)
    override fun searchByName(query: String, sourceList: SourceList, offset: Int, limit: Int): Page {
        if (query.isBlank()) throw EmptyQueryException()
        validatePagination(offset, limit)
        val current = currentVersion(sourceList) ?: return Page.empty(offset, limit)

        // Case-insensitive CONTAINS on the primary name OR any alias. The schema keeps
        // two lowercased, trigram-indexed columns (primary_name_lower, alias_search)
        // for exactly this (Req 16.3). Escape LIKE metacharacters so a query such as
        // "50%" matches literally rather than as a wildcard.
        val needle = "%" + escapeLike(query.lowercase()) + "%"
        val filterSql = " AND (primary_name_lower LIKE :needle ESCAPE '\\' OR alias_search LIKE :needle ESCAPE '\\')"
        return pageOf(current, offset, limit, filterSql, mapOf("needle" to needle))
    }

    // --- internals ---

    /** Resolves the CURRENT version_id for [sourceList], or null when none exists (Req 16.4, 16.5). */
    private fun currentVersion(sourceList: SourceList): VersionId? =
        versionStore.getPointer(sourceList, PointerKind.CURRENT)

    /**
     * Builds a [Page] over the CURRENT version scoped by [current], applying the
     * optional [filterSql] predicate (empty for [list]). Runs a COUNT for [Page.total]
     * (the full match count in CURRENT, Req 16.1) and a windowed SELECT ordered by
     * `fixed_ref` for the page rows (Req 16.2).
     */
    private fun pageOf(
        current: VersionId,
        offset: Int,
        limit: Int,
        filterSql: String,
        extraParams: Map<String, Any>,
    ): Page {
        val params = MapSqlParameterSource()
            .addValue("publishDate", java.sql.Date.valueOf(current.publishDate))
            .addValue("digest", current.digest.value)
        extraParams.forEach { (k, v) -> params.addValue(k, v) }

        val total = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM records
             WHERE publish_date = :publishDate AND digest = :digest$filterSql
            """.trimIndent(),
            params,
            Long::class.java,
        ) ?: 0L

        // Short-circuit: nothing matches, or the requested page starts past the end.
        if (total == 0L || offset >= total) return Page(emptyList(), total, offset, limit)

        val pageParams = MapSqlParameterSource()
            .addValues(params.values)
            .addValue("limit", limit)
            .addValue("offset", offset)
        val records = jdbc.query(
            """
            SELECT * FROM records
             WHERE publish_date = :publishDate AND digest = :digest$filterSql
             ORDER BY fixed_ref
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
            pageParams,
        ) { rs, _ -> mapRecordRow(rs, current) }

        return Page(records, total, offset, limit)
    }

    /**
     * Validates pagination bounds (Req 16.8). Non-numeric values are rejected before
     * this by Spring's parameter binding; here we enforce the numeric bounds:
     * offset `>= 0`, limit `> 0`, and limit `<= `[QueryApi.MAX_LIMIT].
     */
    private fun validatePagination(offset: Int, limit: Int) {
        if (offset < 0) throw InvalidPaginationException("offset must be >= 0, was $offset")
        if (limit <= 0) throw InvalidPaginationException("limit must be > 0, was $limit")
        if (limit > QueryApi.MAX_LIMIT) {
            throw InvalidPaginationException("limit must be <= ${QueryApi.MAX_LIMIT}, was $limit")
        }
    }

    /**
     * Escapes the SQL `LIKE` metacharacters (`%`, `_`, and the escape char `\`) so a
     * user query is matched as a literal substring, not a wildcard pattern.
     */
    private fun escapeLike(raw: String): String =
        raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** Reconstructs an [InternalModelEntry] from a `records` row, stamped with [versionId]. */
    private fun mapRecordRow(rs: ResultSet, versionId: VersionId): InternalModelEntry =
        InternalModelEntry(
            fixedRef = FixedRef(rs.getString("fixed_ref")),
            entityType = EntityType.valueOf(rs.getString("entity_type")),
            primaryName = rs.getString("primary_name"),
            aliases = readList(rs.getString("aliases")),
            addresses = readList(rs.getString("addresses")),
            documents = readList(rs.getString("documents")),
            nationalities = readList(rs.getString("nationalities")),
            citizenships = readList(rs.getString("citizenships")),
            birthDates = readList(rs.getString("birth_dates")),
            sanctionPrograms = readList(rs.getString("sanction_programs")),
            remarks = readList(rs.getString("remarks")),
            relationships = readList(rs.getString("relationships")),
            versionId = versionId,
        )

    private inline fun <reified T> readList(json: String?): List<T> {
        if (json.isNullOrBlank()) return emptyList()
        return mapper.readValue(json, object : TypeReference<List<T>>() {})
    }

    private fun MapSqlParameterSource.addValues(other: Map<String, Any?>): MapSqlParameterSource {
        other.forEach { (k, v) -> addValue(k, v) }
        return this
    }
}
