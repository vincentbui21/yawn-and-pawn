package com.yawnandpawn.app.ui.wake

import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.format.Money
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** Why snooze cannot be bought right now ("Snooze unavailable: {reason}"). */
enum class SnoozeUnavailableReason { Offline, MaxSnoozesReached, PriceCapReached, PaymentPending, PricesNotLoaded }

/** What the one `button-snooze` shows on every wake screen. */
sealed interface SnoozeOffer {
    /** "Snooze · {price}": opens the confirm sheet. */
    data class Available(
        val price: Money,
    ) : SnoozeOffer

    /** Disabled with its reason. */
    data class Unavailable(
        val reason: SnoozeUnavailableReason,
    ) : SnoozeOffer

    /** Test alarm: "Test · no charge". */
    data object TestMode : SnoozeOffer

    /** Before the first unlock after a reboot: lock icon, "Unlock your phone to snooze". */
    data object LockedBeforeUnlock : SnoozeOffer

    /** After "Not now" on an already-paid purchase: "An earlier {price} payment is being refunded". */
    data class StrandedRefund(
        val price: Money,
    ) : SnoozeOffer
}

/** "Snooze {n} of {max} · {paid} paid this morning", after at least one snooze. */
data class SessionLine(
    val snoozeNumber: Int,
    val maxSnoozes: Int,
    val paid: Money,
)

/** A payment outcome shown as a wake `snackbar` (no action, stays until the next tap or at least 10 s). */
enum class WakeMessage { UnlockFailed, PaymentCancelled, PaymentError, PaymentOffline, PaymentPending }

/** The three states of `sheet-snooze-confirm`. */
sealed interface SnoozeSheet {
    /** Price, next price (none at the last snooze), nudge, tax note where prices exclude tax. */
    data class Confirm(
        val minutes: Int,
        val price: Money,
        val nextPrice: Money?,
        val showTaxNote: Boolean = false,
    ) : SnoozeSheet

    /** "Pay" on a locked phone: "Unlock to pay {price}" while the keyguard is up. */
    data class Unlocking(
        val price: Money,
    ) : SnoozeSheet

    /** A stranded purchase at this price exists: "You already paid {price} earlier ...". */
    data class AlreadyPaid(
        val price: Money,
    ) : SnoozeSheet
}

/** Notes a wake screen can carry (`note-inline`, Sunrise tokens). */
enum class WakeNote { DirectBoot, PhoneCall }

/** Ringing: label, clock, date, "I'm up" and snooze, plus the confirm sheet and payment messages over it. */
data class RingingUiState(
    val time: LocalTime,
    val date: LocalDate,
    val label: String? = null,
    val snooze: SnoozeOffer,
    val sessionLine: SessionLine? = null,
    val note: WakeNote? = null,
    val sheet: SnoozeSheet? = null,
    val message: WakeMessage? = null,
)

/** The grace window in the check header. */
sealed interface GraceState {
    /** Muted: `countdown-ring` with [secondsLeft] of [totalSeconds]. */
    data class Running(
        val secondsLeft: Int,
        val totalSeconds: Int,
    ) : GraceState

    /** The alarm is back at full volume: bell and "Time's up. Alarm's back on until you finish." */
    data object Expired : GraceState
}

enum class MathOperator { Plus, Times }

enum class MemoryPhase { Watch, YourTurn }

enum class HouseHuntResult { None, Checking, Matched, NoMatch }

/** The check being solved. */
sealed interface CheckContent {
    val type: CheckType

    data class Math(
        val problemNumber: Int,
        val problemCount: Int,
        val left: Int,
        val right: Int,
        val operator: MathOperator,
        val answer: String = "",
        val wrong: Boolean = false,
    ) : CheckContent {
        override val type: CheckType get() = CheckType.Math
    }

    /** [pool] holds the letters still to place (`null` = moved into a slot); [slots] the answer so far. */
    data class WordUnscramble(
        val wordNumber: Int,
        val wordCount: Int,
        val pool: List<Char?>,
        val slots: List<Char?>,
        val wrong: Boolean = false,
    ) : CheckContent {
        override val type: CheckType get() = CheckType.WordUnscramble
    }

    /** A 3x3 grid; [litTile] (1 to 9) is highlighted while the sequence plays; [numbered] is the TalkBack variant. */
    data class MemorySequence(
        val round: Int,
        val roundCount: Int,
        val phase: MemoryPhase,
        val litTile: Int? = null,
        val numbered: Boolean = false,
        val wrong: Boolean = false,
    ) : CheckContent {
        override val type: CheckType get() = CheckType.MemorySequence
    }

    data class QrBarcode(
        val cameraAvailable: Boolean = true,
        val wrongCode: Boolean = false,
        val torchOn: Boolean = false,
    ) : CheckContent {
        override val type: CheckType get() = CheckType.QrBarcode
    }

    data class HouseHunt(
        val cameraAvailable: Boolean = true,
        val result: HouseHuntResult = HouseHuntResult.None,
    ) : CheckContent {
        override val type: CheckType get() = CheckType.HouseHunt
    }
}

/** A check screen: grace header, the check, and the footer (fallback link, snooze). */
data class CheckUiState(
    val grace: GraceState,
    val content: CheckContent,
    val snooze: SnoozeOffer,
    /** "Can't do this check?": at once when the camera is unavailable, else after 5 failed attempts. */
    val showFallbackLink: Boolean = false,
    val note: WakeNote? = null,
    val sheet: SnoozeSheet? = null,
    val message: WakeMessage? = null,
)

/** Success after the check. */
sealed interface SuccessKind {
    /** Zero snoozes: the streak number, "days in a row" and "Up on time." (only "Up on time." before streaks, [streakDays] 0). */
    data class OnTime(
        val streakDays: Int,
    ) : SuccessKind

    /** After at least one paid snooze; [paidThisMorning] null shows no "{paid} paid this morning" line (Money is Epic 4). */
    data class AfterSnooze(
        val paidThisMorning: Money?,
    ) : SuccessKind

    /** A test alarm. */
    data object Test : SuccessKind
}

data class SuccessUiState(
    val kind: SuccessKind,
    /** A pending payment was never used: "Your pending payment wasn't used. Google refunds it automatically." */
    val pendingNotUsed: Boolean = false,
)

data class SnoozedUiState(
    val nextRingAt: LocalTime,
)

/** The non-camera checks the Fallback check picker offers, Math always first. */
data class FallbackPickerUiState(
    val options: List<CheckType> = listOf(CheckType.Math, CheckType.WordUnscramble, CheckType.MemorySequence),
)

/** Everything the user can do on the wake screens. */
sealed interface WakeIntent {
    data object ImUpClicked : WakeIntent

    data object SnoozeClicked : WakeIntent

    /** "Pay {price} and snooze" (confirm) or "Use it" (already paid). */
    data object SheetUpperClicked : WakeIntent

    /** "I'll get up" (confirm), "Not now" (already paid), "Cancel" (unlocking), swipe down, Back or a tap outside. */
    data object SheetDismissed : WakeIntent

    data class DigitTapped(
        val digit: Int,
    ) : WakeIntent

    data object DeleteDigit : WakeIntent

    data object SubmitAnswer : WakeIntent

    data class LetterTapped(
        val index: Int,
    ) : WakeIntent

    data class SlotTapped(
        val index: Int,
    ) : WakeIntent

    data object ShuffleLetters : WakeIntent

    data object ClearLetters : WakeIntent

    data class TileTapped(
        val tile: Int,
    ) : WakeIntent

    data object TorchToggled : WakeIntent

    data object ShutterClicked : WakeIntent

    data object FallbackLinkClicked : WakeIntent

    data class FallbackChosen(
        val type: CheckType,
    ) : WakeIntent

    data object FallbackPickerClosed : WakeIntent

    data object DoneClicked : WakeIntent
}
