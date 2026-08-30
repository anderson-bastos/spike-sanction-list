package com.spike.ofac.pipeline.models

/**
 * The pointer trio for a single [SourceList] (one row per list in the `pointers`
 * table). The three pointers are updated atomically in a single transaction so
 * consumers never observe a window with no active version (Req 9).
 *
 * [current] is never null while any version exists for the list (Req 9.2).
 * [previous] and [nMinus2] fill in as the window rotates (Req 10.1): on activation
 * old CURRENT becomes PREVIOUS and old PREVIOUS becomes N_MINUS_2, keeping at most
 * three HOT versions (Req 10.5). Rollback repoints CURRENT back to PREVIOUS by
 * pointer only (Req 10.3).
 *
 * @property current the active version resolvable to consumers (Req 9.2).
 * @property previous the immediately prior version, or null when none exists yet.
 * @property nMinus2 the version before [previous], or null when none exists yet.
 */
data class VersionPointers(
    val current: VersionId,
    val previous: VersionId? = null,
    val nMinus2: VersionId? = null,
)
