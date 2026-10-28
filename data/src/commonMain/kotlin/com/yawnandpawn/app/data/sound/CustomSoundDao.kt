package com.yawnandpawn.app.data.sound

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Access to `custom_sound` (Story 7.4). Only `RoomCustomSoundRepository` uses it; "Delete all data" goes through `AlarmDataDao`. */
@Dao
interface CustomSoundDao {
    @Query("SELECT * FROM custom_sound WHERE id = :id")
    suspend fun get(id: String): CustomSoundEntity?

    /** Every imported sound, oldest first, again after every change of the table. */
    @Query("SELECT * FROM custom_sound ORDER BY created_at, id")
    fun observeAll(): Flow<List<CustomSoundEntity>>

    /** Inserts [row]; a row with the same id is an error (each import has a new id). */
    @Insert
    suspend fun insert(row: CustomSoundEntity)

    @Query("SELECT COUNT(*) FROM custom_sound")
    suspend fun count(): Int
}
