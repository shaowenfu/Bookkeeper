package com.shaowenfu.bookkeeper.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条原始通知样本。只追加、不改内容（仅 repost 计数 / removed_at 会更新），
 * M1 时可以原样迁移成 raw_event（source = package_name, kind = "notification", payload = extras_json）。
 */
@Entity(
    tableName = "notification_sample",
    indices = [
        Index(value = ["package_name", "sbn_key"]),
        Index(value = ["post_time"]),
        Index(value = ["content_hash"]),
    ],
)
data class NotificationSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    /** StatusBarNotification.key，同一条通知更新时保持不变 */
    @ColumnInfo(name = "sbn_key") val sbnKey: String,
    @ColumnInfo(name = "notification_id") val notificationId: Int,
    @ColumnInfo(name = "tag") val tag: String?,
    /** 系统记录的通知发出时间（ms） */
    @ColumnInfo(name = "post_time") val postTime: Long,
    /** 本 App 收到回调的时间（ms） */
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "tz_id") val tzId: String,
    /** posted = onNotificationPosted；snapshot = 监听重连时补抓的当前活动通知 */
    @ColumnInfo(name = "capture_source") val captureSource: String,
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "text") val text: String?,
    @ColumnInfo(name = "big_text") val bigText: String?,
    @ColumnInfo(name = "sub_text") val subText: String?,
    @ColumnInfo(name = "summary_text") val summaryText: String?,
    @ColumnInfo(name = "text_lines") val textLines: String?,
    @ColumnInfo(name = "ticker_text") val tickerText: String?,
    @ColumnInfo(name = "category") val category: String?,
    @ColumnInfo(name = "channel_id") val channelId: String?,
    @ColumnInfo(name = "group_key") val groupKey: String?,
    @ColumnInfo(name = "is_group_summary") val isGroupSummary: Boolean,
    @ColumnInfo(name = "is_ongoing") val isOngoing: Boolean,
    @ColumnInfo(name = "flags") val flags: Int,
    /** 全部 extras + actions 等，逐项转成字符串后的 JSON */
    @ColumnInfo(name = "extras_json") val extrasJson: String,
    /** package|title|text|bigText|subText|textLines 的 SHA-256，用于去重 */
    @ColumnInfo(name = "content_hash") val contentHash: String,
    /** 同一个 key 之前出现过、但内容变了 → 这是一条更新 */
    @ColumnInfo(name = "is_update") val isUpdate: Boolean,
    /** is_update 时，指向同 key 的上一条样本 */
    @ColumnInfo(name = "previous_id") val previousId: Long?,
    /** 不同 key 但内容完全相同、且在短时间内出现 → 疑似重复推送（仍然保留） */
    @ColumnInfo(name = "possible_duplicate_of") val possibleDuplicateOf: Long?,
    /** 同 key + 同内容的重发次数（重发不新增行，只在这里计数） */
    @ColumnInfo(name = "repost_count") val repostCount: Int = 0,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "removed_at") val removedAt: Long? = null,
    @ColumnInfo(name = "removed_reason") val removedReason: Int? = null,
)

/** 监听器生命周期日志：用来找出 7 天里的“漏听”时间段 */
@Entity(tableName = "listener_event", indices = [Index(value = ["at"])])
data class ListenerEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    /** connected / disconnected / permission_lost / permission_ok / rebind_requested / keepalive_started / check */
    val type: String,
    val detail: String? = null,
)

/** 白名单之外的包名（只记包名和次数，不存内容），用来核对包名是否写对 */
@Entity(tableName = "seen_package")
data class SeenPackage(
    @PrimaryKey @ColumnInfo(name = "package_name") val packageName: String,
    val count: Int,
    @ColumnInfo(name = "first_seen_at") val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
)

data class PackageCount(
    @ColumnInfo(name = "package_name") val packageName: String,
    val total: Int,
    val updates: Int,
    @ColumnInfo(name = "possible_dups") val possibleDups: Int,
    val reposts: Int,
    @ColumnInfo(name = "last_post_time") val lastPostTime: Long?,
)
