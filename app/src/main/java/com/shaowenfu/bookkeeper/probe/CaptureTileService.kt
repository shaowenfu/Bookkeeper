package com.shaowenfu.bookkeeper.probe

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.shaowenfu.bookkeeper.ui.MainActivity

/** 快速设置磁贴：备用入口。只在通知栏展开时被系统绑定，不常驻。 */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val ready = ScreenReaderService.instance != null
        tile.state = if (ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (ready) "读当前屏幕" else "无障碍未连接"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val service = ScreenReaderService.instance
        if (service != null) {
            if (isLocked) unlockAndRun { service.capture(ScreenReaderService.TRIGGER_TILE) }
            else service.capture(ScreenReaderService.TRIGGER_TILE)
            return
        }
        // 无障碍服务没连上：打开 App 首页，提示去开启
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
