package com.shaowenfu.bookkeeper.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shaowenfu.bookkeeper.Whitelist
import com.shaowenfu.bookkeeper.data.ListenerEvent
import com.shaowenfu.bookkeeper.data.NotificationSample
import com.shaowenfu.bookkeeper.data.PackageCount
import com.shaowenfu.bookkeeper.data.SeenPackage
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenActions(
    val openListenerSettings: () -> Unit,
    val requestBatteryExemption: () -> Unit,
    val openAppDetails: () -> Unit,
    val requestPostNotifications: () -> Unit,
    val share: (Uri, String) -> Unit,
)

private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)
private fun fmt(ms: Long?): String = if (ms == null || ms <= 0) "—" else timeFmt.format(Date(ms))

private val okColor = Color(0xFF2E7D32)
private val badColor = Color(0xFFC62828)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel, actions: ScreenActions) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("概览", "样本", "其他包名", "监听日志")
    Scaffold(
        topBar = { TopAppBar(title = { Text("记账通知采样 · M0") }) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
                }
            }
            when (tab) {
                0 -> OverviewTab(vm, actions) { pkg -> vm.selectedPackage.value = pkg; tab = 1 }
                1 -> SamplesTab(vm)
                2 -> PackagesTab(vm)
                else -> EventsTab(vm)
            }
        }
    }
}

@Composable
private fun OverviewTab(vm: MainViewModel, actions: ScreenActions, onPickPackage: (String) -> Unit) {
    val health by vm.health.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val whitelist by vm.whitelist.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val byPkg = counts.associateBy { it.packageName }

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("运行状态", fontWeight = FontWeight.Bold)
                    StatusRow("通知使用权", health.listenerGranted, "去授权", actions.openListenerSettings)
                    StatusRow("监听服务已连接", connected && health.listenerGranted, "强制重连") { vm.forceReconnect() }
                    StatusRow("不受电池优化限制", health.batteryUnrestricted, "去设置", actions.requestBatteryExemption)
                    StatusRow("允许发送通知（告警用）", health.postNotificationsGranted, "去授权", actions.requestPostNotifications)
                    Text(
                        "上次自检：${fmt(health.lastCheckAt)}。厂商 ROM 还需手动允许“自启动”，并在最近任务里锁定本 App。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = actions.openAppDetails) { Text("打开本 App 的系统设置（自启动 / 后台）") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("导出（保存到 下载/Bookkeeper/）", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.export(json = true) }) { Text("导出 JSON") }
                        OutlinedButton(onClick = { vm.export(json = false) }) { Text("导出 CSV") }
                    }
                    message?.let { m ->
                        Text(m.text, style = MaterialTheme.typography.bodySmall)
                        Row {
                            if (m.uri != null && m.mime != null) {
                                TextButton(onClick = { actions.share(m.uri, m.mime) }) { Text("分享") }
                            }
                            TextButton(onClick = { vm.dismissMessage() }) { Text("关闭") }
                        }
                    }
                }
            }
        }
        item {
            Text("各 App 样本数（点击查看样本）", fontWeight = FontWeight.Bold)
            Text(
                "条数 = 存下的不同通知；更新 = 同一通知内容变化；重发 = 同内容重复推送（只计数不新增）。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val pkgs = (whitelist + counts.map { it.packageName }).distinct()
            .sortedByDescending { byPkg[it]?.total ?: 0 }
        items(pkgs, key = { it }) { pkg ->
            AppCountRow(
                label = Whitelist.label(ctx, pkg),
                pkg = pkg,
                installed = Whitelist.isInstalled(ctx, pkg),
                inWhitelist = pkg in whitelist,
                count = byPkg[pkg],
                onClick = { onPickPackage(pkg) },
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, actionLabel: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "●", color = if (ok) okColor else badColor)
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f))
        if (!ok) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun AppCountRow(
    label: String,
    pkg: String,
    installed: Boolean,
    inWhitelist: Boolean,
    count: PackageCount?,
    onClick: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(pkg, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                val notes = buildList {
                    if (!installed) add("未安装")
                    if (!inWhitelist) add("已移出白名单")
                    add("最近 ${fmt(count?.lastPostTime)}")
                }
                Text(
                    notes.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!installed) badColor else Color.Unspecified,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${count?.total ?: 0} 条", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "更新 ${count?.updates ?: 0} · 重发 ${count?.reposts ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SamplesTab(vm: MainViewModel) {
    val ctx = LocalContext.current
    val selected by vm.selectedPackage.collectAsStateWithLifecycle()
    val samples by vm.samples.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    var hideSummaries by rememberSaveable { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(selected = selected == null, onClick = { vm.selectedPackage.value = null },
                    label = { Text("全部 ${counts.sumOf { it.total }}") })
            }
            items(counts.sortedByDescending { it.total }, key = { it.packageName }) { c ->
                FilterChip(
                    selected = selected == c.packageName,
                    onClick = { vm.selectedPackage.value = c.packageName },
                    label = { Text("${Whitelist.label(ctx, c.packageName)} ${c.total}") },
                )
            }
            item {
                FilterChip(selected = hideSummaries, onClick = { hideSummaries = !hideSummaries },
                    label = { Text("隐藏分组摘要") })
            }
        }
        val shown = if (hideSummaries) samples.filter { !it.isGroupSummary } else samples
        if (shown.isEmpty()) {
            Text(
                "还没有样本。授权通知使用权后，用白名单里的 App 付一笔钱试试。",
                Modifier.padding(16.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shown, key = { it.id }) { s -> SampleCard(s, Whitelist.label(ctx, s.packageName)) }
            if (samples.size >= 500) {
                item { Text("只显示最近 500 条；完整数据请导出。", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun SampleCard(s: NotificationSample, label: String) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$label · ${fmt(s.postTime)}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                val badges = buildList {
                    if (s.isUpdate) add("更新")
                    if (s.repostCount > 0) add("重发×${s.repostCount}")
                    if (s.possibleDuplicateOf != null) add("疑似重复")
                    if (s.isGroupSummary) add("分组摘要")
                    if (s.captureSource == "snapshot") add("补抓")
                    if (s.removedAt != null) add("已移除")
                }
                if (badges.isNotEmpty()) {
                    Text(badges.joinToString(" "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            s.title?.let { Text(it, fontWeight = FontWeight.SemiBold) }
            s.text?.let { Text(it, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis) }
            if (expanded) {
                s.bigText?.takeIf { it != s.text }?.let { Text("bigText: $it", style = MaterialTheme.typography.bodySmall) }
                s.subText?.let { Text("subText: $it", style = MaterialTheme.typography.bodySmall) }
                s.textLines?.let { Text("textLines:\n$it", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "key=${s.sbnKey}\nchannel=${s.channelId} category=${s.category}\n" +
                        "收到 ${fmt(s.receivedAt)}（延迟 ${s.receivedAt - s.postTime} ms）",
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                )
                HorizontalDivider()
                val pretty = remember(s.id) { runCatching { JSONObject(s.extrasJson).toString(2) }.getOrDefault(s.extrasJson) }
                SelectionContainer {
                    Text(
                        pretty,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

@Composable
private fun PackagesTab(vm: MainViewModel) {
    val ctx = LocalContext.current
    val seen by vm.seenPackages.collectAsStateWithLifecycle()
    val whitelist by vm.whitelist.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("当前白名单", fontWeight = FontWeight.Bold)
            Text("只有白名单里的 App 会存通知内容。", style = MaterialTheme.typography.bodySmall)
        }
        items(whitelist, key = { "w:$it" }) { pkg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Whitelist.label(ctx, pkg))
                    Text(pkg, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = { vm.removeFromWhitelist(pkg) }) { Text("移除") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text("手动添加包名") }, singleLine = true, modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.addToWhitelist(input); input = "" }) { Text("添加") }
            }
        }
        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("其他发过通知的 App（只记包名和次数）", fontWeight = FontWeight.Bold)
            Text(
                "如果某个目标 App 的通知没被采到，先在这里找它的真实包名，再加入白名单。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(seen, key = { "s:${it.packageName}" }) { p -> SeenRow(p, Whitelist.label(ctx, p.packageName)) { vm.addToWhitelist(p.packageName) } }
    }
}

@Composable
private fun SeenRow(p: SeenPackage, label: String, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$label · ${p.count} 次")
            Text(p.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text("最近 ${fmt(p.lastSeenAt)}", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onAdd) { Text("加入白名单") }
    }
}

@Composable
private fun EventsTab(vm: MainViewModel) {
    val events by vm.events.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Text(
                "disconnected 到下一次 connected 之间就是漏听时段；permission_lost 表示通知使用权被关掉。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(events, key = { it.id }) { e -> EventRow(e) }
    }
}

@Composable
private fun EventRow(e: ListenerEvent) {
    val bad = e.type in setOf("disconnected", "permission_lost")
    Row {
        Text(fmt(e.at), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        Text(
            e.type + (e.detail?.let { " ($it)" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = if (bad) badColor else Color.Unspecified,
        )
    }
}
