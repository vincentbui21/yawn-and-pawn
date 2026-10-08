package com.yawnandpawn.app.data.billing

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

/** Access to `purchase_record` (Story 4.10). Only `RoomPurchaseRecordRepository` uses it (a scan test enforces it). */
@Dao
interface PurchaseRecordDao {
    @Query("SELECT * FROM purchase_record WHERE token_hash = :tokenHash")
    suspend fun get(tokenHash: String): PurchaseRecordEntity?

    @Query("SELECT * FROM purchase_record ORDER BY purchased_at DESC, token_hash")
    suspend fun all(): List<PurchaseRecordEntity>

    /** Inserts [row], or replaces the row with the same token hash. */
    @Upsert
    suspend fun upsertRecord(row: PurchaseRecordEntity)

    @Query("SELECT COUNT(*) FROM purchase_record")
    suspend fun count(): Int
}
