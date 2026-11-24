package com.shaowenfu.bookkeeper.health

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.shaowenfu.bookkeeper.BookkeeperApp
import com.shaowenfu.bookkeeper.R
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 常驻前台服务：降低进程被杀的概率，并每 5 分钟做一次监听自检。 */
class KeepAliveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(this, todayCount = null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        if (loop?.isActive != true) {
            ListenerHealth.log(this, "keepalive_started")
            loop = scope.launch {
                while (isActive) {
                    ListenerHealth.check(this@KeepAliveService, "keepalive")
                    updateNotification()
                    delay(CHECK_INTERVAL_MS)
                }
            }
        } else {
            scope.launch { updateNotification() }
        }
        return START_STICKY
    }

    private suspend fun updateNotification() {
        val count = AppDatabase.get(this).samples().observeCountSince(startOfToday()).first()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(this, count))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L
        private const val TAG = "KeepAlive"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                // Android 12+ 不允许从后台启动前台服务；下次打开 App 或开机时会再启动
                Log.w(TAG, "cannot start keepalive now: ${e.javaClass.simpleName}")
            }
        }

        /** 状态变化时刷新常驻通知（服务已在运行时才生效） */
        fun refresh(context: Context) = start(context)

        private fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        private fun build(context: Context, todayCount: Int?): Notification {
            val granted = ListenerHealth.isPermissionGranted(context)
            val connected = ListenerHealth.connected.value
            val state = when {
                !granted -> "⚠ 未授权通知使用权"
                !connected -> "⚠ 监听未连接，正在重连"
                else -> "监听中"
            }
            val time = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())
            val text = buildString {
                append(state)
                if (todayCount != null) append(" · 今日样本 ").append(todayCount).append(" 条")
                append(" · 自检 ").append(time)
            }
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, BookkeeperApp.CHANNEL_KEEPALIVE)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("记账通知采样")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()
        }
    }
}
