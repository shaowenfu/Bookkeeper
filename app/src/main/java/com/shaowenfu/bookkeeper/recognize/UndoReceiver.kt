package com.shaowenfu.bookkeeper.recognize

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.shaowenfu.bookkeeper.BookkeeperApp
import com.shaowenfu.bookkeeper.data.AppDatabase
import kotlinx.coroutines.launch

/** 通知上的“撤销”：把刚记的那笔作废（不删除，可在账本里恢复查看原始截图） */
class UndoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_TX_ID, -1)
        if (id <= 0) return
        val pending = goAsync()
        val app = context.applicationContext
        BookkeeperApp.scope.launch {
            try {
                AppDatabase.get(app).txs().voidTx(id)
                app.getSystemService(NotificationManager::class.java).cancel(Notifier.txNotificationId(id))
            } finally {
                pending.finish()
            }
        }
        Toast.makeText(context, "已撤销", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_TX_ID = "tx_id"
    }
}
