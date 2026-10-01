package com.sysadmindoc.alarmclock.worker

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.service.AlarmService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Re-registers enabled alarms after boot, package replacement, or clock
 * changes. Runs outside [android.content.BroadcastReceiver] so large alarm
 * libraries are not constrained by the broadcast ANR window.
 */
@HiltWorker
class BootRescheduleWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val alarmScheduler: AlarmScheduler
) : CoroutineWorker(context, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        AlarmService.createNotificationChannels(applicationContext)
        val notification = NotificationCompat.Builder(
            applicationContext,
            AlarmService.CHANNEL_UPCOMING
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.upcoming_notification_channel))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    override suspend fun doWork(): Result {
        val sourceAction = inputData.getString(KEY_SOURCE_ACTION).orEmpty()
        val forceRecalculate = inputData.getBoolean(KEY_FORCE_RECALCULATE, false)

        return try {
            val count = alarmScheduler.rescheduleAllInBatches(
                forceRecalculate = forceRecalculate,
                batchSize = RESCHEDULE_BATCH_SIZE
            )
            Log.i(TAG, "Rescheduled $count enabled alarms after $sourceAction")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reschedule alarms after $sourceAction", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "boot_reschedule_alarms"
        private const val NOTIFICATION_ID = 2001

        private const val TAG = "BootRescheduleWorker"
        private const val KEY_FORCE_RECALCULATE = "force_recalculate"
        private const val KEY_SOURCE_ACTION = "source_action"
        private const val MAX_ATTEMPTS = 3
        private const val RESCHEDULE_BATCH_SIZE = 25

        fun enqueue(context: Context, sourceAction: String, forceRecalculate: Boolean) {
            val data = Data.Builder()
                .putString(KEY_SOURCE_ACTION, sourceAction)
                .putBoolean(KEY_FORCE_RECALCULATE, forceRecalculate)
                .build()

            val request = OneTimeWorkRequestBuilder<BootRescheduleWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
