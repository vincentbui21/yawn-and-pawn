package com.yawnandpawn.app.core.reliability

import com.yawnandpawn.app.core.alarm.AlarmWithChecks
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant

/** A row of the reliability checklist (Epic 5), in the order the checklist shows them. */
enum class ChecklistRow {
    Notifications,
    FullScreenAlarm,

    /** Shown on Android 12 to 12L only (API 31-32); later versions hold `USE_EXACT_ALARM`. */
    ExactAlarms,
    DoNotDisturb,
    Battery,

    /** Shown only for a maker the guidance covers ([DeviceInfo.makerCovered], Story 5.5). */
    Manufacturer,

    /** Shown only while an enabled alarm has a camera check. */
    Camera,

    /** Hidden until recording checks exist (Epic 7). */
    Microphone,
    LockedScreenTest,
}

/** The status of one [ChecklistRow]. */
enum class RowStatus {
    Ok,

    /** Not OK and never seen OK. */
    NeedsFix,

    /** Not OK although it was OK before: a phone update or the user turned it off. Stays until it is fixed. */
    Revoked,

    /** Does not apply to this phone or these alarms; never blocks. */
    Hidden,
}

/** One evaluated row. */
data class ChecklistEntry(
    val row: ChecklistRow,
    val status: RowStatus,
)

/**
 * The phone the checklist runs on.
 *
 * @property sdkInt the API level.
 * @property maker the manufacturer (`Build.MANUFACTURER`).
 * @property fingerprint the build fingerprint: a confirmation of the maker guidance holds for this build only.
 * @property deviceMaker the maker the guidance knows this phone as ([DeviceMaker.of], Story 5.5).
 */
data class DeviceInfo(
    val sdkInt: Int,
    val maker: String,
    val fingerprint: String,
    val deviceMaker: DeviceMaker = DeviceMaker.Other,
) {
    /** The maker guidance covers this phone ([DeviceMaker.covered]): the Manufacturer row shows. */
    val makerCovered: Boolean get() = deviceMaker.covered
}

/** Port: the phone the app runs on. */
fun interface DeviceInfoSource {
    fun current(): DeviceInfo
}

/** The user confirmed the maker guidance for [maker] on the build [fingerprint] (Story 5.5). */
data class MakerConfirmation(
    val maker: String,
    val fingerprint: String,
)

/**
 * What the checklist remembers between evaluations (settings DataStore, AD-6).
 *
 * @property lastOk the rows seen OK (or Revoked since) by [ReliabilityChecklist.lastOkAfter]: a failing row in it is Revoked.
 * @property lockedTestPassedAt when a test alarm that rang with the phone locked was completed; null before that.
 * @property makerConfirmation the last confirmed maker guidance.
 */
data class StoredReliability(
    val lastOk: Set<ChecklistRow> = emptySet(),
    val lockedTestPassedAt: Instant? = null,
    val makerConfirmation: MakerConfirmation? = null,
)

/**
 * Port: records that a test alarm rung with the phone locked was completed (Story 5.3). `SessionEngine` never calls it
 * itself: it reports the pass to a [LockedTestReporter], which runs this write outside the engine's lock.
 */
interface LockedTestRecorder {
    suspend fun lockedTestPassed(at: Instant): Outcome<Unit, DomainError>
}

/**
 * Port: the [StoredReliability] in the device-protected settings DataStore. Storage errors are `StorageFailure`, never
 * thrown, except in [observe], whose flow fails on a read error (like `CheckConfigRepository`; the caller catches it).
 */
interface ReliabilityRecords : LockedTestRecorder {
    suspend fun read(): Outcome<StoredReliability, DomainError>

    /**
     * The stored records, then again after every change (Story 5.12: onboarding's step 8 and Home's test note learn of a
     * locked pass, which 5.3 writes after the test's Completed).
     */
    fun observe(): Flow<StoredReliability>

    /** Replaces the rows seen OK ([StoredReliability.lastOk]). */
    suspend fun saveLastOk(rows: Set<ChecklistRow>): Outcome<Unit, DomainError>

    /** The user confirmed the maker guidance (Story 5.5). */
    suspend fun confirmMaker(confirmation: MakerConfirmation): Outcome<Unit, DomainError>
}

/** The reliability checklist's rules (Epic 5, Story 5.3). Pure. */
object ReliabilityChecklist {
    /** The rows that can stop an alarm from ringing: only these raise the "Alarms may not ring" banner. */
    val BLOCKING_ROWS: Set<ChecklistRow> =
        setOf(
            ChecklistRow.Notifications,
            ChecklistRow.FullScreenAlarm,
            ChecklistRow.ExactAlarms,
            ChecklistRow.DoNotDisturb,
            ChecklistRow.Battery,
        )

    private const val FIRST_EXACT_ALARM_GRANT_SDK = 31
    private const val LAST_EXACT_ALARM_GRANT_SDK = 32

    /**
     * All nine rows in [ChecklistRow] order, Hidden ones included. A row that is not OK is Revoked when it is in
     * [StoredReliability.lastOk], else NeedsFix.
     */
    fun evaluate(
        status: ReliabilityStatus,
        alarms: List<AlarmWithChecks>,
        device: DeviceInfo,
        stored: StoredReliability,
    ): List<ChecklistEntry> {
        val cameraUsed = alarms.any { it.alarm.enabled && it.checks.any { check -> check.entry.type.usesCamera } }
        val confirmed = stored.makerConfirmation == MakerConfirmation(device.maker, device.fingerprint)
        val exactAlarmGrant = device.sdkInt in FIRST_EXACT_ALARM_GRANT_SDK..LAST_EXACT_ALARM_GRANT_SDK
        return ChecklistRow.entries.map { row ->
            val ok: Boolean? =
                when (row) {
                    ChecklistRow.Notifications -> status.notificationsAllowed
                    ChecklistRow.FullScreenAlarm -> status.fullScreenIntentAllowed
                    ChecklistRow.ExactAlarms -> status.exactAlarmsAllowed.takeIf { exactAlarmGrant }
                    ChecklistRow.DoNotDisturb -> status.dndAllowsAlarms
                    ChecklistRow.Battery -> status.batteryOk
                    ChecklistRow.Manufacturer -> confirmed.takeIf { device.makerCovered }
                    ChecklistRow.Camera -> status.cameraGranted.takeIf { cameraUsed }
                    ChecklistRow.Microphone -> null
                    ChecklistRow.LockedScreenTest -> stored.lockedTestPassedAt != null
                }
            ChecklistEntry(row, statusOf(row, ok, stored.lastOk))
        }
    }

    /** True when a [BLOCKING_ROWS] row is NeedsFix or Revoked; Hidden never blocks. */
    fun alarmBlocking(rows: List<ChecklistEntry>): Boolean =
        rows.any { it.row in BLOCKING_ROWS && (it.status == RowStatus.NeedsFix || it.status == RowStatus.Revoked) }

    /**
     * The [StoredReliability.lastOk] to keep after [rows]: OK and Revoked rows are in it (so a revoked row stays Revoked
     * until it is fixed), NeedsFix rows leave it, and Hidden or missing rows keep their [previous] membership.
     */
    fun lastOkAfter(
        rows: List<ChecklistEntry>,
        previous: Set<ChecklistRow>,
    ): Set<ChecklistRow> {
        val byRow = rows.associate { it.row to it.status }
        return ChecklistRow.entries
            .filter { row ->
                when (byRow[row]) {
                    RowStatus.Ok, RowStatus.Revoked -> true
                    RowStatus.NeedsFix -> false
                    RowStatus.Hidden, null -> row in previous
                }
            }.toSet()
    }

    private fun statusOf(
        row: ChecklistRow,
        ok: Boolean?,
        lastOk: Set<ChecklistRow>,
    ): RowStatus =
        when {
            ok == null -> RowStatus.Hidden
            ok -> RowStatus.Ok
            row in lastOk -> RowStatus.Revoked
            else -> RowStatus.NeedsFix
        }
}

/**
 * One evaluation: the [rows] and the probe [status] they came from (Story 5.4: the checklist tells which Battery setting
 * failed, so a revoked row names battery optimisation only when that is the cause).
 */
data class ReliabilityEvaluation(
    val rows: List<ChecklistEntry>,
    val status: ReliabilityStatus,
)

/**
 * Evaluates the reliability checklist now (Story 5.4 calls it on every start of the checklist screen): the probe, the
 * alarms with their checks, the phone and the stored records through [ReliabilityChecklist.evaluate], then keeps the
 * rows seen OK ([ReliabilityChecklist.lastOkAfter]) when they changed.
 *
 * Never fails: alarms that cannot be read count as none (the Camera row hides), records that cannot be read count as
 * nothing stored and are then not written (a read error never erases the rows seen OK), and a failed write is logged.
 */
class EvaluateReliability(
    private val probe: ReliabilityProbe,
    private val device: DeviceInfoSource,
    private val checks: CheckConfigRepository,
    private val records: ReliabilityRecords,
    private val logger: Logger,
) {
    suspend operator fun invoke(): List<ChecklistEntry> = evaluation().rows

    /** [invoke] with the probe's status the rows came from (one probe read). */
    suspend fun evaluation(): ReliabilityEvaluation {
        val stored = records.read()
        val previous = (stored as? Outcome.Success)?.value
        if (stored is Outcome.Failure) logger.log(LogEvent.OperationFailed.of("read reliability records", stored.error))
        val status = probe.check()
        val rows = ReliabilityChecklist.evaluate(status, alarms(), device.current(), previous ?: StoredReliability())
        if (previous != null) {
            val lastOk = ReliabilityChecklist.lastOkAfter(rows, previous.lastOk)
            if (lastOk != previous.lastOk) {
                val saved = records.saveLastOk(lastOk)
                if (saved is Outcome.Failure) logger.log(LogEvent.OperationFailed.of("save reliability rows", saved.error))
            }
        }
        return ReliabilityEvaluation(rows, status)
    }

    // A failing read of the alarms fails their flow (CheckConfigRepository); the checklist still shows, without Camera.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun alarms(): List<AlarmWithChecks> =
        try {
            checks.observeAlarmsWithChecks().first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.log(LogEvent.OperationFailed("read alarms for the reliability checklist", e::class.simpleName.orEmpty()))
            emptyList()
        }
}
