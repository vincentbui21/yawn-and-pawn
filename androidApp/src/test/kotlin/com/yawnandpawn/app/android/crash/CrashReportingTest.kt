package com.yawnandpawn.app.android.crash

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.android.wake.NoOpCrashReporter
import com.yawnandpawn.app.core.crash.CrashReporter
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.debug.DebugCrashReceiver
import com.yawnandpawn.app.debug.DebugHooksProvider
import com.yawnandpawn.app.debug.DebugTestCrash
import com.yawnandpawn.app.testing.FakeLogger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.FileNotFoundException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Story 1.19: Crashlytics behind the CrashReporter port, its start after unlock, the manifest and the debug hook. */
@RunWith(RobolectricTestRunner::class)
class CrashReportingTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val logger = FakeLogger()

    private class RecordingSink(
        override var isReady: Boolean = true,
    ) : CrashSink {
        val recorded = mutableListOf<Throwable>()

        override fun recordException(throwable: Throwable) {
            recorded += throwable
        }
    }

    private fun Throwable.chain(): List<Throwable> = generateSequence(this) { it.cause }.toList()

    @Test
    fun `a report keeps class names and stack traces but never a label, a file path or a purchase token`() {
        val sink = RecordingSink()
        val cause = FileNotFoundException("/storage/emulated/0/Music/morning-mix.mp3: open failed")
        val crash = IllegalStateException("alarm 'Gym with Anna' token=abcd-purchase-1234", cause)

        FirebaseCrashReporter(sink, logger).report(crash)

        val sent = sink.recorded.single()
        val text = sent.chain().joinToString { "${it.message} ${it.localizedMessage} $it" }
        listOf("Gym", "Anna", "/storage", "morning-mix", "token", "abcd").forEach { assertFalse(it in text, "'$it' in $text") }
        assertEquals(listOf("java.lang.IllegalStateException", "java.io.FileNotFoundException"), sent.chain().map { it.message })
        assertTrue(sent.stackTrace.contentEquals(crash.stackTrace))
        assertTrue(sent.cause!!.stackTrace.contentEquals(cause.stackTrace))
        assertEquals(emptyList(), logger.events, "nothing else is logged")
    }

    @Test
    fun `a cyclic or very long cause chain is cut`() {
        var deep: Throwable = RuntimeException("root")
        repeat(20) { deep = RuntimeException("level $it", deep) }

        assertTrue(FirebaseCrashReporter.sanitized(deep).chain().size <= 9)
    }

    @Test
    fun `before Crashlytics starts a report only logs the exception type`() {
        val sink = RecordingSink(isReady = false)

        FirebaseCrashReporter(sink, logger).report(IllegalStateException("secret"))

        assertTrue(sink.recorded.isEmpty())
        assertEquals(
            listOf<LogEvent>(LogEvent.OperationFailed("report crash", "Crashlytics not started yet: IllegalStateException")),
            logger.events,
        )
    }

    @Test
    fun `without a Firebase configuration nothing starts`() {
        var started = 0
        val startup = FirebaseStartup(context, logger, configured = { false }, unlocked = { true }, initialize = { started++ })

        startup.start()

        assertEquals(0, started)
        assertFalse(startup.started)
    }

    @Test
    fun `start is idempotent - a missing configuration is logged once and a locked start waits with one receiver (Story 2_4)`() {
        val unconfigured = FirebaseStartup(context, logger, configured = { false }, unlocked = { true }, initialize = {})
        unconfigured.start()
        unconfigured.start()
        assertEquals(1, logger.events.size, "${logger.events}")

        var started = 0
        val locked = FirebaseStartup(context, logger, configured = { true }, unlocked = { false }, initialize = { started++ })
        val unlockReceivers = {
            shadowOf(context as Application).registeredReceivers.count { it.intentFilter.hasAction(Intent.ACTION_USER_UNLOCKED) }
        }
        val before = unlockReceivers()
        locked.start()
        locked.start()
        assertEquals(before + 1, unlockReceivers())

        context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()
        locked.start()
        assertEquals(1, started)
    }

    @Test
    fun `an unlock between the first check and the registration still starts Firebase, once`() {
        var started = 0
        var checks = 0
        // Locked at the first check, unlocked by the time the receiver is registered.
        val startup = FirebaseStartup(context, logger, configured = { true }, unlocked = { checks++ > 0 }, initialize = { started++ })

        startup.start()
        context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, started)
        assertTrue(startup.started)
    }

    @Test
    fun `a report never throws when Crashlytics fails, so the wake fallback still runs`() {
        val broken =
            object : CrashSink {
                override val isReady = true

                override fun recordException(throwable: Throwable) = throw IllegalStateException("Crashlytics not initialized")
            }
        val missing =
            object : CrashSink {
                override val isReady: Boolean
                    get() = throw NoClassDefFoundError("com/google/firebase/FirebaseApp")

                override fun recordException(throwable: Throwable) = Unit
            }

        FirebaseCrashReporter(broken, logger).report(RuntimeException("boom"))
        FirebaseCrashReporter(missing, logger).report(RuntimeException("boom"))

        assertEquals(
            listOf<LogEvent>(
                LogEvent.OperationFailed("report crash", "IllegalStateException"),
                LogEvent.OperationFailed("report crash", "NoClassDefFoundError"),
            ),
            logger.events,
        )
    }

    @Test
    fun `an unlocked user starts Firebase at once, a locked one at ACTION_USER_UNLOCKED`() {
        var started = 0
        FirebaseStartup(context, logger, configured = { true }, unlocked = { true }, initialize = { started++ }).start()
        assertEquals(1, started)

        val locked = FirebaseStartup(context, logger, configured = { true }, unlocked = { false }, initialize = { started++ })
        locked.start()
        assertEquals(1, started, "not before the first unlock")
        assertFalse(locked.started)

        context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()
        context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(2, started, "once, after the unlock")
        assertTrue(locked.started)
    }

    @Test
    fun `the merged manifest has no FirebaseInitProvider and turns Analytics collection off`() {
        val packages = context.packageManager

        @Suppress("DEPRECATION")
        val providers = packages.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertTrue(providers.none { it.name == "com.google.firebase.provider.FirebaseInitProvider" }, providers.map { it.name }.toString())

        @Suppress("DEPRECATION")
        val info: ApplicationInfo = packages.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertFalse(info.metaData.getBoolean("firebase_analytics_collection_enabled", true))
        assertFalse(info.metaData.getBoolean("google_analytics_adid_collection_enabled", true))
    }

    @Test
    fun `the app binds Crashlytics only with a Firebase configuration, else the no-op reporter`() {
        val reporter = GlobalContext.get().get<CrashReporter>()
        if (isFirebaseConfigured(context)) assertIs<FirebaseCrashReporter>(reporter) else assertIs<NoOpCrashReporter>(reporter)
        assertFalse(FirebaseApp.getApps(context).isNotEmpty(), "tests never initialise a real FirebaseApp")
    }

    @Test
    fun `the merged debug manifest declares the debug hooks provider`() {
        @Suppress("DEPRECATION")
        val providers =
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS)
                .providers
                .orEmpty()

        assertTrue(providers.any { it.name == "com.yawnandpawn.app.debug.DebugHooksProvider" }, providers.map { it.name }.toString())
    }

    @Test
    fun `the debug crash broadcast throws the test crash`() {
        Robolectric.setupContentProvider(DebugHooksProvider::class.java, "${context.packageName}.debughooks.test")

        assertFailsWith<DebugTestCrash> {
            context.sendBroadcast(Intent(DebugCrashReceiver.ACTION_CRASH))
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
