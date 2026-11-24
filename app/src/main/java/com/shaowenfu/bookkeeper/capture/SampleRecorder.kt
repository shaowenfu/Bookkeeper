package com.shaowenfu.bookkeeper.capture

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import com.shaowenfu.bookkeeper.Whitelist
import com.shaowenfu.bookkeeper.data.AppDatabase
import com.shaowenfu.bookkeeper.data.NotificationSample
import com.shaowenfu.bookkeeper.data.SeenPackage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.TimeZone

/**
 * 把一条 StatusBarNotification 存成样本。
 *
 * 去重规则（原始数据都保留）：
 *  - 同 key + 内容完全相同 → 视为重发，不新增行，只给原样本 repost_count + 1；
 *  - 同 key + 内容不同 → 新增一行，is_update = true，previous_id 指向上一条；
 *  - 不同 key + 内容相同 + 10 分钟内 → 新增一行，possible_duplicate_of 指向最早那条。
 */
object SampleRecorder {
    private val mutex = Mutex()
    private const val DUP_WINDOW_MS = 10 * 60 * 1000L

    suspend fun record(context: Context, sbn: StatusBarNotification, source: String) {
        val pkg = sbn.packageName ?: return
        val db = AppDatabase.get(context)
        val now = System.currentTimeMillis()

        if (pkg !in Whitelist.packages(context)) {
            if (source == "posted" && pkg != context.packageName) notePackage(db, pkg, now)
            return
        }

        val n = sbn.notification ?: return
        val ex = n.extras
        val title = ex?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: ex?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
        val text = ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = ex?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val summary = ex?.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        val lines = ex?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it?.toString().orEmpty() }
        val extrasJson = try {
            ExtrasStringifier.notificationToJson(n).toString()
        } catch (e: Throwable) {
            "{\"_error\":\"${e.javaClass.simpleName}\"}"
        }
        val hash = sha256(listOf(pkg, title, text, bigText, subText, lines).joinToString("\u0001") { it ?: "" })

        mutex.withLock {
            val dao = db.samples()
            val prev = dao.latestForKey(pkg, sbn.key)
            if (prev != null && prev.contentHash == hash) {
                dao.bumpRepost(prev.id, now)
                return
            }
            val dupOf = if (prev == null) {
                dao.firstSameContentOtherKey(pkg, hash, sbn.key, sbn.postTime - DUP_WINDOW_MS)?.id
            } else null

            dao.insert(
                NotificationSample(
                    packageName = pkg,
                    sbnKey = sbn.key,
                    notificationId = sbn.id,
                    tag = sbn.tag,
                    postTime = sbn.postTime,
                    receivedAt = now,
                    tzId = TimeZone.getDefault().id,
                    captureSource = source,
                    title = title,
                    text = text,
                    bigText = bigText,
                    subText = subText,
                    summaryText = summary,
                    textLines = lines,
                    tickerText = n.tickerText?.toString(),
                    category = n.category,
                    channelId = n.channelId,
                    groupKey = sbn.groupKey,
                    isGroupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
                    isOngoing = sbn.isOngoing,
                    flags = n.flags,
                    extrasJson = extrasJson,
                    contentHash = hash,
                    isUpdate = prev != null,
                    previousId = prev?.id,
                    possibleDuplicateOf = dupOf,
                    lastSeenAt = now,
                )
            )
        }
    }

    suspend fun recordRemoved(context: Context, sbn: StatusBarNotification, reason: Int) {
        val pkg = sbn.packageName ?: return
        if (pkg !in Whitelist.packages(context)) return
        AppDatabase.get(context).samples().markRemoved(pkg, sbn.key, System.currentTimeMillis(), reason)
    }

    private suspend fun notePackage(db: AppDatabase, pkg: String, now: Long) {
        mutex.withLock {
            val dao = db.seenPackages()
            if (dao.get(pkg) == null) dao.insert(SeenPackage(pkg, 1, now, now)) else dao.bump(pkg, now)
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
