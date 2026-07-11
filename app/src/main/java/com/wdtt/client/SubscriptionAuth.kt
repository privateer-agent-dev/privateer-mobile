package com.wdtt.client

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Privateer: on-connect авторизация. Перед подключением перечитываем подписку профиля
// и разрешаем коннект, только если сервер НЕ вернул явный отзыв.
// Философия: fail-open — при любой сетевой ошибке/недоступности разрешаем (не наказываем
// платящих за плохую связь; во время шатдауна наш сервер и так недоступен). Блокируем
// ТОЛЬКО при однозначном сигнале "active": false или пустом profiles[] на успешном ответе.
object SubscriptionAuth {

    suspend fun isAllowed(context: Context, profileId: String): Boolean = withContext(Dispatchers.IO) {
        if (profileId.isEmpty()) return@withContext true
        try {
            val store = ProfilesStore(context)
            val profile = store.getProfileOnce(profileId) ?: return@withContext true
            if (profile.groupId.isEmpty()) return@withContext true

            val sub = store.subscriptions.first()
                .firstOrNull { it.groupId.isNotEmpty() && it.groupId == profile.groupId }
                ?: return@withContext true // профиль не из подписки — проверить нечем, разрешаем

            var url = sub.url.trim()
            if (!url.startsWith("http", ignoreCase = true)) return@withContext true
            // наш эндпоинт отдаёт JSON только с ?format=json
            if (url.contains("/sub/mobile/") && !url.contains("format=json")) {
                url += if (url.contains("?")) "&format=json" else "?format=json"
            }

            val deviceId = SettingsStore(context).getOrCreateDeviceId()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Privateer-Mobile/1.0")
                setRequestProperty("X-Device-Id", deviceId)
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@withContext true // недоступно/ошибка — fail-open
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            conn.disconnect()

            val json = try {
                JSONObject(body.trim())
            } catch (_: Exception) {
                return@withContext true // не JSON (deep-link/текст) — проверить нельзя, разрешаем
            }
            if (json.has("active")) {
                return@withContext json.optBoolean("active", true)
            }
            val arr = json.optJSONArray("profiles")
            return@withContext arr != null && arr.length() > 0
        } catch (_: Exception) {
            true // fail-open
        }
    }
}
