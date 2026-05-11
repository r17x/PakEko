package id.local.transfermonitor.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import id.local.transfermonitor.data.TransportSettings
import id.local.transfermonitor.transport.PondServer
import id.local.transfermonitor.transport.TransportService

@Composable
fun TransportSettingsPanel(pondServer: PondServer) {
    val context = LocalContext.current
    val isRunning by pondServer.isRunning.collectAsState()
    val connectionsInfo by pondServer.connections.collectAsState()
    val clientCount = remember(connectionsInfo) {
        connectionsInfo.optJSONArray("clients")?.length() ?: 0
    }
    val settings = remember { TransportSettings(context) }
    var portText by remember { mutableStateOf(settings.port().toString()) }
    var bindAll by remember { mutableStateOf(settings.bindAll()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Transport Server", style = MaterialTheme.typography.titleMedium)

            // Server toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isRunning) {
                        "Running on port ${pondServer.port}"
                    } else {
                        "Stopped"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Switch(
                    checked = isRunning,
                    onCheckedChange = { checked ->
                        if (checked) {
                            val port = portText.toIntOrNull() ?: TransportSettings.DEFAULT_PORT
                            settings.setPort(port)
                            val startIntent = Intent(context, TransportService::class.java).apply {
                                action = TransportService.ACTION_START
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(startIntent)
                            } else {
                                context.startService(startIntent)
                            }
                        } else {
                            val stopIntent = Intent(context, TransportService::class.java).apply {
                                action = TransportService.ACTION_STOP
                            }
                            context.startService(stopIntent)
                        }
                    },
                )
            }

            // Port field
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focusState ->
                        if (!focusState.isFocused) {
                            portText.toIntOrNull()?.let { settings.setPort(it) }
                        }
                    },
                label = { Text("Port") },
                singleLine = true,
                enabled = !isRunning,
            )

            // Bind mode
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Bind:", style = MaterialTheme.typography.bodyMedium)
                FilterChip(
                    selected = bindAll,
                    onClick = {
                        if (!isRunning) {
                            bindAll = true
                            settings.setBindAll(true)
                        }
                    },
                    label = { Text("LAN") },
                    enabled = !isRunning,
                )
                FilterChip(
                    selected = !bindAll,
                    onClick = {
                        if (!isRunning) {
                            bindAll = false
                            settings.setBindAll(false)
                        }
                    },
                    label = { Text("Loopback") },
                    enabled = !isRunning,
                )
            }

            // Client count
            if (isRunning) {
                Text(
                    text = "$clientCount client${if (clientCount == 1) "" else "s"} connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
