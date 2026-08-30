# ═══════════════════════════════════════════════════════════
#  猫猫互联 ProGuard / R8 规则
# ═══════════════════════════════════════════════════════════

# ── 保留行号，便于崩溃堆栈定位 ──────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── 保留注解（友盟等SDK依赖）────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ══ 友盟 SDK ──────────────────────────────────────────
-keep class com.umeng.** { *; }
-keepclassmembers class * {
    public <init>(com.umeng.**);
}
-keep class com.umeng.commonsdk.** { *; }
-keep class com.umeng.analytics.** { *; }
-keep class com.umeng.umsdk.** { *; }

# 友盟 SDK 内部使用的类（反射调用）
-keep class u.** { *; }
-keep class com.um.** { *; }
-dontwarn org.android.spdy.**
-dontwarn org.android.spdy.**
-keep class org.android.spdy.** { *; }
-dontwarn u.**

# ══ AndroidX ─────────────────────────────────────────
-keep class androidx.** { *; }
-dontwarn androidx.**

# ══ Kotlin ───────────────────────────────────────────
-keep class kotlin.** { *; }
-dontwarn kotlin.**
-keepclassmembers class kotlin.Metadata {
    public <init>();
}

# ══ 本项目 manifest 引用的类 ──────────────────────────
-keep class com.maomao.hulian.HulianApplication { *; }
-keep class com.maomao.hulian.MainActivity { *; }
-keep class com.maomao.hulian.MainService { *; }
-keep class com.maomao.hulian.BootReceiver { *; }

# ══ 签名校验类（不可混淆，含硬编码哈希）──────────────
-keep class com.maomao.hulian.SignatureVerifier { *; }

# ══ 使用反射的类 ─────────────────────────────────────
# BtWifiManager / HotspotManager 通过反射调用 WifiManager 隐藏方法
# 这些反射目标是 Android 框架类，不会被 R8 混淆，无需额外 keep
# 但我们的管理类本身需要保留（被 MainService 引用）
-keep class com.maomao.hulian.BtWifiManager { *; }
-keep class com.maomao.hulian.HotspotManager { *; }
-keep class com.maomao.hulian.HotspotMonitor { *; }
-keep class com.maomao.hulian.ConnectionStateMachine { *; }
-keep class com.maomao.hulian.ConnectionStateMachine$* { *; }
-keep class com.maomao.hulian.LicenseManager { *; }
-keep class com.maomao.hulian.ShellExecutor { *; }
-keep class com.maomao.hulian.ShellExecutor$* { *; }
-keep class com.maomao.hulian.FileLogger { *; }
-keep class com.maomao.hulian.PrefsHelper { *; }
-keep class com.maomao.hulian.AppMonitor { *; }
-keep class com.maomao.hulian.AppLauncher { *; }
-keep class com.maomao.hulian.FloatingWindow { *; }
-keep class com.maomao.hulian.AppState { *; }
-keep class com.maomao.hulian.AppState$* { *; }

# ══ 枚举类（R8 需要保留枚举方法）─────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ══ 序列化 / 数据类 ──────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# ══ 通用安全规则 ─────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
