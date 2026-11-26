package com.droid.whaly.scanned

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ScannedDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val type: String,
    val brand: String,
    val lastSeen: Long,
)

/**
 * Turns a raw advertising packet into a folder path: device class first, then brand.
 *
 * Name fragments win over service UUIDs because most of the interesting gear — TVs,
 * whiteboards, speakers — advertises a brand string and nothing else, while rental
 * scooters and trackers hide behind a generic UUID.
 */
object DeviceClassifier {

    private val brands: List<Pair<String, List<String>>> = listOf(
        "Samsung" to listOf("samsung", "galaxy", "sm-", "buds"),
        "LG" to listOf("lg ", "lg-", "webos", "oled"),
        "Sony" to listOf("sony", "bravia", "wh-1000", "wf-1000"),
        "Philips" to listOf("philips", "hue"),
        "Hisense" to listOf("hisense"),
        "TCL" to listOf("tcl"),
        "Panasonic" to listOf("panasonic", "viera"),
        "Xiaomi" to listOf("xiaomi", "mi ", "m365", "mijia", "redmi", "miscooter"),
        "Ninebot" to listOf("ninebot", "segway", "nb-", "max g", "g30"),
        "Voi" to listOf("voi", "voiager"),
        "Lime" to listOf("lime"),
        "Bird" to listOf("bird"),
        "Tier" to listOf("tier"),
        "Dott" to listOf("dott"),
        "Bolt" to listOf("bolt"),
        "Okai" to listOf("okai"),
        "JBL" to listOf("jbl"),
        "Bose" to listOf("bose"),
        "Sonos" to listOf("sonos"),
        "Apple" to listOf("airpods", "iphone", "macbook", "apple", "airtag"),
        "Google" to listOf("google", "nest", "chromecast"),
        "Amazon" to listOf("amazon", "echo", "kindle", "fire tv"),
        "Huawei" to listOf("huawei", "honor"),
        "Anker" to listOf("anker", "soundcore"),
        "Logitech" to listOf("logitech", "mx "),
        "SwitchBot" to listOf("switchbot"),
        "Flipper" to listOf("flipper"),
        "Microsoft" to listOf("microsoft", "surface", "xbox"),
        "Nintendo" to listOf("nintendo", "joy-con", "pro controller"),
    )

    private val types: List<Pair<String, List<String>>> = listOf(
        "Scooter" to listOf("scooter", "voi", "lime", "ninebot", "segway", "m365", "okai", "bird", "tier", "dott", "bolt"),
        "TV" to listOf("tv", "bravia", "webos", "viera", "androidtv", "fire tv", "chromecast", "roku", "hisense", "tcl"),
        "Whiteboard" to listOf("whiteboard", "smartboard", "board", "viewboard", "interactive"),
        "Projector" to listOf("projector", "beamer", "epson", "benq", "optoma"),
        "Speaker" to listOf("speaker", "soundbar", "jbl", "bose", "sonos", "echo", "homepod", "soundcore"),
        "Headphones" to listOf("buds", "airpods", "headphone", "wh-1000", "wf-1000", "beats"),
        "Watch" to listOf("watch", "band", "fit", "garmin"),
        "Printer" to listofPrinter(),
        "Camera" to listofCamera(),
        "Keyboard" to listOf("keyboard", "keychron", "logi", "mx keys"),
        "Tracker" to listOf("airtag", "tile", "tracker", "smarttag"),
        "Beacon" to listOf("beacon", "ibeacon", "eddystone"),
    )

    private fun listofPrinter() = listOf("printer", "hp ", "epson", "brother", "canon")

    private fun listofCamera() = listOf("camera", "gopro", "dji", "insta360")

    fun brand(name: String): String {
        val lower = name.lowercase()
        return brands.firstOrNull { (_, hints) -> hints.any { lower.contains(it) } }?.first ?: "Unknown"
    }

    fun type(name: String): String {
        val lower = name.lowercase()
        return types.firstOrNull { (_, hints) -> hints.any { lower.contains(it) } }?.first ?: "Unknown"
    }

    fun classify(name: String, rssi: Int): Pair<String, String> {
        val clean = name.trim().ifBlank { "Unnamed" }
        return type(clean) to brand(clean)
    }
}

object ScannedDeviceStore {

    private const val PREFS = "WhalyScannedDevices"
    private const val KEY = "devices_json"
    private const val MAX_ENTRIES = 600

    private val cache = linkedMapOf<String, ScannedDevice>()

    fun all(): List<ScannedDevice> = cache.values.sortedByDescending { it.lastSeen }

    fun ensureLoaded(context: Context) {
        if (cache.isNotEmpty()) return
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val device = ScannedDevice(
                    address = item.optString("address"),
                    name = item.optString("name"),
                    rssi = item.optInt("rssi"),
                    type = item.optString("type", "Unknown"),
                    brand = item.optString("brand", "Unknown"),
                    lastSeen = item.optLong("lastSeen"),
                )
                if (device.address.isNotBlank()) cache[device.address] = device
            }
        }
    }

    fun record(context: Context, address: String, name: String, rssi: Int) {
        if (address.isBlank()) return
        val existing = cache[address]
        val displayName = name.trim().ifBlank { existing?.name ?: "Unnamed" }
        val (type, brand) = DeviceClassifier.classify(displayName, rssi)
        cache[address] = ScannedDevice(
            address = address,
            name = displayName,
            rssi = rssi,
            type = if (type == "Unknown" && existing != null) existing.type else type,
            brand = if (brand == "Unknown" && existing != null) existing.brand else brand,
            lastSeen = System.currentTimeMillis(),
        )
        if (cache.size > MAX_ENTRIES) {
            val oldest = cache.values.minByOrNull { it.lastSeen } ?: return
            cache.remove(oldest.address)
        }
        persist(context)
    }

    fun clear(context: Context) {
        cache.clear()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    fun grouped(): List<Pair<String, List<Pair<String, List<ScannedDevice>>>>> =
        all()
            .groupBy { it.type }
            .toSortedMap()
            .map { (type, devices) ->
                type to devices
                    .groupBy { it.brand }
                    .toSortedMap()
                    .map { (brand, inBrand) -> brand to inBrand.sortedByDescending { it.rssi } }
            }

    private fun persist(context: Context) {
        val array = JSONArray()
        cache.values.forEach { device ->
            array.put(
                JSONObject()
                    .put("address", device.address)
                    .put("name", device.name)
                    .put("rssi", device.rssi)
                    .put("type", device.type)
                    .put("brand", device.brand)
                    .put("lastSeen", device.lastSeen),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, array.toString())
            .apply()
    }
}
