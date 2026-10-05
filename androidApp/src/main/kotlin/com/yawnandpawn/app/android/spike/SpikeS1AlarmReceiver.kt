// SPIKE S1 (branch spike/s1-billing-lockscreen only, never merged to main). Throwaway prototype code.
package com.yawnandpawn.app.android.spike

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.yawnandpawn.app.R

/**
 * "Ring in 15 s": an exact `setAlarmClock` alarm fires this receiver, which starts the spike sound and posts a
 * full-screen-intent notification that opens [SpikeS1Activity] over the lock screen (the real app's WakeService
 * pattern, reduced to the minimum and self-contained).
 */
class SpikeS1AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        SpikeS1Log.log("alarm: FIRED (setAlarmClock), posting full-screen notification")
        SpikeS1Sound.start(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Spike S1", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        val open = activityIntent(context, REQUEST_FULL_SCREEN)
        val notification =
            Notification
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle("Spike S1 ringing")
                .setContentText("Tap to open the spike screen")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .build()
        val canPost =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canPost) {
            manager.notify(NOTIFICATION_ID, notification)
            val fsi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) manager.canUseFullScreenIntent() else true
            SpikeS1Log.log("alarm: notification posted, canUseFullScreenIntent=$fsi")
        } else {
            SpikeS1Log.log("alarm: POST_NOTIFICATIONS not granted, no notification (open the Spike S1 icon by hand)")
        }
    }

    companion object {
        private const val CHANNEL_ID = "spike_s1"
        const val NOTIFICATION_ID = 1_051
        private const val REQUEST_ALARM = 5_100
        private const val REQUEST_FULL_SCREEN = 5_101
        private const val REQUEST_SHOW = 5_102
        private const val DELAY_MS = 15_000L

        private fun activityIntent(
            context: Context,
            requestCode: Int,
        ): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, SpikeS1Activity::class.java)
                    .putExtra(SpikeS1Activity.EXTRA_FROM_ALARM, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Arms the exact alarm 15 s from now; logs and returns false when exact alarms are not allowed. */
        fun scheduleIn15s(context: Context): Boolean {
            val alarms = context.getSystemService(AlarmManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarms.canScheduleExactAlarms()) {
                SpikeS1Log.log("alarm: canScheduleExactAlarms() is false, not scheduled")
                return false
            }
            val operation =
                PendingIntent.getBroadcast(
                    context,
                    REQUEST_ALARM,
                    Intent(context, SpikeS1AlarmReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val at = System.currentTimeMillis() + DELAY_MS
            return try {
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, activityIntent(context, REQUEST_SHOW)), operation)
                SpikeS1Log.log("alarm: setAlarmClock in $DELAY_MS ms (wall $at). Lock the phone now.")
                true
            } catch (e: SecurityException) {
                SpikeS1Log.log("alarm: setAlarmClock refused: ${e.message}")
                false
            }
        }

        fun cancelNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}
