package com.yawnandpawn.app.data.session

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction

/**
 * Access to `active_session`, and the one writer of `purchase_intent` ([commit], Story 4.8). Only `RoomActiveSessionStore`
 * uses it.
 */
@Dao
abstract class ActiveSessionDao {
    /** The stored row, if any (there is never more than one). */
    @Query("SELECT * FROM active_session LIMIT 1")
    abstract suspend fun get(): ActiveSessionEntity?

    @Query("SELECT COUNT(*) FROM active_session")
    abstract suspend fun count(): Int

    @Insert
    abstract suspend fun insert(row: ActiveSessionEntity)

    @Query("DELETE FROM active_session")
    abstract suspend fun deleteAll()

    /** Inserts an intent; an id that is already stored fails (an intent is written once). */
    @Insert
    abstract suspend fun insertIntent(row: PurchaseIntentEntity)

    /**
     * One write-ahead commit (AD-2 rule 2): replaces the stored session with [row] (none for Idle, so the table never
     * holds two sessions) and inserts [intents], all in one transaction. If any insert fails nothing is written.
     */
    @Transaction
    open suspend fun commit(
        row: ActiveSessionEntity?,
        intents: List<PurchaseIntentEntity> = emptyList(),
    ) {
        deleteAll()
        if (row != null) insert(row)
        intents.forEach { insertIntent(it) }
    }
}
