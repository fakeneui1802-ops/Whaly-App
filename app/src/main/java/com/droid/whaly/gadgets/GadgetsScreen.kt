package com.droid.whaly.gadgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droid.whaly.active.PermissionGate
import com.droid.whaly.active.advertisePermissions
import kotlinx.coroutines.launch

private enum class GadgetTab(val label: String) {
    LAN("LAN"),
    TV("TV"),
    AUDIO("Audio"),
    RADIO("Radio"),
}

@Composable
fun GadgetsScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(GadgetTab.LAN) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column {
                Text("Gadgets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Ten devices in the room that should not exist",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GadgetTab.entries.forEach { entry ->
                FilterChip(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        when (tab) {
            GadgetTab.LAN -> LanTab()
            GadgetTab.TV -> TvTab()
            GadgetTab.AUDIO -> AudioTab()
            GadgetTab.RADIO -> RadioTab()
        }
    }
}

@Composable
private fun Panel(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun LogBox(lines: List<String>) {
    if (lines.isEmpty()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 140.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerLowest,
                RoundedCornerShape(10.dp),
            )
            .padding(10.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            lines.takeLast(40).forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun LanTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mdnsRunning by MdnsPhantom.running.collectAsState()
    val mdnsSent by MdnsPhantom.sent.collectAsState()
    val mdnsLog by MdnsPhantom.log.collectAsState()
    val ssdpRunning by SsdpPhantom.running.collectAsState()
    val ssdpSent by SsdpPhantom.sent.collectAsState()
    val ssdpLog by SsdpPhantom.log.collectAsState()
    val probeAdded by WifiProbeFlood.added.collectAsState()
    val probeLog by WifiProbeFlood.log.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel(
                "mDNS phantom",
                "Announces fake Bonjour services. iPhones, Macs, printers and TVs browse mDNS " +
                    "constantly, so a phantom printer or Apple TV appears in their list within " +
                    "seconds and cannot be opened.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (mdnsRunning) MdnsPhantom.stop()
                        else MdnsPhantom.start(MdnsPhantom.Service.entries)
                    }) { Text(if (mdnsRunning) "Stop" else "Announce all") }
                    OutlinedButton(onClick = {
                        MdnsPhantom.start(listOf(MdnsPhantom.Service.PRINTER, MdnsPhantom.Service.AIRPRINT))
                    }) { Text("Printers only") }
                }
                Text("announcements: $mdnsSent", style = MaterialTheme.typography.bodySmall)
                LogBox(mdnsLog)
            }
        }

        item {
            Panel(
                "SSDP phantom",
                "Fake UPnP root devices. Windows shows them under Network, media players list " +
                    "them as renderers, and none of them answer.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (ssdpRunning) SsdpPhantom.stop() else SsdpPhantom.start()
                    }) { Text(if (ssdpRunning) "Stop" else "Announce") }
                }
                Text("notifications: $ssdpSent", style = MaterialTheme.typography.bodySmall)
                LogBox(ssdpLog)
            }
        }

        item {
            Panel(
                "Wi-Fi probe flood",
                "Fills the saved-network list with hundreds of suggestions. Android then probes " +
                    "for every one of them, and every probe carries the SSID in the clear where " +
                    "Scrapper on another phone can read it.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { WifiProbeFlood.flood(context, 120) } }) {
                        Text("Add 120")
                    }
                    OutlinedButton(onClick = { scope.launch { WifiProbeFlood.clear(context) } }) {
                        Text("Clear")
                    }
                }
                Text("accepted: $probeAdded", style = MaterialTheme.typography.bodySmall)
                LogBox(probeLog)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun TvTab() {
    val scope = rememberCoroutineScope()
    val status by TvHijack.status.collectAsState()
    val log by TvHijack.log.collectAsState()
    val connected by TvHijack.connected.collectAsState()
    val dialLog by DialLauncher.log.collectAsState()
    var ip by remember { mutableStateOf("") }
    var dialIp by remember { mutableStateOf("") }
    var app by remember { mutableStateOf("YouTube") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel(
                "Smart TV remote",
                "Samsung answers on 8001 with no pairing. LG answers on 3000 but shows a prompt " +
                    "on screen that someone has to accept once.",
            ) {
                OutlinedTextField(
                    value = ip,
                    onValueChange = { ip = it.trim() },
                    label = { Text("TV IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { TvHijack.connectSamsung(ip) } }) { Text("Samsung") }
                    OutlinedButton(onClick = { scope.launch { TvHijack.connectLg(ip) } }) { Text("LG") }
                    OutlinedButton(onClick = { TvHijack.disconnect() }) { Text("Drop") }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { scope.launch { TvHijack.hold(TvHijack.Key.VOLUME_UP, 25, 120) } }) {
                        Text("Vol +25")
                    }
                    OutlinedButton(onClick = { scope.launch { TvHijack.hold(TvHijack.Key.CHANNEL_UP, 20, 150) } }) {
                        Text("Ch +20")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { TvHijack.press(TvHijack.Key.MUTE) }) { Text("Mute") }
                    OutlinedButton(onClick = { TvHijack.press(TvHijack.Key.INPUT) }) { Text("Input") }
                    OutlinedButton(onClick = { TvHijack.press(TvHijack.Key.POWER) }) { Text("Power") }
                }
                if (!connected) {
                    Text(
                        "Not connected.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                LogBox(log)
            }
        }

        item {
            Panel(
                "DIAL app launcher",
                "Plain HTTP on 8008, no authentication on most streaming sticks. Launches or " +
                    "closes an app on the target.",
            ) {
                OutlinedTextField(
                    value = dialIp,
                    onValueChange = { dialIp = it.trim() },
                    label = { Text("device IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = app,
                    onValueChange = { app = it },
                    label = { Text("app name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { DialLauncher.launch(dialIp, app) } }) { Text("Launch") }
                    OutlinedButton(onClick = { scope.launch { DialLauncher.stop(dialIp, app) } }) { Text("Stop") }
                }
                Text(
                    DialLauncher.apps.joinToString(", "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LogBox(dialLog)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun AudioTab() {
    val scope = rememberCoroutineScope()
    val sonosLog by SonosTakeover.log.collectAsState()
    val speakers by SonosTakeover.speakers.collectAsState()
    val hueLog by HueRave.log.collectAsState()
    val bridges by HueRave.bridges.collectAsState()
    val hueRunning by HueRave.running.collectAsState()
    val hueStatus by HueRave.status.collectAsState()
    var bridgeIp by remember { mutableStateOf("") }
    var streamUrl by remember { mutableStateOf("http://192.168.1.2:8080/audio.mp3") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel(
                "Speaker takeover",
                "Sonos and any UPnP renderer answer on 1400 with no authentication. Volume to 100, " +
                    "or point it at a stream and press play.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { SonosTakeover.discover() } }) { Text("Find") }
                }
                Text("${speakers.size} speaker(s)", style = MaterialTheme.typography.bodySmall)
                speakers.forEach { speaker ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            speaker,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { scope.launch { SonosTakeover.setVolume(speaker, 100) } }) {
                            Text("100%")
                        }
                        OutlinedButton(onClick = { scope.launch { SonosTakeover.pause(speaker) } }) {
                            Text("Pause")
                        }
                    }
                }
                OutlinedTextField(
                    value = streamUrl,
                    onValueChange = { streamUrl = it },
                    label = { Text("stream URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                speakers.firstOrNull()?.let { speaker ->
                    Button(onClick = { scope.launch { SonosTakeover.play(speaker, streamUrl) } }) {
                        Text("Play on ${speaker.takeLast(6)}")
                    }
                }
                LogBox(sonosLog)
            }
        }

        item {
            Panel(
                "Hue rave",
                "Philips Hue bridge. Pairing needs the physical link button pressed once, then " +
                    "every light cycles the spectrum.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { HueRave.discover() } }) { Text("Discover") }
                }
                bridges.forEach { ip ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            ip,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = {
                            bridgeIp = ip
                            scope.launch { HueRave.pair(ip) }
                        }) { Text("Pair") }
                    }
                }
                OutlinedTextField(
                    value = bridgeIp,
                    onValueChange = { bridgeIp = it.trim() },
                    label = { Text("bridge IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (hueRunning) HueRave.stop() else HueRave.rave() }) {
                        Text(if (hueRunning) "Stop" else "Rave")
                    }
                    OutlinedButton(onClick = { scope.launch { HueRave.allOff() } }) { Text("All off") }
                }
                Text(hueStatus, style = MaterialTheme.typography.bodySmall)
                LogBox(hueLog)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun RadioTab() {
    val midiRunning by BleMidiFlood.running.collectAsState()
    val midiCount by BleMidiFlood.count.collectAsState()
    val midiStatus by BleMidiFlood.status.collectAsState()
    val targets by ProximityAlarm.targets.collectAsState()
    val armed by ProximityAlarm.armed.collectAsState()
    val alarmStatus by ProximityAlarm.status.collectAsState()
    val distance by ProximityAlarm.distance.collectAsState()
    var threshold by remember { mutableStateOf("-75") }
    val startMidi = PermissionGate(advertisePermissions()) { BleMidiFlood.start(emptyList()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel(
                "BLE MIDI flood",
                "Advertises the BLE-MIDI service under rotating names. Every music app that " +
                    "scans for controllers lists them, and none can connect.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (midiRunning) BleMidiFlood.stop() else startMidi() }) {
                        Text(if (midiRunning) "Stop" else "Start")
                    }
                }
                Text(midiStatus, style = MaterialTheme.typography.bodySmall)
                Text("frames: $midiCount", style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            Panel(
                "Proximity alarm",
                "Watches one device. Beeps the moment its signal drops past the threshold or it " +
                    "stops advertising, which is what happens when something is picked up and " +
                    "carried out of range.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { ProximityAlarm.scan() }) { Text("Scan") }
                    OutlinedButton(onClick = { ProximityAlarm.disarm() }) { Text("Disarm") }
                }
                OutlinedTextField(
                    value = threshold,
                    onValueChange = { threshold = it },
                    label = { Text("trip below dBm") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(alarmStatus, style = MaterialTheme.typography.bodySmall)
                if (armed) {
                    Text(
                        "live $distance dBm",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                targets.take(20).forEach { target ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                target.name.ifBlank { "(no name)" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "${target.address}  ${target.rssi} dBm",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = {
                            ProximityAlarm.arm(target.address, threshold.toIntOrNull() ?: -75)
                        }) { Text("watch") }
                    }
                }
            }
        }
    }
}
