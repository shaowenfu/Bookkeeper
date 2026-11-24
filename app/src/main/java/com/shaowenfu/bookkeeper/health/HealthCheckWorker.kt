package com.shaowenfu.bookkeeper.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** 前台服务之外的第二道保险：每 15 分钟自检一次，前台服务被杀时也能告警。 */
class HealthCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ListenerHealth.check(applicationContext, "worker")
        KeepAliveService.start(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<HealthCheckWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("listener_health", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
