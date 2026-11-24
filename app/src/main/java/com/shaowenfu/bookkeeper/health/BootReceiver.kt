package com.shaowenfu.bookkeeper.health

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / App 更新后自动拉起前台服务（Application.onCreate 也会调度自检任务） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ListenerHealth.log(context, "boot_or_update", intent.action)
        KeepAliveService.start(context)
    }
}
