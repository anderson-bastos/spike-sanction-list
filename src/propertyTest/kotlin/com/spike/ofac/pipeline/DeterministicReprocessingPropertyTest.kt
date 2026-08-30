package com.spike.ofac.pipeline

import com.spike.ofac.pipeline.models.ScopeConfig
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.models.VersionId
import com.spike.ofac.pipeline.stages.VersionPlan
import com.spike.ofac.pipeline.stages.VersionStage
import com.spike.ofac.pipeline.stages.transform.AdvancedXmlStreamParser
import com.spike.ofac.pipeline.stages.transform.Transform
import com.spike.ofac.pipeline.stages.transform.TransformResult
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Tag
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Property 15: Deterministic reprocessing.
 *
 * Validates: Requirements 11.4
 *
 * Feature: ofac-sanctions-ingestion, Property 15: Deterministic reprocessing
 *
 * Req 11.4: when a cycle reprocesses the full snapshot for a given publication
 * and completes successfully, the pipeline must produce a Version whose
 * persisted content is identical to the Version a first-attempt successful cycle
 * for the same publication would produce. In other words, running the pipeline
 * twice over the **same publication snapshot bytes** must yield the same
 * `version_id` and a byte-identical record set — nothing about the transform,
 * reference resolution, or version identity may depend on run order, timing, or
 * any hidden state.
 *
 * ## What the property exercises
 * The generator renders one buildable OFAC Advanced XML snapshot to bytes
 * (`ByteArray`). A single publication is one immutable byte sequence, so
 * "reprocess the same publication" means: feed those exact same bytes through
 * the pipeline twice. Each run performs the real pre-activation pipeline path
 * that determines a version's persisted content:
 *
 *  1. **parse** — [AdvancedXmlStreamParser.parse] over the snapshot bytes
 *     (`transform` stage, task 5.1);
 *  2. **transform** — [Transform.fromParsed] producing the deduplicated,
 *     reference-resolved [TransformResult.Ok.entries] — the record set that gets
 *     persisted (tasks 5.2 / 3 / 4);
 *  3. **version** — [VersionStage.build] deriving the [VersionId] from the
 *     snapshot's `Publish_Date` and the SHA-256 of the raw bytes (task 7.1,
 *     Req 7.2). The digest is recomputed independently on each run from the same
 *     bytes, exactly as validate/version would.
 *
 * The property then asserts that the two independent runs agree:
 *  - **identical `version_id`** — same `(publishDate, sha256(bytes))` both times;
 *  - **byte-identical record set** — the two entry lists are equal element for
 *    element **and in the same order** (a list `shouldContainExactly` compares
 *    ordered equality; `InternalModelEntry` is a data class so equality is a
 *    deep, field-by-field comparison over the fully-resolved record).
 *
 * Every generated profile is deliberately *buildable* (≥ 1 alias so a primary
 * name exists, and ≥ 1 resolvable sanction program, Req 4.4/4.5) so both runs
 * reach a successful [TransformResult.Ok] / [VersionPlan.Accepted]; a snapshot
 * that failed to transform would not exercise "completes successfully" (Req 11.4).
 */
@Tag(PropertyTests.FEATURE_TAG)
class DeterministicReprocessingPropertyTest {

    private val parser = AdvancedXmlStreamParser()
    private val transform = Transform()

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 15: Deterministic reprocessing")
    fun reprocessingTheSameSnapshotYieldsIdenticalVersionAndRecords(
        @ForAll @From("snapshots") snapshot: GeneratedSnapshot,
    ) {
        val bytes = snapshot.toXmlBytes()

        // ---- Run the pipeline twice over the SAME publication bytes. ----
        val first = process(bytes, snapshot.publishDate, snapshot.rawRecordCount)
        val second = process(bytes, snapshot.publishDate, snapshot.rawRecordCount)

        // Both runs complete successfully (Req 11.4 speaks of successful cycles).
        val firstOk = first.shouldNotBeNull()
        val secondOk = second.shouldNotBeNull()

        // ---- Identical version_id: same (publishDate, sha256(bytes)). ----
        secondOk.versionId shouldBe firstOk.versionId

        // ---- Byte-identical record set: same entries, same order. ----
        // (data-class equality is a deep field-by-field comparison, so this is a
        // content-identical, order-identical assertion over the resolved records.)
        secondOk.entries shouldContainExactly firstOk.entries
    }

    /** The successful outcome of one pipeline run: the version identity + record set. */
    private data class RunResult(
        val versionId: VersionId,
        val entries: List<com.spike.ofac.pipeline.models.InternalModelEntry>,
    )

    /**
     * Run the real pre-activation pipeline path over [bytes] once: parse →
     * transform → version. Returns `null` if the snapshot did not transform
     * successfully or the version plan was rejected (the generator only ever
     * produces buildable snapshots, so a `null` here would itself be a failure).
     */
    private fun process(bytes: ByteArray, publishDate: LocalDate, rawRecordCount: String?): RunResult? {
        val parsed = parser.parse(bytes.inputStream())
        val transformResult = transform.fromParsed(parsed, ScopeConfig.SDN_ONLY, rawRecordCount)
        if (transformResult !is TransformResult.Ok) return null

        val plan = VersionStage.build(
            entries = transformResult.entries,
            publishDate = publishDate,
            digest = sha256(bytes),
            scope = ScopeConfig.SDN_ONLY,
            rawRecordCount = transformResult.rawRecordCount,
            outOfScopeCount = transformResult.outOfScopeCount,
        )
        if (plan !is VersionPlan.Accepted) return null

        // Stamp the version onto the records exactly as persist would (Req 7.4),
        // so the compared record set is the one that would actually be persisted.
        val stamped = VersionStage.stampVersion(transformResult.entries, plan.versionId)
        return RunResult(plan.versionId, stamped)
    }

    private fun sha256(bytes: ByteArray): Sha256Digest {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Sha256Digest.ofHex(digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
    }

    // ------------------------------------------------------------------
    // Generated snapshot model + Advanced XML rendering.
    // ------------------------------------------------------------------

    /**
     * A generated snapshot: a `Publish_Date`, a raw `Record_Count`, and a set of
     * buildable profiles. [toXmlBytes] renders it to Advanced XML bytes — the
     * "publication snapshot" the pipeline reprocesses.
     */
    data class GeneratedSnapshot(
        val publishDate: LocalDate,
        val rawRecordCount: String?,
        val profiles: List<ProfileSpec>,
    ) {

        /**
         * Render this snapshot to Advanced XML bytes matching the structure the
         * [AdvancedXmlStreamParser] reads (DateOfIssue, ReferenceValueSets,
         * SanctionsEntries, DistinctParties). The bytes are the immutable
         * publication; both pipeline runs consume this exact array.
         */
        fun toXmlBytes(): ByteArray {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            sb.append("<Sanctions>\n")

            // DateOfIssue -> Publish_Date (not otherwise consumed by transform).
            sb.append("  <DateOfIssue><Year>").append(publishDate.year)
                .append("</Year><Month>").append(publishDate.monthValue)
                .append("</Month><Day>").append(publishDate.dayOfMonth)
                .append("</Day></DateOfIssue>\n")

            // ReferenceValueSets: FeatureType + List labels used for resolution.
            sb.append("  <ReferenceValueSets>\n")
            sb.append("    <FeatureType ID=\"").append(FT_NATIONALITY).append("\">Nationality Country</FeatureType>\n")
            sb.append("    <List ID=\"").append(LIST_SDN).append("\">SDN List</List>\n")
            sb.append("  </ReferenceValueSets>\n")

            // SanctionsEntries: one program-bearing entry per profile so each is
            // buildable (>= 1 resolvable sanction program, Req 4.4).
            sb.append("  <SanctionsEntries>\n")
            profiles.forEach { spec ->
                sb.append("    <SanctionsEntry ID=\"SE-").append(spec.key)
                    .append("\" ProfileID=\"").append(spec.profileId)
                    .append("\" ListID=\"").append(LIST_SDN).append("\">\n")
                spec.programs.forEach { program ->
                    sb.append("      <SanctionsMeasure SanctionsTypeID=\"1\">")
                        .append("<Comment>").append(escape(program)).append("</Comment>")
                        .append("</SanctionsMeasure>\n")
                }
                sb.append("    </SanctionsEntry>\n")
            }
            sb.append("  </SanctionsEntries>\n")

            // DistinctParties: one buildable party per profile.
            sb.append("  <DistinctParties>\n")
            profiles.forEach { spec ->
                sb.append("    <DistinctParty FixedRef=\"").append(spec.fixedRef).append("\">\n")
                sb.append("      <Profile ID=\"").append(spec.profileId)
                    .append("\" PartySubTypeID=\"").append(spec.partySubTypeId).append("\">\n")
                sb.append("        <Identity ID=\"").append(spec.identityId).append("\">\n")
                spec.aliases.forEach { alias ->
                    sb.append("          <Alias Primary=\"").append(alias.primary).append("\">\n")
                    sb.append("            <DocumentedName><DocumentedNamePart><NamePartValue>")
                        .append(escape(alias.fullName))
                        .append("</NamePartValue></DocumentedNamePart></DocumentedName>\n")
                    sb.append("          </Alias>\n")
                }
                sb.append("        </Identity>\n")
                spec.nationalities.forEach { nat ->
                    sb.append("        <Feature FeatureTypeID=\"").append(FT_NATIONALITY).append("\">")
                        .append("<FeatureVersion><VersionDetail>").append(escape(nat))
                        .append("</VersionDetail></FeatureVersion></Feature>\n")
                }
                sb.append("      </Profile>\n")
                sb.append("    </DistinctParty>\n")
            }
            sb.append("  </DistinctParties>\n")

            sb.append("</Sanctions>\n")
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        private fun escape(text: String): String = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    /** One generated buildable profile (in-scope, >= 1 alias, >= 1 program). */
    data class ProfileSpec(
        val key: Int,
        val partySubTypeId: String,
        val aliases: List<AliasSpec>,
        val nationalities: List<String>,
        val programs: List<String>,
    ) {
        val fixedRef: String = "FR-$key"
        val profileId: String = "P-$key"
        val identityId: String = "ID-$key"
    }

    data class AliasSpec(val fullName: String, val primary: Boolean)

    // ------------------------------------------------------------------
    // Arbitraries (flatMap/map only — no Combinators.combine(...).as).
    // ------------------------------------------------------------------

    @Provide
    fun snapshots(): Arbitrary<GeneratedSnapshot> {
        // Distinct integer keys -> distinct FixedRefs/ProfileIDs within a snapshot.
        val keyPool: Arbitrary<List<Int>> =
            Arbitraries.integers().between(0, 400)
                .list().ofMinSize(1).ofMaxSize(6).uniqueElements()

        val publishDateArb: Arbitrary<LocalDate> =
            Arbitraries.integers().between(0, 3650).map { LocalDate.of(2000, 1, 1).plusDays(it.toLong()) }

        return keyPool.flatMap { keys ->
            publishDateArb.flatMap { publishDate ->
                sequence(keys.map { profileSpec(it) }).map { specs ->
                    GeneratedSnapshot(
                        publishDate = publishDate,
                        // record_count = in-scope count (all generated profiles are
                        // in-scope) so version.build accepts and reconciles cleanly.
                        rawRecordCount = specs.size.toString(),
                        profiles = specs,
                    )
                }
            }
        }
    }

    private fun profileSpec(key: Int): Arbitrary<ProfileSpec> {
        val entityTypeArb = Arbitraries.of("3", "4") // 3 = Entity, 4 = Individual (both in-scope)
        return entityTypeArb.flatMap { partySubTypeId ->
            aliasesArb(key).flatMap { aliases ->
                nationalitiesArb(key).flatMap { nationalities ->
                    programsArb(key).map { programs ->
                        ProfileSpec(
                            key = key,
                            partySubTypeId = partySubTypeId,
                            aliases = aliases,
                            nationalities = nationalities,
                            programs = programs,
                        )
                    }
                }
            }
        }
    }

    /** At least one alias so a primary name exists (Req 4.5). Non-ASCII injected (Req 4.3). */
    private fun aliasesArb(key: Int): Arbitrary<List<AliasSpec>> {
        val glyph = NON_ASCII[key % NON_ASCII.size]
        return Arbitraries.integers().between(0, 2).flatMap { extra ->
            Arbitraries.integers().between(-1, extra).map { primaryPos ->
                (0..extra).map { i ->
                    AliasSpec(fullName = "Name $glyph $key alias $i", primary = i == primaryPos)
                }
            }
        }
    }

    private fun nationalitiesArb(key: Int): Arbitrary<List<String>> =
        Arbitraries.integers().between(0, 2).map { n ->
            (0 until n).map { i -> "Country-${NON_ASCII[(key + i) % NON_ASCII.size]}-$i" }
        }

    /** At least one program so the entry is buildable (Req 4.4). */
    private fun programsArb(key: Int): Arbitrary<List<String>> =
        Arbitraries.integers().between(1, 3).map { n -> (0 until n).map { i -> "PROG-$key-$i" } }

    /** Sequence a list of arbitraries into an arbitrary of list, via flatMap/map. */
    private fun <T> sequence(arbs: List<Arbitrary<T>>): Arbitrary<List<T>> =
        arbs.fold(Arbitraries.just(emptyList())) { acc, arb ->
            acc.flatMap { list -> arb.map { value -> list + value } }
        }

    private companion object {
        const val FT_NATIONALITY = "10"
        const val LIST_SDN = "91"

        // Non-ASCII strings to prove verbatim UTF-8 survives reprocessing (Req 4.3).
        val NON_ASCII = listOf("Hải", "Phòng", "Skořepka", "Město", "München", "São", "Łódź", "北京", "Мoskva")
    }
}
