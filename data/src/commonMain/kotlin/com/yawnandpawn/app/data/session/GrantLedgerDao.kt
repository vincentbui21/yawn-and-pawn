package com.yawnandpawn.app.data.session

import androidx.room3.Dao
import androidx.room3.Query

/**
 * Reads, settles and deletes `grant_ledger` rows (Story 4.10). Only `RoomGrantLedgerStore` uses it. Rows are inserted
 * only by [ActiveSessionDao.commit], in the transaction of the Snoozed state.
 */
@Dao
interface GrantLedgerDao {
    @Query("SELECT * FROM grant_ledger ORDER BY created_at, token")
    suspend fun all(): List<GrantLedgerEntity>

    @Query("SELECT * FROM grant_ledger WHERE token = :token")
    suspend fun get(token: String): GrantLedgerEntity?

    @Query("UPDATE grant_ledger SET status = :status WHERE token = :token")
    suspend fun setStatus(
        token: String,
        status: String,
    )

    @Query("DELETE FROM grant_ledger WHERE token = :token")
    suspend fun delete(token: String)

    @Query("SELECT COUNT(*) FROM grant_ledger")
    suspend fun count(): Int
}
