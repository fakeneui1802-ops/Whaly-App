package com.droid.whaly.nfc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.droid.whaly.ButtonFlow
import com.droid.whaly.ScrollableChipRow
import com.droid.whaly.nfc.NfcVReader

/**
 * ISO 15693 and ISO 14443-B. Both are supported by the Android NFC stack and neither was
 * reachable anywhere else in the app.
 */
@Composable
fun NfcVScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val adapter = remember {
        runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
    }
    var armed by remember { mutableStateOf(true) }
    var lastTag by remember { mutableStateOf<Tag?>(null) }
    val dump by NfcVReader.dump.collectAsState()
    val log by NfcVReader.log.collectAsState()
    val busy by NfcVReader.busy.collectAsState()
    var blockIndex by remember { mutableStateOf("00") }
    var blockHex by remember { mutableStateOf("") }

    DisposableEffect(armed, adapter) {
        val activity = context.findActivity()
        if (adapter != null && armed && activity != null) {
            val callback = NfcAdapter.ReaderCallback { tag: Tag ->
                lastTag = tag
                NfcVReader.read(tag)
            }
            runCatching {
                adapter.enableReaderMode(
                    activity,
                    callback,
                    NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_V or
                        NfcAdapter.FLAG_READER_NFC_F or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
            }
        }
        onDispose {
            runCatching { activity?.let { adapter?.disableReaderMode(it) } }
        }
    }

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
                Column(Modifier.weight(1f)) {
                    Text(
                        "NFC-V / 14443-B",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "ISO 15693 inventory, block dump, single-block write",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = armed, onCheckedChange = { armed = it })
            }
        }

        item {
            Panel(
                if (armed) "Reader armed" else "Reader off",
                if (adapter == null) {
                    "This phone reports no NFC adapter."
                } else if (armed) {
                    "Hold an ICODE, SLIX or 14443-B card against the phone. The full block " +
                        "map is read in one pass; cards that require authentication first will " +
                        "return an empty map."
                } else {
                    "Turn the switch on to listen again."
                },
            ) {
                Text(
                    if (busy) "reading…" else "waiting",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                ButtonFlow {
                    OutlinedButton(onClick = { NfcVReader.clear() }) { Text("Clear") }
                    OutlinedButton(
                        onClick = {
                            val current = dump ?: return@OutlinedButton
                            copy(context, NfcVReader.export(current))
                        },
                        enabled = dump != null,
                    ) { Text("Copy dump") }
                }
                LogBox(log)
            }
        }

        dump?.let { current ->
            item {
                Panel(current.kind, current.note) {
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Mono("uid", current.uid)
                            Mono("tech", current.techs.joinToString(", "))
                            Mono("dsfid", current.dsfid)
                            Mono("afi", current.afi)
                        }
                        Column(Modifier.weight(1f)) {
                            Mono("block", "${current.blockSize} B")
                            Mono("count", current.blockCount.toString())
                            Mono("ic", current.icReference)
                        }
                    }
                }
            }

            item {
                Panel(
                    "Blocks",
                    "Every block read in addressed mode. Slix and ICODE answer without " +
                        "authentication; MIFARE and DESFire do not.",
                ) {
                    if (current.blocks.isEmpty()) {
                        Text(
                            "no blocks returned",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerLowest,
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(10.dp),
                        ) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                current.blocks.toSortedMap().forEach { (index, hex) ->
                                    Text(
                                        "%02X: %s".format(index, hex),
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

            item {
                Panel(
                    "Write one block",
                    "Slix, SLIX2 and ICODE accept a single-block write with no key. The bytes " +
                        "are sent exactly as typed, so the length has to match the block size.",
                ) {
                    ButtonFlow {
                        OutlinedTextField(
                            value = blockIndex,
                            onValueChange = { blockIndex = it.trim().take(2) },
                            label = { Text("block") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = blockHex,
                            onValueChange = { blockHex = it.uppercase() },
                            label = { Text("hex bytes") },
                            singleLine = true,
                            modifier = Modifier.weight(2f),
                        )
                    }
                    Button(
                        onClick = {
                            val tag = lastTag ?: return@Button
                            val index = blockIndex.toIntOrNull(16) ?: return@Button
                            val bytes = blockHex
                                .replace(" ", "")
                                .chunked(2)
                                .mapNotNull { it.toIntOrNull(16)?.toByte() }
                                .toByteArray()
                            if (bytes.isNotEmpty()) NfcVReader.writeBlock(tag, index, bytes)
                        },
                        enabled = lastTag != null && blockHex.isNotBlank(),
                    ) { Text("Write") }
                }
            }
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
private fun Mono(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
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

private fun copy(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText("Whaly NFC-V dump", text))
}

/** Compose hands out a ContextWrapper in some hosts; reader mode needs the real Activity. */
private fun Context.findActivity(): android.app.Activity? {
    var current: Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is android.app.Activity) return current
        current = current.baseContext
    }
    return current as? android.app.Activity
}
