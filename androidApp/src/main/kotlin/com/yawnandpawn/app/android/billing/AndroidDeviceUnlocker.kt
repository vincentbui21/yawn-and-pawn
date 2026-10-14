package com.yawnandpawn.app.android.billing

import android.app.Activity
import android.app.KeyguardManager
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.session.UnlockPort
import com.yawnandpawn.app.core.session.UnlockResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The real [UnlockPort] (Story 4.12, Spike S1 option B: price first, then PIN): the Play sheet never shows over a
 * keyguard (S1 V1s), so a locked "Pay" asks for the PIN with `KeyguardManager.requestDismissKeyguard` from the resumed
 * wake screen, and Play opens only after `onDismissSucceeded`. One `UnlockPort` serves the engine (it reads
 * [isKeyguardLocked] for a Pay) and `PurchaseCoordinator` (it asks [requestUnlock] outside the engine's step).
 *
 * - Not locked: [UnlockResult.Succeeded] at once.
 * - The wake screen is not resumed within [screenWait]: [UnlockResult.Failed] (nothing to show the prompt over).
 * - `onDismissCancelled` or `onDismissError`: [UnlockResult.Failed] ("Phone still locked. No charge.").
 * - A wrong PIN gives no callback (S1 V3) and the prompt stays up; after [promptLimit] with no callback the request
 *   counts as failed, so a lost callback never waits for ever. The coordinator's resume rule (Story 4.11) settles a lost
 *   callback sooner when the wake screen comes back.
 */
class AndroidDeviceUnlocker(
    private val keyguard: KeyguardManager,
    private val wakeScreens: WakeScreens,
    private val main: CoroutineContext,
    private val logger: Logger,
    private val screenWait: Duration = BillingTimeouts().wakeScreen,
    private val promptLimit: Duration = PROMPT_LIMIT,
) : UnlockPort {
    override fun isKeyguardLocked(): Boolean = keyguard.isKeyguardLocked

    override suspend fun requestUnlock(): UnlockResult {
        val locked = isKeyguardLocked()
        val screen = if (locked) wakeScreens.await(screenWait) else null
        return when {
            !locked -> {
                UnlockResult.Succeeded
            }

            screen == null -> {
                logger.log(LogEvent.OperationFailed(UNLOCK, "wake screen not resumed"))
                UnlockResult.Failed
            }

            else -> {
                prompt(screen)
            }
        }
    }

    /** Shows the keyguard's prompt over [screen] and waits up to [promptLimit] for its answer (none: Failed). */
    private suspend fun prompt(screen: Activity): UnlockResult {
        val result =
            withTimeoutOrNull(promptLimit) {
                withContext(main) {
                    suspendCancellableCoroutine { waiting ->
                        val answered = AtomicBoolean(false)

                        fun answer(result: UnlockResult) {
                            if (answered.compareAndSet(false, true)) waiting.resume(result)
                        }
                        try {
                            keyguard.requestDismissKeyguard(
                                screen,
                                object : KeyguardManager.KeyguardDismissCallback() {
                                    override fun onDismissSucceeded() = answer(UnlockResult.Succeeded)

                                    override fun onDismissCancelled() = answer(UnlockResult.Failed)

                                    override fun onDismissError() = answer(UnlockResult.Failed)
                                },
                            )
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
                        ) {
                            logger.log(LogEvent.OperationFailed(UNLOCK, e::class.simpleName.orEmpty()))
                            answer(UnlockResult.Failed)
                        }
                    }
                }
            }
        if (result == null) logger.log(LogEvent.OperationFailed(UNLOCK, "no answer within $promptLimit"))
        return result ?: UnlockResult.Failed
    }

    companion object {
        /** How long a PIN prompt may stay unanswered (default taken, owner can change). */
        val PROMPT_LIMIT: Duration = 2.minutes
        private const val UNLOCK = "request unlock"
    }
}
