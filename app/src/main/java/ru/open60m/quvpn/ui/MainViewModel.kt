package ru.open60m.quvpn.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.open60m.quvpn.R
import ru.open60m.quvpn.api.ApiClient
import ru.open60m.quvpn.model.Server
import ru.open60m.quvpn.model.SessionResponse
import ru.open60m.quvpn.model.VpnState
import ru.open60m.quvpn.service.QuVpnService
import java.util.UUID

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** Лимит бесплатного сеанса — как в расширении. */
        const val FREE_LIMIT_MS = 30L * 60 * 1000
    }

    private val prefs: SharedPreferences =
        app.getSharedPreferences("quvpn_prefs", Context.MODE_PRIVATE)

    private val api = ApiClient()

    private val _vpnState = MutableStateFlow<VpnState>(VpnState.Off)
    val vpnState: StateFlow<VpnState> = _vpnState

    private val _selectedServer = MutableStateFlow("de")
    val selectedServer: StateFlow<String> = _selectedServer

    private val _premium = MutableStateFlow(false)
    val premium: StateFlow<Boolean> = _premium

    /** Идёт смена сервера внутри страны. */
    private val _switching = MutableStateFlow(false)
    val switching: StateFlow<Boolean> = _switching

    /** Сообщение под заголовком «Серверы» (результат смены сервера). */
    private val _nodeMessage = MutableStateFlow<String?>(null)
    val nodeMessage: StateFlow<String?> = _nodeMessage

    private var currentSession: SessionResponse? = null

    // Каждая новая сессия получает номер; Activity по нему понимает, что туннель надо перезапустить.
    private var sessionRevision = 0
    @Volatile var appliedRevision = -1

    private var restored = false

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
            viewModelScope.launch(Dispatchers.IO) { doSwitchServer(key) }
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

    /** Кнопка «Сменить сервер»: другой узел той же страны. */
    fun changeNode() {
        if (_vpnState.value !is VpnState.Connected || _switching.value) return
        viewModelScope.launch(Dispatchers.IO) { doChangeNode() }
    }

    fun onServiceConnected() {}

    fun onServiceDisconnected() {
        appliedRevision = -1
        _vpnState.value = VpnState.Off
    }

    fun onServiceError(msg: String) {
        appliedRevision = -1
        _vpnState.value = VpnState.Error(msg)
        // сессия на сервере больше не нужна
        viewModelScope.launch(Dispatchers.IO) { releaseSession() }
    }

    /** Сервис сам остановил туннель по истечении бесплатных 30 минут. */
    fun onFreeTimeExpired() {
        viewModelScope.launch(Dispatchers.IO) { doDisable() }
    }

    private suspend fun doEnable(serverKey: String) {
        _vpnState.value = VpnState.Connecting
        try {
            val installId = getOrCreateInstallId()
            val server = servers.find { it.key == serverKey } ?: servers[0]

            // Статус Premium нужен до подключения: от него зависит 30-минутный лимит
            try {
                val p = api.premiumStatus(installId)
                _premium.value = p.premium
                prefs.edit().putBoolean("premium", p.premium).apply()
            } catch (_: Exception) {
                _premium.value = prefs.getBoolean("premium", false)
            }

            val session = api.createSession(installId, server.country, serverKey)
            currentSession = session
            val now = System.currentTimeMillis()
            saveSession(session, serverKey, now)

            _selectedServer.value = serverKey
            _nodeMessage.value = null
            sessionRevision++
            _vpnState.value = VpnState.Connected(
                serverKey = serverKey,
                nodeId = session.node?.id,
                premium = _premium.value,
                startedAt = now,
                revision = sessionRevision
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

    private suspend fun doChangeNode() {
        val cur = _vpnState.value as? VpnState.Connected ?: return
        val oldToken = currentSession?.access_token ?: prefs.getString("access_token", null)
        val oldSessionId = currentSession?.session_id ?: prefs.getString("session_id", null)
        val oldNodeId = currentSession?.node?.id ?: prefs.getInt("node_id", -1).takeIf { it >= 0 }

        _switching.value = true
        _nodeMessage.value = "Меняем сервер…"
        try {
            val server = servers.find { it.key == cur.serverKey } ?: servers[0]
            val session = api.createSession(
                installId = getOrCreateInstallId(),
                countryCode = server.country,
                serverKey = cur.serverKey,
                excludeNodeIds = listOfNotNull(oldNodeId),
                reason = "user_switch",
                fromNodeId = oldNodeId,
                fromSessionId = oldSessionId
            )
            currentSession = session
            // startedAt сохраняем прежним: смена сервера не продлевает бесплатные 30 минут
            saveSession(session, cur.serverKey, cur.startedAt)

            sessionRevision++
            _vpnState.value = cur.copy(nodeId = session.node?.id, revision = sessionRevision)

            oldToken?.let { api.destroySession(it) }
            showNodeMessage("Готово — сервер #${session.node?.id ?: "?"}")
        } catch (e: Exception) {
            val m = e.message.orEmpty()
            showNodeMessage(
                when {
                    m.contains("no_alternative_node") -> "Других серверов в этой стране сейчас нет. Попробуйте другую страну"
                    m.contains("switch_rate_limited") || m.contains("rate_limit") || m.contains("HTTP 429") ->
                        "Слишком частая смена сервера. Подождите немного"
                    else -> "Не удалось сменить сервер. Попробуйте ещё раз"
                }
            )
        } finally {
            _switching.value = false
        }
    }

    private suspend fun showNodeMessage(text: String) {
        _nodeMessage.value = text
        delay(6000)
        if (_nodeMessage.value == text) _nodeMessage.value = null
    }

    private fun saveSession(session: SessionResponse, serverKey: String, startedAt: Long) {
        prefs.edit()
            .putString("session_id", session.session_id)
            .putString("access_token", session.access_token)
            .putString("proxy_host", session.proxy.host)
            .putInt("proxy_port", session.proxy.port)
            .putInt("node_id", session.node?.id ?: -1)
            .putString("server_key", serverKey)
            .putLong("started_at", startedAt)
            .apply()
    }

    private fun clearSession() {
        prefs.edit()
            .remove("session_id").remove("access_token")
            .remove("proxy_host").remove("proxy_port")
            .remove("node_id").remove("started_at")
            .apply()
    }

    private suspend fun releaseSession() {
        val token = currentSession?.access_token ?: prefs.getString("access_token", null)
        token?.let { try { api.destroySession(it) } catch (_: Exception) {} }
        currentSession = null
        clearSession()
    }

    private suspend fun doDisable() {
        _vpnState.value = VpnState.Disconnecting
        appliedRevision = -1
        _nodeMessage.value = null
        releaseSession()
        _vpnState.value = VpnState.Off
    }

    private suspend fun doSwitchServer(newKey: String) {
        doDisable()
        _selectedServer.value = newKey
        doEnable(newKey)
    }

    fun restoreState() {
        if (restored) return          // поворот экрана не должен сбрасывать состояние
        restored = true

        val key = prefs.getString("server_key", "de") ?: "de"
        _selectedServer.value = key
        _premium.value = prefs.getBoolean("premium", false)

        // Туннель ещё жив (приложение закрывали, а сервис остался) — показываем «Подключён»
        if (QuVpnService.active && prefs.getString("proxy_host", null) != null) {
            sessionRevision++
            appliedRevision = sessionRevision
            _vpnState.value = VpnState.Connected(
                serverKey = key,
                nodeId = prefs.getInt("node_id", -1).takeIf { it >= 0 },
                premium = _premium.value,
                startedAt = prefs.getLong("started_at", System.currentTimeMillis()),
                revision = sessionRevision
            )
        } else {
            _vpnState.value = VpnState.Off
        }
    }

    fun getSessionData(): Triple<String, Int, String>? {
        val host  = prefs.getString("proxy_host", null) ?: return null
        val port  = prefs.getInt("proxy_port", 0).takeIf { it > 0 } ?: return null
        val token = prefs.getString("access_token", "") ?: ""
        return Triple(host, port, token)
    }
}
