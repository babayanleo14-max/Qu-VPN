package ru.open60m.quvpn.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import ru.open60m.quvpn.model.PremiumStatus
import ru.open60m.quvpn.model.SessionResponse
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ApiClient {

    companion object {
        private const val API_BASE     = "https://api.open60m.ru"
        private const val API_FALLBACK = "https://api2.open60m.ru"
        private val JSON_MT = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    Dns.SYSTEM.lookup(hostname)
                } catch (e: Exception) {
                    // Повторная попытка резолва; публичные DNS без доп. библиотеки не задать
                    InetAddress.getAllByName(hostname).toList()
                }
            }
        })
        .build()

    // ---- session/create ----
    suspend fun createSession(
        installId: String,
        countryCode: String,
        serverKey: String
    ): SessionResponse {
        val body = JsonObject().apply {
            addProperty("install_id", installId)
            addProperty("country", countryCode)
            addProperty("server_key", serverKey)
            addProperty("supports_wait", false)
        }
        val json = apiPost("/session/create", body.toString())
        return gson.fromJson(json, SessionResponse::class.java)
    }

    // ---- session/destroy ----
    suspend fun destroySession(accessToken: String) {
        try {
            apiPost("/session/destroy", "{}", authToken = accessToken)
        } catch (_: Exception) {}
    }

    // ---- premium/status ----
    suspend fun premiumStatus(installId: String): PremiumStatus {
        val json = apiGet("/premium/status?install_id=$installId")
        return gson.fromJson(json, PremiumStatus::class.java)
    }

    // ---- базовые HTTP ----
    private fun apiGet(endpoint: String, authToken: String? = null): String {
        val bases = listOf(API_BASE, API_FALLBACK)
        var lastEx: Exception = RuntimeException("No endpoints")
        for (base in bases) {
            try {
                val req = Request.Builder()
                    .url("$base$endpoint")
                    .apply { if (authToken != null) header("Authorization", "Bearer $authToken") }
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
                return body
            } catch (e: Exception) {
                lastEx = e
            }
        }
        throw lastEx
    }

    private fun apiPost(endpoint: String, bodyJson: String, authToken: String? = null): String {
        val bases = listOf(API_BASE, API_FALLBACK)
        var lastEx: Exception = RuntimeException("No endpoints")
        for (base in bases) {
            try {
                val reqBody = bodyJson.toRequestBody(JSON_MT)
                val req = Request.Builder()
                    .url("$base$endpoint")
                    .post(reqBody)
                    .apply { if (authToken != null) header("Authorization", "Bearer $authToken") }
                    .build()
                val resp = client.newCall(req).execute()
                val body = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: $body")
                return body
            } catch (e: Exception) {
                lastEx = e
            }
        }
        throw lastEx
    }
}
