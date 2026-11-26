package com.droid.whaly.offensive

import android.content.Context
import android.os.Environment
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Cross-platform drop: an iPhone in Safari opens the URL and posts a file straight into
 * Whaly. No AirDrop, no pairing, no account. This is the part that actually moves bytes.
 */
class WhalyDropServer(
    private val context: Context,
    port: Int = 8080,
) : NanoHTTPD(port) {

    private val _received = MutableStateFlow<List<String>>(emptyList())
    val received: StateFlow<List<String>> = _received.asStateFlow()

    fun targetDirectory(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(base, "WhalyDrop").apply { mkdirs() }
    }

    private fun listReceived(): List<String> =
        targetDirectory().listFiles()?.sortedByDescending { it.lastModified() }?.map { it.name }.orEmpty()

    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/", "/index.html" -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                uploadPage(),
            )
            "/upload" -> handleUpload(session)
            "/files" -> {
                _received.value = listReceived()
                newFixedLengthResponse(
                    Response.Status.OK,
                    "text/plain; charset=utf-8",
                    listReceived().joinToString("\n"),
                )
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun handleUpload(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "POST only")
        }
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val saved = mutableListOf<String>()
            files.forEach { (field, tempPath) ->
                val temp = File(tempPath)
                if (!temp.exists() || temp.length() == 0L) return@forEach
                val original = session.parameters[field]?.firstOrNull()
                    ?.substringAfterLast('/')
                    ?.substringAfterLast('\\')
                    ?.takeIf { it.isNotBlank() }
                    ?: "upload-${System.currentTimeMillis()}"
                val destination = File(targetDirectory(), sanitize(original))
                temp.copyTo(destination, overwrite = true)
                saved += destination.name
            }
            _received.value = listReceived()
            if (saved.isEmpty()) {
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", resultPage("Nothing arrived", emptyList()))
            } else {
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", resultPage("Received", saved))
            }
        } catch (error: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain; charset=utf-8",
                "Upload failed: ${error.message}",
            )
        }
    }

    private fun sanitize(name: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        return clean.ifBlank { "upload-${System.currentTimeMillis()}" }
    }

    private fun uploadPage(): String = """
        <!doctype html>
        <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Whaly Drop</title>
        <style>
        body{font-family:-apple-system,system-ui,sans-serif;background:#101014;color:#f2f2f7;margin:0;
        display:flex;min-height:100vh;align-items:center;justify-content:center}
        .card{background:#1c1c22;border-radius:22px;padding:28px;max-width:420px;width:90%;box-shadow:0 12px 40px #0008}
        h1{font-size:22px;margin:0 0 6px}p{color:#9a9aa5;font-size:14px;margin:0 0 20px}
        label{display:block;border:2px dashed #3a3a44;border-radius:16px;padding:26px;text-align:center;color:#9a9aa5}
        input[type=file]{display:none}
        button{margin-top:18px;width:100%;padding:14px;border:0;border-radius:14px;background:#4f7cff;color:#fff;
        font-size:16px;font-weight:600}
        ul{color:#9a9aa5;font-size:13px;padding-left:18px}
        </style></head>
        <body><div class="card">
        <h1>Whaly Drop</h1>
        <p>Send a file to the phone. Nothing leaves your network.</p>
        <form method="post" action="/upload" enctype="multipart/form-data">
        <label onclick="document.getElementById('f').click()">Tap to choose a file
        <input id="f" type="file" name="file" onchange="this.form.submit()"></label>
        </form>
        <ul>${listReceived().take(8).joinToString("") { "<li>${it}</li>" }}</ul>
        </div></body></html>
    """.trimIndent()

    private fun resultPage(title: String, files: List<String>): String = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>Whaly Drop</title>
        <style>body{font-family:-apple-system,system-ui,sans-serif;background:#101014;color:#f2f2f7;
        display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}
        .card{background:#1c1c22;border-radius:22px;padding:28px;max-width:420px;width:90%}
        a{color:#4f7cff}</style></head>
        <body><div class="card"><h1>$title</h1>
        <ul>${files.joinToString("") { "<li>$it</li>" }}</ul>
        <p><a href="/">Send another</a></p></div></body></html>
    """.trimIndent()

    companion object {
        fun localAddress(): String? {
            return try {
                NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                    ?.hostAddress
            } catch (_: Exception) {
                null
            }
        }
    }
}
