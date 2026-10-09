package com.yawnandpawn.app.core.billing

import com.yawnandpawn.app.core.error.valueOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

/**
 * The one stored form of a [PriceCatalogSnapshot] (Story 4.3): versioned JSON with one object per product (id, Play's
 * string, micros, currency, fetch time in epoch milliseconds). Unknown keys are ignored, so a newer version's value
 * still decodes after a downgrade. An entry whose currency is malformed is dropped; text that is not this format
 * decodes to null.
 */
object PriceCatalogJson {
    /** The format version written; a later format change bumps it and migrates older values. */
    const val VERSION = 1

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(snapshot: PriceCatalogSnapshot): String =
        json.encodeToString(
            StoredCatalog.serializer(),
            StoredCatalog(
                version = VERSION,
                entries =
                    snapshot.entries.values
                        .sortedBy { it.productId }
                        .map {
                            StoredEntry(
                                it.productId,
                                it.formattedPrice,
                                it.price.micros,
                                it.price.currency,
                                it.fetchedAt.toEpochMilliseconds(),
                            )
                        },
            ),
        )

    /** The snapshot stored as [text], or null when [text] is not this format (or a newer, incompatible version). */
    fun decode(text: String): PriceCatalogSnapshot? {
        val stored = runCatching { json.decodeFromString(StoredCatalog.serializer(), text) }.getOrNull()
        return stored?.takeIf { it.version == VERSION }?.let { catalog ->
            PriceCatalogSnapshot(catalog.entries.mapNotNull { it.toEntry() }.associateBy { it.productId })
        }
    }

    private fun StoredEntry.toEntry(): PriceEntry? =
        Money.parse(micros, currency).valueOrNull()?.let { money ->
            PriceEntry(productId, formattedPrice, money, Instant.fromEpochMilliseconds(fetchedAtMillis))
        }

    @Serializable
    private data class StoredCatalog(
        val version: Int,
        val entries: List<StoredEntry>,
    )

    @Serializable
    private data class StoredEntry(
        @SerialName("productId") val productId: String,
        @SerialName("formattedPrice") val formattedPrice: String,
        @SerialName("micros") val micros: Long,
        @SerialName("currency") val currency: String,
        @SerialName("fetchedAt") val fetchedAtMillis: Long,
    )
}
