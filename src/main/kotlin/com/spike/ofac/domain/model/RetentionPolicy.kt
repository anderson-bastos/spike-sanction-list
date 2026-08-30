package com.spike.ofac.domain.model

import java.time.Duration

/**
 * Configuration for historical retention of displaced (COLD) versions (Req 14).
 *
 * Retention is a **pending business decision** kept fully configurable with **no
 * committed default** — none of these fields carries a hard-coded value here. The
 * `RetentionManager` (design) reads this policy rather than assuming values.
 *
 * @property enabled when false, versions displaced beyond the three HOT versions are
 *   discarded outright (Req 14.4); when true they are retained per [retentionPeriod].
 * @property retentionPeriod how long a COLD version is kept; null means unbounded /
 *   undecided. A pending business decision, no fixed default (Req 14).
 * @property preserve what is preserved for a retained version; RAW is required for
 *   faithful reconstruction (Req 14.3). Pending decision, no default.
 */
data class RetentionPolicy(
    val enabled: Boolean,
    val retentionPeriod: Duration? = null,
    val preserve: PreserveKind,
)

/**
 * What artifact(s) a retained version keeps.
 *
 * [RAW] is the only faithful source for reconstruction (Req 14.3); [MODEL] keeps the
 * normalized internal model; [BOTH] keeps both. Which one applies is a pending
 * business decision, so no default is committed.
 */
enum class PreserveKind {
    RAW,
    MODEL,
    BOTH,
}
