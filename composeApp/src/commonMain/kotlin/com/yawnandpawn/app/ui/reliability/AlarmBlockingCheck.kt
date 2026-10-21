package com.yawnandpawn.app.ui.reliability

import com.yawnandpawn.app.core.reliability.EvaluateReliability
import com.yawnandpawn.app.core.reliability.ReliabilityChecklist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The "Alarms may not ring. Fix settings" `banner-warning` of Home and Settings (Story 5.4, flow F7): [refresh] evaluates
 * the reliability checklist in [scope] ([EvaluateReliability], which also keeps the rows seen OK) and hands
 * [ReliabilityChecklist.alarmBlocking] to [onResult]. Only Notifications, Full-screen alarm, Exact alarms, Do Not Disturb
 * and Battery raise it. The banner has no dismiss: it clears itself on the first refresh after those rows are fixed.
 * Refreshes run one after the other, so the latest one decides. The evaluation (the probe's system service calls and the
 * records read) runs in [evaluationContext], off the main thread in the app; [onResult] runs back in [scope].
 */
class AlarmBlockingCheck(
    private val evaluate: EvaluateReliability,
    private val scope: CoroutineScope,
    private val evaluationContext: CoroutineContext = Dispatchers.Default,
    private val onResult: (blocking: Boolean) -> Unit,
) {
    private val running = Mutex()

    /** Evaluates again (the screen resumed), without waiting for it. */
    fun refresh() {
        scope.launch {
            running.withLock {
                val blocking = withContext(evaluationContext) { ReliabilityChecklist.alarmBlocking(evaluate()) }
                onResult(blocking)
            }
        }
    }
}
