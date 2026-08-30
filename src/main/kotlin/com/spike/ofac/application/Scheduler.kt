package com.spike.ofac.application


import com.spike.ofac.application.port.out.SourceAdapter
import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.version.Validate
import com.spike.ofac.domain.version.ValidationResult
import com.spike.ofac.domain.version.VersionPlan
import com.spike.ofac.domain.version.VersionStage
import com.spike.ofac.application.obtain.ChangeDecision
import com.spike.ofac.application.obtain.DownloadResult
import com.spike.ofac.application.obtain.Obtain
import com.spike.ofac.application.persist.Persist
import com.spike.ofac.application.persist.PersistResult
import com.spike.ofac.application.publish.ActivationResult
import com.spike.ofac.application.publish.Publish
import com.spike.ofac.domain.transform.AdvancedXmlStreamParser
import com.spike.ofac.domain.transform.ParsedSnapshot
import com.spike.ofac.domain.transform.RawPartialDate
import com.spike.ofac.domain.transform.Transform
import com.spike.ofac.domain.transform.TransformResult
import com.spike.ofac.application.port.out.RawSnapshotStore
import com.spike.ofac.application.port.out.VersionStore
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The `Scheduler` — triggers one ingestion cycle per configured [SourceListConfig]
 * on a configurable, bounded interval and orchestrates the six source-independent
 * stages in order (Req 1.1, Req 11).
 *
 * The Scheduler owns **no ingestion logic**: it wires
 * `obtain → validate → transform → version → persist → publish` and records the
 * resulting [CycleOutcome] so the next scheduled tick simply retries after a
 * failure (Req 1.6, Req 11.2). Because it re-runs the whole cycle from `obtain`
 * on every tick — reading only the source, never any intermediate artifact — a
 * fresh cycle after a failed one recovers naturally (Req 11.3): there is no
 * persisted half-state to resume from, since every pre-activation failure leaves
 * `CURRENT` and the pointer trio untouched (Req 11.1, 11.5).
 *
 * Design contract (`design.md` "Scheduler"):
 * ```
 * Scheduler.run_cycle(source_list: SourceListConfig) -> CycleOutcome
 *   # invoked on the polling interval; one call per Source_List per tick
 * CycleOutcome = { status: SKIPPED_NO_CHANGE | ACTIVATED | FAILED,
 *                  failed_stage?, cause?, version_id? }
 * ```
 *
 * ## Deriving the two identity inputs
 *
 * **Digest** — the `VersionId`'s digest is the SHA-256 recomputed over the
 * downloaded snapshot bytes ([sha256Of]). This is the same value `validate`
 * checks the advertised digest against, and `persist`/`RawSnapshotStore` later
 * verify the stored file against, so identity, integrity, and the raw-file name
 * all agree on one hash. The advertised digest is trusted only for
 * change-detection and integrity *validation*; the version's identity always
 * uses the locally computed hash so two same-day publications with different
 * content get distinct `VersionId`s (Req 7.2, 7.3).
 *
 * **Publish_Date** — derived best-effort ([derivePublishDate]) from, in order:
 *   1. the parsed snapshot's `DateOfIssue` ([ParsedSnapshot.publishDate], a
 *      [RawPartialDate]) when it carries at least a year — this is the source's
 *      own authoritative publication date and the preferred choice;
 *   2. otherwise the HEAD `Last-Modified` instant, interpreted as a UTC date, as
 *      a transport-level approximation of when the source last changed;
 *   3. otherwise today's UTC date, so a cycle is never blocked purely for lack of
 *      a date (the digest still disambiguates identity, Req 7.3).
 * A partial `DateOfIssue` (year-only, or year+month) is completed with January /
 * the first of the month so a `LocalDate` can be formed while preserving the
 * source's year (the part the source actually asserted).
 *
 * **Record_Count** — the Advanced XML (the canonical ingestion format) carries no
 * dedicated `<Record_Count>` element, so the "count from the body" (Req 8) is the
 * number of `DistinctParty` records the snapshot parsed to
 * ([ParsedSnapshot.profiles]`.size`). Feeding that as `Record_Count` makes the
 * reconciliation identity hold exactly: `expected = record_count - out_of_scope -
 * overlaps`, i.e. for a single list `expected == in-scope entry count`, which is
 * the `persistedCount` `publish` checks against (Req 8.1, 8.2).
 */
class Scheduler(
    private val versionStore: VersionStore,
    private val rawSnapshotStore: RawSnapshotStore,
    private val sourceLists: List<SourceListConfig>,
    private val parser: AdvancedXmlStreamParser = AdvancedXmlStreamParser(),
    private val transform: Transform = Transform(),
    private val clock: () -> Instant = Instant::now,
) {

    private val log = LoggerFactory.getLogger(Scheduler::class.java)

    /**
     * The most recent [CycleOutcome] observed per list, so the outcome of a tick
     * (including a failure naming its stage) is observable and the next tick's
     * retry is simply the next scheduled invocation (Req 11.2). Reads/writes are
     * confined to the scheduler thread; the map is kept for observability, not
     * for cross-tick state that a fresh cycle depends on (a fresh cycle reads only
     * the source, Req 11.3).
     */
    private val lastOutcomes = mutableMapOf<SourceList, CycleOutcome>()

    /** The last recorded outcome for [sourceList], or `null` before the first tick. */
    fun lastOutcome(sourceList: SourceList): CycleOutcome? = lastOutcomes[sourceList]

    /**
     * The Spring `@Scheduled` trigger. Fires on the configured, bounded interval
     * (`ofac.scheduler.interval`, defaulting to a sub-daily period) and runs one
     * [runCycle] per configured [SourceListConfig] (Req 1.1). The `@Scheduled`
     * annotation lives on [SchedulerConfiguration.scheduledTrigger], which calls
     * this method, so the pure orchestration here stays trivially unit-testable
     * without Spring.
     */
    fun tick() {
        for (config in sourceLists) {
            val outcome =
                try {
                    runCycle(config)
                } catch (e: Exception) {
                    // A defensive backstop: no stage should throw (each maps its own
                    // failure), but if one does, the cycle is recorded as a failure of
                    // that source's obtain-onward pipeline so the tick never aborts the
                    // loop over the other lists, and CURRENT is untouched (Req 11).
                    CycleOutcome.failed(StageName.OBTAIN, e.message ?: e.javaClass.simpleName)
                }
            lastOutcomes[config.sourceList] = outcome
            logOutcome(config, outcome)
        }
    }

    /**
     * Runs one full ingestion cycle for [config], invoking the six stages in
     * order and mapping their results to a single [CycleOutcome].
     *
     * Flow:
     *  1. **obtain.checkChange** — `NoChange` → [CycleOutcome.skippedNoChange];
     *     `HeadFailed` → `FAILED(obtain)`; `Changed` → continue.
     *  2. **obtain.download** — `DownloadFailed` → `FAILED(obtain)`; `Snapshot` → continue.
     *  3. **validate.check** — the downloaded bytes against the advertised digest;
     *     `Rejected` → `FAILED(validate)`.
     *  4. **transform.fromParsed** — parse once (to reach `Publish_Date`) then
     *     transform; `Failed` → `FAILED(transform)`.
     *  5. **version.build** — `Rejected` → `FAILED(version)`.
     *  6. **persist.write** — any `Failed*` → `FAILED(persist)`.
     *  7. **publish.activate** — `Rejected*` → `FAILED(publish)`; `Activated` →
     *     [CycleOutcome.activated] carrying the new `version_id`.
     *
     * Every failure names the stage that failed (Req 11.2). No stage before
     * `publish` mutates `CURRENT`, so any `FAILED(...)` leaves the pointer trio
     * exactly as it was (Req 11.1, 11.5).
     */
    fun runCycle(config: SourceListConfig): CycleOutcome {
        val adapter: SourceAdapter = config.adapter
        val url: URI = config.url
        val lastIngested = versionStore.lastIngested(config.sourceList)

        // --- Stage 1: obtain.checkChange (HEAD) ---
        val headLastModified: Instant? = when (val decision = Obtain.checkChange(adapter, url, lastIngested)) {
            is ChangeDecision.NoChange ->
                return CycleOutcome.skippedNoChange()

            is ChangeDecision.HeadFailed ->
                return CycleOutcome.failed(StageName.OBTAIN, decision.cause)

            // Carry the HEAD Last-Modified forward as the fallback Publish_Date input
            // for when the snapshot body carries no DateOfIssue (see derivePublishDate).
            is ChangeDecision.Changed -> decision.lastModified
        }

        // --- Stage 1b: obtain.download (GET) ---
        val snapshot = when (val download = Obtain.download(adapter, url)) {
            is DownloadResult.DownloadFailed ->
                return CycleOutcome.failed(StageName.OBTAIN, download.cause)

            is DownloadResult.Snapshot -> download
        }
        val rawBytes = snapshot.bytes

        // The version's identity digest is ALWAYS the SHA-256 recomputed over the
        // downloaded bytes, not the advertised one (Req 7.2, 7.3).
        val computedDigest = sha256Of(rawBytes)

        // --- Stage 2: validate.check (integrity + well-formedness) ---
        when (val validation = Validate.check(rawBytes, snapshot.advertisedDigest)) {
            is ValidationResult.Rejected ->
                return CycleOutcome.failed(StageName.VALIDATE, validation.cause.name)

            is ValidationResult.Ok -> Unit // proceed to transform
        }

        // --- Stage 3: transform (parse once, then transform the parsed snapshot) ---
        // Parse first so Publish_Date (DateOfIssue) is available for the version
        // stage; then transform the already-parsed snapshot to avoid a second parse.
        val parsed: ParsedSnapshot =
            try {
                parser.parse(ByteArrayInputStream(rawBytes))
            } catch (e: Exception) {
                return CycleOutcome.failed(StageName.TRANSFORM, e.message ?: e.javaClass.simpleName)
            }

        // Record_Count "from the body" for the Advanced XML is the number of
        // DistinctParty records the snapshot carries (the canonical format has no
        // dedicated <Record_Count> element — see Transform's KDoc). Reconciliation
        // then holds exactly: expected = record_count - out_of_scope - overlaps, so
        // for a single list expected == in-scope entry count (Req 8.1).
        val bodyRecordCount = parsed.profiles.size.toString()
        val transformResult = transform.fromParsed(parsed, config.scope, bodyRecordCount)
        val transformed = when (transformResult) {
            is TransformResult.Failed ->
                return CycleOutcome.failed(StageName.TRANSFORM, transformResult.detail)

            is TransformResult.Ok -> transformResult
        }

        // --- Stage 4: version.build (identity + expected_count) ---
        val publishDate = derivePublishDate(parsed.publishDate, headLastModified)
        val plan = VersionStage.build(
            entries = transformed.entries,
            publishDate = publishDate,
            digest = computedDigest,
            scope = config.scope,
            rawRecordCount = transformed.rawRecordCount,
            outOfScopeCount = transformed.outOfScopeCount,
        )
        val accepted = when (plan) {
            is VersionPlan.Rejected ->
                return CycleOutcome.failed(StageName.VERSION, plan.reason.name)

            is VersionPlan.Accepted -> plan
        }

        // --- Stage 5: persist.write (raw file + isolated version + associate path) ---
        when (val persisted = Persist.write(accepted, transformed.entries, rawBytes, versionStore, rawSnapshotStore)) {
            is PersistResult.Persisted -> Unit // proceed to publish
            PersistResult.FailedRawWrite ->
                return CycleOutcome.failed(StageName.PERSIST, "RAW_WRITE")
            PersistResult.FailedRawIntegrity ->
                return CycleOutcome.failed(StageName.PERSIST, "RAW_INTEGRITY")
            PersistResult.FailedPersist ->
                return CycleOutcome.failed(StageName.PERSIST, "PERSIST")
        }

        // --- Stage 6: publish.activate (result-validate + atomic activation) ---
        // persistedCount is the in-scope, post-dedup entry count (Req 8.2).
        val persistedCount = transformed.entries.size
        return when (val activation = Publish.activate(config.sourceList, accepted, persistedCount, versionStore)) {
            is ActivationResult.Activated ->
                CycleOutcome.activated(activation.versionId)
            ActivationResult.RejectedCountMismatch ->
                CycleOutcome.failed(StageName.PUBLISH, "COUNT_MISMATCH", accepted.versionId)
            ActivationResult.RejectedRepointFailed ->
                CycleOutcome.failed(StageName.PUBLISH, "REPOINT_FAILED", accepted.versionId)
        }
    }

    private fun logOutcome(config: SourceListConfig, outcome: CycleOutcome) {
        when (outcome.status) {
            CycleStatus.SKIPPED_NO_CHANGE ->
                log.info("Cycle for {} skipped: no change", config.sourceList)
            CycleStatus.ACTIVATED ->
                log.info("Cycle for {} activated version {}", config.sourceList, outcome.versionId)
            CycleStatus.FAILED ->
                log.warn(
                    "Cycle for {} FAILED at stage {}: {} (CURRENT unchanged; retry next tick)",
                    config.sourceList, outcome.failedStage, outcome.cause,
                )
        }
    }

    // --- identity-input derivation helpers ---

    /**
     * Derives a best-effort [LocalDate] `Publish_Date` (see class KDoc).
     *
     * Preference order: the parsed snapshot's `DateOfIssue` ([snapshotDate]) when
     * it has at least a year; else the HEAD [lastModified] as a UTC date; else
     * today (UTC). A partial [snapshotDate] is completed to the first day of its
     * month (or January 1st) so a `LocalDate` can be formed without inventing a
     * more specific day/month than the source asserted.
     */
    internal fun derivePublishDate(snapshotDate: RawPartialDate?, lastModified: Instant?): LocalDate {
        snapshotDate?.year?.let { year ->
            val month = snapshotDate.month?.coerceIn(1, 12) ?: 1
            val day = snapshotDate.day?.coerceIn(1, 28) ?: 1
            return LocalDate.of(year, month, day)
        }
        if (lastModified != null) {
            return lastModified.atZone(ZoneOffset.UTC).toLocalDate()
        }
        return clock().atZone(ZoneOffset.UTC).toLocalDate()
    }

    private fun sha256Of(bytes: ByteArray): Sha256Digest {
        val digestBytes = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = buildString(digestBytes.size * 2) {
            for (b in digestBytes) {
                val v = b.toInt() and 0xFF
                append(HEX_DIGITS[v ushr 4])
                append(HEX_DIGITS[v and 0x0F])
            }
        }
        return Sha256Digest(hex)
    }

    private companion object {
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
    }
}

/**
 * Per-`Source_List` configuration the [Scheduler] iterates over (Req 1.1, Req 13).
 *
 * Bundles the four things a cycle needs that vary per list: which [sourceList]
 * this is (its independent version line, Req 10.2), the snapshot [url] to obtain,
 * the configured [scope] (Req 12), and the source-specific [adapter] seam (Req 13).
 *
 * @property sourceList the list this config drives (SDN / Consolidated).
 * @property url the Advanced XML snapshot URL to HEAD/GET.
 * @property scope the configured list scope for the version stage / dedup path.
 * @property adapter the source adapter encapsulating obtain I/O + field mapping.
 */
data class SourceListConfig(
    val sourceList: SourceList,
    val url: URI,
    val scope: ScopeConfig,
    val adapter: SourceAdapter,
)

/** The six pipeline stages, named so a failure outcome can identify which failed (Req 11.2). */
enum class StageName {
    OBTAIN,
    VALIDATE,
    TRANSFORM,
    VERSION,
    PERSIST,
    PUBLISH,
}

/** The three terminal statuses a cycle can end in (`design.md` `CycleOutcome.status`). */
enum class CycleStatus {
    /** The source had no new publication; nothing was downloaded (Req 1.4). */
    SKIPPED_NO_CHANGE,

    /** A new version was persisted and atomically activated as `CURRENT`. */
    ACTIVATED,

    /** A stage failed before activation; `CURRENT` is unchanged (Req 11.1, 11.5). */
    FAILED,
}

/**
 * The outcome of one [Scheduler.runCycle] invocation (`design.md` `CycleOutcome`).
 *
 * ```
 * CycleOutcome = { status: SKIPPED_NO_CHANGE | ACTIVATED | FAILED,
 *                  failed_stage?, cause?, version_id? }
 * ```
 *
 * A single value type (rather than a sealed hierarchy) mirrors the design's
 * record shape exactly; the optional fields are populated per [status]:
 *  - [CycleStatus.SKIPPED_NO_CHANGE] — all optionals null.
 *  - [CycleStatus.ACTIVATED] — [versionId] set, [failedStage]/[cause] null.
 *  - [CycleStatus.FAILED] — [failedStage] and [cause] set naming the failure
 *    (Req 11.2); [versionId] set only for a `publish`-stage failure where the
 *    version identity is known.
 *
 * @property status the terminal status of the cycle.
 * @property failedStage the stage that failed, for a [CycleStatus.FAILED] outcome.
 * @property cause a human-readable failure cause, for a [CycleStatus.FAILED] outcome.
 * @property versionId the activated (or publish-rejected) version's identity, when known.
 */
data class CycleOutcome(
    val status: CycleStatus,
    val failedStage: StageName? = null,
    val cause: String? = null,
    val versionId: VersionId? = null,
) {
    companion object {
        /** A cycle that found no change and downloaded nothing (Req 1.4). */
        fun skippedNoChange(): CycleOutcome = CycleOutcome(CycleStatus.SKIPPED_NO_CHANGE)

        /** A cycle that persisted and activated [versionId] as `CURRENT`. */
        fun activated(versionId: VersionId): CycleOutcome =
            CycleOutcome(CycleStatus.ACTIVATED, versionId = versionId)

        /**
         * A cycle that failed at [failedStage] with [cause] (Req 11.2). [versionId]
         * is supplied only when the failure is late enough that the identity is
         * known (a `publish`-stage rejection).
         */
        fun failed(failedStage: StageName, cause: String, versionId: VersionId? = null): CycleOutcome =
            CycleOutcome(CycleStatus.FAILED, failedStage = failedStage, cause = cause, versionId = versionId)
    }
}
