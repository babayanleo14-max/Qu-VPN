package hev.htproxy

/**
 * JNI-обёртка над hev-socks5-tunnel (tun2socks).
 * Имя пакета и класса зашито в нативной библиотеке (hev/htproxy/TProxyService) — не переименовывать.
 */
object TProxyService {
    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    @JvmStatic external fun TProxyGetStats(): LongArray

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
