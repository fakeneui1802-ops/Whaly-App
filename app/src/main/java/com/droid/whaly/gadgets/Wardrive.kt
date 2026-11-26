package com.droid.whaly.gadgets

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wi-Fi wardriving without an ESP32.
 *
 * The Unleashed wardriving app needs the Wi-Fi devboard because the Flipper has no radio.
 * A phone already has both halves — a Wi-Fi radio and a GPS — so this is the same result with
 * no dongle: scan, stamp every new BSSID with a fix, write CSV and KML.
 *
 * Needs ACCESS_FINE_LOCATION. Android redacts SSIDs and BSSIDs without it, so a scan without
 * the permission returns an empty list rather than partial data.
 */
object Wardrive {

    data class Hit(
        val bssid: String,
        val ssid: String,
        val rssi: Int,
        val frequency: Int,
        val capabilities: String,
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val seenAt: Long,
    )

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()
    private val _hits = MutableStateFlow<List<Hit>>(emptyList())
    val hits: StateFlow<List<Hit>> = _hits.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val active = AtomicBoolean(false)
    private var worker: Thread? = null
    private val seen = LinkedHashMap<String, Hit>()

    private fun append(line: String) {
        _log.value = (_log.value + line).takeLast(120)
    }

    private fun hasLocation(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun fix(context: Context): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        if (!hasLocation(context)) return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return providers
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }

    fun stop() {
        active.set(false)
        worker?.interrupt()
        worker = null
        _running.value = false
    }

    fun reset() {
        seen.clear()
        _hits.value = emptyList()
        _count.value = 0
        _log.value = emptyList()
    }

    fun start(context: Context) {
        if (!active.compareAndSet(false, true)) return
        if (!hasLocation(context)) {
            active.set(false)
            _log.value = listOf("Location permission is required — Android hides BSSIDs without it")
            return
        }
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            active.set(false)
            _log.value = listOf("No Wi-Fi service")
            return
        }
        _running.value = true
        append("wardriving started")

        worker = Thread({
            while (active.get()) {
                val location = fix(context)
                val results = runCatching { wifi.scanResults }.getOrDefault(emptyList())
                if (results.isEmpty()) {
                    append("scan returned nothing — is Wi-Fi on?")
                }
                results.forEach { result ->
                    val bssid = result.BSSID ?: return@forEach
                    if (seen.containsKey(bssid)) return@forEach
                    val hit = Hit(
                        bssid = bssid.uppercase(Locale.US),
                        ssid = result.SSID.orEmpty(),
                        rssi = result.level,
                        frequency = result.frequency,
                        capabilities = result.capabilities.orEmpty(),
                        latitude = location?.latitude ?: 0.0,
                        longitude = location?.longitude ?: 0.0,
                        accuracy = location?.accuracy ?: 0f,
                        seenAt = System.currentTimeMillis(),
                    )
                    seen[bssid] = hit
                }
                _hits.value = seen.values.toList()
                _count.value = seen.size
                append("${seen.size} unique BSSID(s) · ${results.size} in last scan")
                runCatching { wifi.startScan() }
                try {
                    Thread.sleep(4000)
                } catch (_: InterruptedException) {
                    break
                }
            }
            active.set(false)
            _running.value = false
        }, "whaly-wardrive").apply { isDaemon = true; start() }
    }

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun exportCsv(context: Context): File? {
        if (seen.isEmpty()) return null
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "wardrive-${stamp()}.csv")
        return runCatching {
            file.bufferedWriter().use { writer ->
                writer.write("WigleWifi-1.4,appRelease=Whaly,model=Android,release=${Build.VERSION.RELEASE}\n")
                writer.write("MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,Type\n")
                seen.values.forEach { hit ->
                    val channel = channelOf(hit.frequency)
                    val auth = when {
                        hit.capabilities.contains("WPA3") -> "[WPA3]"
                        hit.capabilities.contains("WPA2") -> "[WPA2]"
                        hit.capabilities.contains("WPA") -> "[WPA]"
                        hit.capabilities.contains("WEP") -> "[WEP]"
                        else -> "[OPEN]"
                    }
                    writer.write(
                        listOf(
                            hit.bssid,
                            hit.ssid.replace(",", " "),
                            auth,
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(hit.seenAt)),
                            channel.toString(),
                            hit.rssi.toString(),
                            "%.6f".format(Locale.US, hit.latitude),
                            "%.6f".format(Locale.US, hit.longitude),
                            "0",
                            hit.accuracy.toInt().toString(),
                            "WIFI",
                        ).joinToString(","),
                    )
                    writer.write("\n")
                }
            }
            append("CSV → ${file.absolutePath}")
            file
        }.getOrNull()
    }

    fun exportKml(context: Context): File? {
        if (seen.isEmpty()) return null
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "wardrive-${stamp()}.kml")
        return runCatching {
            file.bufferedWriter().use { writer ->
                writer.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
                writer.write("""<kml xmlns="http://www.opengis.net/kml/2.2"><Document>""" + "\n")
                writer.write("<name>Whaly wardrive</name>\n")
                seen.values.forEach { hit ->
                    if (hit.latitude == 0.0 && hit.longitude == 0.0) return@forEach
                    writer.write(
                        "<Placemark><name>${escape(hit.ssid.ifBlank { hit.bssid })}</name>" +
                            "<description>${hit.bssid} · ${hit.rssi} dBm · ${channelOf(hit.frequency)}</description>" +
                            "<Point><coordinates>${hit.longitude},${hit.latitude},0</coordinates></Point>" +
                            "</Placemark>\n",
                    )
                }
                writer.write("</Document></kml>\n")
            }
            append("KML → ${file.absolutePath}")
            file
        }.getOrNull()
    }

    private fun channelOf(frequency: Int): Int = when {
        frequency == 2484 -> 14
        frequency in 2412..2472 -> (frequency - 2407) / 5
        frequency in 5180..5825 -> (frequency - 5000) / 5
        frequency in 5955..7115 -> (frequency - 5950) / 5
        else -> 0
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
