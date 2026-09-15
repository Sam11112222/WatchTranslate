package com.sam1112220.watchtranslate.ui.rect

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sam1112220.watchtranslate.AppVm
import com.sam1112220.watchtranslate.R
import com.sam1112220.watchtranslate.Route
import com.sam1112220.watchtranslate.Stage
import com.sam1112220.watchtranslate.data.Fmt
import com.sam1112220.watchtranslate.data.ShapeMode
import com.sam1112220.watchtranslate.engine.Lang
import com.sam1112220.watchtranslate.ui.BottomBar
import com.sam1112220.watchtranslate.ui.PageTabs
import com.sam1112220.watchtranslate.ui.StatusBar
import com.sam1112220.watchtranslate.ui.TextInputOverlay
import com.sam1112220.watchtranslate.ui.theme.T
import com.sam1112220.watchtranslate.ui.theme.Wt

// =====================================================================
// 长方形屏 · 列表布局骨架
// =====================================================================

@Composable
fun RectScaffold(
    title: String,
    vm: AppVm,
    withStatusBar: Boolean = true,
    bottom: @Composable () -> Unit = {},
    scrollable: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Wt.Bg)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
    ) {
        if (withStatusBar) {
            StatusBar(Fmt.nowTime())
        }
        Spacer(Modifier.height(if (withStatusBar) 4.dp else 0.dp))
        Text(
            title,
            fontSize = T.pageTitle,
            color = Wt.TextPrimary,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
        ) { content() }
        bottom()
    }
}

/** 语对芯片：中文 ⇄ English */
@Composable
fun LangChips(
    from: Lang,
    to: Lang,
    onSwap: () -> Unit,
    onFrom: () -> Unit,
    onTo: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Wt.BandTopActive)
                .clickable { onFrom() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(from.label, fontSize = T.chip, color = Wt.Cyan, fontWeight = FontWeight.SemiBold)
        }
        Icon(
            Icons.Filled.SwapHoriz,
            contentDescription = "切换语种",
            tint = Wt.TextSecondary,
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .size(22.dp)
                .clickable { onSwap() }
        )
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(Wt.BandBottomActive)
                .clickable { onTo() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(to.label, fontSize = T.chip, color = Wt.Violet, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 通用卡片容器 */
@Composable
fun CardBox(
    modifier: Modifier = Modifier,
    color: Color = Wt.SurfaceCard,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(color)
            .padding(12.dp)
    ) { content() }
}

/** 音量 / 语速 直线滑块行 */
@Composable
fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = T.body, color = Wt.TextPrimary)
            Spacer(Modifier.width(10.dp))
            Text(valueText, fontSize = T.body, color = Wt.TextSecondary)
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Wt.Primary,
                activeTrackColor = Wt.Primary,
                inactiveTrackColor = Wt.NavIdle
            ),
            modifier = Modifier.fillMaxWidth().height(30.dp)
        )
    }
}

/** 单选行 */
@Composable
fun RadioRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) Wt.BandBottomActive else Wt.SurfaceCard)
            .then(
                if (selected) Modifier.border(1.5.dp, Wt.Primary, RoundedCornerShape(18.dp))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) Wt.Primary else Wt.TextMuted, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(Modifier.size(11.dp).clip(CircleShape).background(Wt.Primary))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = T.rowTitle, color = Wt.TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, fontSize = T.caption, color = Wt.TextSecondary, maxLines = 1)
        }
    }
}

/** 绿色状态徽标条 */
@Composable
fun StatusBadge(text: String, color: Color = Wt.Green) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = T.body, color = color, fontWeight = FontWeight.Medium)
    }
}

/** 图标 + 主副文案 + 右箭头（供底部快捷使用） */
@Composable
fun IconRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    container: Color = Wt.SurfaceCard,
    trailingCheck: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = T.rowTitle, color = Wt.TextPrimary, maxLines = 1)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = T.caption, color = Wt.TextSecondary, maxLines = 1)
            }
        }
        if (trailingCheck) {
            Icon(Icons.Filled.Check, null, tint = Wt.Primary, modifier = Modifier.size(20.dp))
        } else {
            Icon(Icons.Filled.KeyboardArrowRight, null, tint = Wt.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}

/** 记录卡（原文 + 译文 + 时间） */
@Composable
fun RecordCard(
    src: String,
    dst: String,
    time: String,
    container: Color,
    accent: Color,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Text(src, fontSize = T.rowTitle, color = Wt.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            "$dst · $time",
            fontSize = T.caption, color = accent,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}
