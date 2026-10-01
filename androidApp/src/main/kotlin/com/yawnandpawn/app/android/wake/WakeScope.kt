package com.yawnandpawn.app.android.wake

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * The wake runtime's timers (the volume ramp, the emergency ring's 30-minute limit) on the main thread, where
 * `MediaPlayer` also reports its errors. One failed job never cancels another; an exception is logged. A Koin `single`.
 */
class WakeScope(
    logger: Logger,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext =
        SupervisorJob() + Dispatchers.Main +
            CoroutineExceptionHandler { _, e ->
                logger.log(LogEvent.OperationFailed("wake runtime timer", e::class.simpleName.orEmpty()))
            }
}
