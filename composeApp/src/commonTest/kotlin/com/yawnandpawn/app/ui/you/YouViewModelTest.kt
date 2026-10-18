package com.yawnandpawn.app.ui.you

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
 * Story 5.8: the You tab's links (privacy, terms, support), "Email support" with and without an email app, "Copy
 * address", and the 4 s "No browser found." snackbar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YouViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val links = RecordingLinks()
    private val appLinks =
        AppLinks(
            supportEmail = "help@example.com",
            orderHistoryUrl = "https://play.google.com/store/account/orderhistory",
            privacyUrl = "https://example.com/privacy/",
            termsUrl = "https://example.com/terms/",
            supportUrl = "https://example.com/support/",
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun screen(): YouViewModel = YouViewModel(links, appLinks)

    @Test
    fun `production shows the built rows, never Delete all data or About`() {
        assertEquals(
            setOf(YouRow.PurchaseHistory, YouRow.Payments, YouRow.Privacy, YouRow.Support, YouRow.EmailSupport, YouRow.Terms),
            BuiltYouRows,
        )
    }

    @Test
    fun `Privacy policy, Terms and Support open their pages, with no snackbar`() =
        runTest(dispatcher) {
            val viewModel = screen()

            viewModel.onIntent(YouIntent.PrivacyClicked)
            viewModel.onIntent(YouIntent.TermsClicked)
            viewModel.onIntent(YouIntent.SupportClicked)

            assertEquals(listOf("https://example.com/privacy/", "https://example.com/terms/", "https://example.com/support/"), links.urls)
            assertFalse(viewModel.state.value.noBrowser)
        }

    @Test
    fun `with no browser it says No browser found for 4 s, and a second tap starts the 4 s again`() =
        runTest(dispatcher) {
            links.canOpenUrls = false
            val viewModel = screen()

            viewModel.onIntent(YouIntent.PrivacyClicked)
            assertTrue(viewModel.state.value.noBrowser)
            advanceTimeBy(YouViewModel.SNACKBAR_MILLIS - 1_000)
            assertTrue(viewModel.state.value.noBrowser)

            viewModel.onIntent(YouIntent.TermsClicked)
            advanceTimeBy(YouViewModel.SNACKBAR_MILLIS - 1)
            assertTrue(viewModel.state.value.noBrowser, "the second tap restarted the 4 s")
            advanceTimeBy(2)
            runCurrent()
            assertFalse(viewModel.state.value.noBrowser)
            assertTrue(links.urls.isEmpty())
        }

    @Test
    fun `Email support opens an email to the support address with no subject or body`() =
        runTest(dispatcher) {
            val viewModel = screen()

            viewModel.onIntent(YouIntent.EmailSupportClicked)

            assertEquals(listOf(SentEmail("help@example.com", "", "")), links.emails)
            assertNull(viewModel.state.value.noEmailAppAddress)
        }

    @Test
    fun `with no email app the note shows the address until an email opens or the user moves on`() =
        runTest(dispatcher) {
            links.canSendEmail = false
            val viewModel = screen()

            viewModel.onIntent(YouIntent.EmailSupportClicked)
            assertEquals("help@example.com", viewModel.state.value.noEmailAppAddress)
            // A note, not a snackbar: it stays.
            advanceTimeBy(YouViewModel.SNACKBAR_MILLIS * 2)
            assertEquals("help@example.com", viewModel.state.value.noEmailAppAddress)

            links.canSendEmail = true
            viewModel.onIntent(YouIntent.EmailSupportClicked)
            assertNull(viewModel.state.value.noEmailAppAddress)

            links.canSendEmail = false
            viewModel.onIntent(YouIntent.EmailSupportClicked)
            viewModel.onIntent(YouIntent.PaymentsClicked)
            assertNull(viewModel.state.value.noEmailAppAddress)
            viewModel.onIntent(YouIntent.EmailSupportClicked)
            viewModel.onIntent(YouIntent.PurchaseHistoryClicked)
            assertNull(viewModel.state.value.noEmailAppAddress)
        }

    @Test
    fun `Copy address puts the address on the clipboard and confirms it for 4 s only where the phone does not`() =
        runTest(dispatcher) {
            links.canSendEmail = false
            val viewModel = screen()
            viewModel.onIntent(YouIntent.EmailSupportClicked)

            viewModel.onIntent(YouIntent.CopyAddressClicked)
            assertEquals(listOf("help@example.com"), links.copied)
            assertTrue(viewModel.state.value.addressCopied)
            assertEquals("help@example.com", viewModel.state.value.noEmailAppAddress)
            advanceTimeBy(YouViewModel.SNACKBAR_MILLIS + 1)
            runCurrent()
            assertFalse(viewModel.state.value.addressCopied)

            links.phoneConfirmsCopy = true
            viewModel.onIntent(YouIntent.CopyAddressClicked)
            assertEquals(listOf("help@example.com", "help@example.com"), links.copied)
            assertFalse(viewModel.state.value.addressCopied)
        }

    @Test
    fun `a newer snackbar replaces the one showing`() =
        runTest(dispatcher) {
            links.canOpenUrls = false
            val viewModel = screen()

            viewModel.onIntent(YouIntent.SupportClicked)
            viewModel.onIntent(YouIntent.CopyAddressClicked)

            assertTrue(viewModel.state.value.addressCopied)
            assertFalse(viewModel.state.value.noBrowser)
        }

    @Test
    fun `navigation, Delete all data and About change nothing else and open nothing`() =
        runTest(dispatcher) {
            val viewModel = screen()

            listOf(
                YouIntent.PurchaseHistoryClicked,
                YouIntent.PaymentsClicked,
                YouIntent.DeleteAllClicked,
                YouIntent.DeleteConfirmed,
                YouIntent.DeleteCancelled,
                YouIntent.AboutClicked,
            ).forEach(viewModel::onIntent)

            assertEquals(YouUiState(appVersion = ""), viewModel.state.value)
            assertTrue(links.urls.isEmpty() && links.emails.isEmpty() && links.copied.isEmpty())
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
