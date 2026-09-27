package com.yawnandpawn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.max
import com.yawnandpawn.app.ui.format.periodName
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.time_wheel_hour
import com.yawnandpawn.app.ui.resources.time_wheel_hour_unit
import com.yawnandpawn.app.ui.resources.time_wheel_minute
import com.yawnandpawn.app.ui.resources.time_wheel_minute_unit
import com.yawnandpawn.app.ui.resources.time_wheel_period
import com.yawnandpawn.app.ui.theme.CLOCK_XL_MAX_FONT_SCALE
import com.yawnandpawn.app.ui.theme.PpsTheme
import com.yawnandpawn.app.ui.theme.cappedFontSize
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * `time-picker` (owner decision 2026-09-27, replaces keyboard input): an hour and a minute wheel, plus an AM/PM wheel
 * when the phone uses a 12-hour clock ([is24Hour] false). Each wheel scrolls with snapping to one value, repeats its
 * range for an endless feel with momentum, ticks lightly (haptic) per value, highlights the centre value and never opens
 * a keyboard; "h" and "min" unit labels follow the hour and minute wheels. Digits use `display` with
 * tabular figures (capped at 1.3x font scale, like `clock-xl`, so three wheels fit a 360 dp screen at 200%).
 * TalkBack reads each wheel as an adjustable control ("Hour, 6"); swipe up or down changes the value by one.
 * [time] is the source of truth: every settled change is reported through [onTimeChange].
 */
@Composable
fun PpsWheelTimePicker(
    time: LocalTime,
    is24Hour: Boolean,
    onTimeChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestTime by rememberUpdatedState(time)
    val latestOnChange by rememberUpdatedState(onTimeChange)
    val digits = wheelDigitStyle()
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val hourValues = if (is24Hour) HOURS_24 else HOURS_12
        val hourIndex = hourValues.indexOf(if (is24Hour) time.hour else to12Hour(time.hour))
        Wheel(
            title = stringResource(Res.string.time_wheel_hour),
            count = hourValues.size,
            selectedIndex = hourIndex,
            text = { index -> if (is24Hour) twoDigits(hourValues[index]) else hourValues[index].toString() },
            spoken = { index -> hourValues[index].toString() },
            style = digits,
            endless = true,
            onSelect = { index ->
                val current = latestTime
                val hour = if (is24Hour) hourValues[index] else from12Hour(hourValues[index], pm = current.hour >= NOON)
                latestOnChange(LocalTime(hour, current.minute))
            },
        )
        UnitLabel(stringResource(Res.string.time_wheel_hour_unit))
        Wheel(
            title = stringResource(Res.string.time_wheel_minute),
            count = MINUTES_PER_HOUR,
            selectedIndex = time.minute,
            text = { index -> twoDigits(index) },
            spoken = { index -> index.toString() },
            style = digits,
            endless = true,
            onSelect = { index -> latestOnChange(LocalTime(latestTime.hour, index)) },
        )
        UnitLabel(stringResource(Res.string.time_wheel_minute_unit))
        if (!is24Hour) PeriodWheel(time = time, onTimeChange = { latestOnChange(it) })
    }
}

/**
 * One wheel: a snapping [LazyColumn] of [count] values ([VISIBLE_ITEMS] visible, the centre one selected), repeated
 * [REPEAT] times when [endless]. For TalkBack the whole wheel is one adjustable control: [title] as its label, the
 * selected value ([spoken]) as its state, and set-progress (swipe up or down) to move one value.
 */
@Composable
private fun Wheel(
    title: String,
    count: Int,
    selectedIndex: Int,
    text: (Int) -> String,
    spoken: (Int) -> String,
    style: TextStyle,
    endless: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val density = LocalDensity.current
    val itemHeight: Dp = max(spacing.targetWake, with(density) { style.lineHeight.toDp() } + spacing.space2)
    // Fixed width: the widest value plus padding (a LazyColumn has no intrinsic width, and the highlight fills it).
    val measurer = rememberTextMeasurer()
    val widest = remember(count, style, measurer) { (0 until count).maxOf { measurer.measure(text(it), style).size.width } }
    val wheelWidth: Dp = max(spacing.targetWake + spacing.space4, with(density) { widest.toDp() } + spacing.space6)
    val listSize = if (endless) count * REPEAT else count
    val startIndex = if (endless) (REPEAT / 2) * count + selectedIndex else selectedIndex
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val latestSelected by rememberUpdatedState(selectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)
    WheelSync(listState = listState, count = count, endless = endless, selectedIndex = selectedIndex, onSelect = onSelect)
    Box(
        modifier =
            modifier
                .width(wheelWidth)
                .height(itemHeight * VISIBLE_ITEMS)
                .wheelSemantics(title, spoken(selectedIndex), selectedIndex, count) { index ->
                    if (index != latestSelected) latestOnSelect(index)
                },
        contentAlignment = Alignment.Center,
    ) {
        // The centre highlight, behind the values.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .background(colors.surfaceVariant, PpsTheme.shapes.sm),
        )
        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(listState, SnapPosition.Center),
            contentPadding = PaddingValues(vertical = itemHeight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(listSize) { index ->
                val value = index % count
                Box(
                    modifier = Modifier.height(itemHeight).padding(horizontal = spacing.space3),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text(value),
                        style = style,
                        color = if (value == selectedIndex) colors.text else colors.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Keeps the wheel and [selectedIndex] in step: a settled scroll reports the centred value (when it differs), and a
 * change from outside (TalkBack, a restored form) moves the wheel to the nearest copy of the value.
 */
@Composable
private fun WheelSync(
    listState: LazyListState,
    count: Int,
    endless: Boolean,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val latestSelected by rememberUpdatedState(selectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val haptics = LocalHapticFeedback.current
    // A light tick each time a new value passes the centre while the wheel scrolls (owner decision 2026-09-27).
    LaunchedEffect(listState, count) {
        snapshotFlow { listState.centredIndex() % count }
            .drop(1)
            .collect { if (listState.isScrollInProgress) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
    }
    LaunchedEffect(listState, count) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val value = listState.centredIndex() % count
                if (value != latestSelected) latestOnSelect(value)
            }
    }
    LaunchedEffect(selectedIndex, count) {
        if (listState.isScrollInProgress) return@LaunchedEffect
        val centred = listState.centredIndex()
        if (centred % count == selectedIndex) return@LaunchedEffect
        val target = if (endless) centred + shortestStep(centred % count, selectedIndex, count) else selectedIndex
        val listSize = if (endless) count * REPEAT else count
        listState.scrollToItem(target.coerceIn(0, listSize - 1))
    }
}

/** One adjustable control for TalkBack: [title] as label, [spokenValue] as state, set-progress moves to an index. */
private fun Modifier.wheelSemantics(
    title: String,
    spokenValue: String,
    selectedIndex: Int,
    count: Int,
    onSet: (Int) -> Unit,
): Modifier =
    clearAndSetSemantics {
        contentDescription = title
        stateDescription = spokenValue
        progressBarRangeInfo =
            ProgressBarRangeInfo(
                current = selectedIndex.toFloat(),
                range = 0f..(count - 1).toFloat(),
                steps = (count - 2).coerceAtLeast(0),
            )
        setProgress { target ->
            onSet(target.roundToInt().coerceIn(0, count - 1))
            true
        }
    }

/** The AM/PM wheel of a 12-hour phone (locale markers). */
@Composable
private fun PeriodWheel(
    time: LocalTime,
    onTimeChange: (LocalTime) -> Unit,
) {
    val latestTime by rememberUpdatedState(time)
    val am = periodName(am = true)
    val pm = periodName(am = false)
    Wheel(
        title = stringResource(Res.string.time_wheel_period),
        count = 2,
        selectedIndex = if (time.hour >= NOON) 1 else 0,
        text = { index -> if (index == 0) am else pm },
        spoken = { index -> if (index == 0) am else pm },
        style = PpsTheme.typography.title,
        endless = false,
        onSelect = { index ->
            val current = latestTime
            onTimeChange(LocalTime(from12Hour(to12Hour(current.hour), pm = index == 1), current.minute))
        },
        modifier = Modifier.padding(start = PpsTheme.spacing.space3),
    )
}

/** The item nearest the viewport centre. */
private fun LazyListState.centredIndex(): Int {
    val info = layoutInfo
    val centre = (info.viewportStartOffset + info.viewportEndOffset) / 2
    return info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - centre) }?.index ?: firstVisibleItemIndex
}

/** The signed step from [from] to [to] on a ring of [count] values, going the short way round. */
internal fun shortestStep(
    from: Int,
    to: Int,
    count: Int,
): Int {
    val forward = ((to - from) % count + count) % count
    return if (forward <= count / 2) forward else forward - count
}

/** 0..23 to the 12-hour clock face value (12, 1..11). */
internal fun to12Hour(hour: Int): Int = if (hour % NOON == 0) NOON else hour % NOON

/** A 12-hour clock face value (12, 1..11) and AM/PM back to 0..23. */
internal fun from12Hour(
    hour12: Int,
    pm: Boolean,
): Int = (hour12 % NOON) + if (pm) NOON else 0

private fun twoDigits(value: Int): String = value.toString().padStart(2, '0')

private const val NOON = 12
private const val MINUTES_PER_HOUR = 60
private const val VISIBLE_ITEMS = 3

/** Copies of the range in an endless wheel: enough that nobody reaches an end by scrolling. */
private const val REPEAT = 200

private val HOURS_24: List<Int> = (0 until 24).toList()
private val HOURS_12: List<Int> = listOf(12) + (1..11).toList()
