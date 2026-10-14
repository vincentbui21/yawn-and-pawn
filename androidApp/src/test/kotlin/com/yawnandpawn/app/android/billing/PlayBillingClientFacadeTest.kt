package com.yawnandpawn.app.android.billing

import android.app.Activity
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.ProductDetails
import com.yawnandpawn.app.StopAppRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Story 4.12 review 6: the real facade. Robolectric has no Play Store, which is the no-Play path: every call answers
 * `BILLING_UNAVAILABLE` at once, with no client built. The launch request carries the install id as the obfuscated
 * account id and the session id as the obfuscated profile id.
 */
@RunWith(RobolectricTestRunner::class)
class PlayBillingClientFacadeTest {
    @get:Rule
    val stopApp = StopAppRule()

    private val facade = PlayBillingClientFacade(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `without the Play Store every call answers BILLING_UNAVAILABLE at once`() {
        val answers = mutableListOf<Int>()

        facade.startConnection(onSetup = { answers += it.code }, onDisconnected = {})
        facade.queryProducts(listOf("snooze_usd_01")) { answers += it.result.code }
        facade.queryPurchases { answers += it.result.code }
        facade.consumeAsync("token") { answers += it.code }
        facade.queryBillingCountry { result, country ->
            answers += result.code
            assertNull(country)
        }
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        answers += facade.launchBillingFlow(activity, FakeBillingClientFacade.product("snooze_usd_01"), "install-1", "session-1").code
        facade.endConnection()

        assertEquals(List(6) { BillingResponseCode.BILLING_UNAVAILABLE }, answers)
        assertEquals(false, facade.isReady)
    }

    @Test
    fun `the launch request sends the install id as account id and the session id as profile id`() {
        val details = productDetails()
        val product = PlayProduct("snooze_usd_02", "$2.00", 2_000_000, "USD", offerToken = "offer-2", handle = details)

        val request = assertNotNull(flowRequest(product, accountId = "install-7", profileId = "session-9"))

        assertEquals("install-7", request.accountId)
        assertEquals("session-9", request.profileId)
        assertEquals("offer-2", request.offerToken)
        assertSame(details, request.details)
        assertNotNull(request.toParams())
    }

    @Test
    fun `no request without Play's product details`() {
        assertNull(flowRequest(FakeBillingClientFacade.product("snooze_usd_01"), "install-1", "session-1"))
    }

    /** Play's `ProductDetails` from its JSON (the constructor is not public). */
    private fun productDetails(): ProductDetails {
        val json =
            """{"productId":"snooze_usd_02","type":"inapp","title":"Snooze","name":"Snooze","description":"d",""" +
                """"localizedIn":["en-US"],"skuDetailsToken":"t","oneTimePurchaseOfferDetails":{"priceAmountMicros":2000000,""" +
                """"priceCurrencyCode":"USD","formattedPrice":"$2.00","offerIdToken":"offer-2"}}"""
        val constructor = ProductDetails::class.java.getDeclaredConstructor(String::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(json)
    }
}
