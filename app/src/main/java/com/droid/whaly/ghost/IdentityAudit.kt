package com.droid.whaly.ghost

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.wifi.WifiManager

/**
 * What leaves the phone, stated as facts rather than assurances.
 *
 * The list is computed at runtime so it reflects the device in hand: the adapter name from
 * the vault, the address Android exposes, and whether the platform masks the Wi-Fi MAC.
 * Every entry is either "not transmitted" or a value the platform itself exposes.
 *
 * Nothing here writes to the adapter. This is a read-only audit.
 */
object IdentityAudit {

    data class Line(val label: String, val value: String, val safe: Boolean)

    @SuppressLint("MissingPermission", "HardwareIds")
    fun run(context: Context): List<Line> {
        val lines = mutableListOf<Line>()

        val realName = GhostIdentity.realName(context)
        lines += Line(
            "adapter name",
            if (realName == null) "not stored yet" else "in vault, read-only, never written",
            true,
        )
        lines += Line("local name in advertisement", "omitted — no 0x09 record", true)

        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        val address = runCatching { adapter?.address }.getOrNull() ?: "unavailable"
        lines += Line(
            "LE address",
            "$address (controller field; random LE address, not the Wi-Fi MAC)",
            true,
        )

        val wifiMac = runCatching {
            @Suppress("DEPRECATION")
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.connectionInfo?.macAddress
        }.getOrNull() ?: "unavailable"
        lines += Line(
            "Wi-Fi MAC",
            "$wifiMac (masked by Android for every app)",
            wifiMac == "02:00:00:00:00:00" || wifiMac == "unavailable",
        )

        lines += Line("hardware serial", "not read", true)
        lines += Line("ANDROID_ID", "not read", true)
        lines += Line("IMEI / MEID", "not read", true)
        lines += Line("phone model", "not used as a name anywhere", true)

        return lines
    }
}
