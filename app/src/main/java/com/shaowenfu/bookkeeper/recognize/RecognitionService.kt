package com.shaowenfu.bookkeeper.recognize

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shaowenfu.bookkeeper.BookkeeperApp
import com.shaowenfu.bookkeeper.R
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 识别期间的短时前台服务：防止 vivo 在等模型返回的几秒里把进程冻住（M0′ 实测丢过一次结果）。
 * 识别完立即退出，不常驻；最多跑 3 分钟，超时的交给 WorkManager。
 */
class RecognitionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val again = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { goForeground() }
        if (job?.isActive == true) {
            again.set(true)
        } else {
            job = scope.launch {
                val guard = launch { delay(MAX_RUN_MS); job?.cancel() }
                try {
                    do {
                        again.set(false)
                        Recognizer.processAll(this@RecognitionService)
                    } while (again.get())
                } finally {
                    guard.cancel()
                    BookkeeperApp.scope.launch { RecognitionRunner.scheduleRetryIfNeeded(applicationContext) }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val n = recognizingNotification(this)
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }


    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ID = 7
        private const val MAX_RUN_MS = 3 * 60_000L

        fun recognizingNotification(context: Context): Notification =
            Notification.Builder(context, BookkeeperApp.CHANNEL_WORKING)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("正在识别付款信息…")
                .setOngoing(true)
                .build()
    }
}

/** 没法起前台服务时（后台启动受限）的兜底，以及失败后的延迟重试 */
class RecognitionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Recognizer.processAll(applicationContext)
        RecognitionRunner.scheduleRetryIfNeeded(applicationContext)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = RecognitionService.recognizingNotification(applicationContext)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(RecognitionService.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(RecognitionService.ID, n)
    }
}

object RecognitionRunner {
    private const val WORK_NOW = "recognize_now"
    private const val WORK_RETRY = "recognize_retry"

    /** 有新的待识别事件：优先起短时前台服务，起不来就用加急任务 */
    fun kick(context: Context) {
        val app = context.applicationContext
        try {
            app.startForegroundService(Intent(app, RecognitionService::class.java))
        } catch (e: Exception) {
            WorkManager.getInstance(app).enqueueUniqueWork(
                WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<RecognitionWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build(),
            )
        }
    }

    /** 还有失败待重试的：1 分钟后、有网络时再跑一次 */
    suspend fun scheduleRetryIfNeeded(context: Context) {
        val app = context.applicationContext
        if (Prefs(app).apiKey.isBlank()) return
        if (AppDatabase.get(app).rawEvents().todoCount() == 0) return
        WorkManager.getInstance(app).enqueueUniqueWork(
            WORK_RETRY, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RecognitionWorker>()
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }
}
