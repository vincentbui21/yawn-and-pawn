package com.yawnandpawn.app.core.summary

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.stats.IsoWeek
import com.yawnandpawn.app.core.stats.WeeklySummary
import kotlinx.coroutines.flow.Flow

/**
 * The weekly summary's stored state (Story 6.8): the Settings switch ([enabled], on by default) and the last ISO week a
 * run handled (posted, nothing to send, too late or no permission), so a week never posts twice. Nothing else is stored.
 */
data class WeeklySummaryPrefs(
    val enabled: Boolean = true,
    val lastHandledWeek: IsoWeek? = null,
)

/**
 * Port for [WeeklySummaryPrefs], kept in the device-protected settings DataStore by `:data` (so "Delete all data" clears
 * it and a backup carries it). Storage errors come back as `StorageFailure`, never thrown.
 */
interface WeeklySummaryStore {
    /** Emits on every change; an unreadable store is logged and reads as the defaults (on, no week handled). */
    fun observe(): Flow<WeeklySummaryPrefs>

    /** One read; a store that cannot be read is a `StorageFailure` (the run then posts nothing). */
    suspend fun read(): Outcome<WeeklySummaryPrefs, DomainError>

    suspend fun setEnabled(on: Boolean): Outcome<Unit, DomainError>

    suspend fun setLastHandledWeek(week: IsoWeek): Outcome<Unit, DomainError>
}

/** Posts the weekly summary notification (the Android adapter). */
interface WeeklySummaryNotifier {
    /** `POST_NOTIFICATIONS` is granted and the app's notifications are enabled. Never requests anything. */
    fun canPost(): Boolean

    /** Posts [summary]; a failure (a refused post, a broken service) is a failure value, never thrown. */
    fun post(summary: WeeklySummary): Outcome<Unit, DomainError>
}
