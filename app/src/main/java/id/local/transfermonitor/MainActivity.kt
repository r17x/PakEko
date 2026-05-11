package id.local.transfermonitor

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.local.transfermonitor.data.NotificationEvent
import id.local.transfermonitor.data.PaymentDetection
import id.local.transfermonitor.data.bodyPreview
import id.local.transfermonitor.protocol.AppIntent
import id.local.transfermonitor.protocol.AppRuntime
import id.local.transfermonitor.transport.PondServer
import id.local.transfermonitor.ui.NotificationBrowserScreen
import id.local.transfermonitor.ui.PatternBuilderScreen
import id.local.transfermonitor.ui.TransportSettingsPanel
import id.local.transfermonitor.util.DebugExport
import id.local.transfermonitor.util.formatLogTime
import id.local.transfermonitor.util.hasNotificationListenerAccess
import java.text.NumberFormat
import java.util.Locale

data class InstalledApp(val packageName: String, val appLabel: String)

enum class AppScreen { MAIN, NOTIFICATION_BROWSER, PATTERN_BUILDER }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as TransferMonitorApp
        val pondServer = app.pondServer

        setContent {
            var currentScreen by remember { mutableStateOf(AppScreen.MAIN) }
            var selectedPackageName by remember { mutableStateOf<String?>(null) }
            var selectedEventId by remember { mutableStateOf<Long?>(null) }
            var showAppPicker by remember { mutableStateOf(false) }
            TransferMonitorTheme {
                if (showAppPicker) {
                    AppPickerDialog(runtime = app.runtime, onDismiss = { showAppPicker = false })
                }
                when (currentScreen) {
                    AppScreen.MAIN -> TransferMonitorScreen(
                        runtime = app.runtime,
                        pondServer = pondServer,
                        openNotificationSettings = {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        },
                        onNavigate = { currentScreen = it },
                        onAppClick = { packageName ->
                            selectedPackageName = packageName
                            currentScreen = AppScreen.NOTIFICATION_BROWSER
                        },
                        onManageApps = { showAppPicker = true },
                    )
                    AppScreen.NOTIFICATION_BROWSER -> NotificationBrowserScreen(
                        runtime = app.runtime,
                        packageName = selectedPackageName,
                        onBack = { currentScreen = AppScreen.MAIN },
                        onCreatePattern = selectedPackageName?.let { _ ->
                            { eventId: Long ->
                                selectedEventId = eventId
                                currentScreen = AppScreen.PATTERN_BUILDER
                            }
                        },
                    )
                    AppScreen.PATTERN_BUILDER -> {
                        val pkg = selectedPackageName
                        val eventId = selectedEventId
                        if (pkg != null && eventId != null) {
                            PatternBuilderScreen(
                                runtime = app.runtime,
                                packageName = pkg,
                                eventId = eventId,
                                onBack = {
                                    currentScreen = AppScreen.NOTIFICATION_BROWSER
                                },
                            )
                        }
                    }
                }
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
    runtime: AppRuntime,
    pondServer: PondServer,
    openNotificationSettings: () -> Unit,
    onNavigate: (AppScreen) -> Unit,
    onAppClick: (String) -> Unit,
    onManageApps: () -> Unit,
) {
    val context = LocalContext.current
    val events by runtime.recentEvents.collectAsState()
    val detections by runtime.recentDetections.collectAsState()
    var hasAccess by remember { mutableStateOf(context.hasNotificationListenerAccess()) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var webhookUrl by remember { mutableStateOf("") }
    val app = remember { context.applicationContext as TransferMonitorApp }
    var pendingWebhookDetection by remember { mutableStateOf<PaymentDetection?>(null) }

    // Load initial webhook URL and refresh access status
    LaunchedEffect(Unit) {
        webhookUrl = id.local.transfermonitor.data.WebhookSettings(context).url()
    }

    LaunchedEffect(Unit) {
        runtime.statusMessage.collect { message ->
            exportMessage = message
        }
    }

    // Periodically refresh notification access status
    LaunchedEffect(Unit) {
        while (true) {
            val current = context.hasNotificationListenerAccess()
            if (current != hasAccess) hasAccess = current
            kotlinx.coroutines.delay(3000)
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
                        runtime.send(AppIntent.SendWebhook(detectionId = detection.id, allowPendingRetry = true))
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
                MonitoredAppsPanel(
                    runtime = runtime,
                    onManage = onManageApps,
                    onAppClick = onAppClick,
                )
            }

            item {
                TransportSettingsPanel(
                    pondServer = pondServer,
                )
            }

            item {
                WebhookPanel(
                    webhookUrl = webhookUrl,
                    webhookMessage = exportMessage,
                    onWebhookUrlChanged = { webhookUrl = it },
                    onSaveWebhookUrl = {
                        runtime.send(AppIntent.ConfigureWebhook(webhookUrl))
                    },
                    onSendLatest = {
                        val latest = detections.firstOrNull()
                        if (latest != null) {
                            runtime.send(AppIntent.SendWebhook(detectionId = latest.id, allowPendingRetry = true))
                        } else {
                            exportMessage = "No detection to send yet"
                        }
                    },
                    onRetryWebhooks = {
                        runtime.send(AppIntent.RetryWebhooks)
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
                        OutlinedButton(onClick = { onNavigate(AppScreen.NOTIFICATION_BROWSER) }) {
                            Text("Log")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                runtime.send(AppIntent.DeleteAll)
                                exportMessage = null
                            }
                        ) {
                            Text("Delete all")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(onClick = { runtime.send(AppIntent.RefreshData) }) {
                            Text("Refresh")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                runtime.send(AppIntent.ReparseAll)
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
                                    val result = DebugExport.exportJson(context, app.database)
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
                                    val result = DebugExport.exportSqliteDatabase(context, app.database)
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
                                runtime.send(AppIntent.ParseStoredEvent(event.id))
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
private fun MonitoredAppsPanel(
    runtime: AppRuntime,
    onManage: () -> Unit,
    onAppClick: (String) -> Unit,
) {
    val monitoredApps by runtime.monitoredApps.collectAsState()
    val userPatterns by runtime.userPatterns.collectAsState()
    val patternCounts = remember(userPatterns) {
        userPatterns.groupingBy { it.packageName }.eachCount()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Monitored Apps", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onManage) {
                    Text("Manage")
                }
            }

            if (monitoredApps.isEmpty()) {
                Text(
                    "No apps monitored",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                monitoredApps.forEach { packageName ->
                    val patternCount = patternCounts[packageName] ?: 0
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAppClick(packageName) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    packageName.substringAfterLast('.'),
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    "$patternCount pattern${if (patternCount == 1) "" else "s"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                ">",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
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
                text = "${formatLogTime(detection.createdAt)} · confidence ${"%.2f".format(detection.confidence)}",
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
            val body = event.bodyPreview()
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
                    text = formatLogTime(event.capturedAt),
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

private fun formatWebhookStatus(detection: PaymentDetection): String {
    val status = detection.webhookStatus ?: return "Webhook: not recorded"
    val code = detection.webhookStatusCode?.let { " HTTP $it" }.orEmpty()
    val attempts = detection.webhookAttemptCount?.let { " · attempts $it" }.orEmpty()
    val message = detection.webhookMessage?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    return "Webhook: $status$code$attempts$message"
}

@Composable
private fun AppPickerDialog(runtime: AppRuntime, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    val monitoredPackages by runtime.monitoredApps.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        installedApps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName }
                .map { info -> InstalledApp(info.packageName, pm.getApplicationLabel(info).toString()) }
                .sortedBy { it.appLabel.lowercase() }
        }
    }

    val filtered = remember(installedApps, searchQuery) {
        if (searchQuery.isBlank()) installedApps
        else installedApps.filter { it.appLabel.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Monitored Apps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search apps") }, singleLine = true)
                Text("${monitoredPackages.size} monitored · ${installedApps.size} installed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(modifier = Modifier.height(400.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        val isMonitored = app.packageName in monitoredPackages
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.appLabel, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(checked = isMonitored, onCheckedChange = {
                                if (isMonitored) runtime.send(AppIntent.RemoveMonitoredApp(app.packageName))
                                else runtime.send(AppIntent.AddMonitoredApp(app.packageName, app.appLabel))
                            })
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
