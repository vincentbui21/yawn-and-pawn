package com.yawnandpawn.app.android.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.Purchase
import com.yawnandpawn.app.StopAppRule
import com.yawnandpawn.app.core.billing.LaunchResult
import com.yawnandpawn.app.core.billing.PlayPurchaseState
import com.yawnandpawn.app.core.billing.PurchaseSnapshot
import com.yawnandpawn.app.core.billing.PurchaseUpdate
import com.yawnandpawn.app.core.session.ConsumeResult
import com.yawnandpawn.app.core.session.PurchaseFailureKind
import com.yawnandpawn.app.core.session.PurchaseToken
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Story 4.12: the response-code table, row by row, and Play's `Purchase` to `PurchaseSnapshot` (Robolectric, for
 * Android's `org.json` inside Play's `Purchase`).
 */
@RunWith(RobolectricTestRunner::class)
class PlayMappingTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val offline = LaunchResult.Failed(PurchaseFailureKind.Offline)
    private val error = LaunchResult.Failed(PurchaseFailureKind.Error)

    @Test
    fun `launch codes map to launch results, one row each`() {
        val table =
            mapOf(
                BillingResponseCode.OK to LaunchResult.Launched,
                BillingResponseCode.USER_CANCELED to LaunchResult.Cancelled,
                BillingResponseCode.ITEM_ALREADY_OWNED to LaunchResult.ItemAlreadyOwned,
                BillingResponseCode.NETWORK_ERROR to offline,
                BillingResponseCode.SERVICE_UNAVAILABLE to offline,
                BillingResponseCode.SERVICE_DISCONNECTED to offline,
                BillingResponseCode.BILLING_UNAVAILABLE to error,
                BillingResponseCode.ERROR to error,
                BillingResponseCode.DEVELOPER_ERROR to error,
                BillingResponseCode.ITEM_UNAVAILABLE to error,
                BillingResponseCode.FEATURE_NOT_SUPPORTED to error,
                BillingResponseCode.ITEM_NOT_OWNED to error,
                UNKNOWN_CODE to error,
            )

        table.forEach { (code, expected) -> assertEquals(expected, PlayResponses.launchResult(code), PlayResponses.name(code)) }
    }

    @Test
    fun `update codes map to purchase updates, one row each, and OK carries the purchases`() {
        val snapshot =
            PurchaseSnapshot(
                PurchaseToken("t"),
                "snooze_usd_01",
                PlayPurchaseState.Pending,
                null,
                null,
                null,
                Instant.fromEpochMilliseconds(0),
            )
        val purchases = assertIs<PurchaseUpdate.Purchases>(PlayResponses.update(BillingResponseCode.OK, listOf(snapshot)))
        assertEquals(listOf(snapshot), purchases.purchases, "a pending purchase is passed on; the reconciler never grants it")

        val table =
            mapOf(
                BillingResponseCode.USER_CANCELED to PurchaseUpdate.Cancelled,
                BillingResponseCode.ITEM_ALREADY_OWNED to PurchaseUpdate.ItemAlreadyOwned,
                BillingResponseCode.NETWORK_ERROR to PurchaseUpdate.Failed(PurchaseFailureKind.Offline),
                BillingResponseCode.SERVICE_UNAVAILABLE to PurchaseUpdate.Failed(PurchaseFailureKind.Offline),
                BillingResponseCode.SERVICE_DISCONNECTED to PurchaseUpdate.Failed(PurchaseFailureKind.Offline),
                BillingResponseCode.BILLING_UNAVAILABLE to PurchaseUpdate.Failed(PurchaseFailureKind.Error),
                BillingResponseCode.ERROR to PurchaseUpdate.Failed(PurchaseFailureKind.Error),
                BillingResponseCode.DEVELOPER_ERROR to PurchaseUpdate.Failed(PurchaseFailureKind.Error),
                BillingResponseCode.ITEM_UNAVAILABLE to PurchaseUpdate.Failed(PurchaseFailureKind.Error),
                BillingResponseCode.FEATURE_NOT_SUPPORTED to PurchaseUpdate.Failed(PurchaseFailureKind.Error),
                BillingResponseCode.ITEM_NOT_OWNED to PurchaseUpdate.Failed(PurchaseFailureKind.Error),
            )
        table.forEach { (code, expected) -> assertEquals(expected, PlayResponses.update(code, emptyList()), PlayResponses.name(code)) }
    }

    @Test
    fun `consume codes map to consume results, and ITEM_NOT_OWNED is never Consumed`() {
        assertEquals(ConsumeResult.Consumed, PlayResponses.consumeResult(BillingResponseCode.OK))
        assertEquals(ConsumeResult.NotOwned, PlayResponses.consumeResult(BillingResponseCode.ITEM_NOT_OWNED))
        listOf(
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.NETWORK_ERROR,
            BillingResponseCode.ERROR,
            BillingResponseCode.ITEM_ALREADY_OWNED,
            BillingResponseCode.USER_CANCELED,
        ).forEach { code -> assertEquals(ConsumeResult.Failed(PlayResponses.name(code)), PlayResponses.consumeResult(code)) }
    }

    @Test
    fun `transient codes are the offline ones and ERROR`() {
        val transient =
            listOf(
                BillingResponseCode.NETWORK_ERROR,
                BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingResponseCode.SERVICE_DISCONNECTED,
                BillingResponseCode.ERROR,
            )
        transient.forEach { assertTrue(PlayResponses.isTransient(it), PlayResponses.name(it)) }
        listOf(BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.DEVELOPER_ERROR, BillingResponseCode.ITEM_UNAVAILABLE).forEach {
            assertTrue(!PlayResponses.isTransient(it), PlayResponses.name(it))
        }
    }

    @Test
    fun `a purchase maps its account id to the install id and its profile id to the session id`() {
        val snapshot =
            FakeBillingClientFacade
                .purchase(
                    "snooze_usd_03",
                    token = "tok",
                    accountId = "install-1",
                    profileId = "session-1",
                    orderId = "GPA.9",
                    timeMillis = 1_234,
                ).toSnapshots()
                .single()

        assertEquals(PurchaseToken("tok"), snapshot.token)
        assertEquals("snooze_usd_03", snapshot.productId)
        assertEquals(PlayPurchaseState.Purchased, snapshot.purchaseState)
        assertEquals("install-1", snapshot.accountId)
        assertEquals("session-1", snapshot.profileId)
        assertEquals("GPA.9", snapshot.orderId)
        assertEquals(Instant.fromEpochMilliseconds(1_234), snapshot.purchaseTime)
    }

    @Test
    fun `pending maps to Pending, UNSPECIFIED and unknown states are dropped, blank ids are null`() {
        val pending =
            FakeBillingClientFacade
                .purchase("snooze_usd_01", stateCode = PlayStateCodes.PENDING, accountId = "", profileId = " ", orderId = "")
                .toSnapshots()
                .single()
        assertEquals(PlayPurchaseState.Pending, pending.purchaseState)
        assertNull(pending.accountId)
        assertNull(pending.profileId)
        assertNull(pending.orderId)

        assertEquals(emptyList(), FakeBillingClientFacade.purchase("snooze_usd_01", stateCode = PlayStateCodes.UNSPECIFIED).toSnapshots())
        assertEquals(emptyList(), FakeBillingClientFacade.purchase("snooze_usd_01", stateCode = UNKNOWN_CODE).toSnapshots())
        assertNull(playStateOf(PlayStateCodes.UNSPECIFIED))
    }

    @Test
    fun `Play's own Purchase becomes the same plain values, with the obfuscated ids`() {
        val json =
            """{"orderId":"GPA.3301","packageName":"com.yawnandpawn.app","productId":"snooze_usd_02","purchaseTime":1800000000000,""" +
                """"purchaseState":0,"purchaseToken":"abc","obfuscatedAccountId":"install-1","obfuscatedProfileId":"session-1",""" +
                """"quantity":1,"acknowledged":false}"""
        val purchase = Purchase(json, "signature")

        val plain = purchase.toPlayPurchase()

        assertEquals("abc", plain.token)
        assertEquals(listOf("snooze_usd_02"), plain.products)
        assertEquals(purchase.purchaseState, plain.stateCode)
        assertEquals("install-1", plain.accountId)
        assertEquals("session-1", plain.profileId)
        assertEquals("GPA.3301", plain.orderId)
        assertEquals(1_800_000_000_000, plain.purchaseTimeMillis)
        assertEquals(PlayPurchaseState.Purchased, plain.toSnapshots().single().purchaseState)
        assertTrue("abc" !in plain.toString())
    }

    private companion object {
        const val UNKNOWN_CODE = 99
    }
}
