package com.shaowenfu.bookkeeper.recognize

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.shaowenfu.bookkeeper.BookkeeperApp
import com.shaowenfu.bookkeeper.R
import com.shaowenfu.bookkeeper.capture.ScreenReaderService
import com.shaowenfu.bookkeeper.data.RawEvent
import com.shaowenfu.bookkeeper.ui.MainActivity
import com.shaowenfu.bookkeeper.ui.Money

/** 识别结果通知：记了哪笔、合并到哪笔、没识别到、失败。新记的那笔可以直接撤销。 */
object Notifier {
    private const val ID_MISSING_KEY = 9

    fun recorded(context: Context, e: RawEvent, outcomes: List<Ledger.Outcome>) {
        for (o in outcomes) {
            val t = o.tx
            val title = (if (o.merged) "已补充 " else "已记账 ") + Money.signed(t.amountCents, t.direction)
            val text = listOfNotNull(t.app, t.merchant, t.category).joinToString(" · ") +
                if (t.needsReview) " · 待核对" else ""
            val b = base(context, title, text.ifEmpty { "打开查看" }, openTx(context, t.id))
            if (!o.merged) {
                val undo = PendingIntent.getBroadcast(
                    context, t.id.toInt(),
                    Intent(context, UndoReceiver::class.java).putExtra(UndoReceiver.EXTRA_TX_ID, t.id),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                b.addAction(Notification.Action.Builder(null, "撤销", undo).build())
            }
            notify(context, txNotificationId(t.id), b.build())
        }
    }

    fun noTransaction(context: Context, e: RawEvent) {
        // 唤醒时自动识别的那次，如果屏幕上没有付款信息就不打扰
        if (e.source == ScreenReaderService.TRIGGER_AUTO_WAKE) return
        notify(context, 5000 + e.id.toInt(), base(context, "没识别到付款信息", "打开识别记录查看截图", openEvent(context, e.id)).build())
    }

    fun failed(context: Context, e: RawEvent) {
        notify(
            context, 5000 + e.id.toInt(),
            base(context, "识别失败", (e.lastError ?: "").take(60) + "。打开识别记录可以重试", openEvent(context, e.id)).build(),
        )
    }

    fun missingKey(context: Context) {
        notify(context, ID_MISSING_KEY, base(context, "还没有设置 DeepSeek API Key", "截图已保存，填好 Key 后会自动识别", openEvent(context, -1)).build())
    }

    fun txNotificationId(txId: Long) = 2000 + txId.toInt()

    private fun base(context: Context, title: String, text: String, intent: PendingIntent) =
        Notification.Builder(context, BookkeeperApp.CHANNEL_LEDGER)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(intent)
            .setAutoCancel(true)

    private fun openTx(context: Context, txId: Long) = PendingIntent.getActivity(
        context, txId.toInt(),
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_TX_ID, txId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openEvent(context: Context, eventId: Long) = PendingIntent.getActivity(
        context, 100_000 + eventId.toInt(),
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_EVENT_ID, eventId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notify(context: Context, id: Int, n: Notification) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}
