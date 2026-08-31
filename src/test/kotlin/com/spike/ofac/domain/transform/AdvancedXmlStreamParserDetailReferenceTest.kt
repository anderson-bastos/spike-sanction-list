package com.spike.ofac.domain.transform

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit test for `DetailReferenceValues` resolution wiring in the streaming parser.
 *
 * OFAC's Advanced XML carries some feature values *by reference*: a
 * `VersionDetail` with a `@DetailReferenceID` whose text lives in the top-level
 * `DetailReferenceValues` table (e.g. Gender → 91526=Male / 91527=Female). This
 * test drives [AdvancedXmlStreamParser] over a minimal inline document to pin
 * two behaviors:
 *
 *  - the `DetailReferenceValues`/`DetailReference` table is read into
 *    [RawReferenceTables.detailReferenceNames]; and
 *  - a `VersionDetail/@DetailReferenceID` is captured onto
 *    [RawFeature.detailReferenceId] (rather than being lost as an empty inline
 *    value).
 */
class AdvancedXmlStreamParserDetailReferenceTest {

    private val parser = AdvancedXmlStreamParser()

    @Test
    fun `parses the DetailReferenceValues table and captures a feature DetailReferenceID`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <Sanctions>
              <ReferenceValueSets>
                <FeatureTypeValues>
                  <FeatureType ID="224">Gender</FeatureType>
                </FeatureTypeValues>
                <DetailReferenceValues>
                  <DetailReference ID="91526">Male</DetailReference>
                  <DetailReference ID="91527">Female</DetailReference>
                </DetailReferenceValues>
              </ReferenceValueSets>
              <DistinctParties>
                <DistinctParty FixedRef="42">
                  <Profile ID="p1" PartySubTypeID="4">
                    <Identity ID="i1">
                      <Alias Primary="true">
                        <DocumentedName>
                          <DocumentedNamePart>
                            <NamePartValue>Jane Doe</NamePartValue>
                          </DocumentedNamePart>
                        </DocumentedName>
                      </Alias>
                    </Identity>
                    <Feature ID="f1" FeatureTypeID="224">
                      <FeatureVersion>
                        <VersionDetail DetailTypeID="1431" DetailReferenceID="91526" />
                      </FeatureVersion>
                    </Feature>
                  </Profile>
                </DistinctParty>
              </DistinctParties>
            </Sanctions>
        """.trimIndent()

        val snapshot = parser.parse(xml.byteInputStream())

        // The lookup table was read.
        snapshot.references.detailReferenceNames shouldBe mapOf("91526" to "Male", "91527" to "Female")

        // The feature carried its DetailReferenceID (no inline text).
        val feature = snapshot.profiles.single().features.single()
        feature.featureTypeId shouldBe "224"
        feature.detailValue shouldBe null
        feature.detailReferenceId shouldBe "91526"
    }
}
