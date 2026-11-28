package com.shaowenfu.bookkeeper.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * vivo 会把后台的截图记账冻住，长按音量键就会卡住，直到打开 App 才生效。
 * 解决办法是在系统里允许它“后台高耗电”。这里尽量直接跳到那一页，跳不过去就退到电池设置或应用信息。
 */
object BatteryGuide {
    val isVivo: Boolean get() = Build.MANUFACTURER.equals("vivo", true) || Build.BRAND.equals("vivo", true) ||
        Build.BRAND.equals("iqoo", true)

    val steps: String get() = if (isVivo) {
        "设置 → 电池 → 后台耗电管理 → 截图记账 → 允许后台高耗电。再在最近任务里下拉截图记账的卡片，把它锁定"
    } else {
        "在系统电池设置里，把截图记账设为“不限制”或“允许后台运行”，并在最近任务里锁定它"
    }

    /** 依次尝试，返回是否打开了某个页面 */
    fun open(context: Context): Boolean {
        val pkg = context.packageName
        val candidates = listOf(
            // OriginOS / Funtouch 新版：后台耗电管理
            Intent().setComponent(ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")),
            Intent().setComponent(ComponentName("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")),
            Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$pkg")),
        )
        for (i in candidates) {
            val ok = runCatching {
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}
