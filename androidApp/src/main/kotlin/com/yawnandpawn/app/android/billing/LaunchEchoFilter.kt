package com.yawnandpawn.app.android.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode

/**
 * Delivers each sheet result once (Story 4.11 deferral, the "echo rule"). When `launchBillingFlow` answers an error
 * itself (for example `ITEM_ALREADY_OWNED`), Play also sends the same code to the purchases listener. The adapter already
 * returns that answer from `launch`, so the listener's copy is an echo and is dropped here:
 * - an update that arrives while `launchBillingFlow` runs is held, then let through once the call has returned, except
 *   one bare copy (no purchases) of the code the call returned;
 * - when no copy came during the call, the first bare copy of that code within [windowMillis] after it is dropped.
 *
 * Purchases (OK with a list) are never dropped. All calls are synchronized: Play's listener runs on the main thread,
 * which is also where the launch runs, but nothing here relies on that.
 */
class LaunchEchoFilter(
    private val windowMillis: Long,
    private val nowMillis: () -> Long,
) {
    /** One `onPurchasesUpdated` call. */
    class Update(
        val result: PlayResult,
        val purchases: List<PlayPurchase>?,
    ) {
        val bare: Boolean get() = result.code != BillingResponseCode.OK && purchases.isNullOrEmpty()
    }

    private var launching = false
    private val held = mutableListOf<Update>()
    private var armedCode: Int? = null
    private var armedUntil = 0L

    /** `launchBillingFlow` is about to run. */
    @Synchronized
    fun begin() {
        launching = true
        held.clear()
        armedCode = null
    }

    /** True when [update] is held or dropped, so the caller must not deliver it now. */
    @Synchronized
    fun intercept(update: Update): Boolean {
        val echo = !launching && isArmedEcho(update)
        if (launching) held += update
        if (echo) armedCode = null
        return launching || echo
    }

    private fun isArmedEcho(update: Update): Boolean = update.bare && armedCode == update.result.code && nowMillis() <= armedUntil

    /**
     * `launchBillingFlow` returned [code]. Returns the updates held during the call that must still be delivered, in
     * order (the echo of [code] removed).
     */
    @Synchronized
    fun end(code: Int): List<Update> {
        launching = false
        val out = held.toMutableList()
        held.clear()
        if (code != BillingResponseCode.OK) {
            val echo = out.indexOfFirst { it.bare && it.result.code == code }
            if (echo >= 0) {
                out.removeAt(echo)
            } else {
                armedCode = code
                armedUntil = nowMillis() + windowMillis
            }
        }
        return out
    }
}
