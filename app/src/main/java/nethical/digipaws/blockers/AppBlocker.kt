package nethical.digipaws.blockers

import android.os.SystemClock
import android.util.Log
import nethical.digipaws.ui.activity.TimedActionActivity
import nethical.digipaws.utils.DaySchedule
import java.util.Calendar

class AppBlocker:BaseBlocker() {

    // package-name -> end-time-in-millis
    private var cooldownAppsList:MutableMap<String,Long> = mutableMapOf()

    // package-name -> [(start-time, end-time, days), ...]
    private var cheatHours: MutableMap<String, List<DaySchedule.Range>> = mutableMapOf()

    var blockedAppsList = hashSetOf("")

    /**
     * Check if app needs to be blocked
     *
     * @param packageName
     * @return
     */
    fun doesAppNeedToBeBlocked(packageName: String): AppBlockerResult {

        if(cooldownAppsList.containsKey(packageName)){
            // check if app has surpassed the cooldown period
            if (cooldownAppsList[packageName]!! < SystemClock.uptimeMillis()){
                removeCooldownFrom(packageName)
                return AppBlockerResult(isBlocked = true)
            }

            // app is still under cooldown
            return AppBlockerResult(
                isBlocked = false,
                cooldownEndTime = cooldownAppsList[packageName]!!
            )
        }

        // check if app is under cheat-hours
        val endCheatMillis = getEndTimeInMillis(packageName)
        if (endCheatMillis != null) {
            return AppBlockerResult(isBlocked = false, cheatHoursEndTime = endCheatMillis)
        }

        if (blockedAppsList.contains(packageName)) {
            return AppBlockerResult(
                isBlocked = true
            )
        }
        return AppBlockerResult(isBlocked = false)
    }
    fun putCooldownTo(packageName: String, endTime: Long) {
        cooldownAppsList[packageName] = endTime
        Log.d("cooldownAppsList",cooldownAppsList.toString())
    }

    fun removeCooldownFrom(packageName: String) {
        cooldownAppsList.remove(packageName)
    }

    /**
     * Check if the package is currently under cheat hours.
     *
     * @param packageName The app package name.
     * @return Returns null if the app is not under cheat hours, or the timestamp (uptimeMillis) when it ends.
     */
    private fun getEndTimeInMillis(packageName: String): Long? {
        val ranges = cheatHours[packageName] ?: return null

        val now = Calendar.getInstance()
        val uptimeNow = SystemClock.uptimeMillis()

        ranges.forEach { range ->
            val minutesLeft = DaySchedule.minutesLeft(range, now) ?: return@forEach
            Log.d("AppBlocker", "$packageName cheat-hour ends after $minutesLeft minutes")
            return uptimeNow + (minutesLeft * 60 * 1000L)
        }
        return null
    }


    fun refreshCheatHoursData(cheatList: List<TimedActionActivity.AutoTimedActionItem>) {
        cheatHours.clear()
        cheatList.forEach { item ->
            val range = item.toRange()
            item.packages.forEach { packageName ->
                Log.d("AppBlocker", "added timed-action data for $packageName : $range")
                cheatHours[packageName] = (cheatHours[packageName] ?: emptyList()) + range
            }
        }
    }

    /**
     * App blocker check result
     *
     * @property isBlocked
     * @property cheatHoursEndTime specifies when cheat-hour ends. returns -1 if not in cheat-hour
     * @property cooldownEndTime specifies when cooldown ends. returns -1 if not in cooldown
     */
    data class AppBlockerResult(
        val isBlocked: Boolean,
        val cheatHoursEndTime: Long = -1L,
        val cooldownEndTime: Long = -1L
    )

}