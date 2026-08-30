package com.spike.ofac.adapter.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/**
 * Configuration for the local `Raw_Snapshot_Store` folder.
 *
 * Each raw snapshot is written **once** to a file in [folder] whose name is
 * derived from the (`Publish_Date`, `Digest`) pair, kept immutable, and used for
 * faithful reconstruction (Req 15). The raw bytes live on disk here, never in the
 * `Data_Store` (Req 15.8).
 *
 * Bound from properties prefixed `ofac.raw-snapshot-store` (see
 * `application.yml`). Tests point [folder] at a separate temporary directory so
 * raw-store tests never touch the operational folder.
 *
 * @property folder filesystem directory holding the versioned raw snapshot files.
 */
@ConfigurationProperties(prefix = "ofac.raw-snapshot-store")
data class RawSnapshotStoreProperties(
    val folder: Path,
)
