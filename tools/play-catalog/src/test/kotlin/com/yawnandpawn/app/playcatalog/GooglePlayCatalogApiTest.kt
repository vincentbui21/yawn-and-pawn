package com.yawnandpawn.app.playcatalog

import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.Json
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.androidpublisher.AndroidPublisher
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The real adapter against a scripted in-memory HTTP transport: request shapes and mapping. No network. */
class GooglePlayCatalogApiTest {
    private val requests = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    /** Answers each request with the first response whose key the decoded "METHOD url" contains. */
    private fun api(vararg responses: Pair<String, Pair<Int, String>>): GooglePlayCatalogApi {
        val transport =
            object : MockHttpTransport() {
                override fun buildRequest(
                    method: String,
                    url: String,
                ): LowLevelHttpRequest {
                    val request = "$method ${URLDecoder.decode(url, Charsets.UTF_8)}"
                    requests += request
                    return object : MockLowLevelHttpRequest(url) {
                        override fun execute(): LowLevelHttpResponse {
                            bodies += contentAsString
                            val (status, json) = responses.first { (key, _) -> key in request }.second
                            return MockLowLevelHttpResponse().setStatusCode(status).setContentType(Json.MEDIA_TYPE).setContent(json)
                        }
                    }
                }
            }
        return GooglePlayCatalogApi(
            AndroidPublisher.Builder(transport, GsonFactory.getDefaultInstance(), null).setApplicationName("test").build(),
        )
    }

    private val base = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.yawnandpawn.app"

    @Test
    fun `list follows every page`() {
        val api =
            api(
                "pageToken=p2" to (200 to """{"oneTimeProducts": [{"productId": "snooze_usd_02"}]}"""),
                "oneTimeProducts" to (200 to """{"oneTimeProducts": [{"productId": "snooze_usd_01"}], "nextPageToken": "p2"}"""),
            )

        val products = api.listOneTimeProducts("com.yawnandpawn.app")

        assertEquals(listOf("snooze_usd_01", "snooze_usd_02"), products.map { it.productId })
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.startsWith("GET $base/oneTimeProducts?pageSize=100") }, requests.toString())
    }

    @Test
    fun `a product with its buy option, regions and listings maps from the API`() {
        val api =
            api(
                "oneTimeProducts" to (
                    200 to
                        """
                        {"oneTimeProducts": [{"productId": "snooze_usd_02",
                          "listings": [{"languageCode": "en-US", "title": "Snooze", "description": "One snooze for your alarm."}],
                          "purchaseOptions": [{"purchaseOptionId": "buy", "state": "INACTIVE",
                            "buyOption": {"legacyCompatible": true},
                            "regionalPricingAndAvailabilityConfigs": [
                              {"regionCode": "US", "price": {"currencyCode": "USD", "units": "2"}, "availability": "AVAILABLE"},
                              {"regionCode": "FI", "price": {"currencyCode": "EUR", "units": "1", "nanos": 790000000},
                               "availability": "NO_LONGER_AVAILABLE"}],
                            "newRegionsConfig": {"usdPrice": {"currencyCode": "USD", "units": "2"},
                              "eurPrice": {"currencyCode": "EUR", "units": "2"}, "availability": "AVAILABLE"}}]}]}
                        """.trimIndent()
                ),
            )

        val product = api.listOneTimeProducts("com.yawnandpawn.app").single()

        assertEquals(listOf(SnoozeCatalog.listing), product.listings)
        val option = product.purchaseOptions.single()
        assertEquals(OptionState.INACTIVE, option.state)
        assertTrue(option.isBuy && option.legacyCompatible && !option.multiQuantityEnabled)
        assertEquals(RegionalConfig(Price.usd(2), available = true), option.regions["US"])
        assertEquals(
            RegionalConfig(Price("EUR", 1, 790_000_000), available = false, rawAvailability = "NO_LONGER_AVAILABLE"),
            option.regions["FI"],
        )
        assertEquals(Price.usd(2), option.newRegionsUsd)
        assertTrue(option.newRegionsAvailable)
    }

    @Test
    fun `convertRegionPrices sends the USD price and maps every region`() {
        val api =
            api(
                "pricing:convertRegionPrices" to (
                    200 to
                        """
                        {"convertedRegionPrices": {
                           "US": {"regionCode": "US", "price": {"currencyCode": "USD", "units": "5"}},
                           "JP": {"regionCode": "JP", "price": {"currencyCode": "JPY", "units": "750"}}},
                         "convertedOtherRegionsPrice": {"usdPrice": {"currencyCode": "USD", "units": "5"},
                           "eurPrice": {"currencyCode": "EUR", "units": "4", "nanos": 690000000}},
                         "regionVersion": {"version": "2025/03"}}
                        """.trimIndent()
                ),
            )

        val converted = api.convertRegionPrices("com.yawnandpawn.app", Price.usd(5))

        assertEquals("POST $base/pricing:convertRegionPrices", requests.single())
        assertTrue(""""currencyCode":"USD"""" in bodies.single() && """"units":"5"""" in bodies.single(), bodies.single())
        assertEquals(
            ConvertedPrices("2025/03", mapOf("JP" to Price("JPY", 750), "US" to Price.usd(5)), Price.usd(5), Price("EUR", 4, 690_000_000)),
            converted,
        )
    }

    @Test
    fun `patch creates with allowMissing, the update mask and the regions version, and never sends the state`() {
        val desired = SnoozeCatalog.desiredProduct(3, FakePlayCatalogApi.convert(3))
        val api = api("onetimeproducts/snooze_usd_03" to (200 to """{"productId": "snooze_usd_03"}"""))

        api.patchOneTimeProduct("com.yawnandpawn.app", desired, PatchField.entries.toSet(), "2025/03", allowMissing = true)

        val request = requests.single()
        assertTrue(request.startsWith("PATCH $base/onetimeproducts/snooze_usd_03?"), request)
        assertTrue("allowMissing=true" in request, request)
        assertTrue("updateMask=listings,purchaseOptions" in request, request)
        assertTrue("regionsVersion.version=2025/03" in request, request)
        val body = bodies.single()
        assertTrue(""""legacyCompatible":true""" in body, body)
        assertTrue(""""title":"Snooze"""" in body && """"description":"One snooze for your alarm."""" in body, body)
        assertTrue(""""regionCode":"JP"""" in body && """"availability":"AVAILABLE"""" in body, body)
        assertFalse(""""state"""" in body, body)
        assertTrue(
            """"newRegionsConfig":{"availability":"AVAILABLE","eurPrice":{"currencyCode":"EUR","nanos":0,"units":"3"},""" +
                """"usdPrice":{"currencyCode":"USD","nanos":0,"units":"3"}}""" in body,
            body,
        )
    }

    @Test
    fun `an update keeps the option's tax and compliance settings and offer tags as Play returned them`() {
        val listed =
            api(
                "oneTimeProducts" to (
                    200 to
                        """
                        {"oneTimeProducts": [{"productId": "snooze_usd_02",
                          "listings": [{"languageCode": "en-US", "title": "Snooze", "description": "One snooze for your alarm."}],
                          "purchaseOptions": [{"purchaseOptionId": "buy", "state": "ACTIVE", "buyOption": {"legacyCompatible": true},
                            "taxAndComplianceSettings": {"withdrawalRightType": "WITHDRAWAL_RIGHT_SERVICE"},
                            "offerTags": [{"tag": "owner-tag"}],
                            "regionalPricingAndAvailabilityConfigs": [
                              {"regionCode": "US", "price": {"currencyCode": "USD", "units": "3"}, "availability": "AVAILABLE"}]}]}]}
                        """.trimIndent()
                ),
            ).listOneTimeProducts("com.yawnandpawn.app").single()
        val desired = SnoozeCatalog.desiredProduct(2, FakePlayCatalogApi.convert(2), listed)

        val body =
            GsonFactory.getDefaultInstance().toString(PlayModelMapper.toApi("com.yawnandpawn.app", desired, "2025/03"))

        assertTrue(""""taxAndComplianceSettings":{"withdrawalRightType":"WITHDRAWAL_RIGHT_SERVICE"}""" in body, body)
        assertTrue(""""offerTags":[{"tag":"owner-tag"}]""" in body, body)
        assertTrue(""""units":"2"""" in body, body)
    }

    @Test
    fun `a listing-only patch masks only listings`() {
        val desired = SnoozeCatalog.desiredProduct(4, FakePlayCatalogApi.convert(4))
        val api = api("onetimeproducts/snooze_usd_04" to (200 to """{"productId": "snooze_usd_04"}"""))

        api.patchOneTimeProduct("com.yawnandpawn.app", desired, setOf(PatchField.LISTINGS), "2025/03", allowMissing = false)

        assertTrue("updateMask=listings&" in requests.single() || requests.single().endsWith("updateMask=listings"), requests.single())
        assertTrue("allowMissing=false" in requests.single(), requests.single())
    }

    @Test
    fun `activation goes through batchUpdateStates for the buy option`() {
        val api =
            api(
                "purchaseOptions:batchUpdateStates" to (
                    200 to
                        """{"oneTimeProducts": [{"productId": "snooze_usd_05", "purchaseOptions": [{"purchaseOptionId": "buy", "state": "ACTIVE",
                           "buyOption": {"legacyCompatible": true}}]}]}"""
                ),
            )

        val product = api.activatePurchaseOption("com.yawnandpawn.app", "snooze_usd_05", "buy")

        assertEquals("POST $base/oneTimeProducts/snooze_usd_05/purchaseOptions:batchUpdateStates", requests.single())
        assertTrue(""""activatePurchaseOptionRequest":{""" in bodies.single(), bodies.single())
        assertTrue(""""purchaseOptionId":"buy"""" in bodies.single(), bodies.single())
        assertEquals(OptionState.ACTIVE, product.purchaseOptions.single().state)
    }

    @Test
    fun `an API error becomes a PlayApiException with the status and Play's message, after one attempt`() {
        val api =
            api(
                "oneTimeProducts" to (
                    403 to
                        """{"error": {"code": 403, "message": "The caller does not have permission", "status": "PERMISSION_DENIED"}}"""
                ),
            )

        val error = assertFailsWith<PlayApiException> { api.listOneTimeProducts("com.yawnandpawn.app") }

        assertEquals("onetimeproducts.list", error.call)
        assertEquals("HTTP 403: The caller does not have permission", error.message)
        assertTrue(error.isPermissionProblem)
        assertEquals(1, requests.size)
    }

    @Test
    fun `the desired product survives the trip through the API model`() {
        val desired = SnoozeCatalog.desiredProduct(9, FakePlayCatalogApi.convert(9))

        val back = PlayModelMapper.fromApi(PlayModelMapper.toApi("com.yawnandpawn.app", desired, "2025/03"))

        assertEquals(
            desired.copy(purchaseOptions = desired.purchaseOptions.map { it.copy(state = OptionState.UNKNOWN) }),
            back.copy(purchaseOptions = back.purchaseOptions.map { it.copy(regions = it.regions.toSortedMap()) }),
        )
    }
}
