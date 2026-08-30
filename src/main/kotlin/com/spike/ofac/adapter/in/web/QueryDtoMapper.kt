package com.spike.ofac.adapter.`in`.web

import com.spike.ofac.application.port.`in`.Page as DomainPage
import com.spike.ofac.domain.model.Address as DomainAddress
import com.spike.ofac.domain.model.Alias as DomainAlias
import com.spike.ofac.domain.model.AliasCategory as DomainAliasCategory
import com.spike.ofac.domain.model.Document as DomainDocument
import com.spike.ofac.domain.model.EntityType as DomainEntityType
import com.spike.ofac.domain.model.InternalModelEntry as DomainEntry
import com.spike.ofac.domain.model.PartialDate as DomainPartialDate
import com.spike.ofac.domain.model.Relationship as DomainRelationship
import com.spike.ofac.domain.model.VersionId as DomainVersionId
import com.spike.ofac.adapter.web.generated.model.Address as AddressDto
import com.spike.ofac.adapter.web.generated.model.Alias as AliasDto
import com.spike.ofac.adapter.web.generated.model.Document as DocumentDto
import com.spike.ofac.adapter.web.generated.model.InternalModelEntry as EntryDto
import com.spike.ofac.adapter.web.generated.model.Page as PageDto
import com.spike.ofac.adapter.web.generated.model.PartialDate as PartialDateDto
import com.spike.ofac.adapter.web.generated.model.Period as PeriodDto
import com.spike.ofac.adapter.web.generated.model.Relationship as RelationshipDto
import com.spike.ofac.adapter.web.generated.model.VersionId as VersionIdDto

/**
 * Maps the domain model onto the **contract DTOs generated from `openapi.yaml`**
 * (task 24.6, spec-first).
 *
 * The generated DTOs (`com.spike.ofac.adapter.web.generated.model.*`) are the
 * wire types the [QueryController] returns, keeping the published contract
 * decoupled from the domain model: a change to the domain does not silently
 * change the API, and the controller only compiles while it produces exactly the
 * shapes the contract declares. This object is the single translation seam.
 *
 * The mapping is total and mechanical — field-for-field — because the OpenAPI
 * schemas were derived from these same domain types, so the shapes line up.
 */
object QueryDtoMapper {

    /** Domain [DomainPage] -> generated [PageDto]. */
    fun toDto(page: DomainPage): PageDto =
        PageDto(
            records = page.records.map(::toDto),
            total = page.total,
            offset = page.offset,
            limit = page.limit,
        )

    /** Domain [DomainEntry] -> generated [EntryDto]. */
    fun toDto(e: DomainEntry): EntryDto =
        EntryDto(
            fixedRef = e.fixedRef.value,
            entityType = toDto(e.entityType),
            primaryName = e.primaryName,
            aliases = e.aliases.map(::toDto),
            addresses = e.addresses.map(::toDto),
            documents = e.documents.map(::toDto),
            nationalities = e.nationalities,
            citizenships = e.citizenships,
            birthDates = e.birthDates.map(::toDto),
            sanctionPrograms = e.sanctionPrograms,
            remarks = e.remarks,
            relationships = e.relationships.map(::toDto),
            versionId = e.versionId?.let(::toDto),
        )

    private fun toDto(t: DomainEntityType): EntryDto.EntityType =
        when (t) {
            DomainEntityType.Individual -> EntryDto.EntityType.INDIVIDUAL
            DomainEntityType.Entity -> EntryDto.EntityType.ENTITY
        }

    private fun toDto(a: DomainAlias): AliasDto =
        AliasDto(name = a.name, isPrimary = a.isPrimary, category = toDto(a.category), type = a.type)

    private fun toDto(c: DomainAliasCategory): AliasDto.Category =
        when (c) {
            DomainAliasCategory.STRONG -> AliasDto.Category.STRONG
            DomainAliasCategory.WEAK -> AliasDto.Category.WEAK
        }

    private fun toDto(a: DomainAddress): AddressDto =
        AddressDto(raw = a.raw, parts = a.parts, country = a.country)

    private fun toDto(d: DomainDocument): DocumentDto =
        DocumentDto(type = d.type, number = d.number, issuer = d.issuer)

    private fun toDto(d: DomainPartialDate): PartialDateDto =
        PartialDateDto(
            year = d.year,
            month = d.month,
            day = d.day,
            period = d.period?.let { PeriodDto(from = toDto(it.from), to = toDto(it.to)) },
        )

    private fun toDto(r: DomainRelationship): RelationshipDto =
        RelationshipDto(toFixedRef = r.toFixedRef.value, relationType = r.relationType)

    private fun toDto(v: DomainVersionId): VersionIdDto =
        VersionIdDto(publishDate = v.publishDate, digest = v.digest.value)
}
