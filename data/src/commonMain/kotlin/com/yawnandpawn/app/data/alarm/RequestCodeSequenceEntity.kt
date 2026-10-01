package com.yawnandpawn.app.data.alarm

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * The single row of `request_code_sequence` in `app.db` (schema v2): the highest alarm request code ever handed out
 * (AD-4), so a deleted alarm's code is never reused. [id] is always [ROW_ID].
 */
@Entity(tableName = "request_code_sequence")
data class RequestCodeSequenceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = ROW_ID,
    @ColumnInfo(name = "last_used")
    val lastUsed: Int,
) {
    companion object {
        const val ROW_ID = 0
    }
}
