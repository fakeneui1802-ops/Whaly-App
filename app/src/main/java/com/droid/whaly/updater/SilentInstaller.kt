package com.droid.whaly.updater

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.droid.whaly.ShizukuHelper
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Install routes, best first.
 *
 * Play Protect only inserts itself in front of installs that arrive through the package
 * installer session API from an unknown installer. The shell uid (2000) holds
 * INSTALL_PACKAGES, so `pm install` from adb, Shizuku or root skips the scan dialog and
 * the "unknown app" prompt entirely. `-i com.android.vending` records the Play Store as
 * the installer of record, which keeps the verifier from treating the package as a
 * sideload in the first place.
 */
enum class InstallRoute(val label: String) {
    SHIZUKU("Shizuku shell"),
    ROOT("Root shell"),
    SESSION("System installer"),
}

data class InstallOutcome(
    val ok: Boolean,
    val route: InstallRoute,
    val detail: String,
)

object SilentInstaller {

    private const val TAG = "SilentInstaller"
    private const val FAKE_INSTALLER = "com.android.vending"

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** Only called right before an install, because it wakes the su daemon. */
    fun rootReady(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor(4, TimeUnit.SECONDS)
        text.contains("uid=0")
    } catch (_: Throwable) {
        false
    }

    fun routes(): List<InstallRoute> = buildList {
        if (shizukuReady()) add(InstallRoute.SHIZUKU)
        if (rootReady()) add(InstallRoute.ROOT)
        add(InstallRoute.SESSION)
    }

    /**
     * Tries every silent route, returns the first success. A failure falls through to the
     * next route rather than aborting, because Shizuku dies whenever the user reboots.
     */
    fun install(context: Context, apk: File): InstallOutcome {
        if (!apk.isFile || apk.length() == 0L) {
            return InstallOutcome(false, InstallRoute.SESSION, "APK missing or empty: ${apk.absolutePath}")
        }

        if (shizukuReady()) {
            val outcome = installViaShizuku(apk)
            if (outcome.ok) return outcome
            Log.w(TAG, "shizuku route failed: ${outcome.detail}")
        }

        if (rootReady()) {
            val outcome = installViaRoot(apk)
            if (outcome.ok) return outcome
            Log.w(TAG, "root route failed: ${outcome.detail}")
        }

        return InstallOutcome(
            ok = false,
            route = InstallRoute.SESSION,
            detail = "No silent route available — falling back to the system installer",
        )
    }

    private fun installViaShizuku(apk: File): InstallOutcome {
        // -S streams the APK over stdin; the shell cannot read the app's private cache.
        val command = "pm install -S ${apk.length()} -r -i $FAKE_INSTALLER"
        val raw = ShizukuHelper.runShellCommandWithStdin(command, apk)
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return InstallOutcome(false, InstallRoute.SHIZUKU, raw.take(300))
        }
        val out = json.optString("out").trim()
        val err = json.optString("err").trim()
        val code = json.optInt("code", -1)
        val ok = code == 0 && out.contains("Success", ignoreCase = true)
        return InstallOutcome(
            ok = ok,
            route = InstallRoute.SHIZUKU,
            detail = if (ok) "Installed through the shell uid" else "pm install: ${err.ifBlank { out }.take(300)}",
        )
    }

    private fun installViaRoot(apk: File): InstallOutcome = try {
        val command = "pm install -r -i $FAKE_INSTALLER ${apk.absolutePath}"
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText().trim()
        val finished = process.waitFor(240, TimeUnit.SECONDS)
        InstallOutcome(
            ok = finished && text.contains("Success", ignoreCase = true),
            route = InstallRoute.ROOT,
            detail = text.take(300).ifBlank { "su produced no output" },
        )
    } catch (error: Exception) {
        InstallOutcome(false, InstallRoute.ROOT, error.javaClass.simpleName + ": " + (error.message ?: ""))
    }

    // ---- verifier control -------------------------------------------------------------

    /** "0" means the package verifier is off, "null" or a missing key means it is on. */
    fun verifierState(): String = runPrivileged(
        "settings get global package_verifier_enable; settings get global verifier_verify_adb_installs",
    ).second

    fun disableVerifier(): InstallOutcome {
        val (ok, detail) = runPrivileged(
            "settings put global package_verifier_enable 0; " +
                "settings put global verifier_verify_adb_installs 0; " +
                "settings get global package_verifier_enable; " +
                "settings get global verifier_verify_adb_installs",
        )
        return InstallOutcome(
            ok = ok && detail.contains("0"),
            route = if (shizukuReady()) InstallRoute.SHIZUKU else InstallRoute.ROOT,
            detail = detail.ifBlank { "no output" },
        )
    }

    fun enableVerifier(): InstallOutcome {
        val (ok, detail) = runPrivileged(
            "settings put global package_verifier_enable 1; " +
                "settings put global verifier_verify_adb_installs 1; " +
                "settings get global package_verifier_enable",
        )
        return InstallOutcome(ok, if (shizukuReady()) InstallRoute.SHIZUKU else InstallRoute.ROOT, detail.ifBlank { "no output" })
    }

    /** Runs through Shizuku when it is alive, root otherwise. Returns (exitOk, output). */
    private fun runPrivileged(command: String): Pair<Boolean, String> {
        if (shizukuReady()) {
            val raw = ShizukuHelper.runShellCommandWithOutput(command)
            val json = try {
                JSONObject(raw)
            } catch (_: Exception) {
                return false to raw.take(300)
            }
            val text = (json.optString("out") + json.optString("err")).trim()
            return (json.optInt("code", -1) == 0) to text
        }
        if (rootReady()) {
            return try {
                val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText().trim()
                val finished = process.waitFor(30, TimeUnit.SECONDS)
                finished to text
            } catch (error: Exception) {
                false to (error.message ?: "root failed")
            }
        }
        return false to "No Shizuku and no root — grant one of them first"
    }
}
