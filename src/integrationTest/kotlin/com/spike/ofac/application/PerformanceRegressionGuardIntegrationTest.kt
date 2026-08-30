package com.spike.ofac.application

import com.spike.ofac.adapter.out.persistence.PgVersionStore

import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.transform.AdvancedXmlStreamParser
import com.spike.ofac.domain.transform.ParsedSnapshot
import com.spike.ofac.domain.transform.Transform
import com.spike.ofac.domain.transform.TransformResult
import com.spike.ofac.adapter.out.persistence.InMemoryVersionStore
import com.spike.ofac.application.port.out.PointerKind
import com.spike.ofac.testsupport.Fixtures
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.system.measureTimeMillis

/**
 * Task 22.3 — a **lightweight time/memory regression guard** over the real
 * `sdn_advanced.xml` fixture, plus the **atomic-activation SLA** (Req 9.5).
 *
 * ## What this is (and isn't)
 * This is a *non-functional performance guard*, not a functional requirement. It
 * is a cheap early-warning check meant to run in CI as a plain JUnit test — a
 * single parse+transform pass over the real 19,249-profile SDN Advanced XML with
 * coarse wall-clock and peak-memory assertions. It is intentionally distinct
 * from, and much cheaper than, the rigorous JMH microbenchmark (task 22.1): JMH
 * measures throughput/allocation with warmup and statistics; this test just
 * trips a wire if a change makes the hotspot dramatically slower or hungrier.
 *
 * The functional correctness of the parse/transform (exact counts, scope split,
 * `Expected_Count` reconciliation) is covered by
 * [RealFixtureTransformReconciliationIntegrationTest] and the property tests —
 * this guard deliberately does **not** re-assert them.
 *
 * ## Thresholds are deliberately generous
 * The spike baseline (`ofac-data/benchmark.py`, `spike-ofac.md` §9) measured
 * ~3.9 s processing and ~402 MB peak on the SDN Advanced XML. These bounds are
 * set several times higher than that baseline so ordinary CI-runner jitter,
 * cold JITs, and GC noise do **not** cause flaky failures. The guard only fires
 * on a gross regression (e.g. an accidental DOM load, or an O(n²) blow-up), not
 * on a modest slowdown.
 *
 * ## Fixture guard
 * The large XML fixture is `.gitignore`d, so every test [assumeTrue]-skips when
 * the file is absent — CI without the data still builds green.
 */
class PerformanceRegressionGuardIntegrationTest {

    private val parser = AdvancedXmlStreamParser()
    private val transform = Transform()

    // --- (1) parse + transform time/memory regression guard (Req 4 — code under measurement) ---

    @Test
    fun `parse plus transform of the real SDN advanced XML stays within generous time and memory bounds`() {
        val sdn = Fixtures.SDN_ADVANCED_XML
        assumeTrue(Fixtures.available(sdn), "Fixture missing: $sdn (large file is .gitignored)")

        // Settle the heap and take a pre-run baseline so the delta reflects the
        // parse+transform working set rather than whatever the JVM held before.
        val runtime = Runtime.getRuntime()
        forceGc()
        val usedBefore = usedHeapBytes(runtime)

        var result: TransformResult
        val elapsedMs = measureTimeMillis {
            val snapshot: ParsedSnapshot = parseStreaming(sdn)
            result = transform.fromParsed(
                snapshot,
                scope = ScopeConfig.SDN_ONLY,
                rawRecordCount = snapshot.profiles.size.toString(),
            )
        }

        // Sanity: the pass actually produced the in-scope model (guards against a
        // silently-empty run making the timing/memory numbers meaningless).
        val ok = result.shouldBeInstanceOf<TransformResult.Ok>()
        (ok.entries.size > 0) shouldBe true

        // Peak heap growth attributable to the pass. Sampling after the run (before
        // any GC) approximates the working-set peak for a streaming parse.
        val usedAfter = usedHeapBytes(runtime)
        val heapGrowthMb = ((usedAfter - usedBefore).coerceAtLeast(0)) / (1024 * 1024)

        // --- Generous early-warning bounds (baseline ~3.9 s / ~402 MB) ---
        // Time: 30 s is ~7.5x the spike baseline — absorbs cold JIT + CI jitter,
        // but a DOM load or O(n^2) regression blows well past it.
        assert(elapsedMs < TIME_BUDGET_MS) {
            "parse+transform took ${elapsedMs}ms, exceeding the ${TIME_BUDGET_MS}ms early-warning budget " +
                "(spike baseline ~3.9s). This is a coarse regression guard, not a hard perf gate."
        }
        // Memory: 1500 MB is ~3.7x the spike ~402 MB peak — a streaming parse should
        // stay far under it; a DOM materialization of the ~120 MB file would not.
        assert(heapGrowthMb < MEMORY_BUDGET_MB) {
            "parse+transform grew the heap by ~${heapGrowthMb}MB, exceeding the ${MEMORY_BUDGET_MB}MB " +
                "early-warning budget (spike peak ~402MB). Suspect a non-streaming (DOM) parse."
        }
    }

    // --- (2) atomic-activation SLA: a new CURRENT is resolvable within 5 s (Req 9.5) ---

    @Test
    fun `a newly activated CURRENT is resolvable well within the 5 second SLA`() {
        // A cheap, Docker-free SLA check against the version-pointer state machine:
        // the same atomicSetCurrent contract the PgVersionStore upholds (task 13.5).
        // Req 9.5: once activation completes, the new CURRENT must resolve to
        // consumers within 5 seconds.
        val store = InMemoryVersionStore()
        val v = VersionId(LocalDate.of(2026, 8, 20), Sha256Digest("a".repeat(64)))

        store.putIsolatedFor(
            sourceList = SourceList.SDN,
            versionId = v,
            records = listOf(
                InternalModelEntry(
                    fixedRef = FixedRef("1"),
                    entityType = EntityType.Individual,
                    primaryName = "Guard Subject",
                    sanctionPrograms = listOf("PROGRAM"),
                ),
            ),
        )

        // Activation must complete and the new CURRENT must resolve — inside 5 s.
        val elapsedMs = measureTimeMillis {
            store.atomicSetCurrent(SourceList.SDN, v) shouldBe true
            store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe v
        }

        assert(elapsedMs < ACTIVATION_SLA_MS) {
            "activation + CURRENT resolution took ${elapsedMs}ms, exceeding the ${ACTIVATION_SLA_MS}ms SLA (Req 9.5)"
        }
    }

    // --- Helpers --------------------------------------------------------------

    private fun parseStreaming(path: Path): ParsedSnapshot =
        openStream(path).use { parser.parse(it) }

    private fun openStream(path: Path): InputStream =
        BufferedInputStream(Files.newInputStream(path))

    private fun usedHeapBytes(runtime: Runtime): Long = runtime.totalMemory() - runtime.freeMemory()

    /** Best-effort settle of the heap before sampling the pre-run baseline. */
    private fun forceGc() {
        System.gc()
        Thread.sleep(100)
        System.gc()
    }

    private companion object {
        // Baseline: spike measured ~3.9 s processing, ~402 MB peak (spike-ofac.md §9).
        // Bounds are set generously above baseline so this stays an early-warning
        // guard, not a flaky hard gate.
        const val TIME_BUDGET_MS = 30_000L // ~7.5x the ~3.9 s baseline
        const val MEMORY_BUDGET_MB = 1_500L // ~3.7x the ~402 MB peak
        const val ACTIVATION_SLA_MS = 5_000L // Req 9.5: new CURRENT resolvable within 5 s
    }
}
