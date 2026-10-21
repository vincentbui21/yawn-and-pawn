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
import com.yawnandpawn.app.android.telemetry.AnalyticsSink
import com.yawnandpawn.app.android.telemetry.RecordingAnalyticsSink
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
        var unsentDeletes = 0

        /** The unsent-report calls in order: "collection off", "delete", "collection on". */
        val unsentCalls = mutableListOf<String>()

        override fun recordException(throwable: Throwable) {
            recorded += throwable
        }

        override fun deleteUnsentReports() {
            unsentDeletes++
            unsentCalls += "delete"
        }

        override fun setCollectionEnabled(enabled: Boolean) {
            unsentCalls += if (enabled) "collection on" else "collection off"
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

                override fun deleteUnsentReports() = throw IllegalStateException("Crashlytics not initialized")
            }
        val missing =
            object : CrashSink {
                override val isReady: Boolean
                    get() = throw NoClassDefFoundError("com/google/firebase/FirebaseApp")

                override fun recordException(throwable: Throwable) = Unit

                override fun deleteUnsentReports() = Unit
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
    fun `Delete all data drops the unsent reports once Crashlytics has started, and never throws (Story 5_9)`() {
        val sink = RecordingSink(isReady = false)
        val unsent = CrashlyticsUnsentReports(sink, logger)

        unsent.deleteUnsent()
        assertEquals(0, sink.unsentDeletes, "nothing to drop before Crashlytics started")

        sink.isReady = true
        unsent.deleteUnsent()
        assertEquals(1, sink.unsentDeletes)
        // Collection is off around the delete and back on after it (review item 6).
        assertEquals(listOf("collection off", "delete", "collection on"), sink.unsentCalls)

        val broken =
            object : CrashSink {
                override val isReady = true

                override fun recordException(throwable: Throwable) = Unit

                override fun deleteUnsentReports() = throw IllegalStateException("Crashlytics not initialized")
            }
        val missing =
            object : CrashSink {
                override val isReady: Boolean
                    get() = throw NoClassDefFoundError("com/google/firebase/FirebaseApp")

                override fun recordException(throwable: Throwable) = Unit

                override fun deleteUnsentReports() = Unit
            }
        CrashlyticsUnsentReports(broken, logger).deleteUnsent()
        CrashlyticsUnsentReports(missing, logger).deleteUnsent()

        assertEquals(
            listOf<LogEvent>(
                LogEvent.OperationFailed("delete unsent crash reports", "IllegalStateException"),
                LogEvent.OperationFailed("delete unsent crash reports", "NoClassDefFoundError"),
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
    fun `ready turns true only once Firebase has started, after the unlock (Story 5-6)`() {
        val unconfigured = FirebaseStartup(context, logger, configured = { false }, unlocked = { true }, initialize = {})
        unconfigured.start()
        assertFalse(unconfigured.ready.value)

        val failing = FirebaseStartup(context, logger, configured = { true }, unlocked = { true }, initialize = { error("no Firebase") })
        assertFailsWith<IllegalStateException> { failing.start() }
        assertFalse(failing.ready.value, "not ready when initializing failed")

        val locked = FirebaseStartup(context, logger, configured = { true }, unlocked = { false }, initialize = {})
        locked.start()
        assertFalse(locked.ready.value, "not before the first unlock")

        context.sendBroadcast(Intent(Intent.ACTION_USER_UNLOCKED))
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(locked.ready.value)
    }

    @Test
    fun `the start turns Analytics collection off right after initializing, before ready turns true (Story 5-6 fix 4)`() {
        val order = mutableListOf<String>()
        lateinit var startup: FirebaseStartup
        val sink =
            object : AnalyticsSink by RecordingAnalyticsSink() {
                override fun setCollectionEnabled(enabled: Boolean) {
                    order += "collection $enabled, ready ${startup.ready.value}"
                }
            }
        startup =
            FirebaseStartup(context, logger, analytics = sink, configured = { true }, unlocked = { true }, initialize = { order += "init" })

        startup.start()

        assertEquals(listOf("init", "collection false, ready false"), order)
        assertTrue(startup.ready.value)
        assertTrue(startup.startedCollectionOff)
        assertTrue(logger.events.isEmpty(), "${logger.events}")
    }

    @Test
    fun `a failing collection off is logged, Firebase still starts, and the consent gate is told to turn it off itself`() {
        val failing = RecordingAnalyticsSink().apply { failure = IllegalStateException("not initialized") }
        val startup = FirebaseStartup(context, logger, analytics = failing, configured = { true }, unlocked = { true }, initialize = {})

        startup.start()

        assertTrue(startup.ready.value)
        assertFalse(startup.startedCollectionOff)
        assertEquals(listOf<LogEvent>(LogEvent.OperationFailed("turn usage stats collection off", "IllegalStateException")), logger.events)

        val notReady = RecordingAnalyticsSink(isReady = false)
        val unready = FirebaseStartup(context, logger, analytics = notReady, configured = { true }, unlocked = { true }, initialize = {})
        unready.start()
        assertEquals(emptyList(), notReady.calls)
        assertFalse(unready.startedCollectionOff)
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
