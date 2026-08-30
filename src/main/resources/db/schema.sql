-- =============================================================================
-- OFAC Sanctions Ingestion — PostgreSQL schema (task 13.1)
--
-- Maps the VersionStore / Data_Store onto local PostgreSQL, per design.md
-- "PostgreSQL schema sketch". Three tables:
--
--   versions  — one insert-only row per immutable version
--   records   — persisted Internal_Model entries, each stamped with version_id
--   pointers  — one row per source_list holding current / previous / n_minus_2
--
-- Immutability (Req 7.5): rows inside a persisted version are insert-only —
-- never UPDATE'd or DELETE'd. Version lifecycle transitions (HOT -> COLD) and the
-- pointer swap happen on the `versions.state` column and the `pointers` row only,
-- never by mutating record rows. The raw snapshot bytes are NOT stored here:
-- `versions.raw_snapshot_path` is a filesystem path into the local
-- Raw_Snapshot_Store, populated only after the stored file's SHA-256 matches the
-- recorded Digest (Req 15.6, 15.8).
--
-- Multi-valued attribute modeling — IMPLEMENTATION CHOICE: JSONB.
--   The multi-valued attributes of an Internal_Model entry (aliases, addresses,
--   documents, nationalities, citizenships, birth_dates, sanction_programs,
--   remarks, relationships) are stored as JSONB columns on the `records` row
--   rather than as child tables. Rationale for this MVP: the in-scope volume is
--   small (~19,730 records; design.md Overview), each entry is read/written as a
--   whole unit (write-once at persist, read whole by the Query API), and JSONB
--   keeps the persistence layer a single insert per record with no join fan-out.
--   Name search over aliases (Req 16.3) is supported by a generated, lowercased
--   searchable text column (`alias_search`) with a trigram GIN index — see below.
-- =============================================================================

-- pg_trgm powers the case-insensitive CONTAINS (substring) matching the
-- Query_API name search needs (Req 16.3) via GIN trigram indexes.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- -----------------------------------------------------------------------------
-- versions — one insert-only row per immutable version (Req 7.4, 7.5, 7.6).
--
-- version_id = (publish_date, digest) (Req 7.2, 7.3): the digest disambiguates
-- same-day publications, so both are the composite primary key.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS versions (
    publish_date        DATE        NOT NULL,
    -- lowercase hex SHA-256 of the raw snapshot (64 chars); part of version_id.
    digest              CHAR(64)    NOT NULL,

    -- 'SDN' | 'CONSOLIDATED' — each list versions on an independent line (Req 10.2).
    source_list         TEXT        NOT NULL
        CONSTRAINT versions_source_list_chk CHECK (source_list IN ('SDN', 'CONSOLIDATED')),

    -- Reconciliation counts (Req 8).
    record_count        INTEGER     NOT NULL,   -- source-reported <Record_Count>
    out_of_scope_count  INTEGER     NOT NULL,   -- vessels + aircraft excluded (Req 5, 8.1)
    overlap_count       INTEGER     NOT NULL,   -- shared FixedRefs removed by dedup (Req 6, 8.1)
    expected_count      INTEGER     NOT NULL,   -- record_count - out_of_scope - overlap (Req 8.1)
    persisted_count     INTEGER     NOT NULL,   -- in-scope, post-dedup; must equal expected_count (Req 8.2)

    -- HOT (CURRENT/PREVIOUS/N_MINUS_2) or COLD (displaced past the window) (Req 10, 14).
    state               TEXT        NOT NULL
        CONSTRAINT versions_state_chk CHECK (state IN ('HOT', 'COLD')),

    ingested_at         TIMESTAMPTZ NOT NULL,

    -- Filesystem path into the local Raw_Snapshot_Store (NOT a bytea/large object).
    -- Nullable; populated only after the stored file's SHA-256 matches the recorded
    -- Digest (Req 15.5, 15.6). The raw bytes live on disk, never in the DB (Req 15.8).
    raw_snapshot_path   TEXT        NULL,

    -- Result of the last integrity check over the stored raw FILE bytes vs the
    -- recorded Digest; NULL when never checked (Req 14.5, 15.5).
    integrity_ok        BOOLEAN     NULL,

    CONSTRAINT versions_pk PRIMARY KEY (publish_date, digest)
);

-- Fetch the most recent version per list (last_ingested) and enumerate a list's
-- versions by recency for window rotation / COLD reclassification (Req 9, 10).
CREATE INDEX IF NOT EXISTS versions_source_list_ingested_at_idx
    ON versions (source_list, ingested_at DESC);

-- -----------------------------------------------------------------------------
-- records — persisted Internal_Model entries, each stamped with its version_id
-- (Req 7.4). Insert-only; immutable once written (Req 7.5).
--
-- Multi-valued attributes are JSONB (see header note). Two derived, lowercased
-- columns back the case-insensitive CONTAINS name search (Req 16.3):
--   primary_name_lower — the primary name, folded to lower case
--   alias_search       — every alias name concatenated, folded to lower case
-- Both get trigram GIN indexes so `col LIKE '%needle%'` (case-insensitive) is
-- index-assisted. The name search matches primary name OR any alias.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS records (
    -- Surrogate key: a record is (version_id, fixed_ref) unique, but a stable
    -- single-column id keeps foreign-key-free child access simple if ever needed.
    id                  BIGINT      GENERATED ALWAYS AS IDENTITY,

    -- version_id = (publish_date, digest); every record is stamped with the
    -- version it belongs to (Req 7.4).
    publish_date        DATE        NOT NULL,
    digest              CHAR(64)    NOT NULL,

    -- Stable OFAC id (== uid); dedup + cross-version key (Req 4.1, 6).
    fixed_ref           TEXT        NOT NULL,

    -- In-scope only: 'Individual' | 'Entity' (Req 5). Never Vessel/Aircraft.
    entity_type         TEXT        NOT NULL
        CONSTRAINT records_entity_type_chk CHECK (entity_type IN ('Individual', 'Entity')),

    -- Required; used as the display name when a record has zero aliases (Req 4.5).
    primary_name        TEXT        NOT NULL,

    -- Multi-valued attributes as JSONB (implementation choice — see header).
    -- Arrays default to an empty JSON array; sanction_programs is 1..N (Req 4.4)
    -- but that cardinality is enforced in the domain model, not the DDL.
    aliases             JSONB       NOT NULL DEFAULT '[]'::jsonb,
    addresses           JSONB       NOT NULL DEFAULT '[]'::jsonb,
    documents           JSONB       NOT NULL DEFAULT '[]'::jsonb,
    nationalities       JSONB       NOT NULL DEFAULT '[]'::jsonb,
    citizenships        JSONB       NOT NULL DEFAULT '[]'::jsonb,
    birth_dates         JSONB       NOT NULL DEFAULT '[]'::jsonb,
    sanction_programs   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    remarks             JSONB       NOT NULL DEFAULT '[]'::jsonb,
    relationships       JSONB       NOT NULL DEFAULT '[]'::jsonb,

    -- Derived searchable columns for case-insensitive CONTAINS name search (Req 16.3).
    --
    -- primary_name_lower is the lowercased primary name — a STORED generated
    -- column, since lower(primary_name) is a simple immutable expression.
    --
    -- alias_search holds the lowercased, newline-joined concatenation of every
    -- alias `name` in the aliases JSONB array. It CANNOT be a generated column:
    -- flattening a JSONB array requires the set-returning jsonb_array_elements,
    -- and PostgreSQL forbids subqueries / set-returning functions in generation
    -- expressions. It is therefore a plain column populated by the persist stage
    -- at write time (records are insert-only and written whole, so the value is
    -- computed once and never drifts). Newline-joining keeps distinct aliases
    -- from forming a false substring match across a boundary.
    primary_name_lower  TEXT
        GENERATED ALWAYS AS (lower(primary_name)) STORED,
    alias_search        TEXT        NOT NULL DEFAULT '',

    CONSTRAINT records_pk PRIMARY KEY (id),

    -- A given FixedRef appears at most once within a version (post-dedup, Req 6).
    CONSTRAINT records_version_fixed_ref_uk UNIQUE (publish_date, digest, fixed_ref),

    -- Records belong to an existing version. No ON DELETE action: versions are
    -- insert-only and never deleted within the operational lifecycle (Req 7.5).
    CONSTRAINT records_version_fk
        FOREIGN KEY (publish_date, digest)
        REFERENCES versions (publish_date, digest)
);

-- Deterministic, stable pagination ordering by FixedRef within a version
-- (Req 16.2). The Query API constrains reads to the CURRENT version_id (Req 16.5),
-- so ordering is scoped by (publish_date, digest) then fixed_ref.
CREATE INDEX IF NOT EXISTS records_version_fixed_ref_idx
    ON records (publish_date, digest, fixed_ref);

-- Case-insensitive CONTAINS (substring) search over the primary name (Req 16.3),
-- index-assisted via pg_trgm on the lowercased column.
CREATE INDEX IF NOT EXISTS records_primary_name_lower_trgm_idx
    ON records USING GIN (primary_name_lower gin_trgm_ops);

-- Case-insensitive CONTAINS (substring) search over alias values (Req 16.3),
-- covering aliases even though they are stored in JSONB — the generated
-- alias_search column flattens them into a trigram-indexable text column.
CREATE INDEX IF NOT EXISTS records_alias_search_trgm_idx
    ON records USING GIN (alias_search gin_trgm_ops);

-- -----------------------------------------------------------------------------
-- pointers — one row per source_list holding the CURRENT / PREVIOUS / N_MINUS_2
-- version_ids (Req 9). The trio is swapped atomically in a single transaction on
-- activation (Req 9.1, 9.2) and by rollback (Req 10.3); this is the only mutable
-- table. `current` is never null while any version exists for the list (Req 9.2).
--
-- Each pointer is a (publish_date, digest) pair referencing a version row.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pointers (
    source_list             TEXT NOT NULL
        CONSTRAINT pointers_source_list_chk CHECK (source_list IN ('SDN', 'CONSOLIDATED')),

    current_publish_date    DATE     NOT NULL,
    current_digest          CHAR(64) NOT NULL,

    previous_publish_date   DATE     NULL,
    previous_digest         CHAR(64) NULL,

    n_minus_2_publish_date  DATE     NULL,
    n_minus_2_digest        CHAR(64) NULL,

    CONSTRAINT pointers_pk PRIMARY KEY (source_list),

    -- CURRENT must always reference an existing version (Req 9.2).
    CONSTRAINT pointers_current_fk
        FOREIGN KEY (current_publish_date, current_digest)
        REFERENCES versions (publish_date, digest),

    -- PREVIOUS / N_MINUS_2 are optional but, when set, must reference a version.
    -- The CHECK guards against a half-populated pair (one column set, the other null).
    CONSTRAINT pointers_previous_fk
        FOREIGN KEY (previous_publish_date, previous_digest)
        REFERENCES versions (publish_date, digest),
    CONSTRAINT pointers_previous_pair_chk CHECK (
        (previous_publish_date IS NULL) = (previous_digest IS NULL)
    ),

    CONSTRAINT pointers_n_minus_2_fk
        FOREIGN KEY (n_minus_2_publish_date, n_minus_2_digest)
        REFERENCES versions (publish_date, digest),
    CONSTRAINT pointers_n_minus_2_pair_chk CHECK (
        (n_minus_2_publish_date IS NULL) = (n_minus_2_digest IS NULL)
    )
);
