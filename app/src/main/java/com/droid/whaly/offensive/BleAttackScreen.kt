package com.droid.whaly.offensive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droid.whaly.Spammer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class BleAttack(val label: String, val detail: String) {
    SOUR_APPLE(
        "Sour Apple",
        "Malformed Nearby Action frames aimed at the iOS Bluetooth stack",
    ),
    FIND_MY(
        "Find My spoof",
        "Rotating tracker keys — every nearby iPhone raises an unknown-tracker alert",
    ),
    FAST_PAIR_BOMB(
        "Fast Pair bomb",
        "Model ids cycled at maximum rate — pairing dialogs stack on Android receivers",
    );

    fun create(): Spammer = when (this) {
        SOUR_APPLE -> SourAppleSpam()
        FIND_MY -> FindMySpoofSpam()
        FAST_PAIR_BOMB -> FastPairBombSpam()
    }
}

object BleAttackLab {
    private val running = mutableMapOf<BleAttack, Spammer>()
    private val _active = MutableStateFlow<Set<BleAttack>>(emptySet())
    val active: StateFlow<Set<BleAttack>> = _active.asStateFlow()

    fun toggle(attack: BleAttack) {
        val existing = running[attack]
        if (existing != null) {
            existing.stop()
            running.remove(attack)
        } else {
            val spammer = attack.create()
            running[attack] = spammer
            spammer.start()
        }
        _active.value = running.keys.toSet()
    }

    fun stopAll() {
        running.values.forEach { it.stop() }
        running.clear()
        _active.value = emptySet()
    }
}

@Composable
fun BleAttackScreen(onBack: () -> Unit) {
    val active by BleAttackLab.active.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            OffensiveHeader("BLE Attack Lab", "Three payload families, one radio", onBack)
            Column(modifier = Modifier.padding(16.dp)) {
                BleAttack.entries.forEach { attack ->
                    val isActive = attack in active
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(attack.label, fontWeight = FontWeight.SemiBold)
                            Text(
                                attack.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row {
                                Button(onClick = { BleAttackLab.toggle(attack) }) {
                                    Text(if (isActive) "Stop" else "Start")
                                }
                            }
                        }
                    }
                }
                if (active.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { BleAttackLab.stopAll() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Stop all")
                    }
                }
            }
        }
    }
}
