package com.shaowenfu.bookkeeper.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.Capture
import com.shaowenfu.bookkeeper.data.ServiceEvent
import com.shaowenfu.bookkeeper.export.Exporter
import com.shaowenfu.bookkeeper.probe.Extractor
import com.shaowenfu.bookkeeper.probe.Importer
import com.shaowenfu.bookkeeper.probe.ScreenReaderService
import com.shaowenfu.bookkeeper.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class Health(
    val serviceEnabled: Boolean = false,
    val serviceConnected: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val apiKeySet: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    val prefs = Prefs(app)

    val captures: StateFlow<List<Capture>> =
        db.captures().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val events: StateFlow<List<ServiceEvent>> =
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
        // 设置里开着但服务没连上：多半是进程被系统杀了，记一笔
        if (enabled && !connected && !reportedDead) {
            reportedDead = true
            ScreenReaderService.logEvent(app, "enabled_but_dead", "app_open")
        }
        if (connected) reportedDead = false
        _health.value = Health(enabled, connected, notificationsAllowed, prefs.apiKey.isNotBlank())
    }

    fun observe(id: Long): Flow<Capture?> = db.captures().observe(id)

    fun importImages(uris: List<Uri>) = viewModelScope.launch {
        val ids = withContext(Dispatchers.IO) { Importer.importUris(getApplication(), uris, "gallery") }
        _message.value = "已导入 ${ids.size} 张截图" + if (prefs.autoImage) "，正在识别" else ""
    }

    fun rerun(id: Long, text: Boolean, image: Boolean) {
        Extractor.launch(getApplication(), id, text, image)
        _message.value = "已重新提交识别"
    }

    fun saveLabels(id: Long, label: String?, amount: String?, textOk: Boolean?, imageOk: Boolean?, note: String?) =
        viewModelScope.launch {
            db.captures().setLabels(id, label?.ifBlank { null }, amount?.ifBlank { null }, textOk, imageOk, note?.ifBlank { null })
            _message.value = "标注已保存"
        }

    fun delete(c: Capture) = viewModelScope.launch {
        withContext(Dispatchers.IO) { c.imagePath?.let { File(it).delete() } }
        db.captures().delete(c.id)
    }

    fun export(withImages: Boolean) = viewModelScope.launch {
        try {
            val r = withContext(Dispatchers.IO) {
                if (withImages) Exporter.exportZip(getApplication()) else Exporter.exportJson(getApplication())
            }
            _lastExport.value = r.uri to if (withImages) "application/zip" else "application/json"
            _message.value = "已导出 ${r.rows} 条到 下载/Bookkeeper/${r.displayName}"
        } catch (e: Exception) {
            _message.value = "导出失败：${e.message}"
        }
    }

    fun saveSettings(apiKey: String, baseUrl: String, textModel: String, imageModel: String,
                     autoText: Boolean, autoImage: Boolean, keepShot: Boolean) {
        prefs.apiKey = apiKey; prefs.baseUrl = baseUrl
        prefs.textModel = textModel; prefs.imageModel = imageModel
        prefs.autoText = autoText; prefs.autoImage = autoImage; prefs.keepScreenshot = keepShot
        _health.value = _health.value.copy(apiKeySet = prefs.apiKey.isNotBlank())
        _message.value = "设置已保存"
    }

    fun clearMessage() { _message.value = null }
}
