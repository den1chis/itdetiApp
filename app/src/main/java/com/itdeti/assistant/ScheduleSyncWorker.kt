package com.itdeti.assistant

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScheduleSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(
    context,
    workerParams
) {

    override suspend fun doWork(): Result {

        return try {

            val events = withContext(Dispatchers.IO) {
                ItdetiApi.syncUpcoming(days = 7)
            }

            ReminderScheduler.synchronize(
                applicationContext,
                events
            )

            Result.success()

        } catch (e: Exception) {

            Result.retry()
        }
    }
}