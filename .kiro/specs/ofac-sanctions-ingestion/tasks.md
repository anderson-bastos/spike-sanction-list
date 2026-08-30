# Implementation Plan: OFAC Sanctions Ingestion

## Implementation Language

**Chosen stack (confirmed, but changeable): Kotlin on Spring Boot.**

Matching `design.md`, the pipeline is realized in **Kotlin on Spring Boot**, built with **Gradle (Kotlin DSL)**. Property-based testing uses **jqwik** on the JVM, with jqwik's **stateful/model-based** mode for the version-pointer properties (P10–P14). Example and unit tests use **JUnit 5** with **kotest assertions**, and **MockK** as the mocking library for isolating collaborators in unit tests. Database integration tests run against **Testcontainers (PostgreSQL)**, with Postgres access via **Spring Data (JDBC/JPA) or jOOQ** (implementation choice) and atomic activation via Spring **`@Transactional`**. HTTP obtain tests use **MockWebServer** or **WireMock**. The read-only `Query_API` is served with **Spring Web**, and the `Scheduler` is realized with Spring **`@Scheduled`** (no OS cron). XML is parsed with **StAX** (`javax.xml.stream.XMLStreamReader`) as a streaming parse. The design's interfaces stay expressed as language-neutral contracts, and the property→requirement mapping is language-independent, so the stack can be swapped without changing the task structure or the property/requirement mappings.

## Overview

This plan builds the pipeline as six source-independent stages (`obtain → validate → transform → version → persist → publish`) driven by a `Scheduler` and parameterized by a per-source `SourceAdapter`, exactly as laid out in the design.

The plan is **incremental and test-driven**. It builds the **pure-logic components first** (data models, scope filter, dedup, count reconciliation, version-identity, and the version-pointer state machine) because those carry most of the 20 property-based tests and can be validated in isolation against generated inputs. The **I/O components** (obtain HEAD/GET, integrity validation, the PostgreSQL `Data_Store` that holds the processed model + version metadata + pointers, the local versioned `Raw_Snapshot_Store` folder that holds each raw snapshot file, the read-only `Query_API` over `CURRENT`, and the scheduler) come next, covered by example and integration/smoke tests. Everything is finally wired end-to-end against the real `ofac-data/` fixtures.

Each of the 20 correctness properties is implemented as a **single** property-based test with a **minimum of 100 iterations**, tagged `Feature: ofac-sanctions-ingestion, Property {n}: {text}` as the design's Testing Strategy specifies. Property sub-tasks sit next to the code they validate so logic errors surface early.

### Pending business decisions (kept configurable, never hard-coded)

- **List scope default** (Req 12): `SDN_ONLY` vs `SDN_AND_CONSOLIDATED`. Tasks accept scope as configuration with **no committed default**.
- **Retention period** (Req 14): undecided. Tasks treat the retention period as a config parameter.
- **What to preserve** (Req 14): raw snapshot / model / both. Tasks treat `preserve` as config; reconstruction-fidelity work assumes RAW is available (only faithful source).

Tasks that depend on these decisions are marked and implemented against configuration, not fixed values. Multi-source UN/EU adapters and retention are marked as future/optional work.

## Tasks

- [x] 1. Set up project structure, tooling, and fixtures
  - Create a **Gradle (Kotlin DSL) Spring Boot** project with the package layout: `pipeline/` (stages, adapters, models, store) and a matching test source set (unit, property, integration).
  - Add dependencies: **jqwik** (property-based testing), **JUnit 5** and **kotest assertions** (example/unit tests), **MockK** (mocking library for isolating collaborators in unit tests), **Testcontainers (PostgreSQL)** for the local database that backs the whole `VersionStore`, **MockWebServer** or **WireMock** for HTTP obtain tests, **Spring Web** for the `Query_API`, **Spring Data (JDBC/JPA) or jOOQ** for Postgres access (implementation choice), and a **PostgreSQL JDBC driver**.
  - Provision a local PostgreSQL instance — or spin one up with **Testcontainers** — for the persistence integration tests; the data-access layer connects to it and each integration test is isolated (Testcontainers instance per class, or a rolled-back `@Transactional` test).
  - Add configuration for the local `Raw_Snapshot_Store` folder path (where each raw snapshot file is written under a name derived from `Publish_Date` + `Digest`) and a separate temporary folder used by tests so raw-store tests never touch the operational folder. The raw snapshot is stored as a file in this folder, never in the `Data_Store`.
  - Note the name-search indexing need for the `Query_API`: the schema (task 13.1) must create indexes supporting case-insensitive **contains** matching over the primary name and alias values, plus an index enabling deterministic `FixedRef` ordering.
  - Wire the real `ofac-data/` files (`sdn_advanced.xml`, `cons_advanced.xml`, `sdn.xml`, `sdn.csv`) as test fixtures via a fixtures accessor; reference `benchmark.py` for the streaming-parse approach and the `PartySubTypeID → type` ReferenceValueSet.
  - Configure the PBT library defaults (min 100 iterations, jqwik `@Property(tries = 100)` style) and a shared tagging convention for property tests.
  - _Requirements: 7.1, 13.1, 15.1, 16.2_

- [x] 2. Define the internal data models and value types
  - [x] 2.1 Implement `InternalModelEntry` and its value types
    - Define `InternalModelEntry`, `Alias`, `Address`, `Document`, `PartialDate`, `Relationship`, `Diagnostic` per the Data Models section.
    - Enforce cardinalities: multi-valued attributes are 0..N; `sanction_programs` is 1..N; `primary_name` required; `PartialDate` requires at least one of year/period.
    - Model `entity_type` as in-scope only (`Individual` | `Entity`); UTF-8 strings preserved verbatim.
    - _Requirements: 4.1, 4.4, 4.6_
  - [x] 2.2 Implement `VersionId`, `VersionMetadata`, `VersionPointers`, and config types
    - Define `VersionId = (Publish_Date, Sha256Digest)`, `VersionMetadata`, and per-list `VersionPointers`.
    - Define `ScopeConfig` (`SDN_ONLY` | `SDN_AND_CONSOLIDATED`) and `RetentionPolicy` (`enabled`, optional `retention_period`, `preserve`) as configuration types with no committed defaults.
    - _Requirements: 7.2, 12.1, 14.1_

- [x] 3. Implement scope filtering (pure logic)
  - [x] 3.1 Implement the scope classifier and filter
    - Classify each raw profile by `PartySubTypeID` using the observed ReferenceValueSet (`{"1":"Vessel","2":"Aircraft","3":"Entity","4":"Individual"}`); `IN_SCOPE = {Entity, Individual}`.
    - Include every in-scope record; exclude Vessel/Aircraft; exclude missing/empty/unrecognized types emitting one diagnostic per excluded record without aborting.
    - _Requirements: 5.1, 5.2, 5.3_
  - [x] 3.2 Write property test for the scope filter
    - **Property 3: Scope filter yields zero vessels and aircraft**
    - Generate arbitrary mixes of entity types (including missing/empty/unrecognized); assert zero Vessel/Aircraft in output, exactly the Individual/Entity records kept, and one diagnostic per excluded record.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 3: Scope filter yields zero vessels and aircraft`. Min 100 iterations.
    - **Validates: Requirements 5.1, 5.2, 5.3**

- [x] 4. Implement deduplication by FixedRef (pure logic)
  - [x] 4.1 Implement cross-list dedup with SDN precedence
    - Combine in-scope record sets into exactly one record per distinct `FixedRef`; on SDN/Consolidated overlap retain the SDN representation; produce the distinct union (not the sum).
    - Apply only when the configured scope includes both lists.
    - _Requirements: 6.1, 6.2, 6.3_
  - [x] 4.2 Write property test for deduplication
    - **Property 4: Deduplication produces the distinct union**
    - Generate SDN/Consolidated record-set pairs with controlled overlap; assert one record per FixedRef, shared records equal their SDN form, and persisted count equals the distinct-union size (≤ naive sum, equal only with no overlap).
    - Tag: `Feature: ofac-sanctions-ingestion, Property 4: Deduplication produces the distinct union`. Min 100 iterations.
    - **Validates: Requirements 6.1, 6.2, 6.3**

- [x] 5. Implement the transform stage (streaming parse, reference resolution, normalization)
  - [x] 5.1 Implement streaming parse and reference resolution
    - Streaming parse of Advanced XML `DistinctParty` profiles with a StAX `XMLStreamReader` that advances token-by-token per `DistinctParty` without materializing a full DOM, so memory stays bounded (the spike's `benchmark.py` measured the same streaming strategy).
    - Resolve `Feature`, `IDRegDocument`, `SanctionsEntry`, `ProfileRelationship` ID references to referenced records; process content as UTF-8.
    - Build `InternalModelEntry` values; use the primary name when a record has zero aliases; preserve partial birth dates (year-only or `DatePeriod`).
    - _Requirements: 4.1, 4.2, 4.3, 4.5, 4.6_
  - [x] 5.2 Implement transform diagnostics and hard-failure semantics
    - Emit one diagnostic per unresolvable ID reference (identifying record + reference) and continue with remaining records.
    - Fail the whole stage (`FAILED`) if any record cannot be parsed into an entry, so no partial version is produced.
    - Assemble `TransformResult` carrying `entries`, `out_of_scope_count`, `record_count` (from body), and `diagnostics`; integrate scope filter (task 3) and dedup (task 4).
    - _Requirements: 4.7, 4.8_
  - [x] 5.3 Write property test for transformation round-trip
    - **Property 5: Transformation preserves data and resolves references (round-trip)**
    - Generate profiles including zero-alias, non-ASCII names/addresses, and incomplete birth dates; assert transform→serialize preserves every field exactly, primary name used when no aliases, and every resolvable reference resolved.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 5: Transformation preserves data and resolves references (round-trip)`. Min 100 iterations.
    - **Validates: Requirements 4.1, 4.2, 4.3, 4.5, 4.6**
  - [x] 5.4 Write property test for mandatory sanction program
    - **Property 6: Every persisted entry has at least one sanction program**
    - Assert every persisted in-scope entry has ≥1 sanction program while other multi-valued attributes may be empty.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 6: Every persisted entry has at least one sanction program`. Min 100 iterations.
    - **Validates: Requirements 4.4**
  - [x] 5.5 Write property test for unresolved references not aborting
    - **Property 7: Unresolvable references do not abort transformation**
    - Generate snapshots with arbitrary numbers of unresolvable references; assert completion, exactly one diagnostic per unresolved reference, and entries for all remaining resolvable records.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 7: Unresolvable references do not abort transformation`. Min 100 iterations.
    - **Validates: Requirements 4.7**
  - [x] 5.6 Write unit tests for transform edge cases
    - Zero-alias profile produces an entry using the primary name (Req 4.5); an unparseable record fails the stage with no partial version (Req 4.8).
    - _Requirements: 4.5, 4.8_

- [x] 6. Checkpoint - core transformation logic
  - Ensure all tests pass, ask the user if questions arise.

- [x] 7. Implement version identity and count reconciliation (pure logic)
  - [x] 7.1 Implement `version.build` (identity + Expected_Count)
    - Build `VersionId = (Publish_Date, Digest)`; stamp `version_id` onto records at persist time.
    - Derive `Expected_Count = Record_Count - out_of_scope_count - shared_fixedref_overlaps` (overlap term zero for single-list scope).
    - Reject with `RECORD_COUNT_MISSING_OR_INVALID` when `Record_Count` is absent or non-numeric.
    - _Requirements: 7.2, 7.3, 7.4, 8.1, 8.4_
  - [x] 7.2 Write property test for count reconciliation
    - **Property 8: Count reconciliation**
    - Assert derived `Expected_Count` matches the formula, persisted in-scope post-dedup count equals it exactly, and dropping any in-scope record makes counts differ (activation rejected).
    - Tag: `Feature: ofac-sanctions-ingestion, Property 8: Count reconciliation`. Min 100 iterations.
    - **Validates: Requirements 8.1, 8.2, 8.3**
  - [x] 7.3 Write property test for version identity
    - **Property 9: Version identity disambiguates same-day publications**
    - Generate two snapshots with equal `Publish_Date` but different content; assert their `VersionId`s differ (via Digest) and both persist as separate versions.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 9: Version identity disambiguates same-day publications`. Min 100 iterations.
    - **Validates: Requirements 7.2, 7.3, 7.4**
  - [x] 7.4 Write unit test for invalid Record_Count
    - `Record_Count` absent/non-numeric rejected (Req 8.4); count mismatch (drop one record) rejected (Req 8.3).
    - _Requirements: 8.3, 8.4_

- [x] 8. Implement the VersionStore contract and the version-pointer state machine (pure logic + reference model)
  - [x] 8.1 Define the `VersionStore` interface and an in-memory reference implementation
    - Define `put_isolated`, `atomic_set_current`, `get_pointer`, `reclassify_cold`, `last_ingested`, `verify_integrity`, `associate_raw_path` per the contract.
    - Provide an in-memory implementation guaranteeing atomic pointer swap and immutable version records, usable as the model for stateful property tests; the in-memory `associate_raw_path` may be a no-op/stub since the reference model holds no real raw files.
    - This stays the reference **model** for the stateful property tests (Properties 10–14); the concrete PostgreSQL-backed store (`PgVersionStore`) is built later in task 13 against this same contract.
    - _Requirements: 7.5, 7.6, 9.1, 9.2, 15.6_
  - [x] 8.2 Implement `publish.activate` (result-validate + atomic activation + window rotation)
    - Result-validate persisted in-scope post-dedup count == `expected_count` exactly (`REJECTED(COUNT_MISMATCH)` otherwise).
    - Atomically repoint `CURRENT`; never zero CURRENT; on repoint failure keep the pointer trio unchanged (`REJECTED(REPOINT_FAILED)`).
    - Rotate window: old CURRENT→PREVIOUS, old PREVIOUS→N_MINUS_2, older→COLD; keep at most three HOT versions per list.
    - _Requirements: 8.2, 8.3, 9.1, 9.2, 9.3, 9.4, 10.1, 10.5_
  - [x] 8.3 Implement `publish.rollback` and per-list independence
    - Rollback moves `CURRENT → PREVIOUS` by pointer only (no download/reprocess/mutation); reject with `NO_PREVIOUS` when absent.
    - Ensure each `Source_List` versions on an independent line so operations on one list never touch another's versions/pointers.
    - _Requirements: 10.2, 10.3, 10.4_
  - [x] 8.4 Write stateful property test for atomic activation
    - **Property 10: Atomic activation never yields zero CURRENT**
    - Stateful/model-based test: random activation sequences; assert CURRENT always resolves to exactly one fully-persisted version, isolated versions not resolvable pre-activation, and post-activation CURRENT resolves fully to new (success) or prior (rejected).
    - Tag: `Feature: ofac-sanctions-ingestion, Property 10: Atomic activation never yields zero CURRENT`. Min 100 iterations.
    - **Validates: Requirements 7.6, 9.1, 9.2**
  - [x] 8.5 Write stateful property test for window rotation
    - **Property 11: Version window rotation keeps the three most recent HOT versions**
    - Stateful test over N activations; assert HOT set is exactly the three most-recent in order, HOT count ≤ 3, and displaced versions become COLD with content unchanged.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 11: Version window rotation keeps the three most recent HOT versions`. Min 100 iterations.
    - **Validates: Requirements 7.5, 9.3, 10.1, 10.5**
  - [x] 8.6 Write stateful property test for activate-then-rollback round-trip
    - **Property 12: Activate-then-rollback restores the prior CURRENT (round-trip)**
    - For states with a PREVIOUS, assert activate-then-rollback restores CURRENT to the exact pre-activation version, moving pointers only.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 12: Activate-then-rollback restores the prior CURRENT (round-trip)`. Min 100 iterations.
    - **Validates: Requirements 10.3**
  - [x] 8.7 Write stateful property test for per-list independence
    - **Property 13: Per-list independence**
    - Interleave activate/rollback across two `Source_Lists`; assert operations on one leave the other's versions and pointer trio unchanged.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 13: Per-list independence`. Min 100 iterations.
    - **Validates: Requirements 10.2**
  - [x] 8.8 Write unit tests for rollback and repoint edge cases
    - Rollback with no `PREVIOUS` rejected (Req 10.4); repoint failure leaves pointers unchanged (Req 9.4).
    - _Requirements: 9.4, 10.4_

- [x] 9. Checkpoint - version and pointer state machine
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Implement the validate stage (integrity + well-formedness)
  - [x] 10.1 Implement `validate.check`
    - Compute SHA-256 and compare to advertised `Digest` **before any parsing**; on match verify well-formed Advanced XML.
    - Return distinct causes: `ABSENT_DIGEST`, `DIGEST_MISMATCH`, `MALFORMED_XML`; every rejection leaves CURRENT unchanged.
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5_
  - [x] 10.2 Write property test for validation
    - **Property 2: Validation accepts exactly the intact, well-formed snapshots**
    - Generate byte sequences + advertised digests and single-byte mutations of valid snapshots; assert OK iff SHA-256 matches AND well-formed, and each failure maps to its distinct cause.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 2: Validation accepts exactly the intact, well-formed snapshots`. Min 100 iterations.
    - **Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5**
  - [x] 10.3 Write unit tests for distinct validation causes
    - Absent digest, digest mismatch, and malformed XML each labelled correctly.
    - _Requirements: 3.2, 3.3, 3.5_

- [x] 11. Implement the SourceAdapter seam and OfacAdapter
  - [x] 11.1 Define the `SourceAdapter` interface and implement `OfacAdapter`
    - Define `head`, `get`, `map_record`, `entity_type_of` per the contract.
    - `OfacAdapter`: sends no credentials; maps `PartySubTypeID` via the observed ReferenceValueSet; returns `MappingError(field)` on a required-field mapping failure.
    - Ensure the six core stages consume the adapter without source-specific branches (Req 13.1).
    - _Requirements: 2.3, 13.1, 13.2, 13.4_
  - [x] 11.2 Write unit tests for adapter behavior
    - `OfacAdapter` sends no credentials (Req 2.3); required-field mapping failure names source + field (Req 13.4).
    - _Requirements: 2.3, 13.4_

- [x] 12. Implement the obtain stage (HEAD change-check + GET download)
  - [x] 12.1 Implement `obtain.check_change`
    - Issue HEAD via the adapter; read `Last-Modified` + `Digest` with a 30s timeout.
    - Decide `NO_CHANGE` vs `CHANGED` by comparing advertised `Digest` to the last-ingested version's digest; fall back to `Publish_Date` + `Record_Count` when the `Digest` header is absent.
    - Return `HEAD_FAILED(cause)` on connect error/timeout, ending the cycle with CURRENT unchanged and recording the failure for retry.
    - _Requirements: 1.2, 1.3, 1.4, 1.5, 1.6_
  - [x] 12.2 Implement `obtain.download`
    - GET the full snapshot over HTTPS, following ≤5 redirects, with a 120s timeout; OFAC adapters send no credentials; token-based adapters supply a token.
    - Verify completeness (e.g., `Content-Length` vs bytes received) before acceptance; on any failure discard the partial download and leave CURRENT unchanged (`DOWNLOAD_FAILED(cause)`).
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
  - [x] 12.3 Write property test for change-detection decision
    - **Property 1: Change-detection decision**
    - Generate source states (advertised digest, or Publish_Date+Record_Count fallback) and last-ingested states; assert download iff content differs.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 1: Change-detection decision`. Min 100 iterations.
    - **Validates: Requirements 1.3, 1.4, 1.5**
  - [x] 12.4 Write unit tests for download boundaries
    - 5 redirects accepted, 6 rejected (Req 2.2); truncated/incomplete download rejected (Req 2.4).
    - _Requirements: 2.2, 2.4_

- [x] 13. Implement the persist stage, the PostgreSQL-backed VersionStore, and the file-based RawSnapshotStore
  - [x] 13.1 Create the PostgreSQL schema for versions, records, and pointers
    - Create the `versions` table (one insert-only row per immutable version: `version_id = (publish_date, digest)`, `source_list`, the counts `record_count`/`out_of_scope_count`/`overlap_count`/`expected_count`/`persisted_count`, `state` HOT/COLD, `ingested_at`, a nullable `raw_snapshot_path` **filesystem path** into the local `Raw_Snapshot_Store` (populated only after the stored raw file's SHA-256 matches the recorded `Digest`) — **not** a `bytea`/large-object column; the raw bytes live on disk, never in the DB — and `integrity_ok`), the `records` table (persisted `Internal_Model` entries each stamped with their `version_id`; multi-valued attributes modeled either as child tables or as JSONB columns — implementation choice), and the `pointers` table (one row per `source_list` holding `current`/`previous`/`n_minus_2`), per the design's schema sketch.
    - Add the indexes the `Query_API` needs: a case-insensitive **contains** index over the primary name and over alias values (e.g. a trigram/GIN index) for the name-search endpoint, and an index on `FixedRef` for deterministic pagination ordering.
    - Rows within a persisted version are insert-only — never updated or deleted (immutability, Req 7.5).
    - _Requirements: 7.4, 7.5, 7.6, 15.1, 16.2, 16.3_
  - [x] 13.2 Implement `PgVersionStore` (concrete `VersionStore` over local PostgreSQL)
    - Implement the full `VersionStore` contract against PostgreSQL: `put_isolated` inserts version + record rows in a not-active state (invisible to consumers); `associate_raw_path` records the `Raw_Snapshot_Store` file path on the version metadata after the stored file's integrity has been confirmed; `atomic_set_current` updates the `pointers` row for the `source_list` inside a **single Spring `@Transactional` transaction** so the CURRENT repoint and window rotation are atomic; `get_pointer` reads CURRENT/PREVIOUS/N_MINUS_2; `reclassify_cold` marks versions displaced past N_MINUS_2 as COLD; `last_ingested` returns the digest of the most recent version; `verify_integrity` **delegates to the `RawSnapshotStore`** (task 13.3), which recomputes SHA-256 over the stored raw-snapshot **file** bytes and compares against the recorded `Digest` — the raw bytes are no longer a database column.
    - Wire `publish.activate`/`publish.rollback` (task 8) onto `PgVersionStore` so the atomic pointer swap (Req 9) is provided by the real database transaction.
    - _Requirements: 7.5, 7.6, 9.1, 9.2, 10.5, 14.5, 15.6_
  - [x] 13.3 Implement the `RawSnapshotStore` (`FsRawSnapshotStore`) over the local versioned folder
    - Implement `put(version_id, bytes)`: write a **write-once immutable** file named from (`Publish_Date`, `Digest`) using a temp-file + atomic rename so a partially written file is never visible as a persisted snapshot (Req 15.3); distinct (`Publish_Date`, `Digest`) pairs map to distinct file names so two same-day publications never overwrite one another (Req 15.2, 15.4).
    - Implement `get(version_id)`: read the stored raw file bytes for reconstruction (Req 14.3).
    - Implement `verify_integrity(version_id)`: recompute SHA-256 over the stored **file** bytes and compare against the recorded `Digest` (Req 15.5, 14.5).
    - Store the raw snapshot only as a file in the `Raw_Snapshot_Store`, never in the `Data_Store` (Req 15.8).
    - _Requirements: 14.5, 15.1, 15.2, 15.3, 15.4, 15.5, 15.8_
  - [x] 13.4 Write unit tests for the raw snapshot store
    - Versioned file naming derived from (`Publish_Date`, `Digest`); two same-day publications with different `Digest` produce two distinct files with neither overwriting the other (Req 15.2); a fully written file is immutable (Req 15.4); a failed write leaves no visible/partial file and is fail-closed (Req 15.9); the raw snapshot is never written into the DB (Req 15.8).
    - _Requirements: 15.2, 15.4, 15.8, 15.9_
  - [x] 13.5 Write integration test for real-transaction atomic activation
    - Against a Testcontainers PostgreSQL database, assert the real Spring `@Transactional` database transaction makes activation atomic: CURRENT always resolves to exactly one fully-persisted version, an isolated version is not resolvable before activation, and a rejected activation leaves the pointer trio unchanged — exercising Property 10/11 semantics against the concrete store; assert the new CURRENT is resolvable within 5s (Req 9.5).
    - _Requirements: 9.1, 9.2, 9.5, 10.5_
  - [x] 13.6 Implement `persist.write`
    - Write the raw snapshot bytes to the `RawSnapshotStore` (`put`) **and** all records as a new isolated, immutable version via `VersionStore.put_isolated`; stamp each record with its `version_id`.
    - Associate the stored raw file path with the `Version` metadata (`associate_raw_path`) **only after** the SHA-256 recomputed over the stored file bytes equals the recorded `Digest` (Req 15.5, 15.6).
    - Fail-closed: if the raw write fails, discard any partial file and leave CURRENT unchanged (`FAILED(RAW_WRITE)`, Req 15.9); if the stored-file integrity check fails, discard the file and leave CURRENT unchanged (`FAILED(RAW_INTEGRITY)`, Req 15.7); if the record write fails, discard the partial version and leave CURRENT unchanged (`FAILED(PERSIST)`, Req 7.7).
    - Keep the new version invisible to consumers until `publish` activates it.
    - _Requirements: 7.1, 7.4, 7.5, 7.6, 7.7, 15.6, 15.7, 15.9_
  - [x] 13.7 Write property test for raw file naming, immutability, and integrity association
    - **Property 18: Raw snapshot file naming, immutability, and integrity association**
    - Generate publications (including same-`Publish_Date`/different-`Digest` pairs); assert exactly one file per distinct (`Publish_Date`, `Digest`) with no overwrite, a fully written file's bytes never change, the stored file's SHA-256 equals the recorded `Digest`, and the version's `raw_snapshot_path` is associated only after that match holds.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 18: Raw snapshot file naming, immutability, and integrity association`. Min 100 iterations.
    - **Validates: Requirements 15.2, 15.3, 15.5, 15.6**
  - [x] 13.8 Write property test for failure safety before activation
    - **Property 14: Failure safety before activation**
    - Inject failures at each pre-activation stage (obtain/validate/transform/version/persist, incl. adapter auth/mapping failures and raw-write/raw-integrity failures); assert the pointer trio is identical before and after and no partial version is ever CURRENT.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 14: Failure safety before activation`. Min 100 iterations.
    - **Validates: Requirements 2.5, 7.7, 9.4, 11.1, 11.5, 13.4, 13.5, 15.7, 15.9**
  - [x] 13.9 Write property test for deterministic reprocessing
    - **Property 15: Deterministic reprocessing**
    - Run the pipeline twice on the same publication snapshot; assert identical `version_id` and byte-identical record set.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 15: Deterministic reprocessing`. Min 100 iterations.
    - **Validates: Requirements 11.4**

- [x] 14. Implement scope-configuration validation
  - [x] 14.1 Implement scope config validation and wiring
    - Accept only `SDN_ONLY` or `SDN_AND_CONSOLIDATED`; reject `CONSOLIDATED_ONLY`, and any absent/empty/unrecognized value, ingesting nothing and surfacing an error.
    - Wire scope into the pipeline: `SDN_ONLY` persists no Consolidated record; `SDN_AND_CONSOLIDATED` runs the dedup path (task 4). No default committed (pending business decision).
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5_
  - [x] 14.2 Write property test for scope configuration validation
    - **Property 16: Scope configuration validation**
    - Generate arbitrary scope values; assert acceptance iff exactly `SDN_ONLY` or `SDN_AND_CONSOLIDATED`, otherwise rejection with nothing ingested.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 16: Scope configuration validation`. Min 100 iterations.
    - **Validates: Requirements 12.1, 12.4, 12.5**
  - [x] 14.3 Write unit tests for scope wiring
    - `SDN_ONLY` persists no Consolidated record (Req 12.2); `SDN_AND_CONSOLIDATED` exercises the dedup path (Req 12.3).
    - _Requirements: 12.2, 12.3_

- [x] 15. Implement the Scheduler and wire the full cycle
  - [x] 15.1 Implement the `Scheduler` and cycle orchestration
    - Implement `run_cycle(source_list)` invoking the six stages in order and returning `CycleOutcome` (SKIPPED_NO_CHANGE | ACTIVATED | FAILED with failed_stage/cause/version_id).
    - Trigger per configured `Source_List` on a configurable, bounded interval defaulting to sub-daily, realized with Spring `@Scheduled` (no OS cron); record outcomes so the next tick retries after failure.
    - Ensure every stage failure produces an observable outcome naming the failed stage, and a fresh cycle restarts from `obtain` reading only the source (no intermediate artifacts).
    - _Requirements: 1.1, 11.1, 11.2, 11.3, 11.5_

- [x] 16. Checkpoint - end-to-end pipeline
  - Ensure all tests pass, ask the user if questions arise.

- [x] 17. Implement the read-only Query API over the CURRENT version
  - [x] 17.1 Implement the `QueryApi` component (paginated list + name search)
    - Implement a **read-only** HTTP interface with **Spring Web** over the `Data_Store` with two endpoints, both serving **only** the `CURRENT` version resolved via the `VersionStore`/`Data_Store` pointer (never PREVIOUS/N_MINUS_2/COLD, Req 16.5).
    - `list`: return `In_Scope_Records` from CURRENT with offset/limit pagination (default limit 50, max 1000) and metadata (`total`/`offset`/`limit`); deterministic, stable ordering by `FixedRef` (Req 16.1, 16.2).
    - `search_by_name`: case-insensitive **contains** match on the primary name **or** any alias, from CURRENT, with the same pagination, bounds, ordering, and metadata as `list` (Req 16.3).
    - Return a success response with an empty page and `total` 0 when nothing matches or a `Source_List` has no CURRENT yet (Req 16.4); reject a missing/empty search query as a client error (Req 16.7); reject invalid pagination (negative/non-numeric/`limit` > max) as a client error (Req 16.8).
    - Observe activation atomically — each read resolves fully to either the old or the new CURRENT, never a partial dataset (Req 16.6); never modify any Version, pointer, or record (Req 16.9).
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.5, 16.6, 16.7, 16.8, 16.9_
  - [x] 17.2 Write property test for name-search correctness over CURRENT
    - **Property 19: Name search matches primary name and aliases (case-insensitive contains) over CURRENT**
    - Generate CURRENT record sets and query strings; assert a record is returned iff its primary name or some alias contains the query case-insensitively, and only CURRENT records are ever returned.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 19: Name search matches primary name and aliases (case-insensitive contains) over CURRENT`. Min 100 iterations.
    - **Validates: Requirements 16.3, 16.5**
  - [x] 17.3 Write property test for pagination correctness
    - **Property 20: Pagination is deterministic, complete, and non-overlapping**
    - Generate CURRENT record sets and offset/limit sequences; assert stable `FixedRef` ordering, pages are non-overlapping and cover exactly the matching set with no gaps or duplicates, `total` equals the full match count, and the returned page respects `offset`/`limit`.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 20: Pagination is deterministic, complete, and non-overlapping`. Min 100 iterations.
    - **Validates: Requirements 16.1, 16.2, 16.8**
  - [x] 17.4 Write Query API integration tests against Testcontainers PostgreSQL
    - Against a Testcontainers PostgreSQL `Data_Store` with the real CURRENT pointer: empty/missing query rejected as client error (Req 16.7); invalid pagination rejected (Req 16.8); empty-but-valid page with `total` 0 when no match / no CURRENT (Req 16.4); reads stay consistent during an activation, resolving to old or new CURRENT only (Req 16.6); the API is read-only (Req 16.9); only CURRENT is served, never PREVIOUS/N_MINUS_2/COLD (Req 16.5).
    - _Requirements: 16.4, 16.5, 16.6, 16.7, 16.8, 16.9_

- [x] 18. Integration and smoke tests against real fixtures
  - [x] 18.1 Write end-to-end transform + count-reconciliation tests on real fixtures
    - Run transform over `sdn_advanced.xml` (19,249 profiles / 17,373 in scope) and `cons_advanced.xml` (481 profiles); assert scope counts and Expected_Count reconcile; cross-check `Record_Count` against `sdn.xml` and `sdn.csv`.
    - _Requirements: 4.1, 5.1, 8.1, 8.2_
  - [x] 18.2 Write multi-list dedup integration test
    - Ingest SDN + Consolidated fixtures; assert the 93 known overlaps collapse and the persisted count equals the distinct union.
    - _Requirements: 6.1, 6.2, 6.3, 12.3_
  - [x] 18.3 Write obtain HEAD/GET integration tests against a MockWebServer/WireMock-served fixture
    - Headers read (Req 1.2); HTTPS GET with timeouts (Req 2.1); new CURRENT resolvable within 5s (Req 9.5).
    - _Requirements: 1.2, 2.1, 9.5_
  - [x] 18.4 Write Scheduler smoke test
    - Fires per `Source_List` on the configured interval; interval-bounds validation and sub-daily default.
    - _Requirements: 1.1_
  - [x] 18.5 Write reusable-core structural test
    - A second stub adapter drives the same six stages unchanged.
    - _Requirements: 13.1, 13.2_
  - [x] 18.6 Write failure-observability integration tests
    - Each stage's failure yields an outcome naming that stage (Req 11.2); a fresh cycle after a failed one succeeds reading only the source (Req 11.3).
    - _Requirements: 11.2, 11.3_

- [x] 19. Implement retention over the local raw-snapshot store (optional / pending business decision)
  - [x] 19.1 Implement the `RetentionManager` component and its `apply_after_activation` operation (config-driven)
    - Implement `RetentionManager` as the single component that owns the retention lifecycle (design "### RetentionManager"), holding no policy state of its own — the `RetentionPolicy` is injected configuration.
    - Implement `apply_after_activation(source_list, policy)`, invoked after `publish` completes a window rotation: when retention is **ENABLED**, classify versions displaced past `N_MINUS_2` as COLD and retain them (with their `Raw_Snapshot_Store` file) for the configured `retention_period`, applied **independently per `Source_List`** (Req 14.1, 14.2); when **DISABLED**, discard the displaced version including its raw snapshot file (Req 14.4).
    - Collaborate through `VersionStore.reclassify_cold` (Req 10.5) and the version metadata `HOT` | `COLD` `state` to move displaced versions into COLD; keep the clean separation — window rotation (Req 10.5, owned by `publish`/`VersionStore`) always demotes to COLD, while `RetentionManager` decides retain-for-period vs discard (Req 14).
    - Treat `retention_period` and `preserve` (RAW/MODEL/BOTH) as configuration; commit no fixed values (`retention_period` is a **PENDING** business decision, no fixed default).
    - _Requirements: 14.1, 14.2, 14.4, 10.5_
  - [x] 19.2 Implement `RetentionManager.check_cold_integrity` and wire COLD retention to the raw snapshot file in the `Raw_Snapshot_Store`
    - When retention is enabled and RAW is preserved, the retained raw snapshot for a COLD version is the local **versioned file** already written to the `Raw_Snapshot_Store` (task 13.3), keyed by (`Publish_Date`, `Digest`) — never a PostgreSQL `bytea`/large object. When retention is disabled, discard the displaced version including its raw file (Req 14.4).
    - Implement `check_cold_integrity(version_id) -> IntegrityOutcome`: verify a retained COLD version's stored raw file via `RawSnapshotStore.verify_integrity` (recompute SHA-256 over the stored **file** bytes vs the recorded `Digest`); on mismatch return `FLAGGED_UNUSABLE`, marking the version unusable for reconstruction while preserving the recorded `Digest` for audit (Req 14.5).
    - Leave the check's trigger open (on read / periodic sweep / on demand) — it is a deployment/config decision, not a hard-coded schedule.
    - _Requirements: 14.3, 14.4, 14.5_
  - [x] 19.3 Write property test for retention integrity and reconstruction fidelity
    - **Property 17: Retention integrity and reconstruction fidelity**
    - For COLD versions retained with the raw snapshot **file** in the `Raw_Snapshot_Store`: assert the stored file's SHA-256 equals the recorded `Digest` and re-transform reproduces the recorded model; if the stored file bytes don't hash to the recorded `Digest`, the version is flagged unusable while the `Digest` is preserved.
    - Tag: `Feature: ofac-sanctions-ingestion, Property 17: Retention integrity and reconstruction fidelity`. Min 100 iterations.
    - **Validates: Requirements 14.3, 14.5**
  - [x] 19.4 Write unit tests for retention branches
    - Displaced version discarded when disabled (Req 14.4); classified COLD and retained when enabled (Req 14.1, 14.2).
    - _Requirements: 14.1, 14.2, 14.4_

- [x] 20. Implement future multi-source adapters (future work / optional)
  - [x] 20.1 Implement `UnAdapter` and `EuAdapter` scaffolding
    - Implement UN (no token) and EU (token-based) adapters driving the unchanged six-stage core; `EuAdapter` supplies a token and aborts obtain on a missing/invalid token, retaining the last good version.
    - _Requirements: 13.1, 13.2, 13.3, 13.5_
  - [x] 20.2 Write unit tests for token-based adapter behavior
    - `EuAdapter` attaches a token (Req 13.3) and aborts on a missing token retaining the last good version (Req 13.5).
    - _Requirements: 13.3, 13.5_

- [x] 21. Final checkpoint - ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 22. Performance testing (optional / non-functional)
  - Non-functional performance guards over the functional pipeline (tasks 1–17), matching the design's "Performance testing" subsection. These are guards, not correctness properties — they do **not** replace the 20 correctness properties, they only watch for regressions against the spike's measured baseline.
  - [x] 22.1 Set up a JMH microbenchmark for the parse + transform hotspot
    - Configure the **Java Microbenchmark Harness (JMH)** in a **separate Gradle source set/task**, kept out of the normal test suite (JMH runs are slow), that microbenchmarks the StAX parse + transform over the real `ofac-data/sdn_advanced.xml` fixture (19,249 profiles) — the dominant ~98%-of-cost hotspot.
    - Use the spike's `benchmark.py` timings (~3.9 s processing, ~402 MB peak) as the reference baseline for expected throughput/memory.
    - Note in the task that this is a **non-functional performance guard**, not a functional requirement — cite Req 4 (transform) as the code under measurement.
    - _Requirements: Req 4 (transform) — referenced as the code under measurement; this is a performance guard, not a functional requirement._
  - [x] 22.2 Add a Gatling load/latency test for the Query API endpoints
    - Use **Gatling** to load- and latency-test the Query API list and name-search endpoints; the name-search exercises the case-insensitive **contains** match over the primary name + aliases, relying on the trigram/GIN indexes (task 13.1). Note **k6** as an equivalent alternative.
    - Frame the latency targets against the atomic-activation SLA context (a new `CURRENT` resolvable within 5 s).
    - _Requirements: 16.1, 16.3 (name-search + list endpoints under load); atomic-activation SLA context (Req 9.5) — performance guard, not a functional requirement._
  - [x] 22.3 Add a lightweight JUnit-based time/memory regression guard (optional)
    - Add a cheap **JUnit-based time/memory regression guard** over the real `sdn_advanced.xml` fixture that can run in CI as an early-warning check, distinct from the rigorous JMH benchmark (22.1).
    - Also cover the **atomic-activation SLA**: a new `CURRENT` must be resolvable within 5 s (Req 9.5).
    - _Requirements: 9.5 (atomic-activation SLA); Req 4 (parse+transform timing) — CI regression guard, not a functional requirement._

- [x] 23. Mutation testing (optional / non-functional)
  - Non-functional test-effectiveness measurement, matching the design's "Mutation testing" subsection. The goal is validating that the 20 property tests + example tests actually **catch injected defects**, beyond line coverage. This does **not** replace the correctness properties.
  - [x] 23.1 Configure PITest with pitest-kotlin, scoped to the pure-logic packages
    - Configure **PITest** ([pitest.org](https://pitest.org)) via its Gradle plugin, plus **pitest-kotlin** to cut noise from Kotlin-generated null-checks/synthetics that otherwise surface as equivalent (unkillable) mutants.
    - Scope the mutators to the **pure-logic packages** the properties cover: transform, scope filter, dedup, count reconciliation, version-identity, and the version-pointer state machine.
    - _Requirements: test-effectiveness guard over the pure-logic packages (Properties 1–16, 18–20) — non-functional, no new requirement reference._
  - [x] 23.2 Set an initial mutation-score threshold gate on the pure-logic packages only
    - Set an initial **mutation-score threshold gate** scoped to the pure-logic packages only, keeping the I/O/adapter packages out of the initial gate (real-integration behavior and Kotlin equivalent mutants add noise there).
    - The goal is validating that the 20 property tests + example tests actually catch injected defects.
    - _Requirements: test-effectiveness gate over the pure-logic packages — non-functional, no new requirement reference._

## Notes

- **Stack:** Kotlin on Spring Boot, Gradle (Kotlin DSL); jqwik for property-based tests (stateful mode for the pointer properties), JUnit 5 + kotest assertions for example/unit tests, MockK as the mocking library for unit tests, Testcontainers (PostgreSQL) for DB integration, MockWebServer/WireMock for HTTP obtain tests, StAX for XML parsing, Spring Web for the `Query_API`, and Spring `@Scheduled` for the `Scheduler` (see "Implementation Language"). The stack can be swapped if a different language is chosen; the task/property/requirement mapping is language-independent.
- **Mocking (MockK):** unit tests use **MockK** to isolate collaborators — mock the `SourceAdapter`, the `VersionStore`/`RawSnapshotStore`, and individual stages so a unit under test is exercised in isolation (e.g. the `Scheduler`/cycle orchestration unit tests, the adapter unit tests). Integration tests use **real** components with no mocking of the component under integration: real Testcontainers PostgreSQL with a real Spring `@Transactional` transaction, and MockWebServer/WireMock serving fixtures for HTTP obtain. MockK appears in integration tests only in a pointed role — injecting a failure into a collaborator to exercise the fail-closed paths (Req 7.7, 9.4, 11, 15.7, 15.9), such as the failure-safety property test (task 13.8).
- Tasks marked with `*` are optional sub-tasks (property tests, unit tests, integration/smoke tests, and future/optional features). Core implementation tasks are never optional.
- **Non-functional test suites (optional):** performance testing (**JMH** parse+transform microbenchmark, **Gatling**/k6 Query API load/latency, plus an optional lightweight CI time/memory guard) and mutation testing (**PITest** + **pitest-kotlin** with a mutation-score threshold gate scoped to the pure-logic packages) are added as tasks 22–23. They are non-functional guards that **complement, and do not replace,** the 20 correctness properties.
- **All 20 correctness properties** are implemented as single property-based tests (min 100 iterations), tagged `Feature: ofac-sanctions-ingestion, Property {n}: {text}`. Property mapping: P1→12.3, P2→10.2, P3→3.2, P4→4.2, P5→5.3, P6→5.4, P7→5.5, P8→7.2, P9→7.3, P10→8.4, P11→8.5, P12→8.6, P13→8.7, P14→13.8, P15→13.9, P16→14.2, P17→19.3, P18→13.7, P19→17.2, P20→17.3.
- The version-pointer properties (10, 11, 12, 13, 14) are exercised as **stateful/model-based** property tests against the in-memory reference store (task 8.1).
- **Persistence engine:** a single **local PostgreSQL** `Data_Store` backs the `VersionStore` — the processed `Internal_Model` records, the version metadata (including the `raw_snapshot_path` **pointer** into the local `Raw_Snapshot_Store`), and the CURRENT/PREVIOUS/N_MINUS_2 pointers. The atomic pointer swap (Req 9) is provided by a PostgreSQL transaction. Task 8.1 keeps an in-memory reference implementation as the **model** for the stateful property tests; the concrete `PgVersionStore` (schema + store) is built in task 13 (13.1 schema, 13.2 `PgVersionStore`, 13.5 real-transaction atomic-activation integration test).
- **Raw snapshot store:** each downloaded raw snapshot is written **once** as an immutable **file** in the local versioned `Raw_Snapshot_Store` folder (`FsRawSnapshotStore`, task 13.3), named from (`Publish_Date`, `Digest`); it is **never** stored in the `Data_Store`. Integrity verification recomputes SHA-256 over the stored file bytes vs the recorded `Digest`, and the file path is associated with the version metadata only after that match (Req 15).
- **Query API:** the read-only `Query_API` (task 17) serves only the `CURRENT` version through a paginated list endpoint and a name-search endpoint (case-insensitive contains over primary name + aliases), with offset/limit pagination (default 50, max 1000) and deterministic `FixedRef` ordering (Req 16).
- **Requirements coverage:** Req 1 (12.1, 11.1-scheduler), Req 2 (12.2, 11.1-adapter), Req 3 (10.1), Req 4 (2.1, 5.1–5.2), Req 5 (3.1), Req 6 (4.1), Req 7 (7.1, 13.1–13.2, 13.6, 8.1–8.2), Req 8 (7.1, 8.2), Req 9 (8.2–8.3, 13.2, 13.5), Req 10 (8.2–8.3, 13.2), Req 11 (13.6, 15.1), Req 12 (14.1), Req 13 (11.1, 20.1), Req 14 (19.1–19.2), Req 15 (1.1, 13.1, 13.3, 13.6, 19.2), Req 16 (1.1, 13.1, 17.1).
- **Pending business decisions** (scope default, retention period, preserve form) are implemented as configuration; nothing is hard-coded. The `RetentionManager` component (task 19) owns the retention lifecycle — applying retain-for-period vs discard after window rotation and exposing the COLD integrity check. Retention (task 19) and multi-source UN/EU adapters (task 20) are future/optional work.
- Build order is test-driven: pure logic first (tasks 2–8, carrying most property tests), then I/O (tasks 10–15, including the PostgreSQL `Data_Store` and the file-based `Raw_Snapshot_Store`), then the read-only `Query_API` (task 17), then integration against real fixtures (task 18).

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["2.1", "2.2"] },
    { "id": 2, "tasks": ["3.1", "4.1", "7.1", "8.1", "10.1", "14.1"] },
    { "id": 3, "tasks": ["3.2", "4.2", "5.1", "7.2", "7.3", "7.4", "8.2", "10.2", "10.3", "13.1", "13.3", "14.2", "14.3"] },
    { "id": 4, "tasks": ["5.2", "8.3", "11.1", "13.4"] },
    { "id": 5, "tasks": ["5.3", "5.4", "5.5", "5.6", "8.4", "8.5", "8.6", "8.7", "8.8", "11.2", "12.1", "12.2", "13.2"] },
    { "id": 6, "tasks": ["12.3", "12.4", "13.5", "13.6"] },
    { "id": 7, "tasks": ["13.7", "13.8", "13.9", "15.1"] },
    { "id": 8, "tasks": ["17.1"] },
    { "id": 9, "tasks": ["17.2", "17.3", "17.4", "18.1", "18.2", "18.3", "18.4", "18.5", "18.6", "19.1"] },
    { "id": 10, "tasks": ["19.2", "20.1"] },
    { "id": 11, "tasks": ["19.3", "19.4", "20.2"] },
    { "id": 12, "tasks": ["22.1", "22.2", "22.3", "23.1"] },
    { "id": 13, "tasks": ["23.2"] }
  ]
}
```
