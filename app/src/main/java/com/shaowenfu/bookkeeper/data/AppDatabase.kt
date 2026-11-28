package com.shaowenfu.bookkeeper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RawEvent::class, Tx::class, TxEvent::class, LineItem::class, ServiceEvent::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawEvents(): RawEventDao
    abstract fun txs(): TxDao
    abstract fun txEvents(): TxEventDao
    abstract fun lineItems(): LineItemDao
    abstract fun serviceEvents(): ServiceEventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // M1 用新的库文件；探针阶段的 bookkeeper_probe.db 不再使用
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "bookkeeper.db",
                ).build().also { instance = it }
            }
    }
}
