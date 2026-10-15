package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.reliability.ChecklistRow
import com.yawnandpawn.app.core.reliability.MakerConfirmation
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.reliability.ReliabilityItem
import com.yawnandpawn.app.core.reliability.ReliabilityProbe
import com.yawnandpawn.app.core.reliability.ReliabilityRecords
import com.yawnandpawn.app.core.reliability.ReliabilitySettings
import com.yawnandpawn.app.core.reliability.ReliabilityStatus
import com.yawnandpawn.app.core.reliability.StoredReliability
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

/** [ReliabilitySettings] that records every item it was asked to open. */
class FakeReliabilitySettings : ReliabilitySettings {
    val opened = mutableListOf<ReliabilityItem>()

    override fun open(item: ReliabilityItem) {
        opened += item
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
 * [ReliabilityRecords] in memory: [stored] is what a read returns and what the writes change. With [readFailure] or
 * [writeFailure] set, reads or writes fail with it and change nothing. [writes] counts the successful writes.
 */
class FakeReliabilityRecords(
    var stored: StoredReliability = StoredReliability(),
    var readFailure: DomainError? = null,
    var writeFailure: DomainError? = null,
) : ReliabilityRecords {
    var writes = 0
        private set

    override suspend fun read(): Outcome<StoredReliability, DomainError> =
        readFailure?.let { Outcome.Failure(it) } ?: Outcome.Success(stored)

    override suspend fun saveLastOk(rows: Set<ChecklistRow>): Outcome<Unit, DomainError> = write { it.copy(lastOk = rows) }

    override suspend fun lockedTestPassed(at: Instant): Outcome<Unit, DomainError> = write { it.copy(lockedTestPassedAt = at) }

    override suspend fun confirmMaker(confirmation: MakerConfirmation): Outcome<Unit, DomainError> =
        write { it.copy(makerConfirmation = confirmation) }

    private fun write(change: (StoredReliability) -> StoredReliability): Outcome<Unit, DomainError> {
        writeFailure?.let { return Outcome.Failure(it) }
        stored = change(stored)
        writes++
        return Outcome.Success(Unit)
    }
}
