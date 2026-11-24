package com.shaowenfu.bookkeeper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.shaowenfu.bookkeeper.health.HealthCheckWorker
import com.shaowenfu.bookkeeper.health.KeepAliveService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BookkeeperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createChannels()
        HealthCheckWorker.schedule(this)
        KeepAliveService.start(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_KEEPALIVE, "常驻监听状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "保持通知采样服务在后台运行"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "监听异常告警", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "通知使用权被关闭或监听断开时提醒"
            }
        )
    }

    companion object {
        const val CHANNEL_KEEPALIVE = "keepalive"
        const val CHANNEL_ALERT = "alert"

        /** 进程级协程作用域：监听服务被销毁时，正在写库的任务也能写完 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
