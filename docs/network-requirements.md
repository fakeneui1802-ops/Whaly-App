# Network requirements

What each feature needs from the network. "No network" means the radio does all the work —
Bluetooth, NFC or IR — and the phone can be in airplane mode with the radio back on.

## No network at all

| Feature | Radio |
|---|---|
| Platform Spoofer | BLE advertising + Bluetooth Classic name |
| BLE Attack Lab — Sour Apple, Find My, Fast Pair bomb | BLE advertising |
| GATT Flood | BLE GATT |
| BLE Spam, Bond Spam | BLE advertising, `createBond` |
| NRF Scanner, Audio Scanner | BLE scan |
| Scooter Hack, Scanned Devices, scooter recon | BLE scan + GATT |
| All IR features | `ConsumerIrManager` / USB IR |
| NFC tools, NFC Fuzzer | NFC |
| HID keyboard | Bluetooth HID profile |
| Whaly Chat | BLE advertising + GATT |

E-scooter work is in this group: it is Bluetooth only. Mobile data does nothing for it, and
neither does Wi-Fi. Airplane mode with Bluetooth re-enabled works fine.

## Wi-Fi / LAN only

The device has to be on the same network as the target. Mobile data cannot reach these.

| Feature | Why |
|---|---|
| LAN Scanner | ARP, mDNS, SSDP on the local subnet |
| Router Control Panel | HTTP to the gateway |
| Camera scan + camera stream | RTSP / ONVIF / MJPEG on local addresses |
| Whaly Drop | Browser upload page served on the phone's LAN address |
| SmartTV Cast | SSDP / mDNS discovery, local control port |
| Wi-Fi Attacks hub | `WifiManager` against the connected network |

## Internet

| Feature | Notes |
|---|---|
| Updates | GitHub Releases over HTTPS. Works on Wi-Fi or mobile data. |
| Plugin store | Fetches the catalogue, falls back to the bundled 42-plugin copy offline. Wi-Fi or mobile data. |
| Plugin translation | Optional, calls a translation endpoint. |
| Camera ghost route | The SOCKS5 relay needs to be reachable; Orbot on the same device needs no data, a remote relay does. |

## Summary

- **Bluetooth, NFC, IR:** no network.
- **LAN features:** Wi-Fi only, same subnet, mobile data does not work.
- **Updates, plugin store:** any internet connection.
