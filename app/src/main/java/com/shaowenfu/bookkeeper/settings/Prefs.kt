package com.shaowenfu.bookkeeper.settings

import android.content.Context

/**
 * 探针阶段的设置。Key 存在 App 私有目录的 SharedPreferences 里；
 * 正式版按设计文档改为 Android Keystore 加密存储。
 */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("probe_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var baseUrl: String
        get() = sp.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/').ifEmpty { DEFAULT_BASE_URL }).apply()

    var textModel: String
        get() = sp.getString("text_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("text_model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    var imageModel: String
        get() = sp.getString("image_model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString("image_model", v.trim().ifEmpty { DEFAULT_MODEL }).apply()

    /** 每次采样后自动跑文字路径 */
    var autoText: Boolean
        get() = sp.getBoolean("auto_text", true)
        set(v) = sp.edit().putBoolean("auto_text", v).apply()

    /** 每次采样后自动跑看图路径（截图正常时） */
    var autoImage: Boolean
        get() = sp.getBoolean("auto_image", true)
        set(v) = sp.edit().putBoolean("auto_image", v).apply()

    /** 截图时是否保存图片。关掉后只保存节点文字和截图是否黑屏 */
    var keepScreenshot: Boolean
        get() = sp.getBoolean("keep_screenshot", true)
        set(v) = sp.edit().putBoolean("keep_screenshot", v).apply()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-flash"
    }
}
