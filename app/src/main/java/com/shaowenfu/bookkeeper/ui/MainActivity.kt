package com.shaowenfu.bookkeeper.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.shaowenfu.bookkeeper.R
import com.shaowenfu.bookkeeper.capture.CaptureTileService

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** 从通知点进来时直接打开那笔交易 / 那条识别记录 */
    private val nav = NavState()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
            if (uris.isNotEmpty()) vm.importImages(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                MainScreen(
                    vm = vm,
                    nav = nav,
                    actions = ScreenActions(
                        openAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        openAppDetails = {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:$packageName")))
                        },
                        requestPostNotifications = { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        addTile = ::addTile,
                        pickImages = {
                            pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        share = { uri, mime ->
                            val send = Intent(Intent.ACTION_SEND).setType(mime)
                                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(Intent.createChooser(send, "分享导出文件"))
                        },
                    ),
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val tx = intent?.getLongExtra(EXTRA_TX_ID, -1L) ?: -1L
        val ev = intent?.getLongExtra(EXTRA_EVENT_ID, -1L) ?: -1L
        when {
            tx > 0 -> nav.openTx(tx)
            ev > 0 -> nav.openEvent(ev)
            intent?.hasExtra(EXTRA_EVENT_ID) == true -> nav.openEvents()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        vm.refreshHealth(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    private fun addTile() {
        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(this, CaptureTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_stat_notify),
                mainExecutor,
            ) { result ->
                val msg = when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "磁贴已添加"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "磁贴已经在了"
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "没有添加"
                    else -> "系统不支持一键添加，请下拉通知栏 → 编辑，手动拖入“截图记账”"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "请下拉通知栏 → 编辑，手动拖入“截图记账”", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_TX_ID = "tx_id"
        const val EXTRA_EVENT_ID = "event_id"
    }
}
