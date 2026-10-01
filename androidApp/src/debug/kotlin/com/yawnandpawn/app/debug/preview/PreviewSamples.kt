package com.yawnandpawn.app.debug.preview

import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.editor.CheckMode
import com.yawnandpawn.app.ui.editor.EditorForm
import com.yawnandpawn.app.ui.editor.EditorPane
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.FullEditorSections
import com.yawnandpawn.app.ui.editor.MotivationChoice
import com.yawnandpawn.app.ui.editor.MotivationTiming
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.Money
import com.yawnandpawn.app.ui.home.AlarmCard
import com.yawnandpawn.app.ui.home.DisableUnderLock
import com.yawnandpawn.app.ui.home.HomeHero
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.sound.SoundOption
import com.yawnandpawn.app.ui.sound.SoundPickerUiState
import com.yawnandpawn.app.ui.sound.SoundSource
import com.yawnandpawn.app.ui.wake.CheckContent
import com.yawnandpawn.app.ui.wake.CheckUiState
import com.yawnandpawn.app.ui.wake.FallbackPickerUiState
import com.yawnandpawn.app.ui.wake.GraceState
import com.yawnandpawn.app.ui.wake.HouseHuntResult
import com.yawnandpawn.app.ui.wake.MathOperator
import com.yawnandpawn.app.ui.wake.MemoryPhase
import com.yawnandpawn.app.ui.wake.RingingUiState
import com.yawnandpawn.app.ui.wake.SessionLine
import com.yawnandpawn.app.ui.wake.SnoozeOffer
import com.yawnandpawn.app.ui.wake.SnoozeSheet
import com.yawnandpawn.app.ui.wake.SnoozeUnavailableReason
import com.yawnandpawn.app.ui.wake.SnoozedUiState
import com.yawnandpawn.app.ui.wake.SuccessKind
import com.yawnandpawn.app.ui.wake.SuccessUiState
import com.yawnandpawn.app.ui.wake.WakeMessage
import com.yawnandpawn.app.ui.wake.WakeNote
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.util.Currency
import java.util.Locale

/**
 * Fake data for the design preview and its screenshot tests: realistic states from EXPERIENCE.md Key Flows (Linh's
 * 7:30 weekday alarm, Marco's 5:45). Prices are [Money] in the phone's local currency (US dollars when the locale has
 * none), so they go through the normal price formatting and never carry a hard-coded symbol. The base fee is a
 * realistic amount in that currency (design preview feedback item 5: not "₫1" on a Vietnamese phone).
 */
object PreviewSamples {
    private val currency: String =
        runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrNull() ?: "USD"

    /** The fake base fee B in [currency]: snooze N costs B x N. */
    private val baseFee: Int = baseFeeFor(currency)

    /** The price of snooze [n] (B x n). */
    fun price(n: Int): Money = Money.of(baseFee * n, currency)

    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    // Home -------------------------------------------------------------------------------------------------------

    val alarmCards =
        listOf(
            AlarmCard("1", LocalTime(5, 45), weekdays, "Early shift", listOf(CheckType.Math), enabled = true),
            AlarmCard("2", LocalTime(7, 30), weekdays, "Stand-up", listOf(CheckType.Math, CheckType.QrBarcode), enabled = true),
            AlarmCard(
                "3",
                LocalTime(9, 0),
                setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
                null,
                listOf(CheckType.WordUnscramble),
                enabled = false,
            ),
            // More cards, so Home scrolls and its header collapses on a phone (design preview feedback item 18).
            AlarmCard(
                "4",
                LocalTime(6, 15),
                setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY),
                "Gym",
                listOf(CheckType.MemorySequence),
                true,
            ),
            AlarmCard("5", LocalTime(10, 30), emptySet(), "Dentist", listOf(CheckType.Math), enabled = true),
            AlarmCard("6", LocalTime(13, 0), weekdays, "Lunch walk", listOf(CheckType.HouseHunt), enabled = false),
        ).sortedBy { it.time }

    val homeList =
        HomeUiState(
            hero = HomeHero(streakDays = 12, paidThisWeek = null),
            nextAlarm = Countdown.HoursMinutes(7, 12),
            alarms = alarmCards,
        )

    val homePaid = homeList.copy(hero = HomeHero(streakDays = 1, paidThisWeek = price(3)), nextAlarm = Countdown.Minutes(45))

    val homeDaysAway = homeList.copy(nextAlarm = Countdown.DaysHours(2, 3))

    val homeEmpty = HomeUiState()

    val homeMissed = homeList.copy(missedAlarmAt = LocalTime(7, 30), reregisterCheck = CheckType.QrBarcode)

    val homeReliability = homeList.copy(reliabilityProblem = true)

    val homeSession = homeList.copy(sessionInProgress = true)

    val homeDisableDialog = homeList.copy(disableDialog = DisableUnderLock("2", LocalTime(7, 30), Countdown.HoursMinutes(8, 0)))

    // Sound list (the editor's Sound sub-screen) ---------------------------------------------------------------------

    private val recordings = listOf("Message 1", "Message 2")

    val soundList =
        SoundPickerUiState(
            options =
                listOf(
                    SoundOption("b1", "Sunrise", SoundSource.BuiltIn),
                    SoundOption("b2", "Birdsong", SoundSource.BuiltIn),
                    SoundOption("b3", "Marimba", SoundSource.BuiltIn),
                    SoundOption("b4", "Rooster", SoundSource.BuiltIn),
                    SoundOption("s1", "Argon", SoundSource.System),
                    SoundOption("s2", "Oxygen", SoundSource.System),
                    SoundOption("f1", "morning-mix.mp3", SoundSource.File, missing = true),
                ),
            selectedId = "b1",
        )

    // Alarm editor -------------------------------------------------------------------------------------------------

    val fullSections =
        FullEditorSections(
            checks = listOf(CheckChip(CheckType.Math, Difficulty.Medium), CheckChip(CheckType.QrBarcode, Difficulty.Easy)),
            checkMode = CheckMode.Random,
            qrCodeSaved = true,
            feeLadder = listOf(price(1), price(2), price(3)),
            soundName = "Sunrise",
        )

    val editorNew =
        EditorUiState(
            ringsIn = Countdown.HoursMinutes(23, 0),
            full =
                FullEditorSections(
                    feeLadder = listOf(price(1), price(2), price(3)),
                    soundName = "Sunrise",
                    sounds = soundList,
                    recordings = recordings,
                ),
        )

    val editorEdit =
        EditorUiState(
            isNew = false,
            form = EditorForm(time = LocalTime(7, 30), repeatDays = weekdays, label = "Stand-up"),
            ringsIn = Countdown.HoursMinutes(7, 12),
            full =
                fullSections.copy(
                    motivation = MotivationChoice.Recording("Message 1"),
                    motivationTiming = MotivationTiming.AfterImUp,
                    sounds = soundList,
                    recordings = recordings,
                ),
        )

    /** Custom repeat days (Mon, Wed, Fri): the day chips are shown. */
    val editorCustomDays =
        editorEdit.copy(form = editorEdit.form.copy(repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)))

    val editorSoundPane = editorEdit.copy(pane = EditorPane.Sound)

    val editorSoundPreviewing =
        editorSoundPane.copy(full = editorSoundPane.full?.copy(sounds = soundList.copy(selectedId = "b2", previewingId = "b2")))

    val editorSnoozePane = editorEdit.copy(pane = EditorPane.Snooze)

    val editorCheckPane = editorEdit.copy(pane = EditorPane.WakeCheck)

    val editorQuietPane = editorEdit.copy(pane = EditorPane.QuietTime)

    val editorMotivationPane = editorEdit.copy(pane = EditorPane.Motivation)

    val editorNoCheck = editorNew.copy(full = editorNew.full?.copy(checks = emptyList(), noCheckError = true))

    val editorNoCheckPane = editorNoCheck.copy(pane = EditorPane.WakeCheck)

    val editorWeakening = editorEdit.copy(full = editorEdit.full?.copy(weakeningAppliesAfter = LocalTime(7, 30)))

    val editorSoundMissing = editorEdit.copy(full = editorEdit.full?.copy(soundName = "morning-mix.mp3", soundMissing = true))

    val editorTomorrow = editorNew.copy(ringsTomorrowAt = EditorForm.DEFAULT_TIME, ringsIn = Countdown.HoursMinutes(23, 0))

    // Wake flow ----------------------------------------------------------------------------------------------------

    private val today = LocalDate(2026, 9, 28)

    val ringingFirst = RingingUiState(time = LocalTime(7, 30), date = today, label = "Stand-up", snooze = SnoozeOffer.Available(price(1)))

    val ringingAfterSnooze =
        ringingFirst.copy(
            time = LocalTime(7, 39),
            snooze = SnoozeOffer.Available(price(2)),
            sessionLine = SessionLine(snoozeNumber = 1, maxSnoozes = 5, paid = price(1)),
        )

    val ringingTest = ringingFirst.copy(label = null, snooze = SnoozeOffer.TestMode)

    val ringingLocked = ringingFirst.copy(snooze = SnoozeOffer.LockedBeforeUnlock, note = WakeNote.DirectBoot)

    val ringingOffline = ringingFirst.copy(snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.Offline))

    val ringingMaxSnoozes =
        ringingAfterSnooze.copy(
            snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.MaxSnoozesReached),
            sessionLine = SessionLine(5, 5, price(15)),
        )

    val ringingStranded = ringingFirst.copy(snooze = SnoozeOffer.StrandedRefund(price(1)))

    val ringingPhoneCall = ringingFirst.copy(note = WakeNote.PhoneCall)

    val sheetConfirm = ringingFirst.copy(sheet = SnoozeSheet.Confirm(minutes = 9, price = price(1), nextPrice = price(2)))

    val sheetLastSnooze =
        ringingAfterSnooze.copy(sheet = SnoozeSheet.Confirm(minutes = 9, price = price(5), nextPrice = null, showTaxNote = true))

    val sheetUnlocking = ringingFirst.copy(sheet = SnoozeSheet.Unlocking(price(1)))

    val sheetAlreadyPaid = ringingFirst.copy(sheet = SnoozeSheet.AlreadyPaid(price(1)))

    fun ringingWithMessage(message: WakeMessage): RingingUiState =
        when (message) {
            WakeMessage.PaymentOffline -> {
                ringingFirst.copy(message = message, snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.Offline))
            }

            WakeMessage.PaymentPending -> {
                ringingFirst.copy(message = message, snooze = SnoozeOffer.Unavailable(SnoozeUnavailableReason.PaymentPending))
            }

            else -> {
                ringingFirst.copy(message = message)
            }
        }

    val mathProblem =
        CheckContent.Math(
            problemNumber = 1,
            problemCount = 2,
            left = 47,
            right = 38,
            operator = MathOperator.Plus,
            answer = "8",
        )

    val checkMath =
        CheckUiState(
            grace = GraceState.Running(secondsLeft = 14, totalSeconds = 20),
            content = mathProblem,
            snooze = SnoozeOffer.Available(price(1)),
        )

    val checkMathWrong =
        checkMath.copy(
            grace = GraceState.Expired,
            content = mathProblem.copy(problemNumber = 2, left = 6, right = 7, operator = MathOperator.Times, answer = "", wrong = true),
        )

    val checkWord =
        checkMath.copy(
            content =
                CheckContent.WordUnscramble(
                    wordNumber = 1,
                    wordCount = 2,
                    pool = listOf('R', null, 'I', 'S', 'N', 'U'),
                    slots = listOf('S', 'U', null, null, null, null),
                ),
        )

    val checkMemoryWatch =
        checkMath.copy(
            content = CheckContent.MemorySequence(round = 1, roundCount = 3, phase = MemoryPhase.Watch, litTile = 5),
        )

    val checkMemoryTurn =
        checkMath.copy(content = CheckContent.MemorySequence(round = 2, roundCount = 3, phase = MemoryPhase.YourTurn, numbered = true))

    val checkQr = checkMath.copy(content = CheckContent.QrBarcode())

    val checkQrWrong = checkMath.copy(content = CheckContent.QrBarcode(wrongCode = true), showFallbackLink = true)

    val checkQrCameraUnavailable = checkMath.copy(content = CheckContent.QrBarcode(cameraAvailable = false), showFallbackLink = true)

    val checkHouseHunt = checkMath.copy(content = CheckContent.HouseHunt())

    val checkHouseHuntNoMatch = checkMath.copy(content = CheckContent.HouseHunt(result = HouseHuntResult.NoMatch))

    val checkTest = checkMath.copy(snooze = SnoozeOffer.TestMode)

    val checkSheet = checkMath.copy(sheet = SnoozeSheet.Confirm(minutes = 9, price = price(1), nextPrice = price(2)))

    val fallbackPicker = FallbackPickerUiState()

    val successOnTime = SuccessUiState(SuccessKind.OnTime(streakDays = 12))

    val successFirst = SuccessUiState(SuccessKind.OnTime(streakDays = 0))

    val successAfterSnooze = SuccessUiState(SuccessKind.AfterSnooze(paidThisMorning = price(3)))

    val successPending = SuccessUiState(SuccessKind.AfterSnooze(paidThisMorning = price(1)), pendingNotUsed = true)

    val successTest = SuccessUiState(SuccessKind.Test)

    val snoozed = SnoozedUiState(nextRingAt = LocalTime(7, 39))
}

/**
 * A realistic fake base fee per currency (whole units): about one US dollar, rounded the way a price would be. Unknown
 * currencies use 1 of their unit when they have decimals, 100 when they have none (like the yen).
 */
internal fun baseFeeFor(currencyCode: String): Int =
    BASE_FEES[currencyCode]
        ?: if (runCatching { Currency.getInstance(currencyCode).defaultFractionDigits }.getOrNull() == 0) NO_DECIMALS_FEE else 1

/** The fallback base fee in a currency without decimals. */
private const val NO_DECIMALS_FEE = 100

private val BASE_FEES: Map<String, Int> =
    mapOf(
        "USD" to 1,
        "EUR" to 1,
        "GBP" to 1,
        "CHF" to 1,
        "CAD" to 1,
        "SGD" to 1,
        "AUD" to 2,
        "NZD" to 2,
        "VND" to 25_000,
        "IDR" to 15_000,
        "KRW" to 1_500,
        "JPY" to 150,
        "HUF" to 400,
        "CLP" to 1_000,
        "COP" to 4_000,
        "ARS" to 1_000,
        "NGN" to 1_500,
        "KZT" to 500,
        "INR" to 80,
        "PKR" to 300,
        "BDT" to 120,
        "RUB" to 90,
        "UAH" to 40,
        "THB" to 35,
        "PHP" to 55,
        "TWD" to 30,
        "CZK" to 25,
        "MXN" to 20,
        "ZAR" to 20,
        "TRY" to 35,
        "EGP" to 50,
        "SEK" to 10,
        "NOK" to 10,
        "DKK" to 7,
        "CNY" to 7,
        "HKD" to 8,
        "PLN" to 4,
        "BRL" to 5,
        "MYR" to 5,
        "SAR" to 4,
        "AED" to 4,
        "ILS" to 4,
        "PEN" to 4,
    )
