package com.shaowenfu.bookkeeper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Capture::class, ServiceEvent::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun captures(): CaptureDao
    abstract fun serviceEvents(): ServiceEventDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        // 新文件名：和 M0 的 bookkeeper_m0.db 分开，M0 的样本你已经导出过了
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "bookkeeper_probe.db",
                ).build().also { instance = it }
            }
    }
}
