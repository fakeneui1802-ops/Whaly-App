package com.droid.whaly.ghost

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
import com.droid.whaly.ButtonFlow

/**
 * Popup spam and identity hygiene.
 *
 * The adapter name is read-only here. Nothing on this screen, and nothing behind it, writes
 * `BluetoothAdapter.setName`; the owner's device name stays exactly as it was.
 */
@Composable
fun GhostScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val running by GhostSpam.running.collectAsState()
    val count by GhostSpam.count.collectAsState()
    val flavour by GhostSpam.flavour.collectAsState()
    val log by GhostSpam.log.collectAsState()

    var realName by remember { mutableStateOf(GhostIdentity.realName(context) ?: GhostIdentity.remember(context)) }
    var sample by remember { mutableStateOf(GhostIdentity.randomName()) }
    var audit by remember { mutableStateOf(IdentityAudit.run(context)) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Text("Ghost", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Popup spam with nothing identifying on air",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            GhostPanel(
                "Identity",
                "The real adapter name is read once and stored. No screen writes it. " +
                    "Advertising runs with no local name at all, so no 0x09 record is generated " +
                    "and the phone's name cannot appear in a scan, a popup or a log.",
            ) {
                Text(
                    "vault: ${realName ?: "not stored yet"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    "name in advertisement: no",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "adapter writes: none",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                ButtonFlow {
                    OutlinedButton(onClick = { sample = GhostIdentity.randomName() }) {
                        Text("New random name")
                    }
                    OutlinedButton(onClick = {
                        realName = GhostIdentity.remember(context)
                        audit = IdentityAudit.run(context)
                    }) { Text("Re-read") }
                }
                Text(
                    "local alias: $sample",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            GhostPanel(
                "Identity audit",
                "What leaves the phone, read at runtime. Each line is either a value the " +
                    "platform exposes or a field that is not read at all.",
            ) {
                audit.forEach { line ->
                    Column {
                        Text(
                            line.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            line.value,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (line.safe) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
                OutlinedButton(onClick = { audit = IdentityAudit.run(context) }) {
                    Text("Re-read")
                }
            }
        }

        item {
            GhostPanel(
                "Popup spam",
                "Android, Windows and Apple each raise a device sheet from the payload alone. " +
                    "Fast Pair and Continuity resolve the product name from the model ID, which " +
                    "is why a popup appears without a name being transmitted.",
            ) {
                Text(
                    if (running) "${flavour.label} · $count sent" else "idle",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(
                    onClick = { if (running) GhostSpam.stop() else GhostSpam.start(GhostSpam.Flavour.MIXED) },
                ) { Text(if (running) "Stop" else "Start all three") }
                GhostLog(log)
            }
        }

        items(GhostSpam.Flavour.entries.size) { index ->
            val entry = GhostSpam.Flavour.entries[index]
            GhostPanel(entry.label, entry.note) {
                Button(
                    onClick = {
                        GhostSpam.stop()
                        GhostSpam.start(entry)
                    },
                    enabled = !running,
                ) { Text("Start") }
            }
        }
    }
}

@Composable
private fun GhostPanel(title: String, subtitle: String, content: @Composable () -> Unit) {
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
private fun GhostLog(lines: List<String>) {
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
