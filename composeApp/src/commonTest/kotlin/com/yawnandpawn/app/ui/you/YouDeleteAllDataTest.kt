package com.yawnandpawn.app.ui.you

import com.yawnandpawn.app.core.billing.PriceRefreshJobs
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.reset.DeleteAllData
import com.yawnandpawn.app.core.reset.PendingDeletion
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.testing.FakeAlarmScheduler
import com.yawnandpawn.app.testing.FakeAnalyticsDataReset
import com.yawnandpawn.app.testing.FakeBackgroundWork
import com.yawnandpawn.app.testing.FakeDeletionMarker
import com.yawnandpawn.app.testing.FakeInstallIdProvider
import com.yawnandpawn.app.testing.FakeLocalDataEraser
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.SchedulerCall
import com.yawnandpawn.app.testing.aDeleteAllData
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.ui.links.AppLinks
import com.yawnandpawn.app.ui.links.ExternalLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Story 5.9: the You tab's "Delete all data" row, its `dialog-confirm` and the real use case on fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class YouDeleteAllDataTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val state = MutableStateFlow<SessionState>(SessionState.Idle)
    private val restored = MutableStateFlow(true)
    private val guard = SessionLockGuard(state, restored, MutableStateFlow(false))
    private val marker = FakeDeletionMarker()
    private val scheduler = FakeAlarmScheduler()
    private val eraser = FakeLocalDataEraser()
    private val installIds = FakeInstallIdProvider()
    private val work = FakeBackgroundWork()
    private val logger = FakeLogger()
    private val analytics = FakeAnalyticsDataReset()
    private val pendingDeletion = PendingDeletion(initiallyPending = false)

    private val deleteAllData =
        aDeleteAllData(
            sessionLock = guard,
            marker = marker,
            eraser = eraser,
            scheduler = scheduler,
            installIds = installIds,
            work = work,
            analytics = analytics,
            logger = logger,
            pendingDeletion = pendingDeletion,
        )

    /** No link is opened in these tests. */
    private val links =
        object : ExternalLinks {
            override fun openUrl(url: String): Boolean = error("not used")

            override fun sendEmail(
                to: String,
                subject: String,
                body: String,
            ): Boolean = error("not used")

            override fun copyText(text: String): Boolean = error("not used")
        }

    private val appLinks =
        AppLinks(
            supportEmail = "help@example.com",
            orderHistoryUrl = "https://example.com/orders",
            privacyUrl = "https://example.com/privacy/",
            termsUrl = "https://example.com/terms/",
            supportUrl = "https://example.com/support/",
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The view model, and every completed deletion the app is told about (it resets to Home on each). */
    private fun TestScope.viewModel(): Pair<YouViewModel, List<Unit>> {
        val viewModel = YouViewModel(links, appLinks, MonotonicClock { 0L }, deleteAllData, guard)
        val completed = mutableListOf<Unit>()
        backgroundScope.launch(dispatcher) { pendingDeletion.completed.collect { completed += it } }
        return viewModel to completed
    }

    @Test
    fun `Delete all data opens the dialog, and Keep it closes it and deletes nothing`() =
        runTest(dispatcher) {
            val (viewModel, completed) = viewModel()
            assertFalse(viewModel.state.value.showDeleteDialog)
            assertTrue(viewModel.state.value.deleteEnabled)

            viewModel.onIntent(YouIntent.DeleteAllClicked)
            assertTrue(viewModel.state.value.showDeleteDialog)

            viewModel.onIntent(YouIntent.DeleteCancelled)

            assertFalse(viewModel.state.value.showDeleteDialog)
            assertEquals(0, eraser.alarmDataDeletes)
            assertEquals(emptyList<SchedulerCall>(), scheduler.calls)
            assertEquals(0, installIds.resets)
            assertEquals(emptyList<Unit>(), completed)
        }

    @Test
    fun `Delete closes the dialog, deletes everything once and the app is told to go back to Home`() =
        runTest(dispatcher) {
            val (viewModel, completed) = viewModel()

            viewModel.onIntent(YouIntent.DeleteAllClicked)
            viewModel.onIntent(YouIntent.DeleteConfirmed)
            // A second tap of the closed dialog does nothing.
            viewModel.onIntent(YouIntent.DeleteConfirmed)

            assertFalse(viewModel.state.value.showDeleteDialog)
            assertEquals(1, eraser.alarmDataDeletes)
            assertEquals(1, eraser.settingsClears)
            assertEquals(1, installIds.resets)
            assertFalse(marker.present)
            assertEquals(emptyMap<Int, Long>(), scheduler.armed)
            assertEquals(listOf(PriceRefreshJobs.NOW), work.enqueued)
            assertEquals(1, analytics.resets, "the usage stats data and id are reset (Story 5.6)")
            assertEquals(listOf(Unit), completed)
            assertFalse(pendingDeletion.pending.value)
            assertTrue(viewModel.state.value.deleteEnabled, "enabled again once the deletion ran")
        }

    @Test
    fun `while the session is not Idle the row is disabled, the dialog closes and Delete deletes nothing`() =
        runTest(dispatcher) {
            val (viewModel, completed) = viewModel()
            val notIdle =
                listOf<Pair<String, () -> Unit>>(
                    "Snoozed" to { state.value = SessionState.Snoozed(aSession()) },
                    "Completed waiting for history" to { state.value = SessionState.Completed(aSession()) },
                    "not restored" to { restored.value = false },
                )

            notIdle.forEach { (case, arrange) ->
                viewModel.onIntent(YouIntent.DeleteAllClicked)
                assertTrue(viewModel.state.value.showDeleteDialog, case)
                arrange()

                assertFalse(viewModel.state.value.deleteEnabled, case)
                assertFalse(viewModel.state.value.showDeleteDialog, "$case: the open dialog closes")
                viewModel.onIntent(YouIntent.DeleteAllClicked)
                assertFalse(viewModel.state.value.showDeleteDialog, "$case: a disabled row opens nothing")
                viewModel.onIntent(YouIntent.DeleteConfirmed)

                state.value = SessionState.Idle
                restored.value = true
                assertTrue(viewModel.state.value.deleteEnabled, case)
            }

            assertEquals(0, eraser.alarmDataDeletes)
            assertFalse(marker.present)
            assertEquals(emptyList<Unit>(), completed)
            assertEquals(emptyList<LogEvent>(), logger.events, "nothing ran, nothing failed")
        }

    @Test
    fun `a failed deletion is logged and holds the app for the resume, and the app is not told it completed`() =
        runTest(dispatcher) {
            val (viewModel, completed) = viewModel()
            eraser.failure = DomainError.StorageFailure("disk full")
            viewModel.onIntent(YouIntent.DeleteAllClicked)

            viewModel.onIntent(YouIntent.DeleteConfirmed)

            assertTrue(marker.present, "the marker stays for the resume")
            assertTrue(pendingDeletion.pending.value, "a half-done delete holds the app (review item 1)")
            assertEquals(emptyList<Unit>(), completed)
            assertEquals(
                listOf<LogEvent>(LogEvent.OperationFailed.of(DeleteAllData.OPERATION, DomainError.StorageFailure("disk full"))),
                logger.events,
            )
        }
}
