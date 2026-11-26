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
            version = "3.3",
            intro = "Scrapper, plus the Flipper features a phone can actually do.",
            added = listOf(
                "Scrapper: passive 802.11 capture from a monitor interface. Every MAC in range with vendor, every SSID each device probes for, every beacon with channel and encryption, and DNS on open networks. Saves a pcap.",
                "KARMA candidates: the SSIDs neighbouring devices are asking for, ranked. Feed one to the Rogue AP tab and those devices try to join it.",
                "TV-B-Gone: power toggle for 18 television families, encoded here rather than looked up. NEC, Samsung32, SIRC and RC5.",
                "Beacon spam: iBeacon, Eddystone UID, Eddystone URL and AltBeacon with names you pick.",
                "Tracker detector: the other direction. Flags a Find My, SmartTag or Tile frame that keeps showing up while you move.",
            ),
            patched = listOf(
                "Active Ops gained Scrapper and Flipper tabs.",
            ),
            removed = emptyList(),
        ),
        ChangelogEntry(
            version = "3.2",
            intro = "Active Ops: seven new live attacks under one roof.",
            added = listOf(
                "Rogue access point with a sign-in portal. Starts a local-only hotspot, serves a captive page on it, and records every username and password typed into it.",
                "UPnP port hijack. Discovers the router's IGD over SSDP and opens a port forward to this phone through AddPortMapping.",
                "IoT shell sweep. Telnet on 23, 2323 and 2333 across the /24 with 17 default credential pairs, then runs commands on whatever answers.",
                "MQTT broker takeover. Full 3.1.1 CONNECT, SUBSCRIBE # and PUBLISH over TCP, so every smart-home topic lands on the phone.",
                "Print bomb. Finds IPP endpoints on 631 and pushes a job to every one, repeatedly.",
                "Discovery flood. SSDP M-SEARCH plus NOTIFY and mDNS service enumeration at 30 ms, which drops hubs and TVs off the network.",
                "Tracker impersonation. Find My, SmartTag and Tile frames with a rotating identifier, which raises unwanted-tracking alerts on nearby phones.",
                "Root: 802.11 deauthentication flood. Capability probe for monitor mode and mdk4, aireplay-ng or nexutil, then a live deauth run.",
                "Root: traffic intercept. iptables DNAT for DNS and plain HTTP plus a hosts-file pin, with a local responder and a tap that forwards the request onward.",
            ),
            patched = listOf(
                "Active Ops is reachable from Other, one tap from the function list.",
            ),
            removed = emptyList(),
        ),
        ChangelogEntry(
            version = "3.1.3",
            intro = "Camera scan stops chasing the router, and the persona name reaches the host.",
            added = emptyList(),
            patched = listOf(
                "Platform Spoofer now sets the local name inside the advertisement. Windows and macOS read the name from the advertisement, not from the adapter, so the host kept showing the old name.",
                "Quiet Connect bonds before it connects. A HID Device link cannot exist without a link key, and connecting first aborted the bond that was already in flight.",
                "Bonding tries the public API before the hidden int overload, which the non-SDK list blocks on Android 14 and newer.",
                "CamCatch skips the gateway address. A router answers on 80 and 554, so it was picked as a camera and burned the whole password list.",
                "RTSP probe now reads the SDP and requires an m=video track. A bare 200 from a router is no longer reported as a stream.",
            ),
            removed = emptyList(),
        ),
        ChangelogEntry(
            version = "3.1.2",
            intro = "Nothing hides behind the navigation pill any more.",
            added = emptyList(),
            patched = listOf(
                "Persona list, GATT Flood list, Scanned Devices list and the Updates screen all reserve space for the floating navigation pill.",
                "IR Carrier Sweep says what is missing and what to do about it instead of a bare No IR transmitter.",
            ),
            removed = emptyList(),
        ),
        ChangelogEntry(
            version = "3.1.1",
            intro = "Camera results are tappable, and the update log scrolls instead of clipping.",
            added = listOf(
                "Auto plugin updater, official catalogue only. A user-added source can add plugins but never overwrites what is installed.",
                "Shizuku risk dialog listing what the grant actually hands over.",
            ),
            patched = listOf(
                "Tapping a discovered camera opens CamCatch at that exact IP instead of rescanning the subnet.",
                "The release notes box keeps its size and scrolls internally, so the whole changelog is readable.",
                "Ghost route chip moved off the bottom navigation bar to the top-right of the camera screen.",
            ),
            removed = emptyList(),
        ),
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
