package com.yawnandpawn.app.data.session

import androidx.room3.Dao
import androidx.room3.Query

/**
 * Reads, settles and purges `grant_ledger` rows (Story 4.10). Only `RoomGrantLedgerStore` uses it (a scan test enforces
 * it). Rows are inserted only by [ActiveSessionDao.commit], in the transaction of the Snoozed state.
 */
@Dao
interface GrantLedgerDao {
    /** The rows not settled yet, oldest first. */
    @Query("SELECT * FROM grant_ledger WHERE settled_at IS NULL ORDER BY created_at, token")
    suspend fun pending(): List<GrantLedgerEntity>

    @Query("SELECT * FROM grant_ledger WHERE token = :token")
    suspend fun get(token: String): GrantLedgerEntity?

    @Query("UPDATE grant_ledger SET status = :status WHERE token = :token")
    suspend fun setConsumed(
        token: String,
        status: String,
    )

    @Query("UPDATE grant_ledger SET settled_at = :settledAt WHERE token = :token")
    suspend fun setSettled(
        token: String,
        settledAt: Long,
    )

    /** Deletes the settled markers settled before [before] (epoch millis); pending rows stay. Returns how many. */
    @Query("DELETE FROM grant_ledger WHERE settled_at IS NOT NULL AND settled_at < :before")
    suspend fun deleteSettledBefore(before: Long): Int

    @Query("SELECT COUNT(*) FROM grant_ledger")
    suspend fun count(): Int
}
