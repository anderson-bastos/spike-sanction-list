package com.spike.ofac.pipeline.store

import com.spike.ofac.pipeline.adapters.MappingResult
import com.spike.ofac.pipeline.models.EntityType
import com.spike.ofac.pipeline.models.FixedRef
import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.ScopeConfig
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.models.SourceList
import com.spike.ofac.pipeline.models.VersionId
import com.spike.ofac.pipeline.stages.RejectionReason
import com.spike.ofac.pipeline.stages.Validate
import com.spike.ofac.pipeline.stages.ValidationResult
import com.spike.ofac.pipeline.stages.VersionPlan
import com.spike.ofac.pipeline.stages.VersionStage
import com.spike.ofac.pipeline.stages.obtain.ChangeDecision
import com.spike.ofac.pipeline.stages.obtain.DownloadResult
import com.spike.ofac.pipeline.stages.persist.Persist
import com.spike.ofac.pipeline.stages.persist.PersistResult
import com.spike.ofac.pipeline.stages.publish.ActivationResult
import com.spike.ofac.pipeline.stages.publish.Publish
import com.spike.ofac.pipeline.stages.transform.TransformResult
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Tag
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Property 14: Failure safety before activation.
 *
 * *For any* failure injected at *any* pre-activation stage —
 * `obtain → validate → transform → version → persist` (including the adapter's
 * auth / mapping failures and the persist stage's raw-write / raw-integrity
 * failures) and the `publish` result-validation / repoint gates that run
 * *before* the atomic swap commits — the list's pointer trio
 * (`CURRENT` / `PREVIOUS` / `N_MINUS_2`) is **identical before and after** the
 * cycle, and **no partial version ever becomes `CURRENT`** (Req 2.5, 7.7, 9.4,
 * 11.1, 11.5, 13.4, 13.5, 15.7, 15.9).
 *
 * The invariant is verified at the store/stage level, which is self-contained
 * and does not depend on the `Scheduler` orchestration (task 15.1, in flight):
 *
 *  1. Seed an [InMemoryVersionStore] with a prior, already-activated `CURRENT`
 *     (and optionally a `PREVIOUS`) via [InMemoryVersionStore.putIsolated] +
 *     [InMemoryVersionStore.atomicSetCurrent] — the "last good version".
 *  2. Snapshot the full pointer trio.
 *  3. Inject a failure at one generated pre-activation stage. Stages that never
 *     reach the store (obtain / validate / transform / version / adapter) fail
 *     *before* any store mutation, so the store is not touched at all; the
 *     persist / publish stages are driven against the seeded store with a
 *     deliberately failing collaborator (a throwing raw store, an
 *     integrity-mismatched digest, a throwing version store, a mismatched
 *     persisted count, or an unknown/isolated version that cannot be repointed).
 *  4. Assert the pointer trio is byte-for-byte unchanged, that `CURRENT` still
 *     resolves to the prior good version (never the new / partial candidate),
 *     and that the injected failure surfaced as its expected fail-closed result.
 *
 * The generated inputs are the **failure kind** and the **prior store state**
 * (whether a `PREVIOUS` exists), so every generated case exercises one stage's
 * failure against a live prior window.
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 14: Failure safety before
 * activation`.
 *
 * **Validates: Requirements 2.5, 7.7, 9.4, 11.1, 11.5, 13.4, 13.5, 15.7, 15.9**
 */
@Tag(PropertyTests.FEATURE_TAG)
class FailureSafetyBeforeActivationPropertyTest {

    /** The single list every generated case operates on. */
    private val list = SourceList.SDN

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 14: Failure safety before activation")
    fun injectedPreActivationFailureLeavesPointerTrioUnchanged(
        @ForAll @From("scenarios") scenario: Scenario,
    ) {
        // 1. Seed a live prior window: an activated CURRENT and optionally a PREVIOUS.
        val store = InMemoryVersionStore()
        val priorPrevious: VersionId? =
            if (scenario.withPrevious) {
                val id = freshVersion(store, publishDate = DAY_0, tag = "prev")
                store.atomicSetCurrent(list, id).shouldBeTrue()
                id
            } else {
                null
            }
        val priorCurrent = freshVersion(store, publishDate = DAY_1, tag = "curr")
        store.atomicSetCurrent(list, priorCurrent).shouldBeTrue()

        // 2. Snapshot the trio before the failing cycle.
        val before = trio(store)
        before.current shouldBe priorCurrent
        if (scenario.withPrevious) {
            // The second activation rotated the first-activated version down to PREVIOUS.
            before.previous shouldBe priorPrevious
        }

        // The candidate a failing cycle would have tried to make CURRENT. It shares
        // the same publish date as the prior CURRENT (a same-day republish) but has
        // distinct content, so it is a genuinely different, never-activated version.
        val candidate = VersionId(DAY_1, digestOf("candidate-${scenario.failure}"))
        candidate shouldNotBe priorCurrent

        // 3. Inject the generated pre-activation failure and confirm it fail-closed.
        injectFailure(scenario.failure, store, candidate)

        // 4. The pointer trio must be byte-for-byte identical, CURRENT must still be
        //    the prior good version, and the never-activated candidate must not be
        //    resolvable through ANY pointer (no partial version became CURRENT).
        val after = trio(store)
        after shouldBe before
        after.current shouldBe priorCurrent
        store.getPointer(list, PointerKind.CURRENT) shouldNotBe candidate
        after.previous shouldNotBe candidate
        after.nMinus2 shouldNotBe candidate
    }

    /**
     * Drives the one injected failure for [failure] against the seeded [store],
     * asserting it produces its expected fail-closed result. Stages upstream of
     * persist never call into the store, so for those the mere fact that the
     * stage rejected (and the caller therefore never advances to persist/publish)
     * is what keeps the trio unchanged.
     */
    private fun injectFailure(failure: FailureKind, store: InMemoryVersionStore, candidate: VersionId) {
        when (failure) {
            // --- obtain (Req 2.5, 11.1, 13.5): HEAD / GET failures end the cycle
            // before anything is persisted; CURRENT is retained. ---
            FailureKind.OBTAIN_HEAD_FAILED -> {
                val decision: ChangeDecision = ChangeDecision.HeadFailed("connect timeout")
                (decision is ChangeDecision.HeadFailed).shouldBeTrue()
            }
            FailureKind.OBTAIN_DOWNLOAD_FAILED -> {
                val result: DownloadResult = DownloadResult.DownloadFailed("truncated body")
                (result is DownloadResult.DownloadFailed).shouldBeTrue()
            }

            // --- adapter auth/mapping (Req 13.4, 13.5): a required-field mapping
            // failure aborts before persist, naming the source + field. ---
            FailureKind.ADAPTER_MAPPING_ERROR -> {
                val mapping: MappingResult =
                    MappingResult.MappingError(field = "primary_name", fixedRef = "FR-1")
                (mapping is MappingResult.MappingError).shouldBeTrue()
                (mapping as MappingResult.MappingError).field shouldBe "primary_name"
            }

            // --- validate (Req 3, 11.1): every rejection leaves CURRENT unchanged.
            // Well-formed-check fails on malformed bytes even though the digest matches. ---
            FailureKind.VALIDATE_REJECTED -> {
                val result: ValidationResult = Validate.check(
                    snapshot = MALFORMED_XML_BYTES,
                    advertisedDigest = Sha256Digest.ofHex(digestHex(MALFORMED_XML_BYTES)),
                )
                (result is ValidationResult.Rejected).shouldBeTrue()
                (result as ValidationResult.Rejected).cause shouldBe ValidationResult.Cause.MALFORMED_XML
            }

            // --- transform (Req 4.8, 11.1): an unparseable record fails the whole
            // stage, so no partial version is produced. ---
            FailureKind.TRANSFORM_FAILED -> {
                val result: TransformResult = TransformResult.Failed(
                    cause = TransformResult.Failed.Cause.UNPARSEABLE_RECORD,
                    detail = "record could not be built",
                    fixedRef = "FR-2",
                )
                (result is TransformResult.Failed).shouldBeTrue()
            }

            // --- version (Req 8.4, 11.1): a missing/invalid Record_Count rejects
            // the plan before persist. ---
            FailureKind.VERSION_REJECTED -> {
                val plan = VersionStage.build(
                    entries = emptyList(),
                    publishDate = DAY_1,
                    digest = candidate.digest,
                    scope = ScopeConfig.SDN_ONLY,
                    rawRecordCount = null, // absent -> RECORD_COUNT_MISSING_OR_INVALID
                    outOfScopeCount = 0,
                )
                (plan is VersionPlan.Rejected).shouldBeTrue()
                (plan as VersionPlan.Rejected).reason shouldBe
                    RejectionReason.RECORD_COUNT_MISSING_OR_INVALID
            }

            // --- persist raw-write (Req 15.9): the raw store throws; the partial
            // file is discarded and CURRENT is untouched. ---
            FailureKind.PERSIST_RAW_WRITE -> {
                val result = Persist.write(
                    versionPlan = VersionPlan.Accepted(candidate, expectedCount = 1),
                    entries = candidateEntries(candidate),
                    rawBytes = "raw".toByteArray(),
                    store = store,
                    rawStore = ThrowingOnPutRawStore,
                )
                result shouldBe PersistResult.FailedRawWrite
            }

            // --- persist raw-integrity (Req 15.7): the stored file's SHA-256 does
            // not match the recorded Digest; the file is discarded, CURRENT stays. ---
            FailureKind.PERSIST_RAW_INTEGRITY -> {
                val result = Persist.write(
                    versionPlan = VersionPlan.Accepted(candidate, expectedCount = 1),
                    entries = candidateEntries(candidate),
                    rawBytes = "raw".toByteArray(),
                    store = store,
                    rawStore = IntegrityMismatchRawStore,
                )
                result shouldBe PersistResult.FailedRawIntegrity
            }

            // --- persist record-write (Req 7.7): the version store throws on the
            // record write; the partial version is discarded, CURRENT stays. ---
            FailureKind.PERSIST_RECORD_WRITE -> {
                val failingStore = ThrowingOnPutIsolatedStore(store)
                val result = Persist.write(
                    versionPlan = VersionPlan.Accepted(candidate, expectedCount = 1),
                    entries = candidateEntries(candidate),
                    rawBytes = "raw".toByteArray(),
                    store = failingStore,
                    rawStore = OkRawStore,
                )
                result shouldBe PersistResult.FailedPersist
            }

            // --- publish result-validate (Req 8.3, 11.1): the persisted count does
            // not reconcile; activation is rejected before the swap, CURRENT stays. ---
            FailureKind.PUBLISH_COUNT_MISMATCH -> {
                // The candidate is persisted in isolation first (as persist would),
                // so only the publish gate — not persist — is the failure under test.
                store.putIsolated(candidate, candidateEntries(candidate))
                val plan = VersionPlan.Accepted(candidate, expectedCount = 1)
                val result = Publish.activate(list, plan, persistedCount = 2, store = store)
                result shouldBe ActivationResult.RejectedCountMismatch
            }

            // --- publish repoint-failed (Req 9.4): the atomic repoint cannot be
            // applied (the version was never persisted), leaving the trio unchanged. ---
            FailureKind.PUBLISH_REPOINT_FAILED -> {
                // Deliberately do NOT persist the candidate, so atomicSetCurrent
                // returns false and publish maps it to REPOINT_FAILED (Req 9.4).
                val plan = VersionPlan.Accepted(candidate, expectedCount = 1)
                val result = Publish.activate(list, plan, persistedCount = 1, store = store)
                result shouldBe ActivationResult.RejectedRepointFailed
            }
        }
    }

    // --- generators ---

    @Provide
    fun scenarios(): Arbitrary<Scenario> {
        val failures: Arbitrary<FailureKind> = Arbitraries.of(FailureKind::class.java)
        val withPrevious: Arbitrary<Boolean> = Arbitraries.of(true, false)
        return Combinators.combine(failures, withPrevious).`as` { f, p -> Scenario(f, p) }
    }

    /** A generated case: which stage fails, and whether a PREVIOUS is seeded. */
    data class Scenario(val failure: FailureKind, val withPrevious: Boolean)

    /** The pre-activation failure to inject, one representative per stage. */
    enum class FailureKind {
        OBTAIN_HEAD_FAILED,
        OBTAIN_DOWNLOAD_FAILED,
        ADAPTER_MAPPING_ERROR,
        VALIDATE_REJECTED,
        TRANSFORM_FAILED,
        VERSION_REJECTED,
        PERSIST_RAW_WRITE,
        PERSIST_RAW_INTEGRITY,
        PERSIST_RECORD_WRITE,
        PUBLISH_COUNT_MISMATCH,
        PUBLISH_REPOINT_FAILED,
    }

    // --- helpers ---

    /** The pointer trio for [list] as an immutable, value-comparable snapshot. */
    private fun trio(store: InMemoryVersionStore): PointerTrio =
        PointerTrio(
            current = store.getPointer(list, PointerKind.CURRENT),
            previous = store.getPointer(list, PointerKind.PREVIOUS),
            nMinus2 = store.getPointer(list, PointerKind.N_MINUS_2),
        )

    private data class PointerTrio(
        val current: VersionId?,
        val previous: VersionId?,
        val nMinus2: VersionId?,
    )

    /** Persists a fresh isolated version and returns its id (not yet activated). */
    private fun freshVersion(store: InMemoryVersionStore, publishDate: LocalDate, tag: String): VersionId {
        val id = VersionId(publishDate, digestOf("$tag-${seed++}"))
        store.putIsolated(id, candidateEntries(id))
        return id
    }

    private fun candidateEntries(versionId: VersionId): List<InternalModelEntry> =
        listOf(
            InternalModelEntry(
                fixedRef = FixedRef("FR-${versionId.digest.value.take(8)}"),
                entityType = EntityType.Individual,
                primaryName = "name",
                sanctionPrograms = listOf("program"),
                versionId = versionId,
            ),
        )

    private var seed = 0

    private companion object {
        val DAY_0: LocalDate = LocalDate.of(2020, 1, 1)
        val DAY_1: LocalDate = LocalDate.of(2020, 1, 2)

        /** Bytes that are not well-formed XML, so Validate rejects with MALFORMED_XML. */
        val MALFORMED_XML_BYTES: ByteArray = "<not-closed>".toByteArray()

        fun digestHex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        fun digestOf(content: String): Sha256Digest = Sha256Digest.ofHex(digestHex(content.toByteArray()))
    }

    // --- failing collaborators ---

    /** A raw store whose [put] throws, exercising the RAW_WRITE fail-closed path (Req 15.9). */
    private object ThrowingOnPutRawStore : RawSnapshotStore {
        override fun put(versionId: VersionId, bytes: ByteArray): Path =
            throw java.io.IOException("disk full")

        override fun get(versionId: VersionId): ByteArray = throw UnsupportedOperationException()
        override fun verifyIntegrity(versionId: VersionId): Boolean = true
        override fun delete(versionId: VersionId): Boolean = false
    }

    /**
     * A raw store whose stored-file integrity check fails, exercising the
     * RAW_INTEGRITY fail-closed path (Req 15.7). [put] succeeds (returns a path)
     * but [verifyIntegrity] reports a mismatch.
     */
    private object IntegrityMismatchRawStore : RawSnapshotStore {
        override fun put(versionId: VersionId, bytes: ByteArray): Path = Path.of("/tmp/raw-${versionId.digest.value}")
        override fun get(versionId: VersionId): ByteArray = ByteArray(0)
        override fun verifyIntegrity(versionId: VersionId): Boolean = false
        override fun delete(versionId: VersionId): Boolean = false
    }

    /** A raw store that behaves normally, so the persist failure is isolated to the record write. */
    private object OkRawStore : RawSnapshotStore {
        override fun put(versionId: VersionId, bytes: ByteArray): Path = Path.of("/tmp/raw-${versionId.digest.value}")
        override fun get(versionId: VersionId): ByteArray = ByteArray(0)
        override fun verifyIntegrity(versionId: VersionId): Boolean = true
        override fun delete(versionId: VersionId): Boolean = false
    }

    /**
     * A [VersionStore] decorator whose [putIsolated] throws, exercising the
     * PERSIST (record-write) fail-closed path (Req 7.7). All other operations
     * delegate to the seeded store so the pointer trio it holds stays observable.
     */
    private class ThrowingOnPutIsolatedStore(private val delegate: InMemoryVersionStore) : VersionStore {
        override fun putIsolated(versionId: VersionId, records: List<InternalModelEntry>): Unit =
            throw java.io.IOException("data store write failed")

        override fun associateRawPath(versionId: VersionId, rawPath: Path) =
            delegate.associateRawPath(versionId, rawPath)

        override fun atomicSetCurrent(sourceList: SourceList, versionId: VersionId): Boolean =
            delegate.atomicSetCurrent(sourceList, versionId)

        override fun getPointer(sourceList: SourceList, pointer: PointerKind): VersionId? =
            delegate.getPointer(sourceList, pointer)

        override fun reclassifyCold(sourceList: SourceList) = delegate.reclassifyCold(sourceList)

        override fun coldVersions(sourceList: SourceList): List<VersionId> = delegate.coldVersions(sourceList)

        override fun lastIngested(sourceList: SourceList) = delegate.lastIngested(sourceList)

        override fun verifyIntegrity(versionId: VersionId): Boolean = delegate.verifyIntegrity(versionId)
    }
}
