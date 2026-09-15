package com.sam1112220.watchtranslate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sam1112220.watchtranslate.ui.DiagOverlay
import com.sam1112220.watchtranslate.ui.FullTextOverlay
import com.sam1112220.watchtranslate.ui.ModelLoadingScreen
import com.sam1112220.watchtranslate.ui.rect.RectApp
import com.sam1112220.watchtranslate.ui.round.RoundApp
import com.sam1112220.watchtranslate.ui.theme.Wt

class MainActivity : ComponentActivity() {

    private val micPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            val vm: AppVm = viewModel()

            LaunchedEffect(Unit) {
                vm.initSpeech { }
                vm.refreshStats()
                vm.reloadHistory()
                vm.startModelLoad()
            }

            BackHandler(enabled = vm.route != Route.TRANSLATE) { vm.back() }

            Box(Modifier.fillMaxSize().background(Wt.Bg)) {
                val report = vm.selfTestReport
                val full = vm.fullText
                when {
                    report != null -> DiagOverlay(report) { vm.closeSelfTest() }
                    // 点译文 → 全屏可滚动查看（长译文不再显示不全）；圆形屏缩进避免 ✕ 被圆边裁掉
                    full != null -> FullTextOverlay(vm.fullTitle, full, round = vm.isRound) { vm.closeFull() }
                    // 首次启动：展开内置神经模型期间独占界面
                    vm.loadPhase == "EXTRACT" -> ModelLoadingScreen(
                        phase = vm.loadPhase,
                        done = vm.loadDone,
                        total = vm.loadTotal,
                        note = vm.loadNote,
                        round = vm.isRound
                    )
                    vm.isRound -> RoundApp(vm)
                    else -> RectApp(vm)
                }
            }
        }
    }
}
