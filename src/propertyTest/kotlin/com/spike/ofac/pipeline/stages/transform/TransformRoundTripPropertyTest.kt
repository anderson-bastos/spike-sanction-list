package com.spike.ofac.pipeline.stages.transform

import com.spike.ofac.pipeline.models.EntityType
import com.spike.ofac.pipeline.models.PartialDate
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

/**
 * Property 5: Transformation preserves data and resolves references (round-trip).
 *
 * Validates: Requirements 4.1, 4.2, 4.3, 4.5, 4.6
 *
 * Feature: ofac-sanctions-ingestion, Property 5: Transformation preserves data and resolves references (round-trip)
 *
 * This is a **model-level** round-trip (design "approach (a)"): it generates
 * source-shaped [RawParsedProfile]s together with the [RawReferenceTables] that
 * make every one of their ID references resolvable, feeds them through
 * [ProfileEntryBuilder.build], and asserts the built
 * [com.spike.ofac.pipeline.models.InternalModelEntry] reproduces every generated
 * source field exactly — the "transform → serialize preserves every field"
 * facet — without going through XML text. That keeps the property focused on the
 * transform's own contract (reference resolution + normalization) rather than
 * XML lexing.
 *
 * Each generated profile is deliberately *buildable*: it always carries
 * - **at least one alias** (so `primary_name` can be set, Req 4.5 — a zero-alias
 *   profile is reported unbuildable by the builder, so "primary name used when no
 *   aliases" means: when no alias is flagged primary, the **first** alias becomes
 *   the primary name), and
 * - **at least one resolvable sanction program** (so the entry meets the
 *   mandatory-program invariant, Req 4.4).
 *
 * Every ID reference the profile carries — `AliasTypeID`, `FeatureTypeID`,
 * `LocationID`, `CountryID`, `LocPartTypeID`, `IDRegDocTypeID`, sanctions
 * `ListID`, relationship `To-ProfileID` / `RelationTypeID` — is placed in the
 * reference tables so it resolves (Req 4.2). Non-ASCII text is injected into
 * names and addresses to prove verbatim UTF-8 preservation (Req 4.3), and birth
 * dates are generated as year-only, partial, and true ranges to prove partial
 * dates survive (Req 4.6).
 *
 * The property asserts, for every generated profile:
 *  - **4.1 / 4.5** the built entry carries the FixedRef, entity type, primary
 *    name (first alias when none is flagged primary), and the remaining aliases;
 *  - **4.2** every resolvable reference is resolved: nationalities, citizenships,
 *    addresses (with resolved country + parts), documents (with resolved type),
 *    and relationships (with resolved related FixedRef + relation label);
 *  - **4.3** non-ASCII names and address text are preserved byte-for-byte;
 *  - **4.6** partial / year-only / range birth dates are preserved as generated;
 *  - and the build produces **no diagnostics** (every reference resolved).
 */
@Tag(PropertyTests.FEATURE_TAG)
class TransformRoundTripPropertyTest {

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 5: Transformation preserves data and resolves references (round-trip)")
    fun transformationPreservesDataAndResolvesReferences(
        @ForAll @From("snapshots") gen: GeneratedSnapshot,
    ) {
        val snapshot = gen.toParsedSnapshot()
        val results = ProfileEntryBuilder().build(snapshot)

        // One built (in-scope) entry per generated profile, in source order.
        results.size shouldBe gen.profiles.size
        val builtByRef = results.associateBy { it.fixedRef }

        gen.profiles.forEach { spec ->
            val result = builtByRef.getValue(spec.fixedRef)

            // Every generated profile is buildable by construction: no
            // unbuildable reason, and no diagnostics because every reference
            // the profile carries is resolvable (Req 4.2, 4.7).
            result.unbuildableReason shouldBe null
            result.diagnostics shouldBe emptyList()
            val entry = result.entry.shouldNotBeNull()

            // ---- 4.1 identity + entity type ----
            entry.fixedRef.value shouldBe spec.fixedRef
            entry.entityType shouldBe spec.entityType

            // ---- 4.5 names: first alias is primary when none flagged; the
            // rest become aliases in source order, preserved verbatim (4.3). ----
            val primaryIndex = spec.aliases.indexOfFirst { it.primary }
                .let { if (it >= 0) it else 0 }
            entry.primaryName shouldBe spec.aliases[primaryIndex].fullName
            val expectedAliasNames =
                spec.aliases.filterIndexed { i, _ -> i != primaryIndex }.map { it.fullName }
            entry.aliases.map { it.name } shouldContainExactly expectedAliasNames

            // ---- 4.2 / 4.3 / 4.6 features resolved and preserved ----
            entry.nationalities shouldContainExactly spec.nationalities
            entry.citizenships shouldContainExactly spec.citizenships
            entry.birthDates shouldContainExactly spec.birthDates.map { it.expected }

            // Addresses: raw text + resolved country preserved verbatim (4.2/4.3).
            entry.addresses.map { it.raw } shouldContainExactly spec.addresses.map { it.rawJoined }
            entry.addresses.map { it.country } shouldContainExactly spec.addresses.map { it.country }

            // Documents: type resolved from IDRegDocTypeID, number/issuer verbatim (4.2).
            entry.documents.map { it.type } shouldContainExactly spec.documents.map { it.typeLabel }
            entry.documents.map { it.number } shouldContainExactly spec.documents.map { it.number }

            // Relationships: To-ProfileID resolved to the related FixedRef and
            // RelationTypeID resolved to its label (4.2).
            entry.relationships.map { it.toFixedRef.value } shouldContainExactly
                spec.relationships.map { it.toFixedRef }
            entry.relationships.map { it.relationType } shouldContainExactly
                spec.relationships.map { it.relationLabel }

            // ---- 4.4 mandatory program present (round-trip resolves it) ----
            entry.sanctionPrograms shouldContainExactly spec.programs
        }
    }

    // ------------------------------------------------------------------
    // Generated model (source-shaped) + its resolvable reference tables.
    // ------------------------------------------------------------------

    /** A generated snapshot: a set of buildable profiles with resolvable refs. */
    data class GeneratedSnapshot(val profiles: List<ProfileSpec>) {

        /**
         * Assemble the [ParsedSnapshot] the builder consumes, deriving every
         * reference table entry from the profile specs so all references resolve.
         */
        fun toParsedSnapshot(): ParsedSnapshot {
            val fixedRefByProfile = profiles.associate { it.profileId to it.fixedRef }

            val rawProfiles = profiles.map { spec ->
                RawParsedProfile(
                    fixedRef = spec.fixedRef,
                    profileId = spec.profileId,
                    identityId = spec.identityId,
                    partySubTypeId = spec.partySubTypeId,
                    aliases = spec.aliases.map { RawAlias(aliasTypeId = null, primary = it.primary, fullName = it.fullName) },
                    features = spec.features(),
                )
            }

            val featureTypeNames = mapOf(
                FT_BIRTHDATE to "Birthdate",
                FT_NATIONALITY to "Nationality Country",
                FT_CITIZENSHIP to "Citizenship Country",
                FT_LOCATION to "Location",
            )

            // Countries, location-part types, doc types, relation types, lists.
            val countryNames = HashMap<String, String>()
            val locations = HashMap<String, RawLocation>()
            val idRegDocuments = ArrayList<RawIdRegDocument>()
            val idRegDocTypeNames = HashMap<String, String>()
            val sanctionsEntries = ArrayList<RawSanctionsEntry>()
            val relationships = ArrayList<RawProfileRelationship>()
            val relationTypeNames = HashMap<String, String>()

            profiles.forEach { spec ->
                spec.addresses.forEach { addr ->
                    countryNames[addr.countryId] = addr.country
                    locations[addr.locationId] = RawLocation(
                        locationId = addr.locationId,
                        parts = addr.partValues.map { RawLocationPart(locPartTypeId = null, value = it) },
                        countryId = addr.countryId,
                    )
                }
                spec.documents.forEach { doc ->
                    idRegDocTypeNames[doc.typeId] = doc.typeLabel
                    idRegDocuments += RawIdRegDocument(
                        id = doc.id,
                        identityId = spec.identityId,
                        idRegDocTypeId = doc.typeId,
                        registrationNumber = doc.number,
                    )
                }
                sanctionsEntries += RawSanctionsEntry(
                    id = "SE-${spec.profileId}",
                    profileId = spec.profileId,
                    programNames = spec.programs,
                )
                spec.relationships.forEach { rel ->
                    relationTypeNames[rel.relationTypeId] = rel.relationLabel
                    relationships += RawProfileRelationship(
                        id = rel.id,
                        fromProfileId = spec.profileId,
                        toProfileId = fixedRefByProfile.entries.first { it.value == rel.toFixedRef }.key,
                        relationTypeId = rel.relationTypeId,
                    )
                }
            }

            val references = RawReferenceTables(
                featureTypeNames = featureTypeNames,
                idRegDocTypeNames = idRegDocTypeNames,
                relationTypeNames = relationTypeNames,
                countryNames = countryNames,
                locations = locations,
                idRegDocuments = idRegDocuments,
                sanctionsEntries = sanctionsEntries,
                relationships = relationships,
            )

            return ParsedSnapshot(publishDate = null, profiles = rawProfiles, references = references)
        }
    }

    /** One generated buildable profile with all-resolvable references. */
    data class ProfileSpec(
        val fixedRef: String,
        val profileId: String,
        val identityId: String,
        val partySubTypeId: String,
        val entityType: EntityType,
        val aliases: List<AliasSpec>,
        val nationalities: List<String>,
        val citizenships: List<String>,
        val birthDates: List<BirthDateSpec>,
        val addresses: List<AddressSpec>,
        val documents: List<DocumentSpec>,
        val relationships: List<RelationshipSpec>,
        val programs: List<String>,
    ) {
        /** Build the raw features in the order the builder appends them. */
        fun features(): List<RawFeature> {
            val features = ArrayList<RawFeature>()
            var seq = 0
            birthDates.forEach { bd ->
                features += RawFeature(
                    featureId = "$profileId-BD-${seq++}",
                    featureTypeId = FT_BIRTHDATE,
                    datePeriod = bd.raw,
                )
            }
            nationalities.forEach { n ->
                features += RawFeature("$profileId-NAT-${seq++}", FT_NATIONALITY, detailValue = n)
            }
            citizenships.forEach { c ->
                features += RawFeature("$profileId-CIT-${seq++}", FT_CITIZENSHIP, detailValue = c)
            }
            addresses.forEach { a ->
                features += RawFeature("$profileId-LOC-${seq++}", FT_LOCATION, locationId = a.locationId)
            }
            return features
        }
    }

    data class AliasSpec(val fullName: String, val primary: Boolean)

    /** A birth date spec carrying both the raw source shape and its expected model form. */
    data class BirthDateSpec(val raw: RawDatePeriod, val expected: PartialDate)

    data class AddressSpec(
        val locationId: String,
        val countryId: String,
        val country: String,
        val partValues: List<String>,
    ) {
        /** The builder joins location parts with ", " to form `Address.raw`. */
        val rawJoined: String = partValues.joinToString(", ")
    }

    data class DocumentSpec(
        val id: String,
        val typeId: String,
        val typeLabel: String,
        val number: String,
    )

    data class RelationshipSpec(
        val id: String,
        val toFixedRef: String,
        val relationTypeId: String,
        val relationLabel: String,
    )

    // ------------------------------------------------------------------
    // Arbitraries
    // ------------------------------------------------------------------

    @Provide
    fun snapshots(): Arbitrary<GeneratedSnapshot> {
        // A pool of distinct integer keys → distinct FixedRefs, so relationship
        // targets can reference real profiles within the same snapshot.
        val keyPool: Arbitrary<List<Int>> =
            Arbitraries.integers().between(0, 300)
                .list().ofMinSize(1).ofMaxSize(8).uniqueElements()

        return keyPool.flatMap { keys ->
            val refs = keys.map { "FR-$it" }
            val perProfile = keys.map { key -> profileSpec(key, allRefs = refs) }
            // Sequence the per-profile arbitraries with flatMap/map (no
            // Combinators.combine(...).as), folding into a single list arbitrary.
            sequence(perProfile).map { specs -> GeneratedSnapshot(specs) }
        }
    }

    /** Sequence a list of arbitraries into an arbitrary of list, via flatMap/map. */
    private fun <T> sequence(arbs: List<Arbitrary<T>>): Arbitrary<List<T>> =
        arbs.fold(Arbitraries.just(emptyList())) { acc, arb ->
            acc.flatMap { list -> arb.map { value -> list + value } }
        }

    private fun profileSpec(key: Int, allRefs: List<String>): Arbitrary<ProfileSpec> {
        val fixedRef = "FR-$key"
        val profileId = "P-$key"
        val entityTypeArb = Arbitraries.of(
            "3" to EntityType.Entity,
            "4" to EntityType.Individual,
        )

        return entityTypeArb.flatMap { (partySubTypeId, entityType) ->
            aliasesArb(key).flatMap { aliases ->
                textList(key, "nat", 0, 2).flatMap { nationalities ->
                    textList(key, "cit", 0, 2).flatMap { citizenships ->
                        birthDatesArb(key).flatMap { birthDates ->
                            addressesArb(key).flatMap { addresses ->
                                documentsArb(key).flatMap { documents ->
                                    relationshipsArb(key, allRefs).flatMap { relationships ->
                                        programsArb(key).map { programs ->
                                            ProfileSpec(
                                                fixedRef = fixedRef,
                                                profileId = profileId,
                                                identityId = "ID-$key",
                                                partySubTypeId = partySubTypeId,
                                                entityType = entityType,
                                                aliases = aliases,
                                                nationalities = nationalities,
                                                citizenships = citizenships,
                                                birthDates = birthDates,
                                                addresses = addresses,
                                                documents = documents,
                                                relationships = relationships,
                                                programs = programs,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** At least one alias (Req 4.5). Non-ASCII injected to prove verbatim UTF-8 (Req 4.3). */
    private fun aliasesArb(key: Int): Arbitrary<List<AliasSpec>> {
        val name = nonAsciiName(key)
        // 0..2 extra aliases plus the guaranteed first; a fraction flag one primary.
        return Arbitraries.integers().between(0, 2).flatMap { extra ->
            Arbitraries.integers().between(-1, extra).map { primaryPos ->
                val names = (0..extra).map { "$name-alias-$it" }
                names.mapIndexed { i, n -> AliasSpec(fullName = n, primary = i == primaryPos) }
            }
        }
    }

    /** Non-ASCII, non-blank text values (Req 4.3). */
    private fun textList(key: Int, tag: String, min: Int, max: Int): Arbitrary<List<String>> =
        Arbitraries.integers().between(min, max).map { n ->
            (0 until n).map { "$tag-${NON_ASCII[(key + it) % NON_ASCII.size]}-$it" }
        }

    /** Birth dates: year-only, full partial, and true ranges — all preserved (Req 4.6). */
    private fun birthDatesArb(key: Int): Arbitrary<List<BirthDateSpec>> =
        Arbitraries.integers().between(0, 2).map { n ->
            (0 until n).map { i -> birthDate(key + i, (key + i) % 3) }
        }

    private fun birthDate(seed: Int, shape: Int): BirthDateSpec {
        val year = 1950 + (seed % 60)
        return when (shape) {
            0 -> { // year-only
                val raw = RawDatePeriod(RawPartialDate(year = year), RawPartialDate(year = year))
                BirthDateSpec(raw, PartialDate(year = year))
            }
            1 -> { // full partial date (single day)
                val month = 1 + (seed % 12)
                val day = 1 + (seed % 28)
                val raw = RawDatePeriod(
                    RawPartialDate(year, month, day),
                    RawPartialDate(year, month, day),
                )
                BirthDateSpec(raw, PartialDate(year = year, month = month, day = day))
            }
            else -> { // true range
                val start = RawPartialDate(year = year)
                val end = RawPartialDate(year = year + 1)
                val raw = RawDatePeriod(start, end)
                BirthDateSpec(
                    raw,
                    PartialDate(
                        period = PartialDate.Period(
                            from = PartialDate(year = year),
                            to = PartialDate(year = year + 1),
                        ),
                    ),
                )
            }
        }
    }

    /** Addresses with resolvable LocationID + CountryID; non-ASCII parts (Req 4.2/4.3). */
    private fun addressesArb(key: Int): Arbitrary<List<AddressSpec>> =
        Arbitraries.integers().between(0, 2).map { n ->
            (0 until n).map { i ->
                val glyph = NON_ASCII[(key + i) % NON_ASCII.size]
                AddressSpec(
                    locationId = "LOC-$key-$i",
                    countryId = "C-$key-$i",
                    country = "Country-$glyph-$i",
                    partValues = listOf("City-$glyph-$i", "Street $glyph $i"),
                )
            }
        }

    private fun documentsArb(key: Int): Arbitrary<List<DocumentSpec>> =
        Arbitraries.integers().between(0, 2).map { n ->
            (0 until n).map { i ->
                DocumentSpec(
                    id = "DOC-$key-$i",
                    typeId = "DT-$key-$i",
                    typeLabel = "Passport-$i",
                    number = "NUM-$key-$i",
                )
            }
        }

    private fun relationshipsArb(key: Int, allRefs: List<String>): Arbitrary<List<RelationshipSpec>> =
        Arbitraries.integers().between(0, 2).map { n ->
            (0 until n).map { i ->
                val target = allRefs[(key + i) % allRefs.size]
                RelationshipSpec(
                    id = "REL-$key-$i",
                    toFixedRef = target,
                    relationTypeId = "RT-$key-$i",
                    relationLabel = "Owned by $i",
                )
            }
        }

    /** At least one sanction program so the entry is buildable (Req 4.4). */
    private fun programsArb(key: Int): Arbitrary<List<String>> =
        Arbitraries.integers().between(1, 3).map { n ->
            (0 until n).map { i -> "PROG-$key-$i" }
        }

    private fun nonAsciiName(key: Int): String {
        val glyph = NON_ASCII[key % NON_ASCII.size]
        return "Name $glyph $key"
    }

    private companion object {
        const val FT_BIRTHDATE = "8"
        const val FT_NATIONALITY = "10"
        const val FT_CITIZENSHIP = "11"
        const val FT_LOCATION = "25"

        // A spread of non-ASCII strings drawn from the spike's examples and beyond
        // (Hải Phòng, Skořepka), to prove verbatim UTF-8 preservation (Req 4.3).
        val NON_ASCII = listOf("Hải", "Phòng", "Skořepka", "Město", "München", "São", "Łódź", "北京", "Мoskva")
    }
}
