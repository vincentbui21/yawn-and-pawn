// SPIKE S1 (branch spike/s1-billing-lockscreen only, never merged to main). Throwaway prototype code.
package com.yawnandpawn.app.android.spike

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.pm.PackageInfoCompat
import java.util.UUID

/**
 * Spike S1 screen: "pay for a snooze over the lock screen". Opens from its own launcher icon "Spike S1" (own task), from
 * `yawnandpawn://spike-s1`, or from the "Ring in 15 s" full-screen notification. It shows over the lock screen and turns
 * the screen on, starts the looping alarm sound when it opens, and offers:
 *
 * - **Pay**: when the keyguard is locked, `requestDismissKeyguard` first (logs onDismissSucceeded / Cancelled / Error),
 *   then `launchBillingFlow`; unlocked, `launchBillingFlow` straight away (questions 1, 2, 4).
 * - **Pay without unlock**: `launchBillingFlow` straight away even while locked (question 1).
 * - **Stop sound** / **Start sound**, **Query purchases**, **Consume all**, **Ring in 15 s**, **Reconnect billing**,
 *   **Share log**, **Clear log**.
 *
 * Every step is logged under tag `SpikeS1` with elapsedRealtime and ms since the last Pay tap (`adb logcat -s SpikeS1`).
 */
@Suppress("TooManyFunctions") // Spike: one function per button.
@SuppressLint("SetTextI18n") // Spike: English-only debug text, never shipped to main.
class SpikeS1Activity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private var payCount = 0

    private val keyguard: KeyguardManager by lazy { getSystemService(KeyguardManager::class.java) }

    private val ticker =
        object : Runnable {
            override fun run() {
                render()
                main.postDelayed(this, TICK_MS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildLayout())
        SpikeS1Log.log("activity: onCreate fromAlarm=${intent.fromAlarm()} ${lockState()} version=${versionText()}")
        SpikeS1Billing.connect(this)
        if (savedInstanceState == null) SpikeS1Sound.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SpikeS1Log.log("activity: onNewIntent fromAlarm=${intent.fromAlarm()} ${lockState()}")
        if (intent.fromAlarm()) SpikeS1Sound.start(this)
    }

    override fun onResume() {
        super.onResume()
        SpikeS1Log.log("activity: onResume ${lockState()}")
        SpikeS1Log.onChange = { render() }
        main.post(ticker)
    }

    override fun onPause() {
        SpikeS1Log.log("activity: onPause ${lockState()}")
        main.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onStop() {
        SpikeS1Log.log("activity: onStop ${lockState()}")
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        SpikeS1Log.log("activity: windowFocus=$hasFocus ${lockState()}")
    }

    override fun onDestroy() {
        SpikeS1Log.log("activity: onDestroy isFinishing=$isFinishing")
        if (SpikeS1Log.onChange != null) SpikeS1Log.onChange = null
        super.onDestroy()
    }

    // ---- Buttons ----------------------------------------------------------------------------------------------------

    private fun pay(viaUnlock: Boolean) {
        payCount++
        SpikeS1Log.payAt = SpikeS1Log.now()
        val locked = keyguard.isKeyguardLocked
        val mode = if (viaUnlock) "PAY" else "PAY_WITHOUT_UNLOCK"
        val pay = "#$payCount/$mode/${if (locked) "locked" else "unlocked"}"
        val profileId = UUID.randomUUID().toString()
        SpikeS1Log.log(
            "==== $pay tapped: ${lockState()} network=${network()} sound=${SpikeS1Sound.isPlaying} " +
                "alarmVolume=${SpikeS1Sound.alarmVolume()} billing=${SpikeS1Billing.connection}",
        )
        SpikeS1Log.log("$pay obfuscatedProfileId(new random UUID)=$profileId")
        if (viaUnlock && locked) {
            SpikeS1Log.log("$pay requestDismissKeyguard")
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        SpikeS1Log.log("$pay onDismissSucceeded ${lockState()}")
                        SpikeS1Billing.launch(this@SpikeS1Activity, pay, profileId, accountId())
                    }

                    override fun onDismissCancelled() {
                        SpikeS1Log.log("$pay onDismissCancelled ${lockState()} (no billing flow launched)")
                    }

                    override fun onDismissError() {
                        SpikeS1Log.log("$pay onDismissError ${lockState()} (no billing flow launched)")
                    }
                },
            )
        } else {
            SpikeS1Billing.launch(this, pay, profileId, accountId())
        }
    }

    private fun ringIn15s() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            SpikeS1Log.log("alarm: asking for POST_NOTIFICATIONS (needed for the full-screen notification); tap again after")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        SpikeS1Sound.stop()
        SpikeS1AlarmReceiver.cancelNotification(this)
        SpikeS1AlarmReceiver.scheduleIn15s(this)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        SpikeS1Log.log("permission: ${permissions.joinToString()} granted=$granted")
    }

    private fun stopSound() {
        SpikeS1Sound.stop()
        SpikeS1AlarmReceiver.cancelNotification(this)
    }

    private fun shareLog() {
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Spike S1 log ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}")
                .putExtra(Intent.EXTRA_TEXT, deviceLine() + "\n" + SpikeS1Log.all())
        startActivity(Intent.createChooser(send, "Share Spike S1 log"))
    }

    // ---- State ------------------------------------------------------------------------------------------------------

    private fun render() {
        if (!::status.isInitialized) return
        val d = SpikeS1Billing.durations.sorted()
        val timing =
            if (d.isEmpty()) {
                "no results yet"
            } else {
                "n=${d.size} median=${d[d.size / 2]} ms max=${d.last()} ms"
            }
        status.text =
            listOf(
                "LOCKED: ${if (keyguard.isKeyguardLocked) "YES" else "no"} (secure=${keyguard.isDeviceSecure})",
                "Sound: ${if (SpikeS1Sound.isPlaying) "PLAYING" else "off"} alarm vol ${SpikeS1Sound.alarmVolume()}",
                "Billing: ${SpikeS1Billing.connection}",
                "Product: ${SpikeS1Billing.productStatus}",
                "Last result: ${SpikeS1Billing.lastResult}",
                "Since Pay: ${SpikeS1Log.sincePay()?.let { "$it ms" } ?: "-"}",
                "Pay→result: $timing",
                versionText(),
            ).joinToString("\n")
        logView.text = SpikeS1Log.newest(LOG_LINES_SHOWN).joinToString("\n")
    }

    private fun lockState(): String = "keyguardLocked=${keyguard.isKeyguardLocked} deviceLocked=${keyguard.isDeviceLocked}"

    private fun network(): String =
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "NONE"
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> "validated"
                else -> "connected-not-validated"
            }
        } catch (e: SecurityException) {
            "unknown (${e.message})"
        }

    private fun accountId(): String {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACCOUNT, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ACCOUNT, it).apply()
            SpikeS1Log.log("obfuscatedAccountId (new install id)=$it")
        }
    }

    private fun versionText(): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        return "v${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }

    private fun deviceLine(): String =
        "device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
            "build=${Build.DISPLAY} app=${versionText()}"

    private fun Intent?.fromAlarm(): Boolean = this?.getBooleanExtra(EXTRA_FROM_ALARM, false) == true

    // ---- Layout -----------------------------------------------------------------------------------------------------

    private fun buildLayout(): ScrollView {
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(PADDING_DP)
                setPadding(pad, pad * 2, pad, pad)
            }
        status =
            TextView(this).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, STATUS_TEXT_SP)
                setTypeface(typeface, Typeface.BOLD)
            }
        column.addView(status)
        column.addView(row(button("Pay") { pay(viaUnlock = true) }, button("Pay without unlock") { pay(viaUnlock = false) }))
        column.addView(row(button("Stop sound") { stopSound() }, button("Start sound") { SpikeS1Sound.start(this) }))
        column.addView(
            row(button("Query purchases") { SpikeS1Billing.queryPurchases() }, button("Consume all") { SpikeS1Billing.consumeAll() }),
        )
        column.addView(row(button("Ring in 15 s") { ringIn15s() }, button("Reconnect billing") { SpikeS1Billing.connect(this) }))
        column.addView(row(button("Share log") { shareLog() }, button("Clear log") { SpikeS1Log.clear() }))
        logView =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, LOG_TEXT_SP)
                setTextIsSelectable(true)
            }
        column.addView(logView)
        SpikeS1Log.log("activity: ${deviceLine()}")
        return ScrollView(this).apply { addView(column) }
    }

    private fun row(vararg buttons: Button): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }

    private fun button(
        label: String,
        onClick: () -> Unit,
    ): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            minHeight = dp(BUTTON_MIN_HEIGHT_DP)
            setOnClickListener {
                SpikeS1Log.log("button: $label")
                onClick()
                render()
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_FROM_ALARM = "com.yawnandpawn.app.spike.FROM_ALARM"
        private const val PREFS = "spike_s1"
        private const val KEY_ACCOUNT = "obfuscated_account_id"
        private const val REQUEST_NOTIFICATIONS = 51
        private const val TICK_MS = 500L
        private const val LOG_LINES_SHOWN = 80
        private const val PADDING_DP = 16
        private const val BUTTON_MIN_HEIGHT_DP = 64
        private const val STATUS_TEXT_SP = 22f
        private const val LOG_TEXT_SP = 11f
    }
}
