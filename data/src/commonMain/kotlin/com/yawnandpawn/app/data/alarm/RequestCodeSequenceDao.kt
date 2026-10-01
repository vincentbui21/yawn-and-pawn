package com.yawnandpawn.app.data.alarm

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import com.yawnandpawn.app.core.alarm.RequestCodes

/** Access to `request_code_sequence`. Only `RoomRequestCodeSequence` uses it. */
@Dao
abstract class RequestCodeSequenceDao {
    @Query("SELECT * FROM request_code_sequence WHERE id = 0")
    abstract suspend fun get(): RequestCodeSequenceEntity?

    @Query("SELECT request_code FROM alarm")
    abstract suspend fun alarmCodes(): List<Int>

    @Insert
    abstract suspend fun insert(row: RequestCodeSequenceEntity)

    @Update
    abstract suspend fun update(row: RequestCodeSequenceEntity): Int

    /**
     * Reads the mark, writes mark + 1 and returns it, in one transaction, so a crash can never hand the same code out
     * twice. The row is created by the v1 to v2 migration; a database created at v2 has no row yet, so the first call
     * seeds it from the alarms (none on a fresh install) and [RequestCodes.INITIAL_HIGH_WATER_MARK].
     */
    @Transaction
    open suspend fun next(): Int {
        val stored = get()
        val mark = stored?.lastUsed ?: RequestCodes.highWaterMark(alarmCodes())
        check(mark < Int.MAX_VALUE) { "request codes exhausted" }
        val next = mark + 1
        val row = RequestCodeSequenceEntity(lastUsed = next)
        if (stored == null) insert(row) else update(row)
        return next
    }
}
