package com.sam1112220.watchtranslate.ui.round

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sam1112220.watchtranslate.ui.theme.T
import com.sam1112220.watchtranslate.ui.theme.Wt
import kotlin.math.abs
import kotlin.math.sqrt

// =====================================================================
// 圆形屏径向系统几何常量（设计报告第二节）
//   外环导航 r = 214      上下文弧带 r = 156–200
//   弓形内容带 r = 57–153（双圆弧透镜造型）  中心圆钮 r ≤ 46
//   内容安全半径 186
// =====================================================================

/** 弓形内容带样式 */
enum class BandStyle { IDLE, ACTIVE_TOP, ACTIVE_BOTTOM, GHOST }

/** 尺度换算：设计稿按 450dp 表盘 1:1 出图，此处按实际表盘缩放 */
class RoundScope internal constructor(val scale: Float) {
    fun px(designDp: Float): Dp = (designDp * scale).dp
}

/**
 * 说明：内容带不再使用「双圆弧透镜」造型。
 * 规则椭圆（背板 337.4×120.8 / 内容卡 302.2×174.2），透镜两端是尖角，
 * 正是「形状不规则、显示被切掉」的来源，故整体改为 drawOval。
 */

/** 当前页所属板块 → 外环导航高亮段（0 上 / 1 右 / 2 下 / 3 左） */
enum class Module(val slot: Int, val label: String) {
    TRANSLATE(0, "翻译"),
    DIALOG(1, "对话"),
    SETTINGS(2, "设置"),
    ABOUT(3, "关于")
}

/** 圆形屏径向骨架 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoundScaffold(
    activeModule: Module,
    contextText: String,
    modifier: Modifier = Modifier,
    topBandStyle: BandStyle = BandStyle.IDLE,
    bottomBandStyle: BandStyle = BandStyle.IDLE,
    hideBands: Boolean = false,
    showSubIndicator: Boolean = false,
    centerSize: Dp? = null,
    centerColor: Color = Wt.Primary,
    centerContent: @Composable (RoundScope) -> Unit = {},
    /** 上带之上的横向内容槽（如语种切换行），用 RoundScope 拿同一份缩放 */
    topHint: @Composable (RoundScope) -> Unit = {},
    bottomHint: String = "",
    /** 圆形屏专有：外圈弧形滑块（0..1），由表冠或拖动调节 */
    volumeArc: Float? = null,
    onVolumeArc: ((Float) -> Unit)? = null,
    bottomSlot: @Composable (RoundScope) -> Unit = {},
    onCenterClick: (() -> Unit)? = null,
    onCenterLongClick: (() -> Unit)? = null,
    onTopBandClick: (() -> Unit)? = null,
    onBottomBandClick: (() -> Unit)? = null,
    /** 点击四段外环导航：参数为 12/3/6/9 点（上右下左），可点哪段传哪段 */
    onNavSegmentClick: ((Int) -> Unit)? = null,
    topBand: @Composable (ColumnScope, RoundScope) -> Unit = { _, _ -> },
    bottomBand: @Composable (ColumnScope, RoundScope) -> Unit = { _, _ -> },
    /** 上下两条弧形带的弧线小标题（如「中文 · 原文」「English · 译文」），沿弧带外缘排布 */
    topTitle: String = "",
    bottomTitle: String = "",
    /** 正文（原文 / 译文）沿带中径弧形排布；非空时优先于 topBand/bottomBand 闭包 */
    topBody: String = "",
    bottomBody: String = "",
    topBodyColor: Color = Wt.TextPrimary,
    bottomBodyColor: Color = Wt.TextPrimary,
    /** 正文弧线字号（设计 sp），默认 bandTitle；标题类可传 heroTitle 等更大字号 */
    topBodySize: Float = T.bandTitle.value,
    bottomBodySize: Float = T.bandTitle.value,
    /** 隐藏顶部 12 点导航胶囊（翻译主页用它放语种切换文字） */
    hideTopNav: Boolean = false,
    /**
     * 按 slot 隐藏外环导航胶囊（0=上12点 1=右3点 2=下6点 3=左9点）。
     * 用于「播放声音」页：6 点位置改放音量弧，该段导航需整体去除。
     */
    hiddenNavSlots: Set<Int> = emptySet(),
    /**
     * 弧带文字整体沿弧向下（向圆心方向）偏移的设计单位（450dp 基准，正值=更靠圆心）。
     * 用于修正小标题与正文贴得过近/重叠的情况。
     */
    arcTextInset: Float = 0f,
    /**
     * **上带**文字（topTitle / topBody）额外下移的真实 dp 数。
     *
     * 与 arcTextInset 的区别：arcTextInset 同时影响上下两带，
     * 本参数只推上带，下带纹丝不动——用于「只把上半部分文字往下挪一点」的诉求。
     *
     * 单位是真实 dp（会随屏幕密度换算），内部转为 design 单位后再减半径：
     * 上带文字位于圆心上方，半径减小即 y 增大，视觉上就是「下移」。
     */
    topTextDropDp: Float = 0f,
    /**
     * 音量弧所居角度区间（度，0=3点 90=6点 180=9点 270=12点）。
     * 默认原设计 158~262（左下偏下）；传 38~142 即「正下方」。
     */
    volumeArcStart: Float = 158f,
    volumeArcSpan: Float = 104f,
    /** 顶部 12 点位置的弧形文字（如语种切换 "English = 中文"），替代被隐藏的导航胶囊 */
    topArcText: String = "",
    onTopArcClick: (() -> Unit)? = null,
    /** 四个斜对角的小按钮/小文字（弧形贴圆盘边缘）。角：TL=左上225° TR=右上315° BL=左下135° BR=右下45° */
    cornerTopLeft: String = "",
    cornerTopLeftColor: Color = Wt.TextSecondary,
    onCornerTopLeftClick: (() -> Unit)? = null,
    cornerTopRight: String = "",
    cornerTopRightColor: Color = Wt.TextSecondary,
    onCornerTopRightClick: (() -> Unit)? = null,
    cornerBottomLeft: String = "",
    cornerBottomLeftColor: Color = Wt.TextSecondary,
    onCornerBottomLeftClick: (() -> Unit)? = null,
    cornerBottomRight: String = "",
    cornerBottomRightColor: Color = Wt.TextSecondary,
    onCornerBottomRightClick: (() -> Unit)? = null
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val faceDp = minOf(maxWidth.value, maxHeight.value)
        val k = (faceDp / Wt.FACE_ROUND).coerceIn(0.45f, 1.15f)
        val density = LocalDensity.current

        val navClick = onNavSegmentClick
        Canvas(
            Modifier.fillMaxSize()
                .then(
                    if (onVolumeArc != null) Modifier.pointerInput(Unit) {
                        // 音量弧带手势：按下点落在弧环上才认领，认领后从按下事件起
                        // 全程消费。必须消费按下事件，否则外层 swipePage 会把同一次
                        // 拖动同时判成左右翻页（真机表现为调音量时连翻两页）。
                        val d = k * density.density
                        val rArc = 196f * d
                        val band = 40f * d
                        // 弧带实际占用的角度区间（绘制与判定共用，保证手指落点一致）
                        val aLo = volumeArcStart
                        val aHi = volumeArcStart + volumeArcSpan
                        // 判定区间外扩 8°，避免坐标取整导致端点认领失败
                        val hLo = aLo - 8f
                        val hHi = aHi + 8f
                        awaitEachGesture {
                            // requireUnconsumed = false 是必需的：按下事件可能已被
                            // 上游（焦点 / 外层手势）消费，用默认的 true 会导致整个
                            // 处理器永远不认领手势（真机上表现为拖动完全无效）。
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val dx0 = down.position.x - size.width / 2f
                            val dy0 = down.position.y - size.height / 2f
                            val dist0 = sqrt(dx0 * dx0 + dy0 * dy0)
                            val ang0 = ((Math.toDegrees(
                                kotlin.math.atan2(dy0.toDouble(), dx0.toDouble())
                            ).toFloat() % 360f) + 360f) % 360f
                            val hitArc = abs(dist0 - rArc) <= band && ang0 in hLo..hHi
                            if (!hitArc) {
                                return@awaitEachGesture
                            }
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val c = event.changes.firstOrNull { it.id == down.id } ?: break
                                c.consume()
                                if (!c.pressed) break
                                val dx = c.position.x - size.width / 2f
                                val dy = c.position.y - size.height / 2f
                                val ang = Math.toDegrees(
                                    kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                                ).toFloat()
                                val norm = ((ang % 360f) + 360f) % 360f
                                if (norm in hLo..hHi) {
                                    onVolumeArc(((norm - aLo) / volumeArcSpan).coerceIn(0f, 1f))
                                }
                            }
                        }
                    } else Modifier
                )
                .then(
                    if (navClick != null || onTopArcClick != null ||
                        onCornerTopLeftClick != null || onCornerTopRightClick != null ||
                        onCornerBottomLeftClick != null || onCornerBottomRightClick != null ||
                        onTopBandClick != null || onBottomBandClick != null
                    ) Modifier.pointerInput(Unit) {
                        detectTapGestures { pos ->
                            val cxp = size.width / 2f
                            val cyp = size.height / 2f
                            val dx = pos.x - cxp
                            val dy = pos.y - cyp
                            val dist = sqrt(dx * dx + dy * dy)
                            val ang = ((Math.toDegrees(
                                kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                            ).toFloat() % 360f) + 360f) % 360f
                            val px = k * density.density

                            // 1. 顶部语种切换（270°，半径 200）
                            //    角度容差 34（span 64 的一半）必须 < 45，否则会覆盖左上/右上角按钮
                            if (topArcText.isNotBlank() && onTopArcClick != null &&
                                abs(dist - 200f * px) <= 24f * density.density &&
                                angDist(ang, 270f) <= 34f
                            ) { onTopArcClick(); return@detectTapGestures }

                            // 2. 四角文字（半径 205，贴近屏幕边缘）
                            if (cornerTopLeft.isNotBlank() && onCornerTopLeftClick != null &&
                                abs(dist - 205f * px) <= 26f * density.density && angDist(ang, 225f) <= 40f
                            ) { onCornerTopLeftClick(); return@detectTapGestures }
                            if (cornerTopRight.isNotBlank() && onCornerTopRightClick != null &&
                                abs(dist - 205f * px) <= 26f * density.density && angDist(ang, 315f) <= 40f
                            ) { onCornerTopRightClick(); return@detectTapGestures }
                            if (cornerBottomLeft.isNotBlank() && onCornerBottomLeftClick != null &&
                                abs(dist - 205f * px) <= 26f * density.density && angDist(ang, 135f) <= 40f
                            ) { onCornerBottomLeftClick(); return@detectTapGestures }
                            if (cornerBottomRight.isNotBlank() && onCornerBottomRightClick != null &&
                                abs(dist - 205f * px) <= 26f * density.density && angDist(ang, 45f) <= 40f
                            ) { onCornerBottomRightClick(); return@detectTapGestures }

                            // 3. 外环导航胶囊：中心线 r=207.5、粗细 18~20，命中带取 ±28px
                            val rNav = Wt.R_NAV_CAP * px
                            if (abs(dist - rNav) <= 28f * density.density) {
                                // 与绘制侧 listOf(270f, 0f, 90f, 180f) 保持一致：
                                // 270°=上方 0 / 0°=右方 1 / 90°=下方 2 / 180°=左方 3
                                if (ang in 240f..300f) { if (!hideTopNav && 0 !in hiddenNavSlots) navClick?.invoke(0) }
                                else if (ang < 30f || ang >= 330f) { if (1 !in hiddenNavSlots) navClick?.invoke(1) }
                                else if (ang in 60f..120f) { if (2 !in hiddenNavSlots) navClick?.invoke(2) }
                                else if (ang in 150f..210f) { if (3 !in hiddenNavSlots) navClick?.invoke(3) }
                            }

                            // 4. 上下弧形带（半径 64~176，角度上带 210~330 / 下带 30~150）
                            //    用弧形判定替代原来的矩形 Box 热区，避免矩形热区挡住四角按钮
                            val inBand = dist in (64f * px)..(176f * px)
                            if (inBand) {
                                if (ang in 210f..330f && onTopBandClick != null) {
                                    onTopBandClick(); return@detectTapGestures
                                }
                                if (ang in 30f..150f && onBottomBandClick != null) {
                                    onBottomBandClick(); return@detectTapGestures
                                }
                            }
                        }
                    } else Modifier
                )
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val px = k * density.density
            listOf(270f, 0f, 90f, 180f).forEachIndexed { i, mid ->
                // 翻译主页隐藏顶部 12 点导航（该位置放语种切换文字）
                if (hideTopNav && i == 0) return@forEachIndexed
                // 播放声音页隐藏 6 点导航（该位置改放音量弧）
                if (i in hiddenNavSlots) return@forEachIndexed
                val on = i == activeModule.slot
                val len = (if (on) Wt.NAV_CAP_LEN_ON else Wt.NAV_CAP_LEN) * px
                val th = (if (on) Wt.NAV_CAP_TH_ON else Wt.NAV_CAP_TH) * px
                val r = Wt.R_NAV_CAP * px
                // 弧形小胶囊：用弧长换算成张角，画一段贴合圆环的圆弧（圆头），
                // 替代原来的直线胶囊，更符合圆形表盘。
                val sweepDeg = Math.toDegrees((len / r).toDouble()).toFloat()
                drawArc(
                    color = if (on) Wt.Primary else Wt.NavCapIdle,
                    startAngle = mid - sweepDeg / 2f,
                    sweepAngle = sweepDeg,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(2 * r, 2 * r),
                    style = Stroke(width = th, cap = StrokeCap.Round)
                )
            }

            // 底部紫色弧形细线已按用户要求去除（showSubIndicator 保留参数但不再绘制）

            // 圆形屏专有的外圈弧形滑块（音量）
            if (volumeArc != null) {
                val r3 = 196f * px
                val start = volumeArcStart
                val span = volumeArcSpan
                drawArc(
                    color = Wt.NavIdle,
                    startAngle = start, sweepAngle = span, useCenter = false,
                    topLeft = Offset(c.x - r3, c.y - r3),
                    size = Size(2 * r3, 2 * r3),
                    style = Stroke(width = 11f * px, cap = StrokeCap.Round)
                )
                val v = volumeArc.coerceIn(0f, 1f)
                if (v > 0.01f) {
                    drawArc(
                        color = Wt.Primary,
                        startAngle = start, sweepAngle = span * v, useCenter = false,
                        topLeft = Offset(c.x - r3, c.y - r3),
                        size = Size(2 * r3, 2 * r3),
                        style = Stroke(width = 11f * px, cap = StrokeCap.Round)
                    )
                }
            }

            if (!hideBands) {
                // 上下各一条「弧形带」：厚描边弧段（圆头），贴合圆表盘。
                val rBand = Wt.ARC_BAND_R * px
                val wBand = Wt.ARC_BAND_W * px
                val span = Wt.ARC_BAND_SPAN
                // 上带：中心 270°（12 点）
                drawArc(
                    color = cardColor(topBandStyle, true),
                    startAngle = 270f - span / 2f, sweepAngle = span, useCenter = false,
                    topLeft = Offset(c.x - rBand, c.y - rBand),
                    size = Size(2 * rBand, 2 * rBand),
                    style = Stroke(width = wBand, cap = StrokeCap.Round)
                )
                // 下带：中心 90°（6 点）
                drawArc(
                    color = cardColor(bottomBandStyle, false),
                    startAngle = 90f - span / 2f, sweepAngle = span, useCenter = false,
                    topLeft = Offset(c.x - rBand, c.y - rBand),
                    size = Size(2 * rBand, 2 * rBand),
                    style = Stroke(width = wBand, cap = StrokeCap.Round)
                )

                // 小标题弧线文字（沿弧带外缘排布）
                // arcTextInset > 0 时半径减小 => 文字整体向圆心（沿弧向下）移动
                //
                // topTextDropDp 是「真实 dp」，而半径以 design 单位计（1 design 单位 = k 个真实 dp，
                // 因为 px = k * density.density，1dp = density.density px），故除以 k 换算。
                val topDropDesign = if (topTextDropDp != 0f) topTextDropDp / k else 0f
                val titlePx = (T.arcLabel.value * k.coerceIn(0.92f, 1.08f)) * density.density
                val baseTitleR = (Wt.ARC_TITLE_R - arcTextInset) * px
                val topTitleR = (Wt.ARC_TITLE_R - arcTextInset - topDropDesign) * px
                if (topTitle.isNotBlank()) {
                    drawArcText(topTitle, topTitleR, 270f, Wt.ARC_BAND_SPAN, titlePx, Wt.Cyan)
                }
                if (bottomTitle.isNotBlank()) {
                    drawArcText(bottomTitle, baseTitleR, 90f, Wt.ARC_BAND_SPAN, titlePx, Wt.Violet)
                }
                // 正文（原文 / 译文）沿带中径弧形排布，自适应字号（超长截断加省略号，点带可全屏看）
                val bodyMinPx = T.hint.value * k.coerceIn(0.92f, 1.08f) * density.density
                val baseBodyR = (Wt.ARC_BAND_R - arcTextInset) * px
                val topBodyR = (Wt.ARC_BAND_R - arcTextInset - topDropDesign) * px
                if (topBody.isNotBlank()) {
                    val base = topBodySize * k.coerceIn(0.92f, 1.08f) * density.density
                    drawArcTextFit(topBody, topBodyR, 270f, Wt.ARC_BAND_SPAN, base, bodyMinPx, topBodyColor)
                }
                if (bottomBody.isNotBlank()) {
                    val base = bottomBodySize * k.coerceIn(0.92f, 1.08f) * density.density
                    drawArcTextFit(bottomBody, baseBodyR, 90f, Wt.ARC_BAND_SPAN, base, bodyMinPx, bottomBodyColor)
                }
            }
        }

        if (contextText.isNotBlank()) {
            ArcLabel(
                text = contextText,
                radiusDp = 178f * k,
                fontSizeSp = T.arcLabel.value * k.coerceIn(0.92f, 1.08f),
                color = Wt.TextPrimary
            )
        }

        if (!hideBands) {
            // 语种切换行等提示槽：在上带正上方
            if (topHint != null) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .offset(y = (-180 * k).dp)
                        .size(width = (280 * k).dp, height = (28 * k).dp),
                    contentAlignment = Alignment.Center
                ) { topHint(RoundScope(k)) }
            }
            // 上带：仅承载内容，点击由统一 detectTapGestures 的弧形判定处理
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (-120 * k).dp)
                    .size(width = (304 * k).dp, height = (96 * k).dp),
                contentAlignment = Alignment.Center
            ) {
                if (topBody.isBlank()) {
                    Column(
                        modifier = Modifier.width((224 * k).dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) { topBand(this, RoundScope(k)) }
                }
            }

            // 下带：同样仅承载内容，点击由统一 detectTapGestures 处理
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(y = (120 * k).dp)
                    .size(width = (304 * k).dp, height = (96 * k).dp),
                contentAlignment = Alignment.Center
            ) {
                if (bottomBody.isBlank()) {
                    Column(
                        modifier = Modifier.width((224 * k).dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) { bottomBand(this, RoundScope(k)) }
                }
            }
        }

        if (centerSize != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(centerSize * k)
                    .clip(CircleShape)
                    .background(centerColor)
                    .then(
                        when {
                            onCenterLongClick != null -> Modifier.combinedClickable(
                                onClick = { onCenterClick?.invoke() },
                                onLongClick = { onCenterLongClick() }
                            )
                            onCenterClick != null -> Modifier.clickable { onCenterClick() }
                            else -> Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) { centerContent(RoundScope(k)) }
        }

        // 底部提示文字 → 左下角弧形小字（非跳转提示统一挪到角落，避开 6 点导航胶囊）
        if (bottomHint.isNotBlank()) {
            ArcTextLabel(
                text = bottomHint, midAngleDeg = 135f, spanDeg = 100f, radiusDp = 186f,
                fontSizeSp = T.label.value, color = Wt.TextMuted, bold = false,
                minFontSizeSp = T.label.value * 0.8f
            )
        }
        Box(Modifier.align(Alignment.Center)) { bottomSlot(RoundScope(k)) }

        // 顶部语种切换（替代被隐藏的 12 点导航胶囊，弧形贴在上方）
        if (topArcText.isNotBlank()) {
            ArcTextLabel(
                text = topArcText, midAngleDeg = 270f, spanDeg = 64f, radiusDp = 200f,
                fontSizeSp = T.rowTitle.value, color = Wt.TextPrimary
            )
        }
        // 四个斜对角的小按钮/小文字（弧形贴圆盘边缘）。
        // 角（Compose 角度，0=右/90=下/180=左/270=上）：左上=225°、右上=315°、左下=135°、右下=45°
        if (cornerTopLeft.isNotBlank()) ArcTextLabel(cornerTopLeft, 225f, 46f, 205f, (T.pageTitle.value + 3f), cornerTopLeftColor)
        if (cornerTopRight.isNotBlank()) ArcTextLabel(cornerTopRight, 315f, 46f, 205f, (T.pageTitle.value + 3f), cornerTopRightColor)
        if (cornerBottomLeft.isNotBlank()) ArcTextLabel(cornerBottomLeft, 135f, 46f, 205f, (T.pageTitle.value + 3f), cornerBottomLeftColor)
        if (cornerBottomRight.isNotBlank()) ArcTextLabel(cornerBottomRight, 45f, 46f, 205f, (T.pageTitle.value + 3f), cornerBottomRightColor)
    }
}

private fun cardColor(s: BandStyle, top: Boolean): Color = when (s) {
    BandStyle.ACTIVE_TOP -> Wt.CardActiveTop
    BandStyle.ACTIVE_BOTTOM -> Wt.CardActiveBottom
    BandStyle.GHOST -> if (top) Wt.BandTrackTop else Wt.BandTrackBottom
    BandStyle.IDLE -> if (top) Wt.CardIdleTop else Wt.CardIdleBottom
}

/** 沿 12 点方向排布的弧形文字 */
@Composable
fun ArcLabel(
    text: String,
    radiusDp: Float,
    fontSizeSp: Float,
    color: Color,
    bold: Boolean = true
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val faceDp = minOf(maxWidth.value, maxHeight.value)
        val k = (faceDp / Wt.FACE_ROUND).coerceIn(0.45f, 1.15f)
        val density = LocalDensity.current
        val paint = remember(bold) {
            android.graphics.Paint().apply {
                isAntiAlias = true
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF,
                    if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = radiusDp * density.density
            paint.textSize = fontSizeSp * density.density
            paint.color = color.toArgb()
            val rect = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
            val p = android.graphics.Path().apply { addArc(rect, 180f, 180f) }
            val pm = android.graphics.PathMeasure(p, false)
            val tw = paint.measureText(text)
            val hOffset = ((pm.length - tw) / 2f).coerceAtLeast(0f)
            drawIntoCanvas { cv ->
                cv.nativeCanvas.drawTextOnPath(text, p, hOffset, paint.textSize * 0.32f, paint)
            }
        }
    }
}

/**
 * 沿圆弧排布一行可点击的文字（全屏 Canvas 自定位，圆心 = 屏幕中心）。
 * 用于角落入口（历史 / 短语 / 离线）与顶部语种切换等弧形标签，贴合圆表盘边缘。
 * @param midAngleDeg 弧线中心角（度）：0=右、90=下、180=左、270=上
 * @param radiusDp 半径（设计单位，450dp 基准，内部自动乘缩放 k）
 * @param fontSizeSp 字号（设计 sp，内部自动乘缩放 k）
 */
@Composable
fun ArcTextLabel(
    text: String,
    midAngleDeg: Float,
    spanDeg: Float = 40f,
    radiusDp: Float,
    fontSizeSp: Float,
    color: Color,
    bold: Boolean = true,
    minFontSizeSp: Float = fontSizeSp
) {
    if (text.isBlank()) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val faceDp = minOf(maxWidth.value, maxHeight.value)
        val k = (faceDp / Wt.FACE_ROUND).coerceIn(0.45f, 1.15f)
        val density = LocalDensity.current
        Canvas(Modifier.fillMaxSize()) {
            drawArcTextFit(
                text, radiusDp * k * density.density, midAngleDeg, spanDeg,
                fontSizeSp * k * density.density, minFontSizeSp * k * density.density, color, bold
            )
        }
    }
}

/**
 * 在 DrawScope 内沿圆弧排布一行文字。
 * @param midAngle 弧线中心角（度）：0=右、90=下、180=左、270=上
 * 正立方向自动判断：上半（180°≤mid<360°，y 负半区）顺时针、下半（0°≤mid<180°）逆时针，
 * 保证文字始终朝外、不颠倒。
 */
private fun DrawScope.drawArcText(
    text: String,
    radiusPx: Float,
    midAngle: Float,
    span: Float,
    fontSizePx: Float,
    color: Color,
    bold: Boolean = true
) {
    if (text.isBlank()) return
    val c = center
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = fontSizePx
        this.color = color.toArgb()
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.SANS_SERIF,
            if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
        )
        isFakeBoldText = bold
    }
    val rect = android.graphics.RectF(
        c.x - radiusPx, c.y - radiusPx, c.x + radiusPx, c.y + radiusPx
    )
    val path = android.graphics.Path()
    val norm = ((midAngle % 360f) + 360f) % 360f
    if (norm >= 180f) {
        // 上半：顺时针（sweep 正），文字朝外、正立
        path.addArc(rect, midAngle - span / 2f, span)
    } else {
        // 下半：逆时针（sweep 负），文字朝外、正立
        path.addArc(rect, midAngle + span / 2f, -span)
    }
    val pm = android.graphics.PathMeasure(path, false)
    val tw = paint.measureText(text)
    val hOffset = ((pm.length - tw) / 2f).coerceAtLeast(0f)
    val vOff = if (norm >= 180f) paint.textSize * 0.30f else paint.textSize * 0.05f
    drawContext.canvas.nativeCanvas.drawTextOnPath(text, path, hOffset, vOff, paint)
}

/**
 * 沿圆弧排布一行文字并自适应字号：文字超长时逐级缩小字号，仍放不下则截断加省略号。
 * 用于原文 / 译文正文（长度不固定）。
 */
private fun DrawScope.drawArcTextFit(
    text: String,
    radiusPx: Float,
    midAngle: Float,
    span: Float,
    baseFontSizePx: Float,
    minFontSizePx: Float,
    color: Color,
    bold: Boolean = false
) {
    if (text.isBlank()) return
    val arcLen = (radiusPx * span * Math.PI / 180f).toFloat()
    val paint = android.graphics.Paint().apply { isAntiAlias = true }
    var fs = baseFontSizePx
    paint.textSize = fs
    var tw = paint.measureText(text)
    while (tw > arcLen && fs > minFontSizePx) {
        fs *= 0.92f
        paint.textSize = fs
        tw = paint.measureText(text)
    }
    var shown = text
    if (tw > arcLen) {
        while (shown.isNotEmpty() && paint.measureText(shown + "…") > arcLen) {
            shown = shown.dropLast(1)
        }
        shown += "…"
    }
    drawArcText(shown, radiusPx, midAngle, span, fs, color, bold)
}

/** 两个角度之间的最小差（处理 0/360 环绕） */
private fun angDist(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}
