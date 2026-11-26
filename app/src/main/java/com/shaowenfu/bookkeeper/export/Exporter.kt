package com.shaowenfu.bookkeeper.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.shaowenfu.bookkeeper.BuildConfig
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.ui.Money
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 导出到 下载/Bookkeeper/。
 * CSV：账本（一行一笔，商品明细合并成一列），用 Excel 打开。
 * JSON：账本 + 商品 + 全部识别记录（含模型原始输出，不含图片），用于排查和重新计算。
 */
object Exporter {
    data class Result(val uri: Uri, val displayName: String, val rows: Int)

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private val csvTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private fun iso(ms: Long?): Any = if (ms == null) JSONObject.NULL else isoFmt.format(Date(ms))
    private fun n(v: Any?): Any = v ?: JSONObject.NULL

    suspend fun exportCsv(context: Context): Result {
        val db = AppDatabase.get(context)
        val txs = db.txs().all()
        val items = db.lineItems().all().groupBy { it.txId }
        val header = listOf("id", "时间", "时间为截图时间", "类型", "金额", "商户", "平台", "渠道", "付款方式", "分类", "自动扣费", "待核对", "已删除", "商品", "备注")
        val sb = StringBuilder("﻿").append(header.joinToString(",")).append("\r\n")
        for (t in txs) {
            val its = items[t.id].orEmpty().joinToString("；") { li -> li.name + (li.amountCents?.let { " ${Money.yuan(it)}" } ?: "") }
            val row = listOf(
                t.id, csvTime.format(Date(t.occurredAt)), t.timeEstimated, Money.directionLabel(t.direction), Money.yuan(t.amountCents),
                t.merchant, t.app, t.channel, t.funding, t.category, t.isAutoDebit, t.needsReview, t.status == "void", its, t.note,
            )
            sb.append(row.joinToString(",") { csv(it) }).append("\r\n")
        }
        val name = "bookkeeper-${stamp()}.csv"
        return Result(write(context, name, "text/csv", sb.toString().toByteArray()), name, txs.size)
    }

    suspend fun exportJson(context: Context): Result {
        val db = AppDatabase.get(context)
        val txs = db.txs().all()
        val root = JSONObject()
            .put("format", "bookkeeper-ledger").put("format_version", 1)
            .put("exported_at", iso(System.currentTimeMillis()))
            .put("device_tz", TimeZone.getDefault().id)
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("app_version", BuildConfig.VERSION_NAME)

        root.put("transactions", JSONArray().apply {
            txs.forEach { t ->
                put(JSONObject()
                    .put("id", t.id).put("occurred_at", iso(t.occurredAt)).put("time_estimated", t.timeEstimated)
                    .put("amount_cents", t.amountCents).put("direction", t.direction)
                    .put("merchant", n(t.merchant)).put("app", n(t.app)).put("channel", n(t.channel)).put("funding", n(t.funding))
                    .put("category", n(t.category)).put("is_auto_debit", t.isAutoDebit).put("needs_review", t.needsReview)
                    .put("status", t.status).put("note", n(t.note))
                    .put("created_at", iso(t.createdAt)).put("updated_at", iso(t.updatedAt)))
            }
        })
        root.put("line_items", JSONArray().apply {
            db.lineItems().all().forEach { li ->
                put(JSONObject().put("tx_id", li.txId).put("name", li.name).put("qty", n(li.qty))
                    .put("amount_cents", n(li.amountCents)).put("raw_event_id", n(li.rawEventId)))
            }
        })
        root.put("tx_events", JSONArray().apply {
            db.txEvents().all().forEach { put(JSONObject().put("tx_id", it.txId).put("raw_event_id", it.rawEventId).put("role", it.role)) }
        })
        root.put("raw_events", JSONArray().apply {
            db.rawEvents().all().forEach { e ->
                put(JSONObject()
                    .put("id", e.id).put("created_at", iso(e.createdAt)).put("source", e.source)
                    .put("package_name", n(e.packageName)).put("image_status", e.imageStatus)
                    .put("text_chars", e.textChars).put("flat_text", n(e.flatText))
                    .put("status", e.status).put("attempts", e.attempts).put("last_error", n(e.lastError))
                    .put("model", n(e.model)).put("latency_ms", n(e.latencyMs))
                    .put("result", e.resultJson?.let { runCatching { JSONObject(it) }.getOrDefault(it) } ?: JSONObject.NULL))
            }
        })
        root.put("service_events", JSONArray().apply {
            db.serviceEvents().all().forEach { put(JSONObject().put("at", iso(it.at)).put("type", it.type).put("detail", n(it.detail))) }
        })
        val name = "bookkeeper-${stamp()}.json"
        return Result(write(context, name, "application/json", root.toString(2).toByteArray()), name, txs.size)
    }

    private fun csv(v: Any?): String {
        if (v == null) return ""
        val s = v.toString()
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun write(context: Context, name: String, mime: String, bytes: ByteArray): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Bookkeeper")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法在“下载”目录创建文件")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
