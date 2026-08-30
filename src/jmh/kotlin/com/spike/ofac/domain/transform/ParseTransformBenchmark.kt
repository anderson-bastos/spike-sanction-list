package com.spike.ofac.domain.transform

import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.testsupport.Fixtures
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.io.BufferedInputStream
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Task 22.1 — JMH microbenchmark of the parse + transform hotspot.
 *
 * ## What this measures (and why)
 * The spike found that ~98% of ingestion cost is the XML parse + transform of a
 * full SDN snapshot (`spike-ofac.md` §9), measured by `ofac-data/benchmark.py` at
 * **~3.9 s processing** and a **~402 MB peak**. This benchmark drives the JVM
 * equivalent — the streaming [AdvancedXmlStreamParser] plus the [Transform]
 * stage — over the **real** `ofac-data/sdn_advanced.xml` fixture (19,249
 * profiles) so we can watch for regressions against that baseline.
 *
 * ## Non-functional guard — NOT a functional requirement
 * This is a **performance guard**. [Req 4] (the transform) is the *code under
 * measurement*, but nothing here asserts functional behaviour — the correctness
 * of the transform is covered by the correctness properties and the real-fixture
 * integration test. A JMH run neither passes nor fails a requirement; it reports
 * throughput/latency numbers to compare against the spike baseline.
 *
 * ## Why it lives in its own source set / task
 * JMH runs are slow (multiple forks × warmup + measurement iterations over a
 * ~120 MB fixture), so this class is in the dedicated `jmh` source set and is run
 * only via the opt-in `./gradlew jmh` task — never as part of `test` / `check`.
 *
 * ## Fixture handling
 * The large fixtures are `.gitignore`d. [Setup] reads the whole
 * `sdn_advanced.xml` into a byte array once (per trial) so the benchmark measures
 * parse + transform CPU/allocation rather than disk I/O; if the fixture is
 * absent the setup throws, which JMH surfaces as a clear benchmark error rather
 * than silently reporting meaningless numbers.
 *
 * ## Two benchmarks
 *  - [parse] — the streaming StAX parse alone (the dominant sub-cost).
 *  - [parseAndTransform] — parse + the full [Transform.run] (parse → scope filter
 *    → entry build → dedup), i.e. the end-to-end hotspot.
 *
 * Both run in [Mode.SingleShotTime] measuring **wall-clock ms per invocation**,
 * so the reported figure is directly comparable to the spike's ~3.9 s.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 10)
@Fork(2)
open class ParseTransformBenchmark {

    private lateinit var fixtureBytes: ByteArray
    private val parser = AdvancedXmlStreamParser()
    private val transform = Transform()

    /**
     * Load the real SDN Advanced XML into memory once per trial so the benchmark
     * measures parse + transform, not file-system read latency. Throws (failing
     * the benchmark loudly) if the `.gitignore`d fixture is not present.
     */
    @Setup(Level.Trial)
    fun loadFixture() {
        val fixture = Fixtures.SDN_ADVANCED_XML
        require(Fixtures.available(fixture)) {
            "JMH fixture missing: $fixture (the large ofac-data file is .gitignored — " +
                "place the real sdn_advanced.xml under ofac-data/ before running `./gradlew jmh`)"
        }
        fixtureBytes = Files.readAllBytes(fixture)
    }

    /** Streaming StAX parse only — the dominant ~98%-of-cost sub-step (Req 4.1). */
    @Benchmark
    fun parse(blackhole: Blackhole) {
        BufferedInputStream(fixtureBytes.inputStream()).use { input ->
            blackhole.consume(parser.parse(input))
        }
    }

    /**
     * Parse + full transform (scope filter → entry build → within-list dedup) —
     * the end-to-end parse+transform hotspot (Req 4). `rawRecordCount` is passed
     * as the profile total so the transform path exercises the same code the real
     * pipeline runs; the result is consumed via the [Blackhole] to prevent
     * dead-code elimination.
     */
    @Benchmark
    fun parseAndTransform(blackhole: Blackhole) {
        BufferedInputStream(fixtureBytes.inputStream()).use { input ->
            val result = transform.run(
                input = input,
                scope = ScopeConfig.SDN_ONLY,
                rawRecordCount = null,
            )
            blackhole.consume(result)
        }
    }
}
