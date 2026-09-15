package com.sam1112220.watchtranslate.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 深色基线配色（严格取自设计稿 PDF）。
 * 主色为 M3 Expressive 通用紫 #B9A5FF（设计报告第九节指明可整版替换）。
 */
object Wt {
    // ---- 底色 / 表面 ----
    val Bg = Color(0xFF0A0C10)
    val SurfaceLow = Color(0xFF13161C)
    val SurfaceCard = Color(0xFF1B1F27)
    val SurfaceHigh = Color(0xFF232833)
    val Outline = Color(0xFF2C3340)

    // ---- 弓形内容带 ----
    val BandIdle = Color(0xFF191D25)
    val BandTopActive = Color(0xFF123034)   // 中文 / 识别：青绿调
    val BandBottomActive = Color(0xFF2A1E44) // 译文：紫调
    val BandGhost = Color(0xFF12151B)

    // ---- 圆形屏椭圆带（数值与配色取自设计稿导出 PDF 实测）----
    val NavCapIdle = Color(0xFF232A38)      // 非活跃导航胶囊
    val BandTrackTop = Color(0xFF1B2333)    // 上弓形带背板椭圆
    val BandTrackBottom = Color(0xFF101520) // 下弓形带背板椭圆
    val CardIdleTop = Color(0xFF1F2532)     // 上内容卡（空闲）
    val CardIdleBottom = Color(0xFF161B25)  // 下内容卡（空闲）
    val CardActiveTop = Color(0xFF14262E)   // 上内容卡（原文 / 识别态）
    val CardActiveBottom = Color(0xFF241C3A) // 下内容卡（译文态）

    // ---- 主色 ----
    val Primary = Color(0xFFB9A5FF)
    val PrimaryDim = Color(0xFF4B3F78)
    val OnPrimary = Color(0xFF1B1035)

    // ---- 语义色 ----
    val Mint = Color(0xFF6FE0BC)      // 朗读 / 试听 按钮
    val OnMint = Color(0xFF06231A)
    val Cyan = Color(0xFF6FE3E1)      // 中文 / 原文 / 识别 标签
    val Violet = Color(0xFFC4B2FF)    // English / 译文 标签
    val Green = Color(0xFF5FE3A1)     // 成功 / 就绪
    val Amber = Color(0xFFF2C14E)     // 警示 / 应急

    // ---- 文字 ----
    val TextPrimary = Color(0xFFF1F3F8)
    val TextSecondary = Color(0xFFA7B0C0)
    val TextMuted = Color(0xFF7A8398)
    val NavIdle = Color(0xFF2A3040)

    // ---- 几何（dp）：严格对应设计报告第二节 ----
    const val FACE_ROUND = 450f     // 圆形 450 × 450 dp
    const val FACE_RECT_W = 396f    // 长方形 396 × 484 dp
    const val FACE_RECT_H = 484f
    const val R_NAV = 214f          // 外环导航（旧弧段方案，保留兼容）
    const val R_CTX_OUT = 200f      // 上下文弧带外
    const val R_CTX_IN = 156f       // 上下文弧带内
    const val R_BAND_OUT = 153f     // 弓形内容带外
    const val R_BAND_IN = 57f       // 弓形内容带内
    const val R_CENTER = 46f        // 中心圆钮
    const val R_SAFE = 186f         // 内容安全半径

    // ---- 外环导航：细圆环 + 四个胶囊（数值取自设计稿导出 PDF 实测）----
    const val R_RING = 225f         // 细圆环半径（描边 4）
    const val R_NAV_CAP = 207.5f    // 胶囊中心线半径
    const val NAV_CAP_LEN = 151.6f  // 非活跃胶囊长度
    const val NAV_CAP_TH = 18f      // 非活跃胶囊粗细
    const val NAV_CAP_LEN_ON = 153.7f
    const val NAV_CAP_TH_ON = 20.1f

    // ---- 两条内容带：上下各一条「胶囊」（圆角矩形，圆角=高的一半）----
    // 依据参考效果图实测比例：宽接近表盘内宽、高约表盘的 1/4、两端半圆、上下边为直线。
    // 中心距圆心 110 + 高 106 → 内缘 57，中心钮 r=46，两者之间留 11 的余量，互不重叠。
    const val SLOT_W = 350f         // 内容带胶囊宽
    const val SLOT_H = 106f         // 内容带胶囊高
    const val SLOT_CY = 110f        // 内容带胶囊中心距圆心

    // ---- 弧形带（贴合圆表盘的环段，取代胶囊）----
    // 环形扇区：中心半径 120、粗细 112 → 外 176 / 内 64；中心钮 r=46、外环 context 178 均不碰。
    const val ARC_BAND_R = 120f     // 弧带中心半径
    const val ARC_BAND_W = 112f     // 弧带粗细
    const val ARC_BAND_SPAN = 120f  // 弧带张角（上带中心 270°，下带中心 90°）
    const val ARC_TITLE_R = 162f    // 小标题弧线半径（近弧带外缘，留出正文空间）
}

/**
 * 字号规范 —— 设计报告第五节给出 13px 硬下限、正文最低 14px。
 * 真机（OPPO Watch 233dp 圆屏）验证：原字号整体偏大，
 * 按 450dp 设计基准在实际 233dp 表盘上等比缩小（×0.75），
 * 保证 12sp 以上可读，同时不再撑满弓形带。
 * v1.0.6 再整体下调约 10%：真机上仍偏大，且椭圆带内需要留出余量。
 */
object T {
    val pageTitle = 15.sp        // 页面主标题
    val heroTitle = 16.sp        // 关于页姓名
    val rowTitle = 14.sp         // 卡片 / 列表主文案
    val bandTitle = 13.sp        // 弓形带主文案
    val body = 13.sp             // 正文 · 译文
    val chip = 13.sp             // 芯片文字
    val button = 14.sp           // 按钮文字
    val arcLabel = 12.sp         // 弧带标题
    val label = 11.sp            // 弓形带小标签 / 副文案
    val caption = 11.sp          // 脚注
    val hint = 10.sp             // 底部提示
    val badge = 11.sp            // 时间 / 状态徽标
    val url = 10.sp              // 外链 URL
}

/** 通用间距 */
object S {
    val screenPad = 12.dp
    val rowGap = 8.dp
    val bottomBar = 52.dp
    val statusBar = 26.dp
}
