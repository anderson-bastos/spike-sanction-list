package com.spike.ofac.application

import com.spike.ofac.adapter.config.RawSnapshotStoreProperties
import com.spike.ofac.application.port.out.HeadResponse
import com.spike.ofac.application.port.out.HttpResponse
import com.spike.ofac.application.port.out.MappingResult
import com.spike.ofac.application.port.out.SourceAdapter
import com.spike.ofac.application.port.out.SourceEntityType
import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.transform.RawParsedProfile
import com.spike.ofac.adapter.out.persistence.FsRawSnapshotStore
import com.spike.ofac.adapter.out.persistence.InMemoryVersionStore
import com.spike.ofac.application.port.out.PointerKind
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Compile-guard + happy/failure-path smoke check for [Scheduler.runCycle] (task 15.1).
 *
 * It drives a full cycle end-to-end against the **real** stage objects wired
 * through the [Scheduler], using only a fake [SourceAdapter] for the HTTP seam,
 * the [InMemoryVersionStore], and a temp-dir-backed [FsRawSnapshotStore]. It
 * exercises the three terminal outcomes — ACTIVATED, SKIPPED_NO_CHANGE, and a
 * FAILED cycle naming its stage — without duplicating the exhaustive
 * scheduler/integration coverage owned by task 18.4.
 */
class SchedulerCycleSmokeTest {

    private val url = URI.create("https://example.test/sdn_advanced.xml")

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }

    /**
     * A minimal, well-formed Advanced XML snapshot with a `DateOfIssue` and one
     * in-scope Individual (`PartySubTypeID="4"`) carrying a primary name and a
     * Program sanctions measure, so it transforms into exactly one entry.
     */
    private val validSnapshot: ByteArray = """
        <?xml version="1.0" encoding="utf-8"?>
        <Sanctions>
          <DateOfIssue><Year>2024</Year><Month>5</Month><Day>20</Day></DateOfIssue>
          <DistinctParties>
            <DistinctParty FixedRef="1001">
              <Profile ID="p1" PartySubTypeID="4">
                <Identity ID="i1">
                  <Alias AliasTypeID="1403" Primary="true">
                    <DocumentedName>
                      <DocumentedNamePart><NamePartValue>Jane Doe</NamePartValue></DocumentedNamePart>
                    </DocumentedName>
                  </Alias>
                </Identity>
              </Profile>
            </DistinctParty>
          </DistinctParties>
          <SanctionsEntries>
            <SanctionsEntry ID="e1" ProfileID="p1" ListID="1">
              <SanctionsMeasure SanctionsTypeID="1"><Comment>SDGT</Comment></SanctionsMeasure>
            </SanctionsEntry>
          </SanctionsEntries>
        </Sanctions>
    """.trimIndent().toByteArray(Charsets.UTF_8)

    /**
     * A fake adapter returning canned HEAD/GET. [headDigest] is the digest the
     * HEAD advertises (drives change detection); [getBody] + [getDigest] are the
     * downloaded snapshot and its advertised digest (drive validate). A `null`
     * [headStatus]/behavior can be used to simulate a failed HEAD.
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

        // Not used by the scheduler (transform uses the ProfileEntryBuilder, not
        // the adapter's mapRecord, in this pipeline wiring).
        override fun mapRecord(rawProfile: RawParsedProfile): MappingResult =
            MappingResult.MappingError("unused")

        override fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType =
            SourceEntityType.Unknown
    }

    private fun fsStore(folder: Path) =
        FsRawSnapshotStore(RawSnapshotStoreProperties(folder = folder))

    private fun schedulerWith(adapter: SourceAdapter, folder: Path, store: InMemoryVersionStore) =
        Scheduler(
            versionStore = store,
            rawSnapshotStore = fsStore(folder),
            sourceLists = listOf(
                SourceListConfig(SourceList.SDN, url, ScopeConfig.SDN_ONLY, adapter),
            ),
        )

    // --- ACTIVATED ------------------------------------------------------------

    @Test
    fun `a changed, valid snapshot drives a full ACTIVATED cycle`(@TempDir folder: Path) {
        val digest = Sha256Digest.ofHex(sha256Hex(validSnapshot))
        val store = InMemoryVersionStore()
        val scheduler = schedulerWith(
            FakeAdapter(headDigest = digest, getBody = validSnapshot, getDigest = digest),
            folder, store,
        )

        val outcome = scheduler.runCycle(
            SourceListConfig(SourceList.SDN, url, ScopeConfig.SDN_ONLY,
                FakeAdapter(headDigest = digest, getBody = validSnapshot, getDigest = digest)),
        )

        outcome.status shouldBe CycleStatus.ACTIVATED
        val versionId = outcome.versionId.shouldNotBeNull()
        versionId.digest shouldBe digest
        // The version's Publish_Date came from the snapshot's DateOfIssue.
        versionId.publishDate shouldBe java.time.LocalDate.of(2024, 5, 20)
        // CURRENT now resolves to the freshly activated version (Req 9).
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe versionId
    }

    // --- SKIPPED_NO_CHANGE ----------------------------------------------------

    @Test
    fun `an unchanged snapshot is skipped without downloading`(@TempDir folder: Path) {
        val digest = Sha256Digest.ofHex(sha256Hex(validSnapshot))
        val store = InMemoryVersionStore()

        // First: activate a version so last_ingested has this digest.
        schedulerWith(
            FakeAdapter(headDigest = digest, getBody = validSnapshot, getDigest = digest),
            folder, store,
        ).runCycle(
            SourceListConfig(SourceList.SDN, url, ScopeConfig.SDN_ONLY,
                FakeAdapter(headDigest = digest, getBody = validSnapshot, getDigest = digest)),
        ).status shouldBe CycleStatus.ACTIVATED

        val currentBefore = store.getPointer(SourceList.SDN, PointerKind.CURRENT)

        // Second cycle: HEAD advertises the same digest -> NO_CHANGE, no download.
        val outcome = Scheduler(
            versionStore = store,
            rawSnapshotStore = fsStore(folder),
            sourceLists = emptyList(),
        ).runCycle(
            SourceListConfig(SourceList.SDN, url, ScopeConfig.SDN_ONLY,
                FakeAdapter(headDigest = digest, getBody = validSnapshot, getDigest = digest)),
        )

        outcome.status shouldBe CycleStatus.SKIPPED_NO_CHANGE
        outcome.versionId shouldBe null
        // CURRENT is untouched.
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe currentBefore
    }

    // --- FAILED ---------------------------------------------------------------

    @Test
    fun `a HEAD failure yields FAILED naming the obtain stage`(@TempDir folder: Path) {
        val store = InMemoryVersionStore()
        val outcome = Scheduler(
            versionStore = store,
            rawSnapshotStore = fsStore(folder),
            sourceLists = emptyList(),
        ).runCycle(
            SourceListConfig(SourceList.SDN, url, ScopeConfig.SDN_ONLY,
                FakeAdapter(headDigest = null, getBody = validSnapshot, getDigest = null, headThrows = true)),
        )

        outcome.status shouldBe CycleStatus.FAILED
        outcome.failedStage shouldBe StageName.OBTAIN
        outcome.cause.shouldNotBeNull()
        // CURRENT was never set.
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    @Test
    fun `a digest mismatch yields FAILED naming the validate stage`(@TempDir folder: Path) {
        // HEAD says changed (a different digest than any prior), but the downloaded
        // body's advertised digest does not match its bytes -> validate rejects.
        val advertised = Sha256Digest("a".repeat(64))
        val store = InMemoryVersionStore()
        val outcome = Scheduler(
            versionStore = store,
            rawSnapshotStore = fsStore(folder),
            sourceLists = emptyList(),
        ).runCycle(
            SourceListConfig(SourceList.SDN, url, ScopeConfig.SDN_ONLY,
                FakeAdapter(headDigest = advertised, getBody = validSnapshot, getDigest = advertised)),
        )

        outcome.status shouldBe CycleStatus.FAILED
        outcome.failedStage shouldBe StageName.VALIDATE
        outcome.cause shouldBe "DIGEST_MISMATCH"
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe null
    }

    // --- regression: digest advertised on HEAD only, absent on GET ------------

    @Test
    fun `digest advertised on HEAD but absent on GET still validates and activates`(@TempDir folder: Path) {
        // Regression for the live-OFAC obtain flow: OFAC advertises the Digest on
        // the HEAD, but its GET 302-redirects to S3 which does NOT repeat the
        // header, so the download carries no advertised digest. The scheduler must
        // carry the HEAD digest forward into validate (Req 3.2/3.3); otherwise the
        // cycle wrongly fails with ABSENT_DIGEST. Here headDigest is set but
        // getDigest is null, mirroring production.
        val digest = Sha256Digest.ofHex(sha256Hex(validSnapshot))
        val store = InMemoryVersionStore()

        val outcome = Scheduler(
            versionStore = store,
            rawSnapshotStore = fsStore(folder),
            sourceLists = emptyList(),
        ).runCycle(
            SourceListConfig(
                SourceList.SDN, url, ScopeConfig.SDN_ONLY,
                FakeAdapter(headDigest = digest, getBody = validSnapshot, getDigest = null),
            ),
        )

        outcome.status shouldBe CycleStatus.ACTIVATED
        val versionId = outcome.versionId.shouldNotBeNull()
        versionId.digest shouldBe digest
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe versionId
    }
}
