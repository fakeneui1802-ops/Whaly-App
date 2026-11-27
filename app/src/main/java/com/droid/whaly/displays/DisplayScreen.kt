package com.droid.whaly.displays

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.droid.whaly.ButtonFlow

/**
 * Displays: projectors, panels, whiteboards.
 *
 * The LAN pass needs Wi-Fi on the same network. The BLE pass works with Wi-Fi off.
 */
@Composable
fun DisplayScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val results by DisplayScanner.results.collectAsState()
    val scanning by DisplayScanner.scanning.collectAsState()
    val status by DisplayScanner.status.collectAsState()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) DisplayScanner.scanBle()
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
                Column {
                    Text("Displays", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Projectors, panels and whiteboards — Kindermann and the rest",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            DisplayPanel(
                "Scan",
                "mDNS browses Google Cast, AirPlay, RAOP, IPP, PJLink and generic display " +
                    "services. SSDP sends an M-SEARCH and reads the LOCATION header. BLE reads " +
                    "advertised names. The LAN pass needs Wi-Fi on the same network; the BLE " +
                    "pass runs with Wi-Fi off.",
            ) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                ButtonFlow {
                    Button(
                        onClick = {
                            if (scanning) DisplayScanner.stop() else DisplayScanner.scanLan(context)
                        },
                    ) { Text(if (scanning) "Stop" else "Scan LAN") }
                    OutlinedButton(
                        onClick = {
                            val missing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                            } else {
                                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
                            }.filter {
                                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (missing.isEmpty()) DisplayScanner.scanBle() else launcher.launch(missing.toTypedArray())
                        },
                    ) { Text("Scan BLE") }
                    OutlinedButton(onClick = { DisplayScanner.stop() }) { Text("Stop") }
                }
                Text(
                    "${results.size} device(s)",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(results.size) { index ->
            val found = results[index]
            DisplayPanel(found.name, "${found.kind.label} · ${found.host}") {
                Text(
                    found.detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DisplayPanel(title: String, subtitle: String, content: @Composable () -> Unit) {
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
