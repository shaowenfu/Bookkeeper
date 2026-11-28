package com.shaowenfu.bookkeeper.recognize

import android.content.Context
import com.shaowenfu.bookkeeper.capture.Images
import com.shaowenfu.bookkeeper.capture.Masking
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.RawEvent
import com.shaowenfu.bookkeeper.settings.Prefs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** 识别队列：逐条处理待识别的原始事件，失败的最多重试 3 次。 */
object Recognizer {
    private val mutex = Mutex()

    /** 处理所有待识别事件，直到队列为空。多个入口同时调用时串行执行。 */
    suspend fun processAll(context: Context) = mutex.withLock {
        val dao = AppDatabase.get(context).rawEvents()
        if (Prefs(context).apiKey.isBlank()) {
            // 没有 Key：截图先存着，填好 Key 后再识别
            if (dao.todoCount() > 0) Notifier.missingKey(context.applicationContext)
            return@withLock
        }
        val tried = HashSet<Long>()
        while (true) {
            val next = dao.todo().firstOrNull { it.id !in tried } ?: break
            tried += next.id
            process(context, next)
        }
    }

    private suspend fun process(context: Context, e: RawEvent) {
        val app = context.applicationContext
        val dao = AppDatabase.get(app).rawEvents()
        val prefs = Prefs(app)
        val image = e.imagePath?.takeIf { e.imageStatus == "ok" }?.let { File(it) }?.takeIf { it.exists() }
        val text = e.flatText?.takeIf { e.textChars >= 10 }?.let { Masking.mask(it) }
        if (image == null && text == null) {
            dao.update(e.copy(status = RawEvent.STATUS_NO_TX, lastError = "截图失败且读不到文字", processedAt = System.currentTimeMillis()))
            Notifier.noTransaction(app, e)
            return
        }

        val start = System.currentTimeMillis()
        val attempt = e.copy(attempts = e.attempts + 1, model = prefs.model)
        try {
            val parts = image?.let { Images.prepareForUpload(it) }.orEmpty()
            val out = DeepSeekClient(prefs.baseUrl, prefs.apiKey).chat(
                prefs.model, Prompts.SYSTEM, Prompts.user(e.createdAt, e.packageName, parts.size, text), parts,
            )
            if (out.isBlank()) {
                // 模型偶尔对非付款页（如本 App 自己的页面）返回空内容，重试也一样，按“没有交易”处理
                dao.update(
                    attempt.copy(
                        status = RawEvent.STATUS_NO_TX, resultJson = out, lastError = "模型返回空内容",
                        latencyMs = System.currentTimeMillis() - start, processedAt = System.currentTimeMillis(),
                    )
                )
                Notifier.noTransaction(app, e)
                return
            }
            val json = JSONObject(out)
            val outcomes = Ledger.apply(app, attempt, json)
            dao.update(
                attempt.copy(
                    status = if (outcomes.isEmpty()) RawEvent.STATUS_NO_TX else RawEvent.STATUS_DONE,
                    resultJson = out, lastError = null,
                    latencyMs = System.currentTimeMillis() - start, processedAt = System.currentTimeMillis(),
                )
            )
            if (outcomes.isEmpty()) Notifier.noTransaction(app, e) else Notifier.recorded(app, e, outcomes)
        } catch (ex: Exception) {
            val failed = attempt.copy(
                status = RawEvent.STATUS_FAILED,
                lastError = ex.message ?: ex.toString(),
                latencyMs = System.currentTimeMillis() - start,
            )
            dao.update(failed)
            if (failed.attempts >= RawEvent.MAX_ATTEMPTS) Notifier.failed(app, failed)
        }
    }
}
