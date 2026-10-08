package com.yawnandpawn.app.data.billing

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceSource
import com.yawnandpawn.app.core.billing.PurchaseRecord
import com.yawnandpawn.app.core.billing.RecordStatus
import com.yawnandpawn.app.core.error.valueOrNull
import kotlin.time.Instant

/**
 * One row of `purchase_record` in `app.db` (Story 4.10, AD-7, AD-8): a purchase in the user's history, backed up with
 * the alarms. Keyed by [tokenHash], the SHA-256 hex of the token; the raw token is never stored here. The price is
 * integer micros and an ISO currency, and [priceSource] says whether it is the amount charged (`intent`) or an estimate;
 * times are wall time in epoch millis. [status] is `granted`, `consumed`, `stranded` or `reused`, and [consumedAt] says
 * when Play consumed it. There is no foreign key: a charge outlives its alarm and its session row.
 */
@Entity(tableName = "purchase_record", indices = [Index(value = ["purchased_at"])])
data class PurchaseRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "token_hash")
    val tokenHash: String,
    @ColumnInfo(name = "order_id")
    val orderId: String?,
    @ColumnInfo(name = "product_id")
    val productId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String?,
    @ColumnInfo(name = "alarm_id")
    val alarmId: String?,
    @ColumnInfo(name = "snooze_number")
    val snoozeNumber: Int?,
    @ColumnInfo(name = "price_micros")
    val priceMicros: Long,
    @ColumnInfo(name = "currency")
    val currency: String,
    @ColumnInfo(name = "price_source")
    val priceSource: String,
    @ColumnInfo(name = "purchased_at")
    val purchasedAt: Long,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "consumed_at")
    val consumedAt: Long?,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    /** The stored record, or null for a row that is not one (an unknown status or price source, or a malformed currency). */
    fun toRecord(): PurchaseRecord? {
        val known = STATUSES.entries.firstOrNull { it.value == status }?.key
        val source = SOURCES.entries.firstOrNull { it.value == priceSource }?.key
        val price = Money.parse(priceMicros, currency).valueOrNull()
        if (known == null || source == null || price == null) return null
        return PurchaseRecord(
            tokenHash = tokenHash,
            orderId = orderId,
            productId = productId,
            sessionId = sessionId,
            alarmId = alarmId,
            snoozeNumber = snoozeNumber,
            price = price,
            priceSource = source,
            purchasedAt = Instant.fromEpochMilliseconds(purchasedAt),
            status = known,
            consumedAt = consumedAt?.let { Instant.fromEpochMilliseconds(it) },
            updatedAt = Instant.fromEpochMilliseconds(updatedAt),
        )
    }

    companion object {
        /** The stored names of the record statuses (AD-7); Absent is "no row". */
        val STATUSES: Map<RecordStatus, String> =
            mapOf(
                RecordStatus.Granted to "granted",
                RecordStatus.Consumed to "consumed",
                RecordStatus.Stranded to "stranded",
                RecordStatus.Reused to "reused",
            )

        /** The stored names of the price sources: only `intent` is the amount charged. */
        val SOURCES: Map<PriceSource, String> =
            mapOf(
                PriceSource.Intent to "intent",
                PriceSource.Snapshot to "snapshot",
                PriceSource.Tier to "tier",
                PriceSource.Unknown to "unknown",
            )

        fun of(record: PurchaseRecord): PurchaseRecordEntity =
            PurchaseRecordEntity(
                tokenHash = record.tokenHash,
                orderId = record.orderId,
                productId = record.productId,
                sessionId = record.sessionId,
                alarmId = record.alarmId,
                snoozeNumber = record.snoozeNumber,
                priceMicros = record.price.micros,
                currency = record.price.currency,
                priceSource = SOURCES.getValue(record.priceSource),
                purchasedAt = record.purchasedAt.toEpochMilliseconds(),
                status = STATUSES.getValue(record.status),
                consumedAt = record.consumedAt?.toEpochMilliseconds(),
                updatedAt = record.updatedAt.toEpochMilliseconds(),
            )
    }
}
