# Requirements Document

## Introduction

This feature builds an ingestion pipeline for the OFAC (Office of Foreign Assets Control) sanctions lists, following the recommended approach from the technical spike (`spike-ofac.md`, "Alternativa A — Job batch com versão imutável"). The pipeline periodically detects new OFAC publications, downloads the full snapshot when the content changes, validates and transforms it into an internal normalized model (scoped to individuals and entities), persists it as a new immutable version, and atomically activates that version as the current one. It maintains a fixed window of operational versions per list with instant rollback.

The design is technology-agnostic: the pipeline is expressed as a sequence of stages (obtain → validate → transform → version → persist → publish) so it can be reused for additional sanctions sources (UN, EU) by swapping only the per-source read/mapping adapter.

Each downloaded raw snapshot is preserved on disk in a local versioned folder (the Raw_Snapshot_Store) under a file name derived from Publish_Date and Digest, rather than inside the database. The processed Internal_Model is stored in a PostgreSQL database (the Data_Store), and a read-only Query_API exposes the CURRENT data through a paginated list endpoint and a name-search endpoint.

Several business decisions remain open (list scope, historical retention, what to preserve). These are captured explicitly as open questions and assumptions rather than resolved arbitrarily.

Reference: technical spike at `/Users/bastos/Workspace/spike-sanction-list/spike-ofac.md`.

## Glossary

- **OFAC**: U.S. Office of Foreign Assets Control, publisher of the sanctions lists.
- **SLS (Sanctions_List_Service)**: OFAC's public download service exposing list exports over HTTPS without authentication.
- **SDN_List**: Specially Designated Nationals and Blocked Persons list; represents a full asset block.
- **Consolidated_List**: OFAC non-SDN list aggregating sectoral sanctions sub-lists (e.g., NS-MBS, NS-CMIC, SSI).
- **Source_List**: A single sanctions list handled by the pipeline (e.g., SDN, Consolidated). Each Source_List is versioned independently.
- **Advanced_XML**: OFAC's richest export format (based on the UN model), used as the canonical ingestion source.
- **Snapshot**: A complete republication of an entire Source_List. OFAC publishes only full snapshots.
- **Publish_Date**: The publication date reported by OFAC inside the file (`<Publish_Date>`).
- **Record_Count**: The record count OFAC reports inside the file (`<Record_Count>`).
- **Digest**: The SHA-256 hash of a downloaded file, available via the HTTP `Digest` response header and recomputable locally; used for change detection, integrity validation, and version identity.
- **Last_Modified**: The HTTP `Last-Modified` response header indicating the publication time.
- **FixedRef**: The stable identifier OFAC assigns to each record (equivalent to legacy `uid`), persistent across publications; used for deduplication and cross-version identity.
- **Ingestion_Pipeline**: The system under specification, composed of the ordered stages obtain → validate → transform → version → persist → publish.
- **Internal_Model**: The normalized representation the pipeline produces from a parsed snapshot.
- **Version**: A processed, persisted state of a Source_List at a point in time, identified by Publish_Date + Digest.
- **CURRENT**: The version pointer designating the version currently served operationally.
- **PREVIOUS**: The prior operational version (the version that was CURRENT before the latest activation).
- **N_MINUS_2**: The version one step older than PREVIOUS.
- **HOT_Version**: One of the three most recent active operational versions (CURRENT, PREVIOUS, N_MINUS_2).
- **COLD_Version**: A version older than N_MINUS_2, retained only for history/audit if retention is enabled.
- **In_Scope_Record**: A record whose entity type is Individual or Entity.
- **Out_Of_Scope_Record**: A record whose entity type is Vessel or Aircraft.
- **Scheduler**: The component that triggers periodic change-detection cycles (polling).
- **Raw_Snapshot_Store**: A local filesystem folder where each downloaded raw snapshot is saved under a versioned file name derived from (Publish_Date, Digest); replaces any in-database storage of the raw snapshot.
- **Data_Store**: The PostgreSQL database holding the processed Internal_Model records that back the Query_API.
- **Query_API**: The HTTP interface exposing read-only endpoints over the CURRENT version of the persisted Internal_Model.

## Requirements

### Requirement 1: Periodic change detection (polling)

**User Story:** As an operator, I want the pipeline to periodically check each OFAC list for new publications, so that new sanctions data is ingested without manual intervention and without notifications from the source.

#### Acceptance Criteria

1. THE Scheduler SHALL trigger a change-detection cycle for each configured Source_List on a configurable polling interval, where the interval is configurable within a bounded range and defaults to a sub-daily interval (multiple checks per day).
2. WHEN a change-detection cycle starts for a Source_List, THE Ingestion_Pipeline SHALL issue an HTTP HEAD request to that Source_List export endpoint to read the Last_Modified and Digest response headers, completing the HEAD request within 30 seconds.
3. WHEN the Digest returned by the HEAD request equals the Digest of the most recently ingested Version for that Source_List, THE Ingestion_Pipeline SHALL end the cycle without downloading the snapshot.
4. WHEN the Digest returned by the HEAD request differs from the Digest of the most recently ingested Version for that Source_List, THE Ingestion_Pipeline SHALL proceed to download the full snapshot.
5. IF the HEAD response omits the Digest header, THEN THE Ingestion_Pipeline SHALL fall back to comparing the Publish_Date and Record_Count read from the snapshot body to determine whether a change occurred.
6. IF the HEAD request fails to connect or exceeds the 30-second timeout, THEN THE Ingestion_Pipeline SHALL end the cycle without changing the CURRENT version and SHALL record the failure so the next scheduled cycle retries.

### Requirement 2: Download full snapshot

**User Story:** As an operator, I want the pipeline to download the complete list snapshot when a change is detected, so that ingestion always works from the authoritative full dataset.

#### Acceptance Criteria

1. WHEN a change has been detected for a Source_List, THE Ingestion_Pipeline SHALL download the full snapshot from that Source_List Advanced_XML export endpoint over HTTPS, completing the download within 120 seconds.
2. WHILE downloading a snapshot, THE Ingestion_Pipeline SHALL follow up to 5 consecutive HTTP redirects.
3. THE Ingestion_Pipeline SHALL download snapshots without sending authentication credentials for the OFAC Source_List endpoints.
4. WHEN a snapshot download completes, THE Ingestion_Pipeline SHALL verify that the downloaded snapshot is complete and intact before accepting it for ingestion.
5. IF a download fails to establish a connection, receives a non-success HTTP response, exceeds 5 consecutive redirects, exceeds the 120-second download timeout, or fails the completeness-and-integrity verification, THEN THE Ingestion_Pipeline SHALL discard the partial or unverified download, leave the CURRENT version unchanged, and end the cycle with an error indication that the download did not succeed.

### Requirement 3: Validate snapshot integrity

**User Story:** As a compliance owner, I want each downloaded snapshot validated before processing, so that corrupt or malformed data never reaches the operational dataset.

#### Acceptance Criteria

1. WHEN a snapshot download completes, THE Ingestion_Pipeline SHALL compute the SHA-256 Digest of the downloaded file and compare the computed Digest to the Digest advertised by the source before any parsing occurs.
2. IF the source does not advertise a Digest for the downloaded snapshot, THEN THE Ingestion_Pipeline SHALL reject the snapshot, leave the CURRENT version unchanged, and record a validation-failure indication identifying the absent-Digest cause.
3. IF the computed Digest does not match the source-advertised Digest, THEN THE Ingestion_Pipeline SHALL reject the snapshot, leave the CURRENT version unchanged, and record a validation-failure indication identifying the Digest-mismatch cause.
4. WHEN the computed Digest matches the source-advertised Digest, THE Ingestion_Pipeline SHALL verify that the snapshot is well-formed Advanced_XML before parsing its records.
5. IF the snapshot is not well-formed Advanced_XML, THEN THE Ingestion_Pipeline SHALL reject the snapshot, leave the CURRENT version unchanged, and record a validation-failure indication identifying the malformed-Advanced_XML cause.

### Requirement 4: Parse and transform to the internal model

**User Story:** As a developer, I want the pipeline to parse the Advanced XML and produce a normalized internal model, so that downstream consumers work with consistent, resolved records regardless of source quirks.

#### Acceptance Criteria

1. WHEN a validated snapshot is available, THE Ingestion_Pipeline SHALL parse each record into an Internal_Model entry carrying the FixedRef, entity type, primary name, aliases, addresses, documents, nationalities, citizenships, birth dates/places, sanction programs, remarks, and relationships.
2. WHEN transforming a record, THE Ingestion_Pipeline SHALL resolve Advanced_XML identifier references (features, documents, sanction measures, and relationships) to their referenced records.
3. THE Ingestion_Pipeline SHALL process snapshot content as UTF-8 so that non-ASCII characters in names and addresses are preserved exactly.
4. THE Ingestion_Pipeline SHALL represent aliases, addresses, documents, nationalities, citizenships, birth dates, and relationships as multi-valued (zero-or-more) attributes of an Internal_Model entry, and SHALL require at least one sanction program per entry.
5. WHERE a record has zero aliases, THE Ingestion_Pipeline SHALL produce an Internal_Model entry using its primary name without failing.
6. WHERE a birth date is incomplete (year only or a date period), THE Ingestion_Pipeline SHALL preserve the partial birth date in the Internal_Model without requiring a complete date.
7. IF an identifier reference in a record cannot be resolved to a referenced record, THEN THE Ingestion_Pipeline SHALL record a diagnostic identifying the record and the unresolved reference, and SHALL continue transforming the remaining records.
8. IF a record cannot be parsed into an Internal_Model entry, THEN THE Ingestion_Pipeline SHALL record a diagnostic identifying the record and SHALL fail the transformation stage so that no partial Version is activated.

### Requirement 5: Scope filter (individuals and entities only)

**User Story:** As a compliance owner, I want the pipeline to keep only individuals and entities, so that vessels and aircraft (out of the intended scope) are excluded consistently.

#### Acceptance Criteria

1. WHEN transforming a snapshot, THE Ingestion_Pipeline SHALL classify each source record by its entity type field (sdnType / PartySubTypeID) as an In_Scope_Record when the value is Individual or Entity, and SHALL include every In_Scope_Record in the Internal_Model.
2. WHEN transforming a snapshot, THE Ingestion_Pipeline SHALL classify each source record with entity type field (sdnType / PartySubTypeID) value Vessel or Aircraft as an Out_Of_Scope_Record, and SHALL exclude every Out_Of_Scope_Record from the Internal_Model such that the Internal_Model contains zero records of entity type Vessel or Aircraft.
3. IF a source record has an entity type field (sdnType / PartySubTypeID) that is missing, empty, or not one of the recognized values (Individual, Entity, Vessel, Aircraft), THEN THE Ingestion_Pipeline SHALL exclude that record from the Internal_Model and SHALL record a diagnostic entry indicating the record identifier and the unrecognized entity type, without aborting transformation of the remaining records.

### Requirement 6: Deduplication by stable identifier

**User Story:** As a compliance owner, I want records that appear in more than one list represented once, so that the persisted dataset does not double-count overlapping records.

#### Acceptance Criteria

1. WHEN combining records across Source_Lists into a unified persisted dataset, THE Ingestion_Pipeline SHALL persist exactly one record per distinct FixedRef, with no duplicate records for the same FixedRef.
2. WHERE the same FixedRef appears in both the SDN_List and the Consolidated_List, THE Ingestion_Pipeline SHALL retain the SDN_List representation as the governing record for that FixedRef and SHALL NOT persist a separate Consolidated_List record for that FixedRef.
3. WHEN both the SDN_List and the Consolidated_List are ingested, THE Ingestion_Pipeline SHALL produce a persisted record count equal to the count of distinct FixedRefs across both lists (the distinct union), not the sum of the two lists' counts.

> Note: Requirement 6 applies only when the configured scope includes more than one Source_List (see Requirement 12, open question on list scope).

### Requirement 7: Persist as a new immutable version

**User Story:** As an operator, I want each successful ingestion stored as a new immutable version, so that historical states are never mutated and rollback is safe.

#### Acceptance Criteria

1. WHEN transformation of a snapshot completes, THE Ingestion_Pipeline SHALL persist all resulting Internal_Model records as a new Version, and SHALL treat the Version as created only after every transformed record has been persisted.
2. THE Ingestion_Pipeline SHALL identify each Version by the combination of the source Publish_Date and a SHA-256 Digest of the snapshot content.
3. IF two publications share the same Publish_Date, THEN THE Ingestion_Pipeline SHALL distinguish them by their differing SHA-256 Digest and SHALL persist each as a separate Version.
4. THE Ingestion_Pipeline SHALL record the Version identifier on every persisted record so that each record is associated with the Version in which it was ingested.
5. ONCE a Version is persisted, THE Ingestion_Pipeline SHALL treat that Version as immutable and SHALL NOT modify, delete, or add records within that Version.
6. WHILE a new Version is being persisted, THE Ingestion_Pipeline SHALL keep the new Version isolated from the CURRENT Version and SHALL NOT expose the new Version for operational use until activation.
7. IF persistence of the new Version fails before activation, THEN THE Ingestion_Pipeline SHALL discard the partially persisted new Version, leave the CURRENT Version unchanged, and produce an error indication that persistence did not complete.

### Requirement 8: Validate result before activation

**User Story:** As a compliance owner, I want the persisted result checked against the source-reported count, so that an incomplete parse is never activated.

#### Acceptance Criteria

1. WHEN a new Version has been persisted, THE Ingestion_Pipeline SHALL derive an Expected_Count equal to the snapshot Record_Count minus the count of Out_Of_Scope_Records, minus the count of shared-FixedRef overlaps when the configured scope includes more than one Source_List.
2. WHEN a new Version has been persisted, THE Ingestion_Pipeline SHALL compare the count of persisted In_Scope_Records in the new Version (after deduplication) against the derived Expected_Count and require exact equality.
3. IF the persisted count does not exactly equal the Expected_Count, THEN THE Ingestion_Pipeline SHALL reject the new Version, leave the CURRENT version unchanged, and record a validation-failure indication.
4. IF the snapshot Record_Count is absent or non-numeric, THEN THE Ingestion_Pipeline SHALL reject the new Version, leave the CURRENT version unchanged, and record a validation-failure indication identifying the missing or invalid Record_Count.

### Requirement 9: Atomic activation

**User Story:** As an operator, I want a new version activated atomically, so that consumers never see a partial dataset or a window with no active version.

#### Acceptance Criteria

1. WHEN a new Version passes result validation, THE Ingestion_Pipeline SHALL activate it by atomically repointing CURRENT to the new Version, such that no intermediate state is observable and CURRENT resolves fully to either the prior Version or the new Version.
2. WHILE activation is in progress, THE Ingestion_Pipeline SHALL keep exactly one CURRENT version resolvable to consumers at every instant, such that the count of active CURRENT versions is never zero.
3. WHEN activation completes, THE Ingestion_Pipeline SHALL designate the previously CURRENT version as PREVIOUS and the previously PREVIOUS version as N_MINUS_2, and SHALL keep all three consumer-resolvable.
4. IF the atomic repoint fails, THEN THE Ingestion_Pipeline SHALL preserve the prior CURRENT, PREVIOUS, and N_MINUS_2 designations unchanged and emit an error indication.
5. WHEN activation completes, THE Ingestion_Pipeline SHALL make the new CURRENT version resolvable to consumers within 5 seconds.

### Requirement 10: Operational version window and rollback

**User Story:** As an operator, I want a fixed window of recent versions with instant rollback, so that I can revert a bad activation without reprocessing.

#### Acceptance Criteria

1. THE Ingestion_Pipeline SHALL maintain at most three HOT operational versions per Source_List, designated CURRENT, PREVIOUS, and N_MINUS_2, where CURRENT is the most recently activated Version, PREVIOUS the second most recent, and N_MINUS_2 the third most recent.
2. THE Ingestion_Pipeline SHALL version each Source_List on an independent version line, such that activating or rolling back one Source_List changes no Version or pointer of any other Source_List.
3. WHEN an operator requests a rollback for a Source_List that has a PREVIOUS version, THE Ingestion_Pipeline SHALL repoint CURRENT to the PREVIOUS version by pointer move alone, without downloading, parsing, transforming, or reprocessing any snapshot, and without mutating the content of any Version.
4. IF an operator requests a rollback for a Source_List that has no PREVIOUS version, THEN THE Ingestion_Pipeline SHALL reject the request, leave the CURRENT pointer unchanged, and return an indication that no prior version is available to roll back to.
5. WHEN a new Version is activated for a Source_List and more than three HOT versions would result, THE Ingestion_Pipeline SHALL reclassify every Version older than N_MINUS_2 as a COLD_Version and retain each COLD_Version for audit without deleting or mutating its content.

### Requirement 11: Failure recovery via full reprocessing

**User Story:** As an operator, I want any failed cycle to be safely re-runnable from the start, so that failures never corrupt the operational dataset and no checkpointing is required.

#### Acceptance Criteria

1. IF any stage (download, validation, transformation, persistence, or activation) of a change-detection or ingestion cycle fails before atomic activation completes, THEN THE Ingestion_Pipeline SHALL leave the CURRENT Version unchanged and SHALL preserve it as the operationally served Version.
2. IF any stage of a cycle fails, THEN THE Ingestion_Pipeline SHALL record an observable failure outcome indicating which stage failed and SHALL mark the cycle as failed.
3. WHEN a subsequent cycle starts after a prior cycle was marked failed, THE Ingestion_Pipeline SHALL reprocess the full snapshot from the first stage without reading or requiring any intermediate checkpoint or partial artifact from the failed cycle.
4. WHEN a cycle reprocesses the full snapshot for a given publication and completes successfully, THE Ingestion_Pipeline SHALL produce a Version whose persisted content is identical to the Version that a first-attempt successful cycle for the same publication would produce.
5. IF a failure occurs before atomic activation, THEN THE Ingestion_Pipeline SHALL NOT expose any partially persisted Version as CURRENT and SHALL discard any partially persisted artifacts from the failed cycle before or during the next cycle.

### Requirement 12: Configurable list scope

**User Story:** As a compliance owner, I want to configure which OFAC lists the pipeline ingests, so that the deployment can match either a max-risk MVP or full compliance coverage.

#### Acceptance Criteria

1. THE Ingestion_Pipeline SHALL accept a scope configuration whose only valid values are SDN_List only, or SDN_List and Consolidated_List together.
2. WHERE the configuration selects the SDN_List only, THE Ingestion_Pipeline SHALL ingest the SDN_List and SHALL NOT ingest or persist any Consolidated_List-sourced record.
3. WHERE the configuration selects the SDN_List and the Consolidated_List, THE Ingestion_Pipeline SHALL ingest both Source_Lists and SHALL persist exactly one record per distinct FixedRef across both lists per Requirement 6.
4. IF the configuration selects the Consolidated_List only, THEN THE Ingestion_Pipeline SHALL reject the configuration, ingest nothing, and surface an error indicating that Consolidated-only scope is not permitted.
5. IF the scope configuration is absent, empty, or not a recognized value, THEN THE Ingestion_Pipeline SHALL reject the configuration, ingest nothing, and surface an error indicating an invalid scope configuration.

> **OPEN QUESTION (business decision, pending):** Default list scope is undecided. The spike recommends SDN + Consolidated for full compliance coverage, and SDN-only for a maximum-risk MVP (see `spike-ofac.md`, "Quando usar cada lista"). This requirement makes scope configurable but does not fix the default. **Assumption until decided:** scope is configurable with no hard-coded default committed here.

### Requirement 13: Reusable multi-source core

**User Story:** As a developer, I want the pipeline core to be source-agnostic, so that additional sanctions sources (UN, EU) can be added by supplying a per-source adapter rather than rewriting the pipeline.

#### Acceptance Criteria

1. THE Ingestion_Pipeline SHALL expose the common stages obtain, validate, transform, version, persist, and publish as source-independent stages, such that adding a new Source_List requires changes only within that source's adapter and no changes to these six stages.
2. WHEN the Ingestion_Pipeline processes a Source_List, THE Ingestion_Pipeline SHALL read and map that Source_List through its per-source adapter, which encapsulates that source's obtain (including any authentication) and field mapping to the common internal representation.
3. WHERE a Source_List requires an authentication token to download, THE Ingestion_Pipeline SHALL supply that token through the source adapter without altering the obtain, validate, transform, version, persist, or publish stages.
4. IF a source adapter fails to map a required field of a Source_List to the common internal representation, THEN THE Ingestion_Pipeline SHALL reject that Source_List, retain the last successfully persisted version unchanged, and produce an error indication identifying the source and the unmapped field.
5. WHERE a Source_List requires an authentication token to download, IF the download is rejected due to a missing or invalid token, THEN THE Ingestion_Pipeline SHALL abort that source's obtain stage, retain the last successfully persisted version unchanged, and produce an error indication identifying the source and the authentication failure.

> Note: UN and EU publish full XML snapshots with no delta, matching the OFAC pattern; the EU download requires a token (see `spike-ofac.md`, "Comparativo multi-fonte").

### Requirement 14: Historical retention (pending policy)

**User Story:** As a compliance owner, I want the pipeline to retain historical snapshots according to a defined policy, so that past list states can be reconstructed for audit even though OFAC keeps no history.

#### Acceptance Criteria

1. WHERE historical retention is enabled, WHEN a version falls outside the three most recent active versions (CURRENT, PREVIOUS, N_MINUS_2) of its list, THE Ingestion_Pipeline SHALL classify that version as a COLD_Version and retain it for the configured retention period.
2. WHERE historical retention is enabled, THE Ingestion_Pipeline SHALL retain each COLD_Version until its retention period elapses, applying retention independently per list.
3. WHERE historical retention is enabled, THE Ingestion_Pipeline SHALL retain each COLD_Version's raw snapshot as a file in the Raw_Snapshot_Store (per Requirement 15), so that the publication's list state can be reconstructed faithfully (identical to the published state) for audit, uniquely identified by its source Publish_Date and SHA-256 Digest.
4. WHERE historical retention is disabled, WHEN a version falls outside the three most recent active versions of its list, THE Ingestion_Pipeline SHALL discard that version, including its raw snapshot file in the Raw_Snapshot_Store.
5. IF a retained COLD_Version's stored raw snapshot file fails an integrity check where the SHA-256 recomputed over the stored file bytes does not equal the recorded Digest, THEN THE Ingestion_Pipeline SHALL flag that COLD_Version as unusable for reconstruction and preserve the recorded Digest so the failure is auditable.

> **OPEN QUESTION (business decision, pending):** OFAC keeps no history; preserving history is entirely our responsibility (see `spike-ofac.md`, section 11). The preservation form is now decided; the retention period remains open:
> - **DECIDED:** The raw snapshot is preserved as a file in a local versioned folder (the Raw_Snapshot_Store), with a file name derived from (Publish_Date, Digest) — see Requirement 15. The raw snapshot is no longer stored inside the database. Faithful reconstruction of a past OFAC list state relies on this preserved raw file, since OFAC does not republish past versions.
> - **PENDING:** Minimum retention period for COLD_Versions.
>
> **Assumption until decided:** retention is treated as a configurable, optional capability; no fixed retention period is committed here.

### Requirement 15: Persist raw snapshot to a versioned local folder

**User Story:** As an operator, I want each downloaded raw snapshot saved to a local folder under a versioned file name, so that every publication is preserved on disk and reconstructable.

#### Acceptance Criteria

1. WHEN a snapshot passes integrity validation (per Requirement 3) and before the corresponding Version is activated as CURRENT (per Requirement 9), THE Ingestion_Pipeline SHALL write the complete raw snapshot bytes to the Raw_Snapshot_Store under a file name derived from the combination of Publish_Date and Digest.
2. THE Ingestion_Pipeline SHALL derive the raw snapshot file name so that exactly one raw snapshot file exists per distinct (Publish_Date, Digest) pair, such that two publications sharing the same Publish_Date but differing in Digest (per Requirements 7.2 and 7.3) map to two distinct file names and neither overwrites the other.
3. WHILE a raw snapshot file is being written, THE Ingestion_Pipeline SHALL keep the in-progress file invisible as a persisted raw snapshot until all bytes have been written, such that a partially written file is never associated with a Version nor used for reconstruction.
4. ONCE a raw snapshot file has been fully written to the Raw_Snapshot_Store, THE Ingestion_Pipeline SHALL treat that file as immutable and SHALL NOT modify its contents.
5. WHEN a raw snapshot file has been fully written, THE Ingestion_Pipeline SHALL verify that the SHA-256 recomputed over the stored file bytes equals the recorded Digest before associating the file with the Version.
6. WHEN the SHA-256 recomputed over the stored file bytes equals the recorded Digest, THE Ingestion_Pipeline SHALL associate the stored file path with the corresponding Version metadata.
7. IF the SHA-256 recomputed over the stored file bytes does not equal the recorded Digest, THEN THE Ingestion_Pipeline SHALL discard the stored raw snapshot file, leave the CURRENT Version unchanged, and surface an error indication identifying the stored-file integrity-mismatch cause, consistent with the fail-closed model of Requirement 11.
8. THE Ingestion_Pipeline SHALL store the raw snapshot as a file in the Raw_Snapshot_Store and SHALL NOT store the raw snapshot inside the Data_Store.
9. IF writing the raw snapshot file to the Raw_Snapshot_Store fails, THEN THE Ingestion_Pipeline SHALL discard any partially written file, leave the CURRENT Version unchanged, and surface an error indication that the raw snapshot was not persisted, consistent with the fail-closed model of Requirement 11.

### Requirement 16: Query API — paginated list and name search over the CURRENT version

**User Story:** As a compliance analyst, I want to list and search the current sanctions data through an API, so that I can screen names and browse records.

#### Acceptance Criteria

1. WHEN a client requests the list endpoint, THE Query_API SHALL return In_Scope_Records from the CURRENT version using offset/limit pagination, applying a configurable default page size of 50 and a bounded maximum page size of 1000, and SHALL return pagination metadata including total count, offset, and limit alongside the page.
2. THE Query_API SHALL return paginated results in a deterministic, stable order (for example, ordered by FixedRef) so that repeated requests with the same offset and limit return the same records in the same order.
3. WHEN a client requests the name-search endpoint with a non-empty query string, THE Query_API SHALL match the query string against each record's primary name and its aliases using case-insensitive partial (contains) matching, and SHALL return matching In_Scope_Records from the CURRENT version using the same offset/limit pagination, bounds, ordering, and metadata as the list endpoint.
4. WHEN a request to the list or name-search endpoint matches no records (including when a configured Source_List has no CURRENT version yet), THE Query_API SHALL return a success response containing an empty result page with a total count of zero, not an error.
5. THE Query_API SHALL serve only the CURRENT version of each configured Source_List and SHALL NOT return records from the PREVIOUS, N_MINUS_2, or any COLD_Version.
6. WHILE an activation is repointing CURRENT (per Requirement 9), THE Query_API SHALL serve each read consistently from either the old CURRENT or the new CURRENT and SHALL NOT return a partial dataset.
7. IF the name-search query parameter is missing or empty, THEN THE Query_API SHALL reject the request with a client error and SHALL NOT return results.
8. IF pagination parameters are invalid (negative, non-numeric, or a limit exceeding the maximum page size), THEN THE Query_API SHALL reject the request with a client error and SHALL NOT return a partial or invalid page.
9. THE Query_API SHALL be read-only and SHALL NOT modify any Version, pointer, or record.

## Open Questions and Assumptions Summary

The following business decisions are intentionally left open and flagged for stakeholder resolution (not invented here):

1. **List scope** (Requirement 12): SDN only (max-risk MVP) vs SDN + Consolidated (full compliance coverage). The spike leans toward SDN + Consolidated for regulatory completeness.
2. **Historical retention period** (Requirement 14): undecided; OFAC provides no history, so any retention is our responsibility.
3. **What to preserve** (Requirement 14): decided — the raw snapshot is preserved as a file in a local versioned folder (Raw_Snapshot_Store), file name derived from (Publish_Date, Digest), per Requirement 15; the processed Internal_Model is stored in the Data_Store. Only the retention period (item 2) remains open.

Technical parameters treated as configuration rather than open business questions: polling interval (Requirement 1), source endpoints and per-source adapters (Requirement 13).
