package com.shaowenfu.bookkeeper.capture

import android.app.Notification
import android.os.Bundle
import android.os.Parcelable
import org.json.JSONArray
import org.json.JSONObject

/** 把 Notification.extras 里的每一项都转成可读字符串 / JSON，图片等二进制只记类型。 */
object ExtrasStringifier {
    private const val MAX_DEPTH = 4
    private const val MAX_STRING = 8_000

    fun notificationToJson(n: Notification): JSONObject {
        val root = JSONObject()
        root.put("extras", bundleToJson(n.extras, 0))
        n.actions?.let { actions ->
            val arr = JSONArray()
            actions.forEach { a -> arr.put(a?.title?.toString()) }
            root.put("_actions", arr)
        }
        root.put("_ticker", n.tickerText?.toString())
        root.put("_category", n.category)
        root.put("_channel_id", n.channelId)
        root.put("_group", n.group)
        root.put("_sort_key", n.sortKey)
        root.put("_flags", n.flags)
        root.put("_visibility", n.visibility)
        root.put("_when", n.`when`)
        root.put("_has_content_intent", n.contentIntent != null)
        n.publicVersion?.let { pv -> root.put("_public_version", bundleToJson(pv.extras, 1)) }
        return root
    }

    fun bundleToJson(b: Bundle?, depth: Int): JSONObject {
        val obj = JSONObject()
        if (b == null) return obj
        val keys = try { b.keySet().toList() } catch (e: Exception) {
            obj.put("_error", "keySet: ${e.javaClass.simpleName}")
            return obj
        }
        for (k in keys.sorted()) {
            val v = try {
                @Suppress("DEPRECATION")
                b.get(k)
            } catch (e: Throwable) {
                obj.put(k, "<unreadable ${e.javaClass.simpleName}>")
                continue
            }
            obj.put(k, valueToJson(v, depth + 1))
        }
        return obj
    }

    private fun valueToJson(v: Any?, depth: Int): Any {
        if (v == null) return JSONObject.NULL
        if (depth > MAX_DEPTH) return "<depth limit> ${v.javaClass.name}"
        return when (v) {
            is CharSequence -> clip(v.toString())
            is Boolean, is Int, is Long, is Double, is Float, is Short, is Byte -> v
            is Char -> v.toString()
            is Bundle -> bundleToJson(v, depth)
            is Array<*> -> JSONArray().also { arr -> v.forEach { arr.put(valueToJson(it, depth + 1)) } }
            is List<*> -> JSONArray().also { arr -> v.forEach { arr.put(valueToJson(it, depth + 1)) } }
            is IntArray -> JSONArray(v.toList())
            is LongArray -> JSONArray(v.toList())
            is BooleanArray -> JSONArray(v.toList())
            is FloatArray -> JSONArray(v.map { it.toDouble() })
            is DoubleArray -> JSONArray(v.toList())
            is ByteArray -> "<bytes ${v.size}>"
            is android.graphics.Bitmap -> "<bitmap ${v.width}x${v.height}>"
            is android.graphics.drawable.Icon -> "<icon type=${v.type}>"
            is android.app.Person -> JSONObject().apply {
                put("_type", "Person")
                put("name", v.name?.toString())
                put("key", v.key)
            }
            is Parcelable -> "<${v.javaClass.name}> ${clip(safeToString(v))}"
            else -> clip(safeToString(v))
        }
    }

    private fun safeToString(v: Any): String = try { v.toString() } catch (e: Throwable) { "<toString failed>" }

    private fun clip(s: String): String = if (s.length > MAX_STRING) s.substring(0, MAX_STRING) + "…<clipped>" else s
}
