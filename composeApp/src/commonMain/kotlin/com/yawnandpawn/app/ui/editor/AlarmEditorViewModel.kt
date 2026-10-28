package com.yawnandpawn.app.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yawnandpawn.app.core.alarm.Alarm
import com.yawnandpawn.app.core.alarm.AlarmDraft
import com.yawnandpawn.app.core.alarm.AlarmField
import com.yawnandpawn.app.core.alarm.AlarmRepository
import com.yawnandpawn.app.core.alarm.AlarmRule
import com.yawnandpawn.app.core.alarm.CheckConfig
import com.yawnandpawn.app.core.alarm.CheckConfigRepository
import com.yawnandpawn.app.core.alarm.ReRegisterCode
import com.yawnandpawn.app.core.alarm.SaveAlarm
import com.yawnandpawn.app.core.alarm.nextRing
import com.yawnandpawn.app.core.alarm.orderedEntries
import com.yawnandpawn.app.core.billing.PriceCatalogSnapshot
import com.yawnandpawn.app.core.checks.AccessibilityState
import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.config.GlobalSettingsRepository
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.errorOrNull
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.recording.RecordingRepository
import com.yawnandpawn.app.core.reliability.NotificationPermission
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.ScheduleTestAlarm
import com.yawnandpawn.app.core.sound.SoundLibrary
import com.yawnandpawn.app.core.sound.SoundPreview
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import com.yawnandpawn.app.ui.checks.CheckRegistry
import com.yawnandpawn.app.ui.checks.CheckTrial
import com.yawnandpawn.app.ui.checks.CheckType
import com.yawnandpawn.app.ui.checks.Difficulty
import com.yawnandpawn.app.ui.checks.PickableCheckTypes
import com.yawnandpawn.app.ui.checks.QrTrial
import com.yawnandpawn.app.ui.checks.core
import com.yawnandpawn.app.ui.checks.defaultCount
import com.yawnandpawn.app.ui.checks.toCore
import com.yawnandpawn.app.ui.checks.toUi
import com.yawnandpawn.app.ui.checksetup.CheckSetupIntent
import com.yawnandpawn.app.ui.format.Weekdays
import com.yawnandpawn.app.ui.format.countdownOf
import com.yawnandpawn.app.ui.home.AlarmActions
import com.yawnandpawn.app.ui.qr.CameraPermission
import com.yawnandpawn.app.ui.qr.allowsCameraCheck
import com.yawnandpawn.app.ui.settings.WeakeningNote
import com.yawnandpawn.app.ui.settings.weakeningNoteOf
import com.yawnandpawn.app.ui.wake.WakeIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.random.Random
import kotlin.time.Instant
import com.yawnandpawn.app.core.checks.CheckMode as CoreCheckMode

/**
 * The Koin parameter of [AlarmEditorViewModel]: which alarm to edit, `null` for a new one; [copyOf] prefills a new one
 * from that stored alarm (Duplicate).
 */
data class AlarmEditorArgs(
    val alarmId: String?,
    val copyOf: String? = null,
    /** Opens straight on QR registration of the alarm's QR/Barcode check, and saves the new code (Home's "Re-register"). */
    val scanCode: Boolean = false,
)

/**
 * The Alarm editor (Story 1.8): loads the alarm with [alarmId] (or starts from the defaults, the Settings ones from
 * [newAlarmDefaults] since Story 5.1, or, for Duplicate, from the
 * settings of the stored alarm [copyOf] as a new alarm that is stored only on Save), keeps the form, and saves it
 * through [SaveAlarm] as an enabled alarm (a new alarm identical to a stored one switches that one on instead). Back
 * (or Duplicate) with unsaved changes asks "Discard changes?" first; on a sub-screen (Sound, Snooze) Back returns to the
 * main screen. "Rings in ..." and "Rings tomorrow" are computed with
 * `nextOccurrence` from [clock] and [timeZoneProvider] (never the system clock). A stored alarm that cannot be read
 * closes the editor with [EditorEffect.OpenFailed] (Home shows "Couldn't open this alarm."); an alarm that was read
 * gets the overflow menu (Story 1.9): Duplicate and Delete through [actions]. The Sound sub-screen (Story 1.17) lists
 * the built-in sounds and the phone's alarm ringtones ([soundLibrary]) and previews one at a time ([soundPreview]); the
 * preview stops on leaving the sub-screen, on [EditorIntent.Backgrounded] and when the editor closes. "Test alarm"
 * (Story 1.18) rings the form as it is now, unsaved changes included, as a test 10 s later through [testAlarm], then
 * shows its snackbar. The Wake-up check row (Story 3.5) edits the alarm's checks ([checkConfigs]) in the form: tick a
 * check, its setup (difficulty and count within the type's range), Random / All and the order; with none, Save shows
 * "Pick at least one check." and stores nothing. "Offer to skip 2 h before" (Story 7.11) is a switch in card 1, saved
 * through the commitment lock like the quiet time, and the header's "Rings in" passes over a skipped next ring.
 */
@Suppress("TooManyFunctions", "LargeClass") // One small handler per editor action; at the size limit before Story 5.1.
class AlarmEditorViewModel(
    private val alarmId: String?,
    private val repository: AlarmRepository,
    private val checkConfigs: CheckConfigRepository,
    private val saveAlarm: SaveAlarm,
    private val clock: Clock,
    private val timeZoneProvider: TimeZoneProvider,
    private val actions: AlarmActions,
    private val soundLibrary: SoundLibrary,
    private val soundPreview: SoundPreview,
    private val notificationPermission: NotificationPermission,
    private val testAlarm: ScheduleTestAlarm,
    private val copyOf: String? = null,
    /** The checks the Wake-up check sub-screen offers; tests widen it to exercise several checks. */
    private val pickable: List<CheckType> = PickableCheckTypes,
    /** The seed of each "Try it" (Story 3.6): random in the app (a practice run, not a session), fixed in tests. */
    private val previewSeed: () -> Long = { Random.nextLong() },
    /** TalkBack (Story 3.8): the Memory Sequence note and its numbered "Try it". */
    private val accessibility: AccessibilityState = AccessibilityState { false },
    /** The camera permission asked when QR/Barcode is picked (Story 3.10); null treats the camera as allowed (tests). */
    private val cameraPermission: CameraPermission? = null,
    /** Re-register (Story 3.13): open on QR registration and store the code at once, through [reRegisterCode]. */
    private val scanCode: Boolean = false,
    /** Stores a re-registered code alone (the alarm unchanged); null in tests that never re-register. */
    private val reRegisterCode: ReRegisterCode? = null,
    /**
     * The commitment lock's pending changes (Story 4.6): a stored alarm opens with its pending values selected and their
     * notes, and the fee ladder applies the global ones. Null in tests that never meet the lock: none pending.
     */
    private val pendingChanges: PendingChangeRepository? = null,
    /** The fee ladder's settings and prices (Story 4.6); null shows no ladder (tests that do not price snoozes). */
    private val fees: EditorFeeSources? = null,
    /**
     * The Settings defaults a new alarm opens with (Story 5.1): snooze length, quiet time and its vibration. Not an edit,
     * not a Duplicate (which keeps the copied alarm's values). Null opens with the built-in defaults (tests).
     */
    private val newAlarmDefaults: GlobalSettingsRepository? = null,
    /**
     * Called with the stored alarm's id after every successful save, before the editor closes (Story 5.11: onboarding's
     * first alarm moves on to the checklist and remembers the id). Null for the app's editor.
     */
    private val onSaved: ((String) -> Unit)? = null,
    /**
     * The recorded motivation messages (Story 7.2): with them the app's editor shows the Motivation row (its message is
     * saved with the alarm) and Recordings. Null hides both: onboarding's first alarm, and tests that never meet them.
     */
    private val recordings: RecordingRepository? = null,
) : ViewModel() {
    /**
     * The editor opened on QR registration for "Re-register" (only when the alarm has a QR/Barcode check): "Use this
     * code" stores that code alone, and Back closes the editor. Any other opening edits the form as usual.
     */
    private var reRegistering = false

    /** The pane the printable QR returns to on Back (Story 7.10): Check setup or QR registration, whichever opened it. */
    private var printableFrom = EditorPane.CheckSetup

    /** The pending tick of the running preview (Memory's playback). */
    private var trialTicks: Job? = null

    /** The running "Try it" preview, if any (Story 3.6). */
    private var trial: CheckTrial? = null

    private val _state =
        MutableStateFlow(
            EditorUiState(
                isNew = alarmId == null,
                isLoading = alarmId != null || copyOf != null || newAlarmDefaults != null,
                full = wakeCheckOnly(pickable, talkBackOn = accessibility.isScreenReaderOn(), motivation = recordings != null),
            ).withChecks()
                .withMotivation(),
        )
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _effects = Channel<EditorEffect>(Channel.BUFFERED)
    val effects: Flow<EditorEffect> = _effects.receiveAsFlow()

    /** The form as it was opened; any difference is an unsaved change. */
    private var initialForm = EditorForm()

    /** The stored alarm held a check the form cannot show, so the form already differs from what is stored. */
    private var changedOnOpen = false

    /** The stored alarm being edited, or the one a duplicate is prefilled from (its time names it in the delete dialog). */
    private var stored: Alarm? = null

    /** The Sound row, the Sound sub-screen's list and its preview (Story 1.17). */
    private val sounds = EditorSounds(soundLibrary, soundPreview, viewModelScope, _state)

    /** A "Test alarm" is being armed (Story 1.18). */
    private var testInFlight = false

    /** What "Discard" in the open "Discard changes?" dialog does. */
    private var afterDiscard = AfterDiscard.Close

    /** The stored alarm's pending locked fields (Story 4.6), selected when it opened. */
    private var pendingFields = PendingFields()

    /** The latest fee ladder inputs (Story 4.6); null until read, or without [fees]. */
    private var feeInputs: FeeInputs? = null

    /** The ids of the recorded messages last read (Story 7.2); null until a read succeeds. */
    private var recordingIds: Set<String>? = null

    /**
     * The Motivation row's recorded messages (Story 7.2); null without [recordings]. Last of the properties: a list read
     * at once edits the form, which needs every other property set.
     */
    private val motivation =
        recordings?.let { source ->
            EditorMotivation(
                source,
                viewModelScope,
                _state,
                // The exception class only: never a recording name or path in a log (Story 7.1).
                onReadFailed = { cause -> actions.logFailure(LOAD_RECORDINGS, DomainError.StorageFailure("${cause::class.simpleName}")) },
                onLoaded = ::recordingsRead,
            )
        }

    init {
        // A preview never outlives the editor.
        addCloseable { sounds.stopPreview() }
        fees?.let(::watchFees)
        when {
            alarmId != null -> {
                viewModelScope.launch { load(alarmId, asCopy = false) }
            }

            copyOf != null -> {
                viewModelScope.launch { load(copyOf, asCopy = true) }
            }

            // Story 5.1: a new alarm opens with the Settings defaults once they are read (only the background until then,
            // as when an alarm opens), so it has no unsaved changes; an unreadable store is logged and the built-in ones
            // are used.
            newAlarmDefaults != null -> {
                viewModelScope.launch { open(newAlarmDefaults.newAlarmForm { actions.logFailure(LOAD_DEFAULTS, it) }) }
            }

            else -> {
                _state.update { it.withRings(it.form, clock.now(), timeZoneProvider.current(), skippedDate()) }
                sounds.opened()
            }
        }
    }

    /** Shows the [opened] form (a stored alarm, a duplicate, or a new alarm with the defaults) as the form it starts from. */
    private fun open(
        opened: EditorForm,
        shown: EditorUiState.() -> EditorUiState = { this },
    ) {
        // A message deleted elsewhere is no choice (Story 7.2), and dropping it is no unsaved change.
        val form = opened.withoutGone(recordingIds)
        initialForm = form
        _state.update {
            it
                .copy(isLoading = false, form = form)
                .shown()
                .withRings(form, clock.now(), timeZoneProvider.current(), skippedDate())
                .withChecks()
                .withMotivation()
                .withLock()
        }
        sounds.opened()
    }

    fun onIntent(intent: EditorIntent) {
        // Going to the background always stops a preview, even while a save runs.
        if (intent == EditorIntent.Backgrounded) {
            sounds.stopPreview()
            return
        }
        // While a save runs (and after it succeeded, until the editor closes) nothing else is accepted: an edit would be
        // lost, and Back or Discard would close with the alarm stored anyway.
        if (_state.value.isSaving) return
        when (intent) {
            EditorIntent.SaveClicked -> {
                save()
            }

            EditorIntent.BackRequested -> {
                requestBack()
            }

            EditorIntent.DiscardConfirmed -> {
                confirmDiscard()
            }

            EditorIntent.KeepEditing -> {
                hideDiscardDialog()
            }

            is EditorIntent.PaneOpened -> {
                if (intent.pane != EditorPane.Sound) sounds.stopPreview()
                _state.update { if (it.isLoading) it else it.copy(pane = intent.pane) }
                if (intent.pane == EditorPane.WakeCheck) refreshTalkBack()
            }

            is EditorIntent.RepeatChosen -> {
                chooseRepeat(intent.choice)
            }

            EditorIntent.TestAlarmClicked -> {
                scheduleTest()
            }

            else -> {
                onFormIntent(intent)
            }
        }
    }

    private fun onFormIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.TimeChanged -> {
                editForm { it.copy(time = intent.time) }
            }

            is EditorIntent.DayToggled -> {
                editForm { it.copy(repeatDays = it.repeatDays.toggle(intent.day)) }
            }

            is EditorIntent.LabelChanged -> {
                editLabel(intent.label)
            }

            is EditorIntent.SnoozeLengthSelected -> {
                editForm { it.copy(snoozeLengthMinutes = intent.minutes) }
            }

            is EditorIntent.VolumeChanged -> {
                // Never below the minimum (Epic 3 device check): the slider stops there, and so does any other caller.
                val percent = Alarm.ringableVolume(intent.percent)
                editForm { it.copy(volumePercent = percent) }
                sounds.volumeChanged(percent)
            }

            is EditorIntent.Sound -> {
                sounds.onIntent(intent.intent, ::editForm)
            }

            // Story 3.4: the Quiet time sub-screen.
            is EditorIntent.GraceChanged -> {
                editForm { it.copy(graceSeconds = intent.seconds.coerceIn(Alarm.GRACE_SECONDS_RANGE)) }
            }

            else -> {
                if (!onSwitchIntent(intent) && !onCheckIntent(intent) && !onMotivationIntent(intent)) onMenuIntent(intent)
            }
        }
    }

    /** The form's switches (gradual volume, vibration, vibration in quiet time, "Offer to skip"); false for any other intent. */
    private fun onSwitchIntent(intent: EditorIntent): Boolean {
        when (intent) {
            is EditorIntent.GradualVolumeToggled -> {
                editForm { it.copy(gradualVolume = intent.enabled) }
            }

            is EditorIntent.VibrationToggled -> {
                editForm { it.copy(vibration = intent.enabled) }
            }

            is EditorIntent.VibrateInGraceToggled -> {
                editForm { it.copy(vibrateInGrace = intent.enabled) }
            }

            // Story 7.11: saved through the commitment lock (turning it on within 8 h waits for the next ring).
            is EditorIntent.OfferSkipToggled -> {
                editForm { it.copy(offerSkip = intent.enabled) }
            }

            else -> {
                return false
            }
        }
        return true
    }

    /**
     * The Wake-up check sub-screen and Check setup (Story 3.5); false for any other intent. A ticked check is added last
     * at Medium with its type's default count, and only a check the picker offers can be added.
     */
    private fun onCheckIntent(intent: EditorIntent): Boolean {
        when (intent) {
            is EditorIntent.CheckToggled -> {
                toggleCheck(intent.type, intent.selected)
            }

            is EditorIntent.CheckModeSelected -> {
                editForm { it.copy(checkMode = intent.mode) }
            }

            is EditorIntent.CheckMoved -> {
                editForm { it.copy(checks = it.checks.moved(intent.type, intent.up)) }
            }

            is EditorIntent.CheckSetupClicked -> {
                _state.update { if (it.isLoading) it else it.copy(pane = EditorPane.CheckSetup, setupType = intent.type) }
                refreshTalkBack()
            }

            is EditorIntent.CheckSetup -> {
                onSetupIntent(intent.intent)
            }

            is EditorIntent.TryIt -> {
                onTryItIntent(intent.intent)
            }

            else -> {
                return onCodeIntent(intent)
            }
        }
        return true
    }

    /**
     * The Motivation sub-screen and Recordings (Story 7.2); false for any other intent, and in an editor without the
     * Motivation row. The choice is a form field (an unsaved change until Save), picked by the user only; a deleted message
     * that was chosen becomes None.
     */
    private fun onMotivationIntent(intent: EditorIntent): Boolean {
        if (motivation == null) return false
        when (intent) {
            is EditorIntent.MotivationChosen -> {
                editForm { it.copy(motivation = intent.choice) }
            }

            EditorIntent.RecordMessageClicked -> {
                _state.update { if (it.isLoading) it else it.copy(pane = EditorPane.Recordings) }
            }

            // The new message is listed; the user picks it here (it is never chosen for them).
            is EditorIntent.RecordingSaved -> {
                Unit
            }

            is EditorIntent.RecordingDeleted -> {
                // DeleteRecording set the stored alarm to None too, so that alone is no unsaved change.
                initialForm = initialForm.afterRecordingDeleted(intent.id)
                editForm { it.afterRecordingDeleted(intent.id) }
            }

            // "When it plays" waits for Story 7.5.
            else -> {
                Unit
            }
        }
        return intent is EditorIntent.MotivationChosen ||
            intent == EditorIntent.RecordMessageClicked ||
            intent is EditorIntent.RecordingSaved ||
            intent is EditorIntent.RecordingDeleted
    }

    /**
     * The recorded messages were read ([ids]): a chosen message that is not among them (deleted elsewhere) becomes None in
     * the form as opened and as edited, so it is no unsaved change and Save never stores its id again.
     */
    private fun recordingsRead(ids: Set<String>) {
        recordingIds = ids
        initialForm = initialForm.withoutGone(ids)
        editForm { it.withoutGone(ids) }
    }

    /** QR/Barcode (Story 3.10): a registered code, the "Try it" camera and "Fix"; false for any other intent. */
    private fun onCodeIntent(intent: EditorIntent): Boolean {
        when (intent) {
            // Only from QR registration or the printable QR: a print job that finishes after the user left the printable QR
            // changes nothing (review).
            is EditorIntent.CodeRegistered -> {
                val pane = _state.value.pane
                if (pane != EditorPane.ScanCode && pane != EditorPane.PrintableQr) {
                    Unit
                } else if (reRegistering) {
                    storeCode(intent.code)
                } else {
                    editCheck(CheckType.QrBarcode) { it.copy(code = intent.code) }
                    _state.update { it.copy(pane = EditorPane.CheckSetup, setupType = CheckType.QrBarcode) }
                }
            }

            is EditorIntent.TryItScanned -> {
                (trial as? QrTrial)?.let { showTrial(it.onScan(intent.event)) }
            }

            EditorIntent.FixCamera -> {
                cameraPermission?.openSettings()
            }

            EditorIntent.PrintableQrClicked -> {
                openPrintable()
            }

            else -> {
                return false
            }
        }
        return true
    }

    /**
     * "Make a printable QR" (Story 7.10) from Check setup or QR registration: the printable QR, whose Back returns to the
     * pane that opened it. Its code is registered through [EditorIntent.CodeRegistered], as a scan's.
     */
    private fun openPrintable() {
        val from = _state.value.pane
        if (from != EditorPane.CheckSetup && from != EditorPane.ScanCode) return
        printableFrom = from
        _state.update { it.copy(pane = EditorPane.PrintableQr, setupType = CheckType.QrBarcode) }
    }

    /** Ticks or unticks [type]: added last when the picker offers it; any check left clears "Pick at least one check." */
    private fun toggleCheck(
        type: CheckType,
        selected: Boolean,
    ) {
        // Story 3.10: a camera check is selected only with the camera permission, asked now if missing (FR-ONB-2);
        // refused, the card stays unselected and "Camera isn't available." shows with "Fix".
        val permission = cameraPermission?.takeIf { selected && type.usesCamera && !it.isGranted() }
        if (permission != null) {
            viewModelScope.launch {
                val allowed = permission.allowsCameraCheck()
                _state.update { it.copy(full = it.full?.copy(cameraUnavailable = !allowed)) }
                if (allowed) addOrRemoveCheck(type, selected = true)
            }
            return
        }
        if (selected && type.usesCamera) _state.update { it.copy(full = it.full?.copy(cameraUnavailable = false)) }
        addOrRemoveCheck(type, selected)
    }

    private fun addOrRemoveCheck(
        type: CheckType,
        selected: Boolean,
    ) {
        editForm { form ->
            val has = form.checks.any { it.type == type }
            when {
                selected && !has && type in pickable -> form.copy(checks = form.checks + newCheck(type))
                !selected -> form.copy(checks = form.checks.filterNot { it.type == type })
                else -> form
            }
        }
        _state.update { if (it.form.hasNoCoreCheck()) it else it.copy(full = it.full?.copy(noCheckError = false)) }
    }

    /** Check setup of [EditorUiState.setupType]: difficulty, count (within the type's range) and Back. */
    private fun onSetupIntent(intent: CheckSetupIntent) {
        val type = _state.value.setupType ?: return
        when (intent) {
            CheckSetupIntent.Back -> {
                requestBack()
            }

            is CheckSetupIntent.DifficultySelected -> {
                editCheck(type) { it.copy(difficulty = intent.difficulty) }
            }

            is CheckSetupIntent.CountChanged -> {
                val range = type.core?.countRange ?: return
                editCheck(type) { it.copy(count = intent.count.coerceIn(range)) }
            }

            CheckSetupIntent.TryItClicked -> {
                startTryIt(type)
            }

            // Story 3.10: "Your code" opens QR registration; "Fix" the app's settings.
            CheckSetupIntent.ScanCodeClicked -> {
                if (type == CheckType.QrBarcode) _state.update { it.copy(pane = EditorPane.ScanCode) }
            }

            CheckSetupIntent.FixCamera -> {
                cameraPermission?.openSettings()
            }

            // Story 7.10: "Make a printable QR".
            CheckSetupIntent.PrintableQrClicked -> {
                if (type == CheckType.QrBarcode) openPrintable()
            }

            // House Hunt photos are v2 (owner decision 2026-10-27).
            else -> {
                Unit
            }
        }
    }

    /**
     * "Try it" (Story 3.6): [type] at the difficulty set now, one item, from a fresh [previewSeed]. It runs in this
     * ViewModel's state only (no engine, sound, history or scheduler), and never changes the form.
     */
    private fun startTryIt(type: CheckType) {
        val chip =
            _state.value.form.checks
                .firstOrNull { it.type == type } ?: return
        // TalkBack on: the accessible variant (Memory Sequence's numbered tiles, Story 3.8); the notes follow the same read.
        val talkBackOn = refreshTalkBack()
        val started = CheckRegistry.startTrial(type, chip.difficulty, previewSeed(), talkBackOn, chip.code) ?: return
        _state.update { it.copy(pane = EditorPane.TryIt) }
        showTrial(started)
    }

    /**
     * Reads TalkBack again into the Wake-up check and Check setup notes ("Uses numbered tiles with TalkBack."), so they
     * and "Try it" agree after TalkBack was turned on or off while the editor was open (Story 3.8 review); returns it.
     */
    private fun refreshTalkBack(): Boolean {
        val on = accessibility.isScreenReaderOn()
        _state.update { it.copy(full = it.full?.copy(talkBackOn = on)) }
        return on
    }

    /** A tap in the preview: "Done" returns to Check setup, anything else goes to the trial. */
    private fun onTryItIntent(intent: WakeIntent) {
        val current = trial ?: return
        if (intent == WakeIntent.DoneClicked) {
            closeTryIt()
            return
        }
        showTrial(current.onIntent(intent))
    }

    /**
     * Shows [next] and, when it changes by itself (Memory's playback, Story 3.8), ticks it after its delay on this
     * ViewModel's scope: virtual time in tests, and cancelled by the next change or by leaving the preview.
     */
    private fun showTrial(next: CheckTrial) {
        if (next === trial) return
        trial = next
        _state.update { it.copy(tryIt = next.state) }
        trialTicks?.cancel()
        trialTicks =
            next.nextTick?.let { wait ->
                viewModelScope.launch {
                    delay(wait)
                    if (trial === next) showTrial(next.tick())
                }
            }
    }

    /**
     * "Re-register" (Stories 3.10 and 3.13): stores [code] as the alarm's QR/Barcode code with a new registration time,
     * leaving everything else of the alarm as it is (enabled or not, its time, its arming), then closes. A failure
     * shows "Couldn't save the alarm. Try again." and stays on QR registration.
     */
    private fun storeCode(code: RegisteredCode) {
        val id = alarmId
        val store = reRegisterCode
        if (id == null || store == null || _state.value.isSaving) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            when (val result = store(id, code)) {
                is Outcome.Success -> {
                    close()
                }

                is Outcome.Failure -> {
                    _state.update { it.copy(isSaving = false) }
                    showFailure(result.error)
                }
            }
        }
    }

    /** Back or "Done": Check setup again, with the form as it was. */
    private fun closeTryIt() {
        trialTicks?.cancel()
        trialTicks = null
        trial = null
        _state.update { it.copy(pane = EditorPane.CheckSetup, tryIt = null) }
    }

    private fun editCheck(
        type: CheckType,
        change: (CheckChip) -> CheckChip,
    ) = editForm { form -> form.copy(checks = form.checks.map { if (it.type == type) change(it) else it }) }

    /**
     * Opens the stored alarm [id]: to edit it, or ([asCopy], Duplicate) as a new alarm prefilled with its settings, which
     * stores nothing until Save and has no overflow menu (owner decision 2026-10-05).
     */
    private suspend fun load(
        id: String,
        asCopy: Boolean,
    ) {
        // A duplicate is a new alarm with no commitment: it opens with the stored (live) values.
        val read =
            repository.get(id).flatMap { alarm ->
                checkConfigs.forAlarm(id).flatMap { checks -> pendingOf(id, asCopy).map { Triple(alarm, checks, it) } }
            }
        when (read) {
            is Outcome.Success -> {
                val (alarm, checks, pending) = read.value
                stored = alarm
                pendingFields = PendingFields.of(pending, checks.orderedEntries(), ::chipsOf)
                val form = alarm.toForm(checks).withPending(pendingFields)
                changedOnOpen = hasUnshownChecks(checks)
                val custom = form.repeatDays.isNotEmpty() && form.repeatDays != Weekdays
                // Re-register (Story 3.13): straight to QR registration when the alarm has a QR/Barcode check.
                val scan = scanCode && form.checks.any { it.type == CheckType.QrBarcode }
                reRegistering = scan
                open(form) {
                    copy(customRepeat = custom, hasOverflowMenu = !asCopy)
                        .let { opened -> if (scan) opened.copy(pane = EditorPane.ScanCode, setupType = CheckType.QrBarcode) else opened }
                }
            }

            // Deleted meanwhile, or storage unreadable (its pending changes too, which a save must not cancel unseen):
            // there is nothing to edit, and Home says so.
            is Outcome.Failure -> {
                actions.logFailure("open alarm", read.error)
                _effects.send(EditorEffect.OpenFailed)
            }
        }
    }

    private fun editForm(change: (EditorForm) -> EditorForm) {
        _state.update { current ->
            if (current.isLoading) return@update current
            val form = change(current.form)
            val fieldError = current.fieldError?.takeUnless { it != AlarmField.Label && form != current.form }
            current
                .copy(form = form, fieldError = fieldError)
                .withRings(form, clock.now(), timeZoneProvider.current(), skippedDate())
                .withChecks()
                .withMotivation()
                .withLock()
        }
    }

    /**
     * Keeps the fee ladder's inputs (Story 4.6): the live global settings, the global pending changes and the cached
     * prices, each re-read on every change. A store that cannot be read is logged and counts as the defaults, none
     * pending, or no cached prices (the ladder then shows USD approximations); the editor never waits for a price.
     */
    private fun watchFees(sources: EditorFeeSources) {
        val settings = sources.globalSettings.observe().catch { cause -> logFee(cause).also { emit(GlobalSettings()) } }
        val pending = (pendingChanges?.observe() ?: flowOf(emptyList())).catch { cause -> logFee(cause).also { emit(emptyList()) } }
        val prices = sources.priceCatalog.observe().catch { cause -> logFee(cause).also { emit(PriceCatalogSnapshot.EMPTY) } }
        viewModelScope.launch {
            combine(settings, pending, prices) { live, waiting, cached -> FeeInputs(live, waiting, cached) }.collect { inputs ->
                feeInputs = inputs
                _state.update { it.withLock() }
            }
        }
    }

    private fun logFee(cause: Throwable) = actions.logFailure("load fee ladder", cause)

    /** The stored alarm's own pending changes (Story 4.6); none for a duplicate or without [pendingChanges]. */
    private suspend fun pendingOf(
        id: String,
        asCopy: Boolean,
    ): Outcome<List<PendingChange>, DomainError> = pendingChanges?.takeUnless { asCopy }?.forAlarm(id) ?: Outcome.Success(emptyList())

    /** The edited alarm's skipped date (Story 7.11): its "Rings in" passes over that ring. None for a new alarm or a copy. */
    private fun skippedDate(): LocalDate? = stored?.takeIf { alarmId != null }?.skippedDate

    /** The fee ladder and the lock notes for the form as it is now ([lockSections], Story 4.6). */
    private fun EditorUiState.withLock(): EditorUiState {
        val ladder = fees?.let { sources -> feeInputs?.let { it to sources.moneyFormatter } }
        return copy(full = full?.lockSections(form, alarmId, pendingFields, ladder, clock.now(), timeZoneProvider.current()))
    }

    private fun editLabel(label: String) {
        editForm { it.copy(label = label) }
        // The label error clears as soon as the label fits again.
        _state.update { current ->
            if (current.fieldError == AlarmField.Label && !isLabelTooLong(label)) current.copy(fieldError = null) else current
        }
    }

    /** No check a ring can run: none at all, or only checks without a core plugin, which a save would drop. */
    private fun EditorForm.hasNoCoreCheck(): Boolean = checks.none { it.type.core != null }

    /**
     * Whether the form's checks stop Save before it stores anything, and shows why:
     * - no check (or only checks without a core plugin, which a save would drop): "Pick at least one check." under the
     *   Wake-up check row and in its sub-screen;
     * - a QR/Barcode check without a code, which can never be passed (`SaveAlarm` refuses it, Story 3.10): its Check
     *   setup opens, where "Your code" says "Scan a code to use this check.".
     */
    private fun checksBlockSave(form: EditorForm): Boolean {
        val noCheck = form.hasNoCoreCheck()
        val noCode = form.checks.any { it.type == CheckType.QrBarcode && it.code == null }
        when {
            noCheck -> _state.update { it.copy(full = it.full?.copy(noCheckError = true)) }
            noCode -> _state.update { it.copy(pane = EditorPane.CheckSetup, setupType = CheckType.QrBarcode) }
        }
        return noCheck || noCode
    }

    private fun save() {
        val current = _state.value
        if (current.isLoading || current.isSaving || checksBlockSave(current.form)) return
        _state.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            when (val result = saveAlarm.save(current.form.toDraft(alarmId))) {
                // Stays saving until the screen leaves, so a quick second tap cannot store the alarm twice.
                is Outcome.Success -> {
                    // The first save of an enabled alarm (every editor save is one) asks for notifications, once
                    // (Story 1.19); the dialog shows over Home after the editor closes. The alarm is saved, so the
                    // editor closes even when asking fails.
                    notificationPermission.askOnce(actions)
                    onSaved?.invoke(result.value.alarm.id)
                    // Story 4.6: a weakening change waits under the lock, and Home says until when.
                    val waits = weakeningNoteOf(result.value.pendingUntil?.scheduledAt, clock.now(), timeZoneProvider.current())
                    if (waits == null) close() else closeSavedUnderLock(waits)
                }

                is Outcome.Failure -> {
                    if (result.error is DomainError.NotFound) {
                        // Deleted elsewhere while open: there is nothing left to save into.
                        close()
                    } else {
                        _state.update { it.copy(isSaving = false) }
                        showFailure(result.error)
                    }
                }
            }
        }
    }

    /**
     * "Test alarm": the form as it is now (unsaved changes included, built like a save) rings as a test 10 s later,
     * then "Lock your phone. We'll ring in 10 seconds." shows. A test that cannot be armed is logged and shows nothing.
     */
    private fun scheduleTest() {
        val current = _state.value
        // One test at a time: a second tap while one is being armed would interleave the store and the arming.
        if (current.isLoading || testInFlight) return
        // The same label rule as Save: an over-long label shows its field error and rings no test.
        if (isLabelTooLong(current.form.label)) {
            _state.update { it.copy(fieldError = AlarmField.Label) }
            return
        }
        testInFlight = true
        viewModelScope.launch {
            // Not cancelled by the editor closing: the config is stored and the alarm armed (or both taken back).
            val result = withContext(NonCancellable) { testAlarm(current.form.toDraft(alarmId)) }
            testInFlight = false
            when (result) {
                is Outcome.Success -> _effects.trySend(EditorEffect.ShowTestScheduled)
                is Outcome.Failure -> actions.logFailure("schedule test alarm", result.error)
            }
        }
    }

    private suspend fun showFailure(error: DomainError) {
        // A session started meanwhile (Story 2.6): the session lock closes the editor, nothing failed.
        if (error == DomainError.SessionActive) return
        // No check never gets here: Save shows "Pick at least one check." first, and the picker offers valid checks only.
        if (error is DomainError.InvalidAlarm) {
            _state.update { it.copy(fieldError = error.field) }
            // Only the label has its own message on the field; the other controls cannot produce an invalid value.
            if (error.field == AlarmField.Label) return
        }
        _effects.send(EditorEffect.ShowSaveFailed)
    }

    /** "Once" clears the days, "Weekdays" sets Monday to Friday, "Custom" shows the day chips and keeps the days. */
    private fun chooseRepeat(choice: RepeatChoice) {
        when (choice) {
            RepeatChoice.Once -> editForm { it.copy(repeatDays = emptySet()) }
            RepeatChoice.Weekdays -> editForm { it.copy(repeatDays = Weekdays) }
            RepeatChoice.Custom -> Unit
        }
        _state.update { if (it.isLoading) it else it.copy(customRepeat = choice == RepeatChoice.Custom) }
    }

    private fun requestBack() {
        val current = _state.value
        when {
            current.showDiscardDialog -> {
                hideDiscardDialog()
            }

            current.pane == EditorPane.TryIt -> {
                closeTryIt()
            }

            // The printable QR returns to the pane that opened it (Story 7.10).
            current.pane == EditorPane.PrintableQr -> {
                _state.update { it.copy(pane = printableFrom) }
            }

            // QR registration returns to Check setup; opened from "Re-register", Back leaves the editor unchanged.
            current.pane == EditorPane.ScanCode -> {
                if (reRegistering) close() else _state.update { it.copy(pane = EditorPane.CheckSetup) }
            }

            // Recordings returns to the Motivation sub-screen it was opened from (Story 7.2).
            current.pane == EditorPane.Recordings -> {
                _state.update { it.copy(pane = EditorPane.Motivation) }
            }

            current.pane == EditorPane.CheckSetup -> {
                // Check setup returns to the Wake-up check sub-screen it was opened from.
                _state.update { it.copy(pane = EditorPane.WakeCheck, setupType = null) }
            }

            current.pane != EditorPane.Main -> {
                // Leaving the Sound sub-screen stops its preview.
                sounds.stopPreview()
                _state.update { it.copy(pane = EditorPane.Main) }
            }

            hasUnsavedChanges() -> {
                askDiscard(AfterDiscard.Close)
            }

            else -> {
                close()
            }
        }
    }

    private fun hasUnsavedChanges(): Boolean = _state.value.let { !it.isLoading && (changedOnOpen || it.form != initialForm) }

    /** "Discard changes?" before [then]: Back closes the editor, Duplicate opens a new alarm prefilled from the stored one. */
    private fun askDiscard(then: AfterDiscard) {
        afterDiscard = then
        _state.update { it.copy(showDiscardDialog = true) }
    }

    private fun hideDiscardDialog() {
        afterDiscard = AfterDiscard.Close
        _state.update { it.copy(showDiscardDialog = false) }
    }

    /** "Discard": does what asked the dialog (Back closes, Duplicate opens a new alarm prefilled from the stored one). */
    private fun confirmDiscard() {
        // A second tap after the dialog went (or a stale event) does nothing.
        if (!_state.value.showDiscardDialog) return
        val then = afterDiscard
        hideDiscardDialog()
        when (then) {
            AfterDiscard.Close -> close()
            AfterDiscard.Duplicate -> alarmId?.let(::duplicate)
        }
    }

    private fun close() {
        _state.update { it.copy(showDiscardDialog = false) }
        _effects.trySend(EditorEffect.Close)
    }

    private fun closeSavedUnderLock(note: WeakeningNote) {
        _state.update { it.copy(showDiscardDialog = false) }
        _effects.trySend(EditorEffect.CloseSavedUnderLock(note))
    }

    /**
     * The overflow menu of a stored alarm. Duplicate opens a new, unsaved alarm prefilled from the stored alarm (not
     * from unsaved changes; owner decision 2026-10-05);
     * with unsaved changes it asks "Discard changes?" first (owner decision 2026-10-02). Delete asks with the stored
     * time, then deletes (logged) and closes. While either runs nothing else is accepted; a failure shows "Couldn't save
     * the alarm. Try again." (owner decision 2026-10-02).
     */
    private fun onMenuIntent(intent: EditorIntent) {
        val id = alarmId?.takeIf { _state.value.hasOverflowMenu } ?: return
        when (intent) {
            EditorIntent.DuplicateClicked -> {
                if (hasUnsavedChanges()) askDiscard(AfterDiscard.Duplicate) else duplicate(id)
            }

            EditorIntent.DeleteClicked -> {
                _state.update { it.copy(deleteDialogTime = stored?.time ?: it.form.time) }
            }

            EditorIntent.DeleteCancelled -> {
                _state.update { it.copy(deleteDialogTime = null) }
            }

            EditorIntent.DeleteConfirmed -> {
                // Cleared first, so a second tap on "Delete" cannot delete (and log) twice.
                if (_state.value.deleteDialogTime == null) return
                _state.update { it.copy(deleteDialogTime = null, isSaving = true) }
                viewModelScope.launch {
                    val result = actions.delete(id)
                    // Gone already (deleted elsewhere) also leaves nothing to edit.
                    val gone = result is Outcome.Success || result.errorOrNull() is DomainError.NotFound
                    if (gone) {
                        close()
                    } else {
                        _state.update { it.copy(isSaving = false) }
                        showFailure(result.errorOrNull() ?: return@launch)
                    }
                }
            }

            else -> {
                Unit
            }
        }
    }

    /** Opens a new, unsaved alarm prefilled from the stored alarm [id] in place of this editor (nothing is stored). */
    private fun duplicate(id: String) {
        // From now on this editor only leaves: a Save tapped before the navigation must not store the discarded edits.
        _state.update { it.copy(isSaving = true) }
        _effects.trySend(EditorEffect.OpenCopy(id))
    }
}

/** What asked "Discard changes?": Back (close the editor) or the overflow menu's Duplicate (open the copy). */
private enum class AfterDiscard { Close, Duplicate }

/** Asks once for notifications; a failure to ask is logged (the Home banner still shows the missing permission). */
@Suppress("TooGenericExceptionCaught")
private fun NotificationPermission.askOnce(actions: AlarmActions) {
    try {
        if (shouldRequest()) request()
    } catch (e: Exception) {
        actions.logFailure("request notification permission", e)
    }
}

private fun EditorForm.toDraft(alarmId: String?): AlarmDraft =
    AlarmDraft(
        id = alarmId,
        time = time,
        repeatDays = repeatDays,
        label = label,
        enabled = true,
        // Fields the editor does not show are made valid here, so the only field error a user can meet is the label.
        // The chosen sound, kept even when it is missing: the alarm then rings the default (never silent).
        soundRef = soundRef.ifBlank { Alarm.DEFAULT_SOUND_REF },
        volumePercent = volumePercent,
        gradualVolume = gradualVolume,
        // Not editable (owner decision 2026-09-27): the ramp starts at the fixed 20% of the set volume, at any volume.
        rampStartPercent = Alarm.DEFAULT_RAMP_START_PERCENT,
        vibration = vibration,
        snoozeLengthMinutes = snoozeLengthMinutes,
        graceSeconds = graceSeconds.coerceIn(Alarm.GRACE_SECONDS_RANGE),
        vibrateInGrace = vibrateInGrace,
        checks =
            checks.mapNotNull { chip ->
                chip.type.core?.let { CheckEntry(it, chip.difficulty.toCore(), chip.count, code = chip.code) }
            },
        checkMode = CoreCheckMode.valueOf(checkMode.name),
        motivation = motivation.toCore(),
        offerSkip = offerSkip,
    )

/**
 * The production editor's full-editor rows (Story 3.5): the Wake-up check, offering [pickable] (the Quiet time row shows
 * in every editor, Story 3.4), with [motivation] the Motivation row (Story 7.2), without "When it plays" (Story 7.5), and
 * the "Offer to skip 2 h before" switch (Story 7.11).
 */
private fun wakeCheckOnly(
    pickable: List<CheckType>,
    talkBackOn: Boolean,
    motivation: Boolean,
): FullEditorSections =
    FullEditorSections(
        rows = if (motivation) setOf(EditorPane.WakeCheck, EditorPane.Motivation) else setOf(EditorPane.WakeCheck),
        types = pickable,
        talkBackOn = talkBackOn,
        motivationTimingShown = false,
        showOfferSkip = true,
    )

/** The Wake-up check row and sub-screen show the form's checks and mode, and whether QR/Barcode has its code. */
private fun EditorUiState.withChecks(): EditorUiState =
    copy(
        full =
            full?.copy(
                checks = form.checks,
                checkMode = form.checkMode,
                qrCodeSaved = form.checks.any { it.type == CheckType.QrBarcode && it.code != null },
            ),
    )

/**
 * A newly ticked check, with its type's default count (Story 3.5): Math at Easy, the default check's difficulty (owner
 * decision 2026-10-08: "Make Easy the default and leave Medium as it is"), every other type at Medium.
 */
private fun newCheck(type: CheckType): CheckChip =
    CheckChip(type, if (type == CheckType.Math) Difficulty.Easy else Difficulty.Medium, type.core?.defaultCount ?: type.defaultCount)

/** [type] one place up or down, or the list unchanged at either end. */
private fun List<CheckChip>.moved(
    type: CheckType,
    up: Boolean,
): List<CheckChip> {
    val from = indexOfFirst { it.type == type }
    val to = if (up) from - 1 else from + 1
    if (from < 0 || to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * "Rings in ..." and, for a one-time alarm whose time has passed today, when it rings tomorrow (shifted in a DST gap). A
 * repeating alarm's ring on [skippedDate] is passed over (Story 7.11, as `SaveAlarm` keeps the skip for a repeating edit).
 */
private fun EditorUiState.withRings(
    form: EditorForm,
    now: Instant,
    zone: TimeZone,
    skippedDate: LocalDate?,
): EditorUiState {
    val next = nextRing(AlarmRule(form.time, form.repeatDays), skippedDate.takeIf { form.repeatDays.isNotEmpty() }, now, zone)
    val ring = next.toLocalDateTime(zone)
    val tomorrow = form.repeatDays.isEmpty() && ring.date != now.toLocalDateTime(zone).date
    return copy(ringsIn = countdownOf(next - now), ringsTomorrowAt = if (tomorrow) ring.time else null)
}

/**
 * The stored alarm and its [checks] as the form shows them; out-of-range stored values (a check's count included, within
 * its type's range) open as the nearest valid value, and an alarm stored without checks opens with the default ones. A
 * check the editor cannot show ([hasUnshownChecks]) is left out.
 */
private fun Alarm.toForm(checks: List<CheckConfig>): EditorForm =
    EditorForm(
        checks = chipsOf(checks.orderedEntries()),
        checkMode = CheckMode.valueOf(checkMode.name),
        time = time,
        repeatDays = repeatDays,
        label = label.orEmpty(),
        snoozeLengthMinutes = snoozeLengthMinutes.takeIf { it in Alarm.SNOOZE_LENGTHS_MINUTES } ?: Alarm.DEFAULT_SNOOZE_LENGTH_MINUTES,
        volumePercent = Alarm.ringableVolume(volumePercent),
        gradualVolume = gradualVolume,
        rampStartPercent = rampStartPercent.coerceIn(Alarm.PERCENT_RANGE),
        vibration = vibration,
        soundRef = soundRef.ifBlank { Alarm.DEFAULT_SOUND_REF },
        graceSeconds = graceSeconds.coerceIn(Alarm.GRACE_SECONDS_RANGE),
        vibrateInGrace = vibrateInGrace,
        motivation = motivation.toChoice(),
        offerSkip = offerSkip,
    )

/**
 * Whether [checks] hold a type the editor cannot show (no picker row; only the Epic 1 `Placeholder`, which no picker
 * offers and `validateChecks` rejects, so it could never be saved back). The editor leaves it out of the form and
 * counts that as an unsaved change, so the replacement is explicit: Back asks "Discard changes?" and Save stores the
 * checks shown (Story 3.5 review).
 */
private fun hasUnshownChecks(checks: List<CheckConfig>): Boolean = checks.any { it.entry.type.toUi() == null }

private fun <T> Set<T>.toggle(item: T): Set<T> = if (item in this) this - item else this + item

/** Same rule as `SaveAlarm`: trimmed, counted in code points (a surrogate pair is one character). */
internal fun isLabelTooLong(label: String): Boolean {
    val trimmed = label.trim()
    var count = 0
    var index = 0
    while (index < trimmed.length) {
        val pair = trimmed[index].isHighSurrogate() && index + 1 < trimmed.length && trimmed[index + 1].isLowSurrogate()
        index += if (pair) 2 else 1
        count++
    }
    return count > Alarm.MAX_LABEL_LENGTH
}
