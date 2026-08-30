package com.spike.ofac.domain.scope

import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.domain.model.SourceList

/**
 * Validation and wiring for the configured list scope (task 14.1, Req 12).
 *
 * The deployment supplies the scope as a raw configuration value (e.g. a
 * property string). Before any ingestion runs, that value must be parsed into a
 * [ScopeConfig] — and rejected if it is not one of the two permitted values —
 * so the pipeline never starts a cycle under an invalid or unsupported scope
 * (Req 12.5) and never ingests a `Consolidated`-only deployment (Req 12.4).
 *
 * The only accepted values are exactly `SDN_ONLY` and `SDN_AND_CONSOLIDATED`
 * (Req 12.1). Every other input — the reserved-but-unsupported `CONSOLIDATED_ONLY`
 * (Req 12.4), and any absent / empty / unrecognized value (Req 12.5) — is
 * rejected with a [ScopeValidationResult.Invalid] carrying a
 * [ScopeValidationError] and a human-readable reason, and **nothing is ingested**.
 *
 * There is deliberately **no default**: the default list scope is a pending
 * business decision, so a missing value is an error rather than a silent
 * fallback (design.md — "no default is hard-coded").
 *
 * This is pure logic with no I/O; it can be exercised in isolation by the
 * property test (task 14.2) and the unit tests (task 14.3).
 */
object ScopeConfigValidator {

    /**
     * The reserved scope value that is explicitly rejected: ingesting only the
     * Consolidated list is not a permitted deployment (Req 12.4). It is matched
     * by name so the rejection reason can distinguish it from an arbitrary
     * unrecognized value.
     */
    const val CONSOLIDATED_ONLY: String = "CONSOLIDATED_ONLY"

    /**
     * Parse and validate a raw scope configuration value.
     *
     * Matching is case-sensitive against the canonical enum names and tolerant of
     * surrounding whitespace only (a value that is blank once trimmed is treated
     * as absent). The outcome is:
     *  - [ScopeValidationResult.Valid] wrapping the [ScopeConfig] when [raw]
     *    trims to exactly `SDN_ONLY` or `SDN_AND_CONSOLIDATED` (Req 12.1).
     *  - [ScopeValidationResult.Invalid] with [ScopeValidationError.CONSOLIDATED_ONLY_NOT_PERMITTED]
     *    when [raw] trims to `CONSOLIDATED_ONLY` (Req 12.4).
     *  - [ScopeValidationResult.Invalid] with [ScopeValidationError.ABSENT] when
     *    [raw] is `null` or blank (Req 12.5).
     *  - [ScopeValidationResult.Invalid] with [ScopeValidationError.UNRECOGNIZED]
     *    for any other value (Req 12.5).
     *
     * In every [ScopeValidationResult.Invalid] case the caller must ingest
     * nothing and surface the error (Req 12.4, 12.5).
     *
     * @param raw the configured scope value as supplied by the deployment, if any.
     * @return a [ScopeValidationResult] the caller inspects before any ingestion.
     */
    fun validate(raw: String?): ScopeValidationResult {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrEmpty()) {
            return ScopeValidationResult.Invalid(
                error = ScopeValidationError.ABSENT,
                reason = "Scope configuration is absent or empty; supply one of " +
                    "${ScopeConfig.SDN_ONLY} or ${ScopeConfig.SDN_AND_CONSOLIDATED}.",
            )
        }
        if (trimmed == CONSOLIDATED_ONLY) {
            return ScopeValidationResult.Invalid(
                error = ScopeValidationError.CONSOLIDATED_ONLY_NOT_PERMITTED,
                reason = "Consolidated-only scope ($CONSOLIDATED_ONLY) is not permitted; " +
                    "the SDN list must always be ingested.",
            )
        }
        val matched = ScopeConfig.entries.firstOrNull { it.name == trimmed }
            ?: return ScopeValidationResult.Invalid(
                error = ScopeValidationError.UNRECOGNIZED,
                reason = "Unrecognized scope configuration '$trimmed'; valid values are " +
                    "${ScopeConfig.SDN_ONLY} or ${ScopeConfig.SDN_AND_CONSOLIDATED}.",
            )
        return ScopeValidationResult.Valid(matched)
    }

    /**
     * The set of [SourceList]s the pipeline ingests under a validated [scope].
     *
     * This is the wiring that turns the validated scope into concrete pipeline
     * behavior:
     *  - [ScopeConfig.SDN_ONLY] ingests only [SourceList.SDN]; no Consolidated
     *    record is obtained or persisted (Req 12.2).
     *  - [ScopeConfig.SDN_AND_CONSOLIDATED] ingests both [SourceList.SDN] and
     *    [SourceList.CONSOLIDATED], which then engages the cross-list dedup path
     *    (task 4.1 / Req 6, 12.3).
     *
     * The result is ordered with SDN first so downstream dedup keeps SDN as the
     * governing representation on overlap (Req 6.2).
     *
     * @param scope a scope already validated via [validate].
     * @return the ordered list of source lists to ingest.
     */
    fun sourceListsFor(scope: ScopeConfig): List<SourceList> =
        when (scope) {
            ScopeConfig.SDN_ONLY -> listOf(SourceList.SDN)
            ScopeConfig.SDN_AND_CONSOLIDATED ->
                listOf(SourceList.SDN, SourceList.CONSOLIDATED)
        }

    /**
     * Whether the cross-list deduplication path (task 4.1, Req 6) runs under the
     * given [scope].
     *
     * Dedup is meaningful only when more than one list is ingested, so it runs
     * exactly for [ScopeConfig.SDN_AND_CONSOLIDATED] (Req 12.3) and never for
     * [ScopeConfig.SDN_ONLY] (Req 12.2). Callers pass the scope straight through
     * to `CrossListDedup.deduplicate`, which already honors this distinction;
     * this helper documents the wiring at the call site.
     */
    fun runsDedup(scope: ScopeConfig): Boolean =
        scope == ScopeConfig.SDN_AND_CONSOLIDATED
}

/**
 * The outcome of validating a raw scope configuration value (Req 12).
 *
 * Either [Valid], carrying the parsed [ScopeConfig] the pipeline runs under, or
 * [Invalid], carrying the [ScopeValidationError] cause and a human-readable
 * reason. On [Invalid] the pipeline must ingest nothing (Req 12.4, 12.5).
 */
sealed interface ScopeValidationResult {

    /** The scope was accepted; [scope] is the validated configuration. */
    data class Valid(val scope: ScopeConfig) : ScopeValidationResult

    /**
     * The scope was rejected. The pipeline ingests nothing and surfaces [reason].
     *
     * @property error the machine-readable cause of the rejection.
     * @property reason a human-readable explanation suitable for surfacing.
     */
    data class Invalid(
        val error: ScopeValidationError,
        val reason: String,
    ) : ScopeValidationResult
}

/**
 * The distinct causes a scope configuration can be rejected for (Req 12.4, 12.5).
 */
enum class ScopeValidationError {

    /** The value was `null`, empty, or blank (Req 12.5). */
    ABSENT,

    /**
     * The value was the reserved `CONSOLIDATED_ONLY`, which is explicitly not a
     * permitted deployment scope (Req 12.4).
     */
    CONSOLIDATED_ONLY_NOT_PERMITTED,

    /**
     * The value was present but is not one of the recognized scope values
     * (Req 12.5).
     */
    UNRECOGNIZED,
}
