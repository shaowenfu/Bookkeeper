package com.shaowenfu.bookkeeper

import android.content.Context
import android.content.pm.PackageManager

data class TargetApp(val packageName: String, val label: String)

/**
 * 通知监听只处理白名单里的包名。默认列表来自各 App 的应用商店包名；
 * 如果你装的是别的版本（极速版、HarmonyOS 版等），在“其他包名”页把它加进来即可。
 */
object Whitelist {
    val DEFAULTS = listOf(
        TargetApp("com.chinamworld.main", "中国银行"),
        TargetApp("com.tencent.mm", "微信"),
        TargetApp("com.eg.android.AlipayGphone", "支付宝"),
        TargetApp("com.ss.android.ugc.aweme", "抖音"),
        TargetApp("com.sankuai.meituan", "美团"),
        TargetApp("com.sankuai.meituan.takeoutnew", "美团外卖"),
        TargetApp("com.xingin.xhs", "小红书"),
        TargetApp("com.jingdong.app.mall", "京东"),
    )

    const val BOC_PACKAGE = "com.chinamworld.main"

    private const val PREFS = "whitelist"
    private const val KEY_EXTRA = "extra_packages"
    private const val KEY_REMOVED = "removed_defaults"

    @Volatile private var cache: Set<String>? = null

    fun packages(context: Context): Set<String> {
        cache?.let { return it }
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val extra = p.getStringSet(KEY_EXTRA, emptySet()) ?: emptySet()
        val removed = p.getStringSet(KEY_REMOVED, emptySet()) ?: emptySet()
        val result = (DEFAULTS.map { it.packageName }.toSet() - removed) + extra
        cache = result
        return result
    }

    fun add(context: Context, pkg: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val extra = (p.getStringSet(KEY_EXTRA, emptySet()) ?: emptySet()) + pkg
        val removed = (p.getStringSet(KEY_REMOVED, emptySet()) ?: emptySet()) - pkg
        p.edit().putStringSet(KEY_EXTRA, extra).putStringSet(KEY_REMOVED, removed).apply()
        cache = null
    }

    fun remove(context: Context, pkg: String) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val extra = (p.getStringSet(KEY_EXTRA, emptySet()) ?: emptySet()) - pkg
        val removed = if (DEFAULTS.any { it.packageName == pkg })
            (p.getStringSet(KEY_REMOVED, emptySet()) ?: emptySet()) + pkg
        else (p.getStringSet(KEY_REMOVED, emptySet()) ?: emptySet())
        p.edit().putStringSet(KEY_EXTRA, extra).putStringSet(KEY_REMOVED, removed).apply()
        cache = null
    }

    fun label(context: Context, pkg: String): String {
        DEFAULTS.firstOrNull { it.packageName == pkg }?.let { return it.label }
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
