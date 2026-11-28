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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shaowenfu.bookkeeper.capture.ScreenReaderService
import com.shaowenfu.bookkeeper.data.RawEvent
import com.shaowenfu.bookkeeper.data.Tx
import com.shaowenfu.bookkeeper.recognize.Ledger
import com.shaowenfu.bookkeeper.recognize.Prompts
import com.shaowenfu.bookkeeper.settings.BatteryGuide
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScreenActions(
    val openAccessibilitySettings: () -> Unit,
    val openAppDetails: () -> Unit,
    val openBatterySettings: () -> Unit,
    val requestPostNotifications: () -> Unit,
    val addTile: () -> Unit,
    val pickImages: () -> Unit,
    val share: (Uri, String) -> Unit,
)

/** 页面导航：标签页 + 当前打开的交易 / 识别记录。通知点进来时也走这里。 */
class NavState {
    var tab by mutableStateOf(0)
    var txId by mutableLongStateOf(0L)
    var eventId by mutableLongStateOf(0L)

    fun openTx(id: Long) { tab = 0; txId = id }
    fun openEvent(id: Long) { tab = 1; eventId = id }
    fun openEvents() { tab = 1; eventId = 0 }
}

private val dayFmt = SimpleDateFormat("M月d日 EEE", Locale.CHINA)
private val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)

private fun sourceLabel(s: String) = when (s) {
    ScreenReaderService.TRIGGER_SHORTCUT -> "音量键"
    ScreenReaderService.TRIGGER_AUTO_WAKE -> "唤醒自动识别"
    ScreenReaderService.TRIGGER_TILE -> "磁贴"
    "share" -> "分享"
    "gallery" -> "相册"
    else -> s
}

private fun statusLabel(e: RawEvent) = when (e.status) {
    RawEvent.STATUS_PENDING -> if (e.attempts == 0) "等待识别" else "等待重试"
    RawEvent.STATUS_DONE -> "已入账"
    RawEvent.STATUS_NO_TX -> "没有付款信息"
    RawEvent.STATUS_FAILED -> if (e.attempts >= RawEvent.MAX_ATTEMPTS) "失败" else "失败，稍后重试（${e.attempts}/${RawEvent.MAX_ATTEMPTS}）"
    else -> e.status
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, nav: NavState, actions: ScreenActions) {
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }

    Scaffold(topBar = { TopAppBar(title = { Text("截图记账") }) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = nav.tab) {
                listOf("账本", "识别记录", "设置").forEachIndexed { i, t ->
                    Tab(selected = nav.tab == i, onClick = { nav.tab = i; nav.txId = 0; nav.eventId = 0 }, text = { Text(t) })
                }
            }
            when (nav.tab) {
                0 -> if (nav.txId > 0) TxDetail(vm, nav.txId) { nav.txId = 0 } else LedgerTab(vm, actions) { nav.txId = it }
                1 -> if (nav.eventId > 0) EventDetail(vm, nav.eventId) { nav.eventId = 0 } else EventsTab(vm) { nav.eventId = it }
                else -> SettingsTab(vm, actions)
            }
        }
    }
}

// ---------------- 账本 ----------------

@Composable
private fun LedgerTab(vm: MainViewModel, actions: ScreenActions, onOpen: (Long) -> Unit) {
    val txs by vm.txs.collectAsStateWithLifecycle()
    val total by vm.monthTotal.collectAsStateWithLifecycle()
    val h by vm.health.collectAsStateWithLifecycle()
    val byDay = remember(txs) { txs.groupBy { dayFmt.format(Date(it.occurredAt)) } }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("本月支出", style = MaterialTheme.typography.labelLarge)
                    Text("¥${Money.yuan(total.expense - total.refund)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "共 ${total.count} 笔" + if (total.refund > 0) "，已扣除退款 ¥${Money.yuan(total.refund)}" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = actions.pickImages) { Text("导入截图") }
                    }
                }
            }
        }
        if (!h.serviceConnected || !h.apiKeySet) {
            item {
                Text(
                    if (!h.apiKeySet) "还没填 DeepSeek API Key，到“设置”页填写。" else "无障碍服务没连上，长按两个音量键唤醒，或到“设置”页查看。",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (h.batteryGuideDoneAt == 0L && BatteryGuide.isVivo) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("先做一步：允许后台高耗电", fontWeight = FontWeight.Medium)
                        Text("不设置的话，vivo 会冻住截图记账，付款页长按音量键会没反应。${BatteryGuide.steps}。",
                            style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = actions.openBatterySettings) { Text("去设置") }
                            TextButton(onClick = { vm.markBatteryGuideDone() }) { Text("已设置好") }
                        }
                    }
                }
            }
        }
        if (txs.isEmpty()) {
            item { Text("还没有记录。付完款停在支付成功页，长按两个音量键试试。", Modifier.padding(8.dp)) }
        }
        byDay.forEach { (day, list) ->
            item(key = "h-$day") {
                val sum = list.sumOf { if (it.direction == "expense") it.amountCents else if (it.direction == "refund") -it.amountCents else 0 }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp)) {
                    Text(day, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text("支出 ¥${Money.yuan(sum)}", style = MaterialTheme.typography.labelMedium)
                }
            }
            items(list, key = { it.id }) { t -> TxRow(t) { onOpen(t.id) } }
        }
    }
}

@Composable
private fun TxRow(t: Tx, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.merchant ?: t.app ?: "未知商户", fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        t.app, t.category, timeFmt.format(Date(t.occurredAt)) + if (t.timeEstimated) "（截图时间）" else "",
                        if (t.direction != "expense") Money.directionLabel(t.direction) else null,
                        if (t.isAutoDebit) "自动扣费" else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (t.needsReview) Text("待核对", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
            Text(
                Money.signed(t.amountCents, t.direction),
                fontWeight = FontWeight.SemiBold,
                color = if (t.direction == "refund" || t.direction == "income") Color(0xFF2E7D32) else Color.Unspecified,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TxDetail(vm: MainViewModel, id: Long, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val tx by vm.observeTx(id).collectAsState(initial = null)
    val items by vm.observeItems(id).collectAsState(initial = emptyList())
    val sources by produceState(emptyList<RawEvent>(), id) { value = vm.eventsForTx(id) }
    val t = tx ?: run {
        Column(Modifier.padding(16.dp)) { TextButton(onClick = onBack) { Text("← 返回") }; Text("这笔不存在") }
        return
    }

    var amount by remember(t.id, t.updatedAt) { mutableStateOf(Money.yuan(t.amountCents)) }
    var merchant by remember(t.id, t.updatedAt) { mutableStateOf(t.merchant ?: "") }
    var category by remember(t.id, t.updatedAt) { mutableStateOf(t.category) }
    var direction by remember(t.id, t.updatedAt) { mutableStateOf(t.direction) }
    var note by remember(t.id, t.updatedAt) { mutableStateOf(t.note ?: "") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("← 返回账本") }
        if (t.status == Tx.STATUS_VOID) Text("这笔已删除（撤销），不计入统计", color = MaterialTheme.colorScheme.error)
        if (t.needsReview) Text("待核对：金额没能在屏幕文字里找到，或识别把握不大。核对后点保存即可。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text(
            listOfNotNull(
                fullFmt.format(Date(t.occurredAt)) + if (t.timeEstimated) "（屏幕上没有交易时间，用的是截图时间）" else "",
                t.app?.let { "平台：$it" }, t.channel?.let { "渠道：$it" }, t.funding?.let { "付款方式：$it" },
                if (t.isAutoDebit) "自动扣费" else null,
            ).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(amount, { amount = it }, label = { Text("金额（元）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(merchant, { merchant = it }, label = { Text("商户") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("类型", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("expense", "refund", "income", "transfer", "repayment").forEach { d ->
                FilterChip(selected = direction == d, onClick = { direction = d }, label = { Text(Money.directionLabel(d)) })
            }
        }
        Text("分类", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Prompts.CATEGORIES.forEach { c ->
                FilterChip(selected = category == c, onClick = { category = if (category == c) null else c }, label = { Text(c) })
            }
        }
        OutlinedTextField(note, { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val cents = Ledger.parseCents(amount)
                if (cents != null && cents > 0) {
                    vm.saveTx(t.copy(amountCents = cents, merchant = merchant.ifBlank { null }, category = category,
                        direction = direction, note = note.ifBlank { null }))
                }
            }) { Text("保存") }
            if (t.status != Tx.STATUS_VOID) OutlinedButton(onClick = { vm.voidTx(t.id); onBack() }) { Text("删除") }
        }

        if (items.isNotEmpty()) {
            HorizontalDivider()
            Text("商品明细", fontWeight = FontWeight.Medium)
            items.forEach { li ->
                Row(Modifier.fillMaxWidth()) {
                    Text(li.name + (li.qty?.let { q -> if (q != 1.0) " ×${if (q % 1.0 == 0.0) q.toLong() else q}" else "" } ?: ""), Modifier.weight(1f))
                    Text(li.amountCents?.let { "¥${Money.yuan(it)}" } ?: "")
                }
            }
        }
        if (sources.isNotEmpty()) {
            HorizontalDivider()
            Text("来源截图（${sources.size}）", fontWeight = FontWeight.Medium)
            sources.forEach { e ->
                Text("${fullFmt.format(Date(e.createdAt))} · ${sourceLabel(e.source)}", style = MaterialTheme.typography.bodySmall)
                e.imagePath?.let { Screenshot(it) }
            }
        }
    }
}

// ---------------- 识别记录 ----------------

@Composable
private fun EventsTab(vm: MainViewModel, onOpen: (Long) -> Unit) {
    val events by vm.events.collectAsStateWithLifecycle()
    if (events.isEmpty()) {
        Text("还没有识别记录。", Modifier.padding(16.dp)); return
    }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(events, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(e.id) }) {
                Column(Modifier.padding(12.dp)) {
                    Row {
                        Text("${fullFmt.format(Date(e.createdAt))} · ${sourceLabel(e.source)}", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text(statusLabel(e), style = MaterialTheme.typography.labelMedium,
                            color = if (e.status == RawEvent.STATUS_FAILED) MaterialTheme.colorScheme.error else Color.Unspecified)
                    }
                    Text(
                        listOfNotNull(Prompts.appName(e.packageName) ?: e.packageName, e.latencyMs?.let { "${it / 1000.0} 秒" }, e.lastError?.take(60)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun EventDetail(vm: MainViewModel, id: Long, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ev by vm.observeEvent(id).collectAsState(initial = null)
    val e = ev ?: run {
        Column(Modifier.padding(16.dp)) { TextButton(onClick = onBack) { Text("← 返回") }; Text("记录不存在") }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onBack) { Text("← 返回识别记录") }
        Text("${fullFmt.format(Date(e.createdAt))} · ${sourceLabel(e.source)} · ${statusLabel(e)}", fontWeight = FontWeight.Medium)
        Text(
            listOfNotNull(
                e.packageName?.let { "前台 App：$it" }, "截图：${e.imageStatus}", "屏幕文字：${e.textChars} 字",
                e.model?.let { "模型：$it" }, e.latencyMs?.let { "耗时：${it / 1000.0} 秒" }, "尝试次数：${e.attempts}",
                e.lastError?.let { "错误：$it" },
            ).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { vm.retry(e) }) { Text("重新识别") }
        e.resultJson?.let { r ->
            Text("识别结果", fontWeight = FontWeight.Medium)
            SelectionContainer {
                Text(runCatching { JSONObject(r).toString(2) }.getOrDefault(r), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
            }
        }
        if (!e.flatText.isNullOrBlank()) {
            Text("屏幕文字", fontWeight = FontWeight.Medium)
            SelectionContainer {
                Text(e.flatText, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()))
            }
        }
        e.imagePath?.let { Screenshot(it) }
    }
}

@Composable
private fun Screenshot(path: String) {
    val bmp by produceState<android.graphics.Bitmap?>(null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 })
        }
    }
    bmp?.let { Image(it.asImageBitmap(), contentDescription = "截图", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
}

// ---------------- 设置 ----------------

@Composable
private fun StatusRow(ok: Boolean?, title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ok == null) "○" else "●",
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
private fun SettingsTab(vm: MainViewModel, actions: ScreenActions) {
    val h by vm.health.collectAsStateWithLifecycle()
    val serviceEvents by vm.serviceEvents.collectAsStateWithLifecycle()
    val lastExport by vm.lastExport.collectAsStateWithLifecycle()
    val p = vm.prefs
    var key by remember { mutableStateOf(p.apiKey) }
    var base by remember { mutableStateOf(p.baseUrl) }
    var model by remember { mutableStateOf(p.model) }
    var onWake by remember { mutableStateOf(p.captureOnWake) }
    val batteryDone = h.batteryGuideDoneAt > 0
    // 只统计“已设置好”之后、最近 7 天内的延迟长按，用来判断设置是否真的生效
    val since = maxOf(System.currentTimeMillis() - 7 * 24 * 3600_000L, h.batteryGuideDoneAt)
    val staleCount = serviceEvents.count { it.type == "stale_trigger" && it.at >= since }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card { Column(Modifier.padding(16.dp)) {
            Text("准备", style = MaterialTheme.typography.titleMedium)
            StatusRow(
                ok = h.serviceConnected, title = "无障碍服务“截图记账”",
                detail = when {
                    h.serviceConnected -> "已连接"
                    h.serviceEnabled -> "开着但没连上（被系统杀了）。长按两个音量键一次就会唤醒"
                    else -> "未开启。开关是灰的话：先到应用信息 → 右上角 ⋮ → 允许受限制的设置"
                },
                action = "去设置", onAction = actions.openAccessibilitySettings,
            )
            if (!h.serviceEnabled) TextButton(onClick = actions.openAppDetails) { Text("打开应用信息") }
            StatusRow(
                ok = if (staleCount > 0) false else if (batteryDone) true else null,
                title = "允许后台高耗电（防止长按没反应）",
                detail = buildString {
                    append(BatteryGuide.steps)
                    if (staleCount > 0) append(if (batteryDone) "。设置之后仍有 $staleCount 次长按被系统延迟，请检查是否已锁定" else "。最近 7 天有 $staleCount 次长按被系统延迟")
                },
                action = "去设置", onAction = actions.openBatterySettings,
            )
            if (!batteryDone) TextButton(onClick = { vm.markBatteryGuideDone() }) { Text("我已设置好") }
            StatusRow(ok = null, title = "触发方式：长按两个音量键",
                detail = "无障碍设置 → 截图记账 → 快捷方式，勾选“长按音量键”。付完款停在成功页，长按两个音量键约 3 秒")
            StatusRow(ok = null, title = "磁贴（备用）", detail = "下拉通知栏点“截图记账”", action = "添加", onAction = actions.addTile)
            StatusRow(
                ok = h.notificationsAllowed, title = "通知权限",
                detail = if (h.notificationsAllowed) "记账后弹通知，可以直接撤销" else "未允许：看不到记账结果",
                action = if (h.notificationsAllowed) null else "允许", onAction = actions.requestPostNotifications,
            )
        } }

        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("识别", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(key, { key = it }, label = { Text("DeepSeek API Key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(base, { base = it }, label = { Text("接口地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("模型（需支持看图）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("唤醒时直接识别")
                    Text("服务被杀后，长按音量键唤醒它时，如果前台是微信、支付宝等付款 App，就直接识别", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = onWake, onCheckedChange = { onWake = it })
            }
            Button(onClick = { vm.saveSettings(key, base, model, onWake) }) { Text("保存") }
            Text("截图会整张发给模型识别；屏幕文字发送前会把手机号和长数字打码。", style = MaterialTheme.typography.bodySmall)
        } }

        Card { Column(Modifier.padding(16.dp)) {
            Text("导出", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.export(json = false) }) { Text("导出 CSV") }
                OutlinedButton(onClick = { vm.export(json = true) }) { Text("导出 JSON（含识别记录）") }
            }
            lastExport?.let { (uri, mime) -> TextButton(onClick = { actions.share(uri, mime) }) { Text("分享刚导出的文件") } }
        } }

        Card { Column(Modifier.padding(16.dp)) {
            Text("无障碍服务连接记录", style = MaterialTheme.typography.titleMedium)
            if (serviceEvents.isEmpty()) Text("暂无", style = MaterialTheme.typography.bodySmall)
            serviceEvents.take(20).forEach { s ->
                Text("${fullFmt.format(Date(s.at))}  ${s.type}${s.detail?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        } }
    }
}
