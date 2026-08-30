package com.spike.ofac.pipeline.models

import java.time.LocalDate

/**
 * Stable identity of a persisted [Version][VersionMetadata].
 *
 * `VersionId = (Publish_Date, Sha256Digest)` (Req 7.2). The [digest] is the
 * SHA-256 of the raw snapshot bytes, so two publications that share the same
 * [publishDate] but differ in content are distinct versions and both persist
 * separately (Req 7.3). Because it is a `data class`, equality is by value over
 * exactly these two fields, which is what disambiguates same-day publications.
 *
 * @property publishDate the `<Publish_Date>` read from the snapshot body.
 * @property digest lowercase hex SHA-256 of the raw snapshot (Req 7.2, 7.3).
 */
data class VersionId(
    val publishDate: LocalDate,
    val digest: Sha256Digest,
)

/**
 * A SHA-256 digest of the raw snapshot, held as its lowercase hex encoding.
 *
 * Wrapping the hex string in a value class keeps digests type-distinct from
 * arbitrary strings while carrying no runtime overhead. The value is normalized
 * to lowercase so equality (and therefore [VersionId] equality) never depends on
 * hex casing.
 *
 * @property value the 64-character lowercase hex encoding of the 32 digest bytes.
 */
@JvmInline
value class Sha256Digest(val value: String) {
    init {
        require(value.length == HEX_LENGTH) {
            "SHA-256 digest must be $HEX_LENGTH hex characters, was ${value.length}"
        }
        require(value.all { it in HEX_CHARS }) {
            "SHA-256 digest must be lowercase hex, was: $value"
        }
    }

    companion object {
        private const val HEX_LENGTH = 64
        private val HEX_CHARS = ('0'..'9') + ('a'..'f')

        /** Normalizes [hex] to lowercase and wraps it as a [Sha256Digest]. */
        fun ofHex(hex: String): Sha256Digest = Sha256Digest(hex.lowercase())
    }
}
