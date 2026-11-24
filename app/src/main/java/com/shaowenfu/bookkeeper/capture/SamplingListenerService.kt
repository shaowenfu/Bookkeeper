package com.shaowenfu.bookkeeper.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.shaowenfu.bookkeeper.BookkeeperApp
import com.shaowenfu.bookkeeper.health.ListenerHealth
import kotlinx.coroutines.launch

/** 只记日志的通知监听器。不解析、不入账，只把白名单 App 的原始通知存下来。 */
class SamplingListenerService : NotificationListenerService() {
    private val scope = BookkeeperApp.scope

    override fun onListenerConnected() {
        super.onListenerConnected()
        ListenerHealth.onConnected(applicationContext)
        // 重连时补抓当前还挂着的通知，减少断线期间的遗漏（重复的会被去重）
        val active = try { activeNotifications } catch (e: Exception) { null }
        scope.launch {
            active?.forEach { sbn -> safeRecord(sbn, "snapshot") }
        }
    }

    override fun onListenerDisconnected() {
        ListenerHealth.onDisconnected(applicationContext)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        ListenerHealth.markAlive()
        scope.launch { safeRecord(sbn, "posted") }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        sbn ?: return
        scope.launch {
            try {
                SampleRecorder.recordRemoved(applicationContext, sbn, reason)
            } catch (e: Exception) {
                Log.w(TAG, "recordRemoved failed", e)
            }
        }
    }

    private suspend fun safeRecord(sbn: StatusBarNotification, source: String) {
        try {
            SampleRecorder.record(applicationContext, sbn, source)
        } catch (e: Exception) {
            Log.e(TAG, "record failed for ${sbn.packageName}", e)
        }
    }

    companion object {
        private const val TAG = "SamplingListener"
    }
}
