package com.droid.whaly.field

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.droid.whaly.ButtonFlow
import com.droid.whaly.ScrollableChipRow

private enum class FieldTab(val label: String) {
    GATT("GATT"),
    UART("UART"),
    CAMERA("Camera"),
    MAG("Magnet"),
    SONIC("Ultrasonic"),
    TRACKER("Tracker"),
}

/**
 * Field tools: everything here works with Wi-Fi and mobile data off.
 * None of it needs root.
 */
@Composable
fun FieldScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(FieldTab.GATT) }

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
                Text("Field", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "GATT, camera, magnetometer, ultrasound, tracking — radios off",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ScrollableChipRow {
            FieldTab.entries.forEach { entry ->
                FilterChip(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        when (tab) {
            FieldTab.GATT -> GattTab()
            FieldTab.UART -> UartTab()
            FieldTab.CAMERA -> CameraTab()
            FieldTab.MAG -> MagTab()
            FieldTab.SONIC -> SonicTab()
            FieldTab.TRACKER -> TrackerTab()
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun rememberPermission(vararg permissions: String): Pair<Boolean, () -> Unit> {
    val context = LocalContext.current
    var granted by remember(permissions.toList()) {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.all { it }
    }
    return granted to { launcher.launch(arrayOf(*permissions)) }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun GattTab() {
    val context = LocalContext.current
    val peers by BleGattExplorer.peers.collectAsState()
    val nodes by BleGattExplorer.nodes.collectAsState()
    val connected by BleGattExplorer.connected.collectAsState()
    val status by BleGattExplorer.status.collectAsState()
    val log by BleGattExplorer.log.collectAsState()
    val (granted, request) = rememberPermission(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    var selected by remember { mutableStateOf<String?>(null) }
    var readUuid by remember { mutableStateOf("") }
    var writeUuid by remember { mutableStateOf("") }
    var writeHex by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FieldPanel(
                "GATT explorer",
                "Every service and characteristic on a device: read, write, notify. The one " +
                    "thing a phone does strictly better than a Flipper, which has no 2.4 GHz radio.",
            ) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                ButtonFlow {
                    Button(onClick = { if (granted) BleGattExplorer.scan() else request() }) { Text("Scan") }
                    OutlinedButton(onClick = { BleGattExplorer.disconnect() }) { Text("Disconnect") }
                }
                Text("${peers.size} device(s)", style = MaterialTheme.typography.bodySmall)
            }
        }

        items(peers.size) { index ->
            val peer = peers[index]
            val isConnected = connected == peer.address
            FieldPanel(peer.name.ifBlank { "(no name)" }, peer.address) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${peer.rssi} dBm${if (isConnected) "  · connected" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        if (granted) {
                            selected = peer.address
                            BleGattExplorer.connect(context, peer.address)
                        } else {
                            request()
                        }
                    }) { Text(if (isConnected) "Reconnect" else "Connect") }
                }
            }
        }

        if (nodes.isNotEmpty()) {
            item {
                FieldPanel(
                    "Characteristics",
                    "${nodes.size} found. Tap read on a characteristic to fetch its value.",
                ) {
                    Text(
                        "connected: ${selected ?: connected ?: "-"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            items(nodes.size) { index ->
                val node = nodes[index]
                FieldPanel(
                    node.characteristicUuid,
                    "service ${node.serviceUuid}",
                ) {
                    Text(
                        "props ${node.properties}${if (node.value.isNotBlank()) "  ·  ${node.value}" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ButtonFlow {
                        OutlinedButton(onClick = { BleGattExplorer.read(node.characteristicUuid) }) { Text("Read") }
                        OutlinedButton(onClick = { BleGattExplorer.subscribe(node.characteristicUuid, true) }) { Text("Notify") }
                        OutlinedButton(onClick = { BleGattExplorer.subscribe(node.characteristicUuid, false) }) { Text("Stop") }
                    }
                }
            }
        }

        item {
            FieldPanel(
                "Manual read / write",
                "Paste a characteristic UUID. Write takes hex bytes, spaces optional.",
            ) {
                OutlinedTextField(
                    value = readUuid,
                    onValueChange = { readUuid = it.trim() },
                    label = { Text("read UUID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { BleGattExplorer.read(readUuid) }) { Text("Read") }
                OutlinedTextField(
                    value = writeUuid,
                    onValueChange = { writeUuid = it.trim() },
                    label = { Text("write UUID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = writeHex,
                    onValueChange = { writeHex = it.uppercase() },
                    label = { Text("hex bytes") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ButtonFlow {
                    Button(onClick = { BleGattExplorer.write(writeUuid, writeHex, true) }) { Text("Write") }
                    OutlinedButton(onClick = { BleGattExplorer.write(writeUuid, writeHex, false) }) { Text("Write NR") }
                }
                FieldLog(log)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun UartTab() {
    val context = LocalContext.current
    val connected by BleGattExplorer.connected.collectAsState()
    val uart by BleGattExplorer.uart.collectAsState()
    val (granted, request) = rememberPermission(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    var text by remember { mutableStateOf("hello") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FieldPanel(
                "Nordic UART",
                "Service 6e400001, RX 6e400002, TX 6e400003. Every ESP32 example exposes it, " +
                    "so this talks to a devboard, a Marauder, or anything with a serial bridge.",
            ) {
                Text(
                    if (connected == null) "not connected — connect from the GATT tab" else "connected $connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("message") },
                    modifier = Modifier.fillMaxWidth(),
                )
                ButtonFlow {
                    Button(
                        onClick = {
                            if (!granted) request() else BleGattExplorer.uartSend("$text\r\n")
                        },
                        enabled = connected != null,
                    ) { Text("Send") }
                    OutlinedButton(onClick = { BleGattExplorer.uartSend("\r\n") }) { Text("Newline") }
                }
                FieldLog(uart)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun CameraTab() {
    val context = LocalContext.current
    val running by CameraFinder.running.collectAsState()
    val peak by CameraFinder.peak.collectAsState()
    val mean by CameraFinder.mean.collectAsState()
    val torch by CameraFinder.torch.collectAsState()
    val status by CameraFinder.status.collectAsState()
    val alerts by CameraFinder.alerts.collectAsState()
    val (granted, request) = rememberPermission(Manifest.permission.CAMERA)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FieldPanel(
                "Hidden camera finder",
                "The rear sensor sees to about 1000 nm, so an 850-940 nm illuminator that is " +
                    "invisible to the eye reads as a bright point. Maximum luma in the centre " +
                    "crop is the meter — a mean would bury a single LED.",
            ) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "peak $peak / 255    mean $mean    alerts $alerts",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 10.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                            RoundedCornerShape(6.dp),
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(peak / 255f)
                            .heightIn(min = 10.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
                    )
                }
                ButtonFlow {
                    Button(onClick = {
                        if (!granted) request()
                        else if (running) CameraFinder.stop(context)
                        else CameraFinder.start(context)
                    }) { Text(if (running) "Stop" else "Start") }
                    OutlinedButton(onClick = { CameraFinder.setTorch(context, !torch) }) {
                        Text(if (torch) "Torch off" else "Torch on")
                    }
                    OutlinedButton(onClick = { CameraFinder.resetPeak() }) { Text("Reset") }
                }
                Text(
                    "Dark room for the IR pass. Torch on and sweep slowly for the lens glint pass.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun MagTab() {
    val context = LocalContext.current
    val running by MagFinder.running.collectAsState()
    val field by MagFinder.microTesla.collectAsState()
    val baseline by MagFinder.baseline.collectAsState()
    val peak by MagFinder.peak.collectAsState()
    val status by MagFinder.status.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FieldPanel(
                "Magnetic finder",
                "Earth's field is 25-65 uT, so the baseline is measured first and only the " +
                    "deviation counts. Studs, speakers, magnets and live conductors all stand " +
                    "out against it. The tone pitch follows the deviation, so a wall can be " +
                    "swept without looking at the screen.",
            ) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "now %.1f uT    baseline %.1f    peak +%.1f".format(field, baseline, peak),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                ButtonFlow {
                    Button(onClick = {
                        if (running) MagFinder.stop() else MagFinder.start(context)
                    }) { Text(if (running) "Stop" else "Start") }
                    OutlinedButton(onClick = { MagFinder.recalibrate() }) { Text("Recalibrate") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun SonicTab() {
    val running by Ultrasonic.running.collectAsState()
    val level by Ultrasonic.level.collectAsState()
    val peak by Ultrasonic.peak.collectAsState()
    val status by Ultrasonic.status.collectAsState()
    val (granted, request) = rememberPermission(Manifest.permission.RECORD_AUDIO)
    var playback by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FieldPanel(
                "Ultrasonic listener",
                "Capture at 48 kHz and multiply by a 12 kHz carrier. That shifts 15-24 kHz down " +
                    "to 3-12 kHz where the ear works — ring modulation, no FFT. Reveals mosquito " +
                    "repellents, failing supplies and ultrasonic tags.",
            ) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    "level %.4f    peak %.4f".format(level, peak),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 10.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                            RoundedCornerShape(6.dp),
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((level * 8f).coerceIn(0f, 1f))
                            .heightIn(min = 10.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("play shifted audio", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(
                        checked = playback,
                        onCheckedChange = {
                            playback = it
                            Ultrasonic.setPlayback(it)
                        },
                    )
                }
                ButtonFlow {
                    Button(onClick = {
                        if (!granted) request()
                        else if (running) Ultrasonic.stop() else Ultrasonic.start()
                    }) { Text(if (running) "Stop" else "Listen") }
                    OutlinedButton(onClick = { Ultrasonic.setShift(12000) }) { Text("12 kHz") }
                    OutlinedButton(onClick = { Ultrasonic.setShift(8000) }) { Text("8 kHz") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun TrackerTab() {
    val targets by TrackerFinder.targets.collectAsState()
    val tracking by TrackerFinder.tracking.collectAsState()
    val watched by TrackerFinder.watched.collectAsState()
    val rssi by TrackerFinder.rssi.collectAsState()
    val status by TrackerFinder.status.collectAsState()
    val (granted, request) = rememberPermission(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            FieldPanel(
                "Tracker finder",
                "Pick a device and the phone becomes a Geiger counter: beep rate and tone follow " +
                    "RSSI. The proximity alarm answers whether something left; this answers where " +
                    "it is. Move slowly and watch the trend — RSSI is path loss, not distance.",
            ) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (tracking) {
                    Text(
                        "$watched   $rssi dBm",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                ButtonFlow {
                    Button(onClick = { if (granted) TrackerFinder.scan() else request() }) { Text("Scan") }
                    OutlinedButton(onClick = { TrackerFinder.stop() }) { Text("Stop") }
                }
            }
        }

        items(targets.size) { index ->
            val target = targets[index]
            FieldPanel(target.name.ifBlank { "(no name)" }, target.address) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${target.rssi} dBm",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        if (granted) TrackerFinder.watch(target.address) else request()
                    }) { Text("Track") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun FieldPanel(title: String, subtitle: String, content: @Composable () -> Unit) {
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
private fun FieldLog(lines: List<String>) {
    if (lines.isEmpty()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 160.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            lines.takeLast(60).forEach {
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
