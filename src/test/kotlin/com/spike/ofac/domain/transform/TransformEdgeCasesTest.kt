package com.spike.ofac.domain.transform

import com.spike.ofac.domain.model.EntityType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * Unit tests for transform edge cases (task 5.6, Req 4.5, 4.8).
 *
 * These pin down two behaviors of the `transform` stage that the property tests
 * (tasks 5.3–5.5) exercise more broadly but do not isolate as focused examples:
 *
 *  - **Req 4.5 — a record with no *extra* aliases still builds.** In the OFAC
 *    Advanced XML the primary name is itself represented as an alias (a
 *    `DocumentedName`). [ProfileEntryBuilder] therefore treats the primary (or,
 *    absent an explicit `Primary="true"`, the first) alias as
 *    `InternalModelEntry.primaryName` and the *rest* as `aliases[]`. So a profile
 *    carrying exactly one (primary) name and no additional a.k.a./f.k.a. names is
 *    the "zero aliases" case at the model level: it must build an entry whose
 *    `primaryName` is that name and whose `aliases` list is **empty** (Req 4.5).
 *
 *    A profile with *truly* zero `RawAlias` entries has no name to promote to
 *    `primaryName`; the builder reports it as unbuildable, which the stage turns
 *    into a hard failure (that is the Req 4.8 case, covered below).
 *
 *  - **Req 4.8 — an unparseable in-scope record fails the whole stage.** If any
 *    in-scope profile cannot be turned into an entry (e.g. a blank `FixedRef`,
 *    zero names, or no resolvable sanction program), [Transform] returns
 *    [TransformResult.Failed] with cause `UNPARSEABLE_RECORD` and produces **no**
 *    entries, so no partial version can ever be activated.
 *
 * The tests drive [Transform.fromParsed] with hand-built [ParsedSnapshot]
 * fixtures so the exact profile shape under test is unambiguous, without going
 * through the XML parser.
 */
class TransformEdgeCasesTest {

    private val transform = Transform()

    // --- Fixture helpers ------------------------------------------------------

    /** An in-scope Individual (`PartySubTypeID == "4"`). */
    private fun individual(
        fixedRef: String,
        profileId: String,
        aliases: List<RawAlias>,
    ): RawParsedProfile = RawParsedProfile(
        fixedRef = fixedRef,
        profileId = profileId,
        identityId = null,
        partySubTypeId = "4", // Individual (in scope)
        aliases = aliases,
        features = emptyList(),
    )

    /** A single primary name, mirroring one `DocumentedName` in the XML. */
    private fun primaryName(name: String): RawAlias =
        RawAlias(aliasTypeId = null, primary = true, fullName = name)

    /**
     * A [ParsedSnapshot] whose profiles each get one `SanctionsEntry` (so they
     * carry a resolvable sanction program, satisfying Req 4.4) unless
     * [withPrograms] excludes them by `profileId`.
     */
    private fun snapshotWith(
        profiles: List<RawParsedProfile>,
        programsByProfileId: Map<String, List<String>> =
            profiles.associate { it.profileId to listOf("SDGT") },
    ): ParsedSnapshot {
        val entries = programsByProfileId.entries.mapIndexed { i, (profileId, programs) ->
            RawSanctionsEntry(
                id = "entry-$i",
                profileId = profileId,
                listId = null,
                programNames = programs,
            )
        }
        return ParsedSnapshot(
            publishDate = null,
            profiles = profiles,
            references = RawReferenceTables(sanctionsEntries = entries),
        )
    }

    // --- Req 4.5 --------------------------------------------------------------

    @Test
    fun `a profile with a single primary name and no extra aliases builds an entry using the primary name (Req 4_5)`() {
        val snapshot = snapshotWith(
            listOf(
                individual(
                    fixedRef = "12345",
                    profileId = "p1",
                    aliases = listOf(primaryName("Jane Doe")),
                ),
            ),
        )

        val result = transform.fromParsed(snapshot)

        val ok = result.shouldBeInstanceOf<TransformResult.Ok>()
        ok.entries.shouldHaveSize(1)
        val entry = ok.entries.single()
        entry.entityType shouldBe EntityType.Individual
        // The single (primary) name becomes primaryName ...
        entry.primaryName shouldBe "Jane Doe"
        // ... and there are no *additional* aliases (the model-level "zero aliases" case).
        entry.aliases.shouldBeEmpty()
    }

    @Test
    fun `additional names beyond the primary become aliases while the primary sets primaryName (Req 4_5)`() {
        val snapshot = snapshotWith(
            listOf(
                individual(
                    fixedRef = "12345",
                    profileId = "p1",
                    aliases = listOf(
                        primaryName("Jane Doe"),
                        RawAlias(aliasTypeId = null, primary = false, fullName = "Janie"),
                    ),
                ),
            ),
        )

        val ok = transform.fromParsed(snapshot).shouldBeInstanceOf<TransformResult.Ok>()
        val entry = ok.entries.single()

        entry.primaryName shouldBe "Jane Doe"
        entry.aliases.map { it.name } shouldBe listOf("Janie")
    }

    // --- Req 4.8 --------------------------------------------------------------

    @Test
    fun `a profile with truly zero names fails the stage with no partial entries (Req 4_8)`() {
        val snapshot = snapshotWith(
            listOf(
                individual(
                    fixedRef = "12345",
                    profileId = "p1",
                    aliases = emptyList(), // no name at all -> unbuildable
                ),
            ),
        )

        val failed = transform.fromParsed(snapshot).shouldBeInstanceOf<TransformResult.Failed>()

        failed.cause shouldBe TransformResult.Failed.Cause.UNPARSEABLE_RECORD
        failed.fixedRef shouldBe "12345"
    }

    @Test
    fun `a profile with a blank FixedRef fails the stage (Req 4_8)`() {
        val snapshot = snapshotWith(
            listOf(
                individual(
                    fixedRef = "   ",
                    profileId = "p1",
                    aliases = listOf(primaryName("Jane Doe")),
                ),
            ),
        )

        val failed = transform.fromParsed(snapshot).shouldBeInstanceOf<TransformResult.Failed>()

        failed.cause shouldBe TransformResult.Failed.Cause.UNPARSEABLE_RECORD
    }

    @Test
    fun `a profile with no resolvable sanction program fails the stage (Req 4_8)`() {
        val snapshot = snapshotWith(
            profiles = listOf(
                individual(
                    fixedRef = "12345",
                    profileId = "p1",
                    aliases = listOf(primaryName("Jane Doe")),
                ),
            ),
            // No SanctionsEntry for p1 -> no resolvable program (Req 4.4 -> unbuildable).
            programsByProfileId = emptyMap(),
        )

        val failed = transform.fromParsed(snapshot).shouldBeInstanceOf<TransformResult.Failed>()

        failed.cause shouldBe TransformResult.Failed.Cause.UNPARSEABLE_RECORD
        failed.fixedRef shouldBe "12345"
    }

    @Test
    fun `one unparseable in-scope record fails the whole stage - no valid records survive (Req 4_8)`() {
        // A perfectly good record alongside one unbuildable record: the stage must
        // still fail wholesale so no partial version (with only the good record)
        // is produced.
        val good = individual(
            fixedRef = "good-1",
            profileId = "p-good",
            aliases = listOf(primaryName("Good Person")),
        )
        val bad = individual(
            fixedRef = "bad-1",
            profileId = "p-bad",
            aliases = emptyList(), // unbuildable
        )
        val snapshot = snapshotWith(
            profiles = listOf(good, bad),
            programsByProfileId = mapOf("p-good" to listOf("SDGT"), "p-bad" to listOf("SDGT")),
        )

        val result = transform.fromParsed(snapshot)

        val failed = result.shouldBeInstanceOf<TransformResult.Failed>()
        failed.cause shouldBe TransformResult.Failed.Cause.UNPARSEABLE_RECORD
        // The failure names the offending record; the good record is not partially emitted.
        failed.fixedRef shouldBe "bad-1"
        failed.detail.shouldContain("bad-1")
    }
}
