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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    private val firebase = Firebase()
    private val sink = RecordingAnalyticsSink()
    private val logger = FakeLogger()

    /** A started gate on the test's background scope, unconfined, so every change is applied at once. */
    private fun TestScope.gate(store: ConsentStore = consents): AnalyticsConsentGate {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return AnalyticsConsentGate(store, userLock, firebase, sink, scope, logger).also { it.start() }
    }

    /** Firebase's start; by default it could not turn collection off itself, so the gate does. */
    private class Firebase : FirebaseState {
        override val ready = MutableStateFlow(true)
        override var startedCollectionOff = false
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
    fun `when the start turned collection off, the gate calls nothing without consent and only turns it on with consent`() =
        runTest {
            firebase.startedCollectionOff = true
            gate()
            assertEquals(emptyList(), sink.calls)

            consents.setConsent(true)

            assertEquals(on, sink.calls)
        }

    @Test
    fun `without consent a start that could not turn collection off has the gate turn it off, and resets nothing`() =
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
            firebase.ready.value = false
            consents.setConsent(true)
            gate()
            assertEquals(emptyList(), sink.calls)

            firebase.ready.value = true

            assertEquals(on, sink.calls)
        }

    @Test
    fun `consent turned on and off before Firebase starts still resets once it has started`() =
        runTest {
            firebase.ready.value = false
            gate()
            consents.setConsent(true)
            consents.setConsent(false)

            firebase.ready.value = true

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
            firebase.ready.value = false
            consents.setConsent(true)
            gate()
            assertEquals(emptyList(), sink.calls)

            userLock.unlock()
            firebase.ready.value = true

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

    @Test
    fun `a failed collection off is tried again after the retry delay until it succeeds`() =
        runTest {
            sink.failure = IllegalStateException("not initialized")
            gate()
            advanceTimeBy(AnalyticsConsentGate.RETRY_DELAY)
            runCurrent()
            assertEquals(2, logger.events.count { it == LogEvent.OperationFailed("apply usage stats consent", "IllegalStateException") })
            assertEquals(emptyList(), sink.calls)

            sink.failure = null
            advanceTimeBy(AnalyticsConsentGate.RETRY_DELAY)
            runCurrent()

            assertEquals(listOf("collection false"), sink.calls)
        }

    @Test
    fun `a call that keeps failing is tried at most MAX_RETRIES more times, then at the next change`() =
        runTest {
            sink.failure = IllegalStateException("not initialized")
            gate()

            repeat(AnalyticsConsentGate.MAX_RETRIES * 2) {
                advanceTimeBy(AnalyticsConsentGate.RETRY_DELAY)
                runCurrent()
            }

            assertEquals(1 + AnalyticsConsentGate.MAX_RETRIES, logger.events.size, "${logger.events}")
            sink.failure = null
            consents.setConsent(true)
            assertEquals(on, sink.calls)
        }

    /** Review fix 7: the no-consent default of an unreadable choice turns collection off but never deletes the data. */
    @Test
    fun `consent on, then the default of a read error, then on again turns collection off and on but never resets`() =
        runTest {
            val emitted = MutableSharedFlow<AnalyticsConsent>()
            val flaky =
                object : ConsentStore by consents {
                    override fun observe() = emitted
                }
            gate(flaky)
            emitted.subscriptionCount.first { it > 0 }

            emitted.emit(AnalyticsConsent(consent = true, asked = true))
            emitted.emit(AnalyticsConsent())
            emitted.emit(AnalyticsConsent(consent = true, asked = true))

            assertEquals(on + listOf("collection false") + on, sink.calls)
        }

    @Test
    fun `only the user's own no after a yes resets the data`() =
        runTest {
            val emitted = MutableSharedFlow<AnalyticsConsent>()
            val store =
                object : ConsentStore by consents {
                    override fun observe() = emitted
                }
            gate(store)
            emitted.subscriptionCount.first { it > 0 }

            emitted.emit(AnalyticsConsent(consent = true, asked = true))
            emitted.emit(AnalyticsConsent())
            emitted.emit(AnalyticsConsent(consent = false, asked = true))

            assertEquals(on + listOf("collection false", "collection false", "reset"), sink.calls)
        }
}
