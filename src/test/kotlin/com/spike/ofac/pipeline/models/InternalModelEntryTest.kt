package com.spike.ofac.pipeline.models

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for the internal data models and value types (task 2.1).
 *
 * These pin down the cardinality and required-field invariants from the design's
 * Data Models section (Req 4.1, 4.4, 4.6) and that string content is preserved
 * verbatim as UTF-8 (Req 4.3, 4.6).
 */
class InternalModelEntryTest {

    private fun minimalEntry() = InternalModelEntry(
        fixedRef = FixedRef("12345"),
        entityType = EntityType.Individual,
        primaryName = "Jane Doe",
        sanctionPrograms = listOf("SDN"),
    )

    @Test
    fun `minimal in-scope entry has empty multi-valued attributes and no stamped version`() {
        val entry = minimalEntry()

        entry.aliases.shouldContainExactly()
        entry.addresses.shouldContainExactly()
        entry.documents.shouldContainExactly()
        entry.nationalities.shouldContainExactly()
        entry.citizenships.shouldContainExactly()
        entry.birthDates.shouldContainExactly()
        entry.remarks.shouldContainExactly()
        entry.relationships.shouldContainExactly()
        // version_id is stamped only at persist time (Req 7.4).
        entry.versionId.shouldBeNull()
    }

    @Test
    fun `sanction_programs is 1_N - at least one is required (Req 4_4)`() {
        shouldThrow<IllegalArgumentException> {
            minimalEntry().copy(sanctionPrograms = emptyList())
        }
    }

    @Test
    fun `primary_name is required (Req 4_5)`() {
        shouldThrow<IllegalArgumentException> {
            minimalEntry().copy(primaryName = "")
        }
    }

    @Test
    fun `entity_type is in-scope only - Individual or Entity (Req 5)`() {
        EntityType.entries.map { it.name } shouldContainExactly listOf("Individual", "Entity")
    }

    @Test
    fun `FixedRef rejects empty values`() {
        shouldThrow<IllegalArgumentException> { FixedRef("") }
    }

    @Test
    fun `non-ASCII names and addresses are preserved verbatim (Req 4_3)`() {
        val entry = minimalEntry().copy(
            primaryName = "Hải Phòng",
            addresses = listOf(Address(raw = "Skořepka 1058/8 Staré Město")),
        )

        entry.primaryName shouldBe "Hải Phòng"
        entry.addresses.single().raw shouldBe "Skořepka 1058/8 Staré Město"
    }

    @Test
    fun `PartialDate accepts a year-only date (Req 4_6)`() {
        val date = PartialDate(year = 1980)

        date.year shouldBe 1980
        date.month.shouldBeNull()
        date.day.shouldBeNull()
        date.period.shouldBeNull()
    }

    @Test
    fun `PartialDate accepts a DatePeriod range without a top-level year (Req 4_6)`() {
        val date = PartialDate(
            period = PartialDate.Period(
                from = PartialDate(year = 1979),
                to = PartialDate(year = 1981),
            ),
        )

        date.year.shouldBeNull()
        val period = date.period!!
        period.from.year shouldBe 1979
        period.to.year shouldBe 1981
    }

    @Test
    fun `PartialDate requires at least one of year or period (Req 4_6)`() {
        shouldThrow<IllegalArgumentException> {
            PartialDate(month = 6, day = 15)
        }
    }

    @Test
    fun `Alias defaults type to null and is_primary to false`() {
        val alias = Alias(name = "Johnny")

        alias.type.shouldBeNull()
        alias.isPrimary.shouldBeFalse()
    }

    @Test
    fun `Relationship references the related party by FixedRef`() {
        val rel = Relationship(toFixedRef = FixedRef("999"), relationType = "Linked To")

        rel.toFixedRef shouldBe FixedRef("999")
        rel.relationType shouldBe "Linked To"
    }

    @Test
    fun `Diagnostic carries kind, detail, and an optional FixedRef`() {
        val diag = Diagnostic(
            kind = Diagnostic.Kind.UNRESOLVED_REF,
            detail = "Feature ref 42 not found",
            fixedRef = FixedRef("12345"),
        )

        diag.kind shouldBe Diagnostic.Kind.UNRESOLVED_REF
        diag.fixedRef shouldBe FixedRef("12345")

        // fixedRef is optional (some diagnostics are not tied to a record).
        Diagnostic(kind = Diagnostic.Kind.MAP_ERROR, detail = "x").fixedRef.shouldBeNull()
    }
}
