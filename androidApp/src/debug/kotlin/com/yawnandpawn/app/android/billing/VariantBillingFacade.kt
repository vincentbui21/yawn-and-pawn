package com.yawnandpawn.app.android.billing

import android.content.Context
import com.yawnandpawn.app.debug.billing.DebugFakeBillingFacade
import com.yawnandpawn.app.debug.billing.DebugFakeBillingToggle

/**
 * Debug builds only (Story 4.12): Play, unless the hidden developer toggle binds the fake billing for emulator tests
 * (`adb shell am broadcast -a com.yawnandpawn.app.debug.FAKE_BILLING --ez on true`, then restart the app). The release
 * build's version of this function always returns Play, and `checkReleaseContent` fails on any trace of the fake.
 */
fun variantBillingFacade(context: Context): BillingClientFacade =
    if (DebugFakeBillingToggle.isOn(context)) DebugFakeBillingFacade() else PlayBillingClientFacade(context)
