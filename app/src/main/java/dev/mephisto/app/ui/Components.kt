package dev.mephisto.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 共用的小组件。数值全部来自 UI 规格：间距只用 4/8/12/16/24，圆角 12/16/20，
 * 阴影一律 0——阴影在深色模式下会把界面糊成一片。
 */

enum class Tone { NORMAL, WARN, ERROR }

/** 状态条：6dp 圆点 + 一行小字。替代之前"把三种状态拼成一句长话"。 */
@Composable
fun StatusStrip(text: String, tone: Tone = Tone.NORMAL, modifier: Modifier = Modifier) {
    val dot = when (tone) {
        Tone.NORMAL -> MaterialTheme.colorScheme.primary
        Tone.WARN -> MaterialTheme.colorScheme.tertiary
        Tone.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 失败横幅：带重试按钮。不用 Snackbar——它会在你打字时弹出来挡住输入框。 */
@Composable
fun Banner(message: String, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
fun EmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 骨架占位：静态块，不做 shimmer（动效只会让人以为还有东西没加载完）。 */
@Composable
fun SkeletonRow(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            )
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth(0.55f)
                        .height(12.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)),
                )
                Box(
                    Modifier
                        .fillMaxWidth(0.85f)
                        .height(10.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(5.dp)),
                )
            }
        }
    }
}

/** 会话/消息共用的"甲方"标识：取标题第一个字符放进圆里。 */
@Composable
fun Avatar(text: String, size: Int = 36, color: Color = MaterialTheme.colorScheme.primaryContainer) {
    Box(
        Modifier.size(size.dp).background(color, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.take(1).ifBlank { "?" },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 对话里的居中时间分隔。 */
@Composable
fun TimeSeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
