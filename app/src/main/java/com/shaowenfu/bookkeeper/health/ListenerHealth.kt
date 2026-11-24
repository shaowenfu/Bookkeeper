package com.shaowenfu.bookkeeper.health

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.shaowenfu.bookkeeper.BookkeeperApp
import com.shaowenfu.bookkeeper.R
import com.shaowenfu.bookkeeper.capture.SamplingListenerService
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.ListenerEvent
import com.shaowenfu.bookkeeper.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 监听器存活自检：权限是否还在、服务是否连着，断了就重连并告警。 */
object ListenerHealth {
    private const val PREFS = "health"
    private const val ALERT_ID = 2001
    /** 断开超过这个时间仍未恢复就弹告警（重连通常几秒内完成） */
    private const val ALERT_AFTER_MS = 2 * 60 * 1000L

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private val _lastCheckAt = MutableStateFlow(0L)
    val lastCheckAt: StateFlow<Long> = _lastCheckAt

    @Volatile var lastNotificationAt: Long = 0L
        private set

    fun markAlive() {
        lastNotificationAt = System.currentTimeMillis()
    }

    fun component(context: Context) = ComponentName(context, SamplingListenerService::class.java)

    fun isPermissionGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun onConnected(context: Context) {
        _connected.value = true
        prefs(context).edit().remove("disconnected_since").apply()
        log(context, "connected")
        cancelAlert(context)
        KeepAliveService.refresh(context)
    }

    fun onDisconnected(context: Context) {
        _connected.value = false
        prefs(context).edit().putLong("disconnected_since", System.currentTimeMillis()).apply()
        log(context, "disconnected")
        KeepAliveService.refresh(context)
    }

    /** 由前台服务（每 5 分钟）、WorkManager（每 15 分钟）和打开 App 时调用 */
    fun check(context: Context, origin: String) {
        val now = System.currentTimeMillis()
        _lastCheckAt.value = now
        val p = prefs(context)
        val granted = isPermissionGranted(context)
        val wasGranted = p.getBoolean("last_granted", true)
        if (granted != wasGranted) {
            p.edit().putBoolean("last_granted", granted).apply()
            log(context, if (granted) "permission_ok" else "permission_lost", origin)
        }

        if (!granted) {
            postAlert(
                context,
                "通知使用权已关闭",
                "记账采样无法收到通知，点此重新授权。",
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            )
            return
        }

        if (_connected.value) {
            cancelAlert(context)
            return
        }

        val since = p.getLong("disconnected_since", 0L).takeIf { it > 0 } ?: now.also {
            p.edit().putLong("disconnected_since", it).apply()
        }
        NotificationListenerService.requestRebind(component(context))
        log(context, "rebind_requested", origin)
        if (now - since >= ALERT_AFTER_MS) {
            postAlert(
                context,
                "通知监听已断开",
                "已尝试自动重连仍未恢复。点此打开 App 点“强制重连”，或关掉再打开通知使用权。",
                Intent(context, MainActivity::class.java),
            )
        }
    }

    /** 关掉再打开组件，迫使系统重新绑定监听服务（常见的厂商 ROM 绕过办法） */
    fun forceReconnect(context: Context) {
        val pm = context.packageManager
        val c = component(context)
        pm.setComponentEnabledSetting(c, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(c, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        NotificationListenerService.requestRebind(c)
        log(context, "rebind_requested", "manual_force")
    }

    private fun postAlert(context: Context, title: String, text: String, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, ALERT_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, BookkeeperApp.CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(pi)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
        } catch (_: SecurityException) {
            // 没有通知权限时无法告警；首页会显示红色状态
        }
    }

    private fun cancelAlert(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
    }

    fun log(context: Context, type: String, detail: String? = null) {
        val app = context.applicationContext
        BookkeeperApp.scope.launch {
            AppDatabase.get(app).listenerEvents()
                .insert(ListenerEvent(at = System.currentTimeMillis(), type = type, detail = detail))
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
