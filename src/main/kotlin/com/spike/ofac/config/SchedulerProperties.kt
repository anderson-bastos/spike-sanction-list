package com.spike.ofac.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for the ingestion [Scheduler][com.spike.ofac.pipeline.scheduler.Scheduler].
 *
 * The scheduler polls each configured `Source_List` on a **configurable, bounded**
 * interval defaulting to **sub-daily** (Req 1.1), realized with Spring
 * `@Scheduled` (no OS cron). Bound from properties prefixed `ofac.scheduler` (see
 * `application.yml`).
 *
 * @property interval the polling period between ticks. Defaults to 6 hours — a
 *   sub-daily default (Req 1.1). Validated at startup to sit within
 *   [MIN_INTERVAL]..[MAX_INTERVAL]: bounded below so a misconfiguration cannot
 *   hammer the source, and at/under a day so it always stays sub-daily.
 */
@ConfigurationProperties(prefix = "ofac.scheduler")
data class SchedulerProperties(
    val interval: Duration = DEFAULT_INTERVAL,
) {
    init {
        require(interval >= MIN_INTERVAL) {
            "ofac.scheduler.interval must be at least $MIN_INTERVAL, was $interval"
        }
        require(interval <= MAX_INTERVAL) {
            "ofac.scheduler.interval must be at most $MAX_INTERVAL (sub-daily), was $interval"
        }
    }

    companion object {
        /** Lower bound: at least one minute between ticks, so the source is never hammered. */
        val MIN_INTERVAL: Duration = Duration.ofMinutes(1)

        /** Upper bound: at most one day, keeping the polling sub-daily (Req 1.1). */
        val MAX_INTERVAL: Duration = Duration.ofDays(1)

        /** Sub-daily default when no interval is configured (Req 1.1). */
        val DEFAULT_INTERVAL: Duration = Duration.ofHours(6)
    }
}
