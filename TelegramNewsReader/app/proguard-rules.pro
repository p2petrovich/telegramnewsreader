# ============================================================
#  ProGuard / R8 rules — Telegram News Reader
# ============================================================

# ---- Общие настройки ----
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose
# Сохраняем атрибуты, нужные для корректной работы рефлексии/исключений/аннотаций
-keepattributes Signature,InnerClasses,EnclosingMethod,Exceptions,*Annotation*

# ============================================================
#  TDLib (нативная библиотека — вызывается через JNI/рефлексию)
# ============================================================
# TdApi содержит сотни классов, на которые ссылается нативный код TDLib.
# Их НЕЛЬЗЯ переименовывать или удалять.
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**

# ============================================================
#  FFmpeg-kit (com.arthenica) — УДАЛЕНО
# ============================================================
# [FFmpeg removed] FFmpeg-kit полностью убран из проекта (см. build.gradle).
# Раньше здесь были keep-правила для нативных JNI-вызовов:
#   -keep class com.arthenica.ffmpegkit.** { *; }
#   -keep class com.arthenica.smartexception.** { *; }
#   -keepclassmembers class com.arthenica.** { *; }
#   -dontwarn com.arthenica.**
# Теперь они не нужны: аудио-обработка (WAV PCM) ведётся на чистом Kotlin
# в utils/AudioUtils и utils/PcmResampler, без нативных библиотек.

# ============================================================
#  Room
# ============================================================
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# ============================================================
#  Модели приложения (сериализуются Gson + используются в Room/Parcelable)
# ============================================================
# Сохраняем имена полей моделей, чтобы Gson корректно (де)сериализовал JSON backup.
-keep class com.p2petrovich.telegramnewsreader.models.** { *; }
-keepclassmembers class com.p2petrovich.telegramnewsreader.models.** { *; }

# BuildConfig читается через рефлексию в некоторых местах — на всякий случай
-keep class com.p2petrovich.telegramnewsreader.BuildConfig { *; }
-keep class com.p2petrovich.telegramnewsreader.ApiConfig { *; }

# ============================================================
#  Gson
# ============================================================
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn sun.misc.**

# ============================================================
#  OkHttp / Okio (используются OpenRouter и Edge TTS WebSocket)
# ============================================================
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ============================================================
#  Kotlin Coroutines
# ============================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ============================================================
#  Coil (загрузка изображений)
# ============================================================
-dontwarn coil.**

# ============================================================
#  ThreeTenABP (java.time backport)
# ============================================================
-dontwarn org.threeten.bp.**
-keep class org.threeten.bp.** { *; }

# ============================================================
#  Parcelable / Kotlin Parcelize
# ============================================================
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ============================================================
#  Сервисы и компоненты, объявленные в манифесте (на всякий случай)
# ============================================================
-keep class com.p2petrovich.telegramnewsreader.services.** { *; }

# ============================================================
#  P0-задача: вырезать debug-логи из release-сборки
#  (Log.d / Log.v / Log.i не имеют побочных эффектов → R8 удалит вызовы)
# ============================================================
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static boolean isLoggable(java.lang.String, int);
}
