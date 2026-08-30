package com.spike.ofac.pipeline.store

/**
 * Persistence contracts and their concrete implementations.
 *
 *  - `VersionStore` (contract) + [InMemoryVersionStore] reference model (task 8) +
 *    `PgVersionStore` over local PostgreSQL (task 13).
 *  - `RawSnapshotStore` (contract) + `FsRawSnapshotStore` over the local
 *    versioned folder (task 13). The raw snapshot is stored only as a file
 *    in the Raw_Snapshot_Store, never in the Data_Store (Req 15.8).
 */
