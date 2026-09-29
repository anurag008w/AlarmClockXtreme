package com.sysadmindoc.alarmclock.data.cloud

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
                    if (error is java.io.IOException) Result.retry() else Result.failure()
                }
            )
    }
}