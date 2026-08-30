package com.spike.ofac.domain.transform

import com.spike.ofac.domain.model.Diagnostic
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.domain.model.SourceList
import java.io.InputStream

/**
 * The `transform` stage: assemble a [TransformResult] from a validated snapshot
 * (task 5.2, Req 4.7, 4.8).
 *
 * This stage is the orchestration layer that wires together the pure components
 * built in tasks 5.1, 3, and 4:
 *  - [AdvancedXmlStreamParser] — streaming parse of the Advanced XML into a
 *    [ParsedSnapshot] (task 5.1).
 *  - [ProfileEntryBuilder] — reference resolution + `InternalModelEntry`
 *    construction, one [ProfileEntryBuilder.EntryResult] per in-scope profile
 *    (task 5.1).
 *  - [ScopeFilter] — classifies every profile so the out-of-scope count (vessels
 *    / aircraft / unrecognized types) can be reported for count reconciliation
 *    (task 3, Req 5, feeds Req 8.1).
 *  - [CrossListDedup] — combines two lists' in-scope entries into the distinct
 *    union when the configured scope spans both lists (task 4, Req 6).
 *
 * ## Diagnostics (Req 4.7)
 * Diagnostics are **soft** — they never, on their own, fail the stage. They are
 * accumulated from two sources and propagated verbatim in [TransformResult.Ok.diagnostics]:
 *  - one [Diagnostic.Kind.UNRESOLVED_REF] per unresolvable ID reference, already
 *    emitted per identifying record by [ProfileEntryBuilder] (Req 4.7); and
 *  - one [Diagnostic.Kind.UNRECOGNIZED_TYPE] per record excluded for a missing /
 *    empty / unrecognized `PartySubTypeID`, emitted by [ScopeFilter] (Req 5.3).
 * Processing continues across the remaining records regardless (Req 4.7).
 *
 * ## Hard failure (Req 4.8)
 * If **any** in-scope profile cannot be turned into an entry — i.e. any
 * [ProfileEntryBuilder.EntryResult] carries a non-null
 * [ProfileEntryBuilder.EntryResult.unbuildableReason] — the whole stage fails
 * with [TransformResult.Failed], so **no partial version** is ever produced. A
 * single unparseable in-scope record aborts the stage; out-of-scope profiles are
 * never built and so can never trigger this failure.
 *
 * ## `record_count` (Req 8, design "transform")
 * The `TransformResult` carries `record_count` **from the snapshot body**. The
 * OFAC **Advanced XML** — the canonical ingestion format — does **not** carry a
 * `Record_Count` element (it exposes only `DateOfIssue`; the `<Record_Count>`
 * element lives in the *legacy* `sdn.xml` / `consolidated.xml` body, not in
 * `*_advanced.xml`). [ParsedSnapshot] therefore does not capture it. To keep this
 * value available for count reconciliation (Req 8.1, consumed by
 * [com.spike.ofac.domain.version.VersionStage]) it is **passed in by the
 * caller** as the raw source-reported string and echoed through unparsed, exactly
 * as `VersionStage.build` expects to receive and validate it (Req 8.4). Passing
 * it through here rather than deriving it keeps the parser single-purpose and
 * lets the caller source the count from wherever the concrete adapter reads it
 * (e.g. an accompanying legacy body, a HEAD header, or the download metadata).
 *
 * The stage is pure over its inputs and performs no I/O beyond consuming the
 * provided [InputStream]; it never mutates any store or pointer (Req 11).
 */
class Transform(
    private val parser: AdvancedXmlStreamParser = AdvancedXmlStreamParser(),
    private val entryBuilder: ProfileEntryBuilder = ProfileEntryBuilder(),
) {

    /**
     * Run the transform over a single validated snapshot's [input] stream.
     *
     * Steps, in order:
     *  1. **Parse** the Advanced XML into a [ParsedSnapshot] (streaming, task 5.1).
     *  2. **Scope-classify** every profile via [ScopeFilter] to derive
     *     `out_of_scope_count` and the unrecognized-type diagnostics (task 3).
     *  3. **Build** entries for the in-scope profiles via [ProfileEntryBuilder],
     *     collecting per-profile diagnostics and any unbuildable reasons (task 5.1).
     *  4. **Hard-fail** if any in-scope profile was unbuildable (Req 4.8); no
     *     partial result is produced.
     *  5. **Deduplicate** within the single list (first-occurrence-wins by
     *     `FixedRef`) via [CrossListDedup] (task 4). Cross-list dedup for the
     *     `SDN_AND_CONSOLIDATED` scope is done by [combine] over two snapshots.
     *
     * @param input the validated snapshot bytes as a stream; the caller owns
     *   closing it (the parser reads it to completion but does not close it).
     * @param scope the configured list scope. For a single-list run this is
     *   typically [ScopeConfig.SDN_ONLY]; when the scope spans both lists this
     *   single-snapshot pass still yields that list's distinct in-scope entries,
     *   and the two lists are merged by [combine] (Req 6, Req 12).
     * @param rawRecordCount the source-reported `Record_Count` **verbatim** from
     *   the snapshot body (or `null` when absent). Echoed into
     *   [TransformResult.Ok.rawRecordCount] for [com.spike.ofac.domain.version.VersionStage]
     *   to validate (Req 8, 8.4). See the class KDoc for why it is passed in.
     */
    fun run(
        input: InputStream,
        scope: ScopeConfig = ScopeConfig.SDN_ONLY,
        rawRecordCount: String? = null,
    ): TransformResult {
        val snapshot = parser.parse(input)
        return fromParsed(snapshot, scope, rawRecordCount)
    }

    /**
     * Run the transform over an already-parsed [snapshot].
     *
     * Split out from [run] so callers that have already parsed (e.g. tests, or a
     * caller combining two lists via [combine]) can reuse the build / filter /
     * dedup pipeline without re-parsing.
     */
    fun fromParsed(
        snapshot: ParsedSnapshot,
        scope: ScopeConfig = ScopeConfig.SDN_ONLY,
        rawRecordCount: String? = null,
    ): TransformResult {
        // --- Scope classification over ALL profiles (Req 5): the out-of-scope
        // count and the unrecognized-type diagnostics come from here (feeds Req 8.1).
        val scopeResult = ScopeFilter.filter(
            snapshot.profiles.map { profile ->
                ScopeFilter.RawProfile(
                    fixedRef = FixedRef(profile.fixedRef.ifBlank { PLACEHOLDER_FIXED_REF }),
                    partySubTypeId = profile.partySubTypeId,
                )
            },
        )

        // --- Build entries for the in-scope profiles (task 5.1). The builder
        // returns one result per in-scope profile, each either an entry (+ soft
        // diagnostics) or an unbuildable reason (Req 4.8).
        val results = entryBuilder.build(snapshot)

        val diagnostics = ArrayList<Diagnostic>()
        diagnostics += scopeResult.diagnostics // UNRECOGNIZED_TYPE, one per excluded (Req 5.3)

        val entries = ArrayList<InternalModelEntry>(results.size)
        for (result in results) {
            diagnostics += result.diagnostics // UNRESOLVED_REF, one per unresolved ref (Req 4.7)
            val entry = result.entry
            if (entry == null) {
                // An in-scope record could not be parsed into an entry: fail the
                // whole stage so no partial version is produced (Req 4.8).
                return TransformResult.Failed(
                    cause = TransformResult.Failed.Cause.UNPARSEABLE_RECORD,
                    fixedRef = result.fixedRef.ifBlank { null },
                    detail = result.unbuildableReason
                        ?: "In-scope record ${result.fixedRef} could not be transformed into an entry",
                )
            }
            entries += entry
        }

        // --- Within-list dedup by FixedRef (task 4). Cross-list dedup across two
        // snapshots is handled by combine(); here scope only ever collapses this
        // single list's own FixedRefs (first-occurrence-wins).
        val deduped = CrossListDedup.deduplicate(
            sdnRecords = entries,
            consolidatedRecords = emptyList(),
            scope = ScopeConfig.SDN_ONLY,
        )

        return TransformResult.Ok(
            entries = deduped,
            outOfScopeCount = scopeResult.outOfScopeCount,
            rawRecordCount = rawRecordCount,
            diagnostics = diagnostics,
        )
    }

    /**
     * Combine two successful single-list transform results into the distinct
     * union for the [ScopeConfig.SDN_AND_CONSOLIDATED] scope (task 4, Req 6).
     *
     * Each list is transformed independently by [run]/[fromParsed]; this function
     * merges their in-scope entries via [CrossListDedup], retaining the SDN
     * representation on any shared `FixedRef` (Req 6.2) and producing exactly one
     * record per distinct `FixedRef` (Req 6.1, 6.3). Out-of-scope counts and
     * diagnostics from both lists are summed / concatenated. `record_count` is
     * left to the caller to reconcile per list, so it is not combined here (each
     * list carries its own).
     *
     * @param sdn the SDN list's successful transform result.
     * @param consolidated the Consolidated list's successful transform result.
     * @return a combined [TransformResult.Ok] over the distinct union, or the
     *   first [TransformResult.Failed] encountered (a failure on either list
     *   fails the combined transform, Req 4.8).
     */
    fun combine(sdn: TransformResult, consolidated: TransformResult): TransformResult {
        if (sdn is TransformResult.Failed) return sdn
        if (consolidated is TransformResult.Failed) return consolidated
        val sdnOk = sdn as TransformResult.Ok
        val consolidatedOk = consolidated as TransformResult.Ok

        val deduped = CrossListDedup.deduplicate(
            mapOf(
                SourceList.SDN to sdnOk.entries,
                SourceList.CONSOLIDATED to consolidatedOk.entries,
            ),
            scope = ScopeConfig.SDN_AND_CONSOLIDATED,
        )

        return TransformResult.Ok(
            entries = deduped,
            outOfScopeCount = sdnOk.outOfScopeCount + consolidatedOk.outOfScopeCount,
            // record_count is per-list and reconciled per list; the combined view
            // does not invent a single count. Callers reconcile each list's count
            // (and the shared-FixedRef overlap term) at the version stage (Req 8.1).
            rawRecordCount = null,
            diagnostics = sdnOk.diagnostics + consolidatedOk.diagnostics,
        )
    }

    private companion object {
        /**
         * A `DistinctParty` with a blank `FixedRef` is malformed and will fail the
         * build (Req 4.8), but [ScopeFilter] needs a non-empty [FixedRef] to run
         * its classification. We substitute a placeholder purely so scope
         * classification does not throw; the real hard-failure is decided by the
         * entry builder, which reports the blank FixedRef as unbuildable.
         */
        const val PLACEHOLDER_FIXED_REF = "<missing-fixedref>"
    }
}

/**
 * Outcome of [Transform.run] (`design.md` `TransformResult`).
 *
 * ```
 * TransformResult = OK(entries, out_of_scope_count, record_count?, diagnostics)
 *                 | FAILED(cause)   # a record could not be parsed (Req 4.8)
 * ```
 */
sealed interface TransformResult {

    /**
     * A successful transform.
     *
     * @property entries the transformed, in-scope, deduplicated entries — the
     *   distinct union by `FixedRef` (Req 4, 6). Each entry's `versionId` is still
     *   `null`; it is stamped at persist time (Req 7.4).
     * @property outOfScopeCount the number of profiles excluded by the scope
     *   filter — vessels + aircraft (Req 5.2) plus missing / empty / unrecognized
     *   types (Req 5.3). Feeds `Expected_Count` (Req 8.1).
     * @property rawRecordCount the source-reported `Record_Count` **from the
     *   snapshot body**, verbatim and unparsed (or `null` when absent). Passed
     *   straight to [com.spike.ofac.domain.version.VersionStage] which validates
     *   and reconciles it (Req 8, 8.4). See [Transform] KDoc for why it is a
     *   caller-supplied value rather than a parsed one.
     * @property diagnostics soft diagnostics accumulated across the snapshot —
     *   one [Diagnostic.Kind.UNRESOLVED_REF] per unresolvable reference (Req 4.7)
     *   and one [Diagnostic.Kind.UNRECOGNIZED_TYPE] per unrecognized-type
     *   exclusion (Req 5.3). Never fatal.
     */
    data class Ok(
        val entries: List<InternalModelEntry>,
        val outOfScopeCount: Int,
        val rawRecordCount: String?,
        val diagnostics: List<Diagnostic>,
    ) : TransformResult

    /**
     * A failed transform: an in-scope record could not be parsed into an entry,
     * so no partial version is produced and `CURRENT` is left unchanged (Req 4.8,
     * Req 11).
     *
     * @property cause the distinct failure cause.
     * @property detail a human-readable description (the builder's
     *   `unbuildableReason`).
     * @property fixedRef the offending record's `FixedRef` when known.
     */
    data class Failed(
        val cause: Cause,
        val detail: String,
        val fixedRef: String? = null,
    ) : TransformResult {

        /** Distinct reasons the transform stage can fail. */
        enum class Cause {
            /** An in-scope record could not be parsed / built into an entry (Req 4.8). */
            UNPARSEABLE_RECORD,
        }
    }
}
