package com.spike.ofac.pipeline.adapters

/**
 * The single seam that varies per source: `SourceAdapter` and its
 * implementations (`OfacAdapter`, `UnAdapter`, `EuAdapter`).
 *
 * An adapter encapsulates obtain I/O (endpoint, auth) and field mapping
 * (PartySubTypeID -> entity type via the observed ReferenceValueSet). The six
 * core stages never change to add a source (Req 13.1).
 *
 * - `OfacAdapter` (task 11): no credentials (Req 2.3).
 * - `UnAdapter` (task 20.1): no token, same anonymous obtain as OFAC (`spike` §12).
 * - `EuAdapter` (task 20.1): supplies a token (Req 13.3) and aborts obtain on a
 *   missing/invalid token, retaining the last good version (Req 13.5).
 * - `SourceAdapterSupport`: the shared, source-independent field-mapping helper
 *   used by all adapters so mapping semantics stay identical (Req 13.2, 13.4).
 */
