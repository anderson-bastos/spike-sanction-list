package com.spike.ofac.domain.transform

import com.spike.ofac.domain.model.ScopeConfig
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.From
import net.jqwik.api.Label
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.junit.jupiter.api.Tag

/**
 * Property 6: Every persisted entry has at least one sanction program.
 *
 * *For any* transformed snapshot, every persisted in-scope `Internal_Model`
 * entry has at least one sanction program, while the other multi-valued
 * attributes (aliases, addresses, documents, nationalities, citizenships, birth
 * dates, relationships, remarks) are permitted to be empty (design.md Property
 * 6, Req 4.4).
 *
 * The guarantee is enforced in two complementary places, both exercised here:
 *  - [com.spike.ofac.domain.model.InternalModelEntry] fails construction in
 *    its `init` block unless `sanctionPrograms.isNotEmpty()` (Req 4.4); and
 *  - [ProfileEntryBuilder] never emits an entry with zero programs — a profile
 *    whose `SanctionsEntry` references resolve to no program name and no list-name
 *    fallback is reported *unbuildable* (`EntryResult.entry == null`) rather than
 *    yielding an empty-program entry.
 *
 * At the stage level ([Transform]) an unbuildable in-scope profile is a **hard
 * failure** (`FAILED(UNPARSEABLE_RECORD)`, task 5.2 / Req 4.8) so that no partial
 * version is produced — which is a stronger form of "never yields an
 * empty-program entry": the whole snapshot is rejected rather than persisting a
 * subset. This test exercises both faces of the invariant:
 *
 *  1. **Persisted-entry invariant** — when every in-scope profile has a
 *     resolvable program, the transform yields an `Ok`, and every persisted
 *     [com.spike.ofac.domain.model.InternalModelEntry] has
 *     `sanctionPrograms.size >= 1`, regardless of whether its programs came from
 *     explicit program names or the `ListID` fallback; and
 *  2. **No empty-program entry ever** — a snapshot containing a profile with no
 *     resolvable program is rejected as `FAILED` (never producing an entry with
 *     an empty program list), and, checked directly against
 *     [ProfileEntryBuilder.build], such a profile yields `entry == null`
 *     (unbuildable) while the resolvable ones still build with `>= 1` program.
 *
 * Other multi-valued attributes (aliases, addresses, documents, ...) are left
 * empty on the generated profiles to confirm they *may* be empty while the
 * program invariant still holds.
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 6: Every persisted entry has
 * at least one sanction program`.
 *
 * **Validates: Requirements 4.4**
 */
@Tag(PropertyTests.FEATURE_TAG)
class MandatorySanctionProgramPropertyTest {

    /**
     * How a generated profile's sanction program is (or is not) resolvable — this
     * is what drives whether the transform yields an entry.
     */
    enum class ProgramSource {
        /** One or more explicit program names on the profile's `SanctionsEntry`. */
        EXPLICIT_PROGRAMS,

        /** No program names, but a resolvable `ListID` → list-name fallback program. */
        LIST_NAME_FALLBACK,

        /** No program names and no resolvable list name → unbuildable (no entry). */
        NONE,
    }

    /**
     * A single generated in-scope profile spec: its identity, its in-scope type,
     * and how its sanction program resolves. Aliases, addresses, documents, etc.
     * are intentionally left empty to exercise the "other attributes may be empty"
     * half of the property.
     */
    data class ProfileSpec(
        val fixedRef: String,
        val profileId: String,
        val partySubTypeId: String, // "3" Entity or "4" Individual (both in scope)
        val primaryName: String,
        val programSource: ProgramSource,
        val programNames: List<String>,
        val listId: String?,
    ) {
        /** True when this profile is expected to yield a persisted entry. */
        val yieldsEntry: Boolean = programSource != ProgramSource.NONE
    }

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 6: Every persisted entry has at least one sanction program")
    fun everyPersistedEntryHasAtLeastOneSanctionProgram(
        @ForAll @From("snapshots") specs: List<ProfileSpec>,
    ) {
        val snapshot = toSnapshot(specs)
        val anyWithoutProgram = specs.any { !it.yieldsEntry }

        // ------------------------------------------------------------------
        // Direct builder check: a profile with NO resolvable program is
        // reported *unbuildable* (entry == null) — it NEVER yields an entry
        // with an empty program list — while every resolvable profile builds
        // with >= 1 program (Req 4.4).
        // ------------------------------------------------------------------
        val builderResults = ProfileEntryBuilder().build(snapshot)
        val builtByFixedRef = builderResults.associateBy { it.fixedRef }
        for (spec in specs) {
            val built = builtByFixedRef.getValue(spec.fixedRef)
            if (spec.yieldsEntry) {
                val entry = built.entry
                    ?: error("Profile ${spec.fixedRef} with a resolvable program should build")
                entry.sanctionPrograms.size shouldBeGreaterThanOrEqual 1
            } else {
                // No resolvable program -> unbuildable, never an empty-program entry.
                built.entry shouldBe null
            }
        }

        // ------------------------------------------------------------------
        // Stage-level behavior of Transform.
        // ------------------------------------------------------------------
        val result = Transform().fromParsed(snapshot, scope = ScopeConfig.SDN_ONLY)

        if (anyWithoutProgram) {
            // An in-scope profile with no resolvable program cannot be built, so
            // the whole stage fails and NO partial version (hence no empty-program
            // entry) is ever produced (Req 4.4 / 4.8).
            result.shouldBeInstanceOf<TransformResult.Failed>()
            return
        }

        // Every in-scope profile has a resolvable program: the transform succeeds.
        val ok = result.shouldBeInstanceOf<TransformResult.Ok>()

        // Facet 1 — the invariant: EVERY persisted entry has >= 1 sanction program
        // (Req 4.4). This is the core of Property 6.
        for (entry in ok.entries) {
            entry.sanctionPrograms.size shouldBeGreaterThanOrEqual 1
        }

        // Facet 2 — with no unbuildable profile, the persisted set is exactly the
        // generated in-scope profiles (one entry per distinct FixedRef).
        ok.entries.map { it.fixedRef.value } shouldContainExactlyInAnyOrder specs.map { it.fixedRef }

        // Facet 3 — other multi-valued attributes are permitted to be empty while
        // the program invariant still holds: the generated profiles carry no
        // aliases-beyond-primary/addresses/documents/etc., so every built entry
        // demonstrates empty multi-valued attributes coexisting with >= 1 program.
        for (entry in ok.entries) {
            entry.aliases shouldBe emptyList()
            entry.addresses shouldBe emptyList()
            entry.documents shouldBe emptyList()
            entry.nationalities shouldBe emptyList()
            entry.citizenships shouldBe emptyList()
            entry.birthDates shouldBe emptyList()
            entry.relationships shouldBe emptyList()
            entry.remarks shouldBe emptyList()
        }
    }

    /**
     * Assemble a [ParsedSnapshot] from the generated [ProfileSpec]s.
     *
     * Each spec becomes one in-scope [RawParsedProfile] with a single primary
     * alias (so a `primary_name` exists) and no features. Its sanction program is
     * expressed the way the [ProfileEntryBuilder] resolves programs: via a
     * [RawSanctionsEntry] linked by `profileId` carrying `programNames`, and/or a
     * `listId` resolved through the [RawReferenceTables.listNames] table.
     */
    private fun toSnapshot(specs: List<ProfileSpec>): ParsedSnapshot {
        val profiles = specs.map { spec ->
            RawParsedProfile(
                fixedRef = spec.fixedRef,
                profileId = spec.profileId,
                identityId = null,
                partySubTypeId = spec.partySubTypeId,
                aliases = listOf(
                    RawAlias(aliasTypeId = null, primary = true, fullName = spec.primaryName),
                ),
                features = emptyList(),
            )
        }

        val sanctionsEntries = specs.map { spec ->
            RawSanctionsEntry(
                id = "SE-${spec.profileId}",
                profileId = spec.profileId,
                listId = spec.listId,
                programNames = spec.programNames,
            )
        }

        // Only fallback-eligible list ids resolve to a name. NONE profiles carry an
        // unresolvable listId (or none), so they resolve to no program at all.
        val listNames = specs
            .filter { it.programSource == ProgramSource.LIST_NAME_FALLBACK && it.listId != null }
            .associate { it.listId!! to "List ${it.listId}" }

        return ParsedSnapshot(
            publishDate = null,
            profiles = profiles,
            references = RawReferenceTables(
                listNames = listNames,
                sanctionsEntries = sanctionsEntries,
            ),
        )
    }

    @Provide
    fun snapshots(): Arbitrary<List<ProfileSpec>> =
        profileSpec().list().ofMinSize(0).ofMaxSize(12)
            // Distinct FixedRef / ProfileID so within-list dedup does not collapse
            // profiles and the expected-entry set is unambiguous.
            .map { specs -> specs.mapIndexed { index, spec -> spec.withIndex(index) } }

    private fun ProfileSpec.withIndex(index: Int): ProfileSpec =
        copy(fixedRef = "FR-$index", profileId = "P-$index")

    private fun profileSpec(): Arbitrary<ProfileSpec> {
        val partySubTypeId = Arbitraries.of("3", "4") // Entity / Individual — both in scope
        val primaryName = Arbitraries.strings().ofMinLength(1).ofMaxLength(24)
        val programSource = Arbitraries.of(
            ProgramSource.EXPLICIT_PROGRAMS,
            ProgramSource.LIST_NAME_FALLBACK,
            ProgramSource.NONE,
        )
        return Combinators.combine(partySubTypeId, primaryName, programSource)
            .flatAs { subType, name, source ->
                when (source) {
                    ProgramSource.EXPLICIT_PROGRAMS ->
                        Arbitraries.strings().ofMinLength(1).ofMaxLength(12)
                            .list().ofMinSize(1).ofMaxSize(4)
                            .map { programs ->
                                ProfileSpec(
                                    fixedRef = "FR",
                                    profileId = "P",
                                    partySubTypeId = subType,
                                    primaryName = name,
                                    programSource = source,
                                    programNames = programs,
                                    listId = null,
                                )
                            }

                    ProgramSource.LIST_NAME_FALLBACK ->
                        Arbitraries.integers().between(1, 999).map { listKey ->
                            ProfileSpec(
                                fixedRef = "FR",
                                profileId = "P",
                                partySubTypeId = subType,
                                primaryName = name,
                                programSource = source,
                                programNames = emptyList(),
                                listId = "L-$listKey",
                            )
                        }

                    ProgramSource.NONE ->
                        // No programs and either no listId or an unresolvable one:
                        // the builder resolves no program and drops the profile.
                        Arbitraries.of<String?>("UNRESOLVED-LIST", null).map { unresolvedList ->
                            ProfileSpec(
                                fixedRef = "FR",
                                profileId = "P",
                                partySubTypeId = subType,
                                primaryName = name,
                                programSource = source,
                                programNames = emptyList(),
                                listId = unresolvedList,
                            )
                        }
                }
            }
    }
}
