package ru.open60m.quvpn.service

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Локальный SOCKS5-сервер (127.0.0.1), который каждое TCP-соединение превращает
 * в HTTP CONNECT к прокси Qu VPN (тот же Squid, что использует расширение).
 *
 * Принимает только команду CONNECT. UDP ASSOCIATE отклоняется — HTTP-прокси UDP не умеет.
 * DNS решается выше по стеку (mapdns в hev-socks5-tunnel): сюда приходят уже имена доменов.
 */
class SocksHttpBridge(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val protectSocket: (Socket) -> Boolean
) {
    companion object {
        private const val TAG = "SocksHttpBridge"
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 8_000
    }

    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "socks-bridge").apply { isDaemon = true }
    }
    private val active: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private var server: ServerSocket? = null
    @Volatile private var running = false

    /** Запускает сервер, возвращает локальный порт. */
    fun start(): Int {
        val s = ServerSocket(0, 256, InetAddress.getByName("127.0.0.1"))
        server = s
        running = true
        pool.execute { acceptLoop(s) }
        return s.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        active.toList().forEach { runCatching { it.close() } }
        active.clear()
        pool.shutdownNow()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            try {
                val client = s.accept()
                pool.execute { handle(client) }
            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "accept: ${e.message}")
            }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            active.add(client)
            client.tcpNoDelay = true
            client.soTimeout = HANDSHAKE_TIMEOUT_MS
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // --- приветствие SOCKS5 ---
            if (cin.readByteOrThrow() != 5) return
            val nMethods = cin.readByteOrThrow()
            cin.readFullyOrThrow(ByteArray(nMethods))
            cout.write(byteArrayOf(5, 0))
            cout.flush()

            // --- запрос ---
            if (cin.readByteOrThrow() != 5) return
            val cmd = cin.readByteOrThrow()
            cin.readByteOrThrow() // RSV
            val host = when (cin.readByteOrThrow()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { cin.readFullyOrThrow(it) }).hostAddress
                3 -> {
                    val len = cin.readByteOrThrow()
                    String(ByteArray(len).also { cin.readFullyOrThrow(it) }, Charsets.UTF_8)
                }
                4 -> "[" + InetAddress.getByAddress(ByteArray(16).also { cin.readFullyOrThrow(it) }).hostAddress + "]"
                else -> { reply(cout, 8); return }
            }
            val port = (cin.readByteOrThrow() shl 8) or cin.readByteOrThrow()

            if (cmd != 1) { // только CONNECT
                reply(cout, 7)
                return
            }

            // --- соединение с прокси и HTTP CONNECT ---
            val target = "$host:$port"
            val (up, status) = openConnect(target)
            upstream = up
            if (status != 200) {
                Log.d(TAG, "CONNECT $target -> HTTP $status")
                runCatching { up.close() }
                active.remove(up)
                upstream = null
                reply(cout, if (status == 403 || status == 407) 2 else 5)
                return
            }

            reply(cout, 0)
            client.soTimeout = 0
            up.soTimeout = 0

            // --- двусторонняя перекачка ---
            val back = Thread({ pump(up, client) }, "socks-pump-down").apply { isDaemon = true; start() }
            pump(client, up)
            back.join()
        } catch (e: Exception) {
            if (running) Log.d(TAG, "conn: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
            active.remove(client)
            upstream?.let { active.remove(it) }
        }
    }

    /** Открывает сокет к прокси и отправляет CONNECT. Возвращает (сокет, HTTP-код ответа). */
    private fun openConnect(target: String): Pair<Socket, Int> {
        val up = Socket()
        active.add(up)
        try {
            protectSocket(up)
            up.connect(InetSocketAddress(proxyHost, proxyPort), CONNECT_TIMEOUT_MS)
            up.tcpNoDelay = true
            up.soTimeout = HANDSHAKE_TIMEOUT_MS
            up.getOutputStream().apply {
                write("CONNECT $target HTTP/1.1\r\nHost: $target\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            return up to readConnectStatus(up.getInputStream())
        } catch (e: Exception) {
            runCatching { up.close() }
            active.remove(up)
            throw e
        }
    }

    /**
     * Самопроверка: CONNECT к probe-хосту через прокси (как проба в расширении).
     * Возвращает null, если всё хорошо, иначе — понятное описание проблемы.
     */
    fun selfTest(target: String = "probe.open60m.ru:443"): String? {
        var last = "неизвестная ошибка"
        for (attempt in 1..2) {
            try {
                val (sock, status) = openConnect(target)
                runCatching { sock.close() }
                active.remove(sock)
                if (status == 200) return null
                last = when (status) {
                    403, 407 -> "Прокси отклонил подключение (HTTP $status) — возможно, сервер не признал ваш IP"
                    else -> "Прокси ответил HTTP $status"
                }
            } catch (e: Exception) {
                last = "Нет связи с прокси $proxyHost:$proxyPort (${e.javaClass.simpleName}: ${e.message})"
            }
            if (attempt == 1) Thread.sleep(700)
        }
        return last
    }

    /** Копирует from → to; по EOF делает half-close, при ошибке закрывает обе стороны. */
    private fun pump(from: Socket, to: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            val i = from.getInputStream()
            val o = to.getOutputStream()
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                o.flush()
            }
            runCatching { to.shutdownOutput() }
        } catch (_: Exception) {
            runCatching { from.close() }
            runCatching { to.close() }
        }
    }

    private fun reply(out: java.io.OutputStream, code: Int) {
        runCatching {
            out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
        }
    }

    /** Читает заголовок ответа прокси до пустой строки, ничего лишнего не считывая. Возвращает HTTP-код. */
    private fun readConnectStatus(input: InputStream): Int {
        val head = ByteArrayOutputStream()
        var tail = 0
        while (head.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) throw EOFException("proxy closed")
            head.write(b)
            tail = (tail shl 8) or b
            if (tail == 0x0D0A0D0A) {
                val line = head.toString("US-ASCII").lineSequence().firstOrNull().orEmpty()
                return line.split(' ').getOrNull(1)?.toIntOrNull() ?: -1
            }
        }
        return -1
    }

    private fun InputStream.readByteOrThrow(): Int {
        val b = read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun InputStream.readFullyOrThrow(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }
}
