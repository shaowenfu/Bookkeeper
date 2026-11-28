package com.shaowenfu.bookkeeper.recognize

import android.content.Context
import androidx.room.withTransaction
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.LineItem
import com.shaowenfu.bookkeeper.data.RawEvent
import com.shaowenfu.bookkeeper.data.Tx
import com.shaowenfu.bookkeeper.data.TxEvent
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 把一次识别结果写进账本。
 * 同一笔钱可能从支付成功页、订单详情、账单列表多次进来：同金额、同方向、时间接近的合并成一笔，只补空缺字段。
 */
object Ledger {
    data class Outcome(val tx: Tx, val merged: Boolean)

    private val DIRECTIONS = setOf("expense", "refund", "income", "transfer", "repayment")
    private const val MIN = 60_000L

    /** 屏幕文字里确实出现过金额，才拿它核对模型读出的金额 */
    private val MONEY_IN_TEXT = Regex("[¥￥]\\s*\\d|\\d+\\.\\d{2}")

    suspend fun apply(context: Context, raw: RawEvent, result: JSONObject): List<Outcome> {
        val db = AppDatabase.get(context)
        val arr = result.optJSONArray("transactions") ?: return emptyList()
        val pageApp = clean(result.optString("app"))?.takeIf { it != "其他" } ?: Prompts.appName(raw.packageName)
        val outcomes = ArrayList<Outcome>()

        db.withTransaction {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cents = parseCents(o.optString("amount")) ?: continue
                if (cents <= 0) continue
                val direction = o.optString("direction").takeIf { it in DIRECTIONS } ?: "expense"
                val parsedTime = parseTime(clean(o.optString("time")))
                val estimated = parsedTime == null
                val occurredAt = parsedTime ?: raw.createdAt
                val confidence = o.optDouble("confidence", 1.0)
                val amountStr = BigDecimal(cents).movePointLeft(2).setScale(2).toPlainString()
                val textMismatch = !raw.flatText.isNullOrBlank() && raw.textChars >= 10 && MONEY_IN_TEXT.containsMatchIn(raw.flatText) &&
                    !raw.flatText.replace(",", "").contains(amountStr)
                val items = parseItems(o.optJSONArray("items"))
                val now = System.currentTimeMillis()

                val window = if (estimated) 30 * MIN else 15 * MIN
                val alreadyFromThisEvent = db.txEvents().txIdsFor(raw.id).toSet()
                val cand = db.txs().candidates(cents, direction, occurredAt, occurredAt - window, occurredAt + window)
                    .firstOrNull { it.id !in alreadyFromThisEvent }

                if (cand != null) {
                    val merged = cand.copy(
                        updatedAt = now,
                        occurredAt = if (cand.timeEstimated && !estimated) occurredAt else cand.occurredAt,
                        timeEstimated = cand.timeEstimated && estimated,
                        merchant = cand.merchant ?: clean(o.optString("merchant")),
                        app = cand.app ?: pageApp,
                        channel = cand.channel ?: clean(o.optString("channel")),
                        funding = cand.funding ?: clean(o.optString("funding")),
                        category = cand.category ?: clean(o.optString("category")),
                        isAutoDebit = cand.isAutoDebit || o.optBoolean("is_auto_debit"),
                    )
                    db.txs().update(merged)
                    db.txEvents().insert(TxEvent(cand.id, raw.id, "enrich"))
                    if (items.isNotEmpty() && db.lineItems().count(cand.id) == 0) {
                        db.lineItems().insertAll(items.map { it.copy(txId = cand.id, rawEventId = raw.id) })
                    }
                    outcomes += Outcome(merged, merged = true)
                } else {
                    val tx = Tx(
                        createdAt = now, updatedAt = now,
                        occurredAt = occurredAt, timeEstimated = estimated,
                        amountCents = cents, direction = direction,
                        merchant = clean(o.optString("merchant")),
                        app = pageApp,
                        channel = clean(o.optString("channel")),
                        funding = clean(o.optString("funding")),
                        category = clean(o.optString("category")),
                        isAutoDebit = o.optBoolean("is_auto_debit"),
                        needsReview = textMismatch || confidence < 0.6,
                    )
                    val id = db.txs().insert(tx)
                    db.txEvents().insert(TxEvent(id, raw.id, "source"))
                    if (items.isNotEmpty()) db.lineItems().insertAll(items.map { it.copy(txId = id, rawEventId = raw.id) })
                    outcomes += Outcome(tx.copy(id = id), merged = false)
                }
            }
        }
        return outcomes
    }

    fun parseCents(s: String?): Long? {
        val t = s?.replace(",", "")?.replace("¥", "")?.replace("￥", "")?.trim()?.removePrefix("-")?.removePrefix("+") ?: return null
        if (t.isEmpty() || t == "null") return null
        return try {
            BigDecimal(t).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        } catch (e: Exception) { null }
    }

    private fun parseTime(s: String?): Long? {
        if (s == null) return null
        for (p in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            try {
                val f = SimpleDateFormat(p, Locale.US).apply { isLenient = false }
                return f.parse(s)?.time
            } catch (_: Exception) { }
        }
        return null
    }

    private fun parseItems(arr: JSONArray?): List<LineItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = clean(o.optString("name")) ?: return@mapNotNull null
            LineItem(
                txId = 0, name = name,
                qty = if (o.has("qty") && !o.isNull("qty")) o.optDouble("qty") else null,
                amountCents = parseCents(o.optString("amount")),
            )
        }
    }

    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
