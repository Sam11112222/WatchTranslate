# 离线翻译 · Wear OS

一款完全离线的智能手表翻译应用，中英双向。**不依赖任何网络、不上传任何数据**：语音识别、机器翻译、语音合成全部在手表本地完成。

针对 OPPO Watch（`OWW251`，Wear OS / Android 11，armeabi-v7a）真机开发与验证。

## 功能

- **语音翻译**：说完一句自动断句并出结果，无需手动停止
- **对话模式**：面对面对话，双方语言可自由对调
- **文本翻译**：键盘输入直接翻译
- **常用短语**：内置短语本，分类浏览、一键朗读
- **历史记录**：本地留存，支持回溯
- **语音朗读**：端侧合成，可调语速与音调

## 技术方案

| 环节 | 方案 |
|------|------|
| 语音识别 | sherpa-onnx + Zipformer 流式模型（中英双语，int8） |
| 机器翻译 | ML Kit 端侧翻译（快速档）／ OPUS-MT + ONNX Runtime（高精度档） |
| 语音合成 | 系统 TTS 引擎（离线语音包） |
| 界面 | Jetpack Compose，圆形屏 / 方形屏两套自适应布局 |

## 构建

### 环境

- JDK 17
- Android SDK（compileSdk 34，build-tools 34.0.0）
- Gradle 8.9（或使用项目自带 wrapper）
- NDK 无需安装：原生库以预编译 `.so` 形式放在 `app/src/main/jniLibs/`

### 准备模型（必做）

**模型文件未包含在本仓库中**（合计约 408MB，其中语音识别 encoder 单个 173.5MB，超过代码托管平台的单文件限制）。

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
│   ├── zh-en/{encoder,decoder}_model_quantized.onnx, vocab.tsv, tokmeta.json, config.json
│   └── en-zh/{encoder,decoder}_model_quantized.onnx, vocab.tsv, tokmeta.json, config.json
└── nllb/ ...
```

各模型的公开来源：

- **ASR**：`sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20`
  （HuggingFace `csukuangfj` 同名仓库，或 ModelScope 镜像 `pkufool/` 同名仓库）
- **翻译**：`Xenova/opus-mt-zh-en`、`Xenova/opus-mt-en-zh`、`Xenova/nllb-200-distilled-600M`
  取其中的 `onnx/*_quantized.onnx` 与分词器文件

> 模型文件在 APK 内不压缩（`build.gradle.kts` 已配置 `noCompress`），
> 首次启动时会解压到应用私有目录，之后常驻复用。

### 编译

```bash
./gradlew assembleRelease
```

未配置签名时会自动回落到 debug keystore。若需正式签名：

```bash
cp keystore.properties.example keystore.properties
# 填入 storeFile / storePassword / keyAlias / keyPassword
```

## 关于原生库

`app/src/main/jniLibs/` 下的 ONNX Runtime 与 sherpa-onnx 预编译库需要**符号版本一致**：
`libonnxruntime.so` 提供 `OrtGetApiBase@VERS_x.y.z`，而 sherpa 与 ORT 的 Java 绑定
分别按自己编译时的版本号去查找该符号，版本不匹配会在加载时报
`cannot locate symbol "OrtGetApiBase"`。

本项目采用的做法是：统一使用与 Java 绑定配套的 ONNX Runtime 版本，
再用 `tools/patch_symbol_version.py` 把 sherpa 侧的版本需求原地改写为同一版本
（同时改写 `.dynstr` 字符串与 `.gnu.version_r` 的 `vna_hash`，缺一不可）。
`tools/elf_symbol_versions.py` 可用来查看某个 `.so` 的版本定义与需求。

## 目录

```
app/src/main/java/com/sam1112220/watchtranslate/
├── App.kt / AppVm.kt          应用入口与状态
├── MainActivity.kt
├── data/                      偏好设置、历史库、短语库
├── engine/
│   ├── SpeechIn.kt            语音识别（录音 + sherpa-onnx 流式解码）
│   ├── SpeechOut.kt           语音合成
│   ├── MlKitEngine.kt         快速档翻译
│   └── neural/                高精度档翻译（ONNX + 分词器）
└── ui/
    ├── round/                 圆形屏布局
    ├── rect/                  方形屏布局
    └── theme/
```

## 许可

个人项目，供学习与交流使用。
