package com.droid.whaly

/**
 * Structured update log. The dialog after an install reads the entry for the running
 * build, so the version in the title is always the version on the device.
 */
data class ChangelogEntry(
    val version: String,
    val intro: String,
    val added: List<String>,
    val patched: List<String>,
    val removed: List<String>,
)

object Changelog {

    val entries: List<ChangelogEntry> = listOf(
        ChangelogEntry(
            version = "3.1",
            intro = "Update log. Everything below is in this build.",
            added = listOf(
                "Language switch with four options: Русский, English, Deutsch, Español — each named in its own language.",
                "Dolphy plugin catalogue bundled in the app: 42 plugins, listed with a Dolphy Plugin badge.",
                "Camera ghost route: SOCKS5 relay so the camera logs the exit node instead of this phone.",
                "Per-platform personas — iOS, macOS, tvOS, Android, Windows — each with its own discovery frames.",
                "Scanned Devices tree: type, then brand, then every device in range.",
                "Silent installer through Shizuku or root, with a package verifier switch.",
                "Scooter fleet recon: generic GATT explorer, CRC16 frame builders, rental opcode sweep.",
            ),
            patched = listOf(
                "Plugin store returned HTTP 404 after the rebrand. The catalogue URL points at the Dolphy repository again, and a bundled copy is used when the network fails.",
                "Fast Pair frames were malformed, so no pairing sheet ever appeared. Service data is now the three-byte model id.",
                "Bluetooth name stayed spoofed after a force-stop. The original is persisted and restored.",
                "Quiet Connect labels now name the target range: Blue Ducky covers Android 4.2.2 to 13 below the December 2023 patch.",
            ),
            removed = listOf(
                "Dead Telegram links and the placeholder channel handle.",
            ),
        ),
        ChangelogEntry(
            version = "3.0",
            intro = "Platform Spoofer rebuilt around what each operating system actually listens for.",
            added = listOf(
                "PersonaFrame model: manufacturer data and service data handled separately.",
                "AirDrop action frame, Nearby Action 0x0F action 0x01.",
                "Ghost route for camera streams.",
            ),
            patched = listOf(
                "Persona name restore after a crash.",
            ),
            removed = emptyList(),
        ),
    )

    fun forVersion(version: String): ChangelogEntry =
        entries.firstOrNull { it.version == version } ?: entries.first()
}
