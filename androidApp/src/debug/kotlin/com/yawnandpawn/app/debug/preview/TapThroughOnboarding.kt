package com.yawnandpawn.app.debug.preview

import com.yawnandpawn.app.ui.checkpicker.CheckPickerIntent
import com.yawnandpawn.app.ui.checkpicker.CheckPickerUiState
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.editor.CheckChip
import com.yawnandpawn.app.ui.editor.EditorUiState
import com.yawnandpawn.app.ui.editor.RepeatChoice
import com.yawnandpawn.app.ui.format.Countdown
import com.yawnandpawn.app.ui.format.Money
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.home.AlarmCard
import com.yawnandpawn.app.ui.home.HomeUiState
import com.yawnandpawn.app.ui.onboarding.OnboardingIntent
import com.yawnandpawn.app.ui.onboarding.OnboardingStep
import com.yawnandpawn.app.ui.onboarding.OnboardingUiState
import com.yawnandpawn.app.ui.onboarding.TestAlarmStatus
import com.yawnandpawn.app.ui.reliability.ItemStatus

/**
 * Onboarding in the tap-through (F1): every step responds; a selected check's row opens Check setup; the first "Ring a
 * test alarm" shows "Your phone wasn't locked." (the phone in your hand is not locked), the second rings the test alarm
 * (Ringing, Check, Success) and "Done" lands on Home with the new alarm; "Skip for now" lands there with the note
 * recommending the test. Back on the first step leaves the tap-through.
 */
internal fun TapThroughState.onOnboarding(intent: OnboardingIntent) {
    val current = onboarding ?: return
    when (intent) {
        is OnboardingIntent.Checks -> {
            val picker = intent.intent
            if (picker is CheckPickerIntent.SetupClicked) {
                current.checks.checks
                    .firstOrNull { it.type == picker.type }
                    ?.let(setup::openCheckSetup)
            } else {
                onboarding = current.copy(checks = reducePicker(current.checks, picker))
            }
        }

        OnboardingIntent.RingTestAlarm -> {
            if (current.testStatus == TestAlarmStatus.Ready) {
                onboarding = current.copy(testStatus = TestAlarmStatus.NotLocked)
            } else {
                openWake(test = true)
            }
        }

        OnboardingIntent.SkipTestAlarm -> {
            finishOnboarding(testSkipped = true)
        }

        is OnboardingIntent.UsageStatsChosen -> {
            onboarding = current.copy(step = OnboardingStep.TestAlarm)
        }

        else -> {
            onboarding = reduceOnboarding(current, intent)
        }
    }
}

/** Onboarding is done: Home shows the alarm it set up (and, when the test was skipped, the note recommending it). */
internal fun TapThroughState.finishOnboarding(testSkipped: Boolean) {
    val done = onboarding ?: return
    home =
        HomeUiState(
            nextAlarm = Countdown.HoursMinutes(NEXT_RING_HOURS, NEXT_RING_MINUTES),
            alarms = listOf(AlarmCard("new", done.alarmTime, done.repeatDays, null, done.checks.checks.map { it.type }, enabled = true)),
            testSkipped = testSkipped,
        )
    onboarding = null
    stack.clear()
}

/** The steps' own controls; "Continue" moves on (the checks step only with a check selected). */
internal fun reduceOnboarding(
    state: OnboardingUiState,
    intent: OnboardingIntent,
): OnboardingUiState =
    when (intent) {
        OnboardingIntent.Next -> {
            if (state.step == OnboardingStep.Checks && state.checks.checks.isEmpty()) {
                state.copy(checks = state.checks.copy(noCheckError = true))
            } else {
                state.copy(step = OnboardingStep.entries[(state.step.ordinal + 1).coerceAtMost(OnboardingStep.entries.lastIndex)])
            }
        }

        OnboardingIntent.Back -> {
            state.copy(step = OnboardingStep.entries[(state.step.ordinal - 1).coerceAtLeast(0)], testStatus = TestAlarmStatus.Ready)
        }

        OnboardingIntent.LowerBaseFee -> {
            state.lowerFee?.let { state.withFee(it) } ?: state
        }

        OnboardingIntent.RaiseBaseFee -> {
            state.higherFee?.let { state.withFee(it) } ?: state
        }

        else -> {
            reduceAlarmAndChecklist(state, intent)
        }
    }

private fun reduceAlarmAndChecklist(
    state: OnboardingUiState,
    intent: OnboardingIntent,
): OnboardingUiState =
    when (intent) {
        is OnboardingIntent.TimeChanged -> {
            state.copy(alarmTime = intent.time)
        }

        is OnboardingIntent.DayToggled -> {
            val days = if (intent.day in state.repeatDays) state.repeatDays - intent.day else state.repeatDays + intent.day
            state.copy(repeatDays = days)
        }

        is OnboardingIntent.RepeatChosen -> {
            when (intent.choice) {
                RepeatChoice.Once -> state.copy(repeatDays = emptySet(), customRepeat = false)
                RepeatChoice.Weekdays -> state.copy(repeatDays = Weekdays, customRepeat = false)
                RepeatChoice.Custom -> state.copy(customRepeat = true)
            }
        }

        is OnboardingIntent.FixClicked -> {
            // "Fix" returns from the system setting with the item OK (the manufacturer steps are on the checklist screen).
            state.copy(reliability = state.reliability.map { if (it.item == intent.item) it.copy(status = ItemStatus.Ok) else it })
        }

        else -> {
            state
        }
    }

/** The check picker's toggles, mode, order and camera fix (Check setup is opened by the caller). */
internal fun reducePicker(
    state: CheckPickerUiState,
    intent: CheckPickerIntent,
): CheckPickerUiState =
    when (intent) {
        is CheckPickerIntent.Toggled -> {
            val checks =
                if (intent.selected) {
                    state.checks + CheckChip(intent.type, Difficulty.Medium)
                } else {
                    state.checks.filterNot { it.type == intent.type }
                }
            state.copy(checks = checks, noCheckError = checks.isEmpty())
        }

        is CheckPickerIntent.ModeSelected -> {
            state.copy(mode = intent.mode)
        }

        is CheckPickerIntent.Moved -> {
            state.copy(checks = state.checks.moved(intent.type, intent.up))
        }

        CheckPickerIntent.FixCamera -> {
            state.copy(cameraUnavailable = false)
        }

        is CheckPickerIntent.SetupClicked -> {
            state
        }
    }

/** [type] one place up or down in the order (All mode). */
internal fun List<CheckChip>.moved(
    type: CheckType,
    up: Boolean,
): List<CheckChip> {
    val from = indexOfFirst { it.type == type }
    val to = if (up) from - 1 else from + 1
    if (from < 0 || to !in indices) return this
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}

/** One price tier down or up, in whole multiples of the preview base fee (like Settings). */
private fun OnboardingUiState.withFee(fee: Money): OnboardingUiState {
    val unit = Money(baseFee.amountMicros / multipleOf(baseFee), baseFee.currencyCode)
    val multiple = (fee.amountMicros / unit.amountMicros).toInt()
    return copy(
        baseFee = fee,
        lowerFee = if (multiple > 1) unit * (multiple - 1) else null,
        higherFee = if (multiple < MAX_FEE_MULTIPLE) unit * (multiple + 1) else null,
    )
}

/** The base fee's multiple of the lowest tier: the lowest tier has no lower fee. */
private fun OnboardingUiState.multipleOf(fee: Money): Long {
    val lower = lowerFee ?: return 1
    return fee.amountMicros / (fee.amountMicros - lower.amountMicros)
}

/** The preview's base fee goes up to 5 tiers (like Settings). */
private const val MAX_FEE_MULTIPLE = 5

/** Home after onboarding: the 7:30 alarm rings in 7 h 50 min (set up at 23:40, F1). */
private const val NEXT_RING_HOURS = 7
private const val NEXT_RING_MINUTES = 50

/** The editor as shown: what the camera checks have registered comes from the setup flows. */
internal fun TapThroughState.editorShown(): EditorUiState =
    editor.copy(full = editor.full?.copy(qrCodeSaved = setup.qrCodeSaved, houseHuntPhotos = setup.houseHuntPhotos))

/** Onboarding as shown: what the camera checks have registered comes from the setup flows. */
internal fun OnboardingUiState.withRegistrations(setup: SetupFlowState): OnboardingUiState =
    copy(checks = checks.copy(qrCodeSaved = setup.qrCodeSaved, houseHuntPhotos = setup.houseHuntPhotos))
