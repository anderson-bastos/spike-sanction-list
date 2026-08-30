package com.spike.ofac.domain.transform

import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Streaming, memory-bounded parser for the OFAC Advanced XML (task 5.1, Req 4.1, 4.3).
 *
 * ## Why streaming
 * The spike measured that ~98% of the ingestion cost is the XML parse and that a
 * full DOM peaks at ≈402 MB (`spike` §9). `ofac-data/benchmark.py` validated the
 * fix: advance **per `DistinctParty`** with an iterative parser and release each
 * party's subtree before moving on, so resident memory is bounded by a single
 * party rather than the whole document. This class is the JVM equivalent: a StAX
 * [XMLStreamReader] driven token-by-token, never building a full DOM.
 *
 * ## What it produces
 * A single pass yields a [ParsedSnapshot]: one [RawParsedProfile] per
 * `DistinctParty` plus the small cross-section tables ([RawReferenceTables])
 * needed to resolve ID references later — the `ReferenceValueSets` label maps,
 * the `Locations`, and the `IDRegDocuments` / `SanctionsEntries` /
 * `ProfileRelationships` link records. Reference **resolution** and the mapping
 * to the normalized model live in [ProfileEntryBuilder]; this class only reads.
 *
 * ## Encoding
 * The reader is created over the raw [InputStream] so StAX honors the document's
 * declared encoding (`<?xml ... encoding="utf-8"?>`), preserving non-ASCII names
 * and addresses verbatim (Req 4.3).
 *
 * ## Structure it reads (observed in the `*_advanced.xml` fixtures)
 * ```
 * Sanctions
 *   DateOfIssue        -> Publish_Date
 *   ReferenceValueSets -> label maps (FeatureType, IDRegDocType, RelationType, List, Country, LocPartType, AliasType)
 *   Locations/Location -> RawLocation
 *   IDRegDocuments/IDRegDocument           (IdentityID -> party)
 *   DistinctParties/DistinctParty          (FixedRef, Profile/@ID, PartySubTypeID)
 *     Profile/Identity/Alias/DocumentedName/DocumentedNamePart/NamePartValue
 *     Profile/Feature (FeatureVersion -> VersionDetail | DatePeriod | VersionLocation)
 *   ProfileRelationships/ProfileRelationship (From-ProfileID, To-ProfileID, RelationTypeID)
 *   SanctionsEntries/SanctionsEntry          (ProfileID, ListID, SanctionsMeasure programs)
 * ```
 *
 * The parser is defensive about ordering and about missing subtrees: it keys off
 * element local names (ignoring the default namespace) and tolerates absent
 * optional elements, so it does not assume a fixed section order.
 */
class AdvancedXmlStreamParser {

    private val factory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        // Harden against XXE / entity-expansion: this is untrusted external content
        // (see safety guidance). We only need element/text streaming.
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_COALESCING, true)
    }

    /**
     * Parse [input] in a single streaming pass and return the raw snapshot.
     *
     * The [InputStream] is read to completion but never buffered whole; the
     * caller owns closing it. Memory stays bounded to roughly one `DistinctParty`
     * plus the reference tables.
     */
    fun parse(input: InputStream): ParsedSnapshot {
        val reader = factory.createXMLStreamReader(input)
        try {
            return readDocument(reader)
        } finally {
            reader.close()
        }
    }

    private fun readDocument(reader: XMLStreamReader): ParsedSnapshot {
        var publishDate: RawPartialDate? = null

        val featureTypeNames = HashMap<String, String>()
        val idRegDocTypeNames = HashMap<String, String>()
        val relationTypeNames = HashMap<String, String>()
        val listNames = HashMap<String, String>()
        val countryNames = HashMap<String, String>()
        val locPartTypeNames = HashMap<String, String>()
        val aliasTypeNames = HashMap<String, String>()
        val locations = HashMap<String, RawLocation>()
        val idRegDocuments = ArrayList<RawIdRegDocument>()
        val sanctionsEntries = ArrayList<RawSanctionsEntry>()
        val relationships = ArrayList<RawProfileRelationship>()
        val profiles = ArrayList<RawParsedProfile>()

        while (reader.hasNext()) {
            val event = reader.next()
            if (event != XMLStreamConstants.START_ELEMENT) continue
            when (reader.localName) {
                "DateOfIssue" -> publishDate = readDate(reader, "DateOfIssue")

                // ReferenceValueSets: read the label maps we need for resolution.
                "FeatureType" -> putIdText(reader, featureTypeNames)
                "IDRegDocType" -> putIdText(reader, idRegDocTypeNames)
                "RelationType" -> putIdText(reader, relationTypeNames)
                "List" -> putIdText(reader, listNames)
                "Country" -> putIdText(reader, countryNames)
                "LocPartType" -> putIdText(reader, locPartTypeNames)
                "AliasType" -> putIdText(reader, aliasTypeNames)

                "Location" -> readLocation(reader)?.let { locations[it.locationId] = it }
                "IDRegDocument" -> idRegDocuments += readIdRegDocument(reader)
                "ProfileRelationship" -> relationships += readRelationship(reader)
                "SanctionsEntry" -> sanctionsEntries += readSanctionsEntry(reader)

                "DistinctParty" -> profiles += readDistinctParty(reader)
            }
        }

        return ParsedSnapshot(
            publishDate = publishDate,
            profiles = profiles,
            references = RawReferenceTables(
                featureTypeNames = featureTypeNames,
                idRegDocTypeNames = idRegDocTypeNames,
                relationTypeNames = relationTypeNames,
                listNames = listNames,
                countryNames = countryNames,
                locPartTypeNames = locPartTypeNames,
                aliasTypeNames = aliasTypeNames,
                locations = locations,
                idRegDocuments = idRegDocuments,
                sanctionsEntries = sanctionsEntries,
                relationships = relationships,
            ),
        )
    }

    // ---- ReferenceValueSets: <X ID="..">text</X> ----------------------------

    /** Reads an `ID`-keyed label element (e.g. `<FeatureType ID="8">Birthdate</FeatureType>`). */
    private fun putIdText(reader: XMLStreamReader, into: MutableMap<String, String>) {
        val id = reader.attr("ID") ?: run { skipElement(reader); return }
        val text = readElementText(reader).trim()
        if (text.isNotEmpty()) into[id] = text
    }

    // ---- Locations ----------------------------------------------------------

    private fun readLocation(reader: XMLStreamReader): RawLocation? {
        val id = reader.attr("ID") ?: run { skipElement(reader); return null }
        var countryId: String? = null
        val parts = ArrayList<RawLocationPart>()
        val end = "Location"
        var locPartType: String? = null
        while (reader.hasNext()) {
            val event = reader.next()
            when (event) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "LocationCountry" -> countryId = reader.attr("CountryID") ?: countryId
                    "LocationPart" -> locPartType = reader.attr("LocPartTypeID")
                    "Value" -> {
                        val v = readElementText(reader)
                        if (v.isNotBlank()) parts += RawLocationPart(locPartType, v)
                    }
                }
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == end) {
                    return RawLocation(id, parts, countryId)
                }
            }
        }
        return RawLocation(id, parts, countryId)
    }

    // ---- IDRegDocument ------------------------------------------------------

    private fun readIdRegDocument(reader: XMLStreamReader): RawIdRegDocument {
        val id = reader.attr("ID").orEmpty()
        val identityId = reader.attr("IdentityID")
        val typeId = reader.attr("IDRegDocTypeID")
        var registrationNumber: String? = null
        var issuer: String? = null
        val end = "IDRegDocument"
        while (reader.hasNext()) {
            val event = reader.next()
            when (event) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "IDRegistrationNo" -> registrationNumber = readElementText(reader).ifBlank { null }
                    "IssuingAuthority" -> issuer = readElementText(reader).ifBlank { null }
                }
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == end) {
                    return RawIdRegDocument(id, identityId, typeId, registrationNumber, issuer)
                }
            }
        }
        return RawIdRegDocument(id, identityId, typeId, registrationNumber, issuer)
    }

    // ---- ProfileRelationship ------------------------------------------------

    private fun readRelationship(reader: XMLStreamReader): RawProfileRelationship {
        val rel = RawProfileRelationship(
            id = reader.attr("ID").orEmpty(),
            fromProfileId = reader.attr("From-ProfileID"),
            toProfileId = reader.attr("To-ProfileID"),
            relationTypeId = reader.attr("RelationTypeID"),
        )
        skipElement(reader) // consume the (comment-only) body
        return rel
    }

    // ---- SanctionsEntry -----------------------------------------------------

    private fun readSanctionsEntry(reader: XMLStreamReader): RawSanctionsEntry {
        val id = reader.attr("ID").orEmpty()
        val profileId = reader.attr("ProfileID")
        val listId = reader.attr("ListID")
        val programNames = ArrayList<String>()
        val end = "SanctionsEntry"
        // A program name is the Comment text on a SanctionsMeasure whose
        // SanctionsTypeID is the "Program" type (id 1), e.g. "SDGT", "NS-PLC".
        var inProgramMeasure = false
        while (reader.hasNext()) {
            val event = reader.next()
            when (event) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "SanctionsMeasure" ->
                        inProgramMeasure = reader.attr("SanctionsTypeID") == PROGRAM_SANCTIONS_TYPE_ID
                    "Comment" -> if (inProgramMeasure) {
                        val c = readElementText(reader).trim()
                        if (c.isNotEmpty()) programNames += c
                    }
                }
                XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                    "SanctionsMeasure" -> inProgramMeasure = false
                    end -> return RawSanctionsEntry(id, profileId, listId, programNames)
                }
            }
        }
        return RawSanctionsEntry(id, profileId, listId, programNames)
    }

    // ---- DistinctParty ------------------------------------------------------

    private fun readDistinctParty(reader: XMLStreamReader): RawParsedProfile {
        val fixedRef = reader.attr("FixedRef").orEmpty()
        var profileId = ""
        var identityId: String? = null
        var partySubTypeId: String? = null
        val aliases = ArrayList<RawAlias>()
        val features = ArrayList<RawFeature>()
        val end = "DistinctParty"

        while (reader.hasNext()) {
            val event = reader.next()
            when (event) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "Profile" -> {
                        profileId = reader.attr("ID").orEmpty()
                        partySubTypeId = reader.attr("PartySubTypeID")
                    }
                    "Identity" -> identityId = reader.attr("ID") ?: identityId
                    "Alias" -> readAlias(reader)?.let { aliases += it }
                    "Feature" -> features += readFeature(reader)
                }
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == end) {
                    return RawParsedProfile(
                        fixedRef = fixedRef,
                        profileId = profileId,
                        identityId = identityId,
                        partySubTypeId = partySubTypeId,
                        aliases = aliases,
                        features = features,
                    )
                }
            }
        }
        return RawParsedProfile(fixedRef, profileId, identityId, partySubTypeId, aliases, features)
    }

    /** Reads one `Alias`, joining its `NamePartValue` texts in source order. */
    private fun readAlias(reader: XMLStreamReader): RawAlias? {
        val aliasTypeId = reader.attr("AliasTypeID")
        val primary = reader.attr("Primary") == "true"
        val nameParts = ArrayList<String>()
        val end = "Alias"
        while (reader.hasNext()) {
            val event = reader.next()
            when (event) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "NamePartValue") {
                    val v = readElementText(reader)
                    if (v.isNotBlank()) nameParts += v.trim()
                }
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == end) {
                    val full = nameParts.joinToString(" ").trim()
                    return if (full.isEmpty()) null else RawAlias(aliasTypeId, primary, full)
                }
            }
        }
        val full = nameParts.joinToString(" ").trim()
        return if (full.isEmpty()) null else RawAlias(aliasTypeId, primary, full)
    }

    /**
     * Reads one `Feature`, capturing whichever payload shape the `FeatureVersion`
     * carried: a `DatePeriod`, a `VersionLocation` reference, or `VersionDetail`
     * text. The first non-empty shape encountered wins.
     */
    private fun readFeature(reader: XMLStreamReader): RawFeature {
        val featureId = reader.attr("ID").orEmpty()
        val featureTypeId = reader.attr("FeatureTypeID")
        var detailValue: String? = null
        var datePeriod: RawDatePeriod? = null
        var locationId: String? = null
        val end = "Feature"
        while (reader.hasNext()) {
            val event = reader.next()
            when (event) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "DatePeriod" -> if (datePeriod == null) datePeriod = readDatePeriod(reader)
                    "VersionLocation" -> if (locationId == null) locationId = reader.attr("LocationID")
                    "VersionDetail" -> {
                        val text = readElementText(reader).trim()
                        if (text.isNotEmpty() && detailValue == null) detailValue = text
                    }
                }
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == end) {
                    return RawFeature(featureId, featureTypeId, detailValue, datePeriod, locationId)
                }
            }
        }
        return RawFeature(featureId, featureTypeId, detailValue, datePeriod, locationId)
    }

    /**
     * Reads a `DatePeriod`, collapsing the source's nested `Start/From..To` and
     * `End/From..To` into a single [RawDatePeriod]. We take the earliest `From`
     * of `Start` as the period start and the latest `To` of `End` as the period
     * end; when the two coincide it is effectively a single (possibly partial)
     * date. Partial (year-only) dates are preserved (Req 4.6).
     */
    private fun readDatePeriod(reader: XMLStreamReader): RawDatePeriod {
        var start: RawPartialDate? = null
        var end: RawPartialDate? = null
        val endTag = "DatePeriod"
        // Track which section (Start/End) we are inside; read the partial date as
        // soon as we enter a From/To subtree.
        var section: String? = null
        while (reader.hasNext()) {
            val event = reader.next()
            when (event) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "Start", "End" -> section = reader.localName
                    "From", "To" -> {
                        val partial = readPartialDate(reader, reader.localName)
                        when {
                            section == "Start" && start == null -> start = partial
                            section == "End" -> end = partial
                        }
                    }
                }
                XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                    "Start", "End" -> section = null
                    endTag -> return RawDatePeriod(start, end)
                }
            }
        }
        return RawDatePeriod(start, end)
    }

    /** Reads `<Year>/<Month>/<Day>` inside the element named [endTag]. */
    private fun readPartialDate(reader: XMLStreamReader, endTag: String): RawPartialDate {
        var year: Int? = null
        var month: Int? = null
        var day: Int? = null
        while (reader.hasNext()) {
            val event = reader.next()
            when (event) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "Year" -> year = readElementText(reader).trim().toIntOrNull()
                    "Month" -> month = readElementText(reader).trim().toIntOrNull()
                    "Day" -> day = readElementText(reader).trim().toIntOrNull()
                }
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == endTag) {
                    return RawPartialDate(year, month, day)
                }
            }
        }
        return RawPartialDate(year, month, day)
    }

    /**
     * Reads a standalone date element (e.g. `DateOfIssue`) into a [RawPartialDate].
     */
    private fun readDate(reader: XMLStreamReader, endTag: String): RawPartialDate =
        readPartialDate(reader, endTag)

    // ---- Low-level StAX helpers ---------------------------------------------

    /** Reads the concatenated character content of the current element, then positions past its END_ELEMENT. */
    private fun readElementText(reader: XMLStreamReader): String {
        // With IS_COALESCING=true, getElementText returns the full text and leaves
        // the cursor on the END_ELEMENT. It throws if the element has child
        // elements; for the leaf text elements we call it on, that never happens.
        return try {
            reader.elementText
        } catch (_: Exception) {
            // Defensive: if a "text" element unexpectedly had children, skip it.
            ""
        }
    }

    /**
     * Advances past the current element (already positioned on its START_ELEMENT),
     * consuming its entire subtree, so the cursor rests just after its END_ELEMENT.
     * Used to release subtrees we do not need, keeping memory bounded.
     */
    private fun skipElement(reader: XMLStreamReader) {
        var depth = 1
        while (depth > 0 && reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> depth--
            }
        }
    }

    private fun XMLStreamReader.attr(name: String): String? {
        for (i in 0 until attributeCount) {
            if (getAttributeLocalName(i) == name) return getAttributeValue(i)
        }
        return null
    }

    private companion object {
        /** `SanctionsType` id whose measure `Comment` names the program (observed in the Advanced XML). */
        const val PROGRAM_SANCTIONS_TYPE_ID = "1"
    }
}
