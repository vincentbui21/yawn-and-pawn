package com.yawnandpawn.app.playcatalog

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpResponseException
import com.google.api.services.androidpublisher.AndroidPublisher
import com.google.api.services.androidpublisher.model.ActivatePurchaseOptionRequest
import com.google.api.services.androidpublisher.model.BatchUpdatePurchaseOptionStatesRequest
import com.google.api.services.androidpublisher.model.ConvertRegionPricesRequest
import com.google.api.services.androidpublisher.model.ConvertRegionPricesResponse
import com.google.api.services.androidpublisher.model.OneTimeProductBuyPurchaseOption
import com.google.api.services.androidpublisher.model.OneTimeProductListing
import com.google.api.services.androidpublisher.model.OneTimeProductPurchaseOption
import com.google.api.services.androidpublisher.model.OneTimeProductPurchaseOptionNewRegionsConfig
import com.google.api.services.androidpublisher.model.OneTimeProductPurchaseOptionRegionalPricingAndAvailabilityConfig
import com.google.api.services.androidpublisher.model.RegionsVersion
import com.google.api.services.androidpublisher.model.UpdatePurchaseOptionStateRequest
import java.io.IOException
import com.google.api.services.androidpublisher.model.Money as ApiMoney
import com.google.api.services.androidpublisher.model.OneTimeProduct as ApiProduct

/** [PlayCatalogApi] on the Google Play Developer API client (`androidpublisher` v3). */
class GooglePlayCatalogApi(
    private val publisher: AndroidPublisher,
) : PlayCatalogApi {
    private val products get() = publisher.monetization().onetimeproducts()

    override fun listOneTimeProducts(packageName: String): List<OneTimeProduct> =
        call("onetimeproducts.list") {
            val all = mutableListOf<OneTimeProduct>()
            var pageToken: String? = null
            do {
                val page =
                    products
                        .list(packageName)
                        .setPageSize(PAGE_SIZE)
                        .setPageToken(pageToken)
                        .execute()
                page.oneTimeProducts.orEmpty().mapTo(all, PlayModelMapper::fromApi)
                pageToken = page.nextPageToken?.takeIf { it.isNotEmpty() }
            } while (pageToken != null)
            all
        }

    override fun convertRegionPrices(
        packageName: String,
        price: Price,
    ): ConvertedPrices =
        call("convertRegionPrices($price)") {
            val request = ConvertRegionPricesRequest().setPrice(PlayModelMapper.toApi(price))
            PlayModelMapper.fromApi(publisher.monetization().convertRegionPrices(packageName, request).execute())
        }

    override fun patchOneTimeProduct(
        packageName: String,
        product: OneTimeProduct,
        fields: Set<PatchField>,
        regionsVersion: String,
        allowMissing: Boolean,
    ): OneTimeProduct =
        call("onetimeproducts.patch(${product.productId})") {
            val stored =
                products
                    .patch(packageName, product.productId, PlayModelMapper.toApi(packageName, product, regionsVersion))
                    .setAllowMissing(allowMissing)
                    .setUpdateMask(fields.sortedBy { it.ordinal }.joinToString(",") { it.path })
                    .setRegionsVersionVersion(regionsVersion)
                    .execute()
            PlayModelMapper.fromApi(stored)
        }

    override fun activatePurchaseOption(
        packageName: String,
        productId: String,
        purchaseOptionId: String,
    ): OneTimeProduct =
        call("purchaseOptions.batchUpdateStates($productId, activate $purchaseOptionId)") {
            val activate =
                ActivatePurchaseOptionRequest()
                    .setPackageName(packageName)
                    .setProductId(productId)
                    .setPurchaseOptionId(purchaseOptionId)
            val request =
                BatchUpdatePurchaseOptionStatesRequest()
                    .setRequests(listOf(UpdatePurchaseOptionStateRequest().setActivatePurchaseOptionRequest(activate)))
            val response = products.purchaseOptions().batchUpdateStates(packageName, productId, request).execute()
            PlayModelMapper.fromApi(response.oneTimeProducts.single { it.productId == productId })
        }

    private fun <T> call(
        name: String,
        block: () -> T,
    ): T =
        try {
            block()
        } catch (e: GoogleJsonResponseException) {
            val message = e.details?.message ?: e.statusMessage ?: e.message.orEmpty()
            throw PlayApiException(name, "HTTP ${e.statusCode}: $message", e.statusCode, e)
        } catch (e: HttpResponseException) {
            throw PlayApiException(name, "HTTP ${e.statusCode}: ${e.statusMessage ?: e.message.orEmpty()}", e.statusCode, e)
        } catch (e: IOException) {
            throw PlayApiException(name, e.message ?: e.javaClass.simpleName, cause = e)
        }

    private companion object {
        const val PAGE_SIZE = 100
    }
}

/** Maps between the tool's model and the API client's model. Fields the tool does not manage are not read or written. */
object PlayModelMapper {
    private const val AVAILABLE = "AVAILABLE"

    fun toApi(price: Price): ApiMoney =
        ApiMoney()
            .setCurrencyCode(price.currencyCode)
            .setUnits(price.units)
            .setNanos(price.nanos)

    fun fromApi(money: ApiMoney): Price = Price(money.currencyCode, money.units ?: 0L, money.nanos ?: 0)

    fun fromApi(response: ConvertRegionPricesResponse): ConvertedPrices =
        ConvertedPrices(
            regionsVersion = requireNotNull(response.regionVersion?.version) { "convertRegionPrices returned no regions version" },
            regions =
                response.convertedRegionPrices
                    .orEmpty()
                    .mapValues { (_, converted) -> fromApi(converted.price) }
                    .toSortedMap(),
            otherRegionsUsd = response.convertedOtherRegionsPrice?.usdPrice?.let(::fromApi),
            otherRegionsEur = response.convertedOtherRegionsPrice?.eurPrice?.let(::fromApi),
        )

    fun fromApi(product: ApiProduct): OneTimeProduct =
        OneTimeProduct(
            productId = product.productId,
            listings = product.listings.orEmpty().map { Listing(it.languageCode, it.title.orEmpty(), it.description.orEmpty()) },
            purchaseOptions = product.purchaseOptions.orEmpty().map(::fromApi),
        )

    private fun fromApi(option: OneTimeProductPurchaseOption): PurchaseOption =
        PurchaseOption(
            id = option.purchaseOptionId,
            state = OptionState.parse(option.state),
            isBuy = option.buyOption != null,
            legacyCompatible = option.buyOption?.legacyCompatible == true,
            multiQuantityEnabled = option.buyOption?.multiQuantityEnabled == true,
            regions =
                option.regionalPricingAndAvailabilityConfigs.orEmpty().associate {
                    it.regionCode to RegionalConfig(fromApi(it.price), it.availability == AVAILABLE)
                },
            newRegionsUsd = option.newRegionsConfig?.usdPrice?.let(::fromApi),
            newRegionsEur = option.newRegionsConfig?.eurPrice?.let(::fromApi),
            newRegionsAvailable = option.newRegionsConfig?.availability == AVAILABLE,
        )

    /** The patch body. The purchase option state is output only, so it is never sent. */
    fun toApi(
        packageName: String,
        product: OneTimeProduct,
        regionsVersion: String,
    ): ApiProduct =
        ApiProduct()
            .setPackageName(packageName)
            .setProductId(product.productId)
            .setRegionsVersion(RegionsVersion().setVersion(regionsVersion))
            .setListings(
                product.listings.map {
                    OneTimeProductListing().setLanguageCode(it.languageCode).setTitle(it.title).setDescription(it.description)
                },
            ).setPurchaseOptions(product.purchaseOptions.map(::toApi))

    private fun toApi(option: PurchaseOption): OneTimeProductPurchaseOption {
        require(option.isBuy) { "the tool only writes Buy purchase options" }
        val newRegions =
            OneTimeProductPurchaseOptionNewRegionsConfig()
                .setUsdPrice(option.newRegionsUsd?.let(::toApi))
                .setEurPrice(option.newRegionsEur?.let(::toApi))
                .setAvailability(if (option.newRegionsAvailable) AVAILABLE else "NO_LONGER_AVAILABLE")
        return OneTimeProductPurchaseOption()
            .setPurchaseOptionId(option.id)
            .setBuyOption(
                OneTimeProductBuyPurchaseOption()
                    .setLegacyCompatible(option.legacyCompatible)
                    .setMultiQuantityEnabled(option.multiQuantityEnabled),
            ).setRegionalPricingAndAvailabilityConfigs(
                option.regions.map { (region, config) ->
                    OneTimeProductPurchaseOptionRegionalPricingAndAvailabilityConfig()
                        .setRegionCode(region)
                        .setPrice(toApi(config.price))
                        .setAvailability(if (config.available) AVAILABLE else "NO_LONGER_AVAILABLE")
                },
            ).setNewRegionsConfig(newRegions)
    }
}
