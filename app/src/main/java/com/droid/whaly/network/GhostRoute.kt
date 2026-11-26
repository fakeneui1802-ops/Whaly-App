package com.droid.whaly.network

import android.content.Context
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Routes camera requests through a SOCKS5 relay so the camera never logs the phone.
 *
 * Two leaks matter. The HTTP request carries the phone's address, and the User-Agent
 * names the app. With a relay the camera sees the exit node, and the agent is reduced to
 * a generic browser string. The local port scan that found the camera still originates
 * from the phone — that part cannot be hidden without moving the whole scan off-device.
 */
object GhostRoute {

    private const val PREFS = "WhalyGhostRoute"

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var host: String = "127.0.0.1"
        private set
    @Volatile var port: Int = 9050
        private set

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("enabled", false)
        host = prefs.getString("host", "127.0.0.1") ?: "127.0.0.1"
        port = prefs.getInt("port", 9050)
    }

    fun save(context: Context, enabled: Boolean, host: String, port: Int) {
        this.enabled = enabled
        this.host = host.ifBlank { "127.0.0.1" }
        this.port = port.coerceIn(1, 65535)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("enabled", this.enabled)
            .putString("host", this.host)
            .putInt("port", this.port)
            .apply()
    }

    fun open(url: String): HttpURLConnection {
        val target = URL(url)
        if (!enabled) return target.openConnection() as HttpURLConnection
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
        return target.openConnection(proxy) as HttpURLConnection
    }

    /** A relayed request should look like a browser, not like a scanner. */
    fun userAgent(): String = if (enabled) "Mozilla/5.0 (Windows NT 10.0; Win64; x64)" else "CamCatch/1.0"

    fun describe(): String = if (enabled) "relay $host:$port" else "direct"
}
