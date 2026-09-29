package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException

@HiltWorker
class CloudSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: CloudSyncManager
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!syncManager.isLoggedIn()) return Result.success()
        return syncManager.syncNow()
            .fold(
                onSuccess = { Result.success() },
                onFailure = { error ->
                    when {
                        error is java.io.IOException -> Result.retry()
                        error is HttpException && (
                            error.code() == 408 ||
                            error.code() == 429 ||
                            error.code() in 500..599
                        ) -> Result.retry()
                        else -> Result.failure()
                    }
                }
            )
    }

    companion object {
        private const val IMMEDIATE_WORK_NAME = "cloud_sync_immediate"

        /**
         * Queue one cloud sync as soon as network is available. KEEP
         * coalesces boot, app-start and package-update triggers.
         */
        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}