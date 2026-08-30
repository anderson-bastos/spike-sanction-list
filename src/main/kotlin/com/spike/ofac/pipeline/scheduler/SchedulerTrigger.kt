package com.spike.ofac.pipeline.scheduler

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * The Spring `@Scheduled` trigger that drives the [Scheduler] on the configured,
 * bounded interval (Req 1.1) — the only Spring-aware piece of the scheduling
 * seam. No OS cron is involved; the interval comes from
 * [SchedulerProperties.interval][com.spike.ofac.config.SchedulerProperties.interval]
 * (default sub-daily), read here as milliseconds via SpEL.
 *
 * Keeping the `@Scheduled` method on this thin wrapper — separate from the
 * [Scheduler] orchestration itself — lets the pure cycle logic be unit-tested
 * without any Spring scheduling infrastructure, while `@EnableScheduling` (on the
 * application class) picks this bean up in production.
 *
 * @property scheduler the orchestrator whose [Scheduler.tick] runs one cycle per
 *   configured `Source_List` on every fire.
 */
@Component
class SchedulerTrigger(
    private val scheduler: Scheduler,
) {

    /**
     * Fires on the configured interval and runs one ingestion cycle per configured
     * `Source_List` (Req 1.1). `fixedDelayString` reads
     * `ofac.scheduler.interval` (a [java.time.Duration]) and converts it to
     * milliseconds; the `initialDelayString` gives the context time to finish
     * starting before the first poll.
     *
     * A `fixedDelay` (not `fixedRate`) means the next tick starts only after the
     * previous one finishes, so a slow or failing cycle never overlaps itself —
     * the retry is simply the next scheduled fire (Req 1.6, Req 11.2).
     */
    @Scheduled(
        fixedDelayString = "#{@schedulerProperties.interval.toMillis()}",
        initialDelayString = "#{@schedulerProperties.interval.toMillis()}",
    )
    fun poll() {
        scheduler.tick()
    }
}
