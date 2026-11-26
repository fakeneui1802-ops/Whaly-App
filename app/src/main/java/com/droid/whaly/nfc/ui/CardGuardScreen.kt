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
import com.droid.whaly.nfc.CardGuard

@Composable
fun CardGuardScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val running by CardGuard.running.collectAsState()
    val log by CardGuard.log.collectAsState()
    val report by CardGuard.report.collectAsState()
    var armed by remember { mutableStateOf(true) }

    val adapter = remember {
        runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
    }

    DisposableEffect(armed, adapter) {
        if (adapter != null && armed && !running) {
            val callback = NfcAdapter.ReaderCallback { tag: Tag -> onCard(tag) }
            runCatching {
                adapter.enableReaderMode(
                    activity,
                    callback,
                    NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                    null,
                )
            }
        }
        onDispose { runCatching { adapter?.disableReaderMode(activity) } }
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
                Text("Card Guard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "What your contactless card gives away",
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
                Panel {
                    Text(
                        if (adapter == null) "This phone has no NFC reader."
                        else if (running) "Reading the card — hold still"
                        else "Hold the card flat against the back of the phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Reads PPSE, selects the payment application, runs Get Processing Options " +
                            "and walks the records. Everything shown is data the card returned " +
                            "without any authentication.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { armed = true }) { Text("Arm reader") }
                        OutlinedButton(onClick = {
                            armed = false
                            CardGuard.reset()
                        }) { Text("Reset") }
                    }
                }
            }

            report?.let { result ->
                item {
                    Panel {
                        Text(
                            "${result.scheme?.name ?: "Unknown scheme"} · ${result.panMasked}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            "expiry ${result.expiry.ifBlank { "—" }} · ${result.panLength} digits",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        result.cardholder?.let {
                            Text("holder $it", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "CVM: ${result.cvm}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "online required: ${if (result.requiresOnline) "yes" else "no"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    Panel {
                        Text(
                            "What this exposes",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        result.warnings.forEach { warning ->
                            Text(
                                "• $warning",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                item {
                    Panel {
                        Text(
                            "Why this cannot pay for anything",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "A payment needs an ARQC: a cryptogram signed with a key that lives " +
                                "in the card's secure element and is known to the issuer. It is " +
                                "generated per transaction from the terminal's unpredictable " +
                                "number and the card's own counter. Reading the PAN and expiry " +
                                "does not produce one. A terminal that receives a card without " +
                                "a valid ARQC declines, and a phone cannot compute one for " +
                                "someone else's account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "That is also why a cloned card fails at a till: the clone has the " +
                                "data but not the key, so the first online authorisation kills it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                item {
                    Panel {
                        Text(
                            "Tags returned",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 220.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerLowest,
                                    RoundedCornerShape(10.dp),
                                )
                                .padding(10.dp),
                        ) {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                result.tags.forEach { (tag, value) ->
                                    Text(
                                        "$tag  $value",
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
                Panel {
                    Text(
                        "Skimmer detection, honestly",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "A skimmer is a physical overlay on a reader or an ATM slot. It has no " +
                            "radio signature an app can recognise — it is the same NFC field with " +
                            "extra hardware behind it. What you can check is the reader itself: " +
                            "pull the card slot, look for a loose faceplate, compare the reader " +
                            "to the one next to it, and cover the keypad.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "What this screen does give you: the exact list of what your own card " +
                            "hands out, so you know what a skimmer would have taken.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Panel {
                    Text("Log", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
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
                            log.takeLast(50).forEach {
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
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

private fun onCard(tag: Tag) {
    if (CardGuard.running.value) return
    Thread({
        CardGuard.read(tag) { }
    }, "whaly-card-guard").start()
}
