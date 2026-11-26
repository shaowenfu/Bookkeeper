package com.shaowenfu.bookkeeper.capture

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.RawEvent
import com.shaowenfu.bookkeeper.recognize.RecognitionRunner
import java.io.File

/** 分享或从相册选择的截图（支付页、订单页、账单长截图）。复制到 App 私有目录后排队识别。 */
object Importer {
    suspend fun importUris(context: Context, uris: List<Uri>, source: String): List<Long> {
        val app = context.applicationContext
        val dao = AppDatabase.get(app).rawEvents()
        val ids = ArrayList<Long>()
        val now = System.currentTimeMillis()
        uris.forEachIndexed { i, uri ->
            val f = File(app.filesDir, "shots/import-$now-$i")
            f.parentFile?.mkdirs()
            val ok = runCatching {
                app.contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
            }.isSuccess
            val size = if (ok) Images.size(f) else null
            val status = when {
                !ok -> "error:copy"
                size == null || size.w <= 0 -> "error:decode"
                else -> {
                    val small = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 })
                    val black = small != null && Images.isMostlyBlack(small)
                    small?.recycle()
                    if (black) "black" else "ok"
                }
            }
            ids += dao.insert(
                RawEvent(createdAt = now + i, source = source, imagePath = if (ok) f.absolutePath else null, imageStatus = status)
            )
        }
        if (ids.isNotEmpty()) RecognitionRunner.kick(app)
        return ids
    }
}
