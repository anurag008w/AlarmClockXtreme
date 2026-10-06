package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository
import com.sysadmindoc.alarmclock.data.local.entity.AlarmEvent
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import java.time.Instant
import java.time.ZoneId

/** Skip only the exact phone-owned occurrence the user reviewed. Durable claim prevents replay. */
internal class CloudAlarmCommandController(private val context: Context, private val repository: AlarmRepository, private val history: AlarmEventRepository, private val scheduler: AlarmScheduler, private val calculator: NextAlarmCalculator) {
 suspend fun apply(command: CloudUtilityCommand, mapping: Map<String,Long>): Pair<String,String> {
  val journal=context.getSharedPreferences("cloud_alarm_command_journal",Context.MODE_PRIVATE)
  journal.getString(command.commandId,null)?.let { prior ->
   return if(prior=="claimed") "rejected" to "interrupted_check_phone" else prior.substringBefore(":") to prior.substringAfter(":")
  }
  if(journal.all.size>=10000)return "rejected" to "alarm_command_journal_full"
  check(journal.edit().putString(command.commandId,"claimed").commit())
  val result=runCatching {
   require(command.kind=="alarm" && command.action=="skip-next") { "invalid_alarm_action" }
   val id=mapping[command.payload["cloudAlarmId"] as? String] ?: error("alarm_not_mapped")
   val expected=(command.payload["expectedNextTriggerTime"] as? Number)?.toLong() ?: error("missing_occurrence")
   val alarm=repository.getById(id) ?: error("alarm_not_found")
   require(alarm.isEnabled && alarm.isRecurringSchedule && expected>System.currentTimeMillis()+60000 && alarm.nextTriggerTime==expected) { "phone_occurrence_changed_or_too_close" }
   // Avoid targeting a smart-wake window that may already be firing.
   require(com.sysadmindoc.alarmclock.service.AlarmService.activeAlarmId != id) { "alarm_currently_firing" }
   require(!alarm.smartAlarmEnabled) { "smart_alarm_skip_requires_phone" }
   require(android.os.Build.VERSION.SDK_INT < 31 || (context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()) { "exact_alarm_permission_required" }
   val after=calculator.calculate(alarm,Instant.ofEpochMilli(expected+60000).atZone(ZoneId.systemDefault()))
   require(after>expected) { "no_later_occurrence" }
   val current=repository.getById(id)
   require(current==alarm) { "phone_alarm_changed" }
   require(repository.advanceExactAlarmIfUnchanged(alarm,after)) { "phone_occurrence_changed" }
   val advanced=repository.getById(id)
   require(advanced==alarm.copy(nextTriggerTime=after)) { "phone_alarm_changed_after_compare_check_phone" }
   scheduler.cancel(id)
   check(scheduler.scheduleAt(alarm.copy(nextTriggerTime=after),after)) { "reschedule_failed_check_phone" }
   val finalAlarm=repository.getById(id)
   if(finalAlarm!=alarm.copy(nextTriggerTime=after)) {
     if(finalAlarm==null)scheduler.cancel(id) else scheduler.schedule(finalAlarm)
     error("concurrent_phone_edit_reconciled_check_phone")
   }
   val now=System.currentTimeMillis()
   history.record(AlarmEvent(alarmId=id,alarmLabel=alarm.label,scheduledTime=expected,firedAt=now,action=AlarmEvent.ACTION_SKIPPED,actionAt=now,dayOfWeek=Instant.ofEpochMilli(expected).atZone(ZoneId.systemDefault()).dayOfWeek.value))
   "skipped=$expected,next=$after"
  }
  val status=if(result.isSuccess) "applied" else "rejected"
  val message=result.getOrElse { it.message ?: "skip_failed_check_phone" }
  check(journal.edit().putString(command.commandId,"$status:$message").commit())
  return status to message
 }
}
