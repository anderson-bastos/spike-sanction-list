package com.spike.ofac.pipeline.stages.transform

import com.spike.ofac.pipeline.models.Diagnostic
import com.spike.ofac.pipeline.models.ScopeConfig
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.Size
import org.junit.jupiter.api.Tag

/**
 * Property 7: Unresolvable references do not abort transformation (task 5.5).
 *
 * Validates: Requirements 4.7.
 *
 * Generates [ParsedSnapshot]s whose in-scope profiles are **otherwise fully
 * buildable** (≥1 alias so a primary name exists, Req 4.5; ≥1 resolvable
 * sanction program, Req 4.4) but carry an *arbitrary number* of deliberately
 * **unresolvable** ID references of the three kinds [ProfileEntryBuilder] emits
 * a soft [Diagnostic.Kind.UNRESOLVED_REF] for:
 *
 *  1. an `IDRegDocument` linked to the profile's identity whose `IDRegDocTypeID`
 *     is not present in the reference tables (emits one diagnostic, still keeps
 *     the document as type "Unknown");
 *  2. a `Feature` classified as a "Location" whose `LocationID` is not present
 *     in the locations table (emits one diagnostic, drops the address);
 *  3. a `ProfileRelationship` on the profile whose `To-ProfileID` points at a
 *     profile that is not present in the snapshot (emits one diagnostic, drops
 *     the relationship).
 *
 * The generator is carefully aligned with the builder so that **every non-injected
 * reference resolves cleanly** — no incidental diagnostics are produced. This lets
 * the test assert an *exact* equality between the number of injected unresolvable
 * references and the number of emitted `UNRESOLVED_REF` diagnostics.
 *
 * The test then asserts the three invariants of Req 4.7:
 *  - transformation **completes** ([TransformResult.Ok] — never [TransformResult.Failed]);
 *  - there is **exactly one** `UNRESOLVED_REF` diagnostic per injected unresolvable
 *    reference; and
 *  - an **entry exists for every resolvable (in-scope) record** — i.e. every
 *    generated profile still produces an entry despite its unresolved references.
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 7: Unresolvable references do not abort transformation`.
 */
@Tag(PropertyTests.FEATURE_TAG)
class UnresolvedReferencePropertyTest {

    /**
     * A single generated in-scope profile plus the reference-table fragments it
     * needs, carrying the exact count of unresolvable references injected into it
     * so the test can sum expected diagnostics independently of the builder.
     *
     * jqwik inspects these while generating `@ForAll List<ProfilePlan>`, so they
     * must not be private-in-class.
     */
    data class ProfilePlan(
        val profile: RawParsedProfile,
        val documents: List<RawIdRegDocument>,
        val entries: List<RawSanctionsEntry>,
        val relationships: List<RawProfileRelationship>,
        val locations: Map<String, RawLocation>,
        val unresolvableRefCount: Int,
    )

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 7: Unresolvable references do not abort transformation")
    fun unresolvableReferencesDoNotAbortTransformation(
        @ForAll("snapshots") @Size(min = 1, max = 25) plans: List<ProfilePlan>,
    ) {
        // FixedRef/profileId/identityId/locationId are made globally unique by the
        // generator (indexed), so simple concatenation of the per-profile fragments
        // yields a well-formed snapshot with no accidental cross-links.
        val references = RawReferenceTables(
            // Every resolvable label the builder consults for a clean profile. The
            // injected unresolvable refs deliberately use ids ABSENT from these maps.
            featureTypeNames = mapOf(FEATURE_TYPE_LOCATION to "Location"),
            idRegDocTypeNames = mapOf(DOC_TYPE_PASSPORT to "Passport"),
            listNames = mapOf(LIST_SDN to "SDN List"),
            locations = plans.flatMap { it.locations.entries }.associate { it.key to it.value },
            idRegDocuments = plans.flatMap { it.documents },
            sanctionsEntries = plans.flatMap { it.entries },
            relationships = plans.flatMap { it.relationships },
        )
        val snapshot = ParsedSnapshot(
            publishDate = null,
            profiles = plans.map { it.profile },
            references = references,
        )

        val expectedUnresolved = plans.sumOf { it.unresolvableRefCount }

        // --- ProfileEntryBuilder view (Req 4.7): one result per in-scope profile,
        // each built (never unbuildable), diagnostics exactly the injected refs.
        val results = ProfileEntryBuilder().build(snapshot)

        // An entry exists for every resolvable in-scope record: no result is
        // unbuildable, and there is exactly one result per generated profile.
        results.size shouldBe plans.size
        results.forEach { result ->
            result.unbuildableReason shouldBe null
            (result.entry != null) shouldBe true
        }
        val builtRefs = results.map { it.fixedRef }
        builtRefs.shouldContainExactly(plans.map { it.profile.fixedRef })

        // Every diagnostic is an UNRESOLVED_REF, and the count is exact.
        val builderDiagnostics = results.flatMap { it.diagnostics }
        builderDiagnostics.forEach { it.kind shouldBe Diagnostic.Kind.UNRESOLVED_REF }
        builderDiagnostics.size shouldBe expectedUnresolved

        // --- Transform stage view (Req 4.7): completes with Ok (never Failed),
        // carries an entry for every resolvable record, and the same exact number
        // of UNRESOLVED_REF diagnostics.
        val transformResult = Transform().fromParsed(snapshot, ScopeConfig.SDN_ONLY)

        val ok = when (transformResult) {
            is TransformResult.Ok -> transformResult
            is TransformResult.Failed ->
                throw AssertionError(
                    "Unresolvable references must not abort the transform (Req 4.7); " +
                        "got FAILED(${transformResult.cause}): ${transformResult.detail}",
                )
        }

        // FixedRefs are unique per profile, so the deduplicated entry set is exactly
        // one entry per generated (resolvable) profile.
        ok.entries.size shouldBe plans.size
        ok.entries.map { it.fixedRef.value }.shouldContainExactly(plans.map { it.profile.fixedRef })

        val unresolvedDiagnostics = ok.diagnostics.filter { it.kind == Diagnostic.Kind.UNRESOLVED_REF }
        unresolvedDiagnostics.size shouldBe expectedUnresolved
        // Sanity: an arbitrary-but-non-negative number of refs were exercised.
        expectedUnresolved shouldBeGreaterThanOrEqual 0
    }

    // --- Generators -----------------------------------------------------------

    /**
     * A list of profile plans with **globally unique** ids. jqwik generates the
     * per-profile shape (alias, program, and the injected unresolvable-ref counts)
     * independently; this provider then re-stamps each plan with an index-derived
     * unique id space so concatenating the fragments can never accidentally make an
     * "unresolvable" reference resolve against another profile's records.
     */
    @Provide
    fun snapshots(): Arbitrary<List<ProfilePlan>> =
        profileShape().list().ofMinSize(1).ofMaxSize(25).map { shapes ->
            shapes.mapIndexed { index, shape -> materialize(index, shape) }
        }

    /**
     * The raw, index-independent shape of one profile: the choices jqwik makes
     * before ids are assigned. Not private so jqwik can inspect it.
     */
    data class ProfileShape(
        val isIndividual: Boolean,
        val primaryName: String,
        val extraAliasNames: List<String>,
        val programName: String,
        val badDocCount: Int,
        val badLocationCount: Int,
        val badRelationshipCount: Int,
    )

    private fun profileShape(): Arbitrary<ProfileShape> =
        Combinators.combine(
            Arbitraries.of(true, false),
            names(),
            names().list().ofMinSize(0).ofMaxSize(3),
            programNames(),
            Arbitraries.integers().between(0, 4),
            Arbitraries.integers().between(0, 4),
            Arbitraries.integers().between(0, 4),
        ).`as` { isIndividual, primary, aliases, program, badDocs, badLocs, badRels ->
            ProfileShape(
                isIndividual = isIndividual,
                primaryName = primary,
                extraAliasNames = aliases,
                programName = program,
                badDocCount = badDocs,
                badLocationCount = badLocs,
                badRelationshipCount = badRels,
            )
        }

    /**
     * Turn an index-free [ProfileShape] into a fully-wired [ProfilePlan] with a
     * unique id space derived from [index]. Every "good" reference resolves; every
     * injected "bad" reference is guaranteed unresolvable (its type/location/target
     * id is absent from the reference tables assembled by the test).
     */
    private fun materialize(index: Int, shape: ProfileShape): ProfilePlan {
        val fixedRef = "FR-$index"
        val profileId = "P-$index"
        val identityId = "ID-$index"

        // >=1 alias => a primary name exists (Req 4.5). The first is primary.
        val aliases = buildList {
            add(RawAlias(aliasTypeId = null, primary = true, fullName = shape.primaryName))
            shape.extraAliasNames.forEachIndexed { i, name ->
                add(RawAlias(aliasTypeId = null, primary = false, fullName = "$name-$i"))
            }
        }

        // Unresolvable Location features: featureTypeId resolves to "Location" but
        // the LocationID is absent from the locations table (one diagnostic each).
        val locationFeatures = (0 until shape.badLocationCount).map { i ->
            RawFeature(
                featureId = "F-$index-$i",
                featureTypeId = FEATURE_TYPE_LOCATION,
                locationId = "MISSING-LOC-$index-$i",
            )
        }

        // >=1 resolvable sanction program (Req 4.4) via a SanctionsEntry linked by
        // ProfileID, carrying a program name. This never emits a diagnostic.
        val sanctionsEntry = RawSanctionsEntry(
            id = "SE-$index",
            profileId = profileId,
            listId = LIST_SDN,
            programNames = listOf(shape.programName),
        )

        // Unresolvable IDRegDocuments: linked to this identity, but the doc type id
        // is absent from idRegDocTypeNames (one diagnostic each; doc still kept).
        val documents = (0 until shape.badDocCount).map { i ->
            RawIdRegDocument(
                id = "DOC-$index-$i",
                identityId = identityId,
                idRegDocTypeId = "MISSING-DOCTYPE-$index-$i",
                registrationNumber = "REG-$index-$i",
            )
        }

        // Unresolvable ProfileRelationships: linked from this profile, To-ProfileID
        // points at a profile id that does not exist anywhere in the snapshot (one
        // diagnostic each; relationship dropped).
        val relationships = (0 until shape.badRelationshipCount).map { i ->
            RawProfileRelationship(
                id = "REL-$index-$i",
                fromProfileId = profileId,
                toProfileId = "MISSING-PROFILE-$index-$i",
                relationTypeId = null,
            )
        }

        val profile = RawParsedProfile(
            fixedRef = fixedRef,
            profileId = profileId,
            identityId = identityId,
            partySubTypeId = if (shape.isIndividual) "4" else "3",
            aliases = aliases,
            features = locationFeatures,
        )

        return ProfilePlan(
            profile = profile,
            documents = documents,
            entries = listOf(sanctionsEntry),
            relationships = relationships,
            locations = emptyMap(),
            unresolvableRefCount = shape.badDocCount + shape.badLocationCount + shape.badRelationshipCount,
        )
    }

    /** Non-empty display/alias/program strings, including non-ASCII, preserved verbatim. */
    private fun names(): Arbitrary<String> =
        Arbitraries.strings()
            .withChars('a', 'z')
            .withChars('A', 'Z')
            .withChars('0', '9')
            .withChars(' ', 'é', 'ß', 'Ω', '张')
            .ofMinLength(1)
            .ofMaxLength(20)
            .filter { it.isNotBlank() }

    private fun programNames(): Arbitrary<String> =
        Arbitraries.of("SDGT", "NS-PLC", "IRAN", "CUBA", "UKRAINE-EO13662")

    private companion object {
        const val FEATURE_TYPE_LOCATION = "25"
        const val DOC_TYPE_PASSPORT = "1571"
        const val LIST_SDN = "91"
    }
}
