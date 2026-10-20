package org.brahmakumaris.trafficcontrol.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms every enabled alarm after a reboot, an app update
 * (MY_PACKAGE_REPLACED), a manual clock change, or a timezone change -
 * all of which otherwise leave AlarmManager with nothing scheduled.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler(context).rescheduleAll()
    }
}
