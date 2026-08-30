package com.spike.ofac.pipeline

import com.spike.ofac.pipeline.models.ScopeConfig
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.stages.VersionPlan
import com.spike.ofac.pipeline.stages.VersionStage
import com.spike.ofac.pipeline.stages.transform.AdvancedXmlStreamParser
import com.spike.ofac.pipeline.stages.transform.ParsedSnapshot
import com.spike.ofac.pipeline.stages.transform.ScopeFilter
import com.spike.ofac.pipeline.stages.transform.Transform
import com.spike.ofac.pipeline.stages.transform.TransformResult
import com.spike.ofac.testsupport.Fixtures
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Task 18.1 — end-to-end transform + count-reconciliation on the **real**
 * `ofac-data/` fixtures (Req 4.1, 5.1, 8.1, 8.2).
 *
 * This is the first test that drives the streaming transform over the actual
 * OFAC snapshots rather than hand-built fixtures. It proves three things against
 * the live data:
 *
 *  1. **Scope counts reconcile** (Req 5.1). The Advanced XML profile total splits
 *     cleanly into in-scope (Individual + Entity) and out-of-scope
 *     (Vessel + Aircraft), and `total == in_scope + out_of_scope`.
 *  2. **`Expected_Count` reconciles** (Req 8.1, 8.2). Feeding the source-reported
 *     `Record_Count` as `rawRecordCount`, [VersionStage.build] derives
 *     `expected_count = Record_Count - out_of_scope_count`, and that equals the
 *     number of persisted in-scope entries produced by [Transform] — the exact
 *     equality the `publish` stage requires before activation.
 *  3. **Cross-format cross-check** (Req 4.1). The `Record_Count` reported inside
 *     the *legacy* `sdn.xml` and the row count of `sdn.csv` both equal the
 *     Advanced XML profile total, confirming the three formats describe the same
 *     population.
 *
 * ## Observed counts (this fixture set)
 * These match the spike figures exactly (`spike-ofac.md` §5/§9):
 *
 * | file               | profiles | in-scope | out-of-scope |
 * | ------------------ | -------- | -------- | ------------ |
 * | sdn_advanced.xml   | 19,249   | 17,373   | 1,876        |
 * | cons_advanced.xml  |    481   |    481   |     0        |
 *
 * SDN in-scope splits Entity 9,871 + Individual 7,502 = 17,373; out-of-scope
 * splits Vessel 1,534 + Aircraft 342 = 1,876. `sdn.xml` reports
 * `<Record_Count>19249</Record_Count>` and `sdn.csv` has 19,249 data rows (the
 * OFAC SDN CSV carries no header row), both equal to the Advanced XML total.
 *
 * ## Memory
 * The SDN Advanced XML is ~120 MB. The test streams it through the StAX
 * [AdvancedXmlStreamParser] (never a DOM), so memory stays bounded to roughly one
 * `DistinctParty` plus the reference tables (`spike` §9). The parse is the
 * dominant cost, so this test is intentionally slow for the big file.
 *
 * ## Fixture guard
 * The large XML fixtures are `.gitignore`d. Every test [assumeTrue]-skips when its
 * fixture is absent, so CI without the data still builds green rather than
 * failing.
 */
class RealFixtureTransformReconciliationIntegrationTest {

    private val parser = AdvancedXmlStreamParser()
    private val transform = Transform()

    // The `version` stage only needs (Publish_Date, Digest) for identity; the
    // reconciliation math it performs is independent of both. We use fixed,
    // well-formed placeholders so the test focuses on the count reconciliation.
    private val anyPublishDate = LocalDate.of(2026, 8, 20)
    private val anyDigest = Sha256Digest("a".repeat(64))

    // --- SDN Advanced XML: scope + Expected_Count reconciliation (Req 5.1, 8.1, 8.2) ---

    @Test
    fun `SDN advanced XML reconciles scope counts and Expected_Count against the persisted in-scope entries`() {
        val sdn = Fixtures.SDN_ADVANCED_XML
        assumeTrue(Fixtures.available(sdn), "Fixture missing: $sdn (large file is .gitignored)")

        // --- Streaming parse (bounded memory) ---
        val snapshot = parseStreaming(sdn)

        // (Req 4.1) every DistinctParty profile parsed.
        val totalProfiles = snapshot.profiles.size
        totalProfiles shouldBe EXPECTED_SDN_TOTAL_PROFILES

        // (Req 5.1/5.2) scope classification splits the population exactly.
        val scope = classify(snapshot)
        scope.inScope shouldBe EXPECTED_SDN_IN_SCOPE
        scope.outOfScope shouldBe EXPECTED_SDN_OUT_OF_SCOPE
        // total == in_scope + out_of_scope (no record is lost or double-counted).
        (scope.inScope + scope.outOfScope) shouldBe totalProfiles

        // --- Full transform: build the actual persisted in-scope entries ---
        // Feed the source-reported Record_Count (from sdn.xml / the total) so the
        // version stage can reconcile it exactly.
        val recordCount = EXPECTED_SDN_TOTAL_PROFILES
        val transformResult = transform.fromParsed(
            snapshot,
            scope = ScopeConfig.SDN_ONLY,
            rawRecordCount = recordCount.toString(),
        )

        val ok = transformResult.shouldBeInstanceOf<TransformResult.Ok>()
        // The transform's own out-of-scope count agrees with the standalone
        // scope classification above.
        ok.outOfScopeCount shouldBe EXPECTED_SDN_OUT_OF_SCOPE
        // (Req 8.2) persisted in-scope entry count equals the in-scope profiles.
        ok.entries.size shouldBe EXPECTED_SDN_IN_SCOPE

        // --- version.build: Expected_Count reconciliation (Req 8.1, 8.2) ---
        val plan = VersionStage.build(
            entries = ok.entries,
            publishDate = anyPublishDate,
            digest = anyDigest,
            scope = ScopeConfig.SDN_ONLY,
            rawRecordCount = ok.rawRecordCount,
            outOfScopeCount = ok.outOfScopeCount,
        )
        val accepted = plan.shouldBeInstanceOf<VersionPlan.Accepted>()

        // expected_count = Record_Count - out_of_scope_count (single-list: no overlap term).
        accepted.expectedCount shouldBe (recordCount - EXPECTED_SDN_OUT_OF_SCOPE)
        // ... and that is exactly the in-scope figure ...
        accepted.expectedCount shouldBe EXPECTED_SDN_IN_SCOPE
        // ... which the publish stage requires to equal the persisted count exactly (Req 8.2).
        accepted.expectedCount shouldBe ok.entries.size
    }

    // --- Consolidated Advanced XML: scope + Expected_Count (Req 5.1, 8.1, 8.2) ---

    @Test
    fun `Consolidated advanced XML yields 481 in-scope profiles and reconciles Expected_Count`() {
        val cons = Fixtures.CONS_ADVANCED_XML
        assumeTrue(Fixtures.available(cons), "Fixture missing: $cons")

        val snapshot = parseStreaming(cons)

        val totalProfiles = snapshot.profiles.size
        totalProfiles shouldBe EXPECTED_CONS_TOTAL_PROFILES

        val scope = classify(snapshot)
        // Consolidated carries only Entity/Individual in this fixture: all in scope.
        scope.inScope shouldBe EXPECTED_CONS_IN_SCOPE
        scope.outOfScope shouldBe EXPECTED_CONS_OUT_OF_SCOPE
        (scope.inScope + scope.outOfScope) shouldBe totalProfiles

        val recordCount = EXPECTED_CONS_TOTAL_PROFILES
        val ok = transform.fromParsed(
            snapshot,
            scope = ScopeConfig.SDN_ONLY, // single-list pass; scope arg only gates the overlap term
            rawRecordCount = recordCount.toString(),
        ).shouldBeInstanceOf<TransformResult.Ok>()

        ok.entries.size shouldBe EXPECTED_CONS_IN_SCOPE
        ok.outOfScopeCount shouldBe EXPECTED_CONS_OUT_OF_SCOPE

        val accepted = VersionStage.build(
            entries = ok.entries,
            publishDate = anyPublishDate,
            digest = anyDigest,
            scope = ScopeConfig.SDN_ONLY,
            rawRecordCount = ok.rawRecordCount,
            outOfScopeCount = ok.outOfScopeCount,
        ).shouldBeInstanceOf<VersionPlan.Accepted>()

        accepted.expectedCount shouldBe (recordCount - EXPECTED_CONS_OUT_OF_SCOPE)
        accepted.expectedCount shouldBe ok.entries.size
    }

    // --- Cross-format cross-check of Record_Count (Req 4.1) ---

    @Test
    fun `legacy sdn_xml Record_Count matches the SDN advanced XML profile total`() {
        val legacy = Fixtures.SDN_XML
        assumeTrue(Fixtures.available(legacy), "Fixture missing: $legacy")

        val recordCount = readLegacyRecordCount(legacy)
        // The legacy XML reports the same population size as the advanced XML total.
        recordCount shouldBe EXPECTED_SDN_TOTAL_PROFILES

        // When the advanced fixture is also present, cross-check the live parse total.
        val advanced = Fixtures.SDN_ADVANCED_XML
        if (Fixtures.available(advanced)) {
            val advancedTotal = parseStreaming(advanced).profiles.size
            advancedTotal shouldBe recordCount
        }
    }

    @Test
    fun `sdn_csv data-row count matches the SDN advanced XML Record_Count`() {
        val csv = Fixtures.SDN_CSV
        assumeTrue(Fixtures.available(csv), "Fixture missing: $csv")

        val rows = countCsvRecords(csv)
        rows shouldBeGreaterThan 0
        // OFAC's SDN CSV has no header row. Each record begins with a numeric
        // `ent_num` id in the first column, but a record can span multiple
        // physical lines because the free-text remarks column may contain
        // embedded newlines inside its quotes (raw `wc -l` therefore over-counts).
        // Counting records by the leading numeric id yields the true record
        // count, which equals the advanced/legacy XML Record_Count.
        rows shouldBe EXPECTED_SDN_TOTAL_PROFILES
    }

    // --- Helpers --------------------------------------------------------------

    private data class ScopeCounts(val inScope: Int, val outOfScope: Int)

    /** Classify every parsed profile by PartySubTypeID (the same rule transform uses). */
    private fun classify(snapshot: ParsedSnapshot): ScopeCounts {
        val result = ScopeFilter.filter(
            snapshot.profiles.map { p ->
                ScopeFilter.RawProfile(
                    fixedRef = com.spike.ofac.pipeline.models.FixedRef(
                        p.fixedRef.ifBlank { "<missing>" },
                    ),
                    partySubTypeId = p.partySubTypeId,
                )
            },
        )
        return ScopeCounts(inScope = result.kept.size, outOfScope = result.outOfScopeCount)
    }

    /** Parse a fixture with the streaming StAX parser; the stream is buffered and closed here. */
    private fun parseStreaming(path: Path): ParsedSnapshot =
        openStream(path).use { parser.parse(it) }

    private fun openStream(path: Path): InputStream =
        BufferedInputStream(Files.newInputStream(path))

    /** Read `<Record_Count>N</Record_Count>` from the legacy XML body without a full parse. */
    private fun readLegacyRecordCount(path: Path): Int {
        val regex = Regex("<Record_Count>\\s*(\\d+)\\s*</Record_Count>")
        Files.newBufferedReader(path).useLines { lines ->
            for (line in lines) {
                regex.find(line)?.let { return it.groupValues[1].toInt() }
            }
        }
        error("No <Record_Count> element found in $path")
    }

    /**
     * Count records in the OFAC SDN CSV. The file has no header; each record
     * starts with a numeric `ent_num` in the first column. Continuation lines
     * produced by embedded newlines inside the quoted remarks column do not start
     * with a digit, so counting lines that begin with a numeric id gives the true
     * record count.
     */
    private fun countCsvRecords(path: Path): Int {
        val leadingId = Regex("^\\d+,")
        return Files.newBufferedReader(path).useLines { lines ->
            lines.count { leadingId.containsMatchIn(it) }
        }
    }

    private companion object {
        // Observed on this fixture set; equal to the spike figures (spike §5/§9).
        const val EXPECTED_SDN_TOTAL_PROFILES = 19_249
        const val EXPECTED_SDN_IN_SCOPE = 17_373 // Entity 9,871 + Individual 7,502
        const val EXPECTED_SDN_OUT_OF_SCOPE = 1_876 // Vessel 1,534 + Aircraft 342

        const val EXPECTED_CONS_TOTAL_PROFILES = 481
        const val EXPECTED_CONS_IN_SCOPE = 481 // Entity 363 + Individual 118
        const val EXPECTED_CONS_OUT_OF_SCOPE = 0
    }
}
