package com.shaowenfu.bookkeeper.settings

import android.content.Context

/**
 * 设置。API Key 存在 App 私有目录的 SharedPreferences 里（单机侧载自用）；
 * 之后按设计文档改为 Android Keystore 加密存储。
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var baseUrl: String
        get() = sp.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/').ifEmpty { DEFAULT_BASE_URL }).apply()

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    /** 长按音量键把被杀掉的服务唤醒时，如果前台是付款类 App，就直接识别，不用再按第二次 */
    var captureOnWake: Boolean
        get() = sp.getBoolean("capture_on_wake", true)
        set(v) = sp.edit().putBoolean("capture_on_wake", v).apply()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-flash"
    }
}
