package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.core.reliability.DeviceInfo
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.EvaluateReliability
import com.yawnandpawn.app.core.reliability.MakerConfirmation
import com.yawnandpawn.app.core.reliability.ManufacturerSettings
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilityRecords
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.core.reliability.StoredReliability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlin.time.Instant

/** [ReliabilityProbe] returning [status]; [checks] counts the calls. */
class FakeReliabilityProbe(
    var status: ReliabilityStatus = ReliabilityStatus.ALL_OK,
) : ReliabilityProbe {
    var checks = 0
        private set

    override fun check(): ReliabilityStatus {
        checks++
        return status
    }
}

/**
 * [ReliabilitySettings] that records every row it was asked to open (Story 5.4); [opens] is what each open answers
 * (false: nothing could be opened).
 */
class FakeReliabilitySettings(
    var opens: Boolean = true,
) : ReliabilitySettings {
    val opened = mutableListOf<ChecklistRow>()

    override fun open(row: ChecklistRow): Boolean {
        opened += row
        return opens
    }
}

/** [ManufacturerSettings] that records every maker whose settings it was asked to open (Story 5.5). */
class FakeManufacturerSettings : ManufacturerSettings {
    val opened = mutableListOf<DeviceMaker>()

    override fun open(maker: DeviceMaker) {
        opened += maker
    }
}

/**
 * [NotificationPermission] that needs asking while [needed]; a request clears it, like the real "asked once". With
 * [failure] set, a request throws it (the system dialog could not be launched) and stays needed.
 */
class FakeNotificationPermission(
    var needed: Boolean = false,
    var failure: Exception? = null,
) : NotificationPermission {
    var requests = 0
        private set

    override fun shouldRequest(): Boolean = needed

    override fun request() {
        requests++
        failure?.let { throw it }
        needed = false
    }
}

/**
 * [ReliabilityRecords] in memory: [stored] is what a read returns and what the writes change; [observe] emits it on every
 * change, setting it from a test included. With [readFailure] or [writeFailure] set, reads or writes fail with it and
 * change nothing; with [observeFailure] set, [observe] throws it. [writes] counts the successful writes.
 */
class FakeReliabilityRecords(
    stored: StoredReliability = StoredReliability(),
    var readFailure: DomainError? = null,
    var writeFailure: DomainError? = null,
) : ReliabilityRecords {
    private val state = MutableStateFlow(stored)

    var stored: StoredReliability
        get() = state.value
        set(value) {
            state.value = value
        }

    var observeFailure: Throwable? = null

    var writes = 0
        private set

    override suspend fun read(): Outcome<StoredReliability, DomainError> =
        readFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(stored)

    override fun observe(): Flow<StoredReliability> {
        val failure = observeFailure ?: return state
        return flow { throw failure }
    }

    override suspend fun saveLastOk(rows: Set<ChecklistRow>): Outcome<Unit, DomainError> = write { it.copy(lastOk = rows) }

    override suspend fun lockedTestPassed(at: Instant): Outcome<Unit, DomainError> = write { it.copy(lockedTestPassedAt = at) }

    override suspend fun confirmMaker(confirmation: MakerConfirmation): Outcome<Unit, DomainError> =
        write { it.copy(makerConfirmation = confirmation) }

    override suspend fun recordTapped(): Outcome<Unit, DomainError> = write { it.copy(recordTapped = true) }

    private fun write(change: (StoredReliability) -> StoredReliability): Outcome<Unit, DomainError> {
        writeFailure?.let { return Outcome.Failure(it) }
        stored = change(stored)
        writes++
        return Outcome.Success(Unit)
    }
}

/** An API 34 phone of a maker the guidance does not cover, so its Manufacturer row is Hidden. */
val aPlainPhone: DeviceInfo = DeviceInfo(sdkInt = 34, maker = "Google", fingerprint = "google/build-1")

/**
 * The real [EvaluateReliability] over fakes (Story 5.4): [probe] for the settings, [checks] for the alarms (none by
 * default), [records] for what is remembered and [device] for the phone. With the defaults no blocking row fails.
 */
fun anEvaluateReliability(
    probe: ReliabilityProbe = FakeReliabilityProbe(),
    checks: CheckConfigRepository = FakeCheckConfigRepository(),
    records: ReliabilityRecords = FakeReliabilityRecords(),
    device: DeviceInfo = aPlainPhone,
    logger: Logger = FakeLogger(),
): EvaluateReliability = EvaluateReliability(probe, { device }, checks, records, logger)
