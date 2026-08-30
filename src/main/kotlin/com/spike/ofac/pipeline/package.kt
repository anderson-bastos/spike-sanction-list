package com.spike.ofac.pipeline

/**
 * The `pipeline` package is the source-independent core.
 *
 * Sub-packages:
 *  - [com.spike.ofac.pipeline.models]   internal data models and value types (task 2)
 *  - [com.spike.ofac.pipeline.stages]   the six stages: obtain, validate, transform,
 *                                       version, persist, publish (tasks 3-15)
 *  - [com.spike.ofac.pipeline.adapters] the SourceAdapter seam + OfacAdapter (task 11)
 *  - [com.spike.ofac.pipeline.store]    VersionStore / RawSnapshotStore contracts and
 *                                       their PostgreSQL / filesystem implementations (tasks 8, 13)
 *
 * The Scheduler (task 15) and Query_API (task 17) sit alongside this package.
 *
 * Task 1 only establishes the layout; the components are implemented in later tasks.
 */
