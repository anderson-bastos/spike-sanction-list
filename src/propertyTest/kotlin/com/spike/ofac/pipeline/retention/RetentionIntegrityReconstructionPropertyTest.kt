package com.spike.ofac.pipeline.retention

import com.spike.ofac.config.RawSnapshotStoreProperties
import com.spike.ofac.pipeline.models.InternalModelEntry
import com.spike.ofac.pipeline.models.ScopeConfig
import com.spike.ofac.pipeline.models.Sha256Digest
import com.spike.ofac.pipeline.models.VersionId
import com.spike.ofac.pipeline.store.FsRawSnapshotStore
import com.spike.ofac.pipeline.store.InMemoryVersionStore
import com.spike.ofac.pipeline.stages.transform.AdvancedXmlStreamParser
import com.spike.ofac.pipeline.stages.transform.Transform
import com.spike.ofac.pipeline.stages.transform.TransformResult
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
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
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Property 17: Retention integrity and reconstruction fidelity.
 *
 * Feature: ofac-sanctions-ingestion, Property 17: Retention integrity and
 * reconstruction fidelity
 *
 * **Validates: Requirements 14.3, 14.5**
 *
 * A `COLD` version retained with `RAW` preserved keeps the immutable raw-snapshot
 * **file** already written to the local `Raw_Snapshot_Store` (task 13.3), keyed by
 * (`Publish_Date`, `Digest`). Because OFAC never republishes a past version, that
 * stored file is the only faithful source for reconstructing a historical list
 * state. This property pins the two guarantees the retention lifecycle owes over
 * that file:
 *
 *  - **Integrity + reconstruction fidelity (Req 14.3, 14.5).** When the stored
 *    file's SHA-256 equals the recorded `Digest`, the file is intact:
 *    [RawSnapshotStore.verifyIntegrity][com.spike.ofac.pipeline.store.RawSnapshotStore.verifyIntegrity]
 *    returns `true`, [RetentionManager.checkColdIntegrity] reports
 *    [IntegrityOutcome.Ok], and **re-transforming the stored bytes reproduces the
 *    recorded model exactly** — the same entries, in the same order, field for
 *    field. Retrieving the raw file and running the real `transform` stage over it
 *    yields the record set that was originally persisted for that version.
 *
 *  - **Corruption is flagged, the `Digest` is preserved (Req 14.5).** When the
 *    stored file's bytes do **not** hash to the recorded `Digest` (a corrupted /
 *    tampered file, modeled here as a recorded `Digest` that is not the real hash
 *    of the stored bytes), `verifyIntegrity` returns `false` and
 *    [RetentionManager.checkColdIntegrity] returns
 *    [IntegrityOutcome.FlaggedUnusable] — the version is marked **unusable for
 *    reconstruction** (its `integrity_ok` flag flips to `false` via
 *    [VersionStore.markUnusable][com.spike.ofac.pipeline.store.VersionStore.markUnusable])
 *    while the **recorded `Digest` is preserved for audit**, unchanged. The
 *    version's identity and records are never mutated or deleted.
 *
 * ## What each try exercises
 * The generator renders one buildable OFAC Advanced XML snapshot to bytes and
 * transforms it into the **recorded model** (the persisted entries). It then:
 *
 *  1. stores the exact snapshot bytes in a per-try [FsRawSnapshotStore] under a
 *     [VersionId] whose recorded `Digest` **is** the real SHA-256 of those bytes
 *     (the intact, faithful version); and
 *  2. registers a second [VersionId] over the same bytes whose recorded `Digest`
 *     is deliberately **not** the real hash (the corrupted version), so the stored
 *     file no longer hashes to the recorded `Digest`.
 *
 * Each `@Property` try gets its own temp folder (cleaned up afterwards) so files
 * never collide across iterations.
 */
@Tag(PropertyTests.FEATURE_TAG)
class RetentionIntegrityReconstructionPropertyTest {

    private val parser = AdvancedXmlStreamParser()
    private val transform = Transform()

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 17: Retention integrity and reconstruction fidelity")
    fun retentionIntegrityAndReconstructionFidelity(
        @ForAll @From("snapshots") snapshot: GeneratedSnapshot,
    ) {
        val folder = Files.createTempDirectory("retention-integrity-prop-")
        try {
            val rawStore = FsRawSnapshotStore(RawSnapshotStoreProperties(folder = folder))
            val versionStore = InMemoryVersionStore()
            val manager = RetentionManager(versionStore, rawStore)

            val bytes = snapshot.toXmlBytes()

            // The recorded model: the entries the transform produces for this
            // publication — exactly what would have been persisted for the version.
            val recordedModel = transformOf(bytes, snapshot.rawRecordCount)

            // ---- Intact COLD version: recorded Digest IS the real SHA-256. ----
            val intactId = VersionId(snapshot.publishDate, sha256(bytes))
            versionStore.putIsolated(intactId, recordedModel)
            rawStore.put(intactId, bytes)

            // Req 14.5: stored file's SHA-256 equals the recorded Digest => intact.
            rawStore.verifyIntegrity(intactId) shouldBe true
            manager.checkColdIntegrity(intactId) shouldBe IntegrityOutcome.Ok
            // A passing integrity check never flags the version unusable: it leaves
            // the integrity flag untouched (never flips it to false).
            (versionStore.metadataOf(intactId).shouldNotBeNull().integrityOk == false) shouldBe false

            // Req 14.3: re-transforming the STORED raw file reproduces the recorded
            // model exactly — same entries, same order, field for field.
            val reconstructed = transformOf(rawStore.get(intactId), snapshot.rawRecordCount)
            reconstructed shouldContainExactly recordedModel

            // ---- Corrupted COLD version: recorded Digest is NOT the real hash. ----
            // Same bytes on disk, but the version's recorded Digest does not match
            // them, modeling a corrupted/tampered stored file (Req 14.5).
            val corruptedId = VersionId(snapshot.publishDate, mismatchedDigest(intactId.digest))
            versionStore.putIsolated(corruptedId, recordedModel)
            rawStore.put(corruptedId, bytes)

            // Req 14.5: stored bytes don't hash to the recorded Digest => not intact.
            rawStore.verifyIntegrity(corruptedId) shouldBe false

            val outcome = manager.checkColdIntegrity(corruptedId)
            val flagged = outcome.shouldBeInstanceOf<IntegrityOutcome.FlaggedUnusable>()
            // The recorded Digest is preserved for audit, unchanged.
            flagged.recordedDigest shouldBe corruptedId.digest

            // The version is flagged unusable for reconstruction, but its identity
            // and records are left intact (only integrity_ok flips to false).
            val corruptedMeta = versionStore.metadataOf(corruptedId).shouldNotBeNull()
            corruptedMeta.integrityOk shouldBe false
            corruptedMeta.versionId shouldBe corruptedId
            versionStore.recordsOf(corruptedId).shouldNotBeNull() shouldContainExactly recordedModel
        } finally {
            deleteRecursively(folder)
        }
    }

    /**
     * Parse + transform [bytes] into the recorded model (the persisted entries).
     * The generator only produces buildable snapshots, so the transform always
     * succeeds; a non-[TransformResult.Ok] here would itself be a failure.
     */
    private fun transformOf(bytes: ByteArray, rawRecordCount: String?): List<InternalModelEntry> {
        val parsed = parser.parse(bytes.inputStream())
        val result = transform.fromParsed(parsed, ScopeConfig.SDN_ONLY, rawRecordCount)
        return (result as TransformResult.Ok).entries
    }

    private fun sha256(bytes: ByteArray): Sha256Digest {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Sha256Digest.ofHex(digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
    }

    /**
     * A well-formed 64-hex digest guaranteed different from [real] by flipping its
     * first hex character. Used to fabricate a recorded digest that does not match
     * the stored bytes (a corrupted/tampered file).
     */
    private fun mismatchedDigest(real: Sha256Digest): Sha256Digest {
        val hex = real.value
        val head = if (hex[0] == '0') '1' else '0'
        return Sha256Digest.ofHex(head + hex.substring(1))
    }

    private fun deleteRecursively(folder: Path) {
        if (!Files.exists(folder)) return
        Files.walk(folder).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    // ------------------------------------------------------------------
    // Generated snapshot model + Advanced XML rendering.
    // ------------------------------------------------------------------

    /**
     * A generated snapshot: a `Publish_Date`, a raw `Record_Count`, and a set of
     * buildable profiles. [toXmlBytes] renders it to Advanced XML bytes — the raw
     * publication whose file is stored, verified, and reconstructed.
     */
    data class GeneratedSnapshot(
        val publishDate: LocalDate,
        val rawRecordCount: String?,
        val profiles: List<ProfileSpec>,
    ) {

        fun toXmlBytes(): ByteArray {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            sb.append("<Sanctions>\n")

            sb.append("  <DateOfIssue><Year>").append(publishDate.year)
                .append("</Year><Month>").append(publishDate.monthValue)
                .append("</Month><Day>").append(publishDate.dayOfMonth)
                .append("</Day></DateOfIssue>\n")

            sb.append("  <ReferenceValueSets>\n")
            sb.append("    <FeatureType ID=\"").append(FT_NATIONALITY).append("\">Nationality Country</FeatureType>\n")
            sb.append("    <List ID=\"").append(LIST_SDN).append("\">SDN List</List>\n")
            sb.append("  </ReferenceValueSets>\n")

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
    // Arbitraries (flatMap/map only).
    // ------------------------------------------------------------------

    @Provide
    fun snapshots(): Arbitrary<GeneratedSnapshot> {
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

        // Non-ASCII strings to prove verbatim UTF-8 survives store+reconstruct (Req 4.3).
        val NON_ASCII = listOf("Hải", "Phòng", "Skořepka", "Město", "München", "São", "Łódź", "北京", "Мoskva")
    }
}
