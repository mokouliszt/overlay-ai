# ===== Overlay AI keep rules =====
# 現状 release は isMinifyEnabled=false のため未使用。
# R8 を有効化する場合に備えた保持ルール。

# アプリ自身のコードは丸ごと保持（WebView JSブリッジ等を壊さないため）
-keep class dev.mokouliszt.overlayai.** { *; }

# @JavascriptInterface のメソッドは JS から名前で呼ばれるので必須
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Tink / security-crypto（EncryptedSharedPreferences）
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# OkHttp / Okio（多くは consumer rules で足りるが警告抑制）
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**

# メタデータ（リフレクション系の保険）
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
