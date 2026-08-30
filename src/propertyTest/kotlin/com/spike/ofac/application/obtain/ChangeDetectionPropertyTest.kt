package com.spike.ofac.application.obtain

import com.spike.ofac.application.port.out.HeadResponse
import com.spike.ofac.application.port.out.HttpResponse
import com.spike.ofac.application.port.out.MappingResult
import com.spike.ofac.application.port.out.SourceAdapter
import com.spike.ofac.application.port.out.SourceEntityType
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.domain.model.VersionMetadata
import com.spike.ofac.domain.model.VersionState
import com.spike.ofac.domain.transform.RawParsedProfile
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Tag
import java.net.URI
import java.time.Instant
import java.time.LocalDate

/**
 * Property 1: Change-detection decision.
 *
 * Validates that [Obtain.checkChange] downloads (returns
 * [ChangeDecision.Changed]) **iff** the source's advertised content differs from
 * the last-ingested version, across both decision paths the design specifies
 * (`design.md` "obtain", Req 1.3, 1.4, 1.5):
 *
 *  - **Digest path** (Req 1.3, 1.4): when the HEAD advertises a `Digest`, the
 *    decision is `NoChange` iff that digest equals the last-ingested version's
 *    digest, otherwise `Changed`.
 *  - **Fallback path** (Req 1.5): when the HEAD advertises no `Digest`, the
 *    decision falls back to comparing the advertised `Publish_Date` +
 *    `Record_Count` against the last-ingested version's; `NoChange` iff both are
 *    present and equal, otherwise `Changed`.
 *  - **No prior version**: when the list has never been ingested there is no
 *    content to match, so any advertised state is a change (`Changed`).
 *
 * The source state is fed through a fake [SourceAdapter] (mirroring
 * `ObtainSmokeTest.FakeAdapter`) that returns a `HeadResponse(statusCode = 200,
 * digest, lastModified)` built from the generated state, so the pure decision
 * logic is exercised without network I/O.
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 1: Change-detection decision`.
 *
 * **Validates: Requirements 1.3, 1.4, 1.5**
 */
@Tag(PropertyTests.FEATURE_TAG)
class ChangeDetectionPropertyTest {

    /**
     * A generated change-detection scenario: a source state (what the HEAD
     * advertises) paired with a last-ingested state, plus the ground-truth of
     * whether the advertised content differs from what was last ingested.
     *
     * The scenario is constructed so [contentDiffers] is the oracle the
     * biconditional is checked against: it is `true` exactly when the pipeline
     * should re-download.
     */
    data class Scenario(
        /** The digest the HEAD advertises, or `null` to force the fallback path. */
        val advertisedDigest: Sha256Digest?,
        /** The Publish_Date the HEAD advertises (fallback input), or `null`. */
        val advertisedPublishDate: LocalDate?,
        /** The Record_Count the HEAD advertises (fallback input), or `null`. */
        val advertisedRecordCount: Int?,
        /** The last-ingested version, or `null` when the list is fresh. */
        val lastIngested: VersionMetadata?,
    ) {
        /**
         * Ground-truth oracle: does the advertised content differ from the
         * last-ingested version, per the design's decision rules?
         *
         *  - No prior version → always a change.
         *  - Digest advertised → differs iff the digests are unequal.
         *  - No digest → differs unless both fallback inputs are present AND
         *    equal to the last-ingested version's.
         */
        val contentDiffers: Boolean =
            when {
                lastIngested == null -> true
                advertisedDigest != null ->
                    advertisedDigest != lastIngested.versionId.digest
                else -> {
                    val sameDate = advertisedPublishDate != null &&
                        advertisedPublishDate == lastIngested.versionId.publishDate
                    val sameCount = advertisedRecordCount != null &&
                        advertisedRecordCount == lastIngested.recordCount
                    !(sameDate && sameCount)
                }
            }
    }

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 1: Change-detection decision")
    fun changeDetectionDecision(@ForAll @From("scenarios") scenario: Scenario) {
        val adapter = FakeAdapter(
            head = HeadResponse(
                statusCode = 200,
                lastModified = LAST_MODIFIED,
                digest = scenario.advertisedDigest,
            ),
        )

        val decision = Obtain.checkChange(
            adapter = adapter,
            url = URL,
            lastIngested = scenario.lastIngested,
            advertisedPublishDate = scenario.advertisedPublishDate,
            advertisedRecordCount = scenario.advertisedRecordCount,
        )

        // The core biconditional: download (Changed) iff the content differs.
        val downloaded = decision is ChangeDecision.Changed
        downloaded shouldBe scenario.contentDiffers

        // And when the content matches, the decision is exactly NoChange (never a
        // HeadFailed, since the HEAD here always succeeds with a 200).
        if (!scenario.contentDiffers) {
            decision shouldBe ChangeDecision.NoChange
        } else {
            decision.shouldBeInstanceOf<ChangeDecision.Changed>()
        }
    }

    // --- generators --------------------------------------------------------

    @Provide
    fun scenarios(): Arbitrary<Scenario> =
        Arbitraries.oneOf(
            digestPathScenarios(),
            fallbackPathScenarios(),
            freshListScenarios(),
        )

    /**
     * Digest-path scenarios (Req 1.3, 1.4): the HEAD advertises a digest and
     * there is a last-ingested version. The advertised digest is generated to be
     * either equal to the last-ingested digest (→ no change) or different
     * (→ change), covering both sides of the biconditional.
     */
    private fun digestPathScenarios(): Arbitrary<Scenario> =
        digests().flatMap { lastDigest ->
            // 50/50 between advertising the same digest and a fresh (independent)
            // one; an independently generated digest can, rarely, collide with the
            // last one, and the oracle handles either outcome correctly.
            Arbitraries.of(true, false).flatMap { sameContent ->
                val advertised =
                    if (sameContent) Arbitraries.just(lastDigest) else digests()
                advertised.flatMap { advertisedDigest ->
                    lastIngestedFrom(lastDigest).map { last ->
                        Scenario(
                            advertisedDigest = advertisedDigest,
                            advertisedPublishDate = null,
                            advertisedRecordCount = null,
                            lastIngested = last,
                        )
                    }
                }
            }
        }

    /**
     * Fallback-path scenarios (Req 1.5): the HEAD advertises no digest, so the
     * decision compares advertised `Publish_Date` + `Record_Count` against the
     * last-ingested version's. Generates the matching case (both equal) and the
     * differing cases (different date, different count, or an absent input), so
     * both sides of the biconditional are covered.
     */
    private fun fallbackPathScenarios(): Arbitrary<Scenario> =
        digests().flatMap { lastDigest ->
            publishDates().flatMap { lastDate ->
                recordCounts().flatMap { lastCount ->
                    val last = VersionMetadata(
                        versionId = VersionId(lastDate, lastDigest),
                        sourceList = SourceList.SDN,
                        recordCount = lastCount,
                        outOfScopeCount = 0,
                        overlapCount = 0,
                        expectedCount = lastCount,
                        persistedCount = lastCount,
                        state = VersionState.HOT,
                        ingestedAt = Instant.EPOCH,
                    )
                    fallbackAdvertised(lastDate, lastCount).map { (date, count) ->
                        Scenario(
                            advertisedDigest = null,
                            advertisedPublishDate = date,
                            advertisedRecordCount = count,
                            lastIngested = last,
                        )
                    }
                }
            }
        }

    /**
     * Generates the advertised (Publish_Date, Record_Count) fallback pair against
     * a known last-ingested [lastDate]/[lastCount]. Mixes the exact-match case
     * with mutations (different date, different count) and absent inputs (null
     * date or null count) so the "both present and equal" boundary is exercised
     * from every side.
     */
    private fun fallbackAdvertised(
        lastDate: LocalDate,
        lastCount: Int,
    ): Arbitrary<Pair<LocalDate?, Int?>> {
        val dateArb: Arbitrary<LocalDate?> = Arbitraries.oneOf(
            Arbitraries.just(lastDate),
            publishDates(),
            Arbitraries.just(null),
        )
        val countArb: Arbitrary<Int?> = Arbitraries.oneOf(
            Arbitraries.just(lastCount),
            recordCounts(),
            Arbitraries.just(null),
        )
        return dateArb.flatMap { date -> countArb.map { count -> date to count } }
    }

    /**
     * No-prior-version scenarios: whatever the source advertises (digest present
     * or absent) is a change because there is nothing to compare against.
     */
    private fun freshListScenarios(): Arbitrary<Scenario> {
        val digestArb: Arbitrary<Sha256Digest?> = Arbitraries.oneOf(
            digests(),
            Arbitraries.just(null),
        )
        return digestArb.flatMap { digest ->
            publishDates().flatMap { date ->
                recordCounts().map { count ->
                    Scenario(
                        advertisedDigest = digest,
                        advertisedPublishDate = date,
                        advertisedRecordCount = count,
                        lastIngested = null,
                    )
                }
            }
        }
    }

    private fun digests(): Arbitrary<Sha256Digest> =
        Arbitraries.strings()
            .withChars(*HEX_CHARS)
            .ofLength(64)
            .map { Sha256Digest.ofHex(it) }

    private fun publishDates(): Arbitrary<LocalDate> =
        Arbitraries.integers().between(0, 3650)
            .map { EPOCH_DATE.plusDays(it.toLong()) }

    private fun recordCounts(): Arbitrary<Int> =
        Arbitraries.integers().between(0, 100_000)

    private fun lastIngestedFrom(digest: Sha256Digest): Arbitrary<VersionMetadata> =
        publishDates().flatMap { date ->
            recordCounts().map { count ->
                VersionMetadata(
                    versionId = VersionId(date, digest),
                    sourceList = SourceList.SDN,
                    recordCount = count,
                    outOfScopeCount = 0,
                    overlapCount = 0,
                    expectedCount = count,
                    persistedCount = count,
                    state = VersionState.HOT,
                    ingestedAt = Instant.EPOCH,
                )
            }
        }

    /**
     * A minimal in-memory [SourceAdapter] returning a canned HEAD response, so
     * the change-detection logic runs without network I/O. Mirrors
     * `ObtainSmokeTest.FakeAdapter`; the GET and mapping methods are unused by
     * [Obtain.checkChange] and fail if called.
     */
    private class FakeAdapter(
        private val head: HeadResponse,
    ) : SourceAdapter {
        override fun head(url: URI): HeadResponse = head

        override fun get(url: URI): HttpResponse =
            error("get is not used by Obtain.checkChange")

        override fun mapRecord(rawProfile: RawParsedProfile): MappingResult =
            error("mapRecord is not used by Obtain.checkChange")

        override fun entityTypeOf(rawProfile: RawParsedProfile): SourceEntityType =
            error("entityTypeOf is not used by Obtain.checkChange")
    }

    private companion object {
        val URL: URI = URI.create("https://example.test/sdn_advanced.xml")
        val LAST_MODIFIED: Instant = Instant.parse("2024-01-15T00:00:00Z")
        val EPOCH_DATE: LocalDate = LocalDate.of(2020, 1, 1)

        val HEX_CHARS: CharArray =
            (('0'..'9') + ('a'..'f')).toCharArray()
    }
}
