package org.brahmakumaris.trafficcontrol.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Standard Android's "ignore battery optimizations" is not enough on most
 * Chinese-ROM and some Korean/US OEM skins - they ship a SECOND, undocumented
 * battery manager (MIUI Autostart, Samsung Device Care sleeping-apps list,
 * ColorOS/Oppo Startup Manager, etc.) that can still kill a whitelisted app.
 * These component names are the commonly used, publicly documented ones
 * (the same list maintained by community resources such as
 * dontkillmyapp.com). None of them are guaranteed to exist on every build of
 * every ROM, so every attempt falls back to the generic per-app settings
 * screen, which always exists.
 */
object OemSettings {

    fun manufacturerLabel(): String =
        Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    fun openManufacturerSettings(context: Context) {
        val candidates = intentsFor(Build.MANUFACTURER.lowercase())
        for (intent in candidates) {
            if (tryStart(context, intent)) return
        }
        // Fallback: this always exists on every Android device.
        tryStart(context, appDetailsIntent(context))
    }

    private fun intentsFor(manufacturer: String): List<Intent> = when {
        manufacturer.contains("xiaomi") -> listOf(
            Intent().setComponent(
                android.content.ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )
        )

        manufacturer.contains("samsung") -> listOf(
            Intent().setComponent(
                android.content.ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            ),
            Intent("com.samsung.android.sm.ACTION_BATTERY")
        )

        manufacturer.contains("oppo") -> listOf(
            Intent().setComponent(
                android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )
            ),
            Intent().setComponent(
                android.content.ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )
            )
        )

        manufacturer.contains("vivo") -> listOf(
            Intent().setComponent(
                android.content.ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )
            )
        )

        manufacturer.contains("oneplus") -> listOf(
            Intent().setComponent(
                android.content.ComponentName(
                    "com.oneplus.security",
                    "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            )
        )

        manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
            Intent().setComponent(
                android.content.ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )
            )
        )

        manufacturer.contains("letv") -> listOf(
            Intent().setComponent(
                android.content.ComponentName(
                    "com.letv.android.letvsafe",
                    "com.letv.android.letvsafe.AutobootManageActivity"
                )
            )
        )

        manufacturer.contains("asus") -> listOf(
            Intent().setComponent(
                android.content.ComponentName(
                    "com.asus.mobilemanager",
                    "com.asus.mobilemanager.autostart.AutoStartActivity"
                )
            )
        )

        // Motorola, stock/Pixel-like Android, and anything unrecognised:
        // there is no separate OEM manager, the standard app-details /
        // battery screen (opened by the fallback) is the right place.
        else -> emptyList()
    }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    private fun tryStart(context: Context, intent: Intent): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
