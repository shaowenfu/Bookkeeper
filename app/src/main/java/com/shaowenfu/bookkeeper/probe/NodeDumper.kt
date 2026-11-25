package com.shaowenfu.bookkeeper.probe

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** 把一个窗口的无障碍节点树压平成“有文字的节点”列表。 */
object NodeDumper {
    data class Dump(val nodeCount: Int, val nodesJson: String, val flatText: String, val visibleChars: Int)

    private data class Item(
        val text: String?, val desc: String?, val viewId: String?, val cls: String?,
        val bounds: Rect, val visible: Boolean, val depth: Int,
    )

    private const val MAX_NODES = 4000
    private const val MAX_DEPTH = 80

    fun dump(root: AccessibilityNodeInfo): Dump {
        val items = ArrayList<Item>()
        var count = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_NODES || depth > MAX_DEPTH) return
            count++
            val text = n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val desc = n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            if (text != null || desc != null) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                items += Item(text, desc, n.viewIdResourceName, n.className?.toString(), r, n.isVisibleToUser, depth)
            }
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)

        val arr = JSONArray()
        items.forEach { it ->
            arr.put(JSONObject().apply {
                put("text", it.text ?: JSONObject.NULL)
                put("desc", it.desc ?: JSONObject.NULL)
                put("id", it.viewId ?: JSONObject.NULL)
                put("cls", it.cls ?: JSONObject.NULL)
                put("b", JSONArray().put(it.bounds.left).put(it.bounds.top).put(it.bounds.right).put(it.bounds.bottom))
                put("v", it.visible)
                put("d", it.depth)
            })
        }

        // 可见文字按屏幕位置排序：先按行（top 相差 12px 内算同一行），再从左到右
        val visible = items.filter { it.visible }
            .sortedWith(compareBy<Item>({ it.bounds.top / 12 }, { it.bounds.left }))
        val lines = ArrayList<String>()
        for (it in visible) {
            val line = when {
                it.text != null && it.desc != null && it.desc != it.text -> "${it.text}（${it.desc}）"
                it.text != null -> it.text
                else -> it.desc!!
            }
            if (lines.lastOrNull() != line) lines += line
        }
        val flat = lines.joinToString("\n")
        return Dump(count, arr.toString(), flat, flat.count { !it.isWhitespace() })
    }
}
