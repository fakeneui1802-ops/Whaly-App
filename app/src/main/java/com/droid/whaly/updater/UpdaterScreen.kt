package com.droid.whaly.updater

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droid.whaly.offensive.OffensiveHeader

@Composable
fun UpdaterScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by WhalyUpdater.state.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            OffensiveHeader("Updates", "Installed build ${WhalyUpdater.currentVersion()}", onBack)
            Column(modifier = Modifier.padding(16.dp)) {
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
                Spacer(Modifier.height(16.dp))
                Text(
                    "Updates come from github.com/${WhalyUpdater.REPO}/releases. Android only accepts them when the " +
                        "installed build was signed with the same key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
