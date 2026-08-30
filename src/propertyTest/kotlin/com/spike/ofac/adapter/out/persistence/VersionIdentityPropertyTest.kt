package com.spike.ofac.adapter.out.persistence

import com.spike.ofac.application.port.out.PointerKind

import com.spike.ofac.domain.model.EntityType
import com.spike.ofac.domain.model.FixedRef
import com.spike.ofac.domain.model.InternalModelEntry
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.testsupport.PropertyTests
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
 * Property 9: Version identity disambiguates same-day publications.
 *
 * `VersionId = (Publish_Date, Sha256Digest)` (Req 7.2). When two publications
 * share the same `Publish_Date` but differ in content, their SHA-256 digests
 * differ, so their [VersionId]s differ and each is persisted as a **separate**
 * version (Req 7.3). Every persisted record carries its version's identity so it
 * is associated with the version in which it was ingested (Req 7.4).
 *
 * The property generates two distinct content byte arrays that share the same
 * `publishDate`, computes each one's SHA-256 to build a [VersionId], and asserts:
 *  - the two [VersionId]s are unequal (equal `publishDate`, differing `digest`);
 *  - both persist into a single [InMemoryVersionStore] as two independent
 *    versions (each with its own records);
 *  - both can be activated in turn, so the store holds two distinct versions on
 *    the same publish date at once (the second becomes CURRENT, the first
 *    PREVIOUS) — same-day publications are never conflated.
 *
 * Tag: `Feature: ofac-sanctions-ingestion, Property 9: Version identity
 * disambiguates same-day publications`.
 *
 * **Validates: Requirements 7.2, 7.3, 7.4**
 */
@Tag(PropertyTests.FEATURE_TAG)
class VersionIdentityPropertyTest {

    /**
     * A same-day pair: two byte contents guaranteed distinct, sharing one
     * [publishDate]. The generator forces `contentA != contentB` so the SHA-256
     * digests — and therefore the [VersionId]s — always differ.
     */
    data class SameDayPair(
        val publishDate: LocalDate,
        val contentA: ByteArray,
        val contentB: ByteArray,
    )

    @Property(tries = PropertyTests.MIN_TRIES)
    @Label("Property 9: Version identity disambiguates same-day publications")
    fun versionIdentityDisambiguatesSameDayPublications(
        @ForAll @From("sameDayPairs") pair: SameDayPair,
    ) {
        // Build each version's identity from its content's SHA-256 over the SAME
        // publish date — the only thing that can differ is the digest (Req 7.2).
        val versionA = VersionId(pair.publishDate, sha256(pair.contentA))
        val versionB = VersionId(pair.publishDate, sha256(pair.contentB))

        // Facet 1 — same day, different content ⇒ different digest ⇒ different
        // VersionId (Req 7.2, 7.3). Publish dates match; identities do not.
        versionA.publishDate shouldBe versionB.publishDate
        versionA.digest shouldNotBe versionB.digest
        versionA shouldNotBe versionB

        // Facet 2 — both persist as separate versions, each keeping its own
        // records stamped with its own VersionId (Req 7.3, 7.4).
        val store = InMemoryVersionStore()
        val recordsA = recordsFor(versionA, count = 2)
        val recordsB = recordsFor(versionB, count = 3)
        store.putIsolated(versionA, recordsA)
        store.putIsolated(versionB, recordsB)

        store.recordsOf(versionA).shouldNotBeNull() shouldContainExactlyInAnyOrder recordsA
        store.recordsOf(versionB).shouldNotBeNull() shouldContainExactlyInAnyOrder recordsB
        store.metadataOf(versionA).shouldNotBeNull().versionId shouldBe versionA
        store.metadataOf(versionB).shouldNotBeNull().versionId shouldBe versionB
        // Every persisted record is associated with its own version (Req 7.4).
        recordsA.forEach { it.versionId shouldBe versionA }
        recordsB.forEach { it.versionId shouldBe versionB }

        // Facet 3 — both are independently activatable and coexist. Activating A
        // then B leaves both resolvable (B is CURRENT, A is PREVIOUS): two
        // same-day publications live as two distinct versions, never merged.
        store.atomicSetCurrent(SourceList.SDN, versionA) shouldBe true
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe versionA

        store.atomicSetCurrent(SourceList.SDN, versionB) shouldBe true
        store.getPointer(SourceList.SDN, PointerKind.CURRENT) shouldBe versionB
        store.getPointer(SourceList.SDN, PointerKind.PREVIOUS) shouldBe versionA
    }

    /**
     * Generates two byte contents sharing a publish date and guaranteed distinct.
     *
     * `contentB` is derived from `contentA` by appending a non-empty, non-matching
     * suffix, which guarantees the two byte arrays differ regardless of what the
     * base generator produced (including when it produces an empty array). Distinct
     * content is exactly the precondition Req 7.3 disambiguates.
     */
    @Provide
    fun sameDayPairs(): Arbitrary<SameDayPair> {
        val publishDates = Arbitraries.integers().between(0, 3650)
            .map { EPOCH_START.plusDays(it.toLong()) }
        val contents = Arbitraries.integers().between(0, 64)
            .flatMap { size ->
                Arbitraries.bytes().array(ByteArray::class.java).ofSize(size)
            }
        // A non-empty differing suffix (values in 1..255 so the appended byte is
        // never a plain zero-length no-op and B is always distinct from A).
        val suffixes = Arbitraries.bytes().between(1, Byte.MAX_VALUE)
            .array(ByteArray::class.java).ofMinSize(1).ofMaxSize(8)

        return publishDates.flatMap { date ->
            contents.flatMap { a ->
                suffixes.map { suffix ->
                    SameDayPair(publishDate = date, contentA = a, contentB = a + suffix)
                }
            }
        }
    }

    /** Builds [count] distinct in-scope records all stamped with [versionId]. */
    private fun recordsFor(versionId: VersionId, count: Int): List<InternalModelEntry> =
        (0 until count).map { i ->
            InternalModelEntry(
                fixedRef = FixedRef("FR-${versionId.digest.value.take(8)}-$i"),
                entityType = if (i % 2 == 0) EntityType.Individual else EntityType.Entity,
                primaryName = "name-$i",
                sanctionPrograms = listOf("program-$i"),
                versionId = versionId,
            )
        }

    private fun sha256(bytes: ByteArray): Sha256Digest {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Sha256Digest.ofHex(digest.joinToString("") { "%02x".format(it) })
    }

    private companion object {
        val EPOCH_START: LocalDate = LocalDate.of(2020, 1, 1)
    }
}
