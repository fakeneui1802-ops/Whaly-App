# Import policy — what came from the feature map, and what did not

The map is a text document. It contains no executable content, so there is nothing in it that
can run. The risk in a list like this is never the list — it is the repository it points at,
and any code lifted from it.

## Rule

**Features are imported as descriptions. Code is not imported at all.**

Every entry that shipped in 3.9 was written from the public protocol specification:

- **ISO/IEC 15693-3** for NFC-V frames — flags, command, parameters, CRC appended by the
  Android stack.
- **ISO/IEC 14443-3** for the 14443-B ATTRIB frame.
- **The Wigle CSV header** for wardriving output, which is a published format.

Nothing was copied from `unvoiddd/Dolphy-App`, `DarkFlippers/unleashed-firmware` or
`flipperdevices/flipperzero-firmware`. No source file, asset, string table, key or binary.

## What a source has to pass before any of it is touched

These are the checks that matter for an Android app in this category, in the order they catch
things:

| Check | What it catches |
|---|---|
| `DexClassLoader`, `PathClassLoader`, `InMemoryDexClassLoader` | Code loaded at runtime from a file or the network — the standard way a "feature" becomes a payload |
| Base64 or hex blobs over ~2 KB in a source file | A DEX, an ELF or a shell script hidden as a string |
| `Runtime.exec` / `ProcessBuilder` with a URL or an encoded argument | Download-and-run |
| Hardcoded IPs and non-standard ports | C2, exfiltration |
| `AccessibilityService` declarations | Screen reading, keystroke capture, silent UI automation |
| `DeviceAdminReceiver` | Lock, wipe, password change |
| Exported components with no `android:permission` | Any app on the device can drive them |
| `android:usesCleartextTraffic` plus an HTTP endpoint | Credentials in the clear |
| `SEND_SMS` / premium shortcodes | Billing fraud |
| `REQUEST_INSTALL_PACKAGES` + a downloaded APK | Sideloading a second stage |
| Reflection into `hidden` APIs | Bypassing the permission model |
| Signature checks or anti-debug | The author does not want the code read |
| `su` invoked without a visible feature that needs it | Silent privilege escalation |

A repository that trips two or more of those is not a feature source, it is a sample.

## What was rejected from the map

Not malware — just things that are wrong or not buildable on a phone, and would have wasted a
release:

- **125 kHz RFID, iButton, Sub-GHz, GPIO, SWD.** No hardware. Needs a dongle or the Flipper.
- **"Extended frequency range (can damage HW)."** Not applicable, and the warning is real:
  the CC1101 is specified to 928 MHz.
- **"USB HID from the phone."** Gadget descriptors are kernel-side. Root only.
- **NFC Type-2 / NTAG215 emulation.** HCE is ISO-DEP only.
- **"Wi-Fi DoS simulation" and "credential brute-force" as described.** A phone on stock
  Android has no monitor mode and no injection, so both would have been a button that logs
  "failed". Whaly's existing Wi-Fi work is honest about what needs root.
- **Transit card parsers** (Troika, Umarsh, Zolotaya Korona). Region-specific, and the useful
  part is already covered by reading the raw records.

## What was kept

Two features, both phone-native, both no root, both with no dongle:

1. **NFC-V / 14443-B** — the half of the Flipper NFC menu the app never opened.
2. **Wardrive** — the Unleashed feature that exists *because* the Flipper has no radio. A
   phone does not need the devboard for it.
