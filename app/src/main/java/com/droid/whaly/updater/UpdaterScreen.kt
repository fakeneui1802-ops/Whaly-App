package com.droid.whaly.updater

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droid.whaly.offensive.OffensiveHeader
import rikka.shizuku.Shizuku

private const val SHIZUKU_REQUEST = 4242

@Composable
fun UpdaterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by WhalyUpdater.state.collectAsState()

    var shizukuReady by remember { mutableStateOf(SilentInstaller.shizukuReady()) }
    var verifier by remember { mutableStateOf("unknown") }
    var note by remember { mutableStateOf("") }

    fun refresh() {
        shizukuReady = SilentInstaller.shizukuReady()
        verifier = SilentInstaller.verifierState()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            OffensiveHeader("Updates", "Installed build ${WhalyUpdater.currentVersion()}", onBack)
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = when (state.status) {
                                UpdateStatus.IDLE -> "Not checked yet"
                                UpdateStatus.CHECKING -> "Checking GitHub releases..."
                                UpdateStatus.UP_TO_DATE -> "Up to date (${state.latestVersion})"
                                UpdateStatus.AVAILABLE -> "Update available: ${state.latestVersion}"
                                UpdateStatus.DOWNLOADING -> "Downloading ${state.progress}%"
                                UpdateStatus.READY -> "Downloaded, ready to install"
                                UpdateStatus.ERROR -> "Error"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (state.message.isNotBlank() && state.status != UpdateStatus.AVAILABLE) {
                            Text(
                                state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.status == UpdateStatus.DOWNLOADING) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { state.progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (state.status == UpdateStatus.AVAILABLE && state.notes.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                state.notes.take(600),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { WhalyUpdater.check(context) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.status != UpdateStatus.CHECKING && state.status != UpdateStatus.DOWNLOADING,
                ) { Text("Check for updates") }

                Spacer(Modifier.height(8.dp))
                if (state.status == UpdateStatus.AVAILABLE) {
                    Button(
                        onClick = { WhalyUpdater.download(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Download ${state.latestVersion}") }
                }
                if (state.status == UpdateStatus.READY) {
                    Button(
                        onClick = { WhalyUpdater.install(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Install") }
                }

                Spacer(Modifier.height(20.dp))
                Text("Install route", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Play Protect only stops installs that arrive through the package installer " +
                        "session from an unknown source. With Shizuku or root the app installs through " +
                        "the shell uid instead, which carries INSTALL_PACKAGES, so no scan dialog appears.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (Shizuku.pingBinder()) {
                                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                                    Shizuku.requestPermission(SHIZUKU_REQUEST)
                                }
                            } else {
                                note = "Shizuku service not running — start the Shizuku app first"
                            }
                            refresh()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(if (shizukuReady) "Shizuku ready" else "Grant Shizuku") }
                    OutlinedButton(onClick = { refresh() }, modifier = Modifier.weight(1f)) {
                        Text("Re-check")
                    }
                }
                if (note.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Spacer(Modifier.height(20.dp))
                Text("Package verifier", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "package_verifier_enable / verifier_verify_adb_installs — current: $verifier",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { note = SilentInstaller.disableVerifier().detail; refresh() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Turn off") }
                    OutlinedButton(
                        onClick = { note = SilentInstaller.enableVerifier().detail; refresh() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Turn on") }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    "Updates come from github.com/${WhalyUpdater.REPO}/releases. Android only accepts " +
                        "them when the installed build was signed with the same key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
