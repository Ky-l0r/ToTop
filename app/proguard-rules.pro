# To Top R8 规则（release 构建）
# 原则：保留序列化契约、Hilt 注入点、Room 生成代码、Retrofit 接口签名。

# ---------- kotlinx.serialization ----------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.totop.app.**$$serializer { *; }
-keepclassmembers class com.totop.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------- Retrofit / OkHttp ----------
-keepattributes Signature, Exceptions
-keepclasseswithmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---------- Hilt / Dagger ----------
-keep,allowobfuscation,allowshrinking @dagger.hilt.android.HiltAndroidApp class *
-dontwarn dagger.hilt.**

# ---------- Room ----------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ---------- ML Kit ----------
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ---------- 保留行号用于崩溃定位（不含用户数据） ----------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
