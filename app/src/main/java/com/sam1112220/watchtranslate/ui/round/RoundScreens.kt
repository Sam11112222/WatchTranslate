package com.sam1112220.watchtranslate.ui.round

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sam1112220.watchtranslate.AppVm
import com.sam1112220.watchtranslate.R
import com.sam1112220.watchtranslate.Route
import com.sam1112220.watchtranslate.Stage
import com.sam1112220.watchtranslate.data.Fmt
import com.sam1112220.watchtranslate.data.ShapeMode
import com.sam1112220.watchtranslate.data.SpeedTier
import com.sam1112220.watchtranslate.engine.Lang
import com.sam1112220.watchtranslate.ui.BandLabel
import com.sam1112220.watchtranslate.ui.BandText
import com.sam1112220.watchtranslate.ui.CenterIcon
import com.sam1112220.watchtranslate.ui.CenterText
import com.sam1112220.watchtranslate.ui.CornerText
import com.sam1112220.watchtranslate.ui.TextInputOverlay
import com.sam1112220.watchtranslate.ui.rotaryInput
import com.sam1112220.watchtranslate.ui.swipePage
import com.sam1112220.watchtranslate.ui.theme.T
import com.sam1112220.watchtranslate.ui.theme.Wt
import kotlin.math.abs

private const val ROTARY_STEP = 42f

/** 1 毫米对应多少 dp（Android 基准：160dp = 1 英寸 = 25.4 毫米）。 */
private const val MM_TO_DP = 160f / 25.4f

@Composable
fun RoundApp(vm: AppVm) {
    var editing by remember { mutableStateOf(false) }
    var keyboardForDialog by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var acc by remember { mutableStateOf(0f) }

    LaunchedEffect(toast) {
        if (toast != null) {
            kotlinx.coroutines.delay(1300)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize().background(Wt.Bg)) {

        when {
            editing -> TextInputOverlay(
                title = "输入要翻译的内容",
                hint = "中文或 English 都可以",
                initial = vm.input,
                onDone = { vm.input = it; editing = false; vm.doTranslate() },
                onCancel = { editing = false }
            )

            keyboardForDialog -> TextInputOverlay(
                title = "键盘补充 · ${vm.dialogMy.label}",
                hint = "输入对方说的话",
                initial = "",
                onDone = { keyboardForDialog = false; vm.manualRecognize(it) },
                onCancel = { keyboardForDialog = false }
            )

            else -> Box(
                Modifier
                    .fillMaxSize()
                    .rotaryInput { d ->
                        acc += d
                        if (abs(acc) >= ROTARY_STEP) {
                            val steps = (acc / ROTARY_STEP).toInt()
                            acc -= steps * ROTARY_STEP
                            onRotary(vm, steps)
                        }
                    }
                    .swipePage(
                        onRight = { vm.goBackOne() },
                        onLeft = { vm.goForward() }
                    )
            ) {
                when (vm.route) {
                    Route.TRANSLATE -> RoundTranslate(vm, onEdit = { editing = true }, toast = { toast = it })
                    Route.HISTORY -> RoundHistory(vm, toast = { toast = it })
                    Route.PHRASES -> RoundPhrases(vm, toast = { toast = it })
                    Route.DIALOG_LANG -> RoundDialogLang(vm)
                    Route.DIALOG_MIC -> RoundDialogMic(vm, toast = { toast = it }, onKeyboard = { keyboardForDialog = true })
                    Route.DIALOG_RESULT -> RoundDialogResult(vm, toast = { toast = it })
                    Route.SETTINGS -> RoundSettings(vm)
                    Route.SOUND -> RoundSound(vm)
                    Route.SCREENFIT -> RoundScreenFit(vm)
                    Route.MODEL -> RoundModel(vm)
                    Route.VOICE -> RoundVoice(vm)
                    Route.ABOUT -> RoundAbout(vm, toast = { toast = it })
                }

                toast?.let {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = (-26).dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Wt.SurfaceHigh)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(it, fontSize = T.caption, color = Wt.TextPrimary)
                    }
                }
            }
        }
    }
}

/** 表冠旋转分发 */
private fun onRotary(vm: AppVm, steps: Int) {
    when (vm.route) {
        Route.DIALOG_LANG -> {
            val l = if (vm.dialogMy == Lang.ZH) Lang.EN else Lang.ZH
            vm.applyMyLang(l)
            vm.applyPeerLang(l.other)
        }
        Route.SOUND -> vm.applyVolume((vm.volume + steps * 4).coerceIn(0, 100))
        Route.SCREENFIT -> vm.setShapeMode(if (vm.shape == ShapeMode.ROUND) ShapeMode.RECT else ShapeMode.ROUND)
        Route.HISTORY -> {
            if (vm.historyItems.isNotEmpty()) {
                vm.phraseCatIndex = 0
            }
        }
        Route.PHRASES -> {
            val n = vm.phraseCats.size
            if (n > 0) vm.phraseCatIndex = ((vm.phraseCatIndex + steps) % n + n) % n
        }
        Route.TRANSLATE -> {
            if (vm.stage == Stage.DONE) {
                if (steps > 0) vm.speak(vm.result, vm.toLang) else vm.stopSpeak()
            }
        }
        else -> Unit
    }
}

// =====================================================================
// 板块 1 · 文本翻译（状态 A 翻译前 / 状态 B 翻译后）
// =====================================================================
@Composable
private fun RoundTranslate(vm: AppVm, onEdit: () -> Unit, toast: (String) -> Unit) {
    val done = vm.stage == Stage.DONE
    RoundScaffold(
        activeModule = Module.TRANSLATE,
        contextText = "",
        topBandStyle = if (done) BandStyle.ACTIVE_TOP else BandStyle.IDLE,
        bottomBandStyle = if (done) BandStyle.ACTIVE_BOTTOM else BandStyle.IDLE,
        centerSize = 92.dp,
        centerColor = if (done) Wt.Mint else Wt.Primary,
        // 翻译主页：隐藏顶部 12 点导航胶囊，改放语种切换弧形文字
        hideTopNav = true,
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        onTopBandClick = onEdit,
        onCenterClick = { if (done) vm.speak(vm.result, vm.toLang) else { if (vm.input.isNotBlank()) vm.doTranslate() else onEdit() } },
        // 点译文区 → 全屏可滚动查看（长译文不再被带子截断）
        onBottomBandClick = { if (done) vm.openFullResult() },
        onCenterLongClick = { if (done) vm.stopSpeak() },
        // 语种切换：弧形文字贴在上方（替代 12 点导航），点它互换源/目标
        topArcText = "${vm.fromLang.label} = ${vm.toLang.label}",
        onTopArcClick = { vm.swapLangs() },
        topTitle = when {
            done -> "${vm.fromLang.label} · 原文"
            vm.input.isNotBlank() -> "待翻译"
            else -> ""
        },
        // 原文 / 译文正文沿弧形带中径排布（弧形显示）
        topBody = when {
            done -> vm.input
            vm.input.isNotBlank() -> vm.input
            else -> "输入要翻译的内容"
        },
        topBodyColor = if (done || vm.input.isNotBlank()) Wt.TextPrimary else Wt.TextMuted,
        bottomTitle = if (done && !vm.busy) "${vm.toLang.label} · 译文" else "",
        bottomBody = when {
            vm.busy -> "推理中…"
            done -> vm.result
            else -> "点击翻译后在此显示结果"
        },
        bottomBodyColor = if (done) Wt.TextPrimary else Wt.TextMuted,
        // 历史/短语/离线 → 斜对角（3 个入口：左上、右上、右下，弧形文字贴边缘）
        cornerTopLeft = if (!done) "历史" else "",
        onCornerTopLeftClick = { vm.navigate(Route.HISTORY) },
        cornerTopRight = if (!done) "短语" else "",
        onCornerTopRightClick = { vm.navigate(Route.PHRASES) },
        cornerBottomRight = if (!done) "离线" else "",
        onCornerBottomRightClick = { vm.navigate(Route.MODEL) },
        // 翻译完成后左下角出现「重置」：清空所有内容，回到待翻译状态
        cornerBottomLeft = if (done) "重置" else "",
        cornerBottomLeftColor = Wt.Primary,
        onCornerBottomLeftClick = { vm.resetTranslate() },
        centerContent = {
            if (vm.busy) CenterText("…", Wt.OnPrimary)
            else if (done) CenterIcon(Icons.Filled.VolumeUp, Wt.OnMint)
            else CenterText("翻译", Wt.OnPrimary)
        }
    )
}

// =====================================================================
// 板块 2 · 对话翻译（步骤 1 语言选择）
// =====================================================================
@Composable
private fun RoundDialogLang(vm: AppVm) {
    RoundScaffold(
        activeModule = Module.DIALOG,
        contextText = "对话模式",
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        centerSize = 96.dp,
        centerColor = Wt.Primary,
        onCenterClick = { vm.startDialog(); vm.navigate(Route.DIALOG_MIC) },
        onTopBandClick = { val l = if (vm.dialogMy == Lang.ZH) Lang.EN else Lang.ZH; vm.applyMyLang(l); vm.applyPeerLang(l.other) },
        onBottomBandClick = { val l = if (vm.dialogPeer == Lang.ZH) Lang.EN else Lang.ZH; vm.applyPeerLang(l); vm.applyMyLang(l.other) },
        topBody = "我说 · ${vm.dialogMy.label}",
        topBodySize = T.heroTitle.value,
        bottomBody = "对方说 · ${vm.dialogPeer.label}",
        bottomBodySize = T.heroTitle.value,
        bottomHint = "旋转表冠切换语种",
        centerContent = { CenterIcon(Icons.Filled.Check, Wt.OnPrimary, 40.dp) }
    )
}

// =====================================================================
// 板块 2 · 步骤 2 麦克风待机（弓形带让位，中心为直径 160 大麦克风）
// =====================================================================
@Composable
private fun RoundDialogMic(vm: AppVm, toast: (String) -> Unit, onKeyboard: () -> Unit) {
    LaunchedEffect(vm.route) {
        if (!vm.listening && vm.speechErr == null) vm.startListening()
    }
    RoundScaffold(
        activeModule = Module.DIALOG,
        contextText = "${vm.dialogMy.label} ⇄ ${vm.dialogPeer.label}",
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        hideBands = true,
        centerSize = 160.dp,
        centerColor = Wt.Primary,
        onCenterClick = {
            if (vm.listening) vm.stopListening()
            else vm.startListening()
        },
        centerContent = { CenterIcon(Icons.Filled.Mic, Wt.OnPrimary, 68.dp) },
        bottomHint = when {
            vm.listening -> vm.partial.ifBlank { "正在聆听…" }
            vm.speechErr != null -> vm.speechErr!!
            else -> "轻点开始说话"
        },
        // 「用键盘补充」→ 右下角（弧形文字贴边缘）。
        // 常驻显示（原来只在识别出错时出现）：环境嘈杂或想精确输入时随时可用。
        cornerBottomRight = "用键盘补充",
        cornerBottomRightColor = Wt.Primary,
        onCornerBottomRightClick = { onKeyboard() }
    )
}

// =====================================================================
// 板块 2 · 步骤 3 识别结果
// =====================================================================
@Composable
private fun RoundDialogResult(vm: AppVm, toast: (String) -> Unit) {
    RoundScaffold(
        activeModule = Module.DIALOG,
        // 用户要求：本页不再显示顶部的语种上下文行（原「中文 ⇄ English」，白色），
        // 并把「识别 · xx」与识别正文整体下移 3mm，避免贴住表盘上缘。
        contextText = "",
        topTextDropDp = 3f * MM_TO_DP,
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        topBandStyle = BandStyle.ACTIVE_TOP,
        bottomBandStyle = BandStyle.ACTIVE_BOTTOM,
        centerSize = 88.dp,
        centerColor = Wt.Mint,
        onCenterClick = { vm.speak(vm.recogDst, if (vm.recogFrom == vm.dialogMy) vm.dialogPeer else vm.dialogMy) },
        onBottomBandClick = {
            vm.copyToClipboard(vm.recogDst)
            toast("译文已复制")
        },
        topTitle = "识别 · ${vm.recogFrom.label}",
        topBody = vm.recogText,
        bottomTitle = "译文 · ${if (vm.recogFrom == vm.dialogMy) vm.dialogPeer.label else vm.dialogMy.label}",
        bottomBody = if (vm.recogDst.isBlank()) "翻译中…" else vm.recogDst,
        bottomHint = "右下角「重置」开始下一句",
        // 用户反馈：对话模式说完一句后无法说下一句。
        // 原因：识别一次后 dialogStage 变 DONE，结果页没有任何入口复位状态，
        // 切换板块再回来仍然停在结果页。这里右下角加「重置」回到麦克风，开始下一句。
        cornerBottomRight = "重置",
        cornerBottomRightColor = Wt.Primary,
        onCornerBottomRightClick = { vm.resetDialog() },
        centerContent = { CenterIcon(Icons.Filled.VolumeUp, Wt.OnMint, 34.dp) }
    )
}

// =====================================================================
// 板块 3 · 设置（两个选项各占一条弓形带，中心为第三项）
// =====================================================================
@Composable
private fun RoundSettings(vm: AppVm) {
    RoundScaffold(
        activeModule = Module.SETTINGS,
        contextText = "设置",
        showSubIndicator = true,
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        centerSize = 92.dp,
        centerColor = Wt.SurfaceHigh,
        onCenterClick = { vm.navigate(Route.MODEL) },
        onTopBandClick = { vm.navigate(Route.SOUND) },
        onBottomBandClick = { vm.navigate(Route.SCREENFIT) },
        topBody = "播放声音",
        topBodySize = T.heroTitle.value,
        bottomBody = "屏幕适配",
        bottomBodySize = T.heroTitle.value,
        bottomHint = buildString {
            append(if (vm.autoSpeak) "声音已开启" else "声音已关闭")
            append(" · 当前").append(if (vm.isRound) "圆形" else "长方形")
            append(" · 模型就绪")
        },
        centerContent = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Storage, null, tint = Wt.Amber, modifier = Modifier.size(24.dp))
                Spacer(Modifier.height(2.dp))
                CenterText("离线模型", Wt.TextPrimary, T.label)
            }
        }
    )
}

// =====================================================================
// 板块 3 · 选项 1 播放声音（弧形滑块 + 表冠）
// =====================================================================
@Composable
private fun RoundSound(vm: AppVm) {
    RoundScaffold(
        activeModule = Module.SETTINGS,
        contextText = "播放声音",
        showSubIndicator = true,
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        centerSize = 88.dp,
        centerColor = Wt.Mint,
        onCenterClick = {
            if (vm.ttsReady) vm.speakSample(
                if (vm.volume > 0) "语音输出已就绪，这是试听效果。" else "音量已关闭。",
                Lang.ZH
            )
        },
        topBody = if (vm.autoSpeak) "对话自动朗读 · 已开启" else "对话自动朗读 · 已关闭",
        bottomBody = "音量 ${vm.volume}%　语速 ${String.format(java.util.Locale.US, "%.1f", vm.rate)}×　音调 ${String.format(java.util.Locale.US, "%.2f", vm.pitch)}×",
        bottomTitle = if (vm.ttsReady) "音色 ${vm.voiceFor(Lang.ZH)?.substringAfterLast('-') ?: "自动"}" else vm.ttsLabel,
        // 自动朗读 + 音调 → 左上/右上角（底部留给音量弧，弧形文字贴边缘）
        cornerTopLeft = if (vm.autoSpeak) "关自动朗读" else "开自动朗读",
        cornerTopLeftColor = Wt.Primary,
        onCornerTopLeftClick = { vm.applyAutoSpeak(!vm.autoSpeak) },
        cornerTopRight = "音调 ${String.format(java.util.Locale.US, "%.2f", vm.pitch)}",
        cornerTopRightColor = Wt.Cyan,
        onCornerTopRightClick = {
            val next = when {
                vm.pitch < 1.0f -> 1.15f
                vm.pitch < 1.25f -> 1.35f
                vm.pitch < 1.45f -> 1.5f
                else -> 0.9f
            }
            vm.applyPitch(next)
        },
        centerContent = { CenterIcon(Icons.Filled.PlayArrow, Wt.OnMint, 40.dp) },
        volumeArc = vm.volume / 100f,
        // 用户要求：6 点位置的板块切换按钮去除（其余三边不变），该位置改放音量弧。
        hiddenNavSlots = setOf(2),
        // 音量弧移到「正下方」：40°~140°，以 90°（6 点）为中心，弧形/粗细/半径均不变。
        volumeArcStart = 40f,
        volumeArcSpan = 100f,
        // 设计报告第二节：圆形屏外圈弧形滑块「由表冠或拖动调节」。
        // 表冠走 onRotary，这里补上拖动通路（弧带手势会消费事件，不会误翻页）。
        onVolumeArc = { f -> vm.applyVolume((f * 100f + 0.5f).toInt()) }
    )
}

// =====================================================================
// 板块 3 · 选项 2 屏幕适配
// =====================================================================
@Composable
private fun RoundScreenFit(vm: AppVm) {
    val round = vm.shape == ShapeMode.ROUND
    RoundScaffold(
        activeModule = Module.SETTINGS,
        contextText = "屏幕适配",
        showSubIndicator = true,
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        topBandStyle = if (round) BandStyle.ACTIVE_TOP else BandStyle.IDLE,
        bottomBandStyle = if (round) BandStyle.IDLE else BandStyle.ACTIVE_BOTTOM,
        centerSize = 66.dp,
        centerColor = Wt.PrimaryDim,
        onCenterClick = { vm.reapplyShape() },
        onTopBandClick = { vm.setShapeMode(ShapeMode.ROUND) },
        onBottomBandClick = { vm.setShapeMode(ShapeMode.RECT) },
        topBody = if (round) "圆形表盘 · 已选" else "圆形表盘",
        topBodyColor = if (round) Wt.TextPrimary else Wt.TextSecondary,
        bottomBody = if (!round) "长方形表盘 · 已选" else "长方形表盘",
        bottomBodyColor = if (!round) Wt.TextPrimary else Wt.TextSecondary,
        bottomHint = "自动识别结果：${if (vm.systemRound) "圆形" else "长方形"}",
        centerContent = {
            Icon(
                if (round) Icons.Filled.Adjust else Icons.Filled.Storage,
                null, tint = Wt.Primary, modifier = Modifier.size(26.dp)
            )
        }
    )
}

// =====================================================================
// 离线模型与存储
// =====================================================================
@Composable
private fun RoundModel(vm: AppVm) {
    LaunchedEffect(vm.route) { vm.refreshStats() }
    val total = vm.assetBytes + vm.appDataBytes
    RoundScaffold(
        activeModule = Module.SETTINGS,
        contextText = "离线模型",
        showSubIndicator = true,
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        topBandStyle = BandStyle.ACTIVE_TOP,
        bottomBandStyle = BandStyle.ACTIVE_BOTTOM,
        centerSize = 88.dp,
        centerColor = Wt.Primary,
        onCenterClick = { vm.refreshStats() },
        // 上带 = 翻译速度档开关（快速 / 高精度）
        onTopBandClick = {
            vm.applySpeedTier(
                if (vm.speedTier == SpeedTier.FAST) SpeedTier.ACCURATE else SpeedTier.FAST
            )
        },
        onBottomBandClick = { vm.runSelfTest() },
        // 用户反馈：小标题「翻译速度 · 点此切换」与正文「快速档…」贴得过近有重叠，
        // 两条都沿弧向下（向圆心）移动约 2mm。450dp 设计基准下 2mm ≈ 5.7 设计单位。
        arcTextInset = 5.7f,
        topTitle = "翻译速度 · 点此切换",
        topBody = if (vm.speedTier == SpeedTier.FAST)
            if (vm.mlKit.ready) "快速档 · ML Kit 端侧翻译"
            else if (vm.mlKit.downloading) "快速档 · 正在下载语言包…"
            else "快速档 · ML Kit（首次联网下模型）"
        else "高精度档 · Marian 本地模型",
        topBodySize = T.label.value,
        bottomTitle = "存储占用",
        bottomBody = "${Fmt.sizeShort(total)} · 余 ${Fmt.sizeShort(vm.freeBytes)}",
        bottomHint = vm.neuralError?.let { "异常 · $it" }
            ?: if (vm.speedTier == SpeedTier.FAST)
                if (vm.mlKit.ready) "点下方 6 点位置运行引擎自检" else vm.loadNote
            else if (vm.neuralReady) "点下方 6 点位置运行引擎自检" else vm.loadNote,
        centerContent = { CenterText(Fmt.sizeShort(total), Wt.OnPrimary, T.hint) }
    )
}

// =====================================================================
// 板块 3 · 选项 1 语音输出与音色
// =====================================================================
@Composable
private fun RoundVoice(vm: AppVm) {
    RoundScaffold(
        activeModule = Module.SETTINGS,
        contextText = "语音输出",
        showSubIndicator = true,
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        topBandStyle = BandStyle.ACTIVE_TOP,
        bottomBandStyle = BandStyle.ACTIVE_BOTTOM,
        centerSize = 88.dp,
        centerColor = Wt.Mint,
        onCenterClick = {
            if (vm.ttsReady) {
                val first = vm.zhVoiceNames.firstOrNull()
                val l = if (first != null) Lang.ZH else Lang.EN
                vm.speakSample(
                    if (l == Lang.ZH) "这是中文音色试听。" else "This is an English voice sample.",
                    l
                )
            }
        },
        topTitle = "中文 · 端侧音色",
        topBody = vm.zhVoiceNames.firstOrNull()?.substringAfterLast('-') ?: "未检测到本地中文语音",
        bottomTitle = "English · 端侧音色",
        bottomBody = vm.enVoiceNames.firstOrNull()?.substringAfterLast('-') ?: "未检测到本地英文语音",
        bottomHint = vm.ttsLabel,
        centerContent = { CenterIcon(Icons.Filled.PlayArrow, Wt.OnMint, 40.dp) }
    )
}

// =====================================================================
// 板块 4 · 关于
// =====================================================================
@Composable
private fun RoundAbout(vm: AppVm, toast: (String) -> Unit) {
    val ctx = LocalContext.current
    RoundScaffold(
        activeModule = Module.ABOUT,
        contextText = "Sam1112220",
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        centerSize = 100.dp,
        centerColor = Wt.SurfaceHigh,
        topBody = "哔哩哔哩主页",
        bottomBody = "个人博客",
        onTopBandClick = {
            openUrl(ctx, "https://space.bilibili.com/3493115165935719", toast)
        },
        onBottomBandClick = {
            openUrl(ctx, "https://blog.sam1112220.xyz", toast)
        },
        bottomHint = "v0.1.0 · 完全离线",
        centerContent = {
            Image(
                painter = androidx.compose.ui.res.painterResource(R.drawable.developer_avatar),
                contentDescription = "开发者头像",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        }
    )
}

// =====================================================================
// 历史记录
// =====================================================================
@Composable
private fun RoundHistory(vm: AppVm, toast: (String) -> Unit) {
    LaunchedEffect(vm.route) { vm.reloadHistory() }
    val items = vm.historyItems
    var cursor by remember { mutableIntStateOf(0) }
    val cur = items.getOrNull(cursor.coerceAtMost((items.size - 1).coerceAtLeast(0)))
    val nxt = items.getOrNull(cursor + 1)
    RoundScaffold(
        activeModule = Module.TRANSLATE,
        contextText = "历史记录",
        showSubIndicator = true,
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        topBandStyle = if (items.isNotEmpty()) BandStyle.ACTIVE_TOP else BandStyle.IDLE,
        bottomBandStyle = if (items.size > 1) BandStyle.ACTIVE_BOTTOM else BandStyle.IDLE,
        centerSize = 88.dp,
        centerColor = Wt.Primary,
        onCenterClick = { vm.navigate(Route.TRANSLATE) },
        onTopBandClick = {
            items.getOrNull(cursor)?.let {
                vm.copyToClipboard(it.dst); toast("译文已复制")
            }
        },
        onBottomBandClick = {
            items.getOrNull(cursor + 1)?.let {
                vm.copyToClipboard(it.dst); toast("译文已复制")
            }
        },
        topTitle = cur?.let { "${Fmt.relTime(it.ts)} · ${langLabel(it.from)} → ${langLabel(it.to)}" } ?: "",
        topBody = cur?.src ?: "还没有记录",
        topBodyColor = if (cur == null) Wt.TextMuted else Wt.TextPrimary,
        bottomTitle = nxt?.let { "${Fmt.relTime(it.ts)} · ${langLabel(it.from)} → ${langLabel(it.to)}" } ?: "",
        bottomBody = nxt?.src ?: if (items.size <= 1) "翻译一次就会留在这里" else "",
        bottomBodyColor = if (nxt == null) Wt.TextMuted else Wt.TextPrimary,
        bottomHint = "本地存储 · 永不上传",
        centerContent = { CenterText("${vm.historyCount} 条", Wt.OnPrimary, T.bandTitle) }
    )
}

private fun langLabel(code: String): String =
    if (code == "ZH") "中文" else "English"

// =====================================================================
// 常用短语
// =====================================================================
@Composable
private fun RoundPhrases(vm: AppVm, toast: (String) -> Unit) {
    val cats = vm.phraseCats
    val idx = vm.phraseCatIndex.coerceIn(0, (cats.size - 1).coerceAtLeast(0))
    val cat = cats.getOrNull(idx)
    val next = cats.getOrNull(if (cats.size > 1) (idx + 1) % cats.size else -1)

    RoundScaffold(
        activeModule = Module.TRANSLATE,
        contextText = "常用短语",
        topTextDropDp = 1f * MM_TO_DP,
        showSubIndicator = true,
        onNavSegmentClick = { vm.goTab(moduleOfSlot(it)) },
        topBandStyle = BandStyle.ACTIVE_TOP,
        bottomBandStyle = BandStyle.ACTIVE_BOTTOM,
        centerSize = 88.dp,
        centerColor = Wt.Mint,
        onCenterClick = {
            cat?.items?.forEach { vm.speak(it.zh, Lang.ZH) }
        },
        onTopBandClick = { cat?.items?.firstOrNull()?.let { vm.copyToClipboard(it.en); toast("译文已复制") } },
        onBottomBandClick = { vm.phraseCatIndex = ((idx + 1) % cats.size.coerceAtLeast(1)) },
        topTitle = cat?.let { "${it.key} · ${it.items.size} 句" } ?: "",
        topBody = cat?.items?.firstOrNull()?.zh ?: "短语本为空",
        topBodyColor = if (cat == null) Wt.TextMuted else Wt.TextPrimary,
        bottomTitle = next?.let { "${it.key} · ${it.items.size} 句" } ?: "",
        bottomBody = next?.items?.firstOrNull()?.zh ?: "旋转表冠切换分类",
        bottomBodyColor = if (next == null) Wt.TextMuted else Wt.TextPrimary,
        bottomHint = "内置短语本 · 离线朗读",
        centerContent = { CenterIcon(Icons.Filled.PlayArrow, Wt.OnMint, 40.dp) }
    )
}

// =====================================================================
private fun openUrl(ctx: android.content.Context, url: String, toast: (String) -> Unit) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { toast("没有可用的浏览器") }
}

/** 外环导航段（0 上 / 1 右 / 2 下 / 3 左）→ 对应板块 */
private fun moduleOfSlot(slot: Int): Module = when (slot) {
    0 -> Module.TRANSLATE
    1 -> Module.DIALOG
    2 -> Module.SETTINGS
    else -> Module.ABOUT
}
