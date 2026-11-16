package com.yawnandpawn.app.ui.settings

import com.yawnandpawn.app.core.alarm.AlarmWriteLock
import com.yawnandpawn.app.core.config.SaveDefaultSetting
import com.yawnandpawn.app.core.config.SaveGlobalSetting
import com.yawnandpawn.app.core.config.SetBaseFee
import com.yawnandpawn.app.core.config.SetMaxSnoozes
import com.yawnandpawn.app.core.config.SetQuietTime
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.telemetry.SetAnalyticsConsent
import com.yawnandpawn.app.testing.FakeAlarmRepository
import com.yawnandpawn.app.testing.FakeClock
import com.yawnandpawn.app.testing.FakeConsentStore
import com.yawnandpawn.app.testing.FakeGlobalSettingsRepository
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.FakeMoneyFormatter
import com.yawnandpawn.app.testing.FakePendingChangeRepository
import com.yawnandpawn.app.testing.FakePriceCatalog
import com.yawnandpawn.app.testing.FakeTimeChangeSignal
import com.yawnandpawn.app.testing.FakeTimeZoneProvider
import com.yawnandpawn.app.testing.aPriceSnapshot
import com.yawnandpawn.app.ui.links.AppLinks
import com.yawnandpawn.app.ui.links.ExternalLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * 0.1.9: "Rate Yawn & Pawn" opens the Play listing in the Play Store app (`market://`), else the https listing in a
 * browser, else shows "No browser found." for 4 s; "Share Yawn & Pawn" shares the screen's message followed by the
 * listing's address. Both come from the one listing address in `config/app-links.properties` ([AppLinks.playListingUrl]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRateShareViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = FakeClock(Instant.parse("2027-03-06T18:00:00Z"))
    private val zone = FakeTimeZoneProvider(TimeZone.UTC)
    private val pending = FakePendingChangeRepository()
    private val settings = FakeGlobalSettingsRepository(GlobalSettings(), pending)
    private val consents = FakeConsentStore()
    private val sessionLock =
        SessionLockGuard(MutableStateFlow<SessionState>(SessionState.Idle), MutableStateFlow(true), MutableStateFlow(false))
    private val links = RecordingLinks()
    private val appLinks =
        AppLinks(
            supportEmail = "support@yawnandpawn.com",
            orderHistoryUrl = "https://play.google.com/store/account/orderhistory",
            privacyUrl = "https://yawnandpawn.com/privacy/",
            termsUrl = "https://yawnandpawn.com/terms/",
            supportUrl = "https://yawnandpawn.com/support/",
            websiteUrl = "https://yawnandpawn.com/",
            playListingUrl = LISTING,
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.viewModel(rateAndShare: RateAndShare? = RateAndShare(links, appLinks)): SettingsViewModel {
        val save = SaveGlobalSetting(settings, pending, FakeAlarmRepository(), clock, zone, AlarmWriteLock(), sessionLock)
        val viewModel =
            SettingsViewModel(
                globalSettings = settings,
                pendingChanges = pending,
                priceCatalog = FakePriceCatalog(aPriceSnapshot()),
                setBaseFee = SetBaseFee(save),
                setMaxSnoozes = SetMaxSnoozes(save),
                setQuietTime = SetQuietTime(save),
                clock = clock,
                timeZoneProvider = zone,
                timeChanges = FakeTimeChangeSignal(),
                moneyFormatter = FakeMoneyFormatter(),
                logger = FakeLogger(),
                defaults = DefaultSettingSetters(SaveDefaultSetting(settings, sessionLock)),
                consents = consents,
                setAnalyticsConsent = SetAnalyticsConsent(consents, sessionLock),
                rateAndShare = rateAndShare,
            )
        backgroundScope.launch(dispatcher) { viewModel.state.collect {} }
        return viewModel
    }

    private val SettingsViewModel.shown: SettingsUiState
        get() = assertNotNull(state.value, "the stored settings were read")

    @Test
    fun `the market link is the https listing's id on market details, without its other parameters`() {
        assertEquals("market://details?id=com.yawnandpawn.app", appLinks.playStoreAppUrl)
        assertEquals("market://details?id=com.yawnandpawn.app", appLinks.copy(playListingUrl = "$LISTING&hl=en").playStoreAppUrl)
    }

    @Test
    fun `Rate opens the listing in the Play Store app and nothing else`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.RateClicked)

            assertEquals(listOf("market://details?id=com.yawnandpawn.app"), links.playStore)
            assertEquals(emptyList(), links.urls)
            assertFalse(viewModel.shown.noBrowser)
        }

    @Test
    fun `with no Play Store, Rate opens the https listing in a browser`() =
        runTest {
            links.hasPlayStore = false
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.RateClicked)

            assertEquals(listOf(LISTING), links.urls)
            assertFalse(viewModel.shown.noBrowser)
        }

    @Test
    fun `with neither, Rate shows No browser found for 4 s`() =
        runTest {
            links.hasPlayStore = false
            links.hasBrowser = false
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.RateClicked)

            assertTrue(viewModel.shown.noBrowser)
            advanceTimeBy(SettingsViewModel.SNACKBAR_MILLIS - 1)
            assertTrue(viewModel.shown.noBrowser)
            advanceTimeBy(2)
            runCurrent()
            assertFalse(viewModel.shown.noBrowser)
        }

    @Test
    fun `Share shares the message followed by the listing's address`() =
        runTest {
            val viewModel = viewModel()

            viewModel.onIntent(SettingsIntent.ShareClicked("Yawn & Pawn: an alarm clock."))

            assertEquals(listOf("Yawn & Pawn: an alarm clock. $LISTING"), links.shared)
            assertFalse(viewModel.shown.noBrowser)
        }

    @Test
    fun `without the rows' sources a tap does nothing`() =
        runTest {
            val viewModel = viewModel(rateAndShare = null)

            viewModel.onIntent(SettingsIntent.RateClicked)
            viewModel.onIntent(SettingsIntent.ShareClicked("message"))

            assertTrue(links.playStore.isEmpty() && links.urls.isEmpty() && links.shared.isEmpty())
            assertFalse(viewModel.shown.noBrowser)
        }

    private class RecordingLinks : ExternalLinks {
        var hasPlayStore = true
        var hasBrowser = true
        val playStore = mutableListOf<String>()
        val urls = mutableListOf<String>()
        val shared = mutableListOf<String>()

        override fun openUrl(url: String): Boolean = hasBrowser.also { if (it) urls += url }

        override fun openInPlayStore(url: String): Boolean = hasPlayStore.also { if (it) playStore += url }

        override fun shareText(text: String): Boolean = true.also { shared += text }

        override fun sendEmail(
            to: String,
            subject: String,
            body: String,
        ): Boolean = error("not used")

        override fun copyText(text: String): Boolean = error("not used")
    }

    private companion object {
        const val LISTING = "https://play.google.com/store/apps/details?id=com.yawnandpawn.app"
    }
}
