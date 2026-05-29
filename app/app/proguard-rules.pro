# ── UniFFI 生成代码 ──
# UniFFI 生成的 Kotlin 绑定通过 JNA 调用 Rust，所有类名和方法名不能被混淆
-keep class uniffi.** { *; }

# ── JNA ──
# JNA 通过反射调用本地方法，必须 keep
-keep class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# ── Rust JNI 桥接 ──
# Rust 编译的 .so 通过 JNI 被调用，保持所有 native 方法
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── 序列化 / JSON ──
# Rust 层传递的数据类（Record），Kotlin 侧通过构造函数反序列化
-keepclassmembers class * {
    <init>(...);
}

# ── Compose / ViewModel ──
# ViewModel Factory 通过反射创建实例
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.ViewModelProvider.Factory {
    *;
}

# ── OkHttp ──
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ── Android 组件 ──
# BroadcastReceiver / Service 通过系统反射实例化
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.app.Service { *; }
-keep class com.echonion.nion.** { *; }

# ── WorkManager ──
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
