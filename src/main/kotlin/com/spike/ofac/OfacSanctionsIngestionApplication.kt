package com.spike.ofac

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Entry point for the OFAC sanctions ingestion pipeline.
 *
 * The pipeline is realized as six source-independent stages
 * (obtain -> validate -> transform -> version -> persist -> publish)
 * driven by a Scheduler and parameterized by a per-source SourceAdapter,
 * as described in the design document.
 *
 * @EnableScheduling backs the Scheduler contract (Spring @Scheduled, no OS cron).
 * @ConfigurationPropertiesScan binds the pipeline configuration types
 * (e.g. Raw_Snapshot_Store folder path, scope, retention).
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
class OfacSanctionsIngestionApplication

fun main(args: Array<String>) {
    runApplication<OfacSanctionsIngestionApplication>(*args)
}
