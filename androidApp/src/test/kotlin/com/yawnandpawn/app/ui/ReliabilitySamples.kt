package com.yawnandpawn.app.ui

import com.yawnandpawn.app.ui.reliability.ChecklistItem
import com.yawnandpawn.app.ui.reliability.ChecklistRow
import com.yawnandpawn.app.ui.reliability.ItemStatus
import com.yawnandpawn.app.ui.reliability.ReliabilityUiState

/** Story 5.4: the reliability checklist states of the spec's I/O matrix, as the app's ViewModel maps them. */
internal object ReliabilitySamples {
    private fun rows(vararg rows: Pair<ChecklistItem, ItemStatus>) =
        ReliabilityUiState(rows = rows.map { (item, status) -> ChecklistRow(item, status) })

    /** API 34, every setting on and a locked test passed: five rows "OK" (no maker covered, no camera check). */
    val allOk =
        rows(
            ChecklistItem.Notifications to ItemStatus.Ok,
            ChecklistItem.FullScreen to ItemStatus.Ok,
            ChecklistItem.DoNotDisturb to ItemStatus.Ok,
            ChecklistItem.Battery to ItemStatus.Ok,
            ChecklistItem.TestAlarm to ItemStatus.Ok,
        )

    /** Notifications off and Do Not Disturb blocking alarms, never seen OK; no locked test yet. */
    val severalNeedFix =
        rows(
            ChecklistItem.Notifications to ItemStatus.Missing,
            ChecklistItem.FullScreen to ItemStatus.Ok,
            ChecklistItem.DoNotDisturb to ItemStatus.Missing,
            ChecklistItem.Battery to ItemStatus.Ok,
            ChecklistItem.TestAlarm to ItemStatus.Missing,
        )

    /** Battery was OK and is optimised again (flow F7): "Alarm may not ring: battery optimization turned back on". */
    val revokedBattery =
        rows(
            ChecklistItem.Notifications to ItemStatus.Ok,
            ChecklistItem.FullScreen to ItemStatus.Ok,
            ChecklistItem.DoNotDisturb to ItemStatus.Ok,
            ChecklistItem.Battery to ItemStatus.Revoked,
            ChecklistItem.TestAlarm to ItemStatus.Ok,
        )

    /** An enabled alarm with a QR/Barcode check and the camera denied, on a covered maker's phone. */
    val cameraVisible =
        rows(
            ChecklistItem.Notifications to ItemStatus.Ok,
            ChecklistItem.FullScreen to ItemStatus.Ok,
            ChecklistItem.DoNotDisturb to ItemStatus.Ok,
            ChecklistItem.Battery to ItemStatus.Ok,
            ChecklistItem.Manufacturer to ItemStatus.Ok,
            ChecklistItem.Camera to ItemStatus.Missing,
            ChecklistItem.TestAlarm to ItemStatus.Ok,
        )

    /** API 31 with exact alarms denied: the Exact alarms row shows. */
    val api31Exact =
        rows(
            ChecklistItem.Notifications to ItemStatus.Ok,
            ChecklistItem.FullScreen to ItemStatus.Ok,
            ChecklistItem.ExactAlarms to ItemStatus.Missing,
            ChecklistItem.DoNotDisturb to ItemStatus.Ok,
            ChecklistItem.Battery to ItemStatus.Ok,
            ChecklistItem.TestAlarm to ItemStatus.Missing,
        )

    /** "Ring a test alarm" was tapped: "Lock your phone. We'll ring in 10 seconds.". */
    val testScheduled = allOk.copy(testScheduled = true)
}
