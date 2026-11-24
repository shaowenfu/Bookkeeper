package com.shaowenfu.bookkeeper.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shaowenfu.bookkeeper.Whitelist
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.ListenerEvent
import com.shaowenfu.bookkeeper.data.NotificationSample
import com.shaowenfu.bookkeeper.data.PackageCount
import com.shaowenfu.bookkeeper.data.SeenPackage
import com.shaowenfu.bookkeeper.export.Exporter
import com.shaowenfu.bookkeeper.health.KeepAliveService
import com.shaowenfu.bookkeeper.health.ListenerHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HealthStatus(
    val listenerGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val postNotificationsGranted: Boolean = false,
    val lastCheckAt: Long = 0L,
)

data class ExportMessage(val text: String, val uri: Uri?, val mime: String?)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    val selectedPackage = MutableStateFlow<String?>(null)

    val counts: StateFlow<List<PackageCount>> =
        db.samples().observeCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val samples: StateFlow<List<NotificationSample>> =
        selectedPackage.flatMapLatest { db.samples().observeRecent(it, 500) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val events: StateFlow<List<ListenerEvent>> =
        db.listenerEvents().observeRecent(300).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val seenPackages: StateFlow<List<SeenPackage>> =
        db.seenPackages().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connected: StateFlow<Boolean> = ListenerHealth.connected

    private val _health = MutableStateFlow(HealthStatus())
    val health: StateFlow<HealthStatus> = _health

    private val _whitelist = MutableStateFlow(Whitelist.packages(app).sorted())
    val whitelist: StateFlow<List<String>> = _whitelist

    private val _message = MutableStateFlow<ExportMessage?>(null)
    val message: StateFlow<ExportMessage?> = _message

    fun refreshHealth() {
        val ctx = getApplication<Application>()
        ListenerHealth.check(ctx, "app_open")
        KeepAliveService.start(ctx)
        _health.value = HealthStatus(
            listenerGranted = ListenerHealth.isPermissionGranted(ctx),
            listenerConnected = ListenerHealth.connected.value,
            batteryUnrestricted = ListenerHealth.isIgnoringBatteryOptimizations(ctx),
            postNotificationsGranted = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
            lastCheckAt = ListenerHealth.lastCheckAt.value,
        )
    }

    fun forceReconnect() {
        ListenerHealth.forceReconnect(getApplication())
        refreshHealth()
    }

    fun addToWhitelist(pkg: String) {
        val p = pkg.trim()
        if (p.isEmpty()) return
        Whitelist.add(getApplication(), p)
        _whitelist.value = Whitelist.packages(getApplication()).sorted()
        viewModelScope.launch { db.seenPackages().delete(p) }
    }

    fun removeFromWhitelist(pkg: String) {
        Whitelist.remove(getApplication(), pkg)
        _whitelist.value = Whitelist.packages(getApplication()).sorted()
    }

    fun export(json: Boolean) {
        viewModelScope.launch {
            _message.value = ExportMessage("正在导出…", null, null)
            _message.value = try {
                val r = withContext(Dispatchers.IO) {
                    if (json) Exporter.exportJson(getApplication()) else Exporter.exportCsv(getApplication())
                }
                ExportMessage(
                    "已导出 ${r.rows} 条样本到 下载/Bookkeeper/${r.displayName}",
                    r.uri, if (json) "application/json" else "text/csv",
                )
            } catch (e: Exception) {
                ExportMessage("导出失败：${e.message ?: e.javaClass.simpleName}", null, null)
            }
        }
    }

    fun dismissMessage() {
        _message.value = null
    }
}
