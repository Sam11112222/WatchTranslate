# 离线翻译

一款**完全离线**的中英互译应用，为 Android 手表的小屏交互而设计。

语音识别、机器翻译、语音合成三个环节**全部在设备本地完成**——不联网、不上传、不需要账号。断网、开飞行模式、甚至拔掉 SIM/eSIM 都能正常使用。

已在 **OPPO Watch X2**（型号 `OWW251`，Android 11，`armeabi-v7a`）实机开发与验证。

---

## 关于平台

**这是一个标准 Android 应用，不依赖 Wear OS 专属能力。** 手表相关特性均以 `required="false"` 声明：

```xml
<uses-feature android:name="android.hardware.type.watch"        android:required="false" />
<uses-library  android:name="com.google.android.wearable"       android:required="false" />
```

这意味着：

- 可以装在 **Wear OS** 手表上；
- 也可以装在 **OPPO Watch / 其他 Android 手表**（ColorOS Watch 等）上；
- 甚至可以装在手机上运行调试（界面按手表小屏设计，手机上会显得很紧凑）；
- 依赖里没有任何 `androidx.wear.*` 组件，构建产物不绑定特定厂商手表生态。

界面提供**圆形屏与方形屏两套自适应布局**，运行时会根据屏幕形状自动切换。

---

## 功能

| 功能 | 说明 |
|------|------|
| **语音翻译** | 点一下麦克风开始说话，说完自动断句并出结果，无需手动停止 |
| **对话模式** | 面对面对话场景，双方语言可一键对调，说完自动进入下一句 |
| **文本翻译** | 键盘直接输入，适合嘈杂环境或想精确表达时 |
| **常用短语** | 内置分类短语本，表冠切换分类，点一下朗读 |
| **历史记录** | 本地 SQLite 留存，随时回溯 |
| **语音朗读** | 端侧合成，语速/音调可调 |

### 交互设计

针对手表做了几处专门的取舍：

- **表冠优先**：切换板块、切分类、调音量都可用表冠旋转完成，不依赖精细触控；
- **弧形文字**：圆形屏上正文沿弧带排布，最大化利用弧面空间；
- **自动断句**：靠端点检测判断"说完了"，避免手表上难以点准小按钮；
- **离线性可感知**：界面会明确显示当前引擎状态，不会静默失败——比如本地语音包缺失时会直接提示"请用键盘补充"，而不是点了没反应。

---

## 技术方案

| 环节 | 方案 | 说明 |
|------|------|------|
| 语音识别 | sherpa-onnx + Zipformer 流式模型 | 中英双语 int8 量化，边说边出字 |
| 机器翻译 | ML Kit 端侧翻译（快速档）<br>OPUS-MT + ONNX Runtime（高精度档） | 两档可切换：快速档体积小、响应快；高精度档质量更好 |
| 语音合成 | 系统 TTS 引擎 | 复用设备已安装的离线语音包 |
| 界面 | Jetpack Compose | 圆形 / 方形两套布局自适应 |

**关于"离线"的边界**：语音识别、翻译推理、语音合成本身全部离线。唯一需要联网的是 ML Kit 语言包的**首次下载**（约 44MB，中英双向共用一次），下载完成后即完全离线推理，翻译文本不会离开设备。

---

## 构建

### 环境要求

- JDK 17
- Android SDK（compileSdk 34，build-tools 34.0.0）
- Gradle 8.9（或用项目自带 wrapper）
- **不需要 NDK**：原生库以预编译 `.so` 形式放在 `app/src/main/jniLibs/`

### 准备模型（必做）

**模型文件未包含在本仓库中**。合计约 408MB，其中语音识别的 encoder 单个就有 173.5MB，超过了代码托管平台的单文件限制；同时模型体积也不适合放进版本库。

需要把它们放到 `app/src/main/assets/models/` 下，目录结构如下：

```
app/src/main/assets/models/
├── asr/
│   └── sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/
│       ├── encoder-epoch-99-avg-1.int8.onnx    (173.5 MB)
│       ├── decoder-epoch-99-avg-1.int8.onnx    ( 12.5 MB)
│       ├── joiner-epoch-99-avg-1.int8.onnx     (  3.1 MB)
│       └── tokens.txt
├── opus/
│   ├── zh-en/  {encoder,decoder}_model_quantized.onnx
│   │            + vocab.tsv, tokmeta.json, config.json
│   └── en-zh/  同上一套
└── nllb/
    ├── encoder_model_quantized.onnx
    ├── decoder_model_merged_quantized.onnx
    └── 分词器与配置（config.json / tokenizer.json / sentencepiece.bpe.model 等）
```

模型来源（都是公开模型）：

- **语音识别**：`sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`
  — HuggingFace `csukuangfj` 同名仓库，或 ModelScope 镜像 `pkufool` 同名仓库
- **翻译**：`Xenova/opus-mt-zh-en`、`Xenova/opus-mt-en-zh`、`Xenova/nllb-200-distilled-600M`
  — 取其中的 `onnx/*_quantized.onnx` 与分词器文件

> `assets/models/` 已在 `.gitignore` 中排除。
> 模型在 APK 内不做二次压缩（`build.gradle.kts` 已配置 `noCompress`），
> 首次启动时会解压到应用私有目录，之后常驻复用，不重复解压。

### 编译

```bash
./gradlew assembleRelease
```

未配置签名时会自动回落到 Android 默认 debug keystore，**clone 下来无需任何配置即可构建**。若要正式签名：

```bash
cp keystore.properties.example keystore.properties
# 填入 storeFile / storePassword / keyAlias / keyPassword
```

---

## 一处值得记录的坑：原生库符号版本冲突

`app/src/main/jniLibs/` 下同时存在两份来源不同的库：

- **ONNX Runtime**（来自 `onnxruntime-android` AAR）+ 它的 Java 绑定 `libonnxruntime4j_jni.so`
- **sherpa-onnx** 预编译包（自带一份 ONNX Runtime）

两份库的 `SONAME` **都叫 `libonnxruntime.so`**，而 APK 里只能存在一份。

更麻烦的是：`libonnxruntime.so` 会导出一个带**符号版本**的入口 `OrtGetApiBase@VERS_x.y.z`，
而 sherpa 与 ORT 的 Java 绑定是**各自按自己编译时的版本号**去查找这个符号的。
版本号对不上，加载时就会直接报：

```
cannot locate symbol "OrtGetApiBase"
```

**解法**是统一到其中一方的版本，并改写另一方的版本需求。方向选择很关键——
要改**符号耦合浅**的那一方：`libsherpa-onnx-jni.so` 只需要一个 `OrtGetApiBase`
（稳定的 C API 入口），而 `libonnxruntime4j_jni.so` 需要三个 Ort 符号、深入 ORT
内部状态与执行提供器注册，**绝不能改它**。

改写时必须**同时改两处**，缺一不可：

1. `.dynstr` 段里的版本名字符串
2. `.gnu.version_r` 中该条目的 `vna_hash`
   （链接器是先按 hash 定位版本节点的，只改字符串仍然会报找不到符号）

`tools/` 下提供了两个脚本：

- `patch_symbol_version.py` — 改某个 `.so` 的符号版本需求
- `elf_symbol_versions.py` — 查看某个 `.so` 的版本定义与需求

---

## 代码结构

```
app/src/main/java/com/sam1112220/watchtranslate/
├── App.kt / AppVm.kt          应用入口与全局状态
├── MainActivity.kt
├── data/                      偏好设置、历史库（SQLite）、短语库
├── engine/
│   ├── SpeechIn.kt            语音识别：录音 + sherpa-onnx 流式解码
│   ├── SpeechOut.kt           语音合成
│   ├── MlKitEngine.kt         快速档翻译（ML Kit）
│   ├── LangDetect.kt          中英文判别
│   └── neural/                高精度档翻译
│       ├── NeuralMt.kt        ONNX 推理
│       ├── HfTokenizer.kt     分词器
│       └── ModelStore.kt      assets → 私有目录的模型解压
└── ui/
    ├── round/                 圆形屏布局与绘制原语
    ├── rect/                  方形屏布局
    ├── Common.kt              通用组件
    ├── Rotary.kt              表冠输入
    └── theme/
```

---

## 许可

个人项目，供学习与交流使用。
