package com.yawnandpawn.app.data.session

import androidx.room3.Dao
import androidx.room3.Query

/**
 * Reads and purges `purchase_intent` (Story 4.8). Only `RoomPurchaseIntentStore` uses it. Rows are inserted only by
 * [ActiveSessionDao.commit], in the transaction of the session state.
 */
@Dao
interface PurchaseIntentDao {
    @Query("SELECT * FROM purchase_intent WHERE intent_id = :intentId")
    suspend fun get(intentId: String): PurchaseIntentEntity?

    @Query("SELECT * FROM purchase_intent WHERE session_id = :sessionId ORDER BY created_at, intent_id")
    suspend fun forSession(sessionId: String): List<PurchaseIntentEntity>

    @Query("SELECT * FROM purchase_intent WHERE session_id = :sessionId AND product_id = :productId ORDER BY created_at, intent_id")
    suspend fun forProduct(
        sessionId: String,
        productId: String,
    ): List<PurchaseIntentEntity>

    /** Deletes every row created before [createdBefore] (epoch millis); returns how many. */
    @Query("DELETE FROM purchase_intent WHERE created_at < :createdBefore")
    suspend fun deleteOlderThan(createdBefore: Long): Int

    @Query("SELECT COUNT(*) FROM purchase_intent")
    suspend fun count(): Int
}
