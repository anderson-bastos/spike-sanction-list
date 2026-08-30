package com.spike.ofac.domain.transform

import com.spike.ofac.domain.model.Address
import com.spike.ofac.domain.model.Alias
import com.spike.ofac.domain.model.Diagnostic
import com.spike.ofac.domain.model.Document
import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.PartialDate
import com.spike.ofac.domain.model.Relationship

/**
 * Reference resolution + `InternalModelEntry` construction (task 5.1, Req 4.1, 4.2, 4.3, 4.5, 4.6).
 *
 * The [AdvancedXmlStreamParser] produces raw, source-shaped profiles that still
 * carry the Advanced XML's ID references. This builder is the second half of the
 * transform: it **resolves** those references against the [RawReferenceTables]
 * gathered in the same pass and assembles the normalized
 * [InternalModelEntry].
 *
 * ### References resolved (Req 4.2)
 *  - **Feature** → its `FeatureType` label routes the value: birthdate (8),
 *    place of birth (9), nationality country (10), citizenship country (11),
 *    location (25), and any other type becomes a remark. Birthdates keep their
 *    partial shape (Req 4.6); location features resolve their `LocationID` to a
 *    [RawLocation] to build an [Address].
 *  - **IDRegDocument** → linked to the party by `IdentityID`, its
 *    `IDRegDocTypeID` resolved to a document-type label ([Document.type]).
 *  - **SanctionsEntry** → linked to the party by `ProfileID`, contributing its
 *    program names ([InternalModelEntry.sanctionPrograms]) and, via `ListID`, a
 *    list-name fallback program.
 *  - **ProfileRelationship** → linked by `From-ProfileID`, its `To-ProfileID`
 *    kept as the related [FixedRef] and its `RelationTypeID` resolved to a label.
 *
 * ### Names (Req 4.5)
 * The primary alias (or, absent an explicit primary, the first alias) supplies
 * [InternalModelEntry.primaryName]; when a record has **zero aliases** the
 * builder has no name to use and reports it as an unbuildable record (a hard
 * failure that task 5.2 turns into a stage `FAILED`). Non-primary aliases become
 * [InternalModelEntry.aliases].
 *
 * ### What this builder does *not* do
 * It does not run the scope filter or dedup (tasks 3/4, wired by task 5.2) and it
 * does not decide the stage's hard-failure semantics (task 5.2). It only reports,
 * per profile, either a built entry or the reason it could not be built, together
 * with any soft diagnostics (e.g. unresolved references, Req 4.7).
 *
 * Reference resolution is pure and deterministic over its inputs, supporting the
 * deterministic-reprocessing guarantee (Req 11.4).
 */
class ProfileEntryBuilder {

    /** Cross-profile link indexes derived once from the reference tables. */
    private class Links(references: RawReferenceTables) {
        val documentsByIdentity: Map<String, List<RawIdRegDocument>> =
            references.idRegDocuments
                .filter { it.identityId != null }
                .groupBy { it.identityId!! }
        val entriesByProfile: Map<String, List<RawSanctionsEntry>> =
            references.sanctionsEntries
                .filter { it.profileId != null }
                .groupBy { it.profileId!! }
        val relationshipsByProfile: Map<String, List<RawProfileRelationship>> =
            references.relationships
                .filter { it.fromProfileId != null }
                .groupBy { it.fromProfileId!! }
    }

    /**
     * The outcome of building one profile.
     *
     * @property entry       the built entry, or `null` when the profile could not
     *   be turned into an entry (see [unbuildableReason]).
     * @property diagnostics soft diagnostics accumulated for this profile —
     *   unresolved references (Req 4.7) and the like; never fatal on their own.
     * @property unbuildableReason non-null exactly when [entry] is `null`,
     *   describing why (e.g. zero aliases so no primary name, Req 4.5). Task 5.2
     *   decides whether this fails the stage (Req 4.8).
     */
    data class EntryResult(
        val fixedRef: String,
        val entry: InternalModelEntry?,
        val diagnostics: List<Diagnostic> = emptyList(),
        val unbuildableReason: String? = null,
    )

    /**
     * Build entries for every in-scope profile in [snapshot], resolving all ID
     * references against the snapshot's reference tables.
     *
     * Only profiles classified in-scope by [ScopeFilter] (Individual / Entity)
     * are built; out-of-scope and unrecognized profiles are skipped here (the
     * scope filter, task 3, owns their diagnostics and counting). Returns one
     * [EntryResult] per built-or-attempted in-scope profile, in source order.
     */
    fun build(snapshot: ParsedSnapshot): List<EntryResult> {
        val links = Links(snapshot.references)
        val fixedRefByProfile = snapshot.profiles.associate { it.profileId to it.fixedRef }
        return snapshot.profiles.mapNotNull { profile ->
            val entityType = inScopeEntityType(profile.partySubTypeId) ?: return@mapNotNull null
            buildOne(profile, entityType, snapshot.references, links, fixedRefByProfile)
        }
    }

    /** Maps a `PartySubTypeID` to its in-scope [EntityType], or `null` if out of scope/unrecognized. */
    private fun inScopeEntityType(partySubTypeId: String?): EntityType? =
        when (val classification = ScopeFilter.classify(partySubTypeId)) {
            is ScopeFilter.Classification.InScope -> classification.entityType
            else -> null
        }

    private fun buildOne(
        profile: RawParsedProfile,
        entityType: EntityType,
        references: RawReferenceTables,
        links: Links,
        fixedRefByProfile: Map<String, String>,
    ): EntryResult {
        val diagnostics = ArrayList<Diagnostic>()
        val fixedRefValue = profile.fixedRef
        if (fixedRefValue.isBlank()) {
            return EntryResult(
                fixedRef = fixedRefValue,
                entry = null,
                unbuildableReason = "DistinctParty is missing its FixedRef",
            )
        }
        val fixedRef = FixedRef(fixedRefValue)

        // ---- Names (Req 4.5) ----
        val primaryRaw = profile.aliases.firstOrNull { it.primary } ?: profile.aliases.firstOrNull()
        if (primaryRaw == null) {
            // Zero aliases -> no primary name. Task 5.2 decides the hard failure (Req 4.8).
            return EntryResult(
                fixedRef = fixedRefValue,
                entry = null,
                unbuildableReason = "Profile $fixedRefValue has no name (zero aliases); cannot set primary_name (Req 4.5)",
            )
        }
        val primaryName = primaryRaw.fullName
        val aliases = profile.aliases
            .filter { it !== primaryRaw }
            .map { raw ->
                Alias(
                    name = raw.fullName,
                    type = raw.aliasTypeId?.let { references.aliasTypeNames[it] },
                    isPrimary = false,
                )
            }

        // ---- Features: route by resolved FeatureType (Req 4.2, 4.3, 4.6) ----
        val addresses = ArrayList<Address>()
        val nationalities = ArrayList<String>()
        val citizenships = ArrayList<String>()
        val birthDates = ArrayList<PartialDate>()
        val remarks = ArrayList<String>()

        for (feature in profile.features) {
            val typeLabel = feature.featureTypeId?.let { references.featureTypeNames[it] }
            when (typeLabel) {
                "Birthdate" -> feature.datePeriod?.let { toPartialDate(it) }?.let { birthDates += it }
                "Nationality Country" -> featureText(feature)?.let { nationalities += it }
                "Citizenship Country" -> featureText(feature)?.let { citizenships += it }
                "Location" -> {
                    val addr = resolveLocation(feature, references, diagnostics, fixedRef)
                    if (addr != null) addresses += addr
                }
                "Place of Birth" -> featureText(feature)?.let { remarks += "Place of Birth: $it" }
                else -> featureText(feature)?.let { text ->
                    remarks += if (typeLabel != null) "$typeLabel: $text" else text
                }
            }
        }

        // ---- Documents: IDRegDocument linked by IdentityID (Req 4.2) ----
        val documents = ArrayList<Document>()
        val identityId = profile.identityId
        if (identityId != null) {
            for (doc in links.documentsByIdentity[identityId].orEmpty()) {
                val docType = doc.idRegDocTypeId?.let { references.idRegDocTypeNames[it] }
                if (docType == null) {
                    diagnostics += unresolved(
                        fixedRef,
                        "IDRegDocument ${doc.id} has unresolvable IDRegDocTypeID '${doc.idRegDocTypeId}'",
                    )
                }
                documents += Document(
                    type = docType ?: "Unknown",
                    number = doc.registrationNumber,
                    issuer = doc.issuer,
                )
            }
        }

        // ---- Sanctions programs: SanctionsEntry linked by ProfileID (Req 4.2, 4.4) ----
        val programs = LinkedHashSet<String>()
        var listNameFallback: String? = null
        for (entry in links.entriesByProfile[profile.profileId].orEmpty()) {
            programs += entry.programNames
            val listName = entry.listId?.let { references.listNames[it] }
            if (listName != null && listNameFallback == null) listNameFallback = listName
        }
        val sanctionPrograms = when {
            programs.isNotEmpty() -> programs.toList()
            listNameFallback != null -> listOf(listNameFallback!!)
            else -> {
                // No resolvable program; InternalModelEntry requires >= 1 (Req 4.4).
                // Task 5.2 decides whether an entry with no program fails the stage.
                return EntryResult(
                    fixedRef = fixedRefValue,
                    entry = null,
                    diagnostics = diagnostics,
                    unbuildableReason = "Profile $fixedRefValue has no resolvable sanction program (Req 4.4)",
                )
            }
        }

        // ---- Relationships: ProfileRelationship linked by From-ProfileID (Req 4.2) ----
        val relationships = ArrayList<Relationship>()
        for (rel in links.relationshipsByProfile[profile.profileId].orEmpty()) {
            val relatedFixedRef = rel.toProfileId?.let { fixedRefByProfile[it] }
            val relationLabel = rel.relationTypeId?.let { references.relationTypeNames[it] }
            if (relatedFixedRef == null) {
                diagnostics += unresolved(
                    fixedRef,
                    "ProfileRelationship ${rel.id} references unresolvable To-ProfileID '${rel.toProfileId}'",
                )
                continue
            }
            if (relationLabel == null) {
                diagnostics += unresolved(
                    fixedRef,
                    "ProfileRelationship ${rel.id} has unresolvable RelationTypeID '${rel.relationTypeId}'",
                )
            }
            relationships += Relationship(
                toFixedRef = FixedRef(relatedFixedRef),
                relationType = relationLabel ?: "Unknown",
            )
        }

        val entry = InternalModelEntry(
            fixedRef = fixedRef,
            entityType = entityType,
            primaryName = primaryName,
            aliases = aliases,
            addresses = addresses,
            documents = documents,
            nationalities = nationalities,
            citizenships = citizenships,
            birthDates = birthDates,
            sanctionPrograms = sanctionPrograms,
            remarks = remarks,
            relationships = relationships,
            versionId = null, // stamped at persist (Req 7.4)
        )
        return EntryResult(fixedRefValue, entry, diagnostics)
    }

    /** Resolves a location feature's `LocationID` to an [Address], or records a diagnostic (Req 4.2, 4.7). */
    private fun resolveLocation(
        feature: RawFeature,
        references: RawReferenceTables,
        diagnostics: MutableList<Diagnostic>,
        fixedRef: FixedRef,
    ): Address? {
        val locationId = feature.locationId ?: return featureText(feature)?.let { Address(raw = it) }
        val location = references.locations[locationId]
        if (location == null) {
            diagnostics += unresolved(
                fixedRef,
                "Feature ${feature.featureId} references unresolvable LocationID '$locationId'",
            )
            return null
        }
        val raw = location.parts.joinToString(", ") { it.value }
        val country = location.countryId?.let { references.countryNames[it] }
        val parts = location.parts
            .filter { it.locPartTypeId != null }
            .associate { (references.locPartTypeNames[it.locPartTypeId] ?: it.locPartTypeId!!) to it.value }
        return Address(raw = raw.ifBlank { country ?: locationId }, country = country, parts = parts)
    }

    /** A feature's free-text value (`VersionDetail`), preserved verbatim (Req 4.3). */
    private fun featureText(feature: RawFeature): String? = feature.detailValue?.ifBlank { null }

    /**
     * Converts a raw [RawDatePeriod] into a [PartialDate], preserving partial
     * (year-only) dates and true ranges (Req 4.6). A period whose start and end
     * carry the same values collapses to a single partial date; a real range is
     * kept as a [PartialDate.Period]. Returns `null` when neither endpoint carries
     * any date information.
     */
    private fun toPartialDate(period: RawDatePeriod): PartialDate? {
        val start = period.start?.let { toPartial(it) }
        val end = period.end?.let { toPartial(it) }
        return when {
            start != null && end != null && start == end -> start
            start != null && end != null -> PartialDate(period = PartialDate.Period(start, end))
            start != null -> start
            end != null -> end
            else -> null
        }
    }

    /** Converts a raw partial date to the model [PartialDate], or `null` if it has no year. */
    private fun toPartial(raw: RawPartialDate): PartialDate? {
        if (raw.year == null) return null // PartialDate requires at least a year or a period (Req 4.6)
        return PartialDate(year = raw.year, month = raw.month, day = raw.day)
    }

    private fun unresolved(fixedRef: FixedRef, detail: String): Diagnostic =
        Diagnostic(kind = Diagnostic.Kind.UNRESOLVED_REF, detail = detail, fixedRef = fixedRef)
}
