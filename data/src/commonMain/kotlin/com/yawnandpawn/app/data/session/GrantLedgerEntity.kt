package com.yawnandpawn.app.data.session

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.yawnandpawn.app.core.billing.GrantLedgerEntry
import com.yawnandpawn.app.core.billing.LedgerStatus
import com.yawnandpawn.app.core.session.PurchaseToken
import kotlin.time.Instant

/**
 * One row of `grant_ledger` in `runtime.db` (Story 4.10, AD-7): a snooze granted for [token] whose payment is not settled
 * yet, inserted in the same transaction as the Snoozed state. The raw token lives only here: `runtime.db` is never backed
 * up (NFR-14). [status] is `granted` or `consumed`; [createdAt] is wall time in epoch millis.
 */
@Entity(tableName = "grant_ledger")
data class GrantLedgerEntity(
    @PrimaryKey
    @ColumnInfo(name = "token")
    val token: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "alarm_id")
    val alarmId: String,
    @ColumnInfo(name = "product_id")
    val productId: String,
    @ColumnInfo(name = "snooze_number")
    val snoozeNumber: Int,
    @ColumnInfo(name = "order_id")
    val orderId: String?,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    /** The stored row, or null for a status this build does not know (only a damaged file could hold one). */
    fun toEntry(): GrantLedgerEntry? {
        val known = STATUSES.entries.firstOrNull { it.value == status }?.key ?: return null
        val created = Instant.fromEpochMilliseconds(createdAt)
        return GrantLedgerEntry(PurchaseToken(token), sessionId, alarmId, productId, snoozeNumber, orderId, known, created)
    }

    /** Hides the token, like `PurchaseToken`. */
    override fun toString(): String = "GrantLedgerEntity(token=redacted, sessionId=$sessionId, productId=$productId, status=$status)"

    companion object {
        /** The stored names of the ledger statuses (AD-7): `granted` and `consumed`; Absent is "no row". */
        val STATUSES: Map<LedgerStatus, String> = mapOf(LedgerStatus.Granted to "granted", LedgerStatus.Consumed to "consumed")

        fun of(entry: GrantLedgerEntry): GrantLedgerEntity =
            GrantLedgerEntity(
                token = entry.token.value,
                sessionId = entry.sessionId,
                alarmId = entry.alarmId,
                productId = entry.productId,
                snoozeNumber = entry.snoozeNumber,
                orderId = entry.orderId,
                status = STATUSES.getValue(entry.status),
                createdAt = entry.createdAt.toEpochMilliseconds(),
            )
    }
}
