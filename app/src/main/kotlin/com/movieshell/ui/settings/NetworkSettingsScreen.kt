package com.movieshell.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.movieshell.core.net.CleartextAuth
import com.movieshell.core.net.Doh
import com.movieshell.data.store.DohSettings
import com.movieshell.ui.components.AppSwitch
import com.movieshell.ui.components.AppToast
import com.movieshell.ui.components.TabTopBar
import com.movieshell.ui.components.TechTextField
import com.movieshell.ui.theme.V5
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.4.8 A4：网络设置页（DoH 可配置）。
 *
 * 只做：开关 + 三家内置候选 + 自定义地址输入框 + 地址格式校验。
 * 不做 DoT，不做连通性测试按钮（只校验 URL 格式）。
 * 开关与地址改完都要重启 App 才生效（sharedClient 是 lazy 单例）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val dohEnabled by DohSettings.enabled.collectAsState()
    val savedEndpoint by DohSettings.endpoint.collectAsState()
    var endpointText by remember(savedEndpoint) { mutableStateOf(savedEndpoint) }
    var errorText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { DohSettings.init(context) }

    // v1.4.23 ④：已授权/被拦主机列表——CleartextAuth 只给快照（非 Flow），
    // 撤销/允许后 tick +1 触发重读；列表按 host 排序，被拦按最后被拦时间倒序。
    var cleartextTick by remember { mutableStateOf(0) }
    val authorizedHosts = remember(cleartextTick) { CleartextAuth.authorizedHosts().sorted() }
    val blockedHosts = remember(cleartextTick) {
        CleartextAuth.blockedHosts().entries.sortedByDescending { it.value }
    }

    fun save() {
        val trimmed = endpointText.trim()
        val endpoint = trimmed.ifBlank { Doh.DEFAULT_ENDPOINT }
        if (!Doh.isValidEndpoint(endpoint)) {
            errorText = "地址无效：须为 https 开头且带路径，例如 https://doh.pub/dns-query"
            return
        }
        DohSettings.setEndpoint(context, endpoint)
        errorText = null
        AppToast.show(context, "已保存，重启 App 后生效")
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
            topBar = { TabTopBar(title = "网络", onBack = onBack) },
            containerColor = cs.surfaceContainer,
        ) { inner ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(
                    start = V5.Gutter,
                    end = V5.Gutter,
                    top = 8.dp,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    SettingsGroup(title = "DNS over HTTPS (DoH)") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SegItem(
                                position = SegPos.Single,
                                icon = Icons.Outlined.Link,
                                title = "DNS over HTTPS",
                                subtitle = if (dohEnabled) "已开启 · 重启 App 后生效" else "已关闭",
                                onClick = { DohSettings.setEnabled(context, !dohEnabled) },
                                trailing = {
                                    AppSwitch(
                                        checked = dohEnabled,
                                        onCheckedChange = { DohSettings.setEnabled(context, it) },
                                    )
                                },
                            )
                            Text(
                                text = "只对走 OkHttp 的请求生效：源请求、EPG、订阅、走代理的源播放、自签 Emby。多数常规播放走 Media3 默认分支（HttpURLConnection）、libmpv 原生栈与 WebView 嗅探不走 OkHttp，DoH 管不到。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "1.3.16 日志里的分片级 DNS 失败发生在 Media3 默认分支或 libmpv，DoH 覆盖不到——开了 DoH 还遇到分片失败，不等于开关坏了。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "服务地址不可用时，依赖 OkHttp 的请求 DNS 全失败，不会自动回落系统 DNS；关掉开关并重启 App 即恢复今天的行为。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "服务地址是域名时，第一跳仍走系统 DNS 解析它。代理同开时的解析方待实测。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "开关与地址改完都要重启 App 才生效。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    SettingsGroup(title = "服务地址") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Doh.BUILTIN_ENDPOINTS.forEach { candidate ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            endpointText = candidate
                                            errorText = null
                                        }
                                        .padding(horizontal = 4.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = endpointText.trim() == candidate,
                                        onClick = {
                                            endpointText = candidate
                                            errorText = null
                                        },
                                    )
                                    Text(
                                        text = candidate,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                            }
                            TechTextField(
                                value = endpointText,
                                onValueChange = {
                                    endpointText = it
                                    errorText = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = "自定义 DoH 地址",
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            )
                            Text(
                                text = "须为 https 且带路径；留空保存即用默认地址。只做地址格式校验，不测连通性。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val error = errorText
                            if (error != null) {
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.error,
                                )
                            }
                            Button(
                                onClick = { save() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("保存") }
                        }
                    }
                }
                item {
                    SettingsGroup(title = "已授权 HTTP 主机") {
                        Column {
                            if (authorizedHosts.isEmpty()) {
                                Text(
                                    text = "暂无你授权的主机。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            } else {
                                authorizedHosts.forEachIndexed { index, host ->
                                    SegItem(
                                        position = cleartextPos(index, authorizedHosts.size),
                                        icon = Icons.Outlined.Link,
                                        title = host,
                                        subtitle = "该主机的明文 HTTP 请求已放行",
                                        trailing = {
                                            TextButton(onClick = {
                                                CleartextAuth.revoke(host)
                                                cleartextTick++
                                            }) { Text("撤销") }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    SettingsGroup(title = "最近被拦") {
                        Column {
                            if (blockedHosts.isEmpty()) {
                                Text(
                                    text = "最近没有被拦截的主机。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            } else {
                                blockedHosts.forEachIndexed { index, (host, blockedAt) ->
                                    SegItem(
                                        position = cleartextPos(index, blockedHosts.size),
                                        icon = Icons.Outlined.Link,
                                        title = host,
                                        subtitle = "最后被拦 ${formatBlockedTime(blockedAt)}",
                                        trailing = {
                                            TextButton(onClick = {
                                                CleartextAuth.authorize(host)
                                                CleartextAuth.clearBlocked(host)
                                                cleartextTick++
                                            }) { Text("允许") }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    SettingsGroup(title = "说明") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "内置已授权：sspa8.top、mobilecdn.kugou.com，不用你再确认。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "常见 IPv4 私有地址及本机回环默认放行。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "拦截器异常时放行并记日志，不会把正常源误杀。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "PY 规则走 Python 原生 socket，不经过本网关，明文不拦截、不确认。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

// v1.4.23 ④：分段位置（照抄 ClashSettingsScreen.clashPos）。
private fun cleartextPos(index: Int, size: Int): SegPos = when {
    size <= 1 -> SegPos.Single
    index == 0 -> SegPos.First
    index == size - 1 -> SegPos.Last
    else -> SegPos.Middle
}

// v1.4.23 ④：被拦时间格式（照抄 SourcesScreen 的 SimpleDateFormat 用法）。
private fun formatBlockedTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
