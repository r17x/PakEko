package id.local.transfermonitor

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.local.transfermonitor.data.MonitorSettings
import id.local.transfermonitor.data.NotificationEvent
import id.local.transfermonitor.data.PaymentDetection
import id.local.transfermonitor.data.WebhookSettings
import id.local.transfermonitor.util.DebugExport
import id.local.transfermonitor.util.WebhookDispatcher
import id.local.transfermonitor.util.hasNotificationListenerAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as TransferMonitorApp

        setContent {
            TransferMonitorTheme {
                TransferMonitorScreen(
                    database = app.database,
                    openNotificationSettings = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                )
            }
        }
    }
}

@Composable
private fun TransferMonitorTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = Color(0xFF0F766E),
        secondary = Color(0xFFB45309),
        tertiary = Color(0xFF334155),
        background = Color(0xFFF8FAFC),
        surface = Color.White,
        surfaceVariant = Color(0xFFE2E8F0),
    )

    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TransferMonitorScreen(
    database: id.local.transfermonitor.data.TransferDatabase,
    openNotificationSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val webhookSettings = remember { WebhookSettings(context) }
    var events by remember { mutableStateOf<List<NotificationEvent>>(emptyList()) }
    var detections by remember { mutableStateOf<List<PaymentDetection>>(emptyList()) }
    var hasAccess by remember { mutableStateOf(context.hasNotificationListenerAccess()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var webhookUrl by remember { mutableStateOf(webhookSettings.url()) }
    var webhookMessage by remember { mutableStateOf<String?>(null) }
    var pendingWebhookDetection by remember { mutableStateOf<PaymentDetection?>(null) }

    fun refreshData() {
        hasAccess = context.hasNotificationListenerAccess()
        events = database.recentEvents()
        detections = database.recentDetections()
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshData()
            delay(1500)
        }
    }

    pendingWebhookDetection?.let { detection ->
        AlertDialog(
            onDismissRequest = { pendingWebhookDetection = null },
            title = { Text("Issue webhook?") },
            text = {
                Text(
                    text = "${formatIdr(detection.amount)} parsed successfully. Send the configured webhook for this transaction?",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingWebhookDetection = null
                        scope.launch {
                            webhookMessage = "Sending parsed transaction..."
                            val message = withContext(Dispatchers.IO) {
                                val result = WebhookDispatcher.sendConfigured(
                                    context = context,
                                    database = database,
                                    detection = detection,
                                    allowPendingRetry = true,
                                )
                                if (result.sent) {
                                    "Sent parsed transaction: ${result.message}"
                                } else {
                                    "Send failed: ${result.message}"
                                }
                            }
                            refreshData()
                            webhookMessage = message
                        }
                    },
                ) {
                    Text("Send webhook")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingWebhookDetection = null
                        exportMessage = "Parsed ${formatIdr(detection.amount)} locally"
                    },
                ) {
                    Text("Local only")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "PakEko",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusPanel(
                    hasAccess = hasAccess,
                    onOpenSettings = openNotificationSettings,
                )
            }

            item {
                SupportedBankPanel(
                    packageName = MonitorSettings.SUPPORTED_BANK_PACKAGES.single(),
                )
            }

            item {
                WebhookPanel(
                    webhookUrl = webhookUrl,
                    webhookMessage = webhookMessage,
                    onWebhookUrlChanged = { webhookUrl = it },
                    onSaveWebhookUrl = {
                        webhookSettings.setUrl(webhookUrl)
                        webhookMessage = if (webhookUrl.isBlank()) {
                            "Webhook disabled"
                        } else {
                            "Webhook URL saved"
                        }
                    },
                    onSendLatest = {
                        scope.launch {
                            webhookMessage = "Sending latest detection..."
                            val message = withContext(Dispatchers.IO) {
                                val latest = database.latestDetection()
                                    ?: return@withContext "No detection to send yet"
                                webhookSettings.setUrl(webhookUrl)
                                val result = WebhookDispatcher.sendConfigured(
                                    context = context,
                                    database = database,
                                    detection = latest,
                                    allowPendingRetry = true,
                                )
                                if (result.sent) {
                                    "Sent latest detection: ${result.message}"
                                } else {
                                    "Send failed: ${result.message}"
                                }
                            }
                            refreshData()
                            webhookMessage = message
                        }
                    },
                    onRetryWebhooks = {
                        scope.launch {
                            webhookMessage = "Retrying webhook deliveries..."
                            val message = withContext(Dispatchers.IO) {
                                webhookSettings.setUrl(webhookUrl)
                                if (webhookSettings.url().isBlank()) {
                                    return@withContext "Save a webhook URL first"
                                }
                                val result = WebhookDispatcher.retryRecordedDeliveries(context, database)
                                "Retried ${result.attemptedCount}: ${result.sentCount} sent, ${result.failedCount} failed"
                            }
                            refreshData()
                            webhookMessage = message
                        }
                    },
                )
            }

            item {
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Parsed transaction") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Notifications") },
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = {
                                database.deleteAll()
                                exportMessage = null
                                refreshData()
                            }
                        ) {
                            Text("Delete all")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = ::refreshData) {
                            Text("Refresh")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                exportMessage = try {
                                    val result = database.reparseStoredEvents()
                                    refreshData()
                                    "Reparsed ${result.eventCount} events: ${result.detectionCount} detections, ${result.ignoredCount} ignored"
                                } catch (error: Exception) {
                                    "Reparse failed: ${error.message ?: error::class.java.simpleName}"
                                }
                            }
                        ) {
                            Text("Reparse")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = {
                                exportMessage = try {
                                    val result = DebugExport.exportJson(context, database)
                                    "Saved ${result.eventCount} events and ${result.detectionCount} detections to ${result.location}"
                                } catch (error: Exception) {
                                    "Export failed: ${error.message ?: error::class.java.simpleName}"
                                }
                            }
                        ) {
                            Text("Export JSON")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                exportMessage = try {
                                    val result = DebugExport.exportSqliteDatabase(context, database)
                                    "Saved SQLite DB to ${result.location}"
                                } catch (error: Exception) {
                                    "DB export failed: ${error.message ?: error::class.java.simpleName}"
                                }
                            }
                        ) {
                            Text("Export DB")
                        }
                    }
                    exportMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (selectedTab == 0) {
                if (detections.isEmpty()) {
                    item { EmptyPanel("No parsed transactions yet.") }
                } else {
                    items(detections, key = { it.id }) { detection ->
                        DetectionRow(detection)
                    }
                }
            } else {
                if (events.isEmpty()) {
                    item { EmptyPanel("No notifications captured yet.") }
                } else {
                    items(events, key = { it.id }) { event ->
                        EventRow(
                            event = event,
                            onParse = {
                                scope.launch {
                                    exportMessage = "Parsing notification..."
                                    val result = withContext(Dispatchers.IO) {
                                        database.parseStoredEvent(event.id)
                                    }

                                    refreshData()
                                    when {
                                        result == null -> {
                                            exportMessage = "Notification not found"
                                        }
                                        result.detection == null -> {
                                            exportMessage = "Notification ignored: ${result.ignoredReason ?: "not parsed"}"
                                        }
                                        result.detection.webhookStatus == "sent" -> {
                                            exportMessage = "Parsed ${formatIdr(result.detection.amount)}; webhook already sent"
                                        }
                                        webhookSettings.url().isNotBlank() -> {
                                            exportMessage = "Parsed ${formatIdr(result.detection.amount)}"
                                            pendingWebhookDetection = result.detection
                                        }
                                        else -> {
                                            exportMessage = "Parsed ${formatIdr(result.detection.amount)} locally; no webhook URL saved"
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WebhookPanel(
    webhookUrl: String,
    webhookMessage: String?,
    onWebhookUrlChanged: (String) -> Unit,
    onSaveWebhookUrl: () -> Unit,
    onSendLatest: () -> Unit,
    onRetryWebhooks: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Webhook", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = webhookUrl,
                onValueChange = onWebhookUrlChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("POST URL") },
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onRetryWebhooks) {
                    Text("Retry")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onSendLatest) {
                    Text("Send latest")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onSaveWebhookUrl) {
                    Text("Save")
                }
            }
            webhookMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusPanel(
    hasAccess: Boolean,
    onOpenSettings: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Notification access", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = if (hasAccess) "Enabled" else "Disabled",
                        color = if (hasAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = onOpenSettings) {
                    Text("Open settings")
                }
            }
        }
    }
}

@Composable
private fun SupportedBankPanel(packageName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Supported bank app", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "myBCA",
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetectionRow(detection: PaymentDetection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(formatIdr(detection.amount), style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = detection.bankCode,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = detection.status.uppercase(),
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = "${formatTime(detection.createdAt)} · confidence ${"%.2f".format(detection.confidence)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatWebhookStatus(detection),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EventRow(
    event: NotificationEvent,
    onParse: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = event.appLabel,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = event.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (event.title.isNotBlank()) Text(event.title, fontWeight = FontWeight.Medium)
            val body = listOf(event.text, event.bigText, event.subText)
                .filter { it.isNotBlank() }
                .joinToString(separator = "\n")
            if (body.isNotBlank()) {
                Text(
                    text = body,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatTime(event.capturedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.ignoredReason ?: "parsed",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (event.ignoredReason == null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(onClick = onParse) {
                    Text("Parse")
                }
            }
        }
    }
}

@Composable
private fun EmptyPanel(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatIdr(amount: Long): String =
    NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID"))
        .apply { maximumFractionDigits = 0 }
        .format(amount)

private fun formatTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("dd MMM HH:mm:ss")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMillis))

private fun formatWebhookStatus(detection: PaymentDetection): String {
    val status = detection.webhookStatus ?: return "Webhook: not recorded"
    val code = detection.webhookStatusCode?.let { " HTTP $it" }.orEmpty()
    val attempts = detection.webhookAttemptCount?.let { " · attempts $it" }.orEmpty()
    val message = detection.webhookMessage?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    return "Webhook: $status$code$attempts$message"
}
