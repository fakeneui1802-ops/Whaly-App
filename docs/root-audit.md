# Access requirements

What each Whaly feature needs from the device. Verified by reading the call sites, not by
assumption: anything that never calls `su`, `ShizukuHelper` or a privileged API is marked
**None**.

## None — stock Android, no root, no Shizuku

| Feature | How it works |
|---|---|
| Platform Spoofer (personas) | `BluetoothLeAdvertiser`, `BluetoothAdapter.setName`, `NsdManager` |
| BLE Attack Lab — Sour Apple, Find My, Fast Pair bomb | `BluetoothLeAdvertiser` with manufacturer/service data |
| GATT Flood | `BluetoothGatt` connect storm, `connectGatt` |
| BLE Spam / Bond Spam | `BluetoothLeAdvertiser`, `createBond` |
| NRF Scanner, Audio Scanner, Scooter Hack | `BluetoothLeScanner`, `BluetoothGatt` |
| Scanned Devices | `BluetoothLeScanner` + local JSON store |
| IR Flipper remotes, IR Storm, IR Jammer, IR Carrier Sweep | `ConsumerIrManager`, `UsbIrManager` |
| NFC tools, NFC Fuzzer | `NfcAdapter`, `HostApduService`, `IsoDep` |
| QR tools | Camera + ZXing |
| LAN Scanner, Whaly Drop, SmartTV Cast | Sockets, HTTP server, mDNS/SSDP |
| Wi-Fi Attacks hub | `WifiManager`, `ConnectivityManager` — no monitor mode |
| HID keyboard (Blue Ducky) | `BluetoothHidDevice` profile |
| Whaly Chat | BLE advertising + GATT |
| Updates — download | `HttpURLConnection` |

## Shizuku — optional, only unlocks the fast path

| Feature | With Shizuku | Without |
|---|---|---|
| Updates — install | `pm install -S` as shell uid, no Play Protect dialog | `PackageInstaller` session, user confirms |
| Package verifier toggle | `settings put global …` | unavailable |
| Plugin `shizuku.exec` | shell commands | falls back to root, then refuses |
| Plugin `shell` API | same | same |

## Root — required

| Feature | Why |
|---|---|
| Bluetooth Jammer | `l2ping` / `hcitool` binaries, L2CAP flood, `killall` |
| Updates — root route | `pm install` as uid 0 when Shizuku is absent |
| Plugins that call `root.*` | `su` shell |

## Notes

- The jammer is the only built-in feature that refuses to run without root. Its card carries
  a root badge in the catalog.
- Shizuku is not root: it runs the shell uid 2000, which holds `INSTALL_PACKAGES`,
  `WRITE_SECURE_SETTINGS` and `BLUETOOTH_CONNECT` but cannot touch `/data` outside the shell's
  own sandbox.
- Every other feature in the offensive package is plain Android API surface. Nothing in
  `offensive/` imports `ShizukuHelper` or spawns `su`.
