package com.yawnandpawn.app.playcatalog

import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.Json
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.AndroidPublisherScopes
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The runner over the real adapter and the real JSON client, against an in-memory Play that answers the way Play does:
 * prices as strings, output-only fields (state, package name) added, default values (`multiQuantityEnabled: false`)
 * left out, products listed back in its own order. No network.
 */
class PlayRoundTripTest {
    /** Every request as "METHOD decoded-url". */
    private val requests = mutableListOf<String>()

    /** Products as Play stores them, by id. */
    private val stored = linkedMapOf<String, JsonObject>()

    /** Overrides the answer to the first request whose "METHOD url" contains the key. */
    private val scripted = mutableMapOf<String, Pair<Int, String>>()

    private val output = mutableListOf<String>()

    private val transport =
        object : MockHttpTransport() {
            override fun buildRequest(
                method: String,
                url: String,
            ): LowLevelHttpRequest {
                val request = "$method ${URLDecoder.decode(url, Charsets.UTF_8)}"
                requests += request
                return object : MockLowLevelHttpRequest(url) {
                    override fun execute(): LowLevelHttpResponse {
                        val (status, json) = scripted.entries.firstOrNull { it.key in request }?.value ?: answer(request, contentAsString)
                        return MockLowLevelHttpResponse().setStatusCode(status).setContentType(Json.MEDIA_TYPE).setContent(json)
                    }
                }
            }
        }

    private val api =
        GooglePlayCatalogApi(AndroidPublisher.Builder(transport, GsonFactory.getDefaultInstance(), null).setApplicationName("test").build())

    private fun run(mode: Mode): Int {
        output.clear()
        return CatalogRunner(api, { output += it }).run(mode)
    }

    private fun answer(
        request: String,
        body: String,
    ): Pair<Int, String> =
        when {
            request.startsWith("GET ") && "/oneTimeProducts?" in request -> 200 to listAnswer()
            request.startsWith("POST ") && request.endsWith("/pricing:convertRegionPrices") -> 200 to convertAnswer(body)
            request.startsWith("PATCH ") && "/onetimeproducts/" in request -> patchAnswer(request, body)
            request.startsWith("POST ") && request.endsWith("/purchaseOptions:batchUpdateStates") -> 200 to activateAnswer(body)
            else -> 404 to """{"error": {"code": 404, "message": "no such call: $request"}}"""
        }

    private fun listAnswer(): String {
        val products = JsonArray()
        stored.values.reversed().forEach(products::add)
        return JsonObject().apply { add("oneTimeProducts", products) }.toString()
    }

    private fun convertAnswer(body: String): String {
        val units =
            JsonParser
                .parseString(body)
                .asJsonObject["price"]
                .asJsonObject["units"]
                .asString
                .toLong()

        fun money(
            currency: String,
            whole: Long,
            nanos: Int = 0,
        ) = """{"currencyCode": "$currency", "units": "$whole"${if (nanos == 0) "" else ", \"nanos\": $nanos"}}"""
        return """
            {"convertedRegionPrices": {
               "US": {"regionCode": "US", "price": ${money("USD", units)}, "taxAmount": ${money("USD", 0)}},
               "FI": {"regionCode": "FI", "price": ${money("EUR", units * 9 / 10, (units * 9 % 10).toInt() * 100_000_000)}},
               "JP": {"regionCode": "JP", "price": ${money("JPY", units * 150)}}},
             "convertedOtherRegionsPrice": {"usdPrice": ${money("USD", units)}, "eurPrice": ${money("EUR", units)}},
             "regionVersion": {"version": "2025/03"}}
            """.trimIndent()
    }

    private fun patchAnswer(
        request: String,
        body: String,
    ): Pair<Int, String> {
        val id = request.substringAfter("/onetimeproducts/").substringBefore('?')
        val current = stored[id]
        if (current == null && "allowMissing=true" !in request) return 404 to """{"error": {"code": 404, "message": "not found"}}"""
        val sent = JsonParser.parseString(body).asJsonObject
        val product = current?.deepCopy() ?: JsonObject().apply { addProperty("productId", id) }
        product.addProperty("packageName", SnoozeCatalog.PACKAGE_NAME)
        val mask = request.substringAfter("updateMask=").substringBefore('&').split(',')
        if ("listings" in mask) product.add("listings", sent["listings"])
        if ("purchaseOptions" in mask) {
            val states =
                current
                    ?.get("purchaseOptions")
                    ?.asJsonArray
                    ?.associate {
                        it.asJsonObject["purchaseOptionId"].asString to
                            it.asJsonObject["state"]
                    }.orEmpty()
            val options = sent["purchaseOptions"].asJsonArray.deepCopy()
            options.forEach { element ->
                val option = element.asJsonObject
                option.add("state", states[option["purchaseOptionId"].asString] ?: com.google.gson.JsonPrimitive("DRAFT"))
                // Play leaves default values out of its answers.
                val buy = option["buyOption"].asJsonObject
                if (buy["multiQuantityEnabled"]?.asBoolean == false) buy.remove("multiQuantityEnabled")
                option["regionalPricingAndAvailabilityConfigs"].asJsonArray.forEach { config ->
                    val price = config.asJsonObject["price"].asJsonObject
                    if (price["nanos"]?.asInt == 0) price.remove("nanos")
                }
            }
            product.add("purchaseOptions", options)
        }
        product.add("regionsVersion", sent["regionsVersion"])
        stored[id] = product
        return 200 to product.toString()
    }

    private fun activateAnswer(body: String): String {
        val activation =
            JsonParser
                .parseString(
                    body,
                ).asJsonObject["requests"]
                .asJsonArray
                .single()
                .asJsonObject["activatePurchaseOptionRequest"]
                .asJsonObject
        val product = stored.getValue(activation["productId"].asString)
        product["purchaseOptions"]
            .asJsonArray
            .map { it.asJsonObject }
            .filter { it["purchaseOptionId"].asString == activation["purchaseOptionId"].asString }
            .forEach { it.addProperty("state", "ACTIVE") }
        return JsonObject().apply { add("oneTimeProducts", JsonArray().apply { add(product) }) }.toString()
    }

    @Test
    fun `apply over HTTP, then a second run is 0 changes with no write, using only GET, POST and PATCH`() {
        stored["spike_s1_test"] =
            JsonParser
                .parseString(
                    """{"productId": "spike_s1_test", "purchaseOptions": [{"purchaseOptionId": "default", "state": "INACTIVE",
                       "buyOption": {"legacyCompatible": true}}]}""",
                ).asJsonObject
        val spikeBefore = stored.getValue("spike_s1_test").deepCopy()

        assertEquals(ExitCode.OK, run(Mode.APPLY), output.joinToString("\n"))
        assertEquals(51, stored.size)
        assertTrue(stored.values.filter { it["productId"].asString != "spike_s1_test" }.all { "\"state\":\"ACTIVE\"" in it.toString() })
        assertEquals(spikeBefore, stored.getValue("spike_s1_test"))

        val firstRun = requests.size
        assertEquals(ExitCode.OK, run(Mode.APPLY), output.joinToString("\n"))
        val second = requests.drop(firstRun)
        assertEquals("0 changes. Play matches the catalogue.", output.last())
        assertTrue(
            "0 create, 0 update, 0 activate, 50 unchanged, 1 unmanaged, 0 attention. 0 changes." in output,
            output.joinToString("\n"),
        )
        assertFalse(second.any { it.startsWith("PATCH") || "batchUpdateStates" in it }, second.toString())

        assertEquals(setOf("GET", "POST", "PATCH"), requests.map { it.substringBefore(' ') }.toSet())
        assertFalse(
            requests.any { "delete" in it.lowercase() || "deactivate" in it.lowercase() || "spike_s1_test" in it },
            requests.toString(),
        )
    }

    @Test
    fun `a conversion answer without a regions version stops with the call named, before any write`() {
        scripted["pricing:convertRegionPrices"] = 200 to """{"convertedRegionPrices": {}}"""

        assertEquals(ExitCode.API_FAILURE, run(Mode.APPLY))

        assertTrue(
            output.any {
                it == "FAILED: convertRegionPrices(USD 1.00): unexpected answer from Play: no regions version in the answer"
            },
            output.toString(),
        )
        assertFalse(requests.any { it.startsWith("PATCH") || "batchUpdateStates" in it })
    }

    @Test
    fun `an empty activation answer stops the run with the call named and no later write`() {
        scripted["purchaseOptions:batchUpdateStates"] = 200 to "{}"

        assertEquals(ExitCode.API_FAILURE, run(Mode.APPLY))

        assertTrue(
            output.any {
                it ==
                    "FAILED while applying change 1 of 50: purchaseOptions.batchUpdateStates(snooze_usd_01, activate buy): " +
                    "unexpected answer from Play: the answer does not contain snooze_usd_01"
            },
            output.toString(),
        )
        assertEquals(1, requests.count { it.startsWith("PATCH") })
        assertEquals(1, requests.count { "batchUpdateStates" in it })
    }

    @Test
    fun `the production client sends a failed write exactly once, also after a 503 or a 401`() {
        val apiCalls = mutableListOf<String>()
        val statuses = ArrayDeque(listOf(503, 401))
        val playAndOAuth =
            object : MockHttpTransport() {
                override fun buildRequest(
                    method: String,
                    url: String,
                ): LowLevelHttpRequest =
                    object : MockLowLevelHttpRequest(url) {
                        override fun execute(): LowLevelHttpResponse {
                            val response = MockLowLevelHttpResponse().setContentType(Json.MEDIA_TYPE)
                            if ("oauth2" in url || "/token" in url) {
                                return response.setContent("""{"access_token": "test-token", "expires_in": 3600, "token_type": "Bearer"}""")
                            }
                            apiCalls += "$method $url"
                            val status = statuses.removeFirst()
                            return response
                                .setStatusCode(
                                    status,
                                ).setContent("""{"error": {"code": $status, "message": "scripted $status"}}""")
                        }
                    }
            }
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val credentials =
            ServiceAccountCredentials
                .newBuilder()
                .setClientEmail("play-catalog@test.iam.gserviceaccount.com")
                .setClientId("1")
                .setPrivateKey(key.private)
                .setPrivateKeyId("test")
                .setScopes(listOf(AndroidPublisherScopes.ANDROIDPUBLISHER))
                .setHttpTransportFactory { playAndOAuth }
                .build()
        val api = GooglePlayCatalogApi(PlayCatalogMain.publisher(credentials, playAndOAuth))
        val desired = SnoozeCatalog.desiredProduct(1, FakePlayCatalogApi.convert(1))

        val unavailable =
            assertFailsWith<PlayApiException> {
                api.patchOneTimeProduct("com.yawnandpawn.app", desired, PatchField.entries.toSet(), "2025/03", allowMissing = true)
            }
        assertEquals(503, unavailable.httpStatus)
        assertEquals(1, apiCalls.size)

        val unauthorized =
            assertFailsWith<PlayApiException> {
                api.patchOneTimeProduct("com.yawnandpawn.app", desired, PatchField.entries.toSet(), "2025/03", allowMissing = true)
            }
        assertEquals(401, unauthorized.httpStatus)
        assertTrue(unauthorized.isPermissionProblem)
        assertEquals(2, apiCalls.size, apiCalls.toString())
        assertTrue(apiCalls.all { it.startsWith("PATCH ") }, apiCalls.toString())
    }
}
