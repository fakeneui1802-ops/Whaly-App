package com.droid.whaly.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.droid.whaly.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR }

data class UpdateState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val latestVersion: String = "",
    val notes: String = "",
    val progress: Int = 0,
    val message: String = "",
)

/**
 * Checks the fork's GitHub releases, downloads the APK and hands it to PackageInstaller.
 * The installed build must be signed with the same key, otherwise Android refuses the update.
 */
object WhalyUpdater {

    private const val TAG = "WhalyUpdater"
    const val REPO = "fakeneui1802-ops/Whaly-App"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"

    private val executor = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var pendingFile: File? = null

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    private fun versionParts(value: String): List<Int> =
        value.removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }

    private fun isNewer(remote: String, local: String): Boolean {
        val a = versionParts(remote)
        val b = versionParts(local)
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    fun check(context: Context) {
        if (_state.value.status == UpdateStatus.CHECKING) return
        _state.value = UpdateState(status = UpdateStatus.CHECKING)
        executor.execute {
            try {
                val connection = (URL(API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "Whaly-Updater")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val json = JSONObject(body)
                val tag = json.optString("tag_name", "")
                val notes = json.optString("body", "")
                val asset = json.optJSONArray("assets")
                    ?.let { assets ->
                        (0 until assets.length())
                            .map { assets.getJSONObject(it) }
                            .firstOrNull { it.optString("name").endsWith(".apk", true) }
                    }
                if (asset == null) {
                    _state.value = UpdateState(UpdateStatus.ERROR, message = "No APK attached to the latest release")
                    return@execute
                }
                val url = asset.optString("browser_download_url")
                if (isNewer(tag, currentVersion())) {
                    _state.value = UpdateState(
                        status = UpdateStatus.AVAILABLE,
                        latestVersion = tag,
                        notes = notes,
                        message = url,
                    )
                } else {
                    _state.value = UpdateState(
                        status = UpdateStatus.UP_TO_DATE,
                        latestVersion = tag.ifBlank { currentVersion() },
                        message = "Installed build is current",
                    )
                }
            } catch (error: Exception) {
                Log.w(TAG, "update check failed", error)
                _state.value = UpdateState(
                    UpdateStatus.ERROR,
                    message = "Check failed: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
    }

    fun download(context: Context) {
        val url = _state.value.message
        if (url.isBlank()) return
        _state.value = _state.value.copy(status = UpdateStatus.DOWNLOADING, progress = 0)
        val target = File(context.cacheDir, "whaly-update.apk")
        executor.execute {
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Whaly-Updater")
                }
                val total = connection.contentLengthLong.coerceAtLeast(0)
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                _state.value = _state.value.copy(progress = ((copied * 100) / total).toInt())
                            }
                        }
                    }
                }
                connection.disconnect()
                pendingFile = target
                _state.value = _state.value.copy(status = UpdateStatus.READY, progress = 100)
            } catch (error: Exception) {
                Log.w(TAG, "download failed", error)
                _state.value = _state.value.copy(status = UpdateStatus.ERROR, message = "Download failed: ${error.message}")
            }
        }
    }

    fun install(context: Context) {
        val file = pendingFile ?: return
        _state.value = _state.value.copy(message = "Installing...")

        // Silent routes first: the shell uid skips the Play Protect scan dialog entirely.
        val silent = SilentInstaller.install(context, file)
        if (silent.ok) {
            _state.value = _state.value.copy(
                status = UpdateStatus.READY,
                message = "Installed via ${silent.route.label}",
            )
            return
        }
        _state.value = _state.value.copy(message = silent.detail)

        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("whaly.apk", 0, file.length()).use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(context, UpdateInstallReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                session.commit(pending.intentSender)
            }
            _state.value = _state.value.copy(message = "Installer launched")
        } catch (error: Exception) {
            Log.w(TAG, "install failed", error)
            _state.value = _state.value.copy(status = UpdateStatus.ERROR, message = "Install failed: ${error.message}")
        }
    }
}
