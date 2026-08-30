package com.spike.ofac.domain.transform

/**
 * Raw, source-shaped values extracted from the Advanced XML by the streaming
 * parser ([AdvancedXmlStreamParser]) — task 5.1.
 *
 * These types are a **faithful, un-normalized** projection of the Advanced XML
 * `DistinctParty` / `Feature` / `IDRegDocument` / `SanctionsEntry` /
 * `ProfileRelationship` structure. They deliberately carry the source's own ID
 * references (FeatureTypeID, IDRegDocTypeID, RelationTypeID, LocationID, and the
 * profile/identity ids) as raw strings so the *reference-resolution* step
 * ([ProfileEntryBuilder]) can resolve them against the `ReferenceValueSets`
 * tables and the cross-section link tables before producing the normalized
 * [com.spike.ofac.domain.model.InternalModelEntry].
 *
 * Keeping the parse output separate from the internal model means the parser is
 * a single-pass, memory-bounded producer (`spike` §9) and all the "which
 * ReferenceValueSet does this id mean" logic lives in one place, exercised by the
 * transform round-trip property (task 5.3, Property 5).
 *
 * The parser never materializes a full DOM: it advances token-by-token per
 * `DistinctParty` and appends one [RawParsedProfile] per party, plus the small
 * cross-section link tables ([RawReferenceTables]) that associate documents,
 * sanctions entries, and relationships back to their profile.
 */

/** One `DistinctParty` profile as read from the XML, before normalization. */
data class RawParsedProfile(
    /** `DistinctParty/@FixedRef` — the stable OFAC id (== uid). */
    val fixedRef: String,
    /** `Profile/@ID` — links `IDRegDocument`, `SanctionsEntry`, `ProfileRelationship`. */
    val profileId: String,
    /** `Identity/@ID` — links `IDRegDocument` (via `IdentityID`) back to this party. */
    val identityId: String?,
    /** `Profile/@PartySubTypeID` — classified by [ScopeFilter] (1..4 → Vessel/Aircraft/Entity/Individual). */
    val partySubTypeId: String?,
    /** The party's names, in source order; the [RawAlias.primary] one is the primary name. */
    val aliases: List<RawAlias>,
    /** The party's `Feature`s (birthdate, nationality, citizenship, place of birth, address, remarks, ...). */
    val features: List<RawFeature>,
)

/** One `Alias` → `DocumentedName`, its name parts joined in source order. */
data class RawAlias(
    /** `Alias/@AliasTypeID` (e.g. 1400 A.K.A., 1401 F.K.A., 1403 Name), resolved to a label later. */
    val aliasTypeId: String?,
    /** `Alias/@Primary` == "true" — mirrors [com.spike.ofac.domain.model.InternalModelEntry.primaryName]. */
    val primary: Boolean,
    /** The `NamePartValue` texts joined with spaces, preserved verbatim as UTF-8 (Req 4.3). */
    val fullName: String,
    /** `Alias/@LowQuality` == "true" — the OFAC "Category": true → weak, false/absent → strong. */
    val lowQuality: Boolean = false,
)

/**
 * One `Feature` on a profile. A feature's payload is whichever of the three
 * shapes the source used: a free-text [detailValue] (`VersionDetail`), a
 * [datePeriod] (birthdates), or a [locationId] reference (`VersionLocation`).
 */
data class RawFeature(
    /** `Feature/@ID`. */
    val featureId: String,
    /** `Feature/@FeatureTypeID` — resolved to a feature-type label to route the value (birthdate/nationality/...). */
    val featureTypeId: String?,
    /** `FeatureVersion/VersionDetail` text when present (place of birth, nationality text, remark, ...). */
    val detailValue: String? = null,
    /** `FeatureVersion/DatePeriod` when present (birthdates, Req 4.6). */
    val datePeriod: RawDatePeriod? = null,
    /** `FeatureVersion/VersionLocation/@LocationID` when present — resolved to an address (Req 4.2). */
    val locationId: String? = null,
)

/**
 * A raw `DatePeriod`, preserving the source's partial-date structure verbatim
 * (Req 4.6). A single date collapses to `start == end`; a true range keeps both.
 * Each endpoint is a [RawPartialDate] so year-only dates survive.
 */
data class RawDatePeriod(
    val start: RawPartialDate?,
    val end: RawPartialDate?,
)

/** A raw partial calendar date: any of year/month/day may be absent (Req 4.6). */
data class RawPartialDate(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
)

/** One resolved `Location` (from the top-level `Locations` section), for addresses. */
data class RawLocation(
    val locationId: String,
    /** `LocationPart` values joined (city, state, region, address lines), preserved verbatim. */
    val parts: List<RawLocationPart>,
    /** `LocationCountry/@CountryID`, resolved to a country name later when available. */
    val countryId: String? = null,
)

/** One `LocationPart` value tagged with its `LocPartTypeID` (CITY, STATE/PROVINCE, ...). */
data class RawLocationPart(
    val locPartTypeId: String?,
    val value: String,
)

/** One `IDRegDocument` — an identification/registration document (Req 4.2). */
data class RawIdRegDocument(
    val id: String,
    /** `IDRegDocument/@IdentityID` — links the document back to a party's identity. */
    val identityId: String?,
    /** `IDRegDocument/@IDRegDocTypeID` — resolved to a document-type label (Passport, Tax ID No., ...). */
    val idRegDocTypeId: String?,
    /** `IDRegistrationNo` text. */
    val registrationNumber: String? = null,
    /** `IssuingAuthority` text, or resolved issuing country. */
    val issuer: String? = null,
)

/** One `SanctionsEntry` — associates a profile with a list and its program measures (Req 4.2). */
data class RawSanctionsEntry(
    val id: String,
    /** `SanctionsEntry/@ProfileID` — links the entry back to its profile. */
    val profileId: String?,
    /** `SanctionsEntry/@ListID` — resolved to a list name (SDN List, Consolidated List, ...). */
    val listId: String? = null,
    /**
     * The program identifiers carried by the entry. In the Advanced XML the
     * program name is exposed as the `Comment` on a `SanctionsMeasure` whose
     * `SanctionsTypeID` is the "Program" type (id 1) — e.g. "SDGT", "NS-PLC".
     */
    val programNames: List<String> = emptyList(),
)

/** One `ProfileRelationship` — a directed link between two profiles (Req 4.2). */
data class RawProfileRelationship(
    val id: String,
    /** `@From-ProfileID` — the profile the relationship is stated on. */
    val fromProfileId: String?,
    /** `@To-ProfileID` — the related profile. */
    val toProfileId: String?,
    /** `@RelationTypeID` — resolved to a relation label ("Owned or Controlled By", ...). */
    val relationTypeId: String?,
)

/**
 * The cross-section link tables the resolver needs, gathered in the same single
 * streaming pass as the profiles. These are the referenced records that must be
 * resolved to their profiles (Req 4.2): documents (via `IdentityID`), sanctions
 * entries and relationships (via `ProfileID`), and locations (via `LocationID`).
 *
 * @property featureTypeNames   `FeatureTypeID` → label (routes feature values).
 * @property idRegDocTypeNames  `IDRegDocTypeID` → label (document type).
 * @property relationTypeNames  `RelationTypeID` → label (relationship kind).
 * @property listNames          `ListID` → label (SDN List / Consolidated List / ...).
 * @property countryNames       `CountryID` → label (for addresses / nationalities).
 * @property locPartTypeNames   `LocPartTypeID` → label (CITY / STATE/PROVINCE / ...).
 * @property aliasTypeNames     `AliasTypeID` → label (A.K.A. / F.K.A. / Name).
 * @property locations          `LocationID` → [RawLocation].
 * @property idRegDocuments     all `IDRegDocument`s (linked to identities).
 * @property sanctionsEntries   all `SanctionsEntry`s (linked to profiles).
 * @property relationships      all `ProfileRelationship`s (linked to profiles).
 */
data class RawReferenceTables(
    val featureTypeNames: Map<String, String> = emptyMap(),
    val idRegDocTypeNames: Map<String, String> = emptyMap(),
    val relationTypeNames: Map<String, String> = emptyMap(),
    val listNames: Map<String, String> = emptyMap(),
    val countryNames: Map<String, String> = emptyMap(),
    val locPartTypeNames: Map<String, String> = emptyMap(),
    val aliasTypeNames: Map<String, String> = emptyMap(),
    val locations: Map<String, RawLocation> = emptyMap(),
    val idRegDocuments: List<RawIdRegDocument> = emptyList(),
    val sanctionsEntries: List<RawSanctionsEntry> = emptyList(),
    val relationships: List<RawProfileRelationship> = emptyList(),
)

/**
 * The full output of one streaming parse of an Advanced XML snapshot: every raw
 * profile plus the reference/link tables needed to resolve their ID references.
 *
 * @property publishDate the snapshot's `DateOfIssue` (`Publish_Date`), if present.
 * @property profiles    one [RawParsedProfile] per `DistinctParty`, in source order.
 * @property references  the [RawReferenceTables] gathered in the same pass.
 */
data class ParsedSnapshot(
    val publishDate: RawPartialDate?,
    val profiles: List<RawParsedProfile>,
    val references: RawReferenceTables,
)
