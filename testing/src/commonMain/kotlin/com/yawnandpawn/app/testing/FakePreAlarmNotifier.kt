package com.yawnandpawn.app.testing

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.PreAlarmNotifier
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

/**
 * [PreAlarmNotifier] that keeps the notification showing for each alarm request code in [showing] (a post replaces it, a
 * cancel removes it) and every post in [posted] (Story 7.11). Set [canPost] false to deny the permission, [failPost] to
 * refuse posts.
 */
class FakePreAlarmNotifier(
    var canPost: Boolean = true,
) : PreAlarmNotifier {
    private val shown = MutableStateFlow<Map<Int, Pair<Alarm, Duration>>>(emptyMap())
    private val posts = MutableStateFlow<List<Pair<Alarm, Duration>>>(emptyList())
    private val cancelled = MutableStateFlow<List<Int>>(emptyList())

    var failPost: DomainError? = null

    /** The notification showing per alarm request code: the alarm and how long until it rang when posted. */
    val showing: Map<Int, Pair<Alarm, Duration>>
        get() = shown.value

    /** Every successful post, oldest first. */
    val posted: List<Pair<Alarm, Duration>>
        get() = posts.value

    /** Every cancel, by request code, oldest first. */
    val cancels: List<Int>
        get() = cancelled.value

    override fun canPost(): Boolean = canPost

    override fun post(
        alarm: Alarm,
        ringsIn: Duration,
    ): Outcome<Unit, DomainError> {
        failPost?.let { return Outcome.Failure(it) }
        shown.update { it + (alarm.requestCode to (alarm to ringsIn)) }
        posts.update { it + (alarm to ringsIn) }
        return Outcome.Success(Unit)
    }

    override fun cancel(requestCode: Int): Outcome<Unit, DomainError> {
        shown.update { it - requestCode }
        cancelled.update { it + requestCode }
        return Outcome.Success(Unit)
    }
}
