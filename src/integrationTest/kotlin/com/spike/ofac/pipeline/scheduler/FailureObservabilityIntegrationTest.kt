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
import com.spike.ofac.pipeline.models.VersionId
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

/**
 * Failure-observability integration tests for [Scheduler.runCycle] (task 18.6).
 *
 * Two guarantees are exercised end-to-end against the **real** stage objects
 * wired through the [Scheduler] — only the HTTP seam ([SourceAdapter]) and, for
 * the persist-failure case, the [RawSnapshotStore] are faked:
 *
 *  - **Req 11.2** — every stage failure yields a [CycleStatus.FAILED] outcome
 *    naming the [StageName] that failed. One test per pre-activation stage that
 *    can be forced from the source side alone: OBTAIN (HEAD throws, and download
 *    fails), VALIDATE (advertised digest doesn't match the bytes), TRANSFORM (a
 *    valid-digest, well-formed snapshot whose in-scope profile is unbuildable),
 *    and PERSIST (the raw-snapshot write throws). Each asserts CURRENT is left
 *    untouched (Req 11.1).
 *  - **Req 11.3** — a fresh cycle after a failed one recovers by reading only the
 *    source: run a TRANSFORM-failing cycle, then a cycle with a good snapshot for
 *    the same list, and assert it ACTIVATES with no dependence on any artifact
 *    from the failed cycle (the failed cycle left CURRENT null / untouched, and
 *    the fresh cycle re-reads the adapter from `obtain`).
 *
 * Reuses the FakeAdapter + valid-snapshot pattern from `SchedulerCycleSmokeTest`,
 * an [InMemoryVersionStore], and a temp-dir-backed [FsRawSnapshotStore].
 */
class FailureObservabilityIntegrationTest {

    private val url = URI.create("https://example.test/sdn_advanced.xml")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

    // --- snapshot fixtures --------------------------------------------------

    /**
     * A well-formed Advanced XML snapshot with one in-scope Individual
     * (`PartySubTypeID="4"`) carrying a primary name AND a Program sanctions
     * measure, so it transforms into exactly one entry (the happy path).
     */
    private val validSnapshot: ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<Sanctions>\n")
        append("  <DateOfIssue><Year>2024</Year><Month>5</Month><Day>20</Day></DateOfIssue>\n")
        append("  <DistinctParties>\n")
        append("    <DistinctParty FixedRef=\"1001\">\n")
        append("      <Profile ID=\"p1\" PartySubTypeID=\"4\">\n")
        append("        <Identity ID=\"i1\">\n")
        append("          <Alias AliasTypeID=\"1403\" Primary=\"true\">\n")
        append("            <DocumentedName>\n")
        append("              <DocumentedNamePart><NamePartValue>Jane Doe</NamePartValue></DocumentedNamePart>\n")
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
     * A well-formed Advanced XML snapshot with one in-scope Individual that has a
     * primary name but **no `SanctionsEntry`** — so the profile has no resolvable
     * sanction program and is unbuildable (Req 4.4), which makes the transform
     * stage hard-fail (Req 4.8) even though it is valid-digest + well-formed XML
     * (so obtain + validate both pass and the failure lands squarely on TRANSFORM).
     */
    private val transformFailSnapshot: ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<Sanctions>\n")
        append("  <DateOfIssue><Year>2024</Year><Month>6</Month><Day>1</Day></DateOfIssue>\n")
        append("  <DistinctParties>\n")
        append("    <DistinctParty FixedRef=\"2002\">\n")
        append("      <Profile ID=\"p9\" PartySubTypeID=\"4\">\n")
        append("        <Identity ID=\"i9\">\n")
        append("          <Alias AliasTypeID=\"1403\" Primary=\"true\">\n")
        append("            <DocumentedName>\n")
        append("              <DocumentedNamePart><NamePartValue>No Program Person</NamePartValue></DocumentedNamePart>\n")
        append("            </DocumentedName>\n")
        append("          </Alias>\n")
        append("        </Identity>\n")
        append("      </Profile>\n")
        append("    </DistinctParty>\n")
        append("  </DistinctParties>\n")
        append("</Sanctions>\n")
    }.toByteArray(Charsets.UTF_8)

    // --- test doubles -------------------------------------------------------

    /**
     * A fake adapter returning canned HEAD/GET. [headDigest] drives change
     * detection; [getBody] + [getDigest] are the downloaded snapshot and its
     * advertised digest (drive validate). [headThrows] simulates a HEAD I/O
     * failure; [getThrows] simulates a download failure.
     */
    private class FakeAdapter(
        private val headDigest: Sha256Digest?,
        private val getBody: ByteArray,
        private val getDigest: Sha256Digest?,
        private val headThrows: Boolean = false,
        private val getThrows: Boolean = false,
    ) : SourceAdapter {
        override fun head(url: URI): HeadResponse {
            if (headThrows) throw java.io.IOException("connect timeout")
            return HeadResponse(statusCode = 200, lastModified = null, digest = headDigest)
        }

        override fun get(url: URI): HttpResponse {
            if (getThrows) throw java.io.IOException("connection reset mid-download")
            return HttpResponse(
                statusCode = 200,
                body = getBody,
                digest = getDigest,
                contentLength = getBody.size.toLong(),
            )
        }

        override fun mapRecord(rawProfile: RawParsedProfile): MappingResult =
            MappingResult.MappingError("unused")

        override fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType =
            SourceEntityType.Unknown
    }

    /**
     * A [RawSnapshotStore] whose [put] always throws, to force the persist stage
     * onto its fail-closed RAW_WRITE path (`FAILED(PERSIST)`, Req 15.9). All other
     * operations are unused in these cycles.
     */
    private class FailingRawSnapshotStore : RawSnapshotStore {
        override fun put(versionId: VersionId, bytes: ByteArray): Path =
            throw java.io.IOException("disk full")

        override fun get(versionId: VersionId): ByteArray = throw UnsupportedOperationException()
        override fun verifyIntegrity(versionId: VersionId): Boolean = false
        override fun delete(versionId: VersionId): Boolean = false
    }

    private fun fsStore(folder: Path) =
        FsRawSnapshotStore(RawSnapshotStoreProperties(folder = folder))

    private fun digestOf(bytes: ByteArray) = Sha256Digest.ofHex(sha256Hex(bytes))

    /** A scheduler whose per-tick `sourceLists` are empty; cycles are driven directly via [Scheduler.runCycle]. */
    private fun scheduler(folder: Path, store: InMemoryVersionStore, rawStore: RawSnapshotStore = fsStore(folder)) =
        Scheduler(versionStore = store, rawSnapshotStore = rawStore, sourceLists = emptyList())

    private fun sdnConfig(adapter: SourceAdapter) =
        SourceListConfig(SourceList.SDN, url, ScopeConfig.SDN_ONLY, adapter)

    // --- Req 11.2: each stage failure yields FAILED naming that stage -------

    @Test
    fun `OBTAIN HEAD failure yields FAILED naming OBTAIN, CURRENT untouched`(@TempDir folder: Path) {
        val store = InMemoryVersionStore()
        val outcome = scheduler(folder, store).runCycle(
            sdnConfig(FakeAdapter(headDigest = null, getBody = validSnapshot, getDigest = null, headThrows = true)),
        )

        outcome.status shouldBe CycleStatus.FAILED
        outcome.failedStage shouldBe StageName.OBTAIN
        outcome.cause.shouldNotBeNull()
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    @Test
    fun `OBTAIN download failure yields FAILED naming OBTAIN, CURRENT untouched`(@TempDir folder: Path) {
        // HEAD advertises a changed digest so the cycle proceeds to download, then
        // the GET throws mid-download -> obtain.download fails (Req 2.5).
        val advertised = Sha256Digest.ofHex("b".repeat(64))
        val store = InMemoryVersionStore()
        val outcome = scheduler(folder, store).runCycle(
            sdnConfig(FakeAdapter(headDigest = advertised, getBody = validSnapshot, getDigest = advertised, getThrows = true)),
        )

        outcome.status shouldBe CycleStatus.FAILED
        outcome.failedStage shouldBe StageName.OBTAIN
        outcome.cause.shouldNotBeNull()
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    @Test
    fun `VALIDATE digest mismatch yields FAILED naming VALIDATE, CURRENT untouched`(@TempDir folder: Path) {
        // HEAD says changed (a digest unlike any prior), the body downloads fine,
        // but the advertised GET digest does not match the actual bytes -> the
        // validate stage rejects with DIGEST_MISMATCH before any parsing (Req 3.3).
        val advertised = Sha256Digest.ofHex("a".repeat(64))
        val store = InMemoryVersionStore()
        val outcome = scheduler(folder, store).runCycle(
            sdnConfig(FakeAdapter(headDigest = advertised, getBody = validSnapshot, getDigest = advertised)),
        )

        outcome.status shouldBe CycleStatus.FAILED
        outcome.failedStage shouldBe StageName.VALIDATE
        outcome.cause shouldBe "DIGEST_MISMATCH"
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    @Test
    fun `TRANSFORM hard-failure yields FAILED naming TRANSFORM, CURRENT untouched`(@TempDir folder: Path) {
        // Advertise the REAL sha256 of the bytes so obtain + validate both pass;
        // the snapshot is well-formed XML but its only in-scope profile has no
        // sanction program, so transform hard-fails (Req 4.4, 4.8).
        val digest = digestOf(transformFailSnapshot)
        val store = InMemoryVersionStore()
        val outcome = scheduler(folder, store).runCycle(
            sdnConfig(FakeAdapter(headDigest = digest, getBody = transformFailSnapshot, getDigest = digest)),
        )

        outcome.status shouldBe CycleStatus.FAILED
        outcome.failedStage shouldBe StageName.TRANSFORM
        outcome.cause.shouldNotBeNull()
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    @Test
    fun `PERSIST raw-write failure yields FAILED naming PERSIST, CURRENT untouched`(@TempDir folder: Path) {
        // A fully valid snapshot (obtain/validate/transform/version all pass), but
        // the raw-snapshot store's write throws -> persist fails closed on its
        // RAW_WRITE path and CURRENT is left unchanged (Req 15.9, 11.1).
        val digest = digestOf(validSnapshot)
        val store = InMemoryVersionStore()
        val outcome = scheduler(folder, store, rawStore = FailingRawSnapshotStore()).runCycle(
            sdnConfig(FakeAdapter(headDigest = digest, getBody = validSnapshot, getDigest = digest)),
        )

        outcome.status shouldBe CycleStatus.FAILED
        outcome.failedStage shouldBe StageName.PERSIST
        outcome.cause shouldBe "RAW_WRITE"
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    // --- Req 11.3: a fresh cycle after a failed one recovers, reading only src

    @Test
    fun `a fresh cycle after a failed cycle ACTIVATES, reading only the source`(@TempDir folder: Path) {
        val store = InMemoryVersionStore()
        val sched = scheduler(folder, store)

        // 1) A failing cycle: well-formed, valid-digest snapshot that hard-fails
        //    transform (no sanction program). Nothing is persisted or activated.
        val failDigest = digestOf(transformFailSnapshot)
        val failed = sched.runCycle(
            sdnConfig(FakeAdapter(headDigest = failDigest, getBody = transformFailSnapshot, getDigest = failDigest)),
        )
        failed.status shouldBe CycleStatus.FAILED
        failed.failedStage shouldBe StageName.TRANSFORM
        // The failed cycle left CURRENT untouched (no intermediate artifact persisted).
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null

        // 2) A fresh cycle for the SAME source list with a good snapshot. The
        //    scheduler restarts from obtain, reading ONLY the adapter — there is no
        //    checkpoint/partial artifact from the failed cycle to depend on (Req 11.3).
        val goodDigest = digestOf(validSnapshot)
        val recovered = sched.runCycle(
            sdnConfig(FakeAdapter(headDigest = goodDigest, getBody = validSnapshot, getDigest = goodDigest)),
        )

        recovered.status shouldBe CycleStatus.ACTIVATED
        val versionId = recovered.versionId.shouldNotBeNull()
        versionId.digest shouldBe goodDigest
        // CURRENT now resolves to the freshly activated good version, proving the
        // fresh cycle recovered fully from the source alone (Req 11.3).
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe versionId
    }
}
