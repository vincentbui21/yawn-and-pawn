package com.yawnandpawn.app.android.reliability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.yawnandpawn.app.core.log.LogEvent
import com.yawnandpawn.app.core.log.Logger
import com.yawnandpawn.app.core.reliability.DeviceMaker
import com.yawnandpawn.app.core.reliability.ManufacturerSettings

/**
 * The makers' own settings screens for background apps (Story 5.5), in the order "Open settings" tries them. The names
 * are community-collected (for example the MIT AutoStarter library) and change between OS versions: none is verified
 * on a device except the Oppo A96 (Story 5.13). `docs/oem-guidance.md` lists them with their sources, and the
 * manifest's `<queries>` names every [packages] entry, without which API 30+ cannot resolve them.
 */
object OemIntents {
    private val COMPONENTS: Map<DeviceMaker, List<ComponentName>> =
        mapOf(
            DeviceMaker.Xiaomi to
                listOf(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"),
                ),
            DeviceMaker.Samsung to
                listOf(
                    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                ),
            DeviceMaker.Huawei to
                listOf(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                ),
            DeviceMaker.OppoRealme to
                listOf(
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                    ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                ),
            DeviceMaker.Vivo to
                listOf(
                    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                ),
            DeviceMaker.OnePlus to
                listOf(
                    ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                ),
        )

    /** [maker]'s settings screens, most likely first; none for [DeviceMaker.Other]. */
    fun components(maker: DeviceMaker): List<ComponentName> = COMPONENTS[maker].orEmpty()

    /** Every package a component lives in: the manifest's `<queries>` must name each. */
    val packages: Set<String> =
        COMPONENTS.values
            .flatten()
            .map { it.packageName }
            .toSet()
}

/**
 * The Android [ManufacturerSettings] (Story 5.5), opened from "Open settings" (a user tap, so never from the
 * background): the first of the maker's [OemIntents.components] this phone resolves (`resolveActivity`, never launched
 * otherwise), else the app's details page, else the system settings. Each starts as a new task; one that throws is
 * logged and the next is tried, so a phone with none of them logs it and never crashes.
 */
class AndroidManufacturerSettings(
    private val context: Context,
    private val logger: Logger,
) : ManufacturerSettings {
    override fun open(maker: DeviceMaker) {
        val opened = OemIntents.components(maker).any { component -> tryComponent(component) }
        if (!opened && !tryStart(appDetails())) tryStart(Intent(Settings.ACTION_SETTINGS))
    }

    /** Starts [component] only when this phone resolves it. */
    private fun tryComponent(component: ComponentName): Boolean {
        val intent = Intent().setComponent(component)
        return resolves(intent) && tryStart(intent)
    }

    // Intent.resolveActivity returns an explicit component unchecked: only the package manager knows if it exists.
    @Suppress("DEPRECATION", "TooGenericExceptionCaught") // The int-flags overload covers API 26+; an OEM lookup may throw.
    private fun resolves(intent: Intent): Boolean =
        try {
            context.packageManager.resolveActivity(intent, 0) != null
        } catch (e: RuntimeException) {
            logger.log(LogEvent.OperationFailed(RESOLVE, e::class.simpleName.orEmpty()))
            false
        }

    // An OEM screen may be unexported, missing or refuse the caller: ActivityNotFound, Security or anything else.
    @Suppress("TooGenericExceptionCaught")
    private fun tryStart(intent: Intent): Boolean =
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: RuntimeException) {
            logger.log(LogEvent.OperationFailed(OPEN, e::class.simpleName.orEmpty()))
            false
        }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    companion object {
        /** The log operation of a screen that could not be started. */
        const val OPEN = "open manufacturer settings"

        /** The log operation of a component lookup that threw. */
        const val RESOLVE = "resolve manufacturer settings"
    }
}
