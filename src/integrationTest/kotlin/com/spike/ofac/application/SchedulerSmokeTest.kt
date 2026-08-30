package com.spike.ofac.application

import com.spike.ofac.adapter.`in`.scheduling.SchedulerTrigger
import com.spike.ofac.adapter.out.persistence.InMemoryVersionStore

import com.spike.ofac.adapter.config.RawSnapshotStoreProperties
import com.spike.ofac.adapter.config.SchedulerProperties
import com.spike.ofac.application.port.out.HeadResponse
import com.spike.ofac.application.port.out.HttpResponse
import com.spike.ofac.application.port.out.MappingResult
import com.spike.ofac.application.port.out.SourceAdapter
import com.spike.ofac.application.port.out.SourceEntityType
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.transform.RawParsedProfile
import com.spike.ofac.adapter.out.persistence.FsRawSnapshotStore
import com.spike.ofac.application.port.out.PointerKind
import com.spike.ofac.application.port.out.RawSnapshotStore
import com.spike.ofac.application.port.out.VersionStore
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration

/**
 * Scheduler smoke test (task 18.4).
 *
 * Where [SchedulerCycleSmokeTest] and [FailureObservabilityIntegrationTest] cover
 * the *inside* of a single cycle ([Scheduler.runCycle]'s outcomes), this test
 * covers the three *scheduling-seam* guarantees that only the scheduler owns
 * (Req 1.1):
 *
 *  1. **Fires per `Source_List`** — one full [Scheduler.tick] (the method the
 *     Spring `@Scheduled` trigger calls on every fire) runs exactly one cycle per
 *     configured [SourceListConfig] and records an observable [CycleOutcome] for
 *     each, independently — a failing list never suppresses another list's tick.
 *  2. **On the configured interval** — the `@Scheduled` trigger
 *     ([SchedulerTrigger.poll]) delegates one fire to [Scheduler.tick]; the
 *     interval itself is wired via SpEL from [SchedulerProperties.interval].
 *  3. **Interval-bounds validation + sub-daily default** — [SchedulerProperties]
 *     accepts an interval within [SchedulerProperties.MIN_INTERVAL]..
 *     [SchedulerProperties.MAX_INTERVAL], rejects anything outside that band, and
 *     its default is sub-daily (strictly more than one tick per day).
 *
 * Real stage objects are wired through the [Scheduler]; only the HTTP seam
 * ([SourceAdapter]) is faked, backed by an [InMemoryVersionStore] and a temp-dir
 * [FsRawSnapshotStore]. The trigger test isolates the [Scheduler] collaborator
 * with MockK so it asserts *only* the fire→tick delegation.
 */
class SchedulerSmokeTest {

    private val sdnUrl = URI.create("https://example.test/sdn_advanced.xml")
    private val consUrl = URI.create("https://example.test/cons_advanced.xml")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

    private fun digestOf(bytes: ByteArray) = Sha256Digest.ofHex(sha256Hex(bytes))

    /**
     * A well-formed Advanced XML snapshot with one in-scope Individual carrying a
     * primary name and a Program sanctions measure, so it transforms into exactly
     * one entry (drives a full ACTIVATED cycle). [fixedRef] varies so the two
     * lists' snapshots differ.
     */
    private fun validSnapshot(fixedRef: String, name: String): ByteArray = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<Sanctions>\n")
        append("  <DateOfIssue><Year>2024</Year><Month>5</Month><Day>20</Day></DateOfIssue>\n")
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
     * A fake adapter returning canned HEAD/GET. [headDigest] drives change
     * detection; [getBody] + [getDigest] are the downloaded snapshot and its
     * advertised digest. [headThrows] simulates a failed HEAD.
     */
    private class FakeAdapter(
        private val headDigest: Sha256Digest?,
        private val getBody: ByteArray,
        private val getDigest: Sha256Digest?,
        private val headThrows: Boolean = false,
    ) : SourceAdapter {
        override fun head(url: URI): HeadResponse {
            if (headThrows) throw java.io.IOException("connect timeout")
            return HeadResponse(statusCode = 200, lastModified = null, digest = headDigest)
        }

        override fun get(url: URI): HttpResponse =
            HttpResponse(
                statusCode = 200,
                body = getBody,
                digest = getDigest,
                contentLength = getBody.size.toLong(),
            )

        override fun mapRecord(rawProfile: RawParsedProfile): MappingResult =
            MappingResult.MappingError("unused")

        override fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType =
            SourceEntityType.Unknown
    }

    private fun fsStore(folder: Path) =
        FsRawSnapshotStore(RawSnapshotStoreProperties(folder = folder))

    /**
     * A [RawSnapshotStore] backed by an on-disk [FsRawSnapshotStore] but reporting
     * integrity as always-satisfied, so the persist stage's stored-file check
     * passes regardless of which list is being written. (The store's own integrity
     * check is covered exhaustively by the raw-store tests / task 13.)
     */
    private fun passthroughRawStore(folder: Path): RawSnapshotStore {
        val fs = fsStore(folder)
        return object : RawSnapshotStore {
            override fun put(versionId: VersionId, bytes: ByteArray): Path = fs.put(versionId, bytes)
            override fun get(versionId: VersionId): ByteArray = fs.get(versionId)
            override fun verifyIntegrity(versionId: VersionId): Boolean = true
            override fun delete(versionId: VersionId): Boolean = fs.delete(versionId)
        }
    }

    /**
     * A MockK [VersionStore] that records the CURRENT pointer **per `Source_List`**
     * so a genuinely-CONSOLIDATED cycle can activate independently of SDN.
     *
     * The reference [com.spike.ofac.adapter.out.persistence.InMemoryVersionStore]
     * deliberately attributes a bare `put_isolated` to SDN (it is the single-list
     * model for the pointer-state-machine properties), so it cannot host a real
     * CONSOLIDATED activation. Here the persist/publish contract calls are stubbed
     * to a simple per-list CURRENT map, keeping this test focused on the
     * *scheduler's* fan-out (Req 1.1) rather than the reference store's single-list
     * bias.
     */
    private fun perListMockStore(): Pair<VersionStore, MutableMap<SourceList, VersionId>> {
        val current = mutableMapOf<SourceList, VersionId>()
        val store = mockk<VersionStore>(relaxed = true)
        // No prior version for any list -> obtain always sees a change.
        every { store.lastIngested(any()) } returns null
        every { store.putIsolated(any(), any<List<InternalModelEntry>>()) } returns Unit
        every { store.associateRawPath(any(), any()) } returns Unit
        // publish.activate repoints CURRENT for the specific list it is given.
        val listSlot = slot<SourceList>()
        val idSlot = slot<VersionId>()
        every { store.atomicSetCurrent(capture(listSlot), capture(idSlot)) } answers {
            current[listSlot.captured] = idSlot.captured
            true
        }
        every { store.getPointer(any(), PointerKind.CURRENT) } answers {
            current[firstArg()]
        }
        every { store.getPointer(any(), PointerKind.PREVIOUS) } returns null
        return store to current
    }

    // --- (1) fires per Source_List on the configured interval -----------------

    @Test
    fun `one tick fires exactly one cycle per configured Source_List, recording each outcome`(
        @TempDir folder: Path,
    ) {
        val (store, current) = perListMockStore()

        // Two independently-configured lists, each with its own distinct snapshot
        // and advertised digest — so a successful tick activates each one on its
        // own version line (Req 10.2).
        val sdnSnapshot = validSnapshot(fixedRef = "1001", name = "Jane Doe")
        val consSnapshot = validSnapshot(fixedRef = "2002", name = "Acme Corp")
        val sdnDigest = digestOf(sdnSnapshot)
        val consDigest = digestOf(consSnapshot)

        val scheduler = Scheduler(
            versionStore = store,
            rawSnapshotStore = passthroughRawStore(folder),
            sourceLists = listOf(
                SourceListConfig(
                    SourceList.SDN, sdnUrl, ScopeConfig.SDN_ONLY,
                    FakeAdapter(headDigest = sdnDigest, getBody = sdnSnapshot, getDigest = sdnDigest),
                ),
                SourceListConfig(
                    SourceList.CONSOLIDATED, consUrl, ScopeConfig.SDN_AND_CONSOLIDATED,
                    FakeAdapter(headDigest = consDigest, getBody = consSnapshot, getDigest = consDigest),
                ),
            ),
        )

        // Before the first fire, nothing has been observed for either list.
        scheduler.lastOutcome(SourceList.SDN) shouldBe null
        scheduler.lastOutcome(SourceList.CONSOLIDATED) shouldBe null

        scheduler.tick()

        // One tick produced exactly one recorded outcome per configured list.
        val sdnOutcome = scheduler.lastOutcome(SourceList.SDN).shouldNotBeNull()
        val consOutcome = scheduler.lastOutcome(SourceList.CONSOLIDATED).shouldNotBeNull()

        // Each list ran its own full cycle to activation on its own version line.
        sdnOutcome.status shouldBe CycleStatus.ACTIVATED
        consOutcome.status shouldBe CycleStatus.ACTIVATED
        sdnOutcome.versionId.shouldNotBeNull().digest shouldBe sdnDigest
        consOutcome.versionId.shouldNotBeNull().digest shouldBe consDigest

        // Exactly one CURRENT was set per configured list (fan-out, Req 1.1),
        // resolving independently (per-list independence, Req 10.2).
        current.keys shouldContainExactlyInAnyOrder listOf(SourceList.SDN, SourceList.CONSOLIDATED)
        current[SourceList.SDN] shouldBe sdnOutcome.versionId
        current[SourceList.CONSOLIDATED] shouldBe consOutcome.versionId

        // The publish repoint fired once per list, each with its own list token.
        verify(exactly = 1) { store.atomicSetCurrent(SourceList.SDN, any()) }
        verify(exactly = 1) { store.atomicSetCurrent(SourceList.CONSOLIDATED, any()) }
    }

    @Test
    fun `a failing list does not suppress the tick for the other configured list`(
        @TempDir folder: Path,
    ) {
        val (store, current) = perListMockStore()

        // SDN's HEAD throws (obtain fails); CONSOLIDATED is a good, changed snapshot.
        val consSnapshot = validSnapshot(fixedRef = "2002", name = "Acme Corp")
        val consDigest = digestOf(consSnapshot)

        val scheduler = Scheduler(
            versionStore = store,
            rawSnapshotStore = passthroughRawStore(folder),
            sourceLists = listOf(
                SourceListConfig(
                    SourceList.SDN, sdnUrl, ScopeConfig.SDN_ONLY,
                    FakeAdapter(headDigest = null, getBody = ByteArray(0), getDigest = null, headThrows = true),
                ),
                SourceListConfig(
                    SourceList.CONSOLIDATED, consUrl, ScopeConfig.SDN_AND_CONSOLIDATED,
                    FakeAdapter(headDigest = consDigest, getBody = consSnapshot, getDigest = consDigest),
                ),
            ),
        )

        scheduler.tick()

        // The SDN cycle failed at obtain and never repointed its CURRENT...
        val sdnOutcome = scheduler.lastOutcome(SourceList.SDN).shouldNotBeNull()
        sdnOutcome.status shouldBe CycleStatus.FAILED
        sdnOutcome.failedStage shouldBe StageName.OBTAIN
        current[SourceList.SDN] shouldBe null

        // ...but the CONSOLIDATED cycle still fired and activated in the same tick.
        val consOutcome = scheduler.lastOutcome(SourceList.CONSOLIDATED).shouldNotBeNull()
        consOutcome.status shouldBe CycleStatus.ACTIVATED
        current[SourceList.CONSOLIDATED] shouldBe consOutcome.versionId
    }

    @Test
    fun `the Scheduled trigger delegates one fire to a single scheduler tick`() {
        // The interval itself is Spring's concern (SpEL over ofac.scheduler.interval);
        // this asserts the only behaviour the trigger owns — each fire runs one
        // full tick over the configured Source_Lists (Req 1.1).
        val scheduler = mockk<Scheduler>(relaxed = true)
        val trigger = SchedulerTrigger(scheduler)

        trigger.poll()

        verify(exactly = 1) { scheduler.tick() }
    }

    // --- (2) interval-bounds validation ---------------------------------------

    @Test
    fun `an interval within the bounded range is accepted`() {
        shouldNotThrowAny {
            SchedulerProperties(interval = SchedulerProperties.MIN_INTERVAL)
        }
        shouldNotThrowAny {
            SchedulerProperties(interval = SchedulerProperties.MAX_INTERVAL)
        }
        shouldNotThrowAny {
            SchedulerProperties(interval = Duration.ofHours(6))
        }
    }

    @Test
    fun `an interval below the lower bound is rejected`() {
        shouldThrow<IllegalArgumentException> {
            SchedulerProperties(interval = SchedulerProperties.MIN_INTERVAL.minusSeconds(1))
        }
    }

    @Test
    fun `an interval above the upper bound (super-daily) is rejected`() {
        shouldThrow<IllegalArgumentException> {
            SchedulerProperties(interval = SchedulerProperties.MAX_INTERVAL.plusSeconds(1))
        }
    }

    // --- (3) sub-daily default (multiple checks per day) ----------------------

    @Test
    fun `the default interval is sub-daily, yielding more than one check per day`() {
        val default = SchedulerProperties().interval

        // Sub-daily: strictly shorter than a day, so a full day fits more than one tick.
        default shouldBeGreaterThan Duration.ZERO
        (default < Duration.ofDays(1)) shouldBe true

        val ticksPerDay = Duration.ofDays(1).toMillis() / default.toMillis()
        ticksPerDay shouldBeGreaterThan 1L

        // And the default is itself within the validated bounds (constructs cleanly).
        shouldNotThrowAny { SchedulerProperties() }
    }
}
