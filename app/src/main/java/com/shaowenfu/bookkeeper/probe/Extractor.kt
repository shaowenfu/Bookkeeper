package com.shaowenfu.bookkeeper.probe

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.shaowenfu.bookkeeper.BookkeeperApp
import com.shaowenfu.bookkeeper.R
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.Capture
import com.shaowenfu.bookkeeper.llm.DeepSeekClient
import com.shaowenfu.bookkeeper.llm.Prompts
import com.shaowenfu.bookkeeper.settings.Prefs
import com.shaowenfu.bookkeeper.ui.MainActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** 对一次采样跑“文字路径”和“看图路径”两种识别，并发执行，各自记录耗时。 */
object Extractor {

    fun launch(context: Context, captureId: Long, text: Boolean = true, image: Boolean = true) {
        val app = context.applicationContext
        BookkeeperApp.scope.launch { run(app, captureId, text, image) }
    }

    suspend fun run(context: Context, captureId: Long, text: Boolean, image: Boolean) {
        val prefs = Prefs(context)
        val dao = AppDatabase.get(context).captures()
        val c = dao.get(captureId) ?: return
        if (prefs.apiKey.isBlank()) {
            if (text && !c.flatText.isNullOrBlank()) dao.setTextResult(c.id, prefs.textModel, null, null, "未设置 API Key")
            if (image && c.imageStatus == "ok") dao.setImageResult(c.id, prefs.imageModel, null, null, "未设置 API Key", null)
            notifyResult(context, dao.get(captureId) ?: c)
            return
        }
        val client = DeepSeekClient(prefs.baseUrl, prefs.apiKey)

        coroutineScope {
            val t = async {
                if (!text || c.flatText.isNullOrBlank()) return@async
                val start = System.currentTimeMillis()
                try {
                    val out = client.chat(prefs.textModel, Prompts.SYSTEM, Prompts.textUser(c.packageName, Masking.mask(c.flatText)))
                    dao.setTextResult(c.id, prefs.textModel, out, System.currentTimeMillis() - start, null)
                } catch (e: Exception) {
                    dao.setTextResult(c.id, prefs.textModel, null, System.currentTimeMillis() - start, e.message ?: e.toString())
                }
            }
            val i = async {
                val path = c.imagePath
                if (!image || c.imageStatus != "ok" || path == null || !File(path).exists()) return@async
                val start = System.currentTimeMillis()
                var parts: Int? = null
                try {
                    val imgs = Images.prepareForUpload(File(path))
                    parts = imgs.size
                    val out = client.chat(prefs.imageModel, Prompts.SYSTEM, Prompts.imageUser(imgs.size), imgs)
                    dao.setImageResult(c.id, prefs.imageModel, out, System.currentTimeMillis() - start, null, parts)
                } catch (e: Exception) {
                    dao.setImageResult(c.id, prefs.imageModel, null, System.currentTimeMillis() - start, e.message ?: e.toString(), parts)
                }
            }
            t.await(); i.await()
        }
        dao.get(captureId)?.let { notifyResult(context, it) }
    }

    /** 从识别结果里取出金额列表，用于展示和核对 */
    fun amounts(resultJson: String?): List<String> {
        if (resultJson.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONObject(resultJson).optJSONArray("transactions") ?: return emptyList()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("amount")?.takeIf { a -> a.isNotBlank() && a != "null" } }
        } catch (e: Exception) { emptyList() }
    }

    fun summary(resultJson: String?): String {
        if (resultJson.isNullOrBlank()) return "—"
        return try {
            val o = JSONObject(resultJson)
            val arr = o.optJSONArray("transactions")
            if (arr == null || arr.length() == 0) return "没有识别到交易"
            val first = arr.getJSONObject(0)
            val m = first.optString("merchant").takeIf { it.isNotBlank() && it != "null" } ?: ""
            val more = if (arr.length() > 1) " 等 ${arr.length()} 笔" else ""
            "¥${first.optString("amount")} $m$more".trim()
        } catch (e: Exception) { "结果不是 JSON" }
    }

    /** 看图路径给出的金额，有几笔能在节点文字里找到原样数字（金额二次校验） */
    fun amountsFoundInText(c: Capture): Pair<Int, Int>? {
        val text = c.flatText ?: return null
        val amts = amounts(c.imageResult)
        if (amts.isEmpty()) return null
        val norm = text.replace(",", "")
        return amts.count { a -> norm.contains(a.replace(",", "")) } to amts.size
    }

    private fun notifyResult(context: Context, c: Capture) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val lines = buildList {
            if (c.textResult != null || c.textError != null)
                add("文字：" + (c.textError?.let { "失败 · ${it.take(40)}" } ?: "${summary(c.textResult)} · ${ms(c.textLatencyMs)}"))
            if (c.imageResult != null || c.imageError != null)
                add("看图：" + (c.imageError?.let { "失败 · ${it.take(40)}" } ?: "${summary(c.imageResult)} · ${ms(c.imageLatencyMs)}"))
            if (isEmpty()) add("没有可识别的内容（文字 ${c.textChars} 字，截图 ${c.imageStatus}）")
        }
        val open = PendingIntent.getActivity(
            context, c.id.toInt(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CAPTURE_ID, c.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, BookkeeperApp.CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("采样 #${c.id} 识别结果")
            .setContentText(lines.first())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(1000 + c.id.toInt(), n)
    }

    private fun ms(v: Long?) = if (v == null) "" else String.format("%.1f 秒", v / 1000.0)
}
