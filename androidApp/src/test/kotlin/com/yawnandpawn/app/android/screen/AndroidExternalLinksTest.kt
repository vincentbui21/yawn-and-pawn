package com.yawnandpawn.app.android.screen

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.yawnandpawn.app.BuildConfig
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.testing.FakeLogger
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Properties
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 4.17: "Open Google Play order history" and "Email support" start the right intents from the screen in front,
 * and a phone with no browser or email app gets false back (logged without the address or the email's text), never a
 * crash.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidExternalLinksTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val logger = FakeLogger()
    private val links = AndroidExternalLinks(app, logger)

    @Test
    fun `the order history opens with ACTION_VIEW on its https page, as a new task`() {
        assertTrue(links.openUrl("https://play.google.com/store/account/orderhistory"))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://play.google.com/store/account/orderhistory", started.dataString)
        assertTrue(started.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `the support email opens ACTION_SENDTO mailto with the address, subject and body in the URI and the extras`() {
        val body = "Order ID: GPA.3301-2345\nDate: 2027-03-03T08:00:00+02:00\nPrice: €2,38\nStatus: Snooze 2"

        assertTrue(links.sendEmail("help@example.com", "Charge question", body))

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, started.action)
        val data = assertNotNullUri(started.data)
        assertEquals("mailto", data.scheme)
        assertTrue(data.toString().startsWith("mailto:help@example.com?subject="), data.toString())
        // The mailto: URI decodes back to exactly the subject and body.
        val query =
            data
                .toString()
                .substringAfter('?')
                .split('&')
                .associate { it.substringBefore('=') to Uri.decode(it.substringAfter('=')) }
        assertEquals("Charge question", query["subject"])
        assertEquals(body, query["body"])
        assertContentEquals(arrayOf("help@example.com"), started.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Charge question", started.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals(body, started.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `with no browser or email app nothing starts, the caller hears false and the log names no address or text`() {
        shadowOf(app).checkActivities(true)

        assertFalse(links.openUrl("https://play.google.com/store/account/orderhistory"))
        assertFalse(links.sendEmail("help@example.com", "Charge question", "Order ID: GPA.1"))

        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(
            listOf<LogEvent>(
                LogEvent.OperationFailed("open link", "ActivityNotFoundException"),
                LogEvent.OperationFailed("open support email", "ActivityNotFoundException"),
            ),
            logger.events,
        )
    }

    @Test
    fun `the build takes the support address and the order history page from config app-links properties`() {
        val file =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "config/app-links.properties") }
                .first { it.exists() }
        val config = Properties().apply { file.reader().use { load(it) } }

        assertEquals(config.getProperty("support.email"), BuildConfig.SUPPORT_EMAIL)
        assertEquals(config.getProperty("play.orderHistory"), BuildConfig.PLAY_ORDER_HISTORY_URL)
        assertEquals("https://play.google.com/store/account/orderhistory", BuildConfig.PLAY_ORDER_HISTORY_URL)
    }

    private fun assertNotNullUri(uri: Uri?): Uri = checkNotNull(uri) { "no data" }
}
