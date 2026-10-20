package org.brahmakumaris.trafficcontrol.data

import org.brahmakumaris.trafficcontrol.R

/**
 * One fixed daily reminder: a time of day plus the track that plays at that time.
 * The id is stable across app versions - it is used as the AlarmManager request code
 * and as the SharedPreferences key suffix, so it must never change once shipped.
 */
data class ScheduleSlot(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val label: String,
    val rawResId: Int
)

object Schedule {
    val slots: List<ScheduleSlot> = listOf(
        ScheduleSlot(0, 3, 30, "Amrit Vela Hudh", R.raw.h_amritvelashudh),
        ScheduleSlot(1, 4, 0, "Shiv Ki Yaad", R.raw.h_shivkiyaad),
        ScheduleSlot(2, 5, 45, "Satya Hi Shiv Hei", R.raw.h_satyahishivhai),
        ScheduleSlot(3, 7, 0, "Antarman Me Jyoti", R.raw.h_antarmanme),
        ScheduleSlot(4, 10, 30, "Nit Yad Karo Man Se", R.raw.h_nityaadkaro),
        ScheduleSlot(5, 12, 0, "Shiv Pita Ko Yaad Karo", R.raw.h_shivpitakoaab),
        ScheduleSlot(6, 17, 30, "Yogi Bano Gyani Bano", R.raw.h_yogibanogyani),
        ScheduleSlot(7, 19, 30, "Shiv Ki Yad Rahegi", R.raw.h_shivkiyaad),
        ScheduleSlot(8, 21, 30, "Andhakar Man Ka Mite", R.raw.h_andhakar_man_ka_mite)
    )

    fun byId(id: Int): ScheduleSlot? = slots.firstOrNull { it.id == id }
}
