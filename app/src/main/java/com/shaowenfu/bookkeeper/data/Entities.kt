package com.shaowenfu.bookkeeper.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 原始事件：每一次触发（音量键 / 磁贴 / 唤醒自动识别）或每一张导入的截图。
 * 只追加，识别结果写回 result_json；账本里的交易由它合并而来，出错时可以重新识别。
 */
@Entity(tableName = "raw_event", indices = [Index("status")])
data class RawEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** a11y_shortcut / auto_wake / tile / share / gallery */
    val source: String,
    @ColumnInfo(name = "package_name") val packageName: String? = null,
    @ColumnInfo(name = "image_path") val imagePath: String? = null,
    /** ok / black / error:<原因> / none */
    @ColumnInfo(name = "image_status") val imageStatus: String = "none",
    /** 无障碍节点文字（微信等页面为空） */
    @ColumnInfo(name = "flat_text") val flatText: String? = null,
    @ColumnInfo(name = "text_chars") val textChars: Int = 0,
    /** pending / done / no_tx / failed */
    val status: String = STATUS_PENDING,
    val attempts: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    val model: String? = null,
    @ColumnInfo(name = "result_json") val resultJson: String? = null,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long? = null,
    @ColumnInfo(name = "processed_at") val processedAt: Long? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_DONE = "done"
        const val STATUS_NO_TX = "no_tx"
        const val STATUS_FAILED = "failed"
        const val MAX_ATTEMPTS = 3
    }
}

/** 账本上的一笔钱。金额统一用“分”。 */
@Entity(tableName = "tx", indices = [Index("occurred_at"), Index("amount_cents")])
data class Tx(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "occurred_at") val occurredAt: Long,
    /** 屏幕上没有交易时间、用的是截图时间 */
    @ColumnInfo(name = "time_estimated") val timeEstimated: Boolean,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    /** expense / refund / income / transfer / repayment */
    val direction: String,
    val merchant: String? = null,
    /** 微信 / 支付宝 / 美团 / … */
    val app: String? = null,
    /** 支付渠道，例如 微信支付、美团月付 */
    val channel: String? = null,
    /** 付款方式，例如 中国银行储蓄卡、零钱、花呗 */
    val funding: String? = null,
    val category: String? = null,
    @ColumnInfo(name = "is_auto_debit") val isAutoDebit: Boolean = false,
    /** 金额没能在屏幕文字里核对上，或模型把握不大 */
    @ColumnInfo(name = "needs_review") val needsReview: Boolean = false,
    /** confirmed / void */
    val status: String = STATUS_CONFIRMED,
    val note: String? = null,
) {
    companion object {
        const val STATUS_CONFIRMED = "confirmed"
        const val STATUS_VOID = "void"
    }
}

/** 交易由哪些原始事件合并而来。role：source（新建）/ enrich（补充） */
@Entity(
    tableName = "tx_event",
    primaryKeys = ["tx_id", "raw_event_id"],
    foreignKeys = [ForeignKey(entity = Tx::class, parentColumns = ["id"], childColumns = ["tx_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("raw_event_id")],
)
data class TxEvent(
    @ColumnInfo(name = "tx_id") val txId: Long,
    @ColumnInfo(name = "raw_event_id") val rawEventId: Long,
    val role: String,
)

@Entity(
    tableName = "line_item",
    foreignKeys = [ForeignKey(entity = Tx::class, parentColumns = ["id"], childColumns = ["tx_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("tx_id")],
)
data class LineItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "tx_id") val txId: Long,
    val name: String,
    val qty: Double? = null,
    @ColumnInfo(name = "amount_cents") val amountCents: Long? = null,
    @ColumnInfo(name = "raw_event_id") val rawEventId: Long? = null,
)

/** 无障碍服务连接 / 断开记录 */
@Entity(tableName = "service_event")
data class ServiceEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val type: String,
    val detail: String? = null,
)
