@file:Suppress("TooManyFunctions") // The shared wake-screen building blocks (buttons, snooze, grace ring, sheet) live together.

package com.yawnandpawn.app.ui.wake

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.yawnandpawn.app.ui.components.GlassBackdrop
import com.yawnandpawn.app.ui.components.NoteInline
import com.yawnandpawn.app.ui.components.PpsBackground
import com.yawnandpawn.app.ui.components.glass
import com.yawnandpawn.app.ui.components.glassSource
import com.yawnandpawn.app.ui.components.rememberGlassBackdrop
import com.yawnandpawn.app.ui.components.rememberReducedMotion
import com.yawnandpawn.app.ui.format.formatMoney
import com.yawnandpawn.app.ui.resources.Res
import com.yawnandpawn.app.ui.resources.grace_ended
import com.yawnandpawn.app.ui.resources.grace_running
import com.yawnandpawn.app.ui.resources.grace_seconds_left
import com.yawnandpawn.app.ui.resources.payment_cancelled
import com.yawnandpawn.app.ui.resources.payment_error
import com.yawnandpawn.app.ui.resources.payment_offline
import com.yawnandpawn.app.ui.resources.payment_pending_body
import com.yawnandpawn.app.ui.resources.payment_unlock_failed
import com.yawnandpawn.app.ui.resources.snooze_already_paid_body
import com.yawnandpawn.app.ui.resources.snooze_cancel
import com.yawnandpawn.app.ui.resources.snooze_confirm_body
import com.yawnandpawn.app.ui.resources.snooze_confirm_get_up
import com.yawnandpawn.app.ui.resources.snooze_confirm_last_body
import com.yawnandpawn.app.ui.resources.snooze_confirm_nudge_body
import com.yawnandpawn.app.ui.resources.snooze_confirm_pay
import com.yawnandpawn.app.ui.resources.snooze_confirm_tax_note
import com.yawnandpawn.app.ui.resources.snooze_confirm_title
import com.yawnandpawn.app.ui.resources.snooze_not_now
import com.yawnandpawn.app.ui.resources.snooze_unlock_to_pay
import com.yawnandpawn.app.ui.resources.snooze_use_it
import com.yawnandpawn.app.ui.resources.symbol_block
import com.yawnandpawn.app.ui.resources.symbol_lock
import com.yawnandpawn.app.ui.resources.symbol_notifications_active_fill1
import com.yawnandpawn.app.ui.resources.wake_direct_boot_note
import com.yawnandpawn.app.ui.resources.wake_phone_call_note
import com.yawnandpawn.app.ui.resources.wake_reason_max_snoozes
import com.yawnandpawn.app.ui.resources.wake_reason_offline
import com.yawnandpawn.app.ui.resources.wake_reason_payment_pending
import com.yawnandpawn.app.ui.resources.wake_reason_price_cap
import com.yawnandpawn.app.ui.resources.wake_reason_prices_not_loaded
import com.yawnandpawn.app.ui.resources.wake_snooze_price
import com.yawnandpawn.app.ui.resources.wake_snooze_prices_not_loaded
import com.yawnandpawn.app.ui.resources.wake_snooze_unavailable
import com.yawnandpawn.app.ui.resources.wake_snooze_unavailable_talkback
import com.yawnandpawn.app.ui.resources.wake_stranded_refund
import com.yawnandpawn.app.ui.resources.wake_test_no_charge
import com.yawnandpawn.app.ui.resources.wake_unlock_to_snooze
import com.yawnandpawn.app.ui.theme.PpsTheme
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Every wake screen: always the Sunrise token set (whatever the app theme), on the sunrise `background-gradient` (top
 * 40%, flat thumb zone), drawn edge-to-edge with its content inside the system bars. [overlay] (the confirm sheet) sits
 * over the content and gets the [GlassBackdrop] of it, so its glass blurs what is beneath on Android 12+.
 */
@Composable
fun WakeSurface(
    modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.(GlassBackdrop) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    PpsTheme(wake = true) {
        val backdrop = rememberGlassBackdrop()
        Box(modifier = modifier.fillMaxSize()) {
            PpsBackground(modifier = Modifier.glassSource(backdrop), content = content)
            overlay(backdrop)
        }
    }
}

/** Inner padding of a wake screen: inside the system bars, `screen-margin` at the sides. */
@Composable
fun Modifier.wakeContentPadding(): Modifier =
    windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = PpsTheme.spacing.screenMargin, vertical = PpsTheme.spacing.space4)

/**
 * `button-wake-primary` ("I'm up"): full width, 72 dp (64 dp in the sheet), accent fill, `button-wake` label. With
 * [pulse] (Ringing only, owner decision 2026-09-27) it breathes gently (scale 1 to 1.03 and back, 1.2 s), unless the
 * phone asks for no motion; the pulse is drawn only, so its touch target never moves.
 */
@Composable
fun WakePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hero: Boolean = true,
    pulse: Boolean = false,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val pulsing = pulse && !rememberReducedMotion()
    val scale =
        if (pulsing) {
            rememberInfiniteTransition(label = "I'm up pulse")
                .animateFloat(
                    initialValue = 1f,
                    targetValue = PULSE_SCALE,
                    animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
                    label = "scale",
                ).value
        } else {
            1f
        }
    Button(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = if (hero) spacing.targetWakeHero else spacing.targetWake)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        shape = PpsTheme.shapes.full,
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
    ) {
        Text(text = text, style = PpsTheme.typography.buttonWake, textAlign = TextAlign.Center)
    }
}

/** The outlined 64 dp wake action ("Pay {price} and snooze", "Use it", "Cancel"), like `button-snooze`. */
@Composable
fun WakeOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = PpsTheme.spacing.targetWake),
        shape = PpsTheme.shapes.full,
        border = BorderStroke(1.dp, colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
    ) {
        Text(text = text, style = PpsTheme.typography.buttonWake, textAlign = TextAlign.Center)
    }
}

/**
 * `button-snooze`, one component on every wake screen: 64 dp, full width. [SnoozeOffer.Available] is outlined
 * "Snooze · {price}" and opens the confirm sheet; every other offer is `button-snooze-disabled` (disabled token pair,
 * `block` icon, or `lock` before the first unlock) with its reason as the label, read as "Snooze unavailable, {reason}".
 */
@Composable
fun SnoozeButton(
    offer: SnoozeOffer,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (offer is SnoozeOffer.Available) {
        WakeOutlinedButton(
            text = stringResource(Res.string.wake_snooze_price, formatMoney(offer.price)),
            onClick = onClick,
            modifier = modifier,
        )
        return
    }
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val label = disabledSnoozeLabel(offer)
    val spoken = stringResource(Res.string.wake_snooze_unavailable_talkback, disabledSnoozeReason(offer))
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = spacing.targetWake)
                .clip(PpsTheme.shapes.full)
                .background(colors.disabledContainer)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    role = Role.Button
                    disabled()
                }.padding(horizontal = spacing.space6, vertical = spacing.space3),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (offer is SnoozeOffer.LockedBeforeUnlock) Res.drawable.symbol_lock else Res.drawable.symbol_block),
            contentDescription = null,
            modifier = Modifier.size(spacing.space6),
            tint = colors.disabledContent,
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = spacing.space2),
            style = PpsTheme.typography.buttonWake,
            color = colors.disabledContent,
            textAlign = TextAlign.Center,
        )
    }
}

/** The visible label; "prices not loaded yet" alone reads "Prices not loaded yet" on one line (owner decision 2026-10-02). */
@Composable
private fun disabledSnoozeLabel(offer: SnoozeOffer): String =
    when {
        offer == SnoozeOffer.Unavailable(SnoozeUnavailableReason.PricesNotLoaded) -> {
            stringResource(Res.string.wake_snooze_prices_not_loaded)
        }

        offer is SnoozeOffer.Unavailable -> {
            stringResource(Res.string.wake_snooze_unavailable, reasonText(offer.reason))
        }

        else -> {
            disabledSnoozeReason(offer)
        }
    }

@Composable
private fun disabledSnoozeReason(offer: SnoozeOffer): String =
    when (offer) {
        is SnoozeOffer.Available -> ""
        is SnoozeOffer.Unavailable -> reasonText(offer.reason)
        SnoozeOffer.TestMode -> stringResource(Res.string.wake_test_no_charge)
        SnoozeOffer.LockedBeforeUnlock -> stringResource(Res.string.wake_unlock_to_snooze)
        is SnoozeOffer.StrandedRefund -> stringResource(Res.string.wake_stranded_refund, formatMoney(offer.price))
    }

@Composable
private fun reasonText(reason: SnoozeUnavailableReason): String =
    stringResource(
        when (reason) {
            SnoozeUnavailableReason.Offline -> Res.string.wake_reason_offline
            SnoozeUnavailableReason.MaxSnoozesReached -> Res.string.wake_reason_max_snoozes
            SnoozeUnavailableReason.PriceCapReached -> Res.string.wake_reason_price_cap
            SnoozeUnavailableReason.PaymentPending -> Res.string.wake_reason_payment_pending
            SnoozeUnavailableReason.PricesNotLoaded -> Res.string.wake_reason_prices_not_loaded
        },
    )

/**
 * A wake `note-inline` (Direct Boot notice, phone call pause). The call note comes and goes during a ring, so TalkBack
 * announces it politely (Story 2.7).
 */
@Composable
fun WakeNoteView(
    note: WakeNote,
    modifier: Modifier = Modifier,
) {
    NoteInline(
        text =
            stringResource(
                when (note) {
                    WakeNote.DirectBoot -> Res.string.wake_direct_boot_note
                    WakeNote.PhoneCall -> Res.string.wake_phone_call_note
                },
            ),
        modifier = if (note == WakeNote.PhoneCall) modifier.semantics { liveRegion = LiveRegionMode.Polite } else modifier,
    )
}

/**
 * A wake-screen `snackbar`: `inverse-surface`, `rounded.sm`, message only (no action), announced politely. The
 * screen's state decides how long it stays (at least 10 s or until the next tap).
 */
@Composable
fun WakeSnackbar(
    message: WakeMessage,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    Text(
        text =
            stringResource(
                when (message) {
                    WakeMessage.UnlockFailed -> Res.string.payment_unlock_failed
                    WakeMessage.PaymentCancelled -> Res.string.payment_cancelled
                    WakeMessage.PaymentError -> Res.string.payment_error
                    WakeMessage.PaymentOffline -> Res.string.payment_offline
                    WakeMessage.PaymentPending -> Res.string.payment_pending_body
                },
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .clip(PpsTheme.shapes.sm)
                .background(colors.inverseSurface)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = PpsTheme.spacing.space4, vertical = PpsTheme.spacing.space3),
        style = PpsTheme.typography.body,
        color = colors.inverseText,
    )
}

/**
 * `countdown-ring`: 120 dp, 8 dp `accent` stroke over the `outline-subtle` track, seconds centred in `display`, next to
 * "Quiet for {seconds}s. ..."; once expired, a solid bell and "Time's up. Alarm's back on until you finish.".
 * TalkBack reads "{seconds} seconds left" on the ring. The values come from the session engine (the grace `Deadline`);
 * this only draws them (Story 3.4):
 * - a short haptic tick every 5 s ([isTickSecond]) while the window runs, only with the session's quiet-time vibration
 *   ([GraceState.Running.vibrate]) and only as the count passes the second: a header that comes back (the screen
 *   recreated or reopened) does not tick again for the second it shows;
 * - TalkBack politely announces "{seconds} seconds left" every 10 s and at 5 s ([announcedSecond]), not every second;
 * - with reduced motion (animator duration scale 0) the plain seconds number replaces the ring.
 */
@Composable
fun GraceHeader(
    grace: GraceState,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    val haptics = LocalHapticFeedback.current
    val running = grace as? GraceState.Running
    // The lowest second seen in this window, kept across a recreated screen; a higher one starts a new window.
    var lastSeen by rememberSaveable { mutableIntStateOf(running?.secondsLeft ?: NOT_SEEN) }
    LaunchedEffect(running?.secondsLeft) {
        val seconds = running?.secondsLeft ?: return@LaunchedEffect
        val passed = lastSeen != NOT_SEEN && seconds < lastSeen
        if (passed && running.vibrate && isTickSecond(seconds, running.totalSeconds)) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
        lastSeen = seconds
    }
    // On a glass card: the accent ring passes on glass over the sunrise gradient (3.17), not on the gradient itself.
    Row(
        modifier = modifier.fillMaxWidth().glass(PpsTheme.shapes.md).padding(spacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(RING_SIZE), contentAlignment = Alignment.Center) {
            when (grace) {
                is GraceState.Running -> {
                    CountdownRing(grace)
                }

                GraceState.Expired -> {
                    Icon(
                        painter = painterResource(Res.drawable.symbol_notifications_active_fill1),
                        contentDescription = null,
                        modifier = Modifier.size(spacing.targetMin),
                        tint = colors.text,
                    )
                }
            }
        }
        Text(
            text =
                when (grace) {
                    is GraceState.Running -> stringResource(Res.string.grace_running, grace.secondsLeft)
                    GraceState.Expired -> stringResource(Res.string.grace_ended)
                },
            // Only the expired line is a live region: the running line changes every second (the ring announces it).
            modifier =
                Modifier.weight(1f).padding(start = spacing.space4).semantics {
                    if (grace == GraceState.Expired) liveRegion = LiveRegionMode.Polite
                },
            style = PpsTheme.typography.body,
            color = colors.text,
        )
    }
}

/**
 * `sheet-snooze-confirm` over a wake screen: a `glass-bar` bottom sheet (`glass-strong`, blurred backdrop on Android
 * 12+), top corners `rounded.lg`, 24 dp padding. The upper
 * outlined action pays (or uses the earlier payment); the filled bottom one, a tap outside and Back all run
 * [onDismiss] ("I'll get up", "Not now", "Cancel"), and so do a swipe down and TalkBack's dismiss action. Every input
 * is ignored for 500 ms after the sheet opens or its state or displayed price changes ([InputGuard] on the monotonic
 * [LocalWakeClock], whatever the animation setting; Story 4.13). Neither button is pre-selected or focused: the sheet is
 * a pane titled by its first line, a heading, which TalkBack reads first.
 *
 * With [keepClearAboveY] (a window y in px, the bottom of Ringing's clock) the sheet never grows over that line, but
 * keeps at least [MIN_SHEET_FRACTION] of the screen: at large font scales its text scrolls and the buttons stay whole.
 * Where the text runs on past an edge, it fades out over `space6` there, so a cut line reads as "scroll for more".
 */
@Composable
fun SnoozeConfirmSheet(
    sheet: SnoozeSheet,
    onUpper: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
    keepClearAboveY: Float? = null,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    // A new guard (armed now) whenever the sheet's content changes: its state, or a live price that differs.
    val clock = LocalWakeClock.current
    val guard = remember(sheet) { InputGuard(clock) }
    val guardedUpper = { if (guard.accepts()) onUpper() }
    val guardedDismiss = { if (guard.accepts()) onDismiss() }
    val guardedDismissState = rememberUpdatedState(guardedDismiss)
    NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true) { guardedDismiss() }
    val title = sheetTitle(sheet)
    val swipe = rememberSwipeDown(with(LocalDensity.current) { spacing.targetWake.toPx() }) { guardedDismissState.value() }
    var box by remember { mutableStateOf<Rect?>(null) }
    val maxSheetHeight =
        box?.let { bounds ->
            keepClearAboveY?.let { clearY ->
                with(LocalDensity.current) {
                    maxOf(bounds.bottom - clearY, bounds.height * MIN_SHEET_FRACTION).toDp()
                }
            }
        }
    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { box = it.boundsInWindow() }) {
        SheetScrim { guardedDismissState.value() }
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(maxSheetHeight?.let { Modifier.heightIn(max = it) } ?: Modifier)
                    .glass(
                        PpsTheme.shapes.lg.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
                        strong = true,
                        backdrop = backdrop,
                    ).then(swipe)
                    .sheetPane(title) { guardedDismissState.value() }
                    .navigationBarsPadding()
                    .padding(spacing.space6),
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            // The text scrolls when the sheet is capped; the buttons below always keep their full size.
            val scroll = rememberScrollState()
            Column(
                modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .fadeAtScrollEdges(scroll, with(LocalDensity.current) { spacing.space6.toPx() }, opaque = colors.text)
                        .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(spacing.space3),
            ) {
                SheetContent(sheet)
            }
            SheetTaxNote(sheet)
            SheetButtons(sheet = sheet, onUpper = guardedUpper, onDismiss = guardedDismiss)
        }
    }
}

/**
 * Owner check 2026-10-08: at 200% font the sheet's text was cut mid-line just above the buttons. Content that runs on
 * past the top or bottom edge of [scroll]'s viewport fades out over [fade] px there (an alpha mask, so the glass behind
 * shows through and no new colour pair appears); a fully shown edge has no fade, and text that fits draws as before.
 * Only the alpha of [opaque] (any fully opaque theme colour) is used.
 */
private fun Modifier.fadeAtScrollEdges(
    scroll: ScrollState,
    fade: Float,
    opaque: Color,
): Modifier =
    graphicsLayer {
        val masked = scroll.canScrollBackward || scroll.canScrollForward
        compositingStrategy = if (masked) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }.drawWithContent {
        drawContent()
        if (scroll.canScrollBackward) {
            drawRect(
                brush = Brush.verticalGradient(listOf(Color.Transparent, opaque), startY = 0f, endY = fade),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.canScrollForward) {
            drawRect(
                brush = Brush.verticalGradient(listOf(opaque, Color.Transparent), startY = size.height - fade, endY = size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** The sheet's actions for [sheet]: the outlined upper one ([onUpper]) and the filled dismiss one ([onDismiss]). */
@Composable
private fun SheetButtons(
    sheet: SnoozeSheet,
    onUpper: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spacing = PpsTheme.spacing
    Column(modifier = Modifier.padding(top = spacing.space3), verticalArrangement = Arrangement.spacedBy(spacing.space3)) {
        when (sheet) {
            is SnoozeSheet.Confirm -> {
                WakeOutlinedButton(text = stringResource(Res.string.snooze_confirm_pay, formatMoney(sheet.price)), onClick = onUpper)
                WakePrimaryButton(text = stringResource(Res.string.snooze_confirm_get_up), onClick = onDismiss, hero = false)
            }

            is SnoozeSheet.Unlocking -> {
                WakeOutlinedButton(text = stringResource(Res.string.snooze_cancel), onClick = onDismiss)
            }

            is SnoozeSheet.AlreadyPaid -> {
                WakeOutlinedButton(text = stringResource(Res.string.snooze_use_it), onClick = onUpper)
                WakePrimaryButton(text = stringResource(Res.string.snooze_not_now), onClick = onDismiss, hero = false)
            }
        }
    }
}

@Composable
private fun SheetContent(sheet: SnoozeSheet) {
    val colors = PpsTheme.colors
    val typography = PpsTheme.typography
    when (sheet) {
        is SnoozeSheet.Confirm -> {
            val price = formatMoney(sheet.price)
            Text(
                text = stringResource(Res.string.snooze_confirm_title, sheet.minutes),
                modifier = Modifier.semantics { heading() },
                style = typography.headline,
                color = colors.text,
            )
            // A live price that differs replaces this one; TalkBack says the new price (review fix 15).
            Text(
                text = price,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = typography.display,
                color = colors.text,
            )
            Text(
                text =
                    sheet.nextPrice?.let { stringResource(Res.string.snooze_confirm_body, price, formatMoney(it)) }
                        ?: stringResource(Res.string.snooze_confirm_last_body, price),
                style = typography.body,
                color = colors.text,
            )
            Text(
                text = stringResource(Res.string.snooze_confirm_nudge_body, sheet.minutes, price),
                style = typography.body,
                color = colors.textSecondary,
            )
        }

        is SnoozeSheet.Unlocking -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(Res.drawable.symbol_lock), contentDescription = null, tint = colors.text)
                Text(
                    text = stringResource(Res.string.snooze_unlock_to_pay, formatMoney(sheet.price)),
                    modifier = Modifier.padding(start = PpsTheme.spacing.space3).semantics { heading() },
                    style = typography.headline,
                    color = colors.text,
                )
            }
        }

        is SnoozeSheet.AlreadyPaid -> {
            Text(
                text = stringResource(Res.string.snooze_already_paid_body, formatMoney(sheet.price)),
                modifier = Modifier.semantics { heading() },
                style = typography.body,
                color = colors.text,
            )
        }
    }
}

/**
 * The tax note of a confirm [sheet] that shows one: part of the price, so it sits above the buttons outside the scrolling
 * text and never scrolls away at 200% font (Story 4.13 review fix 1).
 */
@Composable
private fun SheetTaxNote(sheet: SnoozeSheet) {
    if (sheet is SnoozeSheet.Confirm && sheet.showTaxNote) {
        Text(
            text = stringResource(Res.string.snooze_confirm_tax_note),
            style = PpsTheme.typography.caption,
            color = PpsTheme.colors.textSecondary,
        )
    }
}

/** The scrim over the wake screen: a tap outside the sheet ([onTap]) is the "I'll get up" path. */
@Composable
private fun SheetScrim(onTap: () -> Unit) {
    val latest = rememberUpdatedState(onTap)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(PpsTheme.colors.text.copy(alpha = SCRIM_ALPHA))
                .pointerInput(Unit) { detectTapGestures { latest.value() } }
                .clearAndSetSemantics { },
    )
}

/** TalkBack announces the sheet by its first line ([title]) and can close it like Back ([onDismiss], "I'll get up"). */
private fun Modifier.sheetPane(
    title: String,
    onDismiss: () -> Unit,
): Modifier =
    semantics {
        paneTitle = title
        dismiss {
            onDismiss()
            true
        }
    }

/** The first line of [sheet], its TalkBack pane title. */
@Composable
private fun sheetTitle(sheet: SnoozeSheet): String =
    when (sheet) {
        is SnoozeSheet.Confirm -> stringResource(Res.string.snooze_confirm_title, sheet.minutes)
        is SnoozeSheet.Unlocking -> stringResource(Res.string.snooze_unlock_to_pay, formatMoney(sheet.price))
        is SnoozeSheet.AlreadyPaid -> stringResource(Res.string.snooze_already_paid_body, formatMoney(sheet.price))
    }

/**
 * Swipe down to close (Story 4.13, the "I'll get up" path): a downward drag of at least [thresholdPx] on the sheet,
 * on its fixed part or past the top of its scrolling text (what the text does not scroll reaches this through nested
 * scrolling), runs [onSwiped] when the finger lifts.
 */
@Composable
private fun rememberSwipeDown(
    thresholdPx: Float,
    onSwiped: () -> Unit,
): Modifier {
    val latest = rememberUpdatedState(onSwiped)
    val tracker = remember(thresholdPx) { SwipeTracker(thresholdPx) }
    val connection =
        remember(tracker) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) tracker.add(available.y)
                    return Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity {
                    if (tracker.end()) latest.value()
                    return Velocity.Zero
                }
            }
        }
    val drag = rememberDraggableState { tracker.add(it) }
    return Modifier
        .nestedScroll(connection)
        .draggable(drag, Orientation.Vertical, onDragStopped = { if (tracker.end()) latest.value() })
}

/** The downward distance of one drag; [end] says whether it reached [threshold] and starts over. */
private class SwipeTracker(
    private val threshold: Float,
) {
    private var total = 0f

    fun add(dy: Float) {
        total += dy
    }

    fun end(): Boolean {
        val swiped = total >= threshold
        total = 0f
        return swiped
    }
}

/** EXPERIENCE.md `sheet-snooze-confirm`: input is ignored for 500 ms after the sheet opens or changes state. */
const val INPUT_LOCK_MILLIS: Long = 500L

private const val SCRIM_ALPHA = 0.32f

/** The share of the screen the confirm sheet may always take, even where the clock sits lower. */
const val MIN_SHEET_FRACTION = 0.4f
private const val PULSE_SCALE = 1.03f
private const val PULSE_MILLIS = 1_200
private const val FULL_CIRCLE = 360f
private const val START_ANGLE = -90f
private val RING_SIZE = 120.dp
private const val TICK_SECONDS = 5
private const val ANNOUNCE_SECONDS = 10

/** No countdown second seen yet. */
private const val NOT_SEEN = -1

/**
 * The `countdown-ring` itself, inside a [RING_SIZE] box: the ring (only the number with reduced motion), the seconds in
 * `display`, "{seconds} seconds left" on focus, and the polite announcement node.
 */
@Composable
private fun BoxScope.CountdownRing(grace: GraceState.Running) {
    val colors = PpsTheme.colors
    val reducedMotion = rememberReducedMotion()
    val spoken = stringResource(Res.string.grace_seconds_left, grace.secondsLeft)
    val announced = announcedSecond(grace.secondsLeft, grace.totalSeconds)?.let { stringResource(Res.string.grace_seconds_left, it) }
    // The polite announcement: text only at an announced second, so TalkBack speaks it then and nothing else.
    Box(
        modifier =
            Modifier.matchParentSize().clearAndSetSemantics {
                liveRegion = LiveRegionMode.Polite
                announced?.let { contentDescription = it }
            },
    )
    val stroke = PpsTheme.spacing.ringStroke
    Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = spoken }) {
        if (reducedMotion) return@Canvas
        val width = stroke.toPx()
        val inset = width / 2
        val arcSize = Size(size.width - width, size.height - width)
        drawArc(colors.outlineSubtle, 0f, FULL_CIRCLE, false, Offset(inset, inset), arcSize, style = Stroke(width))
        val sweep = FULL_CIRCLE * grace.secondsLeft / grace.totalSeconds.coerceAtLeast(1)
        drawArc(colors.accent, START_ANGLE, sweep, false, Offset(inset, inset), arcSize, style = Stroke(width))
    }
    Text(
        text = grace.secondsLeft.toString(),
        modifier = Modifier.clearAndSetSemantics { },
        style = PpsTheme.typography.display,
        color = colors.text,
    )
}

/** The countdown's haptic rhythm (Story 3.4): a short tick every 5 s while it runs, not at its start or at 0. */
internal fun isTickSecond(
    secondsLeft: Int,
    totalSeconds: Int,
): Boolean = secondsLeft in 1 until totalSeconds && secondsLeft % TICK_SECONDS == 0

/**
 * The second TalkBack announces "{seconds} seconds left" at, or null (UX-DR65): every 10 s and at 5 s while the window
 * runs, not at its start.
 */
internal fun announcedSecond(
    secondsLeft: Int,
    totalSeconds: Int,
): Int? = secondsLeft.takeIf { it in 1 until totalSeconds && (it % ANNOUNCE_SECONDS == 0 || it == TICK_SECONDS) }
