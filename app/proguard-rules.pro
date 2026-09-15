# ===== 通用 =====
-dontwarn org.jetbrains.annotations.**
-keep class kotlin.coroutines.Continuation
-dontwarn androidx.compose.**

# ===== 枚举常量名必须保留 =====
# 设置项以字符串形式持久化 ShapeMode / Lang，valueOf 依赖常量原名。
-keepclassmembers enum com.sam1112220.watchtranslate.data.ShapeMode {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
-keepclassmembers enum com.sam1112220.watchtranslate.engine.Lang {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
-keep class com.sam1112220.watchtranslate.ui.round.Module { *; }

# ===== 反射 / 序列化边界（org.json 读取的内置资源结构）=====
-keep class com.sam1112220.watchtranslate.engine.Translator$Result { *; }

# ===== ONNX Runtime：原生库按类名 / 方法签名反射构造 Java 对象 =====
# libonnxruntime4j_jni.so 会在 getInputInfo()/getOutputInfo() 里通过 JNI
# FindClass + NewObjectArray + 构造函数签名来 new NodeInfo(String, ValueInfo)。
# R8 一旦把类名或构造函数改名，就会抛
#   NoSuchMethodError: no non-static method "Lai/onnxruntime/NodeInfo;.<init>(...)V"
# 并被 ART 判为 "No pending exception expected" 直接 SIGABRT。
# 因此 ai.onnxruntime 包必须整体保留（含成员与构造函数）。
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ===== ML Kit 翻译（unbundled）：内部用反射/服务发现，整体保留 =====
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ===== sherpa-onnx：JNI 桥接层，方法与字段名被原生代码直接引用 =====
# libsherpa-onnx-jni.so 通过 JNI 回调 Kotlin 侧同名方法（external / native 一一对应），
# 且 newFromAsset / createStream 等以签名查找。R8 改名会导致
# "NoSuchMethodError" 或直接 UnsatisfiedLinkError，故整体保留。
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**
