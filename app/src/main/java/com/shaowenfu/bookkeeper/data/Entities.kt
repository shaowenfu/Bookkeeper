package com.shaowenfu.bookkeeper.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一次采样：一次按钮/磁贴触发，或一张分享进来的截图。
 * 同时保存“文字路径”和“看图路径”的识别结果，用来比较两种方案。
 */
@Entity(tableName = "capture")
data class Capture(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** a11y_button / tile / share / gallery / in_app */
    val trigger: String,
    /** 被读取的前台 App 包名；分享导入时为 null */
    @ColumnInfo(name = "package_name") val packageName: String? = null,
    @ColumnInfo(name = "window_title") val windowTitle: String? = null,

    // ---- 无障碍节点文字 ----
    @ColumnInfo(name = "node_count") val nodeCount: Int = 0,
    /** 第一次读取到的可见文字字数（WebView 页面第一次可能为空） */
    @ColumnInfo(name = "text_chars_first") val textCharsFirst: Int = 0,
    @ColumnInfo(name = "text_chars") val textChars: Int = 0,
    @ColumnInfo(name = "retried_read") val retriedRead: Boolean = false,
    /** 节点列表 JSON：text / desc / id / cls / bounds / visible */
    @ColumnInfo(name = "nodes_json") val nodesJson: String? = null,
    /** 按屏幕位置排序后的可见文字，一行一个节点 */
    @ColumnInfo(name = "flat_text") val flatText: String? = null,
    @ColumnInfo(name = "read_ms") val readMs: Long? = null,

    // ---- 截图 ----
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    /** ok / black / error:<原因> / none */
    @ColumnInfo(name = "image_status") val imageStatus: String = "none",
    @ColumnInfo(name = "image_w") val imageW: Int? = null,
    @ColumnInfo(name = "image_h") val imageH: Int? = null,
    @ColumnInfo(name = "screenshot_ms") val screenshotMs: Long? = null,

    // ---- 文字路径识别 ----
    @ColumnInfo(name = "text_model") val textModel: String? = null,
    @ColumnInfo(name = "text_result") val textResult: String? = null,
    @ColumnInfo(name = "text_latency_ms") val textLatencyMs: Long? = null,
    @ColumnInfo(name = "text_error") val textError: String? = null,

    // ---- 看图路径识别 ----
    @ColumnInfo(name = "image_model") val imageModel: String? = null,
    @ColumnInfo(name = "image_result") val imageResult: String? = null,
    @ColumnInfo(name = "image_latency_ms") val imageLatencyMs: Long? = null,
    @ColumnInfo(name = "image_error") val imageError: String? = null,
    @ColumnInfo(name = "image_parts") val imageParts: Int? = null,

    // ---- 你手动标注，用来算准确率 ----
    /** 页面类型标签，例如 “微信支付成功” */
    @ColumnInfo(name = "page_label") val pageLabel: String? = null,
    /** 实际金额（多笔用逗号分隔） */
    @ColumnInfo(name = "true_amount") val trueAmount: String? = null,
    /** 文字路径是否正确：null 未标注 */
    @ColumnInfo(name = "text_ok") val textOk: Boolean? = null,
    @ColumnInfo(name = "image_ok") val imageOk: Boolean? = null,
    val note: String? = null,
)

/** 无障碍服务的连接 / 断开记录：用来看一周内被系统杀掉几次 */
@Entity(tableName = "service_event")
data class ServiceEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** connected / unbind / destroyed / interrupted / enabled_but_dead */
    val type: String,
    val detail: String? = null,
)
