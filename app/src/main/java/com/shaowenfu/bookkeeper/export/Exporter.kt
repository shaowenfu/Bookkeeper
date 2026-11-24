package com.shaowenfu.bookkeeper.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.shaowenfu.bookkeeper.BuildConfig
import com.shaowenfu.bookkeeper.Whitelist
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.NotificationSample
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 导出到 下载/Bookkeeper/，供电脑上用 tools/analyze_delay.py 分析。 */
object Exporter {
    data class Result(val uri: Uri, val displayName: String, val rows: Int)

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    private fun iso(ms: Long?): Any = if (ms == null) JSONObject.NULL else isoFmt.format(Date(ms))

    suspend fun exportJson(context: Context): Result {
        val db = AppDatabase.get(context)
        val samples = db.samples().all()
        val events = db.listenerEvents().all()
        val seen = db.seenPackages().all()

        val root = JSONObject()
        root.put("format", "bookkeeper-m0-samples")
        root.put("format_version", 1)
        root.put("exported_at", iso(System.currentTimeMillis()))
        root.put("device_tz", TimeZone.getDefault().id)
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        root.put("app_version", BuildConfig.VERSION_NAME)
        root.put("boc_package", Whitelist.BOC_PACKAGE)
        root.put("whitelist", JSONArray(Whitelist.packages(context).sorted()))

        val arr = JSONArray()
        samples.forEach { s -> arr.put(sampleToJson(context, s)) }
        root.put("samples", arr)

        val ev = JSONArray()
        events.forEach { e ->
            ev.put(JSONObject().apply {
                put("at", e.at); put("at_iso", iso(e.at)); put("type", e.type); put("detail", e.detail ?: JSONObject.NULL)
            })
        }
        root.put("listener_events", ev)

        val sp = JSONArray()
        seen.forEach { p ->
            sp.put(JSONObject().apply {
                put("package_name", p.packageName); put("count", p.count)
                put("first_seen_at", iso(p.firstSeenAt)); put("last_seen_at", iso(p.lastSeenAt))
            })
        }
        root.put("other_packages_seen", sp)

        val name = "bookkeeper-m0-${stamp()}.json"
        val uri = write(context, name, "application/json", root.toString(2).toByteArray(Charsets.UTF_8))
        return Result(uri, name, samples.size)
    }

    suspend fun exportCsv(context: Context): Result {
        val samples = AppDatabase.get(context).samples().all()
        val header = listOf(
            "id", "package_name", "app_label", "sbn_key", "notification_id", "tag",
            "post_time", "post_time_iso", "received_at", "received_at_iso", "tz_id", "capture_source",
            "title", "text", "big_text", "sub_text", "summary_text", "text_lines", "ticker_text",
            "category", "channel_id", "group_key", "is_group_summary", "is_ongoing", "flags",
            "is_update", "previous_id", "possible_duplicate_of", "repost_count", "last_seen_at",
            "removed_at", "removed_reason", "content_hash", "extras_json",
        )
        val sb = StringBuilder()
        sb.append('﻿') // BOM：让 Excel 正确识别 UTF-8 中文
        sb.append(header.joinToString(",")).append("\r\n")
        for (s in samples) {
            val row = listOf<Any?>(
                s.id, s.packageName, Whitelist.label(context, s.packageName), s.sbnKey, s.notificationId, s.tag,
                s.postTime, iso(s.postTime), s.receivedAt, iso(s.receivedAt), s.tzId, s.captureSource,
                s.title, s.text, s.bigText, s.subText, s.summaryText, s.textLines, s.tickerText,
                s.category, s.channelId, s.groupKey, s.isGroupSummary, s.isOngoing, s.flags,
                s.isUpdate, s.previousId, s.possibleDuplicateOf, s.repostCount, s.lastSeenAt,
                s.removedAt, s.removedReason, s.contentHash, s.extrasJson,
            )
            sb.append(row.joinToString(",") { csv(it) }).append("\r\n")
        }
        val name = "bookkeeper-m0-${stamp()}.csv"
        val uri = write(context, name, "text/csv", sb.toString().toByteArray(Charsets.UTF_8))
        return Result(uri, name, samples.size)
    }

    private fun sampleToJson(context: Context, s: NotificationSample): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("package_name", s.packageName)
        put("app_label", Whitelist.label(context, s.packageName))
        put("sbn_key", s.sbnKey)
        put("notification_id", s.notificationId)
        put("tag", s.tag ?: JSONObject.NULL)
        put("post_time", s.postTime)
        put("post_time_iso", iso(s.postTime))
        put("received_at", s.receivedAt)
        put("received_at_iso", iso(s.receivedAt))
        put("tz_id", s.tzId)
        put("capture_source", s.captureSource)
        put("title", s.title ?: JSONObject.NULL)
        put("text", s.text ?: JSONObject.NULL)
        put("big_text", s.bigText ?: JSONObject.NULL)
        put("sub_text", s.subText ?: JSONObject.NULL)
        put("summary_text", s.summaryText ?: JSONObject.NULL)
        put("text_lines", s.textLines ?: JSONObject.NULL)
        put("ticker_text", s.tickerText ?: JSONObject.NULL)
        put("category", s.category ?: JSONObject.NULL)
        put("channel_id", s.channelId ?: JSONObject.NULL)
        put("group_key", s.groupKey ?: JSONObject.NULL)
        put("is_group_summary", s.isGroupSummary)
        put("is_ongoing", s.isOngoing)
        put("flags", s.flags)
        put("is_update", s.isUpdate)
        put("previous_id", s.previousId ?: JSONObject.NULL)
        put("possible_duplicate_of", s.possibleDuplicateOf ?: JSONObject.NULL)
        put("repost_count", s.repostCount)
        put("last_seen_at", s.lastSeenAt)
        put("removed_at", s.removedAt ?: JSONObject.NULL)
        put("removed_reason", s.removedReason ?: JSONObject.NULL)
        put("content_hash", s.contentHash)
        put("extras", try { JSONObject(s.extrasJson) } catch (e: Exception) { s.extrasJson })
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
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("无法在“下载”目录创建文件")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
