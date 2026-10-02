@file:Suppress("TooManyFunctions") // The shared wake-screen building blocks (buttons, snooze, grace ring, sheet) live together.

package com.yawnandpawn.app.ui.wake

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import kotlinx.coroutines.delay
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

/** A wake `note-inline` (Direct Boot notice, phone call pause). */
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
        modifier = modifier,
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
 * TalkBack reads "{seconds} seconds left". Fixed values: the timer itself belongs to the session engine.
 */
@Composable
fun GraceHeader(
    grace: GraceState,
    modifier: Modifier = Modifier,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    // On a glass card: the accent ring passes on glass over the sunrise gradient (3.17), not on the gradient itself.
    Row(
        modifier = modifier.fillMaxWidth().glass(PpsTheme.shapes.md).padding(spacing.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(RING_SIZE), contentAlignment = Alignment.Center) {
            when (grace) {
                is GraceState.Running -> {
                    val spoken = stringResource(Res.string.grace_seconds_left, grace.secondsLeft)
                    val stroke = spacing.ringStroke
                    Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics { contentDescription = spoken }) {
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
            modifier = Modifier.weight(1f).padding(start = spacing.space4).semantics { liveRegion = LiveRegionMode.Polite },
            style = PpsTheme.typography.body,
            color = colors.text,
        )
    }
}

/**
 * `sheet-snooze-confirm` over a wake screen: a `glass-bar` bottom sheet (`glass-strong`, blurred backdrop on Android
 * 12+), top corners `rounded.lg`, 24 dp padding. The upper
 * outlined action pays (or uses the earlier payment); the filled bottom one, a tap outside and Back all run
 * [onDismiss] ("I'll get up", "Not now", "Cancel"). Every input is ignored for 500 ms after the sheet opens or changes
 * state, whatever the animation setting. Neither button is pre-selected.
 */
@Composable
fun SnoozeConfirmSheet(
    sheet: SnoozeSheet,
    onUpper: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    backdrop: GlassBackdrop? = null,
) {
    val colors = PpsTheme.colors
    val spacing = PpsTheme.spacing
    var inputLocked by remember(sheet::class) { mutableStateOf(true) }
    LaunchedEffect(sheet::class) {
        inputLocked = true
        delay(INPUT_LOCK_MILLIS)
        inputLocked = false
    }
    val guardedUpper = { if (!inputLocked) onUpper() }
    val guardedDismiss = { if (!inputLocked) onDismiss() }
    val guardedDismissState = rememberUpdatedState(guardedDismiss)
    NavigationBackHandler(state = rememberNavigationEventState(NavigationEventInfo.None), isBackEnabled = true) { guardedDismiss() }
    Box(modifier = modifier.fillMaxSize()) {
        // Scrim: a tap outside the sheet is the "I'll get up" path.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.text.copy(alpha = SCRIM_ALPHA))
                    .pointerInput(Unit) { detectTapGestures { guardedDismissState.value() } }
                    .clearAndSetSemantics { },
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .glass(
                        PpsTheme.shapes.lg.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
                        strong = true,
                        backdrop = backdrop,
                    ).navigationBarsPadding()
                    .padding(spacing.space6),
            verticalArrangement = Arrangement.spacedBy(spacing.space3),
        ) {
            SheetContent(sheet)
            Column(modifier = Modifier.padding(top = spacing.space3), verticalArrangement = Arrangement.spacedBy(spacing.space3)) {
                when (sheet) {
                    is SnoozeSheet.Confirm -> {
                        WakeOutlinedButton(
                            text = stringResource(Res.string.snooze_confirm_pay, formatMoney(sheet.price)),
                            onClick = guardedUpper,
                        )
                        WakePrimaryButton(text = stringResource(Res.string.snooze_confirm_get_up), onClick = guardedDismiss, hero = false)
                    }

                    is SnoozeSheet.Unlocking -> {
                        WakeOutlinedButton(text = stringResource(Res.string.snooze_cancel), onClick = guardedDismiss)
                    }

                    is SnoozeSheet.AlreadyPaid -> {
                        WakeOutlinedButton(text = stringResource(Res.string.snooze_use_it), onClick = guardedUpper)
                        WakePrimaryButton(text = stringResource(Res.string.snooze_not_now), onClick = guardedDismiss, hero = false)
                    }
                }
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
            Text(text = stringResource(Res.string.snooze_confirm_title, sheet.minutes), style = typography.headline, color = colors.text)
            Text(text = price, style = typography.display, color = colors.text)
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
            if (sheet.showTaxNote) {
                Text(text = stringResource(Res.string.snooze_confirm_tax_note), style = typography.caption, color = colors.textSecondary)
            }
        }

        is SnoozeSheet.Unlocking -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(Res.drawable.symbol_lock), contentDescription = null, tint = colors.text)
                Text(
                    text = stringResource(Res.string.snooze_unlock_to_pay, formatMoney(sheet.price)),
                    modifier = Modifier.padding(start = PpsTheme.spacing.space3),
                    style = typography.headline,
                    color = colors.text,
                )
            }
        }

        is SnoozeSheet.AlreadyPaid -> {
            Text(
                text = stringResource(Res.string.snooze_already_paid_body, formatMoney(sheet.price)),
                style = typography.body,
                color = colors.text,
            )
        }
    }
}

/** EXPERIENCE.md `sheet-snooze-confirm`: input is ignored for 500 ms after the sheet opens or changes state. */
const val INPUT_LOCK_MILLIS: Long = 500L

private const val SCRIM_ALPHA = 0.32f
private const val PULSE_SCALE = 1.03f
private const val PULSE_MILLIS = 1_200
private const val FULL_CIRCLE = 360f
private const val START_ANGLE = -90f
private val RING_SIZE = 120.dp
