package com.spike.ofac.testsupport

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Accessor for the real OFAC data files under `ofac-data/` at the repository root.
 *
 * These are the canonical fixtures for the end-to-end and parse tests:
 *  - [SDN_ADVANCED_XML]  the SDN Advanced XML (canonical, lossless) — the primary
 *                        fixture for the streaming StAX parse (task 5).
 *  - [CONS_ADVANCED_XML] the Consolidated Advanced XML.
 *  - [SDN_XML]           the legacy SDN XML.
 *  - [SDN_CSV]           the SDN CSV.
 *
 * The streaming-parse approach and the `PartySubTypeID -> type` ReferenceValueSet
 * are grounded in `ofac-data/benchmark.py`:
 *  - the benchmark advances per `DistinctParty` with a streaming/iterative parser
 *    (Python `xml.etree.iterparse`, the JVM equivalent being a StAX
 *    `XMLStreamReader`) and clears each element to bound memory, and
 *  - it maps `PartySubTypeID` via [PARTY_SUBTYPE], with [IN_SCOPE] = {Entity, Individual}.
 *
 * The large XML files are `.gitignore`d (see repo `.gitignore`); tests that
 * depend on them should first check [available] and skip (rather than fail) when
 * the files are absent, so CI without the data still builds.
 */
object Fixtures {

    /** Observed `PartySubTypeID -> type` ReferenceValueSet (from `benchmark.py`). */
    val PARTY_SUBTYPE: Map<String, String> = mapOf(
        "1" to "Vessel",
        "2" to "Aircraft",
        "3" to "Entity",
        "4" to "Individual",
    )

    /** In-scope entity types for ingestion (Req 5). */
    val IN_SCOPE: Set<String> = setOf("Entity", "Individual")

    /** Directory holding the real OFAC data files, resolved from the repo root. */
    val ofacDataDir: Path by lazy { locateOfacDataDir() }

    val SDN_ADVANCED_XML: Path get() = ofacDataDir.resolve("sdn_advanced.xml")
    val CONS_ADVANCED_XML: Path get() = ofacDataDir.resolve("cons_advanced.xml")
    val SDN_XML: Path get() = ofacDataDir.resolve("sdn.xml")
    val SDN_CSV: Path get() = ofacDataDir.resolve("sdn.csv")

    /** True when a given fixture file exists on disk. */
    fun available(fixture: Path): Boolean = Files.isRegularFile(fixture)

    /**
     * Walks up from the working directory until it finds an `ofac-data`
     * directory, so the accessor works regardless of the Gradle test CWD.
     */
    private fun locateOfacDataDir(): Path {
        var dir: Path? = Paths.get("").toAbsolutePath()
        while (dir != null) {
            val candidate = dir.resolve("ofac-data")
            if (Files.isDirectory(candidate)) return candidate
            dir = dir.parent
        }
        // Fall back to a repo-root-relative path; existence is checked via `available`.
        return Paths.get("ofac-data").toAbsolutePath()
    }
}
