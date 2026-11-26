package com.droid.whaly.offline

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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class OfflineTab(val label: String) {
    CARDS("NFC cards"),
    IR("IR menus"),
    SONIC("Sonic"),
    FLASH("Flash"),
}

/**
 * Offline toolkit: three things that work with the radios off.
 *
 * NFC card emulation is a public API (HCE). IR is ConsumerIrManager or a USB blaster.
 * Sonic is an AudioTrack. No network, no mobile data, no root anywhere in this screen.
 */
@Composable
fun OfflineScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(OfflineTab.CARDS) }

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
                Text("Offline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Airplane mode tools — no Wi-Fi, no data, no root",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OfflineTab.entries.forEach { entry ->
                FilterChip(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    label = { Text(entry.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        when (tab) {
            OfflineTab.CARDS -> CardsTab()
            OfflineTab.IR -> IrTab()
            OfflineTab.SONIC -> SonicTab()
            OfflineTab.FLASH -> FlashTab()
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

// ---------------------------------------------------------------------------------------------

@Composable
private fun CardsTab() {
    val context = LocalContext.current
    val active by PrankCards.active.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel(
                "Prank cards",
                "The phone presents itself as a Type-4 NFC tag carrying a URL. Tap it on any " +
                    "unlocked phone and that phone's browser opens the link. Card emulation is a " +
                    "public Android API, so this runs on a stock device with the radios off.",
            ) {
                active?.let { card ->
                    Text(
                        "armed: ${card.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        card.url,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } ?: Text(
                    "not armed — nothing is presented",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { PrankCards.clear(context) }) { Text("Disarm") }
            }
        }

        items(PrankCards.cards.size) { index ->
            val card = PrankCards.cards[index]
            Panel(card.name, card.note) {
                Text(
                    card.url,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { PrankCards.select(context, card) }) { Text("Arm") }
                }
            }
        }

        item {
            Panel(
                "How the reader sees it",
                "The service answers the NDEF Type-4 AID D2760000850101 and serves a capability " +
                    "container plus the NDEF file. SELECT, then READ BINARY with an offset, and the " +
                    "tag hands back the whole record.",
            ) {
                Text(
                    "D2760000850101  →  E103  →  URL record",
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
private fun IrTab() {
    val context = LocalContext.current
    val running by IrServiceMenu.running.collectAsState()
    val sent by IrServiceMenu.sent.collectAsState()
    val log by IrServiceMenu.log.collectAsState()
    val available = remember { com.droid.whaly.IrBackend.isAvailable(context) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel(
                "Service menus",
                "Factory sequences the remote is not supposed to reach. Samsung takes INFO, " +
                    "MENU, MUTE, POWER. LG takes MENU twice then 1105. Sony takes DISPLAY, 5, " +
                    "VOL+, POWER.",
            ) {
                Text(
                    if (available) "IR emitter present" else "no IR emitter — USB blaster or plugin required",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text("keys sent: $sent", style = MaterialTheme.typography.bodySmall)
                if (running) {
                    OutlinedButton(onClick = { IrServiceMenu.stop() }) { Text("Stop") }
                }
                LogBox(log)
            }
        }

        items(IrServiceMenu.Target.entries.size) { index ->
            val target = IrServiceMenu.Target.entries[index]
            Panel(target.label, target.note) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { IrServiceMenu.run(context, target) },
                        enabled = !running,
                    ) { Text("Send") }
                    OutlinedButton(
                        onClick = { IrServiceMenu.run(context, target, gapMs = 120L, rounds = 3) },
                        enabled = !running,
                    ) { Text("×3") }
                }
            }
        }

        item {
            Panel(
                "Mute sweep",
                "Cycles the Samsung, LG and Sony mute codes until stopped. Every set in line of " +
                    "sight goes quiet and stays quiet.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { IrServiceMenu.muteSweep(context) }, enabled = !running) {
                        Text("Start")
                    }
                    OutlinedButton(onClick = { IrServiceMenu.stop() }) { Text("Stop") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun SonicTab() {
    val running by SonicTroll.running.collectAsState()
    val mode by SonicTroll.mode.collectAsState()
    val status by SonicTroll.status.collectAsState()
    var level by remember { mutableFloatStateOf(0.92f) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel(
                "Speaker only",
                "Everything here is generated into an AudioTrack at 48 kHz. Nothing is transmitted " +
                    "and nothing needs a permission. Above 20 kHz most of the energy is lost in the " +
                    "speaker, but enough survives to be heard by children, dogs and cats — and MEMS " +
                    "microphones demodulate the band down into the audible range.",
            ) {
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text(
                    SonicTroll.describe(),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("level ${(level * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(value = level, onValueChange = { level = it }, valueRange = 0.1f..1f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (running) SonicTroll.stop() else SonicTroll.start(mode, level) },
                    ) { Text(if (running) "Stop" else "Start ${mode.label}") }
                }
            }
        }

        items(SonicTroll.Mode.entries.size) { index ->
            val entry = SonicTroll.Mode.entries[index]
            Panel(entry.label, entry.note) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        SonicTroll.stop()
                        SonicTroll.start(entry, level)
                    }) { Text("Play") }
                    if (running && mode == entry) {
                        OutlinedButton(onClick = { SonicTroll.stop() }) { Text("Stop") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------

@Composable
private fun FlashTab() {
    val context = LocalContext.current
    val running by TorchStrobe.running.collectAsState()
    val pattern by TorchStrobe.pattern.collectAsState()
    val status by TorchStrobe.status.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Panel(
                "Torch patterns",
                "CameraManager.setTorchMode needs no permission and no root. Slow patterns are a " +
                    "nuisance; 10 and 15 Hz in a dark room is a strobe, and several phones doing " +
                    "it at once is worse.",
            ) {
                Text(status, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { TorchStrobe.stop(context) }, enabled = running) { Text("Stop") }
                }
            }
        }

        items(TorchStrobe.Pattern.entries.size) { index ->
            val entry = TorchStrobe.Pattern.entries[index]
            Panel(entry.label, entry.note) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        TorchStrobe.stop(context)
                        TorchStrobe.start(context, entry)
                    }) { Text("Start") }
                    if (running && pattern == entry) {
                        OutlinedButton(onClick = { TorchStrobe.stop(context) }) { Text("Stop") }
                    }
                }
            }
        }
    }
}
