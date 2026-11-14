package ru.open60m.quvpn.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.open60m.quvpn.api.ApiClient
import ru.open60m.quvpn.model.Server
import ru.open60m.quvpn.model.SessionResponse
import ru.open60m.quvpn.model.VpnState
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SharedPreferences =
        app.getSharedPreferences("quvpn_prefs", Context.MODE_PRIVATE)

    private val api = ApiClient()

    // ---- UI State ----
    private val _vpnState = MutableStateFlow<VpnState>(VpnState.Off)
    val vpnState: StateFlow<VpnState> = _vpnState

    private val _selectedServer = MutableStateFlow("de")
    val selectedServer: StateFlow<String> = _selectedServer

    private val _premium = MutableStateFlow(false)
    val premium: StateFlow<Boolean> = _premium

    // Текущая сессия (для disconnect)
    private var currentSession: SessionResponse? = null

    // ---- Серверы (как в background.js SERVERS) ----
    val servers = listOf(
        Server("de", "Германия", "DE", "🇩🇪"),
        Server("nl", "Нидерланды", "NL", "🇳🇱"),
        Server("ru", "Россия", "RU", "🇷🇺", premiumOnly = true)
    )

    // ---- Install ID (аналог background.js getOrCreateInstallId) ----
    fun getOrCreateInstallId(): String {
        val key = "install_id"
        return prefs.getString(key, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(key, it).apply()
        }
    }

    // ---- Выбор сервера ----
    fun selectServer(key: String) {
        if (_vpnState.value is VpnState.Connected) {
            // Переключаем на лету
            viewModelScope.launch {
                doSwitchServer(key)
            }
        } else {
            _selectedServer.value = key
        }
    }

    // ---- Подключение ----
    fun connect() {
        if (_vpnState.value !is VpnState.Off && _vpnState.value !is VpnState.Error) return
        viewModelScope.launch(Dispatchers.IO) {
            doEnable(_selectedServer.value)
        }
    }

    // ---- Отключение ----
    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            doDisable()
        }
    }

    // ---- Вызывается из Activity когда VpnService сообщил CONNECTED ----
    fun onServiceConnected() {
        // State уже CONNECTING — ничего дополнительно не делаем
    }

    // ---- Вызывается из Activity когда VpnService сообщил DISCONNECTED ----
    fun onServiceDisconnected() {
        _vpnState.value = VpnState.Off
    }

    // ---- Вызывается из Activity когда VpnService сообщил ERROR ----
    fun onServiceError(msg: String) {
        _vpnState.value = VpnState.Error(msg)
    }

    // ======== Внутренняя логика ========

    private suspend fun doEnable(serverKey: String) {
        _vpnState.value = VpnState.Connecting
        try {
            val installId = getOrCreateInstallId()
            val server = servers.find { it.key == serverKey } ?: servers[0]

            // Premium проверяем в фоне, не блокируем подключение
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val p = api.premiumStatus(installId)
                    _premium.value = p.premium
                    prefs.edit().putBoolean("premium", p.premium).apply()
                } catch (_: Exception) {
                    _premium.value = prefs.getBoolean("premium", false)
                }
            }

            val session = api.createSession(installId, server.country, serverKey)
            currentSession = session

            // Сохраняем в prefs для восстановления
            prefs.edit()
                .putString("session_id", session.session_id)
                .putString("access_token", session.access_token)
                .putString("proxy_host", session.proxy.host)
                .putInt("proxy_port", session.proxy.port)
                .putString("server_key", serverKey)
                .apply()

            _selectedServer.value = serverKey

            // Возвращаем данные в UI для запуска сервиса
            _vpnState.value = VpnState.Connected(
                serverKey = serverKey,
                nodeId = session.node?.id,
                premium = _premium.value
            )

        } catch (e: Exception) {
            val msg = when {
                e.message?.contains("no_nodes_available") == true -> "Нет доступных серверов"
                e.message?.contains("no_ports_available") == true -> "Сервер перегружен, попробуйте другую страну"
                e.message?.contains("Failed to connect") == true  -> "Нет соединения с интернетом"
                else -> "Ошибка подключения: ${e.message}"
            }
            _vpnState.value = VpnState.Error(msg)
        }
    }

    private suspend fun doDisable() {
        _vpnState.value = VpnState.Disconnecting
        // Уничтожаем сессию на сервере (fire-and-forget)
        currentSession?.access_token?.let { token ->
            try { api.destroySession(token) } catch (_: Exception) {}
        }
        currentSession = null
        prefs.edit()
            .remove("session_id").remove("access_token")
            .remove("proxy_host").remove("proxy_port")
            .apply()
        _vpnState.value = VpnState.Off
    }

    private suspend fun doSwitchServer(newKey: String) {
        val old = currentSession
        doDisable()
        _selectedServer.value = newKey
        doEnable(newKey)
    }

    // ---- Восстановление после перезапуска приложения ----
    fun restoreState() {
        val host = prefs.getString("proxy_host", null)
        val port = prefs.getInt("proxy_port", 0)
        val key  = prefs.getString("server_key", "de") ?: "de"
        _selectedServer.value = key
        _premium.value = prefs.getBoolean("premium", false)
        // Если был активен — считаем выключенным (пользователь открыл заново)
        if (host != null && port > 0) {
            _vpnState.value = VpnState.Off
        }
    }

    // Данные сессии для запуска сервиса
    fun getSessionData(): Triple<String, Int, String>? {
        val host  = prefs.getString("proxy_host", null) ?: return null
        val port  = prefs.getInt("proxy_port", 0).takeIf { it > 0 } ?: return null
        val token = prefs.getString("access_token", "") ?: ""
        return Triple(host, port, token)
    }
}
