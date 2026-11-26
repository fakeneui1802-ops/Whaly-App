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
        ChangelogEntry(
            version = "2.9.2",
            intro = "Two corrections from testing against a real Android 13 host.",
            added = emptyList(),
            patched = listOf(
                "Fast Pair service data is the three-byte model id again; the old frame carried a leading zero and trailing noise.",
                "Bluetooth name is written to disk before a persona changes it and restored after a force-stop.",
            ),
            removed = emptyList(),
        ),
        ChangelogEntry(
            version = "2.9.1",
            intro = "Launcher art replaced with the supplied artwork.",
            added = listOf(
                "branding/from_image.py slices a real source image into every icon slot.",
            ),
            patched = listOf(
                "Every density, the adaptive layer, the monochrome layer and the site icon now come from the source file.",
            ),
            removed = emptyList(),
        ),
        ChangelogEntry(
            version = "2.9",
            intro = "Scooter recon, a device tree and a silent install path.",
            added = listOf(
                "Fleet detection for Voi, Lime, Bird, Tier, Dott, Bolt and Okai.",
                "Generic GATT explorer with a service table and traffic log.",
                "CRC16/XMODEM frame builders for Ninebot and Xiaomi.",
                "Rental opcode sweep.",
                "Scanned Devices tree: type, then brand, then device.",
                "Silent installer through Shizuku or root, with a package verifier switch.",
            ),
            patched = emptyList(),
            removed = emptyList(),
        ),
        ChangelogEntry(
            version = "2.8.2",
            intro = "Icon pass matched to the reference art.",
            added = emptyList(),
            patched = listOf(
                "Dome widened at eye level and tapered into the jaw.",
                "Brow is one continuous line; eyes enlarged; baleen cage widened to seven plates.",
                "Fins dropped and thickened, tail lobes fattened, background blocks doubled.",
            ),
            removed = emptyList(),
        ),
        ChangelogEntry(
            version = "2.8.1",
            intro = "Whale blue site palette and the Telegram links restored.",
            added = emptyList(),
            patched = listOf(
                "Every colour in the site stylesheet moved off orange, dark mode included.",
                "Telegram links point at t.me/whaly_app_official again.",
            ),
            removed = emptyList(),
        ),
        ChangelogEntry(
            version = "2.8",
            intro = "First fork release. Dolphy is now Whaly.",
            added = listOf(
                "Persona engine, BLE Attack Lab, GATT Flood, NFC Fuzz, IR Fuzz, Whaly Drop.",
                "In-app updater that installs over the running build.",
            ),
            patched = listOf(
                "Quiet Connect bonds over the LE transport before BR/EDR and retries HID app registration.",
            ),
            removed = listOf(
                "Every Dolphy reference: package, plugin extension, symbols, docs.",
            ),
        ),
    )

    fun forVersion(version: String): ChangelogEntry =
        entries.firstOrNull { it.version == version } ?: entries.first()
}
