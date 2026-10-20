package com.yawnandpawn.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.Money
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.billing.PriceEntry
import com.yawnandpawn.app.core.billing.SnoozeEnv
import com.yawnandpawn.app.core.billing.SnoozeProducts
import com.yawnandpawn.app.core.billing.UsdFeeLadder
import com.yawnandpawn.app.core.billing.snoozeAvailability
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.session.SessionData
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.screenshotOptions
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.ui.theme.PpsThemeMode
import com.yawnandpawn.app.ui.wake.CheckInput
import com.yawnandpawn.app.ui.wake.CheckScreen
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.RingingScreen
import com.yawnandpawn.app.ui.wake.RingingUiState
import com.yawnandpawn.app.ui.wake.SnoozedScreen
import com.yawnandpawn.app.ui.wake.SnoozedUiState
import com.yawnandpawn.app.ui.wake.SuccessKind
import com.yawnandpawn.app.ui.wake.SuccessScreen
import com.yawnandpawn.app.ui.wake.SuccessUiState
import com.yawnandpawn.app.ui.wake.mathCheckUiState
import com.yawnandpawn.app.ui.wake.ringingUiState
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Story 4.15 states built the way `WakeActivity` builds them (the production availability over a paid session): the
 * re-ring with its session line in USD (1 of 5) and EUR (4 of 5), the Check footer during a paid session, the Snoozed
 * note, Success after a paid snooze and Success with a pending payment not used. Sunrise, 100% and 200% font scale; at
 * 200% "I'm up" and Snooze stay whole on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-mdpi")
class SnoozedScreenshotTest {
    @get:Rule(order = 0)
    val appTeardown = StopAppRule()

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    private fun shot(name: String) =
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png", roborazziOptions = screenshotOptions)

    /** "I'm up" (72 dp) and Snooze (64 dp) are on screen whole, even at 200%. */
    private fun assertWakeActions(snooze: String) {
        val root = composeRule.onRoot().getBoundsInRoot()
        listOf("I'm up" to 72.dp, snooze to 64.dp).forEach { (label, min) ->
            val node = composeRule.onNodeWithText(label).assertIsDisplayed().assertIsEnabled()
            val bounds = node.getBoundsInRoot()
            assertTrue(bounds.bottom - bounds.top >= min, "$label is at least $min")
            assertTrue(bounds.top >= root.top && bounds.bottom <= root.bottom, "$label is whole on screen")
        }
    }

    private fun ringing(
        name: String,
        state: RingingUiState,
        line: String,
        snooze: String,
    ) = withScreen(PpsThemeMode.Light, content = { RingingScreen(state = state, is24Hour = false, onIntent = {}) }) {
        composeRule.onNodeWithText(line).assertIsDisplayed()
        assertWakeActions(snooze)
        shot(name)
    }

    @Test
    fun `re-ring after one USD snooze`() =
        ringing("wake_ringing_session_line_usd_sunrise", PaidSamples.usdRing, PaidSamples.USD_LINE, "Snooze · US$2.00")

    @Test
    @Config(fontScale = 2.0f)
    fun `re-ring after one USD snooze at 200 percent`() =
        ringing("wake_ringing_session_line_usd_sunrise_font200", PaidSamples.usdRing, PaidSamples.USD_LINE, "Snooze · US$2.00")

    @Test
    fun `re-ring after four EUR snoozes`() =
        ringing("wake_ringing_session_line_eur_sunrise", PaidSamples.eurRing, PaidSamples.EUR_LINE, "Snooze · 5,95 €")

    @Test
    @Config(fontScale = 2.0f)
    fun `re-ring after four EUR snoozes at 200 percent`() =
        ringing("wake_ringing_session_line_eur_sunrise_font200", PaidSamples.eurRing, PaidSamples.EUR_LINE, "Snooze · 5,95 €")

    private fun check(name: String) =
        withScreen(PpsThemeMode.Light, content = { CheckScreen(state = PaidSamples.check, onIntent = {}) }) {
            composeRule.onNodeWithText("Problem 1 of 3").assertExists()
            composeRule.onNodeWithText("Snooze · US$2.00").assertIsDisplayed().assertIsEnabled()
            shot(name)
        }

    @Test
    fun `check footer during a paid session`() = check("wake_check_paid_session_sunrise")

    @Test
    @Config(fontScale = 2.0f)
    fun `check footer during a paid session at 200 percent`() = check("wake_check_paid_session_sunrise_font200")

    private fun snoozed(name: String) =
        withScreen(PpsThemeMode.Light, content = { SnoozedScreen(state = SnoozedUiState(LocalTime(7, 39)), is24Hour = false) }) {
            composeRule.onNodeWithText("Snoozed. Next ring at 7:39 AM.").assertIsDisplayed()
            shot(name)
        }

    @Test
    fun `snoozed`() = snoozed("wake_snoozed_sunrise")

    @Test
    @Config(fontScale = 2.0f)
    fun `snoozed at 200 percent`() = snoozed("wake_snoozed_sunrise_font200")

    private fun success(
        name: String,
        state: SuccessUiState,
        vararg texts: String,
    ) = withScreen(PpsThemeMode.Light, content = { SuccessScreen(state = state, onIntent = {}, basic = true) }) {
        texts.forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        shot(name)
    }

    @Test
    fun `success after paid snoozes`() =
        success(
            "wake_success_after_snooze_paid_sunrise",
            PaidSamples.successPaid,
            "You're up. That's what counts.",
            "$3.00 paid this morning",
        )

    @Test
    @Config(fontScale = 2.0f)
    fun `success after paid snoozes at 200 percent`() =
        success(
            "wake_success_after_snooze_paid_sunrise_font200",
            PaidSamples.successPaid,
            "You're up. That's what counts.",
            "$3.00 paid this morning",
        )

    @Test
    fun `success with a pending payment not used`() =
        success(
            "wake_success_pending_not_used_sunrise",
            PaidSamples.successPending,
            PaidSamples.PENDING_NOTE,
            "$1.00 paid this morning",
        )

    @Test
    @Config(fontScale = 2.0f)
    fun `success with a pending payment not used at 200 percent`() =
        success(
            "wake_success_pending_not_used_sunrise_font200",
            PaidSamples.successPending,
            PaidSamples.PENDING_NOTE,
            "$1.00 paid this morning",
        )
}

/** Paid sessions for Story 4.15, through the production availability and the wake mappings. A 06:15 UTC alarm. */
private object PaidSamples {
    const val USD_LINE = "Snooze 1 of 5 · $1.00 paid this morning"
    const val EUR_LINE = "Snooze 4 of 5 · €11.90 paid this morning"
    const val PENDING_NOTE = "Your pending payment wasn't used. Google refunds it automatically."

    private val utc = TimeZone.UTC
    private val at = Instant.parse("2027-03-03T06:15:00Z")
    private val start = TimeSnapshot(wallMillis = at.toEpochMilliseconds(), elapsedMillis = 1_000_000, bootCount = 1)

    private fun catalog(price: (Int) -> Pair<String, Money>) =
        PriceCatalogSnapshot(
            (1..SnoozeProducts.all.size).associate { tier ->
                val (text, money) = price(tier)
                SnoozeProducts.idOf(tier) to PriceEntry(SnoozeProducts.idOf(tier), text, money, at - 1.hours)
            },
        )

    // Play's own strings, as a US and a Finnish account see them.
    private val usd = SnoozeEnv(online = true, prices = catalog { "US$$it.00" to Money.of(it, "USD") }, now = at, userUnlocked = true)
    private val eur =
        SnoozeEnv(
            online = true,
            prices = catalog { tier -> eurCents(tier).let { "${it / 100},${(it % 100).toString().padStart(2, '0')} €" } to eurOf(tier) },
            now = at,
            userUnlocked = true,
        )

    /** 1.19 € × tier, rounded to the cent: 1,19 · 2,38 · 3,57 · 4,76 · 5,95. */
    private fun eurOf(tier: Int): Money = Money(eurCents(tier) * 10_000L, "EUR")

    private fun eurCents(tier: Int): Int = 119 * tier

    private val base: SessionData =
        aSession(config = aSessionConfig(label = "Stand-up", scheduledAt = at).copy(checkPlan = CheckPlan.default()), startedAt = start)

    private fun paid(
        snoozes: Int,
        price: (Int) -> Money,
    ): SessionData = base.copy(snoozesGranted = snoozes, ringIndex = snoozes + 1, paid = (1..snoozes).map(price))

    private val usdSession = paid(1) { Money.of(it, "USD") }
    private val eurSession = paid(4, ::eurOf)

    private fun ring(
        session: SessionData,
        env: SnoozeEnv,
    ): RingingUiState = ringingUiState(session, snoozeAvailability(session, env, UsdFeeLadder, FakeLogger()), utc, reRingAt(session))

    /** When the re-ring after [session]'s paid snoozes rings: what its clock shows (Epic 4 device follow-up 1). */
    private fun reRingAt(session: SessionData): Instant =
        session.config.scheduledAt + (session.snoozesGranted * session.config.snoozeLengthMinutes).minutes

    val usdRing: RingingUiState = ring(usdSession, usd)
    val eurRing: RingingUiState = ring(eurSession, eur)

    /** Math in Loud on the re-ring, with the footer's next price. */
    val check: CheckUiState =
        checkNotNull(
            mathCheckUiState(
                SessionState.Loud(usdSession),
                snoozeAvailability(usdSession, usd, UsdFeeLadder, FakeLogger()),
                TimeSnapshot(start.wallMillis + 30_000, start.elapsedMillis + 30_000, start.bootCount),
                CheckInput(),
            ),
        )

    val successPaid = SuccessUiState(SuccessKind.AfterSnooze(listOf(Money.of(1, "USD"), Money.of(2, "USD"))))
    val successPending = SuccessUiState(SuccessKind.AfterSnooze(listOf(Money.of(1, "USD"))), pendingNotUsed = true)
}
