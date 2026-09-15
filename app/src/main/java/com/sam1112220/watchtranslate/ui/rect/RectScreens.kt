package com.sam1112220.watchtranslate.ui.rect

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.sam1112220.watchtranslate.ui.BottomBar
import com.sam1112220.watchtranslate.ui.PageTabs
import com.sam1112220.watchtranslate.ui.TextInputOverlay
import com.sam1112220.watchtranslate.ui.ValueRow
import com.sam1112220.watchtranslate.ui.theme.T
import com.sam1112220.watchtranslate.ui.theme.Wt
import java.util.Locale

@Composable
fun RectApp(vm: AppVm) {
    var editing by remember { mutableStateOf(false) }
    var keyboardForDialog by remember { mutableStateOf(false) }

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

        else -> when (vm.route) {
            Route.TRANSLATE -> RectTranslate(vm) { editing = true }
            Route.HISTORY -> RectHistory(vm)
            Route.PHRASES -> RectPhrases(vm)
            Route.DIALOG_LANG -> RectDialogLang(vm)
            Route.DIALOG_MIC -> RectDialogMic(vm, onKeyboard = { keyboardForDialog = true })
            Route.DIALOG_RESULT -> RectDialogResult(vm)
            Route.SETTINGS -> RectSettings(vm)
            Route.SOUND -> RectSound(vm)
            Route.SCREENFIT -> RectScreenFit(vm)
            Route.MODEL -> RectModel(vm)
            Route.VOICE -> RectVoice(vm)
            Route.ABOUT -> RectAbout(vm)
        }
    }
}

// =====================================================================
// 板块 1 · 文本翻译（状态 A / B）
// =====================================================================
@Composable
private fun RectTranslate(vm: AppVm, onEdit: () -> Unit) {
    val done = vm.stage == Stage.DONE
    RectScaffold(
        title = "文本翻译",
        vm = vm,
        bottom = {
            BottomBar(
                leftLabel = if (done) "复制" else "历史",
                leftColor = Wt.TextSecondary,
                onLeft = {
                    if (done) vm.copyToClipboard(vm.result) else vm.navigate(Route.HISTORY)
                },
                rightLabel = if (done) "朗读" else "翻译",
                rightColor = if (done) Wt.Mint else Wt.Primary,
                rightTextColor = if (done) Wt.OnMint else Wt.OnPrimary,
                onRight = {
                    if (done) vm.speak(vm.result, vm.toLang)
                    else { if (vm.input.isBlank()) onEdit() else vm.doTranslate() }
                }
            )
        }
    ) {
        LangChips(
            from = vm.fromLang, to = vm.toLang,
            onSwap = { vm.swapLangs() },
            onFrom = { vm.setFrom(vm.fromLang.other); vm.setTo(vm.fromLang) },
            onTo = { vm.setTo(vm.toLang.other) }
        )
        Spacer(Modifier.height(10.dp))

        CardBox(
            Modifier
                .then(if (done) Modifier.height(74.dp) else Modifier.weight(1f))
                .clickable { onEdit() },
            color = Wt.SurfaceCard
        ) {
            Text("输入", fontSize = T.caption, color = Wt.TextMuted)
            Spacer(Modifier.height(6.dp))
            Text(
                vm.input.ifBlank { "请输入要翻译的内容" },
                fontSize = T.body,
                color = if (vm.input.isBlank()) Wt.TextMuted else Wt.TextPrimary,
                maxLines = if (done) 2 else 6,
                overflow = TextOverflow.Ellipsis
            )
            if (!done) {
                Spacer(Modifier.weight(1f))
                Text(
                    "${vm.input.length} / 500　·　键盘 · 短语本",
                    fontSize = T.caption, color = Wt.TextMuted
                )
            }
        }

        if (done) {
            Spacer(Modifier.height(10.dp))
            // 点译文卡 → 全屏可滚动查看
            CardBox(
                Modifier.weight(1f).clickable { vm.openFullResult() },
                color = Wt.BandBottomActive
            ) {
                Text(
                    "${vm.toLang.label} · 端侧神经 · ${vm.resultMs} ms · 点此全屏",
                    fontSize = T.caption,
                    color = Wt.Green
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    vm.result,
                    fontSize = T.body, color = Wt.TextPrimary,
                    maxLines = 5, overflow = TextOverflow.Ellipsis
                )
            }
        } else if (vm.busy) {
            Spacer(Modifier.height(10.dp))
            StatusBadge("端侧推理中…", Wt.Primary)
        }
    }
}

// =====================================================================
// 板块 2 · 对话翻译 · 步骤 1 语言选择
// =====================================================================
@Composable
private fun RectDialogLang(vm: AppVm) {
    RectScaffold(
        title = "对话模式",
        vm = vm,
        bottom = {
            BottomBar(
                leftLabel = "返回",
                onLeft = { vm.navigate(Route.TRANSLATE) },
                rightIcon = Icons.Filled.Check,
                rightColor = Wt.Primary,
                rightTextColor = Wt.OnPrimary,
                onRight = { vm.startDialog(); vm.navigate(Route.DIALOG_MIC) }
            )
        }
    ) {
        Spacer(Modifier.height(8.dp))
        ValueRow(
            label = "我说",
            value = vm.dialogMy.label,
            valueColor = Wt.Cyan,
            container = Wt.BandTopActive,
            onClick = {
                val l = vm.dialogMy.other
                vm.applyMyLang(l); vm.applyPeerLang(l.other)
            }
        )
        Spacer(Modifier.height(12.dp))
        ValueRow(
            label = "对方说",
            value = vm.dialogPeer.label,
            valueColor = Wt.Violet,
            container = Wt.BandBottomActive,
            onClick = {
                val l = vm.dialogPeer.other
                vm.applyPeerLang(l); vm.applyMyLang(l.other)
            }
        )
        Spacer(Modifier.weight(1f))
        Text(
            "全程离线 · 自动识别说话语言",
            fontSize = T.caption, color = Wt.TextMuted
        )
        Spacer(Modifier.height(8.dp))
    }
}

// =====================================================================
// 板块 2 · 步骤 2 麦克风待机
// =====================================================================
@Composable
private fun RectDialogMic(vm: AppVm, onKeyboard: () -> Unit) {
    LaunchedEffect(vm.route) {
        if (!vm.listening && vm.speechErr == null) vm.startListening()
    }
    RectScaffold(
        title = "对话模式",
        vm = vm,
        bottom = {
            BottomBar(
                leftLabel = "重选语言",
                onLeft = { vm.navigate(Route.DIALOG_LANG) },
                rightLabel = "键盘补充",
                rightColor = Wt.SurfaceHigh,
                rightTextColor = Wt.TextPrimary,
                onRight = { onKeyboard() }
            )
        }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Wt.SurfaceCard)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("${vm.dialogMy.label} ⇄ ${vm.dialogPeer.label}", fontSize = T.chip, color = Wt.TextPrimary)
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(190.dp)
                    .clip(CircleShape)
                    .background(Wt.PrimaryDim.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(158.dp)
                        .clip(CircleShape)
                        .background(Wt.Primary)
                        .clickable {
                            if (vm.listening) vm.stopListening()
                            else if (vm.speechErr != null) onKeyboard()
                            else vm.startListening()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Mic, null, tint = Wt.OnPrimary, modifier = Modifier.size(74.dp))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            when {
                vm.listening -> vm.partial.ifBlank { "正在聆听…" }
                vm.speechErr != null -> vm.speechErr!!
                else -> "轻点麦克风开始说话"
            },
            fontSize = T.body, color = Wt.TextPrimary,
            textAlign = TextAlign.Center, maxLines = 2,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
    }
}

// =====================================================================
// 板块 2 · 步骤 3 识别结果
// =====================================================================
@Composable
private fun RectDialogResult(vm: AppVm) {
    val to = if (vm.recogFrom == vm.dialogMy) vm.dialogPeer else vm.dialogMy
    RectScaffold(
        title = "对话模式",
        vm = vm,
        bottom = {
            BottomBar(
                leftLabel = "结束对话",
                onLeft = { vm.navigate(Route.TRANSLATE) },
                rightLabel = "继续 · 换我说",
                rightColor = Wt.Mint,
                rightTextColor = Wt.OnMint,
                onRight = { vm.dialogStage = Stage.IDLE; vm.navigate(Route.DIALOG_MIC) }
            )
        }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Wt.SurfaceCard)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("${vm.dialogMy.label} ⇄ ${vm.dialogPeer.label}", fontSize = T.chip, color = Wt.TextPrimary)
        }
        Spacer(Modifier.height(10.dp))
        CardBox(color = Wt.BandTopActive) {
            Text("识别 · ${vm.recogFrom.label}", fontSize = T.caption, color = Wt.Cyan)
            Spacer(Modifier.height(6.dp))
            Text(vm.recogText, fontSize = T.body, color = Wt.TextPrimary, maxLines = 3)
        }
        Spacer(Modifier.height(10.dp))
        CardBox(Modifier.weight(1f), color = Wt.BandBottomActive) {
            Text("译文 · ${to.label}", fontSize = T.caption, color = Wt.Violet)
            Spacer(Modifier.height(6.dp))
            Text(vm.recogDst, fontSize = T.body, color = Wt.TextPrimary, maxLines = 4)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Wt.Green.copy(alpha = 0.14f))
                    .clickable { vm.speak(vm.recogDst, to) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (vm.speaking) "正在朗读 · 再听一次" else "点此朗读译文",
                    fontSize = T.body, color = Wt.Green
                )
            }
        }
    }
}

// =====================================================================
// 板块 3 · 设置
// =====================================================================
@Composable
private fun RectSettings(vm: AppVm) {
    RectScaffold(title = "设置", vm = vm, scrollable = false) {
        Spacer(Modifier.height(6.dp))
        IconRow(
            icon = Icons.Filled.VolumeUp, tint = Wt.Mint,
            title = "播放声音",
            subtitle = if (vm.autoSpeak) "对话自动朗读 · 已开启" else "对话自动朗读 · 已关闭",
            onClick = { vm.navigate(Route.SOUND) }
        )
        Spacer(Modifier.height(10.dp))
        IconRow(
            icon = Icons.Filled.Adjust, tint = Wt.Primary,
            title = "屏幕适配",
            subtitle = "当前：${if (vm.isRound) "圆形" else "长方形"} · 首次自动识别",
            onClick = { vm.navigate(Route.SCREENFIT) }
        )
        Spacer(Modifier.weight(1f))
        PageTabs(2)
        Spacer(Modifier.height(10.dp))
        IconRow(
            icon = Icons.Filled.Storage, tint = Wt.Amber,
            title = "离线模型",
            subtitle = "中英双向 · ${Fmt.sizeShort(vm.assetBytes + vm.appDataBytes)} 已安装",
            onClick = { vm.navigate(Route.MODEL) }
        )
        Spacer(Modifier.height(6.dp))
    }
}

// =====================================================================
// 板块 3 · 选项 1 播放声音
// =====================================================================
@Composable
private fun RectSound(vm: AppVm) {
    RectScaffold(
        title = "播放声音",
        vm = vm,
        bottom = {
            BottomBar(
                leftLabel = "返回设置",
                onLeft = { vm.navigate(Route.SETTINGS) },
                rightLabel = "试听",
                rightColor = Wt.Mint,
                rightTextColor = Wt.OnMint,
                onRight = { vm.speakSample("语音输出已就绪，这是试听效果。", Lang.ZH) }
            )
        }
    ) {
        Spacer(Modifier.height(6.dp))
        CardBox(color = Wt.SurfaceCard) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动朗读译文", fontSize = T.rowTitle, color = Wt.TextPrimary, modifier = Modifier.weight(1f))
                Switch(
                    checked = vm.autoSpeak,
                    onCheckedChange = { vm.applyAutoSpeak(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Wt.Bg,
                        checkedTrackColor = Wt.Primary,
                        uncheckedTrackColor = Wt.NavIdle
                    )
                )
            }
            Spacer(Modifier.height(10.dp))
            SliderRow(
                label = "音量",
                valueText = "${vm.volume}%",
                value = vm.volume.toFloat(),
                range = 0f..100f,
                onChange = { vm.applyVolume(it.toInt()) }
            )
            Spacer(Modifier.height(8.dp))
            SliderRow(
                label = "语速",
                valueText = String.format(Locale.US, "%.1f×", vm.rate),
                value = vm.rate,
                range = 0.5f..2.0f,
                onChange = { vm.applyRate(it) }
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clickable { vm.navigate(Route.VOICE) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("音色", fontSize = T.rowTitle, color = Wt.TextPrimary)
                Spacer(Modifier.weight(1f))
                Text(
                    vm.zhVoiceNames.firstOrNull()?.substringAfterLast('-')?.let { "$it · 女声" } ?: "自动选择",
                    fontSize = T.body, color = Wt.TextSecondary
                )
                Icon(
                    Icons.Filled.KeyboardArrowRight, null,
                    tint = Wt.TextMuted, modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (vm.ttsReady) "端侧语音引擎 · 全部语音包本地合成 · 离线可用" else vm.ttsLabel,
            fontSize = T.caption,
            color = if (vm.ttsReady) Wt.Green else Wt.Amber
        )
    }
}

// =====================================================================
// 板块 3 · 选项 2 屏幕适配
// =====================================================================
@Composable
private fun RectScreenFit(vm: AppVm) {
    val round = vm.shape == ShapeMode.ROUND
    RectScaffold(
        title = "屏幕适配",
        vm = vm,
        bottom = {
            BottomBar(
                leftLabel = "返回设置",
                onLeft = { vm.navigate(Route.SETTINGS) },
                rightLabel = "应用并刷新",
                rightColor = Wt.Primary,
                rightTextColor = Wt.OnPrimary,
                onRight = { vm.reapplyShape() }
            )
        }
    ) {
        Spacer(Modifier.height(6.dp))
        RadioRow(
            title = "圆形表盘", subtitle = "450 × 450 · 圆形安全区",
            selected = round, onClick = { vm.setShapeMode(ShapeMode.ROUND) }
        )
        Spacer(Modifier.height(12.dp))
        RadioRow(
            title = "长方形表盘", subtitle = "396 × 484 · 直角排版",
            selected = !round, onClick = { vm.setShapeMode(ShapeMode.RECT) }
        )
        Spacer(Modifier.height(16.dp))
        StatusBadge("自动识别结果：${if (vm.systemRound) "圆形" else "长方形"}")
        Spacer(Modifier.height(8.dp))
        Text(
            "当前屏幕 ${vm.screenSizeLabel}",
            fontSize = T.caption, color = Wt.TextMuted
        )
    }
}

// =====================================================================
// 离线模型与存储
// =====================================================================
@Composable
private fun RectModel(vm: AppVm) {
    LaunchedEffect(vm.route) { vm.refreshStats() }
    val used = vm.assetBytes + vm.appDataBytes
    RectScaffold(
        title = "离线模型",
        vm = vm,
        bottom = {
            BottomBar(
                leftLabel = "模型许可",
                onLeft = { vm.navigate(Route.ABOUT) },
                rightLabel = "重新安装",
                rightColor = Wt.Primary,
                rightTextColor = Wt.OnPrimary,
                onRight = { vm.refreshStats() }
            )
        }
    ) {
        Spacer(Modifier.height(6.dp))
        IconRow(
            icon = Icons.Filled.Storage, tint = Wt.Amber,
            title = "OPUS-MT 中英双向 · 离线翻译引擎",
            subtitle = if (vm.neuralReady) "端侧推理引擎已就绪 · ${Fmt.size(vm.assetBytes)}" else vm.loadNote,
            onClick = { vm.refreshStats() }
        )
        Spacer(Modifier.height(12.dp))
        IconRow(
            icon = Icons.Filled.GraphicEq, tint = Wt.Cyan,
            title = "已用 ${Fmt.sizeShort(used)}",
            subtitle = "可用 ${Fmt.sizeShort(vm.freeBytes)} · ONNX Runtime 端侧推理",
            onClick = { vm.refreshStats() }
        )
        Spacer(Modifier.height(12.dp))
        IconRow(
            icon = Icons.Filled.Speed, tint = Wt.Green,
            title = "翻译速度 · " + if (vm.speedTier == SpeedTier.FAST) "快速档" else "高精度档",
            subtitle = if (vm.speedTier == SpeedTier.FAST)
                "ML Kit 端侧翻译 · 点此切到高精度"
            else "Marian 本地模型，内存峰值最低 · 点此切到快速",
            onClick = {
                vm.applySpeedTier(
                    if (vm.speedTier == SpeedTier.FAST) SpeedTier.ACCURATE else SpeedTier.FAST
                )
            }
        )
        Spacer(Modifier.height(12.dp))
        IconRow(
            icon = Icons.Filled.Mic, tint = Wt.Mint,
            title = "端侧引擎自检",
            subtitle = "跑两句样例，输出张量接口、生成结果与耗时",
            onClick = { vm.runSelfTest() }
        )
        Spacer(Modifier.weight(1f))
        Text(
            vm.neuralError?.let { "推理异常：$it" }
                ?: if (vm.neuralReady) "模型随包内置并在本机推理 · 不发起任何网络请求"
                else vm.loadNote,
            fontSize = T.caption,
            color = if (vm.neuralError != null) Wt.Amber
            else if (vm.neuralReady) Wt.Green else Wt.Amber,
            maxLines = 3
        )
        Spacer(Modifier.height(6.dp))
    }
}

// =====================================================================
// 板块 3 · 选项 1 语音输出与音色
// =====================================================================
@Composable
private fun RectVoice(vm: AppVm) {
    RectScaffold(
        title = "语音输出",
        vm = vm,
        bottom = {
            BottomBar(
                leftLabel = "返回设置",
                onLeft = { vm.navigate(Route.SETTINGS) },
                rightLabel = "试听",
                rightColor = Wt.Mint,
                rightTextColor = Wt.OnMint,
                onRight = { vm.speakSample("这是中文音色试听。", Lang.ZH) }
            )
        }
    ) {
        Spacer(Modifier.height(6.dp))
        IconRow(
            icon = Icons.Filled.VolumeUp, tint = Wt.Cyan,
            title = "中文音色",
            subtitle = vm.zhVoiceNames.firstOrNull()?.substringAfterLast('-')
                ?.let { "$it · 中文" } ?: "未检测到本地中文语音",
            container = Wt.BandTopActive,
            trailingCheck = true,
            onClick = { vm.speakSample("这是中文音色试听。", Lang.ZH) }
        )
        Spacer(Modifier.height(12.dp))
        IconRow(
            icon = Icons.Filled.VolumeUp, tint = Wt.Violet,
            title = "English 音色",
            subtitle = vm.enVoiceNames.firstOrNull()?.substringAfterLast('-')
                ?.let { "$it · English" } ?: "未检测到本地英文语音",
            container = Wt.BandBottomActive,
            trailingCheck = true,
            onClick = { vm.speakSample("This is an English voice sample.", Lang.EN) }
        )
        Spacer(Modifier.weight(1f))
        IconRow(
            icon = Icons.Filled.PlayArrow, tint = Wt.Mint,
            title = "合成引擎",
            subtitle = vm.ttsLabel,
            onClick = { vm.speakSample("这是合成引擎试听。", Lang.ZH) }
        )
        Spacer(Modifier.height(8.dp))
    }
}

// =====================================================================
// 板块 4 · 关于
// =====================================================================
@Composable
private fun RectAbout(vm: AppVm) {
    val ctx = LocalContext.current
    RectScaffold(title = "关于", vm = vm) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Wt.SurfaceHigh)
            ) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.developer_avatar),
                    contentDescription = "开发者头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Sam1112220", fontSize = T.heroTitle, color = Wt.TextPrimary,
                    fontWeight = FontWeight.Bold, maxLines = 1
                )
                Spacer(Modifier.height(3.dp))
                Text("独立开发者 · 科技区 UP 主", fontSize = T.caption, color = Wt.TextSecondary)
            }
        }
        Spacer(Modifier.height(16.dp))
        IconRow(
            icon = Icons.Filled.Tv, tint = Wt.Cyan,
            title = "哔哩哔哩主页",
            subtitle = "space.bilibili.com/3493115165935719",
            onClick = { openUrl(ctx, "https://space.bilibili.com/3493115165935719") }
        )
        Spacer(Modifier.height(12.dp))
        IconRow(
            icon = Icons.Filled.Public, tint = Wt.Primary,
            title = "个人博客",
            subtitle = "blog.sam1112220.xyz",
            onClick = { openUrl(ctx, "https://blog.sam1112220.xyz") }
        )
        Spacer(Modifier.weight(1f))
        Text(
            "v0.1.0 · 完全离线 · 不收集数据",
            fontSize = T.url, color = Wt.TextMuted
        )
        Spacer(Modifier.height(8.dp))
    }
}

// =====================================================================
// 历史记录
// =====================================================================
@Composable
private fun RectHistory(vm: AppVm) {
    LaunchedEffect(vm.route) { vm.reloadHistory() }
    val items = vm.historyItems
    RectScaffold(
        title = "历史记录",
        vm = vm,
        bottom = {
            BottomBar(
                leftLabel = "清空历史",
                onLeft = { vm.clearHistory() },
                rightLabel = "全部 ${vm.historyCount} 条",
                rightColor = Wt.Primary,
                rightTextColor = Wt.OnPrimary,
                onRight = { vm.navigate(Route.TRANSLATE) }
            )
        }
    ) {
        if (items.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text("还没有翻译记录", fontSize = T.body, color = Wt.TextMuted)
        } else {
            val palette = listOf(
                Wt.BandTopActive to Wt.Cyan,
                Wt.BandBottomActive to Wt.Violet,
                Wt.SurfaceCard to Wt.TextSecondary
            )
            items.take(20).forEachIndexed { i, it ->
                val (bg, ac) = palette[i % palette.size]
                RecordCard(
                    src = it.src,
                    dst = it.dst,
                    time = Fmt.relTime(it.ts),
                    container = bg,
                    accent = ac,
                    onClick = { vm.copyToClipboard(it.dst) }
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

// =====================================================================
// 常用短语
// =====================================================================
@Composable
private fun RectPhrases(vm: AppVm) {
    val cats = vm.phraseCats
    RectScaffold(
        title = "常用短语",
        vm = vm,
        bottom = {
            BottomBar(
                leftLabel = "返回翻译",
                onLeft = { vm.navigate(Route.TRANSLATE) },
                rightLabel = "朗读全部",
                rightColor = Wt.Mint,
                rightTextColor = Wt.OnMint,
                onRight = {
                    cats.firstOrNull()?.items?.take(3)?.forEach { vm.speak(it.zh, Lang.ZH) }
                }
            )
        }
    ) {
        Spacer(Modifier.height(4.dp))
        cats.forEachIndexed { i, c ->
            val (icon, tint, bg) = when (i % 4) {
                0 -> Triple(Icons.Filled.LocationOn, Wt.Cyan, Wt.BandTopActive)
                1 -> Triple(Icons.Filled.Restaurant, Wt.Violet, Wt.BandBottomActive)
                2 -> Triple(Icons.Filled.WarningAmber, Wt.Amber, Wt.SurfaceCard)
                else -> Triple(Icons.Filled.ShoppingBag, Wt.Green, Wt.SurfaceCard)
            }
            IconRow(
                icon = icon, tint = tint,
                title = c.key,
                subtitle = "${c.items.firstOrNull()?.zh ?: ""} · 共 ${c.items.size} 句",
                container = bg,
                onClick = { c.items.forEach { vm.speak(it.zh, Lang.ZH) } }
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

// =====================================================================
private fun openUrl(ctx: android.content.Context, url: String) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
