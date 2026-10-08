package com.sysadmindoc.alarmclock.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.platform.ExternalAlarmAck
import com.sysadmindoc.alarmclock.platform.ExternalAlertStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.util.Locale

/**
 * Lets the paired app (Divya) list alarms and change them: set_time, enable,
 * disable, create. Exported, so everything is checked: the pairing code, the
 * separate "remote alarm changes" switch (off by default), one change per
 * 5 seconds, and exactly one matching alarm. No delete, no sound changes.
 * Each change posts a notification with an Undo button and is reported back
 * to the paired app with the new next-trigger time.
 */
class ExternalAlarmReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_EXTERNAL_ALARM) return
        val requestId = intent.getStringExtra("request_id").orEmpty().trim().take(64)
        val op = intent.getStringExtra("op").orEmpty().trim().lowercase(Locale.ROOT)
        if (requestId.isEmpty()) return
        if (!ExternalAlertStore.tokenMatches(context, intent.getStringExtra("token"))) {
            ExternalAlarmAck.rejected(context, requestId, op, "bad_token")
            return
        }
        if (!ExternalAlertStore.isAlarmControlEnabled(context)) {
            ExternalAlarmAck.rejected(context, requestId, op, "disabled")
            return
        }
        if (op !in OPS) {
            ExternalAlarmAck.rejected(context, requestId, op, "invalid")
            return
        }
        if (op != "list" && !ExternalAlertStore.tryAcceptAlarmChangeNow(context)) {
            ExternalAlarmAck.rejected(context, requestId, op, "rate_limited")
            return
        }
        val request = Request(
            requestId = requestId,
            op = op,
            hasAlarmId = intent.hasExtra("alarm_id"),
            alarmId = intent.getLongExtra("alarm_id", -1L),
            label = intent.getStringExtra("label").orEmpty().trim().take(120),
            hour = intent.getIntExtra("hour", -1),
            minute = intent.getIntExtra("minute", -1),
            days = intent.getStringExtra("days").orEmpty()
        )
        val pending = goAsync()
        scope.launch {
            try {
                withTimeout(8_000L) { handle(context.applicationContext, request) }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Timed out handling ${request.op}", e)
                ExternalAlarmAck.rejected(context, requestId, op, "error")
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling ${request.op}", e)
                ExternalAlarmAck.rejected(context, requestId, op, "error")
            } finally {
                pending.finish()
            }
        }
    }

    private class Request(
        val requestId: String,
        val op: String,
        val hasAlarmId: Boolean,
        val alarmId: Long,
        val label: String,
        val hour: Int,
        val minute: Int,
        val days: String
    )

    private suspend fun handle(context: Context, r: Request) {
        val ep = EntryPointAccessors.fromApplication(context, AlarmClockApp.AppEntryPoint::class.java)
        val repo = ep.alarmRepository()
        val scheduler = ep.alarmScheduler()
        val calculator = ep.nextAlarmCalculator()

        if (r.op == "list") {
            val all = repo.getAll().sortedWith(
                compareByDescending<Alarm> { it.isEnabled }
                    .thenBy { if (it.nextTriggerTime > 0) it.nextTriggerTime else Long.MAX_VALUE }
            ).take(MAX_LIST)
            val arr = JSONArray()
            all.forEach { arr.put(toJson(it)) }
            ExternalAlarmAck.send(
                context, r.requestId, r.op, ExternalAlarmAck.APPLIED,
                Bundle().apply { putString("alarms_json", arr.toString()) }
            )
            return
        }

        if (r.op == "create") {
            if (r.hour !in 0..23 || r.minute !in 0..59) {
                ExternalAlarmAck.rejected(context, r.requestId, r.op, "invalid"); return
            }
            val days = parseDays(r.days)
            if (days == null) {
                ExternalAlarmAck.rejected(context, r.requestId, r.op, "invalid"); return
            }
            val draft = Alarm(
                hour = r.hour,
                minute = r.minute,
                label = r.label,
                repeatDays = days,
                isEnabled = true
            ).sanitized()
            val id = repo.save(draft)
            val withId = draft.copy(id = id)
            val trigger = calculator.calculate(withId)
            repo.updateNextTrigger(id, trigger)
            scheduler.schedule(withId.copy(nextTriggerTime = trigger))
            val saved = repo.getById(id)
            reportApplied(context, r, saved ?: withId, prevHour = -1, prevMinute = -1, prevEnabled = false, created = true)
            return
        }

        // set_time / enable / disable need exactly one target alarm.
        val all = repo.getAll()
        val matches = when {
            r.hasAlarmId -> all.filter { it.id == r.alarmId }
            r.label.isNotEmpty() -> all.filter { it.label.contains(r.label, ignoreCase = true) }
            else -> emptyList()
        }
        if (matches.isEmpty()) {
            ExternalAlarmAck.rejected(context, r.requestId, r.op, "not_found"); return
        }
        if (matches.size > 1) {
            ExternalAlarmAck.rejected(context, r.requestId, r.op, "ambiguous"); return
        }
        val alarm = matches.first()
        val prevHour = alarm.hour
        val prevMinute = alarm.minute
        val prevEnabled = alarm.isEnabled

        when (r.op) {
            "set_time" -> {
                if (r.hour !in 0..23 || r.minute !in 0..59) {
                    ExternalAlarmAck.rejected(context, r.requestId, r.op, "invalid", alarm.id); return
                }
                val updated = alarm.copy(hour = r.hour, minute = r.minute)
                repo.update(updated)
                if (updated.isEnabled) scheduler.schedule(updated)
            }
            "enable" -> {
                if (!alarm.isEnabled) {
                    val trigger = calculator.calculate(alarm)
                    repo.setEnabled(alarm.id, enabled = true, nextTrigger = trigger)
                    scheduler.schedule(alarm.copy(isEnabled = true, nextTriggerTime = trigger))
                }
            }
            "disable" -> {
                if (alarm.isEnabled) {
                    val lockMinutes = ep.preferencesManager().getCachedSettings().cancellationLockMinutes
                    if (lockMinutes > 0 && alarm.nextTriggerTime > 0) {
                        val minutesUntil = (alarm.nextTriggerTime - System.currentTimeMillis()) / 60_000
                        if (minutesUntil in 0..lockMinutes.toLong()) {
                            ExternalAlarmAck.rejected(context, r.requestId, r.op, "locked", alarm.id); return
                        }
                    }
                    repo.setEnabled(alarm.id, enabled = false, nextTrigger = 0)
                    scheduler.cancel(alarm.id)
                    scheduler.syncBedtimeDndRule()
                }
            }
        }
        val saved = repo.getById(alarm.id)
        reportApplied(context, r, saved ?: alarm, prevHour, prevMinute, prevEnabled, created = false)
    }

    private fun reportApplied(
        context: Context,
        r: Request,
        alarm: Alarm,
        prevHour: Int,
        prevMinute: Int,
        prevEnabled: Boolean,
        created: Boolean
    ) {
        ExternalAlarmAck.send(
            context, r.requestId, r.op, ExternalAlarmAck.APPLIED,
            Bundle().apply {
                putLong("alarm_id", alarm.id)
                putInt("hour", alarm.hour)
                putInt("minute", alarm.minute)
                putString("label", alarm.label)
                putBoolean("enabled", alarm.isEnabled)
                putLong("next_trigger_ms", if (alarm.isEnabled) alarm.nextTriggerTime else 0L)
                if (!created) {
                    putInt("previous_hour", prevHour)
                    putInt("previous_minute", prevMinute)
                    putBoolean("previous_enabled", prevEnabled)
                }
            }
        )
        runCatching {
            postChangeNotice(context, r.op, alarm, prevHour, prevMinute, prevEnabled, created)
        }.onFailure { Log.w(TAG, "Could not post change notice", it) }
    }

    private fun postChangeNotice(
        context: Context,
        op: String,
        alarm: Alarm,
        prevHour: Int,
        prevMinute: Int,
        prevEnabled: Boolean,
        created: Boolean
    ) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.ext_alarm_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
        val name = alarm.label.ifBlank { context.getString(R.string.ext_alarm_unnamed) }
        val now = fmt(alarm.hour, alarm.minute)
        val was = fmt(prevHour, prevMinute)
        val text = when {
            created -> context.getString(R.string.ext_alarm_notice_created, name, now)
            op == "set_time" -> context.getString(R.string.ext_alarm_notice_moved, name, was, now)
            op == "enable" -> context.getString(R.string.ext_alarm_notice_enabled, name, now)
            else -> context.getString(R.string.ext_alarm_notice_disabled, name, now)
        }
        val undo = Intent(context, ExternalAlarmUndoReceiver::class.java)
            .setAction(ExternalAlarmUndoReceiver.ACTION_UNDO)
            .putExtra(ExternalAlarmUndoReceiver.EXTRA_ALARM_ID, alarm.id)
            .putExtra(ExternalAlarmUndoReceiver.EXTRA_HOUR, prevHour)
            .putExtra(ExternalAlarmUndoReceiver.EXTRA_MINUTE, prevMinute)
            .putExtra(ExternalAlarmUndoReceiver.EXTRA_ENABLED, prevEnabled)
            .putExtra(ExternalAlarmUndoReceiver.EXTRA_CREATED, created)
        val noticeId = NOTICE_BASE + (alarm.id % 1000).toInt()
        val undoPi = PendingIntent.getBroadcast(
            context, noticeId, undo,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(context.getString(R.string.ext_alarm_notice_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .addAction(R.drawable.ic_alarm, context.getString(R.string.ext_alarm_undo), undoPi)
            .build()
        nm.notify(noticeId, notification)
    }

    private fun toJson(a: Alarm): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("hour", a.hour)
        put("minute", a.minute)
        put("label", a.label)
        put("enabled", a.isEnabled)
        put("days", JSONArray(a.repeatDays.map { it.value }.sorted()))
        put("next_trigger_ms", if (a.isEnabled) a.nextTriggerTime else 0L)
    }

    private fun parseDays(csv: String): Set<DayOfWeek>? {
        if (csv.isBlank()) return emptySet()
        val out = mutableSetOf<DayOfWeek>()
        for (part in csv.split(',')) {
            val n = part.trim().toIntOrNull() ?: return null
            if (n !in 1..7) return null
            out.add(DayOfWeek.of(n))
        }
        return out
    }

    companion object {
        private const val TAG = "ExternalAlarmReceiver"
        const val ACTION_EXTERNAL_ALARM = "com.alarmclockxtreme.action.EXTERNAL_ALARM"
        const val CHANNEL_ID = "external_alarm_changes"
        private const val NOTICE_BASE = 6_700
        private const val MAX_LIST = 50
        private val OPS = setOf("list", "set_time", "enable", "disable", "create")

        internal fun fmt(hour: Int, minute: Int): String =
            if (hour < 0 || minute < 0) "--:--" else String.format(Locale.US, "%02d:%02d", hour, minute)
    }
}
