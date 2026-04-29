# TDLib
-keep class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**

# FFmpegKit
-keep class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Coil
-dontwarn coil.**

# ThreeTenABP
-keep class org.threeten.bp.** { *; }
-dontwarn org.threeten.bp.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Application models
-keep class com.p2petrovich.telegramnewsreader.model.** { *; }
-keep class com.p2petrovich.telegramnewsreader.models.** { *; }