package com.yawnandpawn.app.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoDirectTimeAccessTest {
    private val rule = NoDirectTimeAccess(Config.empty)

    private fun findings(
        code: String,
        packageName: String = "com.yawnandpawn.app.core.alarm",
    ) = rule.lint("package $packageName\n\n$code\n")

    /** Lints a real file at `<tmp>/<relativeDir>/Snippet.kt`, so the rule sees its path. */
    private fun findingsInFile(
        code: String,
        relativeDir: String,
        packageName: String,
    ): Int {
        val file = createTempDirectory("no-direct-time").resolve(relativeDir).resolve("Snippet.kt")
        file.parent.createDirectories()
        file.writeText("package $packageName\n\n$code\n")
        return rule.lint(compileForTest(file)).size
    }

    @Test
    fun `Clock_System in core is reported pointing to the ports`() {
        val findings = findings("fun now() = Clock.System.now()")

        assertEquals(1, findings.size)
        assertTrue(findings.single().message.contains("Clock.System"))
        assertTrue(findings.single().message.contains("TimeZoneProvider port"))
    }

    @Test
    fun `every banned form is reported once outside the adapter package`() {
        val violations =
            listOf(
                "fun a() = Clock.System.now()",
                "fun a() = kotlin.time.Clock.System.now()",
                "val clock = Clock.System",
                "fun a() = System.currentTimeMillis()",
                "fun a() = java.lang.System.currentTimeMillis()",
                "fun a() = System.nanoTime()",
                "fun a() = SystemClock.elapsedRealtime()",
                "fun a() = android.os.SystemClock.uptimeMillis()",
                "val ref = SystemClock::elapsedRealtime",
                "fun a() = TimeZone.currentSystemDefault()",
                "fun a() = kotlinx.datetime.TimeZone.currentSystemDefault()",
                "fun a() = TimeZone.Companion.currentSystemDefault()",
                "fun a() = Instant.now()",
                "fun a() = java.time.LocalDateTime.now()",
                "fun a() = ZonedDateTime.now()",
                "fun a() = LocalDate.now()",
                "fun a() = LocalTime.now()",
                "fun a() = java.time.Clock.systemUTC()",
                "fun a() = Clock.systemDefaultZone()",
                "fun a() = Date()",
                "fun a() = java.util.Date()",
                "fun a() = Calendar.getInstance()",
                "fun a() = ZoneId.systemDefault()",
                "fun a() = java.util.TimeZone.getDefault()",
                "import kotlin.time.Clock.System\nfun a() = 1",
                "import java.lang.System.currentTimeMillis\nfun a() = 1",
                "import kotlinx.datetime.TimeZone.Companion.currentSystemDefault\nfun a() = 1",
                "import android.os.SystemClock.elapsedRealtime\nfun a() = 1",
                "import kotlin.time.Clock as C\nfun a() = 1",
                "import java.lang.System as S\nfun a() = 1",
                "import kotlinx.datetime.TimeZone as TZ\nfun a() = 1",
                "import android.os.SystemClock as SC\nfun a() = 1",
            )
        listOf("com.yawnandpawn.app.core.alarm", "com.yawnandpawn.app.ui.home", "com.yawnandpawn.app").forEach { pkg ->
            violations.forEach { code -> assertEquals(1, findings(code, pkg).size, "$code in $pkg") }
        }
    }

    @Test
    fun `ports, fakes and look-alike names are not reported`() {
        val compliant =
            listOf(
                "class Alarm(private val clock: Clock) { fun now() = clock.now() }",
                "fun a(zones: TimeZoneProvider) = zones.current()",
                "fun a() = System.getProperty(\"x\")",
                "fun a() = TimeZone.of(\"Europe/Berlin\")",
                "fun a(clock: FakeClock) = clock.system",
                "fun a() = System.nanoTimeIsNotListed",
                "fun a() = Instant.fromEpochMilliseconds(0)",
                "fun a() = LocalDate.parse(\"2027-01-01\")",
                "fun a() = Date(0L)",
                "fun a() = ZoneId.of(\"UTC\")",
                "fun a() = java.util.TimeZone.getTimeZone(\"UTC\")",
                "fun a() = Calendar.SUNDAY",
                "import android.os.SystemClock\nfun a() = 1",
                "import kotlin.time.Clock\nfun a() = 1",
                "import kotlin.time.Instant as KInstant\nfun a() = 1",
                "import com.example.MyClock.System\nfun a() = 1",
                "import com.example.MySystem.currentTimeMillis\nfun a() = 1",
            )
        compliant.forEach { code -> assertEquals(0, findings(code).size, code) }
    }

    private val adapterCode =
        """
        fun wall() = Clock.System.now()
        fun elapsed() = SystemClock.elapsedRealtime()
        fun zone() = TimeZone.currentSystemDefault()
        fun millis() = System.currentTimeMillis()
        """.trimIndent()

    @Test
    fun `the adapter package in androidApp sources and its sub-packages may read the system clocks`() {
        assertEquals(0, findingsInFile(adapterCode, "androidApp/src/main/kotlin", "com.yawnandpawn.app.android"))
        assertEquals(0, findingsInFile(adapterCode, "androidApp/src/test/kotlin", "com.yawnandpawn.app.android.time"))
    }

    @Test
    fun `the adapter package name outside androidApp sources is still reported`() {
        assertEquals(4, findingsInFile(adapterCode, "core/src/commonMain/kotlin", "com.yawnandpawn.app.android"))
        assertEquals(4, findings(adapterCode, "com.yawnandpawn.app.android").size)
    }

    @Test
    fun `a look-alike android package elsewhere is still reported`() {
        assertEquals(1, findingsInFile("fun wall() = Clock.System.now()", "androidApp/src/main/kotlin", "com.yawnandpawn.app.androidx"))
        assertEquals(1, findingsInFile("fun wall() = Clock.System.now()", "androidApp/src/main/kotlin", "com.yawnandpawn.app.ui.android"))
    }
}
