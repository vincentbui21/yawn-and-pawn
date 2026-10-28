package com.yawnandpawn.app.ui.editor

import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmRule
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.nextOccurrence
import com.yawnandpawn.app.core.billing.MoneyFormatter
import com.yawnandpawn.app.core.billing.PriceCatalog
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.LockWindow
import com.yawnandpawn.app.core.config.LockedField
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.config.applyingTo
import com.yawnandpawn.app.core.config.withCodesFrom
import com.yawnandpawn.app.core.config.withPending
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.ui.checks.toUi
import com.yawnandpawn.app.ui.settings.SnoozePrices
import com.yawnandpawn.app.ui.settings.weakeningNoteOf
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * What the editor's fee ladder reads (Story 4.6): the live global settings, Story 4.3's cached Play prices, and the
 * phone's [MoneyFormatter] for the USD approximations. The global pending changes come from the editor's
 * `PendingChangeRepository`.
 */
class EditorFeeSources(
    val globalSettings: GlobalSettingsRepository,
    val priceCatalog: PriceCatalog,
    val moneyFormatter: MoneyFormatter,
)

/** The latest read of the fee ladder's inputs. */
internal data class FeeInputs(
    val settings: GlobalSettings = GlobalSettings(),
    val pending: List<PendingChange> = emptyList(),
    val prices: PriceCatalogSnapshot = PriceCatalogSnapshot.EMPTY,
)

/**
 * The fee ladder of an alarm ringing as [form] says (Story 4.6): the base fee and max snoozes its next ring after [now]
 * will charge, which are the live global settings with the global pending changes that apply to that ring (a change
 * waiting for a later ring is not shown), priced by [SnoozePrices] (Play's prices, or all USD approximations). A new
 * alarm ([alarmId] null) only has the global changes. A change still waiting for one of this alarm's own rings is never
 * shown: saving the alarm with a later time moves it to the new ring (`SaveAlarm`, Story 4.6 review fix 2).
 */
internal fun feeLadderOf(
    form: EditorForm,
    alarmId: String?,
    inputs: FeeInputs,
    now: Instant,
    zone: TimeZone,
    formatter: MoneyFormatter,
): Pair<List<LadderPrice>, Boolean> {
    val next = nextOccurrence(AlarmRule(form.time, form.repeatDays), now, zone)
    val waitingForThisAlarm: (PendingChange) -> Boolean = { it.effectiveAfter.alarmId == alarmId && it.effectiveAfter.scheduledAt > now }
    val global = inputs.pending.filter { it.alarmId == null }.filterNot(waitingForThisAlarm)
    val settings = inputs.settings.withPending(global.applyingTo(alarmId.orEmpty(), next))
    val prices = SnoozePrices.of(settings.baseFeeTier, settings.maxSnoozes, inputs.prices, now, formatter)
    return prices.ladder.map(LadderPrice::Text) to prices.approximate
}

/**
 * The pending (weaker) values of a stored alarm's locked fields as the form shows them (Story 4.6), and the ring each
 * waits for. Opening the editor selects them, so saving again keeps the commitment as it is (`CommitmentRules`: the
 * same value stays pending with its date), and setting a field back to its live value cancels its pending change.
 */
internal data class PendingFields(
    /** The pending quiet time, in seconds, and the ring it waits for. */
    val grace: Pair<Int, Instant>? = null,
    /** The pending checks and mode as the form shows them, and the ring they wait for. */
    val checks: Pair<Pair<List<CheckChip>, CheckMode>, Instant>? = null,
    /** The pending "Offer to skip" (on), and the ring it waits for (Story 7.11). */
    val offerSkip: Pair<Boolean, Instant>? = null,
) {
    companion object {
        /**
         * The alarm's own [pending] changes (global ones are left out), with the codes registered on the live
         * [liveEntries] (a re-registered sticker always wins, as when the change is promoted); [toChips] maps entries
         * to the form's chips.
         */
        fun of(
            pending: List<PendingChange>,
            liveEntries: List<CheckEntry>,
            toChips: (List<CheckEntry>) -> List<CheckChip>,
        ): PendingFields {
            val own = pending.filter { it.alarmId != null }.associateBy { it.field }
            val grace =
                (own[LockedField.GraceSeconds]?.value as? SettingValue.GraceSeconds)?.let { value ->
                    value.seconds to own.getValue(LockedField.GraceSeconds).effectiveAfter.scheduledAt
                }
            val checks =
                (own[LockedField.Checks]?.value as? SettingValue.Checks)?.let { value ->
                    val plan = value.plan.withCodesFrom(liveEntries)
                    val chips = toChips(plan.entries.ifEmpty { CheckConfig.DEFAULT_ENTRIES })
                    (chips to CheckMode.valueOf(plan.mode.name)) to own.getValue(LockedField.Checks).effectiveAfter.scheduledAt
                }
            val offerSkip =
                (own[LockedField.OfferSkip]?.value as? SettingValue.OfferSkip)?.let { value ->
                    value.on to own.getValue(LockedField.OfferSkip).effectiveAfter.scheduledAt
                }
            return PendingFields(grace, checks, offerSkip)
        }
    }
}

/**
 * [entries] as the form's chips; a stored count outside its type's range opens as the nearest valid one, a type the
 * editor cannot show is left out, and none left opens with the default checks.
 */
internal fun chipsOf(entries: List<CheckEntry>): List<CheckChip> =
    entries
        .mapNotNull { entry ->
            entry.type.toUi()?.let {
                CheckChip(it, entry.difficulty.toUi(), entry.count.coerceIn(entry.type.countRange), code = entry.code)
            }
        }.ifEmpty { EditorForm.DEFAULT_CHECKS }

/** The form with the alarm's [pending] values selected (Story 4.6), so a save keeps them waiting. */
internal fun EditorForm.withPending(pending: PendingFields): EditorForm {
    val grace = pending.grace?.first?.coerceIn(Alarm.GRACE_SECONDS_RANGE) ?: graceSeconds
    val checks = pending.checks?.first
    return copy(
        graceSeconds = grace,
        checks = checks?.first ?: this.checks,
        checkMode = checks?.second ?: checkMode,
        offerSkip = pending.offerSkip?.first ?: offerSkip,
    )
}

/**
 * The commitment-lock parts of the editor (Story 4.6): the fee ladder for [form]'s next ring (when [ladder]'s inputs
 * and formatter are known), and the "Saved. Takes effect after ..." notes of the fields that hold their [pending]
 * value (a field set to another value shows none: saving that would replace or cancel the waiting change). A note
 * names the ring a save would wait for: the later of the one the change waits for and the form's ring when that is in
 * the window (review fix 1: an alarm moved later never rings the later ring with the weaker value).
 */
internal fun FullEditorSections.lockSections(
    form: EditorForm,
    alarmId: String?,
    pending: PendingFields,
    ladder: Pair<FeeInputs, MoneyFormatter>?,
    now: Instant,
    zone: TimeZone,
): FullEditorSections {
    val formRing = nextOccurrence(AlarmRule(form.time, form.repeatDays), now, zone).takeIf { LockWindow.contains(it, now) }
    val waitsUntil: (Instant) -> Instant = { maxOf(it, formRing ?: it) }
    val graceAt =
        pending.grace
            ?.takeIf { (seconds, _) -> form.graceSeconds == seconds }
            ?.second
            ?.let(waitsUntil)
    val checksAt =
        pending.checks
            ?.takeIf { (shown, _) -> shown == (form.checks to form.checkMode) }
            ?.second
            ?.let(waitsUntil)
    // Story 7.11: "Offer to skip" switched on while it is pending shows the same note under the cards.
    val offerSkipAt =
        pending.offerSkip
            ?.takeIf { (on, _) -> form.offerSkip == on }
            ?.second
            ?.let(waitsUntil)
    val prices = ladder?.let { (inputs, formatter) -> feeLadderOf(form, alarmId, inputs, now, zone, formatter) }
    return copy(
        feeLadder = prices?.first,
        pricesApproximate = prices?.second == true,
        graceNote = weakeningNoteOf(graceAt, now, zone),
        checksNote = weakeningNoteOf(checksAt, now, zone),
        weakeningNote = weakeningNoteOf(listOfNotNull(graceAt, checksAt, offerSkipAt).maxOrNull(), now, zone),
    )
}
