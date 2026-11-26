package com.droid.whaly.nfc.ui

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.droid.whaly.nfc.MifareCracker
import java.io.File

@Composable
fun MifareScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val running by MifareCracker.running.collectAsState()
    val log by MifareCracker.log.collectAsState()
    val dump by MifareCracker.dump.collectAsState()
    val progress by MifareCracker.progress.collectAsState()
    var armed by remember { mutableStateOf(true) }
    var saved by remember { mutableStateOf("") }

    val adapter = remember {
        runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
    }

    DisposableEffect(armed, adapter) {
        if (adapter != null && armed && !running) {
            val callback = NfcAdapter.ReaderCallback { tag: Tag -> onTag(context, tag) }
            runCatching {
                adapter.enableReaderMode(
                    activity,
                    callback,
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
            }
        }
        onDispose {
            runCatching { adapter?.disableReaderMode(activity) }
        }
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
                Text("Mifare Classic", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "Key recovery and full card dump",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 160.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (adapter == null) "This phone has no NFC reader."
                            else if (running) "Hold the card still — sector $progress"
                            else "Hold the card against the back of the phone.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "50 default keys plus 10 keys derived from the UID, tried against every " +
                                "sector on both key A and key B. Whatever opens is read block by block.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Android does not expose Crypto1, so nested and darkside are not possible " +
                                "from an app. A card with a non-default key stays locked and the dump " +
                                "says so per sector.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { armed = true }) { Text("Arm reader") }
                            OutlinedButton(onClick = {
                                armed = false
                                MifareCracker.reset()
                                saved = ""
                            }) { Text("Reset") }
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Log", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerLowest,
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(10.dp),
                        ) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                if (log.isEmpty()) {
                                    Text("Nothing yet.", style = MaterialTheme.typography.labelSmall)
                                }
                                log.takeLast(60).forEach {
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
                }
            }

            dump?.let { result ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                "UID ${result.uid}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                "${result.coverage}/${result.total} keys recovered · ${result.sectors.size} sectors",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    saved = saveDump(context, result)
                                }) { Text("Save .mfd") }
                                OutlinedButton(onClick = {
                                    saved = saveText(context, result)
                                }) { Text("Save .txt") }
                            }
                            if (saved.isNotBlank()) {
                                Text(
                                    saved,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                items(result.sectors.size) { index ->
                    val sector = result.sectors[index]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "sector ${sector.index}  A=${sector.keyA ?: "—"}  B=${sector.keyB ?: "—"}",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = if (sector.keyA != null || sector.keyB != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            sector.blocks.forEachIndexed { offset, block ->
                                Text(
                                    "  $offset  $block",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun onTag(context: Context, tag: Tag) {
    if (MifareCracker.running.value) return
    Thread({
        MifareCracker.crack(tag) { }
    }, "whaly-mifare").start()
}

private fun saveDump(context: Context, dump: MifareCracker.Dump): String = try {
    val dir = File(context.getExternalFilesDir(null), "mifare").apply { mkdirs() }
    val file = File(dir, "card-${dump.uid}.mfd")
    file.writeBytes(MifareCracker.toMfd(dump))
    "saved ${file.absolutePath}"
} catch (error: Exception) {
    "save failed: ${error.message}"
}

private fun saveText(context: Context, dump: MifareCracker.Dump): String = try {
    val dir = File(context.getExternalFilesDir(null), "mifare").apply { mkdirs() }
    val file = File(dir, "card-${dump.uid}.txt")
    file.writeText(MifareCracker.toText(dump))
    "saved ${file.absolutePath}"
} catch (error: Exception) {
    "save failed: ${error.message}"
}
