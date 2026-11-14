package ru.open60m.quvpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import hev.htproxy.TProxyService
import ru.open60m.quvpn.R
import ru.open60m.quvpn.ui.MainActivity
import java.io.File

class QuVpnService : VpnService() {

    companion object {
        const val TAG = "QuVpnService"
        const val CHANNEL_ID = "quvpn_channel"
        const val NOTIF_ID = 1

        // Actions
        const val ACTION_CONNECT    = "ru.open60m.quvpn.CONNECT"
        const val ACTION_DISCONNECT = "ru.open60m.quvpn.DISCONNECT"

        // Extras
        const val EXTRA_PROXY_HOST   = "proxy_host"
        const val EXTRA_PROXY_PORT   = "proxy_port"
        const val EXTRA_SERVER_NAME  = "server_name"
        const val EXTRA_ACCESS_TOKEN = "access_token"
        const val EXTRA_PREMIUM      = "premium"

        // Broadcast обратно в UI
        const val BROADCAST_STATE = "ru.open60m.quvpn.STATE"
        const val EXTRA_STATE     = "state"
        const val STATE_CONNECTED    = "CONNECTED"
        const val STATE_DISCONNECTED = "DISCONNECTED"
        const val STATE_ERROR        = "ERROR"
        const val EXTRA_ERROR_MSG    = "error_msg"

        // Параметры TUN / tun2socks
        private const val TUN_MTU      = 8500
        private const val TUN_IPV4     = "198.18.0.1"
        private const val TUN_IPV6     = "fc00::1"
        private const val MAPDNS_ADDR  = "198.18.0.2"   // локальный DNS внутри туннеля
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var bridge: SocksHttpBridge? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        when (intent?.action) {
            ACTION_CONNECT -> {
                val host   = intent.getStringExtra(EXTRA_PROXY_HOST) ?: return START_NOT_STICKY
                val port   = intent.getIntExtra(EXTRA_PROXY_PORT, 0)
                val name   = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "Qu VPN"
                val token  = intent.getStringExtra(EXTRA_ACCESS_TOKEN) ?: ""
                val premium = intent.getBooleanExtra(EXTRA_PREMIUM, false)
                startForeground(NOTIF_ID, buildNotification(name, premium))
                startTunnel(host, port)
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ======== Туннель ========
    // TUN (Android) → hev-socks5-tunnel (tun2socks, нативная) → локальный SOCKS5 (SocksHttpBridge)
    //   → HTTP CONNECT на прокси сессии (Squid, как в расширении).
    // DNS: mapdns внутри tun2socks отвечает фейковыми IP и передаёт мосту домены — UDP не нужен.
    // Своё приложение исключено из VPN (addDisallowedApplication), поэтому API и сокет к прокси идут напрямую.

    private fun startTunnel(proxyHost: String, proxyPort: Int) {
        stopTunnelInternal()

        try {
            val b = SocksHttpBridge(proxyHost, proxyPort) { protect(it) }
            val socksPort = b.start()
            bridge = b

            val builder = Builder()
                .setSession("Qu VPN")
                .setMtu(TUN_MTU)
                .addAddress(TUN_IPV4, 32)
                .addAddress(TUN_IPV6, 128)
                .addRoute("0.0.0.0", 0)          // весь IPv4-трафик через VPN
                .addRoute("::", 0)               // и IPv6, чтобы он не утекал мимо туннеля
                .addDnsServer(MAPDNS_ADDR)
            runCatching { builder.addDisallowedApplication(packageName) }

            val pfd = builder.establish()
            if (pfd == null) {
                b.stop(); bridge = null
                broadcastError("Не удалось создать VPN интерфейс")
                return
            }
            vpnInterface = pfd

            val cfg = File(filesDir, "tproxy.yml")
            cfg.writeText(buildTunnelConfig(socksPort))

            val ok = TProxyService.TProxyStartService(cfg.absolutePath, pfd.fd)
            if (!ok) {
                stopTunnelInternal()
                broadcastError("Не удалось запустить туннель")
                return
            }

            Log.d(TAG, "Tunnel up: socks=127.0.0.1:$socksPort proxy=$proxyHost:$proxyPort")

            // Самопроверка прокси в фоне: если CONNECT не проходит — VPN бесполезен, говорим почему
            Thread({
                val problem = b.selfTest()
                if (bridge !== b) return@Thread // уже остановлен/перезапущен
                if (problem == null) {
                    broadcastState(STATE_CONNECTED)
                } else {
                    Log.e(TAG, "Self-test failed: $problem")
                    stopTunnelInternal()
                    broadcastError(problem)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, "vpn-selftest").apply { isDaemon = true }.start()

        } catch (e: Throwable) {
            Log.e(TAG, "startTunnel failed", e)
            stopTunnelInternal()
            broadcastError(e.message ?: "Ошибка запуска туннеля")
        }
    }

    private fun buildTunnelConfig(socksPort: Int): String = """
        tunnel:
          mtu: $TUN_MTU
          ipv4: $TUN_IPV4
          ipv6: '$TUN_IPV6'
        socks5:
          port: $socksPort
          address: 127.0.0.1
          udp: 'udp'
        mapdns:
          address: $MAPDNS_ADDR
          port: 53
          network: 100.64.0.0
          netmask: 255.192.0.0
          cache-size: 10000
        misc:
          log-level: warn
    """.trimIndent() + "\n"

    private fun stopTunnelInternal() {
        runCatching { if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService() }
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        bridge?.stop()
        bridge = null
    }

    private fun stopTunnel() {
        stopTunnelInternal()
        broadcastState(STATE_DISCONNECTED)
        Log.d(TAG, "Tunnel stopped")
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
    }

    // ======== Broadcast ========
    private fun broadcastState(state: String) {
        sendBroadcast(Intent(BROADCAST_STATE).putExtra(EXTRA_STATE, state))
    }

    private fun broadcastError(msg: String) {
        sendBroadcast(
            Intent(BROADCAST_STATE)
                .putExtra(EXTRA_STATE, STATE_ERROR)
                .putExtra(EXTRA_ERROR_MSG, msg)
        )
    }

    // ======== Notification ========
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Qu VPN",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "VPN статус" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(serverName: String, premium: Boolean): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val sub = if (premium) "Premium • $serverName" else "Бесплатно • $serverName"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🟢 Qu VPN подключён")
            .setContentText(sub)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
