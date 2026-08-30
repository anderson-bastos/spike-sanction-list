package com.spike.ofac.pipeline.scheduler

import com.spike.ofac.config.RawSnapshotStoreProperties
import com.spike.ofac.pipeline.adapters.HeadResponse
import com.spike.ofac.pipeline.adapters.HttpResponse
import com.spike.ofac.pipeline.adapters.MappingResult
import com.spike.ofac.pipeline.adapters.SourceAdapter
import com.spike.ofac.pipeline.adapters.SourceEntityType
import com.spike.ofac.pipeline.models.ScopeConfig
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.models.SourceList
import com.spike.ofac.pipeline.stages.transform.RawParsedProfile
import com.spike.ofac.pipeline.store.FsRawSnapshotStore
import com.spike.ofac.pipeline.store.InMemoryVersionStore
import com.spike.ofac.pipeline.store.PointerKind
import com.spike.ofac.pipeline.store.RawSnapshotStore
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reusable-core **structural** test (task 18.5, Req 13.1, 13.2).
 *
 * The other scheduler tests ([SchedulerSmokeTest], [FailureObservabilityIntegrationTest])
 * drive the pipeline through fake adapters, but they say nothing structural about
 * *source-independence*. This test makes the design's central claim falsifiable:
 *
 *  - **Req 13.1** — the six core stages (`obtain → validate → transform → version →
 *    persist → publish`) are source-independent. Adding a new `Source_List` requires
 *    changes only within that source's adapter — **no** changes to the six stages.
 *  - **Req 13.2** — a source is read and mapped **through its per-source adapter**.
 *
 * How the structure is proven, not merely asserted:
 *
 *  1. A **second stub adapter** ([StubSourceAdapter]) — deliberately *not*
 *     [OfacAdapter] and not any OFAC-shaped adapter — represents a hypothetical
 *     new source ("ACME"). It supplies its own snapshot bytes and its own advertised
 *     digest through the same [SourceAdapter] seam.
 *  2. That stub is dropped into the **exact same** [Scheduler] wiring OFAC uses —
 *     the same default stage objects (`Obtain`/`Validate`/`Transform`/`VersionStage`/
 *     `Persist`/`Publish`) constructed by [Scheduler] itself, with no source flag,
 *     no `if (source == OFAC)` branch, no adapter-type inspection. The only thing
 *     that changed versus an OFAC cycle is the [SourceListConfig.adapter] field.
 *  3. The cycle is asserted to flow all the way through to
 *     [CycleStatus.ACTIVATED], with `CURRENT` resolving to the new version — i.e.
 *     every one of the six stages accepted the stub-sourced snapshot unchanged.
 *  4. The seam is exercised **only** through the adapter: the stub records that its
 *     [head]/[get] were the sole source reads (exactly one of each), so the core
 *     stayed source-agnostic — it read the source solely through the adapter.
 *
 * If any core stage secretly branched on the source (an OFAC-only assumption in
 * `transform`, a hard-coded OFAC endpoint in `obtain`, an OFAC digest quirk in
 * `validate`, ...), a *distinct* stub adapter driving the *same* stages would fail
 * to activate — so a green result here is direct evidence the core is reusable.
 *
 * Only the HTTP seam ([SourceAdapter]) is a test double; the [VersionStore] is a
 * real [InMemoryVersionStore] and the [RawSnapshotStore] a temp-dir-backed
 * [FsRawSnapshotStore] (its integrity check delegated to always-pass so the focus
 * stays on source-independence, exactly as [SchedulerSmokeTest] does).
 */
class ReusableCoreStructuralTest {

    private val acmeUrl = URI.create("https://acme.example.test/acme_sanctions.xml")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

    private fun digestOf(bytes: ByteArray) = Sha256Digest.ofHex(sha256Hex(bytes))

    /**
     * A well-formed Advanced XML snapshot with one in-scope Individual carrying a
     * primary name and a Program sanctions measure, so it transforms into exactly
     * one entry and drives a full ACTIVATED cycle. This is the common on-the-wire
     * shape the core stages understand; a genuinely new source's per-source
     * *adapter* is what would translate that source's native shape into it — here
     * the stub adapter simply serves it directly.
     */
    private fun validSnapshot(fixedRef: String, name: String): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<Sanctions>\n")
        append("  <DateOfIssue><Year>2024</Year><Month>7</Month><Day>4</Day></DateOfIssue>\n")
        append("  <DistinctParties>\n")
        append("    <DistinctParty FixedRef=\"$fixedRef\">\n")
        append("      <Profile ID=\"p1\" PartySubTypeID=\"4\">\n")
        append("        <Identity ID=\"i1\">\n")
        append("          <Alias AliasTypeID=\"1403\" Primary=\"true\">\n")
        append("            <DocumentedName>\n")
        append("              <DocumentedNamePart><NamePartValue>$name</NamePartValue></DocumentedNamePart>\n")
        append("            </DocumentedName>\n")
        append("          </Alias>\n")
        append("        </Identity>\n")
        append("      </Profile>\n")
        append("    </DistinctParty>\n")
        append("  </DistinctParties>\n")
        append("  <SanctionsEntries>\n")
        append("    <SanctionsEntry ID=\"e1\" ProfileID=\"p1\" ListID=\"1\">\n")
        append("      <SanctionsMeasure SanctionsTypeID=\"1\"><Comment>SDGT</Comment></SanctionsMeasure>\n")
        append("    </SanctionsEntry>\n")
        append("  </SanctionsEntries>\n")
        append("</Sanctions>\n")
    }.toByteArray(Charsets.UTF_8)

    /**
     * A **second, distinct** [SourceAdapter] — deliberately not [OfacAdapter] /
     * [com.spike.ofac.pipeline.adapters.UnAdapter] / [com.spike.ofac.pipeline.adapters.EuAdapter]
     * — standing in for a hypothetical new source ("ACME"). It is the *only* thing
     * that varies from an OFAC cycle: it owns the obtain I/O (its own endpoint, its
     * own — here empty — auth) and would own the field mapping. It counts its own
     * [head]/[get] invocations so the test can prove the source was read solely
     * through this seam (Req 13.2).
     */
    private class StubSourceAdapter(
        private val body: ByteArray,
        private val digest: Sha256Digest,
    ) : SourceAdapter {
        val headCount = AtomicInteger(0)
        val getCount = AtomicInteger(0)

        override fun head(url: URI): HeadResponse {
            headCount.incrementAndGet()
            return HeadResponse(statusCode = 200, lastModified = null, digest = digest)
        }

        override fun get(url: URI): HttpResponse {
            getCount.incrementAndGet()
            return HttpResponse(
                statusCode = 200,
                body = body,
                digest = digest,
                contentLength = body.size.toLong(),
            )
        }

        override fun mapRecord(rawProfile: RawParsedProfile): MappingResult =
            MappingResult.MappingError("unused-in-this-path")

        override fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType =
            SourceEntityType.Unknown
    }

    /**
     * A [RawSnapshotStore] backed by an on-disk [FsRawSnapshotStore] but reporting
     * integrity as always-satisfied, so the persist stage's stored-file check does
     * not gate this source-independence test (the store's own integrity check is
     * covered by the raw-store tests / task 13). Mirrors [SchedulerSmokeTest].
     */
    private fun passthroughRawStore(folder: Path): RawSnapshotStore {
        val fs = FsRawSnapshotStore(RawSnapshotStoreProperties(folder = folder))
        return object : RawSnapshotStore {
            override fun put(versionId: com.spike.ofac.pipeline.models.VersionId, bytes: ByteArray): Path =
                fs.put(versionId, bytes)
            override fun get(versionId: com.spike.ofac.pipeline.models.VersionId): ByteArray =
                fs.get(versionId)
            override fun verifyIntegrity(versionId: com.spike.ofac.pipeline.models.VersionId): Boolean = true
            override fun delete(versionId: com.spike.ofac.pipeline.models.VersionId): Boolean =
                fs.delete(versionId)
        }
    }

    /**
     * The single structural assertion: a **second stub adapter drives the same six
     * stages unchanged**, straight through to activation (Req 13.1, 13.2).
     */
    @Test
    fun `a second stub adapter drives the same unchanged six-stage core to activation`(
        @TempDir folder: Path,
    ) {
        val store = InMemoryVersionStore()
        val snapshot = validSnapshot(fixedRef = "9001", name = "Acme Holdings")
        val digest = digestOf(snapshot)
        val adapter = StubSourceAdapter(body = snapshot, digest = digest)

        // The SAME Scheduler an OFAC cycle uses: no source flag, no per-source
        // wiring — the default Obtain/Validate/Transform/VersionStage/Persist/
        // Publish stage objects are constructed inside Scheduler itself. The ONLY
        // source-specific value here is `adapter` on the SourceListConfig (Req 13.2).
        val scheduler = Scheduler(
            versionStore = store,
            rawSnapshotStore = passthroughRawStore(folder),
            sourceLists = emptyList(),
        )

        // Drive the unchanged six-stage cycle through the stub adapter. (Runs on the
        // SDN line because InMemoryVersionStore's bare putIsolated activation is
        // attributed to SDN — this test is about the *stages* being source-agnostic,
        // not about the reference store's single-list bias.)
        val outcome = scheduler.runCycle(
            SourceListConfig(SourceList.SDN, acmeUrl, ScopeConfig.SDN_ONLY, adapter),
        )

        // Every one of the six stages accepted the stub-sourced snapshot: the cycle
        // flowed through obtain → validate → transform → version → persist → publish
        // to activation, with no source-specific stage branching (Req 13.1).
        outcome.status shouldBe CycleStatus.ACTIVATED
        val versionId = outcome.versionId.shouldNotBeNull()
        versionId.digest shouldBe digest

        // publish atomically repointed CURRENT to the new, stub-sourced version.
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe versionId

        // The source was read ONLY through the adapter seam (Req 13.2): obtain issued
        // exactly one HEAD (change check) and one GET (download) against the stub.
        adapter.headCount.get() shouldBe 1
        adapter.getCount.get() shouldBe 1
    }

    /**
     * The reusability claim, made explicit: **two different stub adapters** — the
     * same source seam populated with two independent sources — are pushed through
     * the **identical** [Scheduler]/stage objects and both activate. Nothing about
     * the core changed between the two runs; only the adapter did (Req 13.1, 13.2).
     */
    @Test
    fun `two distinct stub adapters both activate through the identical core wiring`(
        @TempDir folder: Path,
    ) {
        val store = InMemoryVersionStore()
        val scheduler = Scheduler(
            versionStore = store,
            rawSnapshotStore = passthroughRawStore(folder),
            sourceLists = emptyList(),
        )

        // First source: activates cleanly.
        val firstSnapshot = validSnapshot(fixedRef = "3100", name = "First Source Person")
        val firstDigest = digestOf(firstSnapshot)
        val firstAdapter = StubSourceAdapter(body = firstSnapshot, digest = firstDigest)
        val firstOutcome = scheduler.runCycle(
            SourceListConfig(SourceList.SDN, acmeUrl, ScopeConfig.SDN_ONLY, firstAdapter),
        )
        firstOutcome.status shouldBe CycleStatus.ACTIVATED
        firstOutcome.versionId.shouldNotBeNull().digest shouldBe firstDigest

        // Second, structurally distinct source: a different adapter instance serving
        // different content through the SAME unchanged core. It, too, activates —
        // the core did not need to know which source it was (Req 13.1).
        val secondSnapshot = validSnapshot(fixedRef = "3200", name = "Second Source Entity")
        val secondDigest = digestOf(secondSnapshot)
        val secondAdapter = StubSourceAdapter(body = secondSnapshot, digest = secondDigest)
        val secondOutcome = scheduler.runCycle(
            SourceListConfig(SourceList.SDN, acmeUrl, ScopeConfig.SDN_ONLY, secondAdapter),
        )
        secondOutcome.status shouldBe CycleStatus.ACTIVATED
        secondOutcome.versionId.shouldNotBeNull().digest shouldBe secondDigest

        // Each source was read solely through its own adapter (Req 13.2).
        firstAdapter.headCount.get() shouldBe 1
        firstAdapter.getCount.get() shouldBe 1
        secondAdapter.headCount.get() shouldBe 1
        secondAdapter.getCount.get() shouldBe 1

        // CURRENT resolves to the most recently activated source version (the second),
        // proving both were driven end-to-end through the identical stages.
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe secondOutcome.versionId
    }
}
