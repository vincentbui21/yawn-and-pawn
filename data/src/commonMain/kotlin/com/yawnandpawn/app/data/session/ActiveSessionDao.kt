package com.yawnandpawn.app.data.session

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction

/** Access to `active_session`. Only `RoomActiveSessionStore` uses it. */
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

    /** Replaces whatever is stored with [row] in one transaction, so the table never holds two sessions. */
    @Transaction
    open suspend fun replace(row: ActiveSessionEntity) {
        deleteAll()
        insert(row)
    }
}
