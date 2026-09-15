plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名凭据从本地 keystore.properties 读取，**不写死在仓库里**（避免开源泄露签名密钥）。
// 该文件已由 .gitignore 排除，仓库只提供 keystore.properties.example 作为模板。
// 未配置时 signingMap 为空，buildTypes 会自动回落到 Android 默认 debug keystore，
// 保证别人 clone 下来无需任何配置即可构建。
//
// 注意：必须写在 android{} 之外。块内的 `java` 会被 AGP 的作用域遮蔽，
// 导致 java.util.Properties 解析失败（实测报 Unresolved reference: util）。
val signingMap: Map<String, String> = run {
    val f = rootProject.file("keystore.properties")
    if (!f.exists()) return@run emptyMap()
    f.readLines()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
        .mapNotNull { line ->
            val i = line.indexOf('=')
            if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim() else null
        }
        .toMap()
}

android {
    namespace = "com.sam1112220.watchtranslate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sam1112220.watchtranslate"
        minSdk = 30
        targetSdk = 34
        versionCode = 34
        versionName = "1.0.30"
        resourceConfigurations += listOf("zh", "en")

        ndk {
            // 只保留手表实际会用到的两种 ABI。x86_64 仅用于模拟器调试，
            // 而 ORT 的 x86_64 原生库单个就有 33 MB，去掉以压缩体积。
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // sherpa-onnx 官方只提供预编译 .so（无 AAR / Maven 坐标），
        // 因此放在 app/src/main/jniLibs/<abi>/ 由 AGP 自动打包。
        sourceSets.getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    androidResources {
        // 神经模型与词表不做二次压缩：显著缩短构建与首启解压耗时
        // sherpa-onnx 的 zipformer 模型同样以 .onnx 命中此规则
        noCompress += listOf("onnx", "tsv", "spm", "model", "txt")
    }

    signingConfigs {
        create("release") {
            val ksFile = signingMap["storeFile"]?.let { rootProject.file(it) }
            if (ksFile?.exists() == true) {
                storeFile = ksFile
                storePassword = signingMap["storePassword"]
                keyAlias = signingMap["keyAlias"]
                keyPassword = signingMap["keyPassword"]
            }
            // 未配置时不设置任何签名，交给下面的 buildTypes 兜底
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release").takeIf {
                it.storeFile != null
            }
        }
        debug {
            signingConfig = signingConfigs.getByName("release").takeIf {
                it.storeFile != null
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module"
            )
        }
        jniLibs {
            // ---- 同名 libonnxruntime.so 冲突的最终解法（v1.0.22 定案）----
            // 冲突双方（SONAME 都是 libonnxruntime.so，APK 内只能留一份）：
            //   A) onnxruntime-android AAR 自带 1.26.0（含 libonnxruntime4j_jni.so）
            //   B) sherpa-onnx 官方预编译包自带 1.28.2
            //
            // 【根因】ELF 符号版本严格校验：
            //     libsherpa-onnx-jni.so    原本需要 OrtGetApiBase@VERS_1.28.2
            //     libonnxruntime4j_jni.so  原本需要 OrtGetApiBase@VERS_1.26.0
            // 一份 so 只能提供单一 VERDEF，必然有一方 dlopen 报 cannot locate symbol。
            //
            // 【方向选择很关键 —— 曾经选错过，务必记住】
            // 一度把 libonnxruntime4j_jni.so 从 1.26.0 改写到 1.28.2 去迁就 sherpa，
            // 结果三个库都能加载、看似修好了，进对话页却 SIGABRT 崩溃：
            //   Abort message: 'using a recursive mutex in pthread_once'
            //   （ORT Java binding 与 ORT 内部状态耦合深，ABI 错配必然炸）
            //
            // 正确方向：**改"符号耦合浅"的那一方**。
            //   - libonnxruntime4j_jni.so 需要 3 个 Ort* 符号（含 AppendExecutionProvider
            //     _CPU / _Nnapi），深入 ORT 内部状态 → 绝不能改它。
            //   - sherpa 侧只需要 1 个 OrtGetApiBase（纯稳定 C API 入口）
            //     → 改它的版本需求到 1.26.0 才是低风险做法。
            //
            // 因此最终方案：统一用 AAR 的 ORT 1.26.0 + 其原生 4j_jni，
            // 再用 tools/patch_symbol_version.py 把 sherpa 侧的 VERS_1.28.2
            // 原地改写为 VERS_1.26.0（等长 11 字节，含 vna_hash，
            // 详见 tools/patch_symbol_version.py 的说明）。
            //
            // ⚠️【v1.0.23 修复：必须给"整条 sherpa 链"打补丁，不是只打 jni】
            // 加载顺序为 jni -> cxx-api -> c-api，三者的 dynstr 里各自带一份版本需求。
            // v1.0.22 只改了 libsherpa-onnx-jni.so，漏了 libsherpa-onnx-c-api.so，
            // 结果 c-api 仍需求 VERS_1.28.2，加载时照样 cannot locate symbol。
            // 需要改的两个库（两个 ABI 都要）：
            //     libsherpa-onnx-jni.so
            //     libsherpa-onnx-c-api.so      ← v1.0.22 漏掉的这个
            // 改完务必用 .workbuddy/tmp/scan_residual.py 复核「0 处残留」。
            //
            // pickFirsts：确保最终 APK 里的 libonnxruntime.so 来源唯一、可控。
            pickFirsts += listOf(
                "lib/armeabi-v7a/libonnxruntime.so",
                "lib/arm64-v8a/libonnxruntime.so",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // 端侧神经网络推理运行时，完全离线。
    // 必须 >= 1.25：1.22 在 armeabi-v7a（32 位 ARM）上存在 raw_data 未对齐读取缺陷，
    // 创建 ONNX 会话时 SIGBUS(BUS_ADRALN) 原生崩溃（上游修复 PR #27312，2026-02 合并）。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")

    // ML Kit 离线翻译（unbundled 版，不依赖 GMS），用于「快速档」。
    // 语言包首次联网下载一次（约 44MB，中英双向共用），之后完全在设备端推理。
    implementation("com.google.mlkit:translate:17.0.2")
}
