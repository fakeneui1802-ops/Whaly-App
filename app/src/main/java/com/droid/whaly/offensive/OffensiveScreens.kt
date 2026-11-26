package com.droid.whaly.offensive

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun OffensiveHeader(title: String, subtitle: String, onBack: () -> Unit) {
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
fun NfcFuzzScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val frames by NfcFuzzEngine.frames.collectAsState()
    val log by NfcFuzzEngine.log.collectAsState()
    var selected by remember { mutableStateOf(NfcFuzzMode.APDU_RANDOM) }
    var running by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            OffensiveHeader("NFC Fuzzer", "Hold a card or reader against the phone", onBack)
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                NfcFuzzMode.entries.forEach { mode ->
                    FilterChip(
                        selected = selected == mode,
                        onClick = { selected = mode },
                        label = { Text(mode.label) },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Text(
                    selected.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text("Frames: $frames", style = MaterialTheme.typography.titleMedium)
                Text(log, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (running) {
                            NfcFuzzEngine.stop()
                            running = false
                        } else if (activity != null) {
                            NfcFuzzEngine.start(activity, selected)
                            running = NfcFuzzEngine.isRunning()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (running) "Stop" else "Start")
                }
            }
        }
    }
}

@Composable
fun GattFloodScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val targets by GattFlood.targets.collectAsState()
    val scanning by GattFlood.scanning.collectAsState()
    val attempts by GattFlood.attempts.collectAsState()
    val active by GattFlood.activeTarget.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            OffensiveHeader("GATT Flood", "Connection storm against one peripheral", onBack)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { GattFlood.scan(context) }, enabled = !scanning) {
                    Text(if (scanning) "Scanning..." else "Scan")
                }
                Spacer(Modifier.width(12.dp))
                if (active != null) {
                    Button(onClick = { GattFlood.stop() }) { Text("Stop") }
                }
                Spacer(Modifier.width(12.dp))
                Text("$attempts attempts", style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(targets) { target ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(target.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${target.address}  ${target.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button(onClick = { GattFlood.start(context, target) }) { Text("Flood") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IrFuzzScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val bursts by IrFuzzEngine.bursts.collectAsState()
    val frequency by IrFuzzEngine.frequency.collectAsState()
    val status by IrFuzzEngine.status.collectAsState()
    var running by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            OffensiveHeader("IR Carrier Sweep", "30–62 kHz with random frame lengths", onBack)
            Column(modifier = Modifier.padding(16.dp)) {
                Text("$bursts bursts", style = MaterialTheme.typography.titleMedium)
                Text("Carrier: ${frequency / 1000} kHz", style = MaterialTheme.typography.bodyMedium)
                Text(status, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (running) {
                            IrFuzzEngine.stop()
                            running = false
                        } else {
                            IrFuzzEngine.start(context)
                            running = IrFuzzEngine.isRunning()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (running) "Stop" else "Start")
                }
            }
        }
    }
}

@Composable
fun WhalyDropScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var server by remember { mutableStateOf<WhalyDropServer?>(null) }
    var address by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf(emptyList<String>()) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            OffensiveHeader("Whaly Drop", "Send files from iPhone or any browser", onBack)
            Column(modifier = Modifier.padding(16.dp)) {
                val url = address?.let { "http://$it:${server?.listeningPort ?: 8080}" }
                Text(url ?: "Server stopped", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Open the address in Safari on the other device. Files land in Downloads/WhalyDrop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(16.dp))
                if (server == null) {
                    Button(
                        onClick = {
                            error = null
                            val instance = WhalyDropServer(context)
                            try {
                                instance.start()
                                server = instance
                                address = WhalyDropServer.localAddress()
                                files = instance.targetDirectory().listFiles()?.map { it.name }.orEmpty()
                            } catch (exception: Exception) {
                                error = "Port 8080 busy: ${exception.message}"
                                instance.stop()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start server") }
                } else {
                    Button(
                        onClick = {
                            server?.stop()
                            server = null
                            address = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Stop server") }
                }
                Spacer(Modifier.height(16.dp))
                Text("Received", fontWeight = FontWeight.SemiBold)
                files.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
