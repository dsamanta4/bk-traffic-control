package org.brahmakumaris.trafficcontrol.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.brahmakumaris.trafficcontrol.BuildConfig
import org.brahmakumaris.trafficcontrol.R
import org.brahmakumaris.trafficcontrol.TrafficControlApp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * This app isn't on the Play Store, so there's no auto-update path - without
 * this, a fix shipped later would only ever reach a phone if someone
 * remembers to go find the new APK and reinstall it by hand. Checks the
 * GitHub repo's latest release once a day (on app open) and, if it's newer
 * than what's installed, puts a single notification in front of the user
 * that opens the release page to download it. Never downloads or installs
 * anything itself - that still goes through the same manual "tap the APK to
 * install" flow the user already knows from the first install, just as
 * close to one-tap as a sideloaded app can get.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val PREFS_NAME = "traffic_control_update"
    private const val KEY_LAST_CHECK = "last_check_millis"
    private const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
    private const val RELEASES_URL = "https://api.github.com/repos/dsamanta4/bk-traffic-control/releases/latest"
    private const val NOTIFICATION_ID = 44

    fun checkOncePerDay(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MILLIS) return

        Thread {
            try {
                val (latestVersion, releaseUrl) = fetchLatestRelease() ?: return@Thread
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

                if (isNewer(latestVersion, BuildConfig.VERSION_NAME)) {
                    notifyUpdateAvailable(context.applicationContext, latestVersion, releaseUrl)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                // Don't update the timestamp on failure, so a flaky connection just retries next launch.
            }
        }.start()
    }

    private fun fetchLatestRelease(): Pair<String, String>? {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.use { it.readBytes().decodeToString() }
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            val url = json.optString("html_url")
            if (tag.isBlank() || url.isBlank()) return null
            return tag to url
        } finally {
            connection.disconnect()
        }
    }

    /** Simple dotted-numeric version compare ("1.2" > "1.10" handled correctly, unlike string compare). */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val l = local.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val rv = r.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun notifyUpdateAvailable(context: Context, version: String, releaseUrl: String) {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, TrafficControlApp.CHANNEL_SERVICE)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(context.getString(R.string.update_available_text, version))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted - nothing more to do.
        }
    }
}
