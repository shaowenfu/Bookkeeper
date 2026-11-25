package com.shaowenfu.bookkeeper.probe

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.Capture
import com.shaowenfu.bookkeeper.settings.Prefs
import java.io.File

/** 方案一：分享或从相册选择的截图。复制到 App 私有目录后再识别（只走看图路径）。 */
object Importer {
    suspend fun importUris(context: Context, uris: List<Uri>, trigger: String): List<Long> {
        val app = context.applicationContext
        val dao = AppDatabase.get(app).captures()
        val prefs = Prefs(app)
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
            val id = dao.insert(
                Capture(
                    createdAt = now + i, trigger = trigger,
                    imagePath = if (ok) f.absolutePath else null, imageStatus = status,
                    imageW = size?.w, imageH = size?.h,
                )
            )
            ids += id
            if (prefs.autoImage && status == "ok") Extractor.launch(app, id, text = false, image = true)
        }
        return ids
    }
}
