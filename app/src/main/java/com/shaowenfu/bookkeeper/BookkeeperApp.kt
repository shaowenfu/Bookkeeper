package com.shaowenfu.bookkeeper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * M0′ 读屏探针：不常驻后台。只有在你点无障碍按钮、磁贴或分享截图时才干活。
 */
class BookkeeperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "识别结果", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "每次采样后显示识别出的金额和耗时，方便当场核对"
            }
        )
    }

    companion object {
        const val CHANNEL_RESULT = "probe_result"

        /** 进程级协程作用域：触发入口（无障碍、磁贴、分享页）结束后，识别请求也能跑完 */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
