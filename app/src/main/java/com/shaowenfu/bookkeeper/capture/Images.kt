package com.shaowenfu.bookkeeper.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

object Images {
    /** 截图是否基本全黑（FLAG_SECURE 页面截出来就是黑的）。抽样 48×48 个点。 */
    fun isMostlyBlack(bmp: Bitmap): Boolean {
        val n = 48
        var dark = 0
        var total = 0
        for (yi in 0 until n) for (xi in 0 until n) {
            val x = (bmp.width - 1) * xi / (n - 1)
            val y = (bmp.height - 1) * yi / (n - 1)
            val p = bmp.getPixel(x, y)
            val r = (p shr 16) and 0xff; val g = (p shr 8) and 0xff; val b = p and 0xff
            if (r < 12 && g < 12 && b < 12) dark++
            total++
        }
        return dark.toDouble() / total > 0.97
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 88) {
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    data class Size(val w: Int, val h: Int)

    fun size(file: File): Size {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        return Size(o.outWidth, o.outHeight)
    }

    /**
     * 准备上传：宽度缩到 ≤ 1080；长截图（高 > 2.5 倍宽，普通 1080×2400 截图不切）切成若干段，每段高 2 倍宽、相邻重叠 8%。
     * 模型每张图最多约 1024 token，整张长图直接传会被缩到看不清。
     */
    fun prepareForUpload(file: File, maxWidth: Int = 1080): List<String> {
        val s = size(file)
        var sample = 1
        while (s.w / (sample * 2) >= maxWidth) sample *= 2
        val decoded = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("图片无法解码")
        val bmp = if (decoded.width > maxWidth) {
            Bitmap.createScaledBitmap(decoded, maxWidth, decoded.height * maxWidth / decoded.width, true)
                .also { if (it !== decoded) decoded.recycle() }
        } else decoded

        val parts = ArrayList<String>()
        if (bmp.height <= bmp.width * 25 / 10) {
            parts += b64(bmp)
        } else {
            val partH = bmp.width * 2
            val step = partH * 92 / 100
            var top = 0
            while (top < bmp.height) {
                val h = minOf(partH, bmp.height - top)
                val piece = Bitmap.createBitmap(bmp, 0, top, bmp.width, h)
                parts += b64(piece)
                piece.recycle()
                if (top + h >= bmp.height) break
                top += step
            }
        }
        bmp.recycle()
        return parts
    }

    private fun b64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
