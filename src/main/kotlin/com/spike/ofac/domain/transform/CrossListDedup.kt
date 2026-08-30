package com.spike.ofac.domain.transform

import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.domain.model.SourceList

/**
 * Cross-list deduplication by `FixedRef` with SDN precedence (task 4.1, Req 6).
 *
 * OFAC records may appear in more than one list. The spike observed 93 shared
 * `FixedRef`s between SDN and Consolidated (`spike` "Registros por lista..."),
 * so the persisted dataset must be the **distinct union** of the two lists — one
 * record per distinct `FixedRef` — and never the naive sum, which would
 * double-count overlaps.
 *
 * This is pure logic: it takes the already-in-scope record sets for each list
 * (produced by the scope filter, task 3) and combines them. It performs no I/O
 * and does not stamp `version_id` (that happens in `persist`, task 13).
 *
 * The dedup rules (Req 6):
 *  - **6.1** — the result contains exactly one record per distinct `FixedRef`,
 *    with no duplicates for the same `FixedRef`.
 *  - **6.2** — where a `FixedRef` appears in both lists, the SDN representation
 *    is retained as the governing record and the Consolidated representation is
 *    dropped.
 *  - **6.3** — the result count equals the count of distinct `FixedRef`s across
 *    both lists (the distinct union), which is at most the sum of the two lists'
 *    sizes and equal only when there is no overlap.
 *
 * Dedup applies only when the configured scope includes both lists
 * ([ScopeConfig.SDN_AND_CONSOLIDATED]); under [ScopeConfig.SDN_ONLY] there is no
 * Consolidated set to combine, so [deduplicate] returns the SDN records unchanged.
 */
object CrossListDedup {

    /**
     * Combine the in-scope record sets from the SDN and Consolidated lists into
     * the distinct union keyed by [FixedRef], retaining the SDN representation on
     * overlap (Req 6.1, 6.2, 6.3).
     *
     * The cross-list merge is only performed when [scope] is
     * [ScopeConfig.SDN_AND_CONSOLIDATED]. Under [ScopeConfig.SDN_ONLY] the
     * Consolidated set is ignored and [sdnRecords] is returned as-is (already
     * deduplicated within itself; see [withinListDistinct]).
     *
     * Within each individual list a `FixedRef` is expected to be unique, but this
     * function is defensive: if a single list contains repeated `FixedRef`s, the
     * first occurrence in encounter order wins, so the result is always one record
     * per distinct `FixedRef` (Req 6.1).
     *
     * Encounter order is preserved: SDN records keep their relative order first,
     * followed by the Consolidated-exclusive records in their relative order. This
     * keeps the output deterministic for a deterministic input (supporting Req 11.4).
     *
     * @param sdnRecords the in-scope records sourced from the SDN list.
     * @param consolidatedRecords the in-scope records sourced from the Consolidated
     *   list; ignored when [scope] is [ScopeConfig.SDN_ONLY].
     * @param scope the configured list scope controlling whether the merge runs.
     * @return exactly one record per distinct `FixedRef` (the distinct union),
     *   with SDN governing on overlap.
     */
    fun deduplicate(
        sdnRecords: List<InternalModelEntry>,
        consolidatedRecords: List<InternalModelEntry>,
        scope: ScopeConfig,
    ): List<InternalModelEntry> =
        when (scope) {
            ScopeConfig.SDN_ONLY -> withinListDistinct(sdnRecords)
            ScopeConfig.SDN_AND_CONSOLIDATED ->
                // SDN first so its representation governs on overlap (Req 6.2);
                // Consolidated records only contribute FixedRefs SDN does not
                // already carry, yielding the distinct union (Req 6.1, 6.3).
                withinListDistinct(sdnRecords + consolidatedRecords)
        }

    /**
     * Combine already-classified per-list record sets by their [SourceList] origin.
     *
     * A convenience overload for callers that carry records tagged with the list
     * they came from rather than in two separate collections. Records are grouped
     * by origin and forwarded to [deduplicate]; any list other than
     * [SourceList.SDN] / [SourceList.CONSOLIDATED] is not expected here.
     *
     * @param recordsBySource in-scope records grouped by the list they were
     *   sourced from.
     * @param scope the configured list scope controlling whether the merge runs.
     */
    fun deduplicate(
        recordsBySource: Map<SourceList, List<InternalModelEntry>>,
        scope: ScopeConfig,
    ): List<InternalModelEntry> =
        deduplicate(
            sdnRecords = recordsBySource[SourceList.SDN].orEmpty(),
            consolidatedRecords = recordsBySource[SourceList.CONSOLIDATED].orEmpty(),
            scope = scope,
        )

    /**
     * Reduce a single sequence of records to one per distinct [FixedRef],
     * first-occurrence-wins in encounter order (Req 6.1). This guarantees the
     * within-list uniqueness invariant even if an upstream list unexpectedly
     * repeats a `FixedRef`.
     */
    private fun withinListDistinct(records: List<InternalModelEntry>): List<InternalModelEntry> {
        val seen = HashSet<FixedRef>(records.size)
        val result = ArrayList<InternalModelEntry>(records.size)
        for (record in records) {
            if (seen.add(record.fixedRef)) {
                result.add(record)
            }
        }
        return result
    }
}
