package com.spike.ofac.pipeline.models

/**
 * The configured list scope for an ingestion deployment (Req 12).
 *
 * These are the only two valid values (Req 12.1): [SDN_ONLY] for a maximum-risk
 * MVP, and [SDN_AND_CONSOLIDATED] for full compliance coverage (which engages the
 * cross-list dedup path, Req 6/12.3). `CONSOLIDATED_ONLY` is deliberately absent
 * because it is rejected (Req 12.4), as are absent/empty/unrecognized values
 * (Req 12.5) — those are surfaced as errors by the scope-validation step (task 14),
 * not represented here.
 *
 * There is **no committed default**: the default list scope is a pending business
 * decision, so callers must supply a value explicitly rather than relying on one
 * baked in here.
 */
enum class ScopeConfig {
    /** Ingest only the SDN list; no Consolidated record is persisted (Req 12.2). */
    SDN_ONLY,

    /** Ingest SDN + Consolidated, running the dedup path on overlap (Req 12.3). */
    SDN_AND_CONSOLIDATED,
}
