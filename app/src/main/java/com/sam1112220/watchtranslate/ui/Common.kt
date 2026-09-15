package com.sam1112220.watchtranslate.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import com.sam1112220.watchtranslate.ui.theme.T
import com.sam1112220.watchtranslate.ui.theme.Wt

// =====================================================================
// 共享 UI 原子
// =====================================================================

/** 弓形带内的小标签（中文 · 原文 / English · 译文） */
@Composable
fun BandLabel(text: String, color: Color = Wt.TextSecondary, size: TextUnit = T.label) {
    Text(
        text = text,
        fontSize = size,
        color = color,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 弓形带内的正文 */
@Composable
fun BandText(
    text: String,
    color: Color = Wt.TextPrimary,
    size: TextUnit = T.bandTitle,
    maxLines: Int = 3,
    bold: Boolean = false
) {
    Text(
        text = text,
        fontSize = size,
        color = color,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        lineHeight = size * 1.24f,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/** 中心圆钮内的图标 */
@Composable
fun CenterIcon(icon: ImageVector, tint: Color, size: Dp = 34.dp) {
    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
}

/** 中心圆钮内的文字 */
@Composable
fun CenterText(text: String, color: Color, size: TextUnit = T.button, bold: Boolean = true) {
    Text(
        text = text,
        fontSize = size,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Clip
    )
}

/** 长方形屏 · 状态栏 */
@Composable
fun StatusBar(time: String, offline: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().height(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(time, fontSize = T.badge, color = Wt.TextSecondary)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(if (offline) Wt.Green else Wt.Amber)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                if (offline) "离线" else "在线",
                fontSize = T.badge,
                color = if (offline) Wt.Green else Wt.Amber
            )
        }
    }
}

/** 长方形屏 · 图标 + 主副文案 + 右箭头 列表行 */
@Composable
fun SettingRow(
    icon: ImageVector?,
    iconTint: Color,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (emphasize) Wt.BandBottomActive else Wt.SurfaceCard)
            .then(if (emphasize) Modifier.border(1.5.dp, Wt.Primary, RoundedCornerShape(18.dp)) else Modifier)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, fontSize = T.rowTitle, color = Wt.TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle, fontSize = T.caption, color = Wt.TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                androidx.compose.material.icons.Icons.Filled.KeyboardArrowRight,
                null, tint = Wt.TextMuted, modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 长方形屏 · 左标签 + 右取值 + chevron */
@Composable
fun ValueRow(
    label: String,
    value: String,
    valueColor: Color = Wt.Primary,
    container: Color = Wt.SurfaceCard,
    onClick: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = T.rowTitle, color = Wt.TextPrimary, modifier = Modifier.weight(1f))
        Text(value, fontSize = T.rowTitle, color = valueColor, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Filled.KeyboardArrowRight,
            null, tint = valueColor, modifier = Modifier.size(20.dp)
        )
    }
}

/** 长方形屏 · 底部操作条 */
@Composable
fun BottomBar(
    modifier: Modifier = Modifier,
    leftLabel: String? = null,
    leftColor: Color = Wt.TextSecondary,
    onLeft: (() -> Unit)? = null,
    rightLabel: String? = null,
    /** 用图标代替右侧文字（如确认用 ✓）；与 rightLabel 二选一，图标优先 */
    rightIcon: ImageVector? = null,
    rightColor: Color = Wt.Primary,
    rightTextColor: Color = Wt.OnPrimary,
    onRight: (() -> Unit)? = null,
    tabs: List<String>? = null
) {
    Row(
        modifier.fillMaxWidth().height(52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leftLabel != null) {
            Text(
                leftLabel,
                fontSize = T.body,
                color = if (onLeft != null) leftColor else Wt.TextMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = onLeft != null) { onLeft?.invoke() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        if (tabs != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                tabs.forEach {
                    Text(it, fontSize = T.caption, color = Wt.TextMuted)
                }
            }
            Spacer(Modifier.weight(1f))
        }
        if (rightIcon != null) {
            // 确认类操作用小图标，省掉文字占宽
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(rightColor)
                    .clickable(enabled = onRight != null) { onRight?.invoke() }
                    .padding(11.dp)
            ) {
                Icon(rightIcon, contentDescription = "确认", tint = rightTextColor,
                    modifier = Modifier.size(22.dp))
            }
        } else if (rightLabel != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(26.dp))
                    .background(rightColor)
                    .clickable(enabled = onRight != null) { onRight?.invoke() }
                    .padding(horizontal = 22.dp, vertical = 11.dp)
            ) {
                Text(
                    rightLabel, fontSize = T.button, color = rightTextColor,
                    fontWeight = FontWeight.SemiBold, maxLines = 1
                )
            }
        }
    }
}

/** 长方形屏 · 底部分页标签 */
@Composable
fun PageTabs(active: Int) {
    val names = listOf("翻译", "对话", "设置", "关于")
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        names.forEachIndexed { i, n ->
            Text(
                n,
                fontSize = T.caption,
                color = if (i == active) Wt.Primary else Wt.TextMuted,
                fontWeight = if (i == active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 7.dp)
            )
        }
    }
}

/** 全屏文本输入遮罩（圆形屏与长方形屏共用） */@Composable
fun TextInputOverlay(
    title: String,
    hint: String,
    initial: String,
    limit: Int = 500,
    onDone: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }

    // 返回键 = 取消输入。否则事件会落到 Activity，在翻译主页直接退出应用并丢失已输入内容。
    BackHandler { onCancel() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Wt.Bg)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = T.rowTitle, color = Wt.TextPrimary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(Wt.SurfaceCard)
                .padding(12.dp)
        ) {
            if (text.isEmpty()) {
                Text(hint, fontSize = T.body, color = Wt.TextMuted)
            }
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= limit) text = it },
                textStyle = TextStyle(fontSize = T.body, color = Wt.TextPrimary),
                cursorBrush = SolidColor(Wt.Primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text.trim()) }),
                modifier = Modifier.fillMaxSize().focusRequester(fr)
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${text.length} / $limit", fontSize = T.caption, color = Wt.TextMuted
            )
            Spacer(Modifier.weight(1f))
            // 「取消 / 完成」改为图标（✕ / ✓），省掉文字占宽
            Box(
                Modifier
                    .clip(CircleShape)
                    .clickable { onCancel() }
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Filled.Close, contentDescription = "取消",
                    tint = Wt.TextSecondary, modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(Wt.Primary)
                    .clickable {
                        val t = text.trim()
                        if (t.isNotEmpty()) onDone(t)
                    }
                    .padding(10.dp)
            ) {
                Icon(
                    Icons.Filled.Check, contentDescription = "完成",
                    tint = Wt.OnPrimary, modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 全屏文本查看：用于「点译文全屏显示」。
 * 内容可滚动（译文很长时不会显示不全），返回键 = 关闭。
 */
@Composable
fun FullTextOverlay(title: String, text: String, round: Boolean = false, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    BackHandler { onClose() }
    // 圆形屏：内容缩进到内切圆内，否则右上角 ✕ 按钮会被圆边裁掉、点不到（"无法返回"）。
    val hPad = if (round) 44.dp else 14.dp
    val vPad = if (round) 26.dp else 10.dp
    Column(
        Modifier
            .fillMaxSize()
            .background(Wt.Bg)
            .padding(horizontal = hPad, vertical = vPad),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, fontSize = T.caption, color = Wt.Cyan,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box(
                Modifier
                    .clip(CircleShape)
                    .clickable { onClose() }
                    .padding(8.dp)
            ) {
                Icon(
                    Icons.Filled.Close, contentDescription = "关闭",
                    tint = Wt.TextSecondary, modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Wt.SurfaceCard)
                .verticalScroll(scroll)
                .padding(12.dp)
        ) {
            Text(
                text, fontSize = T.body, color = Wt.TextPrimary,
                lineHeight = T.body * 1.45f
            )
        }
        Spacer(Modifier.height(4.dp))
        Text("上下滑动查看全部 · 返回键关闭", fontSize = T.hint, color = Wt.TextMuted)
    }
}

/** 首次启动的端侧模型装载界面 */
@Composable
fun ModelLoadingScreen(
    phase: String,
    done: Long,
    total: Long,
    note: String,
    round: Boolean
) {
    val frac = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(
        Modifier
            .fillMaxSize()
            .background(Wt.Bg)
            .padding(if (round) 34.dp else 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "端侧模型装载",
            fontSize = T.rowTitle,
            color = Wt.TextPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Wt.NavIdle)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(10.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Wt.Primary)
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            if (phase == "EXTRACT") "${(frac * 100).toInt()}%" else "…",
            fontSize = T.body,
            color = Wt.Primary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            note,
            fontSize = T.caption,
            color = Wt.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 3
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "全部模型随包内置，装载过程不产生任何网络请求",
            fontSize = T.hint,
            color = Wt.TextMuted,
            textAlign = TextAlign.Center,
            maxLines = 3
        )
    }
}

/** 全屏诊断报告（自检结果），可滚动 */
@Composable
fun DiagOverlay(text: String, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Wt.Bg)
            .padding(14.dp)
    ) {
        Text(
            "端侧引擎自检",
            fontSize = T.rowTitle,
            color = Wt.TextPrimary,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Wt.SurfaceCard)
                .padding(10.dp)
        ) {
            Text(
                text,
                fontSize = T.caption,
                color = Wt.TextSecondary,
                modifier = Modifier.verticalScroll(rememberScrollState())
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(22.dp))
                .background(Wt.Primary)
                .clickable { onClose() }
                .padding(horizontal = 28.dp, vertical = 10.dp)
        ) {
            Text("关闭", fontSize = T.button, color = Wt.OnPrimary, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * 圆表盘斜对角的小按钮/小文字（贴合圆形边缘，纯文字无背景）。
 * 用于「历史 / 短语 / 离线」这类角落入口，或底部无跳转的提示文字。
 */
@Composable
fun CornerText(
    label: String,
    color: Color = Wt.TextSecondary,
    onClick: (() -> Unit)? = null
) {
    Text(
        text = label,
        fontSize = T.badge,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}
