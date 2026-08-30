package com.spike.ofac.domain.model

/**
 * Internal data models and value types produced by the pipeline.
 *
 * This file (task 2.1) defines the normalized entry produced by `transform`
 * and its value types: [InternalModelEntry], [Alias], [Address], [Document],
 * [PartialDate], [Relationship], and [Diagnostic].
 *
 * The version-identity / metadata / pointer types and the configuration types
 * ([VersionId], `VersionMetadata`, `VersionPointers`, `ScopeConfig`,
 * `RetentionPolicy`) are defined by task 2.2 in this same package.
 *
 * Modeling notes (grounded in design.md "Data Models", `spike` §3–§5):
 *  - Multi-valued attributes are 0..N (Req 4.4). `sanction_programs` is 1..N —
 *    at least one is required and this is enforced in `init`.
 *  - `primary_name` is required (Req 4.5) and used as the display name when a
 *    record has zero aliases.
 *  - `entity_type` is modeled as in-scope only: `Individual` | `Entity` (Req 5).
 *    Vessel / Aircraft are never representable here.
 *  - UTF-8 strings are preserved verbatim (Req 4.3): no normalization,
 *    trimming, or case folding is applied to any string field.
 *  - [PartialDate] requires at least one of `year` / `period` (Req 4.6);
 *    partial birth dates are never rejected.
 */

/**
 * Stable OFAC identifier (`FixedRef`, == `uid`). Used as the deduplication key
 * across lists (Req 6) and as the cross-version key (Req 4.1). Kept as a plain
 * string wrapper so it is a distinct type from other opaque identifiers.
 */
@JvmInline
value class FixedRef(val value: String) {
    init {
        require(value.isNotEmpty()) { "FixedRef must not be empty" }
    }
}

/**
 * The in-scope entity types (Req 5). Vessel and Aircraft are intentionally not
 * part of this enum: an [InternalModelEntry] can only ever be an individual or
 * an entity, so out-of-scope types are unrepresentable by construction.
 */
enum class EntityType {
    Individual,
    Entity,
}

/**
 * The OFAC alias "Category" column (Req: alias category). In the Advanced XML it
 * is carried by `Alias/@LowQuality`: a low-quality alias is a **weak** match, a
 * high-quality one is **strong**. Aliases with no flag default to [STRONG].
 */
enum class AliasCategory {
    STRONG,
    WEAK,
}

/**
 * An alternate name for a party. `type` is the source-provided alias category
 * (e.g. "aka", "fka") when present. `is_primary` marks the alias that mirrors
 * the [InternalModelEntry.primaryName]. `category` is the OFAC strong/weak
 * classification (`Alias/@LowQuality`), defaulting to [AliasCategory.STRONG].
 */
data class Alias(
    val name: String,
    val type: String? = null,
    val isPrimary: Boolean = false,
    val category: AliasCategory = AliasCategory.STRONG,
)

/**
 * A party address. `raw` is the source's rendered address string, preserved
 * verbatim (Req 4.3). `country` is the resolved country when available and
 * `parts` holds any structured components the source exposed.
 */
data class Address(
    val raw: String,
    val country: String? = null,
    val parts: Map<String, String> = emptyMap(),
)

/**
 * An identification document (`IDRegDocument`). `type` is required; `number`
 * and `issuer` are optional because the source frequently omits them.
 */
data class Document(
    val type: String,
    val number: String? = null,
    val issuer: String? = null,
)

/**
 * A (possibly incomplete) date, used for birth dates (Req 4.6).
 *
 * Two shapes are supported and preserved as-is:
 *  - a single (partial) calendar date via [year] / [month] / [day]; or
 *  - a range via [period] (a `DatePeriod` of `from`..`to`).
 *
 * At least one of [year] or [period] must be present — partial dates are never
 * rejected, but a fully empty date carries no information and is disallowed.
 * A [Period] endpoint is itself a [PartialDate], so year-only ranges are
 * representable without special-casing.
 */
data class PartialDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
    val period: Period? = null,
) {
    init {
        require(year != null || period != null) {
            "PartialDate requires at least one of year or period (Req 4.6)"
        }
    }

    /** An inclusive range between two partial dates (`DatePeriod`). */
    data class Period(
        val from: PartialDate,
        val to: PartialDate,
    )
}

/**
 * A relationship to another party (`ProfileRelationship`), referencing the
 * related party by its [FixedRef] and carrying the source's relation label.
 */
data class Relationship(
    val toFixedRef: FixedRef,
    val relationType: String,
)

/**
 * A non-fatal observation accumulated while processing a snapshot. Diagnostics
 * do not, on their own, fail a cycle (Req 4.7, Req 5.3). Each carries the
 * offending record's [FixedRef] when known, a [kind], and a human-readable
 * [detail].
 */
data class Diagnostic(
    val kind: Kind,
    val detail: String,
    val fixedRef: FixedRef? = null,
) {
    enum class Kind {
        /** An ID reference (Feature / IDRegDocument / SanctionsEntry / ProfileRelationship) could not be resolved (Req 4.7). */
        UNRESOLVED_REF,

        /** A record's entity type was missing, empty, or unrecognized (Req 5.3). */
        UNRECOGNIZED_TYPE,

        /** A required field could not be mapped by the source adapter (Req 13.4). */
        MAP_ERROR,
    }
}

/**
 * The normalized record produced by `transform` and persisted in a `Version`.
 *
 * Cardinalities (Req 4.4):
 *  - [aliases], [addresses], [documents], [nationalities], [citizenships],
 *    [birthDates], [remarks], [relationships] are all 0..N.
 *  - [sanctionPrograms] is 1..N (enforced below).
 *
 * [primaryName] is required (Req 4.5). [entityType] is in-scope only (Req 5).
 * All string content is preserved verbatim as UTF-8 (Req 4.3).
 *
 * [versionId] is `null` until the `persist` stage stamps the record with its
 * version identity (Req 7.4); `transform` produces entries without it.
 */
data class InternalModelEntry(
    val fixedRef: FixedRef,
    val entityType: EntityType,
    val primaryName: String,
    val aliases: List<Alias> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val documents: List<Document> = emptyList(),
    val nationalities: List<String> = emptyList(),
    val citizenships: List<String> = emptyList(),
    val birthDates: List<PartialDate> = emptyList(),
    val sanctionPrograms: List<String>,
    val remarks: List<String> = emptyList(),
    val relationships: List<Relationship> = emptyList(),
    val versionId: VersionId? = null,
) {
    init {
        require(primaryName.isNotEmpty()) { "primaryName is required (Req 4.5)" }
        require(sanctionPrograms.isNotEmpty()) {
            "sanctionPrograms must have at least one entry (Req 4.4)"
        }
    }
}
