package com.spike.ofac.adapter.`in`.scheduling

import com.spike.ofac.adapter.out.source.OfacAdapter
import com.spike.ofac.application.Scheduler
import com.spike.ofac.application.SourceListConfig
import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.domain.model.SourceList
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Production wiring that registers the OFAC **SDN** `Source_List` so the running
 * pipeline actually has something to ingest (Req 1.1, Req 13).
 *
 * The core [SchedulerConfiguration] collects every [SourceListConfig] bean via an
 * `ObjectProvider`; until now no such bean existed (the per-list endpoint/scope
 * were pending business decisions), so a live context started with an empty list
 * and never imported anything. This class supplies the SDN entry:
 *  - the live OFAC Sanctions List Service (SLS) `SDN_ADVANCED.XML` endpoint,
 *  - [ScopeConfig.SDN_ONLY] (persist only the SDN list, Req 12.2),
 *  - the credential-free [OfacAdapter] (OFAC publishes anonymously, Req 2.3).
 *
 * The endpoint is externalized as `ofac.source.sdn.url` (defaulting to the
 * canonical SLS URL) and the whole bean is gated by
 * `ofac.source.sdn.enabled` (default `true`) so a deployment can opt out.
 */
@Configuration
class OfacSourceListWiring {

    /**
     * The SDN `Source_List` the scheduler polls. Picked up by
     * [SchedulerConfiguration.sourceListConfigs].
     *
     * @param url the SLS SDN Advanced XML endpoint (Req 2.1).
     */
    @Bean
    @ConditionalOnProperty(prefix = "ofac.source.sdn", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun sdnSourceList(
        @Value("\${ofac.source.sdn.url:https://sanctionslistservice.ofac.treas.gov/api/PublicationPreview/exports/SDN_ADVANCED.XML}")
        url: String,
    ): SourceListConfig = SourceListConfig(
        sourceList = SourceList.SDN,
        url = URI.create(url),
        scope = ScopeConfig.SDN_ONLY,
        adapter = OfacAdapter(),
    )
}

/**
 * One-shot bootstrap runner (profile `bootstrap`) that fires a single ingestion
 * cycle at startup instead of waiting for the scheduled interval — for running
 * the very first import on demand.
 *
 * Activate with `--spring.profiles.active=bootstrap`. Outside that profile the
 * pipeline behaves normally: the scheduled trigger drives it on the configured
 * interval, and this runner is absent.
 */
@Configuration
@Profile("bootstrap")
class BootstrapImportRunner {

    private val log = LoggerFactory.getLogger(BootstrapImportRunner::class.java)

    /**
     * Runs one [Scheduler.tick] over all configured `Source_List`s at startup and
     * logs the outcome per list, so the first import happens immediately and its
     * result (activated version / skipped / failed stage) is observable.
     */
    @Bean
    fun bootstrapImport(scheduler: Scheduler): ApplicationRunner = ApplicationRunner {
        log.info("[bootstrap] running one ingestion cycle on demand...")
        scheduler.tick()
        for (sourceList in SourceList.entries) {
            scheduler.lastOutcome(sourceList)?.let { outcome ->
                log.info("[bootstrap] {} -> {}", sourceList, outcome)
            }
        }
        log.info("[bootstrap] ingestion cycle complete.")
    }
}
