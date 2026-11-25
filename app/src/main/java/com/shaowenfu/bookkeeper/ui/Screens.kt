package com.shaowenfu.bookkeeper.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shaowenfu.bookkeeper.data.Capture
import com.shaowenfu.bookkeeper.probe.Extractor
import com.shaowenfu.bookkeeper.probe.ScreenReaderService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScreenActions(
    val openAccessibilitySettings: () -> Unit,
    val openAppDetails: () -> Unit,
    val requestPostNotifications: () -> Unit,
    val addTile: () -> Unit,
    val pickImages: () -> Unit,
    val share: (Uri, String) -> Unit,
)

/** 标注用的页面类型，对应方案文档里“要采的页面” */
val PAGE_LABELS = listOf(
    "微信支付成功", "微信账单", "支付宝支付成功", "支付宝账单",
    "美团订单", "美团月付账单", "抖音订单", "抖音月付账单",
    "京东订单", "小红书订单", "中行交易明细", "小票", "其他",
)

private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
private fun fmt(ms: Long) = timeFmt.format(Date(ms))
private fun sec(ms: Long?) = if (ms == null) "—" else String.format(Locale.US, "%.1fs", ms / 1000.0)

private fun triggerLabel(t: String) = when (t) {
    ScreenReaderService.TRIGGER_BUTTON -> "无障碍按钮"
    ScreenReaderService.TRIGGER_TILE -> "磁贴"
    "share" -> "分享"
    "gallery" -> "相册"
    else -> t
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, openCaptureId: MutableState<Long?>, actions: ScreenActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.clearMessage() }
    }
    LaunchedEffect(openCaptureId.value) { if (openCaptureId.value != null) tab = 1 }

    Scaffold(
        topBar = { TopAppBar(title = { Text("读屏记账探针 M0′") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                listOf("概览", "样本", "设置").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> Overview(vm, actions)
                1 -> {
                    val id = openCaptureId.value
                    if (id == null) CaptureList(vm) { openCaptureId.value = it }
                    else CaptureDetail(vm, id) { openCaptureId.value = null }
                }
                else -> SettingsPane(vm)
            }
        }
    }
}

@Composable
private fun StatusRow(ok: Boolean?, title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            when (ok) { true -> "●"; false -> "●"; null -> "○" },
            color = when (ok) { true -> Color(0xFF2E7D32); false -> Color(0xFFC62828); null -> Color.Gray },
            modifier = Modifier.padding(end = 10.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun Overview(vm: MainViewModel, actions: ScreenActions) {
    val h by vm.health.collectAsStateWithLifecycle()
    val captures by vm.captures.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val lastExport by vm.lastExport.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card { Column(Modifier.padding(16.dp)) {
                Text("准备", style = MaterialTheme.typography.titleMedium)
                StatusRow(
                    ok = h.serviceConnected,
                    title = "无障碍服务“读屏记账”",
                    detail = when {
                        h.serviceConnected -> "已连接"
                        h.serviceEnabled -> "设置里开着但没连上（可能被系统杀了），关掉再打开一次"
                        else -> "未开启。侧载 App 如果开关是灰的：先到应用信息 → 右上角 ⋮ → 允许受限制的设置"
                    },
                    action = "去设置", onAction = actions.openAccessibilitySettings,
                )
                if (!h.serviceConnected) {
                    TextButton(onClick = actions.openAppDetails) { Text("打开应用信息（允许受限制的设置）") }
                }
                StatusRow(
                    ok = null, title = "无障碍悬浮按钮",
                    detail = "无障碍设置 → 读屏记账 → 快捷方式，选“无障碍按钮”。付完款点一下屏幕边上的按钮即可",
                )
                StatusRow(ok = null, title = "快速设置磁贴（备用）", detail = "下拉通知栏点“读屏记账”", action = "添加", onAction = actions.addTile)
                StatusRow(
                    ok = h.notificationsAllowed, title = "通知权限",
                    detail = if (h.notificationsAllowed) "识别完成后会弹一条结果通知" else "未允许：看不到识别结果通知",
                    action = if (h.notificationsAllowed) null else "允许", onAction = actions.requestPostNotifications,
                )
                StatusRow(ok = h.apiKeySet, title = "DeepSeek API Key", detail = if (h.apiKeySet) "已设置" else "未设置：只采样不识别，到“设置”页填写")
            } }
        }
        item {
            Card { Column(Modifier.padding(16.dp)) {
                Text("方案一：导入截图", style = MaterialTheme.typography.titleMedium)
                Text("也可以在相册或截图预览里点“分享 → 读屏记账”。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = actions.pickImages, modifier = Modifier.padding(top = 8.dp)) { Text("从相册选择（可多选）") }
            } }
        }
        item {
            val black = captures.count { it.imageStatus == "black" }
            val withText = captures.count { it.textChars >= 20 }
            val labeled = captures.count { it.pageLabel != null }
            Card { Column(Modifier.padding(16.dp)) {
                Text("样本 ${captures.size} 条", style = MaterialTheme.typography.titleMedium)
                Text("读到文字（≥20 字）$withText · 截图黑屏 $black · 已标注 $labeled", style = MaterialTheme.typography.bodySmall)
                captures.groupingBy { triggerLabel(it.trigger) }.eachCount().forEach { (k, v) ->
                    Text("$k：$v", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.export(false) }) { Text("导出 JSON") }
                    OutlinedButton(onClick = { vm.export(true) }) { Text("导出 ZIP（含截图）") }
                }
                lastExport?.let { (uri, mime) -> TextButton(onClick = { actions.share(uri, mime) }) { Text("分享刚导出的文件") } }
            } }
        }
        item {
            Card { Column(Modifier.padding(16.dp)) {
                Text("无障碍服务连接记录", style = MaterialTheme.typography.titleMedium)
                if (events.isEmpty()) Text("暂无", style = MaterialTheme.typography.bodySmall)
                events.take(30).forEach { e ->
                    Text("${fmt(e.at)}  ${e.type}${e.detail?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            } }
        }
    }
}

@Composable
private fun CaptureList(vm: MainViewModel, onOpen: (Long) -> Unit) {
    val captures by vm.captures.collectAsStateWithLifecycle()
    if (captures.isEmpty()) {
        Text("还没有样本。付完款停在成功页，点一下无障碍按钮试试。", Modifier.padding(16.dp))
        return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(captures, key = { it.id }) { c ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(c.id) }) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text("#${c.id} · ${fmt(c.createdAt)} · ${triggerLabel(c.trigger)}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        c.pageLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    }
                    Text(
                        listOfNotNull(c.packageName, "文字 ${c.textChars} 字", "截图${ScreenReaderService.statusLabel(c.imageStatus)}").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (c.textResult != null || c.textError != null)
                        Text("文字：${c.textError?.let { "失败" } ?: Extractor.summary(c.textResult)} · ${sec(c.textLatencyMs)}", style = MaterialTheme.typography.bodySmall)
                    if (c.imageResult != null || c.imageError != null)
                        Text("看图：${c.imageError?.let { "失败" } ?: Extractor.summary(c.imageResult)} · ${sec(c.imageLatencyMs)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptureDetail(vm: MainViewModel, id: Long, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val c by vm.observe(id).collectAsState(initial = null)
    val cap = c ?: run {
        Column(Modifier.padding(16.dp)) { TextButton(onClick = onBack) { Text("← 返回") }; Text("样本不存在或已删除") }
        return
    }

    var label by remember(cap.id) { mutableStateOf(cap.pageLabel) }
    var amount by remember(cap.id) { mutableStateOf(cap.trueAmount ?: "") }
    var textOk by remember(cap.id) { mutableStateOf(cap.textOk) }
    var imageOk by remember(cap.id) { mutableStateOf(cap.imageOk) }
    var note by remember(cap.id) { mutableStateOf(cap.note ?: "") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("← 返回列表") }
        Text("#${cap.id} · ${fmt(cap.createdAt)} · ${triggerLabel(cap.trigger)}", style = MaterialTheme.typography.titleMedium)
        Text(
            "App：${cap.packageName ?: "—"}  窗口：${cap.windowTitle ?: "—"}\n" +
                "节点 ${cap.nodeCount} 个 · 可见文字 ${cap.textChars} 字（首次 ${cap.textCharsFirst}${if (cap.retriedRead) "，重读过" else ""}） · 读取 ${sec(cap.readMs)}\n" +
                "截图：${ScreenReaderService.statusLabel(cap.imageStatus)}${cap.imageW?.let { " ${it}×${cap.imageH}" } ?: ""} · ${sec(cap.screenshotMs)}",
            style = MaterialTheme.typography.bodySmall,
        )

        // ---- 标注 ----
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("标注（用来算准确率）", fontWeight = FontWeight.Medium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PAGE_LABELS.forEach { l -> FilterChip(selected = label == l, onClick = { label = if (label == l) null else l }, label = { Text(l) }) }
            }
            OutlinedTextField(amount, { amount = it }, label = { Text("实际金额（多笔用逗号分隔）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TriState("文字路径正确？", textOk) { textOk = it }
            TriState("看图路径正确？", imageOk) { imageOk = it }
            OutlinedTextField(note, { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { vm.saveLabels(cap.id, label, amount, textOk, imageOk, note) }) { Text("保存标注") }
        } }

        ResultBlock("文字路径", cap.textModel, cap.textLatencyMs, cap.textResult, cap.textError, null)
        val found = Extractor.amountsFoundInText(cap)
        ResultBlock(
            "看图路径", cap.imageModel, cap.imageLatencyMs, cap.imageResult, cap.imageError,
            listOfNotNull(cap.imageParts?.let { "切成 $it 段" }, found?.let { "金额在节点文字里找到 ${it.first}/${it.second}" }).joinToString(" · ").ifEmpty { null },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.rerun(cap.id, text = true, image = true) }) { Text("重新识别") }
            OutlinedButton(onClick = { vm.delete(cap); onBack() }) { Text("删除") }
        }

        if (!cap.flatText.isNullOrBlank()) {
            Text("节点文字", fontWeight = FontWeight.Medium)
            SelectionContainer {
                Text(cap.flatText, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
            }
        }
        cap.imagePath?.let { path -> Screenshot(path) }
    }
}

@Composable
private fun TriState(title: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.weight(1f))
        FilterChip(selected = value == true, onClick = { onChange(if (value == true) null else true) }, label = { Text("对") })
        FilterChip(selected = value == false, onClick = { onChange(if (value == false) null else false) }, label = { Text("错") })
    }
}

@Composable
private fun ResultBlock(title: String, model: String?, latency: Long?, result: String?, error: String?, extra: String?) {
    Card { Column(Modifier.padding(12.dp)) {
        Text("$title${model?.let { " · $it" } ?: ""} · ${sec(latency)}", fontWeight = FontWeight.Medium)
        extra?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        when {
            error != null -> Text("失败：$error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            result == null -> Text("未运行", style = MaterialTheme.typography.bodySmall)
            else -> {
                val pretty = runCatching { JSONObject(result).toString(2) }.getOrDefault(result)
                SelectionContainer {
                    Text(pretty, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
                }
            }
        }
    } }
}

@Composable
private fun Screenshot(path: String) {
    val bmp by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
        }
    }
    bmp?.let {
        Text("截图", fontWeight = FontWeight.Medium)
        Image(it.asImageBitmap(), contentDescription = "截图", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SettingsPane(vm: MainViewModel) {
    val p = vm.prefs
    var key by remember { mutableStateOf(p.apiKey) }
    var base by remember { mutableStateOf(p.baseUrl) }
    var tm by remember { mutableStateOf(p.textModel) }
    var im by remember { mutableStateOf(p.imageModel) }
    var autoText by remember { mutableStateOf(p.autoText) }
    var autoImage by remember { mutableStateOf(p.autoImage) }
    var keepShot by remember { mutableStateOf(p.keepScreenshot) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(key, { key = it }, label = { Text("DeepSeek API Key") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(base, { base = it }, label = { Text("接口地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tm, { tm = it }, label = { Text("文字路径模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(im, { im = it }, label = { Text("看图路径模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SwitchRow("采样后自动跑文字路径", autoText) { autoText = it }
        SwitchRow("采样后自动跑看图路径", autoImage) { autoImage = it }
        SwitchRow("黑屏截图也保存（用于核对）", keepShot) { keepShot = it }
        Button(onClick = { vm.saveSettings(key, base, tm, im, autoText, autoImage, keepShot) }) { Text("保存") }
        HorizontalDivider()
        Text(
            "发给模型前会把手机号和 15 位以上的长数字（卡号等）打码。截图会整张上传，探针阶段不做裁剪。" +
                "Key 存在本 App 的私有存储里。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SwitchRow(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
