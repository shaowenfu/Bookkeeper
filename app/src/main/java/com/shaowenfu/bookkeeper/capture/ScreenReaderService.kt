package com.shaowenfu.bookkeeper.capture

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.shaowenfu.bookkeeper.BookkeeperApp
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.RawEvent
import com.shaowenfu.bookkeeper.data.ServiceEvent
import com.shaowenfu.bookkeeper.recognize.RecognitionRunner
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
 * 按需读屏。触发方式：长按两个音量键（无障碍快捷方式），或磁贴。
 * 不处理任何无障碍事件；触发时截一张图，并顺手读一次节点文字（微信读不到，其他页面用来核对金额）。
 */
class ScreenReaderService : AccessibilityService() {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var busy = false

    private val shortcutCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) = capture(TRIGGER_SHORTCUT)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        accessibilityButtonController.registerAccessibilityButtonCallback(shortcutCallback)
        logEvent(this, "connected")
        mainScope.launch {
            // 服务被系统杀掉后，长按音量键会先把它唤醒。如果此时前台是付款类 App，直接识别，省掉第二次长按
            delay(500)
            val pkg = withContext(Dispatchers.Default) { foregroundPackage() }
            if (Prefs(this@ScreenReaderService).captureOnWake && pkg in PAYMENT_APPS) capture(TRIGGER_AUTO_WAKE)
            // 上次没识别完的，顺便补上
            RecognitionRunner.kick(this@ScreenReaderService)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不监听屏幕事件 */ }

    override fun onInterrupt() { /* 没有语音反馈需要打断 */ }

    override fun onUnbind(intent: Intent?): Boolean {
        logEvent(this, "unbind")
        instance = null
        runCatching { accessibilityButtonController.unregisterAccessibilityButtonCallback(shortcutCallback) }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
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
                toast("截屏失败：${e.message ?: e}")
            } finally {
                busy = false
            }
        }
    }

    private suspend fun doCapture(trigger: String) {
        val t0 = System.currentTimeMillis()
        if (trigger == TRIGGER_SHORTCUT) {
            // vivo 冻结进程时，长按会被系统扣住，等用户打开本 App（或回到桌面）才送到。
            // 这时屏幕上已经不是付款页了，截下来只会记出错账，直接丢掉。
            val front = withContext(Dispatchers.Default) { activePackage() }
            if (front != null && isStaleTarget(front)) {
                logEvent(this, "stale_trigger", front)
                toast("刚才的长按被系统延迟了，已忽略。请到设置里允许截图记账后台高耗电")
                return
            }
        }
        if (trigger == TRIGGER_TILE) {
            if (Build.VERSION.SDK_INT >= 31) performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            delay(700)
        } else {
            delay(100)
        }

        // 截图优先：页面可能马上跳走
        val shot = screenshot()
        var status = shot.status
        var imagePath: String? = null
        shot.bitmap?.let { bmp ->
            status = if (Images.isMostlyBlack(bmp)) "black" else "ok"
            val f = File(filesDir, "shots/$t0.jpg")
            withContext(Dispatchers.IO) { Images.saveJpeg(bmp, f) }
            imagePath = f.absolutePath
            bmp.recycle()
        }

        val (pkg, dump) = withContext(Dispatchers.Default) { readOnce() }
        val id = withContext(Dispatchers.IO) {
            AppDatabase.get(this@ScreenReaderService).rawEvents().insert(
                RawEvent(
                    createdAt = t0, source = trigger, packageName = pkg,
                    imagePath = imagePath, imageStatus = status,
                    flatText = dump?.flatText, textChars = dump?.visibleChars ?: 0,
                )
            )
        }
        toast(
            when {
                status == "ok" -> "已截屏，正在识别"
                (dump?.visibleChars ?: 0) >= 10 -> "截图是黑的，用屏幕文字识别"
                else -> "截图失败（$status），也读不到文字"
            }
        )
        if (id > 0) RecognitionRunner.kick(this)
    }

    private fun candidateRoots(): List<AccessibilityNodeInfo> {
        val wins = runCatching { windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { (if (it.isActive) 2 else 0) + (if (it.isFocused) 1 else 0) }
        val roots = wins.mapNotNull { it.root }.filter { it.packageName?.toString() != packageName }
        return roots.ifEmpty { listOfNotNull(rootInActiveWindow) }
    }

    /** 当前活动窗口的包名，包括本 App 自己 */
    private fun activePackage(): String? {
        val wins = runCatching { windows }.getOrDefault(emptyList())
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val win = wins.firstOrNull { it.isActive } ?: wins.firstOrNull { it.isFocused } ?: wins.firstOrNull()
        return (win?.root ?: rootInActiveWindow)?.packageName?.toString()
    }

    private fun isStaleTarget(pkg: String): Boolean =
        pkg == packageName || pkg == "com.android.systemui" || pkg in launcherPackages

    private val launcherPackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val found = runCatching {
            packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        (found + listOf("com.bbk.launcher2", "com.vivo.launcher")).toSet()
    }

    private fun foregroundPackage(): String? = candidateRoots().firstOrNull()?.packageName?.toString()

    private fun readOnce(): Pair<String?, NodeDumper.Dump?> {
        val root = candidateRoots().firstOrNull() ?: return null to null
        return root.packageName?.toString() to runCatching { NodeDumper.dump(root) }.getOrNull()
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
                    if (cont.isActive) cont.resume(Shot(null, "error:$errorCode"))
                }
            })
        }
    } ?: Shot(null, "error:timeout")

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val TRIGGER_SHORTCUT = "a11y_shortcut"
        const val TRIGGER_AUTO_WAKE = "auto_wake"
        const val TRIGGER_TILE = "tile"

        /** 唤醒即识别只对这些 App 生效 */
        val PAYMENT_APPS = setOf(
            "com.tencent.mm", "com.eg.android.AlipayGphone", "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew",
            "com.ss.android.ugc.aweme", "com.jingdong.app.mall", "com.xingin.xhs", "com.taobao.taobao", "me.ele",
            "com.xunmeng.pinduoduo", "com.chinamworld.bocmbci", "com.taobao.idlefish",
        )

        @Volatile var instance: ScreenReaderService? = null
            private set

        fun logEvent(context: Context, type: String, detail: String? = null) {
            val app = context.applicationContext
            BookkeeperApp.scope.launch {
                AppDatabase.get(app).serviceEvents().insert(ServiceEvent(at = System.currentTimeMillis(), type = type, detail = detail))
            }
        }

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, ScreenReaderService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
