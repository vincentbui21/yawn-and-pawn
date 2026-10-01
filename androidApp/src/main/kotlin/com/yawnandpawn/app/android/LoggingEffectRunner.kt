package com.yawnandpawn.app.android

import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.EffectRunner
import com.yawnandpawn.app.core.session.EntryEffect
import com.yawnandpawn.app.core.session.SessionEffect

/**
 * The Epic 1 [EffectRunner]: there is no wake runtime yet (Story 1.14 replaces this), so each effect is only logged
 * by its type name. No effect contents reach the log: no labels, sounds or tokens. An ignored event is logged with its
 * type name and the random session id.
 */
class LoggingEffectRunner(
    private val logger: Logger,
) : EffectRunner {
    override suspend fun run(effect: SessionEffect) {
        logger.log(
            if (effect is SessionEffect.LogIgnored) {
                LogEvent.SessionEventIgnored(effect.eventType, effect.sessionId)
            } else {
                LogEvent.SessionEffectLogged(typeName(effect), entry = false)
            },
        )
    }

    override suspend fun apply(effect: EntryEffect) {
        logger.log(LogEvent.SessionEffectLogged(typeName(effect), entry = true))
    }

    private fun typeName(effect: Any): String = effect::class.simpleName ?: "Effect"
}
