package com.droid.whaly.scanned

import android.bluetooth.BluetoothAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.droid.whaly.offensive.OffensiveHeader
import com.droid.whaly.scooter.ScooterFrames
import com.droid.whaly.scooter.ScooterReconClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun ScannedDevicesScreen(
    onBack: () -> Unit,
    viewModel: ScannedDevicesViewModel = viewModel(),
) {
    val context = LocalContext.current
    val devices by viewModel.devices.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val error by viewModel.error.collectAsState()
    val scope = rememberCoroutineScope()

    var typeFilter by remember { mutableStateOf<String?>(null) }
    var brandFilter by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<ScannedDevice?>(null) }
    var client by remember { mutableStateOf<ScooterReconClient?>(null) }
    var report by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) { viewModel.load(context) }

    DisposableEffect(Unit) {
        onDispose {
            client?.disconnect()
            client = null
        }
    }

    fun append(line: String) {
        log.add(0, line)
        if (log.size > 200) log.removeAt(log.lastIndex)
    }

    fun connect(device: ScannedDevice) {
        client?.disconnect()
        report = ""
        connected = false
        val radio = BluetoothAdapter.getDefaultAdapter() ?: return
        val remote = try {
            radio.getRemoteDevice(device.address)
        } catch (_: Exception) {
            append("Bad address ${device.address}")
            return
        }
        append("Connecting to ${device.name}…")
        client = ScooterReconClient(
            context = context.applicationContext,
            device = remote,
            listener = object : ScooterReconClient.Listener {
                override fun onServices(text: String, writeUuid: UUID?, notifyCount: Int) {
                    report = text
                    connected = true
                    append("GATT ready — write=${writeUuid ?: "none"}, notify chars=$notifyCount")
                }

                override fun onNotify(uuid: UUID, value: ByteArray) {
                    append("RX ${value.joinToString(" ") { "%02X".format(it) }}")
                }

                override fun onWrite(label: String, ok: Boolean) {
                    append(if (ok) "TX $label ✓" else "TX $label failed")
                }

                override fun onDisconnected(reason: String?) {
                    connected = false
                    append("Disconnected${reason?.let { " ($it)" } ?: ""}")
                }

                override fun onError(message: String) {
                    append("Error: $message")
                }
            },
        )
        client?.connect()
    }

    val grouped = remember(devices) {
        devices
            .groupBy { it.type }
            .toSortedMap()
            .map { (type, list) ->
                type to list.groupBy { it.brand }.toSortedMap().map { (brand, inBrand) ->
                    brand to inBrand.sortedByDescending { it.rssi }
                }
            }
    }

    val brands = grouped.firstOrNull { it.first == typeFilter }?.second.orEmpty()
    val inBrand = brands.firstOrNull { it.first == brandFilter }?.second.orEmpty()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            OffensiveHeader(
                title = "Scanned Devices",
                subtitle = when {
                    brandFilter != null -> "$typeFilter / $brandFilter"
                    typeFilter != null -> "$typeFilter"
                    else -> "${devices.size} devices, ${grouped.size} folders"
                },
                onBack = {
                    when {
                        brandFilter != null -> brandFilter = null
                        typeFilter != null -> typeFilter = null
                        else -> onBack()
                    }
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { if (scanning) viewModel.stop() else viewModel.start(context) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.BluetoothSearching, null, modifier = Modifier.height(18.dp))
                    Spacer(Modifier.height(0.dp))
                    Text(if (scanning) "  Stop scan" else "  Scan 10m")
                }
                OutlinedButton(
                    onClick = {
                        viewModel.clear(context)
                        selected = null
                        brandFilter = null
                        typeFilter = null
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.height(18.dp))
                    Text("  Clear")
                }
            }

            if (error != null) {
                Text(
                    error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }

                if (typeFilter == null) {
                    if (grouped.isEmpty()) {
                        item { EmptyHint("No devices yet — start a scan and walk the room.") }
                    }
                    items(grouped, key = { it.first }) { (type, list) ->
                        FolderRow(
                            title = type,
                            subtitle = "${list.sumOf { it.second.size }} devices · ${list.size} brands",
                            onClick = { typeFilter = type },
                        )
                    }
                } else if (brandFilter == null) {
                    items(brands, key = { it.first }) { (brand, list) ->
                        FolderRow(
                            title = brand,
                            subtitle = "${list.size} devices",
                            onClick = { brandFilter = brand },
                        )
                    }
                } else {
                    items(inBrand, key = { it.address }) { device ->
                        DeviceRow(
                            device = device,
                            selected = selected?.address == device.address,
                            onClick = {
                                selected = device
                                connect(device)
                            },
                        )
                    }
                }

                if (selected != null) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        ReconPanel(
                            device = selected!!,
                            connected = connected,
                            report = report,
                            log = log,
                            onSweep = {
                                scope.launch {
                                    val target = client ?: return@launch
                                    append("Sweep: ${ScooterFrames.rentalSweep.size} frames")
                                    for (frame in ScooterFrames.rentalSweep) {
                                        target.send(frame)
                                        delay(260)
                                    }
                                    append("Sweep finished")
                                }
                            },
                            onSendNinebot = {
                                scope.launch {
                                    val target = client ?: return@launch
                                    for (frame in ScooterFrames.ninebotLock) {
                                        target.send(frame)
                                        delay(260)
                                    }
                                }
                            },
                            onSendTuning = {
                                scope.launch {
                                    val target = client ?: return@launch
                                    for (frame in ScooterFrames.ninebotTuning) {
                                        target.send(frame)
                                        delay(300)
                                    }
                                }
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FolderRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Bluetooth, null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(device.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "${device.address} · ${device.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReconPanel(
    device: ScannedDevice,
    connected: Boolean,
    report: String,
    log: List<String>,
    onSweep: () -> Unit,
    onSendNinebot: () -> Unit,
    onSendTuning: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(device.name, fontWeight = FontWeight.Bold)
            Text(
                if (connected) "GATT open" else "Opening GATT…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSweep, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Send, null, modifier = Modifier.height(16.dp))
                    Text(" Sweep")
                }
                OutlinedButton(onClick = onSendNinebot, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Memory, null, modifier = Modifier.height(16.dp))
                    Text(" Lock")
                }
                OutlinedButton(onClick = onSendTuning, modifier = Modifier.weight(1f)) {
                    Text("Tuning")
                }
            }
            if (report.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text("Services", fontWeight = FontWeight.SemiBold)
                Text(
                    report,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.height(160.dp).verticalScroll(rememberScrollState()),
                )
            }
            if (log.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Traffic", fontWeight = FontWeight.SemiBold)
                Column(modifier = Modifier.height(180.dp).verticalScroll(rememberScrollState())) {
                    log.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
