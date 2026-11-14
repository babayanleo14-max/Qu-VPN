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
import ru.open60m.quvpn.R
import ru.open60m.quvpn.api.ApiClient
import ru.open60m.quvpn.model.Server
import ru.open60m.quvpn.model.SessionResponse
import ru.open60m.quvpn.model.VpnState
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: SharedPreferences =
        app.getSharedPreferences("quvpn_prefs", Context.MODE_PRIVATE)

    private val api = ApiClient()

    private val _vpnState = MutableStateFlow<VpnState>(VpnState.Off)
    val vpnState: StateFlow<VpnState> = _vpnState

    private val _selectedServer = MutableStateFlow("de")
    val selectedServer: StateFlow<String> = _selectedServer

    private val _premium = MutableStateFlow(false)
    val premium: StateFlow<Boolean> = _premium

    private var currentSession: SessionResponse? = null

    // Чтобы сервис не перезапускался при каждом возврате на экран
    @Volatile var tunnelStarted = false

    val servers = listOf(
        Server("de", "Германия",   "Франкфурт",              "DE", R.drawable.flag_de),
        Server("nl", "Нидерланды", "Амстердам",              "NL", R.drawable.flag_nl),
        Server("ru", "Россия",     "YouTube · TikTok без рекламы", "RU", R.drawable.flag_ru, premiumOnly = true)
    )

    fun getOrCreateInstallId(): String {
        val key = "install_id"
        return prefs.getString(key, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(key, it).apply()
        }
    }

    fun selectServer(key: String) {
        if (_vpnState.value is VpnState.Connected) {
            viewModelScope.launch { doSwitchServer(key) }
        } else {
            _selectedServer.value = key
        }
    }

    fun connect() {
        if (_vpnState.value !is VpnState.Off && _vpnState.value !is VpnState.Error) return
        viewModelScope.launch(Dispatchers.IO) { doEnable(_selectedServer.value) }
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) { doDisable() }
    }

    fun onServiceConnected() {}

    fun onServiceDisconnected() {
        tunnelStarted = false
        _vpnState.value = VpnState.Off
    }

    fun onServiceError(msg: String) {
        tunnelStarted = false
        _vpnState.value = VpnState.Error(msg)
    }

    private suspend fun doEnable(serverKey: String) {
        _vpnState.value = VpnState.Connecting
        try {
            val installId = getOrCreateInstallId()
            val server = servers.find { it.key == serverKey } ?: servers[0]

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

            prefs.edit()
                .putString("session_id", session.session_id)
                .putString("access_token", session.access_token)
                .putString("proxy_host", session.proxy.host)
                .putInt("proxy_port", session.proxy.port)
                .putString("server_key", serverKey)
                .apply()

            _selectedServer.value = serverKey
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
        tunnelStarted = false
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
        doDisable()
        _selectedServer.value = newKey
        doEnable(newKey)
    }

    fun restoreState() {
        val key = prefs.getString("server_key", "de") ?: "de"
        _selectedServer.value = key
        _premium.value = prefs.getBoolean("premium", false)
        _vpnState.value = VpnState.Off
    }

    fun getSessionData(): Triple<String, Int, String>? {
        val host  = prefs.getString("proxy_host", null) ?: return null
        val port  = prefs.getInt("proxy_port", 0).takeIf { it > 0 } ?: return null
        val token = prefs.getString("access_token", "") ?: ""
        return Triple(host, port, token)
    }
}
