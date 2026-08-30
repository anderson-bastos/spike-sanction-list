package com.spike.ofac.adapter.out.source

import com.spike.ofac.application.port.out.MappingResult
import com.spike.ofac.application.port.out.SourceAdapter
import com.spike.ofac.application.port.out.SourceEntityType
import com.spike.ofac.domain.transform.ParsedSnapshot
import com.spike.ofac.domain.transform.ProfileEntryBuilder
import com.spike.ofac.domain.transform.RawParsedProfile
import com.spike.ofac.domain.transform.RawReferenceTables
import com.spike.ofac.domain.transform.ScopeFilter

/**
 * The source-independent half of a [SourceAdapter]'s field-mapping duty (task
 * 20.1), shared by the OFAC/UN/EU adapters so the mapping semantics stay
 * identical across sources.
 *
 * The two things that genuinely vary per source are **obtain I/O + auth**
 * (which stays in each adapter's [SourceAdapter.head] / [SourceAdapter.get]) and
 * any source-specific reference-value quirks. Everything else about turning a
 * [RawParsedProfile] into an [com.spike.ofac.domain.model.InternalModelEntry]
 * — reference resolution, cardinality enforcement, and reporting a required-field
 * failure that names the offending field and the source record (Req 13.2, 13.4) —
 * is common, so it lives here once rather than being copied into every adapter.
 *
 * The [source] label is threaded into every [MappingResult.MappingError] detail
 * so a mapping failure identifies both the source and the field (Req 13.4).
 *
 * @property source the source name (e.g. `"OFAC"`, `"UN"`, `"EU"`) recorded in
 *   mapping-error detail so the failure identifies the source (Req 13.4).
 * @property entryBuilder the reference-resolving normalizer that enforces the
 *   model cardinalities; injectable for testing.
 * @property references the cross-section reference tables a raw profile's ID
 *   references resolve against (as the transform stage supplies them per
 *   snapshot); empty by default.
 */
class SourceAdapterSupport(
    private val source: String,
    private val entryBuilder: ProfileEntryBuilder = ProfileEntryBuilder(),
    private val references: RawReferenceTables = RawReferenceTables(),
) {

    /**
     * Classifies a raw profile into one of the source's entity types via the
     * observed ReferenceValueSet reused from [ScopeFilter], yielding
     * [SourceEntityType.Unknown] for a missing / empty / unrecognized value so the
     * scope filter can exclude it without aborting (Req 5.3).
     */
    fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType =
        when (val classification = ScopeFilter.classify(rawProfile.partySubTypeId)) {
            is ScopeFilter.Classification.InScope -> when (classification.entityType) {
                com.spike.ofac.domain.model.EntityType.Individual -> SourceEntityType.Individual
                com.spike.ofac.domain.model.EntityType.Entity -> SourceEntityType.Entity
            }
            is ScopeFilter.Classification.OutOfScope -> when (classification.type) {
                ScopeFilter.PartySubType.Vessel -> SourceEntityType.Vessel
                ScopeFilter.PartySubType.Aircraft -> SourceEntityType.Aircraft
                ScopeFilter.PartySubType.Entity -> SourceEntityType.Entity
                ScopeFilter.PartySubType.Individual -> SourceEntityType.Individual
            }
            is ScopeFilter.Classification.Unrecognized -> SourceEntityType.Unknown
        }

    /**
     * Maps one source-shaped [rawProfile] to the common model, or returns a
     * [MappingResult.MappingError] naming the offending required field and
     * identifying the record (its FixedRef) and source when a required field
     * cannot be mapped (Req 13.2, 13.4). The mapping is all-or-nothing: it never
     * returns a partially built entry.
     */
    fun mapRecord(rawProfile: RawParsedProfile): MappingResult {
        // Only in-scope profiles are mappable (the common model's entity_type is
        // Individual|Entity only). Reject others on the entity_type field so the
        // caller can act (Req 13.4).
        if (!entityTypeOf(rawProfile).inScope) {
            return MappingResult.MappingError(
                field = "entity_type",
                fixedRef = rawProfile.fixedRef.ifBlank { null },
                detail = "[$source] PartySubTypeID '${rawProfile.partySubTypeId}' is not an " +
                    "in-scope Individual/Entity, so no InternalModelEntry can be mapped" +
                    rawProfile.fixedRef.ifBlank { null }?.let { " (record $it)" }.orEmpty(),
            )
        }

        // Delegate normalization (reference resolution + cardinality enforcement)
        // to the builder via a single-profile snapshot carrying the references.
        val results = entryBuilder.build(singleProfileSnapshot(rawProfile))
        val result = results.firstOrNull()
            ?: return MappingResult.MappingError(
                field = "entity_type",
                fixedRef = rawProfile.fixedRef.ifBlank { null },
                detail = "[$source] profile was not classified in scope by the builder" +
                    rawProfile.fixedRef.ifBlank { null }?.let { " (record $it)" }.orEmpty(),
            )

        val entry = result.entry
        return if (entry != null) {
            MappingResult.Success(entry)
        } else {
            val field = fieldFromReason(result.unbuildableReason)
            MappingResult.MappingError(
                field = field,
                fixedRef = rawProfile.fixedRef.ifBlank { null },
                detail = "[$source] could not map required field '$field'" +
                    rawProfile.fixedRef.ifBlank { null }?.let { " for record $it" }.orEmpty() +
                    (result.unbuildableReason?.let { ": $it" } ?: ""),
            )
        }
    }

    private fun singleProfileSnapshot(rawProfile: RawParsedProfile): ParsedSnapshot =
        ParsedSnapshot(
            publishDate = null,
            profiles = listOf(rawProfile),
            references = references,
        )

    private fun fieldFromReason(reason: String?): String = when {
        reason == null -> "unknown"
        reason.contains("FixedRef") -> "fixed_ref"
        reason.contains("primary_name") || reason.contains("zero aliases") -> "primary_name"
        reason.contains("sanction program") -> "sanction_programs"
        else -> "unknown"
    }
}
