package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * The app-wide coroutine scope for work that outlives a screen: `rescheduleAll()` on app start and the receivers'
 * `goAsync()` work. One failed job never cancels the others, and an unexpected exception is logged instead of
 * crashing the process. A Koin `single`; tests replace it to wait for the work.
 */
class ApplicationScope(
    logger: Logger,
    context: CoroutineContext = Dispatchers.Default,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext =
        SupervisorJob() + context +
            CoroutineExceptionHandler { _, e ->
                logger.log(LogEvent.OperationFailed("background work", e.message ?: e::class.simpleName.orEmpty()))
            }
}
