package com.shaowenfu.bookkeeper.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shaowenfu.bookkeeper.capture.Importer
import com.shaowenfu.bookkeeper.capture.ScreenReaderService
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.LineItem
import com.shaowenfu.bookkeeper.data.MonthTotal
import com.shaowenfu.bookkeeper.data.RawEvent
import com.shaowenfu.bookkeeper.data.ServiceEvent
import com.shaowenfu.bookkeeper.data.Tx
import com.shaowenfu.bookkeeper.export.Exporter
import com.shaowenfu.bookkeeper.recognize.RecognitionRunner
import com.shaowenfu.bookkeeper.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class Health(
    val serviceEnabled: Boolean = false,
    val serviceConnected: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val apiKeySet: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val prefs = Prefs(app)

    val txs: StateFlow<List<Tx>> =
        db.txs().observeActive().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val monthTotal: StateFlow<MonthTotal> = run {
        val c = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val from = c.timeInMillis
        c.add(Calendar.MONTH, 1)
        db.txs().observeTotal(from, c.timeInMillis)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MonthTotal(0, 0, 0))
    }
    val events: StateFlow<List<RawEvent>> =
        db.rawEvents().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val serviceEvents: StateFlow<List<ServiceEvent>> =
        db.serviceEvents().observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _health = MutableStateFlow(Health())
    val health: StateFlow<Health> = _health
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message
    private val _lastExport = MutableStateFlow<Pair<Uri, String>?>(null)
    val lastExport: StateFlow<Pair<Uri, String>?> = _lastExport

    private var reportedDead = false

    fun refreshHealth(notificationsAllowed: Boolean) {
        val app = getApplication<Application>()
        val enabled = ScreenReaderService.isEnabledInSettings(app)
        val connected = ScreenReaderService.instance != null
        if (enabled && !connected && !reportedDead) {
            reportedDead = true
            ScreenReaderService.logEvent(app, "enabled_but_dead", "app_open")
        }
        if (connected) reportedDead = false
        _health.value = Health(enabled, connected, notificationsAllowed, prefs.apiKey.isNotBlank())
        // 打开 App 时把没识别完的补上（App 在前台，可以起前台服务）
        viewModelScope.launch {
            if (prefs.apiKey.isNotBlank() && db.rawEvents().todoCount() > 0) RecognitionRunner.kick(app)
        }
    }

    fun observeTx(id: Long): Flow<Tx?> = db.txs().observe(id)
    fun observeItems(txId: Long): Flow<List<LineItem>> = db.lineItems().observeForTx(txId)
    fun observeEvent(id: Long): Flow<RawEvent?> = db.rawEvents().observe(id)
    suspend fun eventsForTx(txId: Long): List<RawEvent> = db.rawEvents().forTx(txId)

    fun saveTx(t: Tx) = viewModelScope.launch {
        db.txs().update(t.copy(updatedAt = System.currentTimeMillis(), needsReview = false))
        _message.value = "已保存"
    }

    fun voidTx(id: Long) = viewModelScope.launch {
        db.txs().voidTx(id)
        _message.value = "已删除这笔"
    }

    fun retry(e: RawEvent) = viewModelScope.launch {
        db.rawEvents().update(e.copy(status = RawEvent.STATUS_PENDING, attempts = 0, lastError = null))
        RecognitionRunner.kick(getApplication())
        _message.value = "已重新提交识别"
    }

    fun importImages(uris: List<Uri>) = viewModelScope.launch {
        val ids = withContext(Dispatchers.IO) { Importer.importUris(getApplication(), uris, "gallery") }
        _message.value = "已导入 ${ids.size} 张截图，正在识别"
    }

    fun export(json: Boolean) = viewModelScope.launch {
        try {
            val r = withContext(Dispatchers.IO) {
                if (json) Exporter.exportJson(getApplication()) else Exporter.exportCsv(getApplication())
            }
            _lastExport.value = r.uri to if (json) "application/json" else "text/csv"
            _message.value = "已导出 ${r.rows} 笔到 下载/Bookkeeper/${r.displayName}"
        } catch (e: Exception) {
            _message.value = "导出失败：${e.message}"
        }
    }

    fun saveSettings(apiKey: String, baseUrl: String, model: String, captureOnWake: Boolean) {
        val hadKey = prefs.apiKey.isNotBlank()
        prefs.apiKey = apiKey; prefs.baseUrl = baseUrl; prefs.model = model; prefs.captureOnWake = captureOnWake
        _health.value = _health.value.copy(apiKeySet = prefs.apiKey.isNotBlank())
        _message.value = "设置已保存"
        if (!hadKey && prefs.apiKey.isNotBlank()) RecognitionRunner.kick(getApplication())
    }

    fun clearMessage() { _message.value = null }
}
