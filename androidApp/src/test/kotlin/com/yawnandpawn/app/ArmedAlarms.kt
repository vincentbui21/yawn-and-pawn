package com.yawnandpawn.app

import android.app.AlarmManager
import android.content.Context
import org.robolectric.Shadows.shadowOf

/**
 * How many system alarms are armed right now (Robolectric's AlarmManager). Kept apart, so a test file that pins its own
 * clock (`FakeClock.set`) never names AlarmManager and trips the inexact-alarm rule.
 */
internal fun armedAlarmCount(context: Context): Int = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size
