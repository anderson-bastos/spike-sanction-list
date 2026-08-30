package com.spike.ofac.adapter.`in`.scheduling

import com.spike.ofac.adapter.config.SchedulerProperties
import com.spike.ofac.application.Scheduler
import com.spike.ofac.application.SourceListConfig
import com.spike.ofac.application.port.out.RawSnapshotStore
import com.spike.ofac.application.port.out.VersionStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring wiring for the ingestion [Scheduler] (Req 1.1).
 *
 * Provides three beans:
 *  - the [SchedulerProperties] under the **stable bean name `schedulerProperties`**
 *    so [SchedulerTrigger]'s `@Scheduled(fixedDelayString = "#{@schedulerProperties...}")`
 *    SpEL can resolve the configured, bounded, sub-daily interval;
 *  - the list of configured [SourceListConfig]s the scheduler iterates, sourced
 *    from any [SourceListConfig] beans the deployment defines (empty when none
 *    are configured, so the context still starts cleanly before the source
 *    endpoints / scope are decided — pending business decisions);
 *  - the [Scheduler] itself, wired to the concrete [VersionStore] and
 *    [RawSnapshotStore] beans built in task 13.
 *
 * The `@Scheduled` trigger lives on [SchedulerTrigger]; this class only assembles
 * the collaborators.
 */
@Configuration
class SchedulerConfiguration {

    /**
     * Exposes [SchedulerProperties] under the explicit bean name
     * `schedulerProperties` so the [SchedulerTrigger] SpEL expression can read the
     * interval. The properties are bound by `@ConfigurationPropertiesScan`; this
     * bean simply gives them a name the SpEL can address.
     */
    @Bean("schedulerProperties")
    fun schedulerProperties(properties: SchedulerProperties): SchedulerProperties = properties

    /**
     * The configured source lists the scheduler polls (Req 1.1). Collected from
     * any [SourceListConfig] beans; defaults to empty so the application context
     * starts even before the per-list endpoints, scope, and adapters are wired
     * (those depend on pending business decisions — see the plan).
     */
    @Bean
    fun sourceListConfigs(configs: ObjectProvider<SourceListConfig>): List<SourceListConfig> =
        configs.orderedStream().toList()

    /**
     * The ingestion [Scheduler], wired to the concrete store beans (task 13) and
     * the configured [sourceListConfigs].
     */
    @Bean
    fun scheduler(
        versionStore: VersionStore,
        rawSnapshotStore: RawSnapshotStore,
        sourceListConfigs: List<SourceListConfig>,
    ): Scheduler = Scheduler(
        versionStore = versionStore,
        rawSnapshotStore = rawSnapshotStore,
        sourceLists = sourceListConfigs,
    )
}
