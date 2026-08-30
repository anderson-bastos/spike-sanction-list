package com.spike.ofac.application.retention

import com.spike.ofac.domain.model.PreserveKind
import com.spike.ofac.domain.model.RetentionPolicy
import com.spike.ofac.domain.model.Sha256Digest
import com.spike.ofac.domain.model.SourceList
import com.spike.ofac.domain.model.VersionId
import com.spike.ofac.application.port.out.RawSnapshotStore
import com.spike.ofac.application.port.out.VersionStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate

/**
 * Unit tests for [RetentionManager.applyAfterActivation] (task 19.4, Req 14.1, 14.2, 14.4).
 *
 * These isolate the retain-vs-discard decision that runs after `publish` completes a
 * window rotation. Collaborators ([VersionStore], [RawSnapshotStore]) are MockK mocks
 * so the test observes only which store calls the manager makes:
 *
 *  - **DISABLED** — every displaced (`COLD`) version's raw snapshot file is deleted
 *    from the `Raw_Snapshot_Store` (Req 14.4).
 *  - **ENABLED** — nothing is deleted; the displaced versions stay `COLD` and are
 *    retained with their raw files (Req 14.1, 14.2).
 *
 * Both branches first reclassify versions displaced past `N_MINUS_2` as `COLD`
 * (rotation-owned demotion, Req 10.5) before deciding.
 */
class RetentionManagerApplyAfterActivationTest {

    private fun versionId(day: Int, char: Char): VersionId =
        VersionId(LocalDate.of(2024, 1, day), Sha256Digest(char.toString().repeat(64)))

    private val enabledPolicy =
        RetentionPolicy(enabled = true, retentionPeriod = Duration.ofDays(30), preserve = PreserveKind.RAW)

    private val disabledPolicy =
        RetentionPolicy(enabled = false, retentionPeriod = null, preserve = PreserveKind.RAW)

    @Test
    fun `disabled discards every displaced version's raw snapshot file (Req 14-4)`() {
        val displacedA = versionId(10, 'a')
        val displacedB = versionId(11, 'b')

        val versionStore = mockk<VersionStore>(relaxUnitFun = true)
        val rawStore = mockk<RawSnapshotStore>()
        every { versionStore.coldVersions(SourceList.SDN) } returns listOf(displacedA, displacedB)
        every { rawStore.delete(any()) } returns true

        val manager = RetentionManager(versionStore, rawStore)

        manager.applyAfterActivation(SourceList.SDN, disabledPolicy)

        // Each displaced COLD version's raw file is deleted from the Raw_Snapshot_Store.
        verify(exactly = 1) { rawStore.delete(displacedA) }
        verify(exactly = 1) { rawStore.delete(displacedB) }
        verify(exactly = 2) { rawStore.delete(any()) }
    }

    @Test
    fun `disabled with no displaced versions deletes nothing (Req 14-4)`() {
        val versionStore = mockk<VersionStore>(relaxUnitFun = true)
        val rawStore = mockk<RawSnapshotStore>()
        every { versionStore.coldVersions(SourceList.SDN) } returns emptyList()

        val manager = RetentionManager(versionStore, rawStore)

        manager.applyAfterActivation(SourceList.SDN, disabledPolicy)

        verify(exactly = 0) { rawStore.delete(any()) }
    }

    @Test
    fun `disabled demotes displaced versions to COLD before discarding (Req 10-5, 14-4)`() {
        val displaced = versionId(10, 'a')

        val versionStore = mockk<VersionStore>(relaxUnitFun = true)
        val rawStore = mockk<RawSnapshotStore>()
        every { versionStore.coldVersions(SourceList.SDN) } returns listOf(displaced)
        every { rawStore.delete(any()) } returns true

        val manager = RetentionManager(versionStore, rawStore)

        manager.applyAfterActivation(SourceList.SDN, disabledPolicy)

        // Rotation-owned demotion runs before the COLD set is read and discarded.
        verifyOrder {
            versionStore.reclassifyCold(SourceList.SDN)
            versionStore.coldVersions(SourceList.SDN)
            rawStore.delete(displaced)
        }
    }

    @Test
    fun `enabled retains displaced versions and their raw files, deleting nothing (Req 14-1, 14-2)`() {
        val versionStore = mockk<VersionStore>(relaxUnitFun = true)
        val rawStore = mockk<RawSnapshotStore>()

        val manager = RetentionManager(versionStore, rawStore)

        manager.applyAfterActivation(SourceList.SDN, enabledPolicy)

        // Displaced versions stay COLD; no raw snapshot file is discarded.
        verify(exactly = 0) { rawStore.delete(any()) }
        // The COLD set is not even enumerated for discard when retention is on.
        verify(exactly = 0) { versionStore.coldVersions(any()) }
    }

    @Test
    fun `enabled still applies rotation-owned COLD demotion (Req 10-5)`() {
        val versionStore = mockk<VersionStore>(relaxUnitFun = true)
        val rawStore = mockk<RawSnapshotStore>()

        val manager = RetentionManager(versionStore, rawStore)

        manager.applyAfterActivation(SourceList.SDN, enabledPolicy)

        // Window rotation always demotes past N_MINUS_2, independent of retain-vs-drop.
        verify(exactly = 1) { versionStore.reclassifyCold(SourceList.SDN) }
    }

    @Test
    fun `disabled applies per-list, discarding only the given list's displaced versions (Req 14-2)`() {
        val sdnDisplaced = versionId(10, 'a')

        val versionStore = mockk<VersionStore>(relaxUnitFun = true)
        val rawStore = mockk<RawSnapshotStore>()
        every { versionStore.coldVersions(SourceList.SDN) } returns listOf(sdnDisplaced)
        every { rawStore.delete(any()) } returns true

        val manager = RetentionManager(versionStore, rawStore)

        manager.applyAfterActivation(SourceList.SDN, disabledPolicy)

        // Only the SDN list is reclassified/enumerated; CONSOLIDATED is untouched.
        verify(exactly = 1) { versionStore.reclassifyCold(SourceList.SDN) }
        verify(exactly = 1) { versionStore.coldVersions(SourceList.SDN) }
        verify(exactly = 0) { versionStore.reclassifyCold(SourceList.CONSOLIDATED) }
        verify(exactly = 0) { versionStore.coldVersions(SourceList.CONSOLIDATED) }
    }
}
