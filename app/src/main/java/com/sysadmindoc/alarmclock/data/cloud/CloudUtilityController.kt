package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import android.os.SystemClock
import com.sysadmindoc.alarmclock.ui.timer.*

/** A phone owns ringing. Cloud countdowns are displays, never alarm deadlines. */
internal class CloudUtilityController(private val context: Context) {
    private val store = TimerStore(context)
    private val journal = context.getSharedPreferences("cloud_utility_journal", Context.MODE_PRIVATE)

    fun apply(command: CloudUtilityCommand): Pair<String, String> = synchronized(LOCK) {
        val prior = journal.getString(command.commandId, null)
        if (prior != null) {
            if (prior.startsWith("applied:") || prior.startsWith("rejected:"))
                return@synchronized prior.substringBefore(":") to prior.substringAfter(":")
            return@synchronized "rejected" to "interrupted_check_phone"
        }
        if (journal.all.size >= 10000) return@synchronized "rejected" to "utility_journal_full"
        // Durable at-most-once claim before native effects. If the process dies
        // in between, reject redelivery instead of ringing a duplicate timer.
        check(journal.edit().putString(command.commandId, "claimed").commit())
        if (command.kind == "stopwatch") {
            val result = runCatching { applyStopwatch(command.action) }
            val status = if (result.isSuccess) "applied" else "rejected"
            val message = result.getOrElse { it.message ?: "stopwatch_action_failed" }
            check(journal.edit().putString(command.commandId, "$status:$message").commit())
            return@synchronized status to message
        }
        if (command.kind != "timer") return@synchronized "rejected" to "invalid_utility_kind"
        val now = SystemClock.elapsedRealtime()
        val result = runCatching {
            if (command.action == "start") {
                val seconds = (command.payload["seconds"] as? Number)?.toLong() ?: error("invalid_seconds")
                val record = store.startOrReuse(seconds, command.payload["label"] as? String ?: "", now).record
                TimerAlarmScheduler.schedule(context, record.id, record.endElapsedRealtime)
                TimerNotifications.postRunning(context, record)
                "timerId=${record.id}"
            } else {
                val id = (command.payload["timerId"] as? Number)?.toInt() ?: error("invalid_timer_id")
                val record = store.loadRecords(now).firstOrNull { it.id == id } ?: error("timer_not_found")
                when (command.action) {
                    "pause" -> {
                        require(record.state == TimerState.RUNNING) { "timer_not_running" }
                        store.upsert(record.copy(state=TimerState.PAUSED, endElapsedRealtime=0))
                        TimerAlarmScheduler.cancel(context, id);TimerNotifications.cancelTimer(context,id)
                    }
                    "resume" -> {
                        require(record.state == TimerState.PAUSED && record.remainingMillis > 0) { "timer_not_paused" }
                        val resumed = record.copy(state=TimerState.RUNNING,endElapsedRealtime=now+record.remainingMillis)
                        store.upsert(resumed);TimerAlarmScheduler.schedule(context,id,resumed.endElapsedRealtime)
                        TimerNotifications.postRunning(context,resumed)
                    }
                    "stop" -> {
                        store.remove(id);TimerAlarmScheduler.cancel(context,id)
                        TimerAlarmService.dismiss(context,id);TimerNotifications.cancelTimer(context,id)
                    }
                    else -> error("invalid_timer_action")
                }
                "timerId=$id"
            }
        }
        val status = if (result.isSuccess) "applied" else "rejected"
        val message = result.getOrElse { it.message ?: "native_action_failed" }
        check(journal.edit().putString(command.commandId, "$status:$message").commit())
        status to message
    }
    internal fun applyNotificationStopwatchAction(action: String): String = synchronized(LOCK) {
        require(action in listOf("pause", "resume", "lap"))
        applyStopwatch(action)
    }

    private fun applyStopwatch(action: String): String {
        val prefs = context.getSharedPreferences("stopwatch_state", Context.MODE_PRIVATE)
        val now = SystemClock.elapsedRealtime()
        val snapshot = stopwatchSnapshot(context)
        val state = snapshot["state"] as String
        var elapsed = snapshot["elapsedMillis"] as Long
        val laps = org.json.JSONArray(prefs.getString("laps", "[]"))
        var nextState = state
        when (action) {
            "start" -> { require(state == "IDLE") { "stopwatch_not_idle" }; elapsed=0;nextState="RUNNING" }
            "resume" -> { require(state == "PAUSED") { "stopwatch_not_paused" }; nextState="RUNNING" }
            "pause" -> { require(state == "RUNNING") { "stopwatch_not_running" }; nextState="PAUSED" }
            "reset" -> { nextState="IDLE";elapsed=0;while(laps.length()>0) laps.remove(laps.length()-1) }
            "lap" -> {
                require(state == "RUNNING") { "stopwatch_not_running" }
                require(laps.length()<1000) { "lap_limit_reached" }
                var previous = 0L
                for(i in 0 until laps.length()) previous=maxOf(previous,laps.getJSONObject(i).optLong("t"))
                laps.put(org.json.JSONObject().put("n",laps.length()+1).put("s",(elapsed-previous).coerceAtLeast(0)).put("t",elapsed))
            }
            else -> error("invalid_stopwatch_action")
        }
        val boot = runCatching { android.provider.Settings.Global.getLong(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        check(prefs.edit().putString("state",nextState).putLong("accumulated",elapsed)
            .putLong("startTime",now).putLong("bootCount",boot)
            .putLong("bootToken",System.currentTimeMillis()-now).putString("laps",laps.toString())
            .putLong("remoteRevision",prefs.getLong("remoteRevision",0)+1).commit())
        com.sysadmindoc.alarmclock.ui.stopwatch.StopwatchNotifications.refresh(context)
        return "stopwatch_${nextState.lowercase()}"
    }
    companion object {
        private val LOCK = NativeUtilityLock.monitor
        fun stopwatchSnapshot(context: Context): Map<String, Any?> = synchronized(LOCK) {
            val prefs = context.getSharedPreferences("stopwatch_state",Context.MODE_PRIVATE)
            var state = prefs.getString("state","IDLE") ?: "IDLE"
            var elapsed = prefs.getLong("accumulated",0).coerceAtLeast(0)
            val now = SystemClock.elapsedRealtime()
            val boot = runCatching { android.provider.Settings.Global.getLong(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
            val storedBoot = prefs.getLong("bootCount",-1)
            val sameBoot = if (boot>=0 && storedBoot>=0) boot==storedBoot else
                kotlin.math.abs((System.currentTimeMillis()-now)-prefs.getLong("bootToken",0))<=5000
            if(state=="RUNNING") {
                val delta=now-prefs.getLong("startTime",0)
                if(sameBoot && delta>=0) elapsed+=delta else state="PAUSED"
            }
            val laps=runCatching { org.json.JSONArray(prefs.getString("laps","[]")) }.getOrDefault(org.json.JSONArray())
            mapOf("state" to state,"elapsedMillis" to elapsed,"laps" to (0 until minOf(laps.length(),1000)).map { i ->
                val lap=laps.getJSONObject(i);mapOf("number" to lap.optInt("n"),"splitMillis" to lap.optLong("s"),"totalMillis" to lap.optLong("t"))
            })
        }
    }
}
