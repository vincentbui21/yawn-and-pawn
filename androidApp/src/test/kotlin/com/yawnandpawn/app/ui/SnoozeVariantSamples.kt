package com.yawnandpawn.app.ui

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
import com.yawnandpawn.app.core.session.SnoozeAvailability
import com.yawnandpawn.app.core.time.TimeSnapshot
import com.yawnandpawn.app.testing.FakeLogger
import com.yawnandpawn.app.testing.aSession
import com.yawnandpawn.app.testing.aSessionConfig
import com.yawnandpawn.app.ui.wake.CheckInput
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.RingingUiState
import com.yawnandpawn.app.ui.wake.mathCheckUiState
import com.yawnandpawn.app.ui.wake.ringingUiState
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Story 4.7: every `button-snooze` variant, built the way `WakeActivity` builds it: the production
 * `snoozeAvailability` over a session and an env, mapped onto the Ringing screen and onto the Check screen's footer
 * (Math in Loud). [label] is the visible text, [talkBack] what TalkBack reads (null: the enabled button reads its label).
 */
enum class SnoozeVariant(
    val label: String,
    val talkBack: String?,
) {
    Available("Snooze · US$1.00", null),
    TestMode("Test · no charge", "Snooze unavailable, Test · no charge"),
    BeforeFirstUnlock("Unlock your phone to snooze", "Snooze unavailable, Unlock your phone to snooze"),
    MaxSnoozes("Snooze unavailable: max snoozes reached", "Snooze unavailable, max snoozes reached"),
    PriceCap("Snooze unavailable: price cap reached", "Snooze unavailable, price cap reached"),
    PaymentPending("Snooze unavailable: payment pending", "Snooze unavailable, payment pending"),

    // The amount actually paid (the purchase record, Story 4.10), formatted by the app: never today's Play price.
    Refunding("An earlier $0.99 payment is being refunded", "Snooze unavailable, An earlier $0.99 payment is being refunded"),
    RefundingNoAmount("An earlier payment is being refunded", "Snooze unavailable, An earlier payment is being refunded"),
    Offline("Snooze unavailable: offline", "Snooze unavailable, offline"),
    PricesNotLoaded("Prices not loaded yet", "Snooze unavailable, prices not loaded yet"),
    ;

    /** The file-name part of this variant's screenshots. */
    val shot: String get() = name.lowercase()
}

object SnoozeVariantSamples {
    private val utc = TimeZone.UTC
    private val at = Instant.parse("2027-03-03T06:15:00Z")
    private val start = TimeSnapshot(wallMillis = at.toEpochMilliseconds(), elapsedMillis = 1_000_000, bootCount = 1)
    private val firstProduct = SnoozeProducts.idOf(1)

    /**
     * Play's prices as Play formats them, "US$N.00", fetched an hour before the ring: unlike the app's own formatting of
     * USD on an en-US phone ("$N.00"), so the screenshots show that the button uses Play's string (review fix).
     */
    private val playPrices =
        PriceCatalogSnapshot(
            (1..SnoozeProducts.all.size).associate { tier ->
                SnoozeProducts.idOf(tier) to PriceEntry(SnoozeProducts.idOf(tier), "US$$tier.00", Money.of(tier, "USD"), at - 1.hours)
            },
        )

    private val env = SnoozeEnv(online = true, prices = playPrices, now = at, userUnlocked = true)

    /** A Math session (the default plan, seed 1), first ring, base fee $1, at most 5 snoozes. */
    private val session: SessionData =
        aSession(config = aSessionConfig(scheduledAt = at).copy(checkPlan = CheckPlan.default()), startedAt = start)

    private fun input(variant: SnoozeVariant): Pair<SessionData, SnoozeEnv> =
        when (variant) {
            SnoozeVariant.Available -> {
                session to env
            }

            SnoozeVariant.TestMode -> {
                session.copy(config = session.config.copy(testMode = true)) to env
            }

            SnoozeVariant.BeforeFirstUnlock -> {
                session to env.copy(userUnlocked = false)
            }

            SnoozeVariant.MaxSnoozes -> {
                session.copy(snoozesGranted = 5, ringIndex = 6) to env
            }

            // Production limits never reach the cap: a directly built config (B = 10, max 6), as in the core table test.
            SnoozeVariant.PriceCap -> {
                session.copy(config = session.config.copy(baseFeeTier = 10, maxSnoozes = 6), snoozesGranted = 5, ringIndex = 6) to env
            }

            SnoozeVariant.PaymentPending -> {
                session.copy(paymentPending = true) to env
            }

            SnoozeVariant.Refunding -> {
                val stranded = setOf(firstProduct)
                session.copy(declinedReuseProduct = firstProduct) to
                    env.copy(strandedProducts = stranded, refundingPrices = mapOf(firstProduct to Money(990_000, "USD")))
            }

            SnoozeVariant.RefundingNoAmount -> {
                session.copy(declinedReuseProduct = firstProduct) to env.copy(strandedProducts = setOf(firstProduct))
            }

            SnoozeVariant.Offline -> {
                session to env.copy(online = false)
            }

            SnoozeVariant.PricesNotLoaded -> {
                session to env.copy(prices = PriceCatalogSnapshot.EMPTY)
            }
        }

    /** The production policy's answer for [variant]. */
    fun availability(variant: SnoozeVariant): SnoozeAvailability =
        input(variant).let { (session, env) -> snoozeAvailability(session, env, UsdFeeLadder, FakeLogger()) }

    /** The Ringing screen of [variant]. */
    fun ringing(variant: SnoozeVariant): RingingUiState = ringingUiState(input(variant).first, availability(variant), utc)

    /** The Math Check screen in Loud of [variant], with the footer's snooze. */
    fun check(variant: SnoozeVariant): CheckUiState {
        val state = SessionState.Loud(input(variant).first)
        val now = TimeSnapshot(start.wallMillis + CHECK_AFTER_MILLIS, start.elapsedMillis + CHECK_AFTER_MILLIS, start.bootCount)
        return checkNotNull(mathCheckUiState(state, availability(variant), now, CheckInput()))
    }

    private const val CHECK_AFTER_MILLIS = 30_000L
}
