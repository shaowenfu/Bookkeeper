package com.shaowenfu.bookkeeper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 截图一键记账。没有常驻后台服务：只在长按音量键、点磁贴或分享截图时干活，
 * 识别的那几秒用短时前台服务保活，识别完就退出。
 */
class BookkeeperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LEDGER, "记账结果", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "每次识别后显示记了哪笔，可以直接撤销"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WORKING, "正在识别", NotificationManager.IMPORTANCE_MIN).apply {
                description = "识别期间的几秒内显示，识别完自动消失"
                setShowBadge(false)
            }
        )
        // 探针阶段的旧频道
        nm.deleteNotificationChannel("probe_result")
    }

    companion object {
        const val CHANNEL_LEDGER = "ledger"
        const val CHANNEL_WORKING = "working"

        /** 进程级协程作用域：触发入口结束后，写库等收尾工作也能完成 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
