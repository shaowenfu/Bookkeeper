package com.shaowenfu.bookkeeper.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.shaowenfu.bookkeeper.BuildConfig
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.Capture
import com.shaowenfu.bookkeeper.probe.Extractor
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出到 下载/Bookkeeper/。
 * JSON：节点文字、两路识别结果、耗时、你的标注、无障碍服务连接记录。
 * ZIP：同一个 JSON 加上全部截图（截图里可能有余额、卡号等，分享前注意）。
 */
object Exporter {
    data class Result(val uri: Uri, val displayName: String, val rows: Int)

    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private fun iso(ms: Long?): Any = if (ms == null) JSONObject.NULL else isoFmt.format(Date(ms))
    private fun n(v: Any?): Any = v ?: JSONObject.NULL

    private suspend fun buildJson(context: Context, withImages: Boolean): Pair<JSONObject, List<Capture>> {
        val db = AppDatabase.get(context)
        val captures = db.captures().all()
        val events = db.serviceEvents().all()
        val root = JSONObject()
        root.put("format", "bookkeeper-probe-samples")
        root.put("format_version", 1)
        root.put("exported_at", iso(System.currentTimeMillis()))
        root.put("device_tz", TimeZone.getDefault().id)
        root.put("device", "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        root.put("app_version", BuildConfig.VERSION_NAME)
        root.put("includes_images", withImages)

        val arr = JSONArray()
        captures.forEach { c -> arr.put(toJson(c, withImages)) }
        root.put("captures", arr)

        val ev = JSONArray()
        events.forEach { e -> ev.put(JSONObject().put("at_iso", iso(e.at)).put("at", e.at).put("type", e.type).put("detail", n(e.detail))) }
        root.put("service_events", ev)
        return root to captures
    }

    private fun toJson(c: Capture, withImages: Boolean) = JSONObject().apply {
        put("id", c.id)
        put("created_at", c.createdAt); put("created_at_iso", iso(c.createdAt))
        put("trigger", c.trigger)
        put("package_name", n(c.packageName)); put("window_title", n(c.windowTitle))
        put("node_count", c.nodeCount)
        put("text_chars_first", c.textCharsFirst); put("text_chars", c.textChars); put("retried_read", c.retriedRead)
        put("read_ms", n(c.readMs))
        put("flat_text", n(c.flatText))
        put("nodes", c.nodesJson?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONObject.NULL)
        put("image_status", c.imageStatus); put("image_w", n(c.imageW)); put("image_h", n(c.imageH))
        put("screenshot_ms", n(c.screenshotMs))
        put("image_file", if (withImages && c.imagePath != null && File(c.imagePath).exists()) "images/${c.id}.jpg" else JSONObject.NULL)
        put("text_model", n(c.textModel)); put("text_latency_ms", n(c.textLatencyMs)); put("text_error", n(c.textError))
        put("text_result", parsed(c.textResult))
        put("image_model", n(c.imageModel)); put("image_latency_ms", n(c.imageLatencyMs)); put("image_error", n(c.imageError))
        put("image_parts", n(c.imageParts))
        put("image_result", parsed(c.imageResult))
        val found = Extractor.amountsFoundInText(c)
        put("image_amounts_found_in_text", if (found == null) JSONObject.NULL else "${found.first}/${found.second}")
        put("page_label", n(c.pageLabel)); put("true_amount", n(c.trueAmount))
        put("text_ok", n(c.textOk)); put("image_ok", n(c.imageOk)); put("note", n(c.note))
    }

    private fun parsed(s: String?): Any = when {
        s == null -> JSONObject.NULL
        else -> runCatching { JSONObject(s) }.getOrElse { s }
    }

    suspend fun exportJson(context: Context): Result {
        val (root, captures) = buildJson(context, withImages = false)
        val name = "bookkeeper-probe-${stamp()}.json"
        return Result(write(context, name, "application/json", root.toString(2).toByteArray()), name, captures.size)
    }

    suspend fun exportZip(context: Context): Result {
        val (root, captures) = buildJson(context, withImages = true)
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { zip ->
            zip.putNextEntry(ZipEntry("samples.json"))
            zip.write(root.toString(2).toByteArray()); zip.closeEntry()
            captures.forEach { c ->
                val f = c.imagePath?.let { File(it) }?.takeIf { it.exists() } ?: return@forEach
                zip.putNextEntry(ZipEntry("images/${c.id}.jpg"))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        val name = "bookkeeper-probe-${stamp()}.zip"
        return Result(write(context, name, "application/zip", buf.toByteArray()), name, captures.size)
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
