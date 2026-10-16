package com.yawnandpawn.app.core.config

import com.yawnandpawn.app.core.error.DomainError
import com.yawnandpawn.app.core.error.Outcome
import com.yawnandpawn.app.core.session.GlobalSettings
import com.yawnandpawn.app.core.session.SessionLockGuard
import com.yawnandpawn.app.core.session.SessionState
import com.yawnandpawn.app.core.session.ThemeMode
import com.yawnandpawn.app.core.session.snoozedSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Story 5.1: the new-alarm defaults and the theme, saved at once with no commitment lock, inside the session lock. */
class DefaultSettingsUseCasesTest {
    private val settings = InMemoryGlobalSettings(GlobalSettings(baseFeeTier = 3, maxSnoozes = 2))
    private val sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    private val restored = MutableStateFlow(true)
    private val sessionLock = SessionLockGuard(sessionState, restored, MutableStateFlow(false))
    private val save = SaveDefaultSetting(settings, sessionLock)
    private val setGrace = SetDefaultGrace(save)
    private val setVibrate = SetDefaultVibrateInGrace(save)
    private val setSnooze = SetDefaultSnoozeLength(save)
    private val setTheme = SetThemeMode(save)

    @Test
    fun `each default is stored at once, and the fee settings stay as they are`() =
        runTest {
            assertEquals(Outcome.Success(Unit), setGrace(25))
            assertEquals(Outcome.Success(Unit), setVibrate(false))
            assertEquals(Outcome.Success(Unit), setSnooze(10))
            assertEquals(Outcome.Success(Unit), setTheme(ThemeMode.Dark))

            assertEquals(
                GlobalSettings(
                    baseFeeTier = 3,
                    maxSnoozes = 2,
                    defaultGraceSeconds = 25,
                    defaultSnoozeLengthMinutes = 10,
                    defaultVibrateInGrace = false,
                    themeMode = ThemeMode.Dark,
                ),
                settings.settings.value,
            )
            assertEquals(4, settings.writes)
        }

    @Test
    fun `the ends of the ranges are valid`() =
        runTest {
            assertEquals(Outcome.Success(Unit), setGrace(15))
            assertEquals(Outcome.Success(Unit), setGrace(30))
            listOf(5, 9, 10, 15).forEach { assertEquals(Outcome.Success(Unit), setSnooze(it)) }
            assertEquals(30, settings.settings.value.defaultGraceSeconds)
            assertEquals(15, settings.settings.value.defaultSnoozeLengthMinutes)
        }

    @Test
    fun `a value outside its range is InvalidSetting and nothing is written`() =
        runTest {
            assertEquals(Outcome.Failure(DomainError.InvalidSetting(SettingField.DefaultGrace)), setGrace(14))
            assertEquals(Outcome.Failure(DomainError.InvalidSetting(SettingField.DefaultGrace)), setGrace(31))
            assertEquals(Outcome.Failure(DomainError.InvalidSetting(SettingField.DefaultSnoozeLength)), setSnooze(7))
            assertEquals(Outcome.Failure(DomainError.InvalidSetting(SettingField.DefaultSnoozeLength)), setSnooze(0))

            assertEquals(0, settings.writes)
            assertEquals(GlobalSettings(baseFeeTier = 3, maxSnoozes = 2), settings.settings.value)
        }

    @Test
    fun `during a session, or before the session is restored, every set is SessionActive and writes nothing`() =
        runTest {
            sessionState.value = SessionState.Snoozed(snoozedSession())
            assertEquals(Outcome.Failure(DomainError.SessionActive), setGrace(25))
            assertEquals(Outcome.Failure(DomainError.SessionActive), setVibrate(false))
            assertEquals(Outcome.Failure(DomainError.SessionActive), setSnooze(10))
            assertEquals(Outcome.Failure(DomainError.SessionActive), setTheme(ThemeMode.Light))

            sessionState.value = SessionState.Idle
            restored.value = false
            assertEquals(Outcome.Failure(DomainError.SessionActive), setTheme(ThemeMode.Light))

            assertEquals(0, settings.writes)
        }

    @Test
    fun `a failed write is passed on`() =
        runTest {
            settings.writeFailure = DomainError.StorageFailure("disk full")

            assertEquals(Outcome.Failure(DomainError.StorageFailure("disk full")), setVibrate(false))
            assertEquals(true, settings.settings.value.defaultVibrateInGrace)
        }

    @Test
    fun `with sets one field, valid or not`() {
        val base = GlobalSettings()
        assertEquals(base.copy(defaultGraceSeconds = 99), base.with(DefaultSetting.GraceSeconds(99)))
        assertEquals(base.copy(defaultVibrateInGrace = false), base.with(DefaultSetting.VibrateInGrace(false)))
        assertEquals(base.copy(defaultSnoozeLengthMinutes = 15), base.with(DefaultSetting.SnoozeLength(15)))
        assertEquals(base.copy(themeMode = ThemeMode.Light), base.with(DefaultSetting.Theme(ThemeMode.Light)))
    }

    @Test
    fun `the theme names are stored, so they are pinned, and an unknown one parses as none`() {
        assertEquals(listOf("System", "Light", "Dark"), ThemeMode.entries.map { it.name })
        assertEquals(ThemeMode.Dark, ThemeMode.parse("Dark"))
        assertNull(ThemeMode.parse("Purple"))
        assertNull(ThemeMode.parse(null))
    }

    @Test
    fun `the last-known snapshot keeps the defaults and the theme`() {
        val snapshot =
            SettingsSnapshot(
                GlobalSettings(
                    baseFeeTier = 2,
                    defaultGraceSeconds = 30,
                    defaultSnoozeLengthMinutes = 15,
                    defaultVibrateInGrace = false,
                    themeMode = ThemeMode.Dark,
                ),
                listOf(PendingChange(null, SettingValue.MaxSnoozes(4), Occurrence("a", Instant.parse("2027-03-09T07:30:00Z")))),
            )

        assertEquals(snapshot, PendingChangeJson.decodeSnapshot(PendingChangeJson.encodeSnapshot(snapshot)))
    }

    @Test
    fun `a snapshot from an older build reads its defaults as the defaults, and garbage reads as the defaults too`() {
        assertEquals(
            SettingsSnapshot(GlobalSettings(baseFeeTier = 4, maxSnoozes = 2), emptyList()),
            PendingChangeJson.decodeSnapshot("""{"baseFeeTier":4,"maxSnoozes":2,"pending":[]}"""),
        )
        val garbage =
            """{"baseFeeTier":4,"maxSnoozes":2,"pending":[],"defaultGraceSeconds":99,"defaultSnoozeLengthMinutes":7,""" +
                """"defaultVibrateInGrace":false,"themeMode":"Purple"}"""
        assertEquals(
            SettingsSnapshot(GlobalSettings(baseFeeTier = 4, maxSnoozes = 2, defaultVibrateInGrace = false), emptyList()),
            PendingChangeJson.decodeSnapshot(garbage),
        )
    }
}
