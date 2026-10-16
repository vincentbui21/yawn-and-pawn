package com.yawnandpawn.app.android.telemetry

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/** One event parameter: a whole number or a fixed name (an enum's), never free text. */
sealed interface AnalyticsParam {
    data class Number(
        val value: Long,
    ) : AnalyticsParam

    data class Name(
        val value: String,
    ) : AnalyticsParam
}

/**
 * The calls the app makes on Analytics (Story 5.6, AD-15): Firebase Analytics in production, a recording fake in tests.
 * There is deliberately no call for a user id or a user property.
 */
interface AnalyticsSink {
    /** Firebase has started (after the first unlock, with a Firebase configuration). */
    val isReady: Boolean

    fun setCollectionEnabled(enabled: Boolean)

    /** Grants analytics storage and denies every ad consent (ad storage, ad user data, ad personalization). */
    fun grantAnalyticsStorageOnly()

    /** Deletes the app instance's analytics data and id on the device. */
    fun resetData()

    fun logEvent(
        name: String,
        params: Map<String, AnalyticsParam>,
    )
}

/** [AnalyticsSink] on the default Firebase app's Analytics; looked up only once Firebase has started. */
class FirebaseAnalyticsSink(
    private val context: Context,
) : AnalyticsSink {
    private val analytics: FirebaseAnalytics by lazy { FirebaseAnalytics.getInstance(context) }

    override val isReady: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    override fun setCollectionEnabled(enabled: Boolean) = analytics.setAnalyticsCollectionEnabled(enabled)

    override fun grantAnalyticsStorageOnly() =
        analytics.setConsent(
            mapOf(
                FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to FirebaseAnalytics.ConsentStatus.GRANTED,
                FirebaseAnalytics.ConsentType.AD_STORAGE to FirebaseAnalytics.ConsentStatus.DENIED,
                FirebaseAnalytics.ConsentType.AD_USER_DATA to FirebaseAnalytics.ConsentStatus.DENIED,
                FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to FirebaseAnalytics.ConsentStatus.DENIED,
            ),
        )

    override fun resetData() = analytics.resetAnalyticsData()

    override fun logEvent(
        name: String,
        params: Map<String, AnalyticsParam>,
    ) {
        val bundle = Bundle()
        params.forEach { (key, param) ->
            when (param) {
                is AnalyticsParam.Number -> bundle.putLong(key, param.value)
                is AnalyticsParam.Name -> bundle.putString(key, param.value)
            }
        }
        analytics.logEvent(name, bundle)
    }
}

/** The [AnalyticsSink] of a build without a Firebase configuration: never ready, so nothing is ever called. */
object NoOpAnalyticsSink : AnalyticsSink {
    override val isReady: Boolean = false

    override fun setCollectionEnabled(enabled: Boolean) = Unit

    override fun grantAnalyticsStorageOnly() = Unit

    override fun resetData() = Unit

    override fun logEvent(
        name: String,
        params: Map<String, AnalyticsParam>,
    ) = Unit
}
