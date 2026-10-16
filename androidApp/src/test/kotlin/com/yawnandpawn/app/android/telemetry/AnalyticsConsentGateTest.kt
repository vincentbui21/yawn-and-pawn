package com.yawnandpawn.app.android.telemetry

import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.telemetry.AnalyticsConsent
import com.yawnandpawn.app.core.telemetry.ConsentStore
import com.yawnandpawn.app.testing.FakeConsentStore
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeUserLockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Story 5.6 (AD-15): Analytics collects only with consent, after the unlock, once Firebase has started; turning consent
 * off disables collection and resets the data. Robolectric, on a recording facade (no Firebase).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AnalyticsConsentGateTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val consents = FakeConsentStore()
    private val userLock = FakeUserLockState(unlocked = true)
    private val firebaseStarted = MutableStateFlow(true)
    private val sink = RecordingAnalyticsSink()
    private val logger = FakeLogger()

    /** A started gate on the test's background scope, unconfined, so every change is applied at once. */
    private fun TestScope.gate(store: ConsentStore = consents): AnalyticsConsentGate {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return AnalyticsConsentGate(store, userLock, firebaseStarted, sink, scope, logger).also { it.start() }
    }

    private val on = listOf("collection true", "grant analytics storage only")

    @Test
    fun `consent on, unlocked and Firebase started turns collection on with analytics storage only`() =
        runTest {
            consents.setConsent(true)

            gate()

            assertEquals(on, sink.calls)
        }

    @Test
    fun `without consent every start turns collection off and resets nothing`() =
        runTest {
            gate()

            assertEquals(listOf("collection false"), sink.calls)
        }

    @Test
    fun `turning consent off disables collection, then resets the analytics data`() =
        runTest {
            consents.setConsent(true)
            gate()

            consents.setConsent(false)

            assertEquals(on + listOf("collection false", "reset"), sink.calls)
        }

    @Test
    fun `turning consent on later enables collection, and the same choice again calls nothing`() =
        runTest {
            gate()

            consents.setConsent(true)
            consents.setConsent(true)

            assertEquals(listOf("collection false") + on, sink.calls)
        }

    @Test
    fun `nothing is called before Firebase has started, then the stored choice applies`() =
        runTest {
            firebaseStarted.value = false
            consents.setConsent(true)
            gate()
            assertEquals(emptyList(), sink.calls)

            firebaseStarted.value = true

            assertEquals(on, sink.calls)
        }

    @Test
    fun `consent turned on and off before Firebase starts still resets once it has started`() =
        runTest {
            firebaseStarted.value = false
            gate()
            consents.setConsent(true)
            consents.setConsent(false)

            firebaseStarted.value = true

            assertEquals(listOf("collection false", "reset"), sink.calls)
        }

    @Test
    fun `without a Firebase configuration (a sink never ready) nothing is called`() =
        runTest {
            sink.isReady = false
            consents.setConsent(true)

            gate()
            consents.setConsent(false)

            assertEquals(emptyList(), sink.calls)
        }

    @Test
    fun `consent before the first unlock turns collection on only once the unlock has started Firebase`() =
        runTest {
            userLock.unlocked = false
            firebaseStarted.value = false
            consents.setConsent(true)
            gate()
            assertEquals(emptyList(), sink.calls)

            userLock.unlock()
            firebaseStarted.value = true

            assertEquals(on, sink.calls)
        }

    @Test
    fun `a locked user never gets collection turned on, even with consent`() =
        runTest {
            userLock.unlocked = false
            consents.setConsent(true)

            gate()

            assertEquals(listOf("collection false"), sink.calls)
        }

    @Test
    fun `an unreadable consent counts as no consent and is logged`() =
        runTest {
            val broken =
                object : ConsentStore by consents {
                    override fun observe() = flow<AnalyticsConsent> { throw IllegalStateException("disk unreadable") }
                }

            gate(broken)

            assertEquals(listOf("collection false"), sink.calls)
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("read usage stats consent", "IllegalStateException")), logger.events)
        }

    @Test
    fun `a failing Analytics call is logged and tried again at the next change`() =
        runTest {
            sink.failure = IllegalStateException("not initialized")
            gate()
            assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("apply usage stats consent", "IllegalStateException")), logger.events)

            sink.failure = null
            consents.setConsent(true)

            assertEquals(on, sink.calls)
        }

    @Test
    fun `start runs once, and stop stops following the choice`() =
        runTest {
            val gate = gate()
            gate.start()
            assertEquals(listOf("collection false"), sink.calls)

            gate.stop()
            consents.setConsent(true)

            assertEquals(listOf("collection false"), sink.calls)
        }
}
