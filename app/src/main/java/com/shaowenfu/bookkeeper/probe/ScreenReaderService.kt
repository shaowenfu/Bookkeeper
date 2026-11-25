package com.shaowenfu.bookkeeper.probe

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.shaowenfu.bookkeeper.BookkeeperApp
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.Capture
import com.shaowenfu.bookkeeper.data.ServiceEvent
import com.shaowenfu.bookkeeper.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * 按需读屏：不处理任何无障碍事件，只在你点“无障碍按钮”或磁贴时读一次当前窗口。
 * 读两样东西：节点树里的文字（安全页面也能读），和一张截图（安全页面会是黑的）。
 */
class ScreenReaderService : AccessibilityService() {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var busy = false

    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) = capture(TRIGGER_BUTTON)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
        logEvent(this, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不监听屏幕事件 */ }

    override fun onInterrupt() = logEvent(this, "interrupted")

    override fun onUnbind(intent: Intent?): Boolean {
        logEvent(this, "unbind")
        instance = null
        runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback) }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        logEvent(this, "destroyed")
        instance = null
        mainScope.cancel()
        super.onDestroy()
    }

    fun capture(trigger: String) {
        if (busy) return
        busy = true
        mainScope.launch {
            try {
                doCapture(trigger)
            } catch (e: Exception) {
                toast("采样失败：${e.message ?: e}")
            } finally {
                busy = false
            }
        }
    }

    private suspend fun doCapture(trigger: String) {
        val prefs = Prefs(this)
        val t0 = System.currentTimeMillis()
        if (trigger == TRIGGER_TILE) {
            // 先收起通知栏，等下面的 App 重新成为前台窗口
            if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            delay(700)
        } else {
            delay(150)
        }

        // 1) 节点文字
        val readStart = System.currentTimeMillis()
        val (pkg, title, first) = withContext(Dispatchers.Default) { readOnce() }
        var dump = first
        var retried = false
        if (dump == null || dump.visibleChars < 20) {
            // WebView 页面第一次访问时节点树可能还没建好，等一下再读一次
            delay(800)
            retried = true
            dump = withContext(Dispatchers.Default) { readOnce().third } ?: dump
        }
        val readMs = System.currentTimeMillis() - readStart

        // 2) 截图
        val shotStart = System.currentTimeMillis()
        val shot = screenshot()
        val shotMs = System.currentTimeMillis() - shotStart
        var imagePath: String? = null
        var status = shot.status
        val bmp = shot.bitmap
        if (bmp != null) {
            status = if (Images.isMostlyBlack(bmp)) "black" else "ok"
            if (prefs.keepScreenshot || status == "ok") {
                val f = File(filesDir, "shots/${t0}.jpg")
                withContext(Dispatchers.IO) { Images.saveJpeg(bmp, f) }
                imagePath = f.absolutePath
            }
        }

        val capture = Capture(
            createdAt = t0,
            trigger = trigger,
            packageName = pkg,
            windowTitle = title,
            nodeCount = dump?.nodeCount ?: 0,
            textCharsFirst = first?.visibleChars ?: 0,
            textChars = dump?.visibleChars ?: 0,
            retriedRead = retried,
            nodesJson = dump?.nodesJson,
            flatText = dump?.flatText,
            readMs = readMs,
            imagePath = imagePath,
            imageStatus = status,
            imageW = bmp?.width,
            imageH = bmp?.height,
            screenshotMs = shotMs,
        )
        bmp?.recycle()
        val id = withContext(Dispatchers.IO) { AppDatabase.get(this@ScreenReaderService).captures().insert(capture) }
        toast("已采样 #$id · 文字 ${capture.textChars} 字 · 截图${statusLabel(status)}")
        if (prefs.autoText || prefs.autoImage) Extractor.launch(this, id, prefs.autoText, prefs.autoImage)
    }

    /** 找到当前前台 App 的窗口（跳过本 App、输入法、系统栏） */
    private fun readOnce(): Triple<String?, String?, NodeDumper.Dump?> {
        val candidates = runCatching { windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { (if (it.isActive) 2 else 0) + (if (it.isFocused) 1 else 0) }
        var root: AccessibilityNodeInfo? = null
        var title: String? = null
        for (w in candidates) {
            val r = w.root ?: continue
            if (r.packageName?.toString() == packageName) continue
            root = r; title = w.title?.toString(); break
        }
        if (root == null) root = rootInActiveWindow
        if (root == null) return Triple(null, null, null)
        return Triple(root.packageName?.toString(), title, NodeDumper.dump(root))
    }

    private class Shot(val bitmap: Bitmap?, val status: String)

    private suspend fun screenshot(): Shot = withTimeoutOrNull(4000) {
        suspendCancellableCoroutine { cont ->
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val bmp = try {
                        Bitmap.wrapHardwareBuffer(hb, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hb.close()
                    }
                    if (cont.isActive) cont.resume(Shot(bmp, if (bmp == null) "error:decode" else "ok"))
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resume(Shot(null, "error:${errorName(errorCode)}"))
                }
            })
        }
    } ?: Shot(null, "error:timeout")

    private fun errorName(code: Int) = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no_access"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "too_frequent"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid_display"
        5 -> "invalid_window"
        6 -> "secure_window"
        else -> "code_$code"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val TRIGGER_BUTTON = "a11y_button"
        const val TRIGGER_TILE = "tile"

        @Volatile var instance: ScreenReaderService? = null
            private set

        fun statusLabel(status: String) = when {
            status == "ok" -> "正常"
            status == "black" -> "黑屏"
            status == "none" -> "无"
            else -> "失败(${status.removePrefix("error:")})"
        }

        fun logEvent(context: Context, type: String, detail: String? = null) {
            val app = context.applicationContext
            BookkeeperApp.scope.launch {
                AppDatabase.get(app).serviceEvents().insert(ServiceEvent(at = System.currentTimeMillis(), type = type, detail = detail))
            }
        }

        /** 系统设置里是否开启了本服务（开启了但 instance 为 null = 进程被杀后没重连） */
        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = android.content.ComponentName(context, ScreenReaderService::class.java)
            return enabled.split(':').any { android.content.ComponentName.unflattenFromString(it) == me }
        }
    }
}
