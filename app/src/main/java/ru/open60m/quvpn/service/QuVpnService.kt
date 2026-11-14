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
import ru.open60m.quvpn.R
import ru.open60m.quvpn.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

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
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    @Volatile private var running = false

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
                startTunnel(host, port, token)
            }
            ACTION_DISCONNECT -> {
                stopTunnel()
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ======== Туннель ========
    // Схема: TUN (Android) → читаем пакеты → оборачиваем в HTTP CONNECT через прокси → отдаём обратно
    // Это упрощённая реализация через socks/http proxy + TUN forwarding.
    // Для продакшена рекомендуется использовать tun2socks (C-либа), здесь — чистый Kotlin прототип.

    private fun startTunnel(proxyHost: String, proxyPort: Int, accessToken: String) {
        running = false
        vpnInterface?.close()
        tunnelThread?.interrupt()

        try {
            // Создаём TUN-интерфейс
            val builder = Builder()
                .setSession("Qu VPN")
                .addAddress("10.0.0.2", 24)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)         // весь трафик через VPN
                .setMtu(1500)

            vpnInterface = builder.establish() ?: run {
                broadcastError("Не удалось создать VPN интерфейс")
                return
            }

            running = true
            Log.d(TAG, "TUN interface established, proxy: $proxyHost:$proxyPort")

            // Поток для форвардинга пакетов
            tunnelThread = Thread({
                val tunIn  = FileInputStream(vpnInterface!!.fileDescriptor)
                val tunOut = FileOutputStream(vpnInterface!!.fileDescriptor)
                val buf    = ByteBuffer.allocate(32767)

                // Создаём прокси-соединение
                val proxySocket = Socket()
                try {
                    protect(proxySocket)   // этот сокет идёт напрямую, минуя VPN
                    proxySocket.connect(InetSocketAddress(proxyHost, proxyPort), 7000)
                    proxySocket.soTimeout = 0

                    // HTTP CONNECT туннель к бэкенду (probe endpoint проверяет связь)
                    val connectReq = "CONNECT ${proxyHost}:${proxyPort} HTTP/1.1\r\nHost: ${proxyHost}:${proxyPort}\r\n\r\n"
                    proxySocket.getOutputStream().write(connectReq.toByteArray())

                    Log.d(TAG, "Proxy socket connected")
                    broadcastState(STATE_CONNECTED)

                    val proxyIn  = proxySocket.getInputStream()
                    val proxyOut = proxySocket.getOutputStream()

                    // Два параллельных потока: TUN→proxy и proxy→TUN
                    val toProxy = Thread({
                        val b = ByteArray(32767)
                        while (running && !Thread.currentThread().isInterrupted) {
                            val n = tunIn.read(b)
                            if (n > 0) proxyOut.write(b, 0, n)
                        }
                    }, "tun2proxy").also { it.isDaemon = true; it.start() }

                    val fromProxy = ByteArray(32767)
                    while (running && !Thread.currentThread().isInterrupted) {
                        val n = proxyIn.read(fromProxy)
                        if (n > 0) tunOut.write(fromProxy, 0, n)
                    }
                    toProxy.interrupt()

                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "Tunnel error: ${e.message}")
                        broadcastError(e.message ?: "Ошибка туннеля")
                    }
                } finally {
                    runCatching { proxySocket.close() }
                }
            }, "vpn-tunnel").also { it.isDaemon = true }

            tunnelThread!!.start()

        } catch (e: Exception) {
            Log.e(TAG, "startTunnel failed: ${e.message}")
            broadcastError(e.message ?: "Ошибка запуска")
        }
    }

    private fun stopTunnel() {
        running = false
        tunnelThread?.interrupt()
        tunnelThread = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
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
