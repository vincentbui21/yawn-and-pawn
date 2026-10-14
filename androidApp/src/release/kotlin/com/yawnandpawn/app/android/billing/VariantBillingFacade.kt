package com.yawnandpawn.app.android.billing

import android.content.Context

/** The release build always talks to Play (Story 4.12); the debug build's fake billing toggle does not exist here. */
fun variantBillingFacade(context: Context): BillingClientFacade = PlayBillingClientFacade(context)
