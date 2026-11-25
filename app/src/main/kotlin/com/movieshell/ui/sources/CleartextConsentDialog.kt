package com.movieshell.ui.sources

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.movieshell.ui.components.TechDialog

/**
 * v1.4.23 ④：明文 HTTP 确认弹窗。用户首次加 http 订阅/网页源、且该 host 未授权时弹，
 * 点“允许访问”才 authorize 并继续添加，点取消/返回则不添加。
 * 照抄 SourceRiskDialog 的写法（TechDialog、scrollable = false）；内容是短文本、
 * 没有内层滚动，false 在这里只是不让外层再包一层滚动。
 * 调用处必须包 if 守卫（TechDialog 本身不带守卫，见 1.2.22 的教训）。
 * 不复用 SourceRiskDialog：那是风险条目列表弹窗，语义不对。
 * 确认按钮走 confirmDanger：与 SourceRiskDialog 的“仍要导入”同款，
 * 都是“明知有风险仍放行”的确认，按钮标 error 色以示区分。
 */
@Composable
fun CleartextConsentDialog(
    host: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    TechDialog(
        onDismissRequest = onDismissRequest,
        title = "该地址使用明文 HTTP",
        subtitle = host,
        confirmText = "允许访问",
        confirmDanger = true,
        onConfirm = onConfirm,
        scrollable = false,
    ) {
        Text(
            text = "「${host}」使用明文 HTTP，传输内容可能被中间人看到或篡改。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "只在你信任这个地址时点“允许访问”。允许后，该地址的明文 HTTP 请求不再拦截，可在设置 → 网络 → 已授权 HTTP 主机里撤销。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
