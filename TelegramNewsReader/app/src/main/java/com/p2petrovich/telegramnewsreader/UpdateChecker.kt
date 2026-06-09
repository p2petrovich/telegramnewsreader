package com.p2petrovich.telegramnewsreader.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import com.p2petrovich.telegramnewsreader.BuildConfig
import com.p2petrovich.telegramnewsreader.R
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Проверяет обновления на GitHub Releases (ветка main).
 *
 * Схема версий проекта:
 *   versionName = "3.0.<commitCount>",  versionCode = <commitCount>
 *   тег релиза  = "v3.0.<commitCount>"
 *   → берём substringAfterLast(".") из тега и сравниваем с BuildConfig.VERSION_CODE.
 *
 * Kill-switch: строка "min_version_code: NNN" в теле релиза (case-insensitive).
 *   Если currentCode < minCode — диалог не закрывается, кнопки «Позже» нет.
 *
 * Throttle: не чаще раза в 24 ч (SharedPreferences).
 *   check(activity, force = true) — игнорировать throttle (из меню «Проверить обновления»).
 *
 * Ассет: первый .apk, содержащий "arm64" в имени файла.
 *   Если ассет не найден — открывается html_url (страница релиза).
 */
object UpdateChecker {

    private const val GITHUB_API =
        "https://api.github.com/repos/p2petrovich/telegramnewsreader/releases/latest"

    private const val PREFS_NAME     = "update_checker_prefs"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val INTERVAL_MS    = 24 * 60 * 60 * 1000L   // 24 часа

    // Отдельный клиент с короткими таймаутами — не мешает OkHttp из AiProcessor/TelegramClient
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ── публичный API ─────────────────────────────────────────────────────────

    /**
     * Запустить проверку обновления.
     * Вызывать из MainActivity.onCreate() после прохождения авторизации.
     *
     * @param force true — игнорировать throttle (пункт меню «Проверить обновления»)
     */
    fun check(activity: Activity, force: Boolean = false) {
        if (!force && !isDue(activity)) return
        fetch(activity)
    }

    // ── throttle ──────────────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun isDue(context: Context): Boolean =
        System.currentTimeMillis() - prefs(context).getLong(KEY_LAST_CHECK, 0L) >= INTERVAL_MS

    private fun markChecked(context: Context) {
        prefs(context).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    // ── сеть ──────────────────────────────────────────────────────────────────

    private fun fetch(activity: Activity) {
        val request = Request.Builder()
            .url(GITHUB_API)
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // Тихий сбой — не мешаем пользователю при отсутствии сети
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) return
                    val body = resp.body?.string() ?: return
                    markChecked(activity)
                    parseAndShow(activity, body)
                }
            }
        })
    }

    // ── парсинг ───────────────────────────────────────────────────────────────

    private fun parseAndShow(activity: Activity, json: String) {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return

        // Черновики и пре-релизы пропускаем
        if (root.optBoolean("draft", false)) return
        if (root.optBoolean("prerelease", false)) return

        val tag         = root.optString("tag_name", "")          // "v3.0.487"
        val latestCode  = versionCodeFromTag(tag) ?: return
        val currentCode = BuildConfig.VERSION_CODE

        val body     = root.optString("body", "")
        val minCode  = parseMinVersionCode(body)
        val isForced = minCode != null && currentCode < minCode
        val hasUpdate = latestCode > currentCode

        if (!hasUpdate && !isForced) return

        val assets  = root.optJSONArray("assets") ?: JSONArray()
        val apkUrl  = findArm64ApkUrl(assets) ?: root.optString("html_url", "")
        val htmlUrl = root.optString("html_url", "")

        // Показываем краткое описание релиза (первые 600 символов)
        val notes = body.take(600).ifBlank { "—" }

        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                showDialog(activity, tag, notes, apkUrl, htmlUrl, isForced)
            }
        }
    }

    /**
     * "v3.0.487" → 487
     * Берём только последнюю компоненту, чтобы не зависеть от формата мажорной части.
     * internal — доступно для unit-теста.
     */
    internal fun versionCodeFromTag(tag: String): Int? =
        tag.removePrefix("v").substringAfterLast(".").toIntOrNull()

    /**
     * Ищет в теле релиза строку вида:
     *   min_version_code: 120
     * Позволяет разработчику принудительно потребовать обновление через kill-switch.
     */
    private fun parseMinVersionCode(body: String): Int? =
        Regex("""min_version_code\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /**
     * Первый .apk-ассет, содержащий "arm64" в имени.
     * Игнорирует mapping.txt и прочие артефакты CI.
     */
    private fun findArm64ApkUrl(assets: JSONArray): String? {
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name  = asset.optString("name", "")
            if (name.endsWith(".apk", ignoreCase = true) &&
                name.contains("arm64", ignoreCase = true)
            ) {
                return asset.optString("browser_download_url")
                    .takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    // ── диалог ────────────────────────────────────────────────────────────────

    private fun showDialog(
        activity: Activity,
        tag: String,
        notes: String,
        apkUrl: String,
        htmlUrl: String,
        isForced: Boolean
    ) {
        val builder = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.update_available_title, tag))
            .setMessage(notes)
            .setPositiveButton(R.string.update_download_apk) { _, _ ->
                openUrl(activity, apkUrl)
            }

        // Кнопка «Открыть страницу релиза» только если ассет и html_url различаются
        if (apkUrl != htmlUrl) {
            builder.setNeutralButton(R.string.update_open_release) { _, _ ->
                openUrl(activity, htmlUrl)
            }
        }

        // При обычном обновлении пользователь может отложить
        if (!isForced) {
            builder.setNegativeButton(R.string.update_later, null)
        }

        val dialog = builder.create()

        // Kill-switch: диалог нельзя закрыть до установки обновления
        if (isForced) {
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
        }

        dialog.show()
    }

    private fun openUrl(context: Context, url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
