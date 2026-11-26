# Capability matrix — Flipper firmware, and Android with and without root

Three separate axes, and people constantly collapse them into one:

1. **Which firmware** — stock, Unleashed, Momentum, RogueMaster.
2. **Which privileges** — no root, root.
3. **Which network** — Wi-Fi, mobile data, airplane mode.

Firmware is not hardware. No custom firmware adds a radio the board does not have. The
Flipper has **no Bluetooth and no Wi-Fi at all** — both come from the ESP32 Wi-Fi devboard
module. That single fact invalidates a large part of the marketing lists.

---

# Part 1 — Flipper Zero hardware, fixed

| Radio | Chip | Bands |
|---|---|---|
| Sub-GHz | TI CC1101 | 300–348, 387–464, 779–928 MHz |
| LF RFID | discrete analog front end | 125 kHz |
| HF RFID / NFC | ST25R3916 | 13.56 MHz |
| IR | 3× TX LED + TSOP75338 | 850–950 nm, 38 kHz RX |
| iButton | pogo pins, 1-Wire | — |
| USB | Type-C, device mode | HID, CDC, DFU |

**Not on the board:** Wi-Fi, Bluetooth, GPS, cellular, microphone, camera.

Every "BLE HID", "BLE spam", "BLE BadKB", "mobile app sync over BLE" line in the ecosystem
lists is the **Wi-Fi devboard** doing the work over the 18-pin header. The Flipper itself
cannot see or emit a 2.4 GHz signal.

---

# Part 2 — Firmware differences

## Stock (official)

Everything below is real and supported.

- **Sub-GHz**: Read, Read RAW, Frequency Analyzer, Saved signals, Add Manually, TX power
  control. **Transmit is region-locked** to the legal band for the detected region, and
  restricted frequencies are refused.
- **NFC**: Read, save, emulate, write; MIFARE Classic dictionary attack and nested attack;
  mfkey32 key extraction; Ultralight/NTAG; ISO15693; FeliCa; EMV public data; magic card
  support; NDEF editor.
- **125 kHz**: Read, Read RAW, save, emulate, write (T5577, EM4305), wipe/unlock/password.
- **IR**: Learn, saved remotes, universal remotes for TV / AC / audio / projector / fan /
  LED / camera / monitor / DVD / set-top, RAW capture and replay, protocol decode.
- **iButton**: Read, save, emulate, write.
- **BadUSB**: DuckyScript 1.0, US and a set of layouts.
- **U2F**: hardware second factor over USB.
- **GPIO**: UART, SPI, I2C, 1-Wire, PWM, ADC, 3.3 V and 5 V rails, SWD via external probe.
- **JS**: mJS scripting for GPIO, Sub-GHz, BadUSB, dialogs, storage.
- **File manager, hex viewer, music player, QR/barcode generator, TOTP.**

Stock does **not** have: rolling-code brute force, Sub-GHz playlist or chat, extended
DuckyScript 3.0 commands, NFC fuzzer, iButton fuzzer, Sub-GHz transmit outside the legal
region.

## Unleashed

Based on stock, with the safety rails removed and the protocol tables extended.

- **Sub-GHz region lock removed.** Transmit on any band the CC1101 covers, at full +10 dBm,
  in any region. This is the single biggest practical difference and it is also the one that
  puts you outside type approval.
- Extended protocol list — more fixed-code families, more weather stations, more TPMS.
- **Extended DuckyScript** — `ALTCHAR`, `ALTSTRING`, `SYSRQ`, `HOLD`, `RELEASE`, `MEDIA`,
  `MOUSE`, plus more keyboard layouts.
- BadKB over the Wi-Fi devboard's BLE.
- NFC: additional tools and magic-card handling; darkside-style recovery appears in some
  builds and is unreliable on modern cards.
- iButton and NFC fuzzers.
- Custom dolphin assets, animations, fonts, no boot animation restrictions.

## Momentum

Fork of Unleashed with a UI layer and bundled apps.

- Everything Unleashed does.
- Momentum settings app, dark mode, custom status bar, file browser improvements.
- Bundled FAPs: Sub-GHz spectrum analyzer, Sub-GHz brute forcer, Sub-GHz playlist, NFC
  magic tools, clock, password generator, more games.
- More aggressive Sub-GHz protocol set and a built-in signal replay queue.

## RogueMaster / Xtreme

Kitchen-sink forks of Unleashed. Everything above plus experimental apps and community
FAPs preinstalled. Higher crash rate, slower release cadence, more likely to break state
between updates. Use only if a specific bundled app is worth it.

## What no firmware can do

- Add Bluetooth, Wi-Fi, GPS or cellular.
- Make the CC1101 hear below 300 MHz or above 928 MHz.
- Transmit on two Sub-GHz frequencies at once.
- Read a 125 kHz card without the card touching the pad.
- Break AES rolling codes (Faac SLH, Hörmann BiSecur, Security+ 2.0).
- Emulate a Type-2 NFC tag as a phone would need to.
- Increase the ST25R3916 field strength beyond its coil.

---

# Part 3 — Android, no root vs root

## No root — what actually works

| Capability | API | Notes |
|---|---|---|
| NFC read / write / emulate | `NfcAdapter`, `NfcA`, `IsoDep`, `HostApduService` | Emulation is **ISO-DEP only** (14443-4) |
| MIFARE Classic auth + mfkey32 | `MifareClassic`, `NfcA.transceive` | Nested and darkside impossible — no parity control |
| IR transmit | `ConsumerIrManager` | Raw patterns only, and only if the phone has an emitter |
| BLE scan / advertise / GATT | `BluetoothLeScanner`, `BluetoothLeAdvertiser` | Needs Nearby Devices permission on 12+ |
| BLE HID | `BluetoothHidDevice` | Public since API 28 |
| USB host to a dongle | `UsbManager` + `UsbSerial` | Proxmark3, CC1101 bridge, FT232H, CMSIS-DAP |
| LAN multicast | `DatagramSocket`, mDNS, SSDP | Same subnet only |
| Wi-Fi scanning | `WifiManager` | Location permission required |
| Wi-Fi suggestions | `WifiNetworkSuggestion` | Android 10+ |
| Torch | `CameraManager.setTorchMode` | No permission |
| Audio generation | `AudioTrack` | Full 48 kHz band |
| Local HTTP / WebSocket | `ServerSocket`, Ktor | LAN only |

## No root — what is impossible, and why

| Blocked | Reason |
|---|---|
| Monitor mode, packet capture | No `CAP_NET_RAW`, no driver interface |
| Packet injection, deauth | Same |
| MAC address change | `CAP_NET_ADMIN` |
| ARP / DNS spoofing | Raw sockets and `iptables` |
| Reading another app's data | App sandbox |
| USB gadget HID from the phone | Gadget descriptors come from the kernel |
| Custom USB VID/PID | Same |
| System CA install | User CA store only, apps opt in |
| Toggling airplane mode silently | `WRITE_SECURE_SETTINGS` |
| Kernel modules | No `insmod` |

## Root — what unlocks

- **Raw sockets** (`CAP_NET_RAW`) — packet capture, injection, deauth, beacon spam.
- **Monitor mode** — only with a driver that supports it. The phone's own Wi-Fi chip
  usually does not; Nexmon covers a short list of Broadcom devices, and some Qualcomm and
  MediaTek chips accept vendor test-mode commands. Otherwise: external adapter over OTG.
- **`iptables`** — transparent redirect, captive portal on port 53, traffic intercept.
- **`hostapd`** — a real access point with a real DHCP server, so a portal can auto-redirect.
- **`ip link set`** — MAC change, monitor interface creation.
- **USB gadget configfs** — present the phone as a keyboard, NIC or U2F token.
- **System CA store** — intercept TLS from other apps.
- **`/data` read** — app databases, shared prefs, tokens.
- **`WRITE_SECURE_SETTINGS`** — airplane mode, ADB, developer settings without a tap.

Root does **not** give you: an IR emitter the phone lacks, an NFC field stronger than the
coil, 125 kHz, Sub-GHz, or a Wi-Fi chip that cannot do monitor mode.

---

# Part 4 — Network state

## Airplane mode (radios off)

Works: NFC, IR, BLE if re-enabled manually, audio, torch, USB OTG, all local file work.

Nothing that needs a peer on a network.

## Wi-Fi

Everything LAN-side works: mDNS and SSDP discovery and phantom announcements, ARP scans,
port sweeps, UPnP and SOAP control, DIAL, Sonos, Hue, MQTT, IoT telnet, HTTP portals, Wi-Fi
suggestions, captive portal detection.

Also required for: monitor mode, packet capture, deauth, rogue AP — none of which work over
mobile data, because mobile data has no layer 2 broadcast domain.

## Mobile data

Internet only. No LAN peers, no multicast, no broadcast, no ARP. Useful for: update checks,
CVE lookups, IP geolocation, cloud APIs, downloading payloads.

A phone on mobile data cannot see, scan, spoof or attack any device in the room.

---

# Part 5 — Whaly, cell by cell

| Feature | Root | Network |
|---|---|---|
| NFC read / write / emulate | no | none |
| Prank NFC cards (HCE) | no | none |
| MIFARE Classic + mfkey32 | no | none |
| Card Guard (EMV read-only) | no | none |
| IR remotes, IR troll | no | none |
| IR service menus, mute sweep | no | none |
| Sonic troll, torch strobe | no | none |
| BLE scan, spam, HID, troll | no | none |
| BLE proximity alarm | no | none |
| USB OTG dongles | no | none |
| mDNS / SSDP phantoms | no | Wi-Fi |
| Wi-Fi probe flood | no | Wi-Fi |
| TV remote, DIAL | no | Wi-Fi |
| Speaker takeover, Hue | no | Wi-Fi |
| Rogue AP portal | partial | Wi-Fi |
| UPnP hijack, MQTT, IoT sweep | no | Wi-Fi |
| Discovery flood, print bomb | no | Wi-Fi |
| CamCatch, GhostRoute | no | Wi-Fi |
| Scrapper (monitor mode) | **yes** | Wi-Fi |
| Root deauth, Root intercept | **yes** | Wi-Fi |
| Bluetooth Jammer | **yes** | none |
| Update check, site | no | Wi-Fi or data |
