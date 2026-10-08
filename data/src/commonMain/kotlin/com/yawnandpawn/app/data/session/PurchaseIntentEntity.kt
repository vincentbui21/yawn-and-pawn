package com.yawnandpawn.app.data.session

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PurchaseIntent
import com.yawnandpawn.app.core.error.valueOrNull
import com.yawnandpawn.app.core.session.PurchaseIntentId
import kotlin.time.Instant

/**
 * One row of `purchase_intent` in `runtime.db` (Story 4.8, AD-7): a payment attempt written in the same transaction as
 * the state that set `paying`. The price is Play's live price at the Pay tap, as integer micros and an ISO currency
 * (AD-8); [createdAt] is wall time in epoch millis, which the 7-day purge compares.
 */
@Entity(tableName = "purchase_intent")
data class PurchaseIntentEntity(
    @PrimaryKey
    @ColumnInfo(name = "intent_id")
    val intentId: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "product_id")
    val productId: String,
    @ColumnInfo(name = "snooze_number")
    val snoozeNumber: Int,
    @ColumnInfo(name = "price_micros")
    val priceMicros: Long,
    @ColumnInfo(name = "currency")
    val currency: String,
    @ColumnInfo(name = "formatted_price")
    val formattedPrice: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
) {
    /** The stored intent, or null for a row that is not one (a malformed currency). */
    fun toIntent(): PurchaseIntent? {
        val price = Money.parse(priceMicros, currency).valueOrNull() ?: return null
        return PurchaseIntent(
            intentId = PurchaseIntentId(intentId),
            sessionId = sessionId,
            productId = productId,
            snoozeNumber = snoozeNumber,
            price = price,
            formattedPrice = formattedPrice,
            createdAt = Instant.fromEpochMilliseconds(createdAt),
        )
    }

    companion object {
        fun of(intent: PurchaseIntent): PurchaseIntentEntity =
            PurchaseIntentEntity(
                intentId = intent.intentId.value,
                sessionId = intent.sessionId,
                productId = intent.productId,
                snoozeNumber = intent.snoozeNumber,
                priceMicros = intent.price.micros,
                currency = intent.price.currency,
                formattedPrice = intent.formattedPrice,
                createdAt = intent.createdAt.toEpochMilliseconds(),
            )
    }
}
