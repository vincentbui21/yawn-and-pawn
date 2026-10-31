package com.yawnandpawn.app.ui.about

import com.yawnandpawn.app.core.time.MonotonicClock
import com.yawnandpawn.app.ui.links.AppLinks
import com.yawnandpawn.app.ui.links.ExternalLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 0.1.7 polish: About's version and rows, its links (website, privacy policy, terms), "Contact support" with and without
 * an email app, "Copy address", the 4 s "No browser found." snackbar and the double-tap guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AboutViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val links = RecordingLinks()
    private val appLinks =
        AppLinks(
            supportEmail = "support@yawnandpawn.com",
            orderHistoryUrl = "https://play.google.com/store/account/orderhistory",
            privacyUrl = "https://yawnandpawn.com/privacy/",
            termsUrl = "https://yawnandpawn.com/terms/",
            supportUrl = "https://yawnandpawn.com/support/",
            websiteUrl = "https://yawnandpawn.com/",
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The ViewModel on the test's virtual clock, so a double tap and "1 s later" are exact. */
    private fun screen(): AboutViewModel =
        AboutViewModel(links, appLinks, AppInfo(versionName = "0.1.7"), MonotonicClock { dispatcher.scheduler.currentTime })

    @Test
    fun `it shows the version, the website's address and the support address`() {
        assertEquals(
            AboutUiState(version = "0.1.7", website = "yawnandpawn.com", supportEmail = "support@yawnandpawn.com"),
            screen().state.value,
        )
    }

    @Test
    fun `Website, Privacy policy and Terms open their pages, with no snackbar`() =
        runTest(dispatcher) {
            val viewModel = screen()

            viewModel.onIntent(AboutIntent.WebsiteClicked)
            viewModel.onIntent(AboutIntent.PrivacyClicked)
            viewModel.onIntent(AboutIntent.TermsClicked)

            assertEquals(
                listOf("https://yawnandpawn.com/", "https://yawnandpawn.com/privacy/", "https://yawnandpawn.com/terms/"),
                links.urls,
            )
            assertFalse(viewModel.state.value.noBrowser)
        }

    @Test
    fun `with no browser it says No browser found for 4 s`() =
        runTest(dispatcher) {
            links.canOpenUrls = false
            val viewModel = screen()

            viewModel.onIntent(AboutIntent.WebsiteClicked)
            assertTrue(viewModel.state.value.noBrowser)
            advanceTimeBy(AboutViewModel.SNACKBAR_MILLIS - 1)
            assertTrue(viewModel.state.value.noBrowser)
            advanceTimeBy(2)
            runCurrent()
            assertFalse(viewModel.state.value.noBrowser)
            assertTrue(links.urls.isEmpty())
        }

    @Test
    fun `Contact support opens an email to the support address with no subject or body`() =
        runTest(dispatcher) {
            val viewModel = screen()

            viewModel.onIntent(AboutIntent.ContactSupportClicked)

            assertEquals(listOf(SentEmail("support@yawnandpawn.com", "", "")), links.emails)
            assertNull(viewModel.state.value.noEmailAppAddress)
        }

    @Test
    fun `with no email app the note shows the address until an email opens, and Copy address copies it`() =
        runTest(dispatcher) {
            links.canSendEmail = false
            val viewModel = screen()

            viewModel.onIntent(AboutIntent.ContactSupportClicked)
            assertEquals("support@yawnandpawn.com", viewModel.state.value.noEmailAppAddress)
            // A note, not a snackbar: it stays.
            advanceTimeBy(AboutViewModel.SNACKBAR_MILLIS * 2)
            assertEquals("support@yawnandpawn.com", viewModel.state.value.noEmailAppAddress)

            viewModel.onIntent(AboutIntent.CopyAddressClicked)
            assertEquals(listOf("support@yawnandpawn.com"), links.copied)
            assertTrue(viewModel.state.value.addressCopied)

            links.canSendEmail = true
            viewModel.onIntent(AboutIntent.ContactSupportClicked)
            assertNull(viewModel.state.value.noEmailAppAddress)
        }

    @Test
    fun `where the phone confirms the copy itself, About says nothing`() =
        runTest(dispatcher) {
            links.phoneConfirmsCopy = true
            val viewModel = screen()

            viewModel.onIntent(AboutIntent.CopyAddressClicked)

            assertEquals(listOf("support@yawnandpawn.com"), links.copied)
            assertFalse(viewModel.state.value.addressCopied)
        }

    @Test
    fun `a double tap opens a link or the email once, and a tap 1 s later opens it again`() =
        runTest(dispatcher) {
            val viewModel = screen()

            viewModel.onIntent(AboutIntent.WebsiteClicked)
            viewModel.onIntent(AboutIntent.WebsiteClicked)
            viewModel.onIntent(AboutIntent.ContactSupportClicked)
            viewModel.onIntent(AboutIntent.ContactSupportClicked)
            assertEquals(listOf("https://yawnandpawn.com/"), links.urls)
            assertEquals(1, links.emails.size)

            advanceTimeBy(AboutViewModel.REPEAT_MILLIS)
            viewModel.onIntent(AboutIntent.WebsiteClicked)
            viewModel.onIntent(AboutIntent.ContactSupportClicked)
            assertEquals(2, links.urls.size)
            assertEquals(2, links.emails.size)
        }

    private data class SentEmail(
        val to: String,
        val subject: String,
        val body: String,
    )

    private class RecordingLinks : ExternalLinks {
        var canOpenUrls = true
        var canSendEmail = true
        var phoneConfirmsCopy = false
        val urls = mutableListOf<String>()
        val emails = mutableListOf<SentEmail>()
        val copied = mutableListOf<String>()

        override fun openUrl(url: String): Boolean = canOpenUrls.also { if (it) urls += url }

        override fun sendEmail(
            to: String,
            subject: String,
            body: String,
        ): Boolean = canSendEmail.also { if (it) emails += SentEmail(to, subject, body) }

        override fun copyText(text: String): Boolean {
            copied += text
            return !phoneConfirmsCopy
        }
    }
}
