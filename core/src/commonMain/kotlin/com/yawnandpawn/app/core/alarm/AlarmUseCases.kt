package com.yawnandpawn.app.core.alarm

import com.yawnandpawn.app.core.checks.CheckEntry
import com.yawnandpawn.app.core.checks.CheckMode
import com.yawnandpawn.app.core.checks.CheckPlan
import com.yawnandpawn.app.core.checks.CheckType
import com.yawnandpawn.app.core.checks.qr.RegisteredCode
import com.yawnandpawn.app.core.config.CommitmentAction
import com.yawnandpawn.app.core.config.CommitmentLog
import com.yawnandpawn.app.core.config.CommitmentRules
import com.yawnandpawn.app.core.config.LockDecision
import com.yawnandpawn.app.core.config.LockWindow
import com.yawnandpawn.app.core.config.LockedField
import com.yawnandpawn.app.core.config.Occurrence
import com.yawnandpawn.app.core.config.PendingChange
import com.yawnandpawn.app.core.config.PendingChangeRepository
import com.yawnandpawn.app.core.config.SettingValue
import com.yawnandpawn.app.core.config.withCodesFrom
import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.error.flatMap
import com.yawnandpawn.app.core.error.map
import com.yawnandpawn.app.core.id.IdGenerator
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.onboarding.OnboardingRepository
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.time.Clock
import com.yawnandpawn.app.core.time.TimeZoneProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * What the editor hands to [SaveAlarm]: every user-editable field. [id] is `null` for a new alarm and the stored
 * alarm's id for an edit.
 */
data class AlarmDraft(
    val id: String? = null,
    val time: LocalTime,
    val repeatDays: Set<DayOfWeek> = emptySet(),
    val label: String? = null,
    val enabled: Boolean = true,
    val soundRef: String = Alarm.DEFAULT_SOUND_REF,
    val volumePercent: Int = Alarm.DEFAULT_VOLUME_PERCENT,
    val gradualVolume: Boolean = true,
    val rampStartPercent: Int = Alarm.DEFAULT_RAMP_START_PERCENT,
    val vibration: Boolean = true,
    val snoozeLengthMinutes: Int = Alarm.DEFAULT_SNOOZE_LENGTH_MINUTES,
    val graceSeconds: Int = Alarm.DEFAULT_GRACE_SECONDS,
    val vibrateInGrace: Boolean = Alarm.DEFAULT_VIBRATE_IN_GRACE,
    /** The alarm's checks in the order All mode runs them (Story 3.5). */
    val checks: List<CheckEntry> = CheckConfig.DEFAULT_ENTRIES,
    val checkMode: CheckMode = CheckMode.Random,
)

/**
 * What [SaveAlarm.save] stored: [alarm] as stored (a weakened locked field keeps its effective value), and the occurrence
 * after which its pending changes take effect ([pendingUntil] null: everything applied at once). Story 4.6's editor shows
 * "Saved. Takes effect after tomorrow's {time} alarm." from it.
 */
data class AlarmSaved(
    val alarm: Alarm,
    val pendingUntil: Occurrence?,
)

/**
 * Serializes the read-modify-write of every alarm use case (request-code allocation, get-then-upsert, the scheduler
 * sync after it), so two concurrent saves never pick the same code, an edit racing a delete never re-inserts the
 * deleted alarm, and the system alarms are changed in the same order as the stored ones.
 * One shared instance per app (a Koin `single`), passed to each use case and to [AlarmScheduling].
 */
class AlarmWriteLock {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Creates or edits an alarm. A new alarm gets a new id, the next code from [RequestCodeSequence] and
 * `createdAt = updatedAt = now`; an edit keeps id, request code and `createdAt` and sets `updatedAt = now`. The time
 * is truncated to whole minutes, and a blank label is stored as no label. Invalid input is `InvalidAlarm(field)` and
 * nothing is stored. Once stored, the system alarm follows ([AlarmScheduling.sync]).
 *
 * A new enabled alarm whose settings are identical to a stored alarm's ([hasSameSettingsAs]) is not stored a second
 * time: the stored one is switched on instead (`updatedAt = now`, armed at its next occurrence) and returned, and no
 * request code is allocated (owner decision 2026-10-05, like Samsung Clock). Any difference, or a new alarm saved off,
 * stores the new alarm as usual. The draft's checks (Story 3.5) are validated with the alarm (`InvalidAlarm(Checks)`)
 * and stored with it in one transaction ([CheckConfigRepository.saveWithAlarm]); an identical alarm has the same checks
 * in the same order too.
 *
 * **Commitment lock (Story 4.4, AD-16):** an edit's locked fields (grace window and checks) go through
 * [CommitmentRules.decide]. The alarm is locked by its stored next occurrence (when the stored alarm is on) and by its
 * new one (when the draft is on), whichever are inside the window; a weakening change waits for the latest of them. The
 * other fields are stored at once, the weakened ones keep their effective values in the row and are stored as
 * [PendingChange]s after it ([save] returns when they take effect). A new alarm has no commitment yet.
 *
 * **Consent (Story 5.10, FR-ONB-5):** until the user has accepted the alarm behaviour disclosure in onboarding
 * ([OnboardingRepository]), every save (a new alarm or an edit) returns [DomainError.DisclosureRequired] and nothing is
 * stored or armed. A progress that cannot be read refuses the save too, with its `StorageFailure` (fail closed). An alarm
 * stored before this rule is left as it is and still rings.
 */
class SaveAlarm(
    private val repository: AlarmRepository,
    private val idGenerator: IdGenerator,
    private val clock: Clock,
    private val lock: AlarmWriteLock,
    private val requestCodes: RequestCodeSequence,
    private val scheduling: AlarmScheduling,
    private val sessionLock: SessionLockGuard,
    private val checkConfigRepository: CheckConfigRepository,
    private val pendingRepository: PendingChangeRepository,
    private val timeZoneProvider: TimeZoneProvider,
    private val onboarding: OnboardingRepository,
) {
    /** [save], returning the stored alarm (its locked fields hold their effective values). */
    suspend operator fun invoke(draft: AlarmDraft): Outcome<Alarm, DomainError> = save(draft).map { it.alarm }

    /** Stores [draft]; the result says whether a locked field waits ([AlarmSaved.pendingUntil]). */
    suspend fun save(draft: AlarmDraft): Outcome<AlarmSaved, DomainError> =
        lock.withLock {
            sessionLock.whenIdle {
                consentMissing()?.let { return@whenIdle Outcome.Failure(it) }
                val now = clock.nowMillis()
                val id = draft.id
                if (id == null) {
                    val template = draft.toAlarm(id = idGenerator.newId(), requestCode = RequestCodes.FIRST_ALARM, createdAt = now)
                    // Validate before allocating a code, so invalid input never touches storage.
                    val invalid = validate(template) ?: validateChecks(draft.checks)
                    if (invalid != null) {
                        Outcome.Failure(DomainError.InvalidAlarm(invalid))
                    } else {
                        // A new enabled alarm identical to a stored one switches that one on instead (owner decision
                        // 2026-10-05). A new alarm saved off is stored as usual: it must not switch anything on.
                        if (draft.enabled) {
                            when (val same = identicalStored(template, draft.checks)) {
                                is Outcome.Failure -> {
                                    return@whenIdle same
                                }

                                is Outcome.Success -> {
                                    same.value?.let {
                                        return@whenIdle switchOn(it, now).map { on ->
                                            AlarmSaved(on, null)
                                        }
                                    }
                                }
                            }
                        }
                        requestCodes
                            .next()
                            .flatMap { code ->
                                storeWithChecks(
                                    checkConfigRepository,
                                    template.copy(requestCode = code, updatedAt = now),
                                    draft.checks,
                                    now,
                                )
                            }.onSuccess(scheduling::sync)
                            .map { AlarmSaved(it, null) }
                    }
                } else {
                    // The edit arms the stored row itself, before its pending writes (storeEdit).
                    repository.get(id).flatMap { stored -> storeEdit(stored, draft, now) }
                }
            }
        }

    /**
     * Why no alarm may be saved yet: [DomainError.DisclosureRequired] before "I understand", or the read failure when the
     * onboarding progress cannot be read (fail closed); null once the disclosure is accepted.
     */
    private suspend fun consentMissing(): DomainError? =
        when (val progress = onboarding.get()) {
            is Outcome.Failure -> progress.error
            is Outcome.Success -> DomainError.DisclosureRequired.takeUnless { progress.value.consented }
        }

    /**
     * Stores the edit [draft] of [stored] through the commitment lock: the pending changes of fields applied now are
     * removed, then the row and its checks are stored (the locked fields at their decided live values), then the pending
     * changes of deferred fields are put.
     */
    private suspend fun storeEdit(
        stored: Alarm,
        draft: AlarmDraft,
        now: Instant,
    ): Outcome<AlarmSaved, DomainError> {
        val edited = draft.toAlarm(stored.id, stored.requestCode, stored.createdAt).copy(updatedAt = now)
        (validate(edited) ?: validateChecks(draft.checks))?.let { return Outcome.Failure(DomainError.InvalidAlarm(it)) }
        return checkConfigRepository.forAlarm(stored.id).flatMap { rows ->
            pendingRepository.forAlarm(stored.id).flatMap { own ->
                val zone = timeZoneProvider.current()
                val window = LockWindow.latestOf(listOfNotNull(LockWindow.of(stored, now, zone), LockWindow.of(edited, now, zone)))
                val mine = own.associateBy { it.field }
                val liveEntries = rows.orderedEntries().ifEmpty { CheckConfig.DEFAULT_ENTRIES }
                val grace =
                    CommitmentRules.decide(
                        stored.id,
                        SettingValue.GraceSeconds(stored.graceSeconds),
                        mine[LockedField.GraceSeconds],
                        SettingValue.GraceSeconds(edited.graceSeconds),
                        window,
                        now,
                    )
                val checks =
                    CommitmentRules.decide(
                        stored.id,
                        SettingValue.Checks(CheckPlan(stored.checkMode, liveEntries)),
                        mine[LockedField.Checks],
                        SettingValue.Checks(CheckPlan(edited.checkMode, draft.checks)),
                        window,
                        now,
                    )
                // A code registered in this edit is never held back (a new sticker is not a weaker plan).
                val plan = (checks.live as SettingValue.Checks).plan.withCodesFrom(draft.checks)
                val row = edited.copy(graceSeconds = (grace.live as SettingValue.GraceSeconds).seconds, checkMode = plan.mode)
                // Review fix 3: a change applied now drops its pending change before the row is written, so a failure between
                // never leaves a weaker pending value to undo it later; a deferred change is put after the row. The row is
                // armed as soon as it is stored: a failed pending write must never leave the alarm armed at its old time.
                // Review fix 2 (Story 4.6): a global change waiting for this alarm's ring keeps waiting when the ring moves
                // later. Moved first: a failure after it only makes the change wait longer.
                moveGlobalWaits(stored.id, edited, now, zone)
                    .flatMap { clearApplied(grace, mine) }
                    .flatMap { clearApplied(checks, mine) }
                    .flatMap { storeWithChecks(checkConfigRepository, row, plan.entries, now) }
                    .onSuccess(scheduling::sync)
                    .flatMap { alarm -> putDeferred(grace, mine).flatMap { putDeferred(checks, mine) }.map { alarm } }
                    .map { alarm -> AlarmSaved(alarm, LockWindow.latestOf(listOf(grace, checks).mapNotNull { it.pendingUntil() })) }
            }
        }
    }

    /**
     * Story 4.6 review fix 2 (default taken, owner can change): when the alarm [alarmId] now rings later than a ring a
     * global pending change (a lower base fee, more snoozes) still waits for, that change moves to the new ring, so
     * moving the alarm never hands the weaker setting to the very morning it was saved against. A ring moved earlier
     * needs nothing (the change still waits for the later time), and an alarm switched off keeps it as it is.
     */
    private suspend fun moveGlobalWaits(
        alarmId: String,
        edited: Alarm,
        now: Instant,
        zone: TimeZone,
    ): Outcome<Unit, DomainError> {
        if (!edited.enabled) return Outcome.Success(Unit)
        val next = nextOccurrence(edited.toRule(), now, zone)
        return pendingRepository.all().flatMap { all ->
            all
                .filter { it.alarmId == null && it.effectiveAfter.alarmId == alarmId }
                .filter { it.effectiveAfter.scheduledAt > now && it.effectiveAfter.scheduledAt < next }
                .fold<PendingChange, Outcome<Unit, DomainError>>(Outcome.Success(Unit)) { done, change ->
                    done.flatMap { pendingRepository.put(change.copy(effectiveAfter = Occurrence(alarmId, next))) }
                }
        }
    }

    /** Removes the field's stored pending change (from [stored]) when [decision] applies now; nothing otherwise. */
    private suspend fun clearApplied(
        decision: LockDecision,
        stored: Map<LockedField, PendingChange>,
    ): Outcome<Unit, DomainError> {
        val before = stored[decision.live.field]
        return if (decision is LockDecision.ApplyNow && before != null) {
            pendingRepository.remove(before.alarmId, before.field)
        } else {
            Outcome.Success(Unit)
        }
    }

    /** Puts [decision]'s pending change when it defers and differs from the stored one (from [stored]); nothing otherwise. */
    private suspend fun putDeferred(
        decision: LockDecision,
        stored: Map<LockedField, PendingChange>,
    ): Outcome<Unit, DomainError> =
        if (decision is LockDecision.Defer && stored[decision.live.field] != decision.pending) {
            pendingRepository.put(decision.pending)
        } else {
            Outcome.Success(Unit)
        }

    private fun LockDecision.pendingUntil(): Occurrence? = (this as? LockDecision.Defer)?.pending?.effectiveAfter

    /**
     * The stored alarm that rings exactly like [alarm] ([hasSameSettingsAs]) with the same [checks] in the same order, or
     * null; a failed read is returned.
     */
    private suspend fun identicalStored(
        alarm: Alarm,
        checks: List<CheckEntry>,
    ): Outcome<Alarm?, DomainError> =
        repository.listAll().flatMap { all ->
            var found: Outcome<Alarm?, DomainError> = Outcome.Success(null)
            for (candidate in all.filter { it.hasSameSettingsAs(alarm) }) {
                // An alarm stored without rows has the default checks (the editor opens it with them).
                found =
                    checkConfigRepository.forAlarm(candidate.id).map { rows ->
                        candidate.takeIf { rows.orderedEntries().ifEmpty { CheckConfig.DEFAULT_ENTRIES } == checks }
                    }
                // A failed read ends the search, and so does a match.
                if (found !is Outcome.Success || found.value != null) break
            }
            found
        }

    /** Switches [alarm] on (`updatedAt = now`) and arms its next occurrence after now (for a one-time alarm, its next date). */
    private suspend fun switchOn(
        alarm: Alarm,
        now: Instant,
    ): Outcome<Alarm, DomainError> {
        val on = alarm.copy(enabled = true, updatedAt = now)
        return repository.upsert(on).map { on }.onSuccess(scheduling::sync)
    }

    private fun AlarmDraft.toAlarm(
        id: String,
        requestCode: Int,
        createdAt: Instant,
    ) = Alarm(
        id = id,
        time = LocalTime(time.hour, time.minute),
        repeatDays = repeatDays,
        label = label?.trim()?.ifEmpty { null },
        enabled = enabled,
        soundRef = soundRef,
        volumePercent = volumePercent,
        gradualVolume = gradualVolume,
        rampStartPercent = rampStartPercent,
        vibration = vibration,
        snoozeLengthMinutes = snoozeLengthMinutes,
        graceSeconds = graceSeconds,
        vibrateInGrace = vibrateInGrace,
        requestCode = requestCode,
        createdAt = createdAt,
        updatedAt = createdAt,
        checkMode = checkMode,
    )
}

/**
 * Turns an alarm on or off; `updatedAt = now`. `NotFound(id)` when there is no such alarm. Other fields are not
 * re-validated, so a stored alarm with an out-of-range value can still be switched off. Once stored, the alarm is
 * armed (on) or cancelled (off). Turning an enabled alarm off inside its lock window also writes a
 * `CommitmentEvent(Disabled)` for the occurrence it gave up ([CommitmentLog], Story 4.6), in the same guarded write. The
 * alarm change comes first: a failed write leaves the alarm on and armed, with no event; a failed event write is logged
 * and the alarm stays off (the user asked for that, and it is stored). The guarded write runs to its end even when the
 * caller is cancelled meanwhile (Home's scope cleared right after "Turn off"): a stored change always gets its system
 * alarm and its event.
 */
class SetAlarmEnabled(
    private val repository: AlarmRepository,
    private val clock: Clock,
    private val lock: AlarmWriteLock,
    private val scheduling: AlarmScheduling,
    private val sessionLock: SessionLockGuard,
    private val commitments: CommitmentLog,
    private val logger: Logger,
) {
    suspend operator fun invoke(
        id: String,
        enabled: Boolean,
    ): Outcome<Alarm, DomainError> =
        lock.withLock {
            sessionLock.whenIdle {
                withContext(NonCancellable) {
                    repository.get(id).flatMap { stored ->
                        val now = clock.nowMillis()
                        val changed = stored.copy(enabled = enabled, updatedAt = now)
                        repository
                            .upsert(changed)
                            .map { changed }
                            .onSuccess(scheduling::sync)
                            .onSuccess { if (!enabled) recordCommitment(commitments, logger, stored, CommitmentAction.Disabled, now) }
                    }
                }
            }
        }
}

/**
 * Removes an alarm with its checks in one transaction ([CheckConfigRepository.deleteWithAlarm]) and then cancels its
 * system alarm. `NotFound(id)` when there is no such alarm, and nothing changes; a failed delete changes and cancels
 * nothing, so the alarm stays stored with its checks and armed. A deletion is logged as `AlarmDeleted(id, now)` (the
 * dialog says "This is logged."), and inside the alarm's lock window a `CommitmentEvent(Deleted)` is written too
 * ([CommitmentLog], Story 4.6), both here so no caller can skip them. A failed event write is logged; the alarm stays
 * deleted. Like [SetAlarmEnabled], the guarded write runs to its end even when the caller is cancelled meanwhile.
 */
class DeleteAlarm(
    private val repository: AlarmRepository,
    private val lock: AlarmWriteLock,
    private val scheduling: AlarmScheduling,
    private val sessionLock: SessionLockGuard,
    private val checkConfigRepository: CheckConfigRepository,
    private val clock: Clock,
    private val commitments: CommitmentLog,
    private val logger: Logger,
) {
    suspend operator fun invoke(id: String): Outcome<Unit, DomainError> =
        lock.withLock {
            sessionLock.whenIdle {
                withContext(NonCancellable) {
                    repository.get(id).flatMap { stored ->
                        checkConfigRepository.deleteWithAlarm(id).onSuccess {
                            scheduling.cancel(stored.requestCode)
                            val now = clock.nowMillis()
                            logger.log(LogEvent.AlarmDeleted(id, now))
                            recordCommitment(commitments, logger, stored, CommitmentAction.Deleted, now)
                        }
                    }
                }
            }
        }
}

/**
 * Copies an alarm with its checks (in the same order, with new ids), a new id, a new request code and fresh timestamps;
 * the copy is then armed when it is enabled. `NotFound(id)` when there is no such alarm. Not used by the app since
 * 2026-10-05: Duplicate opens the editor on a new, unsaved alarm prefilled from the stored one, which [SaveAlarm] stores
 * on Save (owner decision).
 */
class DuplicateAlarm(
    private val repository: AlarmRepository,
    private val idGenerator: IdGenerator,
    private val clock: Clock,
    private val lock: AlarmWriteLock,
    private val requestCodes: RequestCodeSequence,
    private val scheduling: AlarmScheduling,
    private val sessionLock: SessionLockGuard,
    private val checkConfigRepository: CheckConfigRepository,
) {
    suspend operator fun invoke(id: String): Outcome<Alarm, DomainError> =
        lock.withLock {
            sessionLock.whenIdle {
                repository
                    .get(id)
                    .flatMap { stored ->
                        checkConfigRepository.forAlarm(id).flatMap { configs ->
                            requestCodes.next().flatMap { code ->
                                val now = clock.nowMillis()
                                val copy = stored.copy(id = idGenerator.newId(), requestCode = code, createdAt = now, updatedAt = now)
                                // An alarm stored without rows rings the default checks, so its copy gets them.
                                val checks = configs.orderedEntries().ifEmpty { CheckConfig.DEFAULT_ENTRIES }
                                storeWithChecks(checkConfigRepository, copy, checks, now)
                            }
                        }
                    }.onSuccess(scheduling::sync)
            }
        }
}

/**
 * Registers [code] again for the QR/Barcode check of the alarm `alarmId` (Home's "Re-register", Stories 3.10 and 3.13):
 * only that check's code and registration time change (`codeRegisteredAt = now`, also for the same code, since
 * re-scanning the same sticker is the usual case and must restart the count of fallbacks). The alarm itself (enabled or
 * not, its time, its other checks) is stored as it is, and nothing is re-armed: arming does not depend on the code.
 * `NotFound(alarmId)` without such an alarm; `InvalidAlarm(CheckCode)` when it has no QR/Barcode check.
 */
class ReRegisterCode(
    private val repository: AlarmRepository,
    private val clock: Clock,
    private val lock: AlarmWriteLock,
    private val sessionLock: SessionLockGuard,
    private val checkConfigRepository: CheckConfigRepository,
) {
    suspend operator fun invoke(
        alarmId: String,
        code: RegisteredCode,
    ): Outcome<Unit, DomainError> =
        lock.withLock {
            sessionLock.whenIdle {
                repository.get(alarmId).flatMap { alarm ->
                    checkConfigRepository.forAlarm(alarmId).flatMap { configs ->
                        if (configs.none { it.entry.type == CheckType.QrBarcode }) {
                            Outcome.Failure(DomainError.InvalidAlarm(AlarmField.CheckCode))
                        } else {
                            val now = clock.nowMillis()
                            val updated =
                                configs.map { config ->
                                    if (config.entry.type != CheckType.QrBarcode) {
                                        config
                                    } else {
                                        config.copy(entry = config.entry.copy(code = code), updatedAt = now, codeRegisteredAt = now)
                                    }
                                }
                            checkConfigRepository.saveWithAlarm(alarm, updated)
                        }
                    }
                }
            }
        }
}

/**
 * Writes the commitment event of [action] on [alarm] (as it was before the change) when it was inside its lock window
 * ([CommitmentLog.eventFor]); called inside the caller's `whenIdle`, after the alarm change succeeded. A failed write is
 * logged: the alarm change it records already happened.
 */
private suspend fun recordCommitment(
    commitments: CommitmentLog,
    logger: Logger,
    alarm: Alarm,
    action: CommitmentAction,
    now: Instant,
) {
    val event = commitments.eventFor(alarm, action, now) ?: return
    val written = commitments.repository.insert(event)
    if (written is Outcome.Failure) logger.log(LogEvent.OperationFailed.of("record commitment event", written.error))
}

/**
 * Validates [alarm] and its [checks], then stores both in one transaction. The rows take the order of [checks]; a type
 * the alarm already had keeps its creation time, and a code saved again unchanged keeps its registration time (Story
 * 3.10), so an ordinary save never restarts Story 3.13's count of fallbacks ([ReRegisterCode] does).
 */
private suspend fun storeWithChecks(
    checkConfigRepository: CheckConfigRepository,
    alarm: Alarm,
    checks: List<CheckEntry>,
    now: Instant,
): Outcome<Alarm, DomainError> {
    (validate(alarm) ?: validateChecks(checks))?.let { return Outcome.Failure(DomainError.InvalidAlarm(it)) }
    return checkConfigRepository.forAlarm(alarm.id).flatMap { stored ->
        checkConfigRepository.saveWithAlarm(alarm, checkRowsFor(alarm.id, checks, stored, now)).map { alarm }
    }
}

/**
 * The `check_config` rows of [checks] for [alarmId], replacing [stored]: in the order of [checks]; a type the alarm
 * already had keeps its creation time, and a code saved again unchanged keeps its registration time (Story 3.10).
 */
internal fun checkRowsFor(
    alarmId: String,
    checks: List<CheckEntry>,
    stored: List<CheckConfig>,
    now: Instant,
): List<CheckConfig> {
    val previous = stored.associateBy { it.entry.type }
    return checks.mapIndexed { position, entry ->
        val before = previous[entry.type]
        CheckConfig(
            id = CheckConfig.idFor(alarmId, entry.type),
            alarmId = alarmId,
            position = position,
            entry = entry,
            createdAt = before?.createdAt ?: now,
            updatedAt = now,
            codeRegisteredAt = entry.code?.let { code -> before?.codeRegisteredAt?.takeIf { before.entry.code == code } ?: now },
        )
    }
}

/** Runs [action] on the success value and returns this outcome unchanged. */
private inline fun <T> Outcome<T, DomainError>.onSuccess(action: (T) -> Unit): Outcome<T, DomainError> {
    if (this is Outcome.Success) action(value)
    return this
}

/** Now, truncated to whole milliseconds: the precision `app.db` stores, so a saved alarm equals the stored one. */
internal fun Clock.nowMillis(): Instant = Instant.fromEpochMilliseconds(now().toEpochMilliseconds())
