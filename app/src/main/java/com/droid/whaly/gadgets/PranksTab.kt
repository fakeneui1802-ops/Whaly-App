package com.droid.whaly.gadgets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droid.whaly.ButtonFlow
import com.droid.whaly.active.RogueApEngine

@Composable
internal fun PranksTab() {
    val context = LocalContext.current

    val tvRunning by TvPrank.running.collectAsState()
    val tvMode by TvPrank.mode.collectAsState()
    val tvFired by TvPrank.fired.collectAsState()
    val tvLog by TvPrank.log.collectAsState()

    val wakeRunning by WakeBomb.running.collectAsState()
    val wakeSent by WakeBomb.sent.collectAsState()
    val wakeTargets by WakeBomb.targets.collectAsState()
    val wakeLog by WakeBomb.log.collectAsState()
    var manualMac by remember { mutableStateOf("") }

    val ap by RogueApEngine.ap.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PrankPanel(
                "TV prank",
                "Autonomous. Nothing is held — a mode is started with an interval and keeps " +
                    "firing for as long as it runs. A set that turns itself up one step every " +
                    "few minutes for an hour is worse than a single burst.",
            ) {
                Text(
                    if (tvRunning) "${tvMode.label} · $tvFired fired" else "idle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                ButtonFlow {
                    Button(
                        onClick = {
                            if (tvRunning) TvPrank.stop()
                            else TvPrank.start(context, TvPrank.Mode.VOLUME_CRAWL, 20, 90)
                        },
                    ) { Text(if (tvRunning) "Stop" else "Start crawl") }
                    OutlinedButton(
                        onClick = { TvPrank.start(context, TvPrank.Mode.MUTE_WAR, 10, 40) },
                        enabled = !tvRunning,
                    ) { Text("Mute war") }
                }
                PrankLog(tvLog)
            }
        }

        item {
            PrankPanel(
                "Wake bomb",
                "Magic packets to every MAC on the segment. Six 0xFF bytes then the address " +
                    "sixteen times, UDP 9 and 7. Wakes every sleeping machine that matches.",
            ) {
                Text(
                    "targets: ${wakeTargets.size} · rounds: $wakeSent",
                    style = MaterialTheme.typography.bodySmall,
                )
                ButtonFlow {
                    Button(
                        onClick = {
                            if (wakeRunning) WakeBomb.stop()
                            else WakeBomb.start(context, 5, 3)
                        },
                    ) { Text(if (wakeRunning) "Stop" else "Wake all") }
                    OutlinedButton(onClick = { WakeBomb.scanArp() }) { Text("Scan ARP") }
                    OutlinedButton(onClick = { WakeBomb.clear() }) { Text("Clear") }
                }
                OutlinedTextField(
                    value = manualMac,
                    onValueChange = { manualMac = it.uppercase() },
                    label = { Text("MAC address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = {
                    WakeBomb.addManual(manualMac)
                    manualMac = ""
                }) { Text("Add") }
                PrankLog(wakeLog)
            }
        }

        item {
            PrankPanel(
                "Join QR",
                "The rogue access point has no DNS, so a client cannot be redirected to the " +
                    "portal. A QR solves that: one scan joins the network and opens the page.",
            ) {
                val runningAp = ap
                if (runningAp == null) {
                    Text(
                        "Start the rogue access point first — Active Ops, Portal tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val payload = "WIFI:T:WPA;S:${runningAp.ssid};P:${runningAp.passphrase};H:false;;"
                    val bitmap = remember(payload) {
                        com.droid.whaly.qr.QrGenerator.generateQrCode(payload, 720)
                    }
                    bitmap?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Wi-Fi join QR",
                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
                        )
                    }
                    Text(
                        payload,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        runningAp.portalUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        items(TvPrank.Mode.entries.size) { index ->
            val entry = TvPrank.Mode.entries[index]
            PrankPanel(entry.label, entry.note) {
                Button(
                    onClick = {
                        TvPrank.stop()
                        TvPrank.start(context, entry, 20, 120)
                    },
                    enabled = !tvRunning,
                ) { Text("Start") }
            }
        }
    }
}

@Composable
private fun PrankPanel(title: String, subtitle: String, content: @Composable () -> Unit) {
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
private fun PrankLog(lines: List<String>) {
    if (lines.isEmpty()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 140.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(10.dp))
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
