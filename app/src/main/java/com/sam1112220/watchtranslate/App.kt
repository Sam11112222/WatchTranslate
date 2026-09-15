package com.sam1112220.watchtranslate

import android.app.Application
import android.util.Log

/**
 * 应用入口：在进程启动时按**依赖顺序**显式加载全部原生库。
 *
 * 背景（真机踩坑记录）：
 * 本工程同时用到两个都依赖 ONNX Runtime 的原生库——
 *   1. sherpa-onnx 的 libsherpa-onnx-jni.so（语音识别）
 *   2. ai.onnxruntime 的 libonnxruntime4j_jni.so（Marian 高精度翻译）
 * 两者 DT_NEEDED 里都写着同一个名字 `libonnxruntime.so`，而 APK 里该文件只有一份。
 *
 * Android 的 System.loadLibrary 默认以 **RTLD_LOCAL** 打开 .so：依赖库虽然会被
 * 递归加载，但只进入**调用方自己的**本地符号作用域。于是谁先 dlopen，
 * `libonnxruntime.so` 就先挂到谁名下；后加载的那个消费者在自己的依赖闭包里
 * 找不到 `OrtGetApiBase`，直接抛：
 *
 *   UnsatisfiedLinkError: dlopen failed:
 *     cannot locate symbol "OrtGetApiBase" referenced by .../libsherpa-onnx-jni.so
 *
 * 实测表现就是这个错会「左右横跳」：留 sherpa 版 ORT 时挂在 libonnxruntime4j_jni.so，
 * 换成 AAR 版 ORT 时又挂到 libsherpa-onnx-jni.so——因为两边都在抢同一份符号。
 *
 * 解法：在 Application.onCreate 里先 `System.loadLibrary("onnxruntime")`，
 * 再显式加载两个消费者。Application 的加载发生在任何业务类之前。
 *
 * 注意：每一步都打日志（成功也打），否则线上只看得到失败、看不到"到底哪一步成功了"。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 顺序很重要：共享 ORT → sherpa → onnxruntime4j
        // 每个都用 runCatching 独立兜底，避免一个失败拖垮后续（对应功能自己会报错）
        load("onnxruntime")
        load("sherpa-onnx-jni")
        load("onnxruntime4j_jni")
    }

    private fun load(name: String) {
        runCatching { System.loadLibrary(name) }
            .onSuccess { Log.i(TAG, "loadLibrary OK: lib$name.so") }
            .onFailure { Log.e(TAG, "loadLibrary FAIL: lib$name.so", it) }
    }

    private companion object {
        const val TAG = "App"
    }
}
