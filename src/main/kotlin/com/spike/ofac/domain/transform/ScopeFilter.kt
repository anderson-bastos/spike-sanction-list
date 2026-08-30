package com.spike.ofac.domain.transform

import com.spike.ofac.domain.model.Diagnostic
import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef

/**
 * The scope classifier and filter (task 3.1, pure logic).
 *
 * `transform` classifies every raw profile by its `PartySubTypeID` and keeps
 * only the in-scope ones (Req 5). This file is deliberately source-independent
 * and free of any I/O or parsing: it operates over already-extracted
 * `(FixedRef, PartySubTypeID)` inputs so it can be exercised in isolation by the
 * property test in task 3.2.
 *
 * Classification uses the ReferenceValueSet observed in the Advanced XML
 * (`ofac-data/benchmark.py`): `PartySubTypeID -> type`
 * `{"1":"Vessel","2":"Aircraft","3":"Entity","4":"Individual"}`, with
 * `IN_SCOPE = {Entity, Individual}` (design.md, transform stage).
 *
 * Filtering rules (Req 5.1–5.3):
 *  - Every in-scope record (Individual / Entity) is included (Req 5.1).
 *  - Vessel and Aircraft are recognized-but-out-of-scope and excluded, so the
 *    output contains zero vessels/aircraft (Req 5.2). These carry no diagnostic:
 *    they are an expected, well-understood exclusion.
 *  - A missing, empty, or unrecognized `PartySubTypeID` excludes the record and
 *    emits exactly one [Diagnostic] of kind [Diagnostic.Kind.UNRECOGNIZED_TYPE]
 *    identifying the record and the unrecognized value, without aborting the
 *    remaining records (Req 5.3).
 */
object ScopeFilter {

    /**
     * The observed `PartySubTypeID -> type` ReferenceValueSet from the Advanced
     * XML (`benchmark.py`). Recognized but out-of-scope types (Vessel, Aircraft)
     * are represented so they can be distinguished from unrecognized values.
     */
    val PARTY_SUBTYPE: Map<String, PartySubType> = mapOf(
        "1" to PartySubType.Vessel,
        "2" to PartySubType.Aircraft,
        "3" to PartySubType.Entity,
        "4" to PartySubType.Individual,
    )

    /** The four recognized `PartySubTypeID` values; only two are in scope. */
    enum class PartySubType(val inScopeType: EntityType?) {
        Vessel(null),
        Aircraft(null),
        Entity(EntityType.Entity),
        Individual(EntityType.Individual),
    }

    /**
     * The outcome of classifying a single raw profile by `PartySubTypeID`.
     *
     * A classification is either [InScope] (keep, carrying the mapped
     * [EntityType]), [OutOfScope] (a recognized Vessel/Aircraft — exclude, no
     * diagnostic), or [Unrecognized] (missing / empty / unknown value — exclude
     * with a diagnostic).
     */
    sealed interface Classification {
        data class InScope(val entityType: EntityType) : Classification
        data class OutOfScope(val type: PartySubType) : Classification
        data class Unrecognized(val rawValue: String?) : Classification
    }

    /**
     * Classify a single `PartySubTypeID` value.
     *
     * A `null`, blank, or unknown value classifies as [Classification.Unrecognized];
     * a recognized value maps to [Classification.InScope] or
     * [Classification.OutOfScope] per the ReferenceValueSet.
     */
    fun classify(partySubTypeId: String?): Classification {
        if (partySubTypeId.isNullOrBlank()) {
            return Classification.Unrecognized(partySubTypeId)
        }
        val type = PARTY_SUBTYPE[partySubTypeId]
            ?: return Classification.Unrecognized(partySubTypeId)
        return when (val inScope = type.inScopeType) {
            null -> Classification.OutOfScope(type)
            else -> Classification.InScope(inScope)
        }
    }

    /** A raw profile reduced to the two fields the scope filter needs. */
    data class RawProfile(
        val fixedRef: FixedRef,
        val partySubTypeId: String?,
    )

    /** One kept, in-scope record: its [FixedRef] and its mapped [EntityType]. */
    data class ScopedRecord(
        val fixedRef: FixedRef,
        val entityType: EntityType,
    )

    /**
     * The result of filtering a batch of raw profiles.
     *
     * @property kept           the in-scope records, in input order (Req 5.1).
     * @property outOfScopeCount the number of excluded records — recognized
     *   Vessel/Aircraft (Req 5.2) plus missing/empty/unrecognized (Req 5.3).
     *   This feeds `Expected_Count` derivation in `version.build` (Req 8.1).
     * @property diagnostics    exactly one [Diagnostic.Kind.UNRECOGNIZED_TYPE]
     *   per record excluded for a missing/empty/unrecognized type (Req 5.3).
     *   Vessel/Aircraft exclusions produce no diagnostic.
     */
    data class ScopeResult(
        val kept: List<ScopedRecord>,
        val outOfScopeCount: Int,
        val diagnostics: List<Diagnostic>,
    )

    /**
     * Classify and filter a batch of raw profiles.
     *
     * Every in-scope profile is kept; every out-of-scope or unrecognized profile
     * is excluded and counted; unrecognized profiles additionally emit one
     * diagnostic each. Processing never aborts on an excluded record (Req 5.3).
     */
    fun filter(profiles: List<RawProfile>): ScopeResult {
        val kept = ArrayList<ScopedRecord>(profiles.size)
        val diagnostics = ArrayList<Diagnostic>()
        var outOfScopeCount = 0

        for (profile in profiles) {
            when (val classification = classify(profile.partySubTypeId)) {
                is Classification.InScope ->
                    kept += ScopedRecord(profile.fixedRef, classification.entityType)

                is Classification.OutOfScope ->
                    // Recognized Vessel/Aircraft: excluded, counted, no diagnostic (Req 5.2).
                    outOfScopeCount++

                is Classification.Unrecognized -> {
                    // Missing/empty/unrecognized type: excluded, counted, one diagnostic (Req 5.3).
                    outOfScopeCount++
                    diagnostics += Diagnostic(
                        kind = Diagnostic.Kind.UNRECOGNIZED_TYPE,
                        detail = unrecognizedDetail(classification.rawValue),
                        fixedRef = profile.fixedRef,
                    )
                }
            }
        }

        return ScopeResult(
            kept = kept,
            outOfScopeCount = outOfScopeCount,
            diagnostics = diagnostics,
        )
    }

    private fun unrecognizedDetail(rawValue: String?): String {
        val shown = when {
            rawValue == null -> "<missing>"
            rawValue.isBlank() -> "<empty>"
            else -> "'$rawValue'"
        }
        return "Unrecognized PartySubTypeID $shown; expected one of ${PARTY_SUBTYPE.keys} " +
            "(Vessel/Aircraft/Entity/Individual)"
    }
}
