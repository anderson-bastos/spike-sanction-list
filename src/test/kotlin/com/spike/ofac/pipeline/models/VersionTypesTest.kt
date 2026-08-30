package com.spike.ofac.pipeline.models

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.nio.file.Path

/**
 * Unit tests for the version identity and configuration value types (task 2.2):
 * [VersionId], [Sha256Digest], [VersionMetadata], [VersionPointers],
 * [ScopeConfig], [RetentionPolicy].
 */
class VersionTypesTest {

    private val digestA = Sha256Digest("a".repeat(64))
    private val digestB = Sha256Digest("b".repeat(64))
    private val date = LocalDate.of(2024, 1, 15)

    // --- VersionId / Sha256Digest ---------------------------------------

    @Test
    fun `same-day publications with different digests are distinct versions (Req 7-2, 7-3)`() {
        val v1 = VersionId(date, digestA)
        val v2 = VersionId(date, digestB)
        v1 shouldNotBe v2
    }

    @Test
    fun `versionId equality is by value over publishDate and digest`() {
        VersionId(date, digestA) shouldBe VersionId(date, digestA)
    }

    @Test
    fun `digest is normalized to lowercase via ofHex`() {
        Sha256Digest.ofHex("A".repeat(64)) shouldBe Sha256Digest("a".repeat(64))
    }

    @Test
    fun `digest rejects wrong length`() {
        shouldThrow<IllegalArgumentException> { Sha256Digest("abc") }
    }

    @Test
    fun `digest rejects non-hex characters`() {
        shouldThrow<IllegalArgumentException> { Sha256Digest("z".repeat(64)) }
    }

    // --- VersionMetadata -------------------------------------------------

    @Test
    fun `versionMetadata defaults rawSnapshotPath and integrityOk to null (Req 15-6)`() {
        val meta = sampleMetadata()
        meta.rawSnapshotPath shouldBe null
        meta.integrityOk shouldBe null
    }

    @Test
    fun `versionMetadata carries reconciliation counts and lifecycle state`() {
        val meta = sampleMetadata().copy(
            rawSnapshotPath = Path.of("/snapshots/2024-01-15_aaaa.xml"),
            integrityOk = true,
            state = VersionState.COLD,
        )
        meta.expectedCount shouldBe 8
        meta.persistedCount shouldBe 8
        meta.state shouldBe VersionState.COLD
        meta.integrityOk shouldBe true
    }

    // --- VersionPointers -------------------------------------------------

    @Test
    fun `versionPointers previous and nMinus2 default to null`() {
        val pointers = VersionPointers(current = VersionId(date, digestA))
        pointers.previous shouldBe null
        pointers.nMinus2 shouldBe null
    }

    // --- ScopeConfig -----------------------------------------------------

    @Test
    fun `scopeConfig has only the two valid values (Req 12-1)`() {
        ScopeConfig.entries.toSet() shouldBe setOf(ScopeConfig.SDN_ONLY, ScopeConfig.SDN_AND_CONSOLIDATED)
    }

    // --- RetentionPolicy -------------------------------------------------

    @Test
    fun `retentionPolicy retentionPeriod defaults to null (pending decision, Req 14)`() {
        val policy = RetentionPolicy(enabled = true, preserve = PreserveKind.RAW)
        policy.retentionPeriod shouldBe null
    }

    @Test
    fun `retentionPolicy carries an explicit period and preserve kind`() {
        val policy = RetentionPolicy(
            enabled = true,
            retentionPeriod = Duration.ofDays(365),
            preserve = PreserveKind.BOTH,
        )
        policy.retentionPeriod shouldBe Duration.ofDays(365)
        policy.preserve shouldBe PreserveKind.BOTH
    }

    private fun sampleMetadata() = VersionMetadata(
        versionId = VersionId(date, digestA),
        sourceList = SourceList.SDN,
        recordCount = 10,
        outOfScopeCount = 2,
        overlapCount = 0,
        expectedCount = 8,
        persistedCount = 8,
        state = VersionState.HOT,
        ingestedAt = Instant.parse("2024-01-15T00:00:00Z"),
    )
}
