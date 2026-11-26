package com.droid.whaly.active

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

@Composable
private fun TrollHeader(title: String, subtitle: String, onBack: () -> Unit) {
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
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrollPanel(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

@Composable
private fun TrollLog(lines: List<String>) {
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
fun IrTrollScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val running by IrTroll.running.collectAsState()
    val count by IrTroll.count.collectAsState()
    val status by IrTroll.status.collectAsState()
    val log by IrTroll.log.collectAsState()
    var mode by remember { mutableStateOf(IrTroll.Mode.VOLUME_MAX) }

    Column(Modifier.fillMaxSize()) {
        TrollHeader("IR Troll", "Five buttons that ruin a television", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TrollPanel {
                    Text(
                        "Each mode encodes the command for every brand itself — NEC, Samsung32, " +
                            "Sony SIRC and Philips RC5 — so it fires at whatever set is in the room.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        IrTroll.Mode.entries.forEach { entry ->
                            FilterChip(
                                selected = mode == entry,
                                onClick = { mode = entry },
                                label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Text(
                        mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (running) IrTroll.stop() else IrTroll.start(context, mode)
                        }) { Text(if (running) "Stop" else "Start") }
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    Text("codes sent: $count", style = MaterialTheme.typography.bodySmall)
                    TrollLog(log)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
fun BleTrollScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val running by BleTroll.running.collectAsState()
    val count by BleTroll.count.collectAsState()
    val status by BleTroll.status.collectAsState()
    val log by BleTroll.log.collectAsState()
    val found by BleTroll.found.collectAsState()
    var mode by remember { mutableStateOf(BleTroll.Mode.NAME_SPAM) }
    var target by remember { mutableStateOf<BleTroll.Found?>(null) }
    val start = PermissionGate(advertisePermissions()) {
        BleTroll.start(context, mode, target)
    }

    Column(Modifier.fillMaxSize()) {
        TrollHeader("BLE Troll", "Five ways to confuse a room", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                TrollPanel {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BleTroll.Mode.entries.forEach { entry ->
                            FilterChip(
                                selected = mode == entry,
                                onClick = { mode = entry },
                                label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                    Text(
                        mode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (running) BleTroll.stop() else start() }) {
                            Text(if (running) "Stop" else "Start")
                        }
                        OutlinedButton(onClick = { BleTroll.scan(context) }) { Text("Scan") }
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall)
                    Text("frames: $count", style = MaterialTheme.typography.bodySmall)
                    TrollLog(log)
                }
            }

            if (mode == BleTroll.Mode.CLONE || mode == BleTroll.Mode.PAIRING_POPUP) {
                item {
                    TrollPanel {
                        Text(
                            if (mode == BleTroll.Mode.CLONE) "Pick the device to clone"
                            else "Pick the device to poke",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (found.isEmpty()) {
                            Text(
                                "Nothing yet — tap Scan.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        found.forEach { device ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        device.name.ifBlank { "(no name)" },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        "${device.address}  ${device.rssi} dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedButton(onClick = { target = device }) {
                                    Text(if (target?.address == device.address) "picked" else "use")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

internal fun hotspotPermissions(): List<String> = buildList {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
    } else {
        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

internal fun advertisePermissions(): List<String> = buildList {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        add(android.Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

@Composable
internal fun PermissionGate(
    permissions: List<String>,
    onGranted: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) onGranted()
    }
    return {
        val missing = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onGranted() else launcher.launch(missing.toTypedArray())
    }
}
