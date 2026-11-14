package ru.open60m.quvpn.model

data class SessionResponse(
    val session_id: String,
    val access_token: String,
    val refresh_token: String?,
    val expires_at: String?,
    val proxy: ProxyInfo,
    val node: NodeInfo?
)

data class ProxyInfo(
    val host: String,
    val port: Int
)

data class NodeInfo(
    val id: Int?,
    val ip: String?
)

data class PremiumStatus(
    val premium: Boolean,
    val premium_until: String?
)

data class Server(
    val key: String,
    val name: String,
    val city: String,
    val country: String,
    val flagRes: Int,
    val premiumOnly: Boolean = false
)

sealed class VpnState {
    object Off : VpnState()
    object Connecting : VpnState()
    data class Connected(
        val serverKey: String,
        val nodeId: Int?,
        val premium: Boolean
    ) : VpnState()
    object Disconnecting : VpnState()
    data class Error(val message: String) : VpnState()
}
