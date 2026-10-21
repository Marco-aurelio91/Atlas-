package com.unyx.atlas.feature.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalWifiOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "📡 Contexto atual" (design spec item 34) — what Atlas can actually observe about *right now*:
 * battery and network state, captured fresh the moment this screen opens (see
 * [captureDeviceContext] for why this is on-demand rather than a background capture service).
 * Same human-readable, no-raw-debug-data principle as [MemoryScreen] — no JSON, no table/column
 * names, just plain sentences.
 */
@Composable
fun ContextScreen(onBack: () -> Unit, viewModel: ContextViewModel = hiltViewModel()) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                }
                Text(
                    text = "Contexto atual",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = viewModel::refresh) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = "Atualizar")
                }
            }

            if (viewModel.error != null) {
                Text(
                    text = viewModel.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            val snapshot = viewModel.snapshot
            if (snapshot == null) {
                Text(
                    text = "Carregando…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ContextRow(
                        icon = Icons.Default.BatteryFull,
                        label = "Bateria",
                        value = snapshot.batteryPercent?.let { percent ->
                            "$percent%" + if (snapshot.batteryCharging == true) " · carregando" else ""
                        } ?: "Não disponível",
                    )
                    ContextRow(
                        icon = when (snapshot.network) {
                            NetworkKind.WIFI -> Icons.Default.Wifi
                            NetworkKind.MOBILE_DATA -> Icons.Default.SignalCellularAlt
                            NetworkKind.OTHER -> Icons.Default.Wifi
                            NetworkKind.NONE -> Icons.Default.SignalWifiOff
                        },
                        label = "Rede",
                        value = when (snapshot.network) {
                            NetworkKind.WIFI -> "Conectado ao Wi-Fi"
                            NetworkKind.MOBILE_DATA -> "Usando dados móveis"
                            NetworkKind.OTHER -> "Conectado"
                            NetworkKind.NONE -> "Sem conexão"
                        },
                    )
                    Text(
                        text = "Capturado às ${formatTime(snapshot.capturedAtMillis)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ContextRow(icon: ImageVector, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun formatTime(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
