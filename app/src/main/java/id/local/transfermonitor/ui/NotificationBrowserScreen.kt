package id.local.transfermonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.local.transfermonitor.data.NotificationEvent
import id.local.transfermonitor.data.UserPattern
import id.local.transfermonitor.data.bodyPreview
import id.local.transfermonitor.protocol.AppIntent
import id.local.transfermonitor.protocol.AppRuntime
import id.local.transfermonitor.util.formatLogTime

enum class LogStatusFilter { ALL, PARSED, IGNORED }

enum class LogTimeFilter(val label: String, val ms: Long?) {
    ALL("All time", null),
    LAST_HOUR("Last hour", 3_600_000L),
    LAST_DAY("Last 24h", 86_400_000L),
    LAST_WEEK("Last 7d", 604_800_000L),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationBrowserScreen(
    runtime: AppRuntime,
    packageName: String?,
    onBack: () -> Unit,
    onCreatePattern: ((Long) -> Unit)? = null,
) {
    val allEvents by runtime.recentEvents.collectAsState()
    val allPatterns by runtime.userPatterns.collectAsState()
    var statusFilter by remember { mutableStateOf(LogStatusFilter.ALL) }
    var timeFilter by remember { mutableStateOf(LogTimeFilter.ALL) }
    var packageFilter by remember { mutableStateOf("") }
    var expandedEventId by remember { mutableStateOf<Long?>(null) }

    val patterns = remember(allPatterns, packageName) {
        if (packageName != null) allPatterns.filter { it.packageName == packageName } else emptyList()
    }
    val events = remember(allEvents, packageName, statusFilter, timeFilter, packageFilter) {
        val now = System.currentTimeMillis()
        allEvents.filter { event ->
            val matchesPkg = packageName?.let { event.packageName == it }
                ?: (packageFilter.isBlank() || event.packageName.contains(packageFilter.trim(), ignoreCase = true))
            val matchesStatus = when (statusFilter) {
                LogStatusFilter.ALL -> true
                LogStatusFilter.PARSED -> event.ignoredReason == null
                LogStatusFilter.IGNORED -> event.ignoredReason != null
            }
            val matchesTime = timeFilter.ms?.let { event.capturedAt >= now - it } ?: true
            matchesPkg && matchesStatus && matchesTime
        }
    }

    val title = if (packageName != null) {
        events.firstOrNull()?.appLabel ?: packageName.substringAfterLast('.')
    } else "Notification Log (${events.size})"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (packageName == null) {
                    OutlinedTextField(value = packageFilter, onValueChange = { packageFilter = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Filter by package") }, singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LogStatusFilter.entries.forEach { f ->
                        FilterChip(selected = statusFilter == f, onClick = { statusFilter = f }, label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LogTimeFilter.entries.forEach { f ->
                        FilterChip(selected = timeFilter == f, onClick = { timeFilter = f }, label = { Text(f.label) })
                    }
                }
            }
            HorizontalDivider()
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (packageName != null && patterns.isNotEmpty()) {
                    item { Text("Patterns (${patterns.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    items(patterns, key = { it.id }) { pattern ->
                        PatternRow(pattern, onToggle = { runtime.send(AppIntent.ToggleUserPattern(pattern.id, !pattern.enabled)) }, onDelete = { runtime.send(AppIntent.DeleteUserPattern(pattern.id)) })
                    }
                    item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }
                    item { Text("Notifications (${events.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                }
                if (events.isEmpty()) {
                    item { Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No notifications match the current filters.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                } else {
                    items(events, key = { it.id }) { event ->
                        NotificationRow(event = event, expanded = event.id == expandedEventId, onToggleExpand = { expandedEventId = if (expandedEventId == event.id) null else event.id }, onClick = onCreatePattern?.let { cb -> { cb(event.id) } })
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternRow(pattern: UserPattern, onToggle: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (pattern.enabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(pattern.label, fontWeight = FontWeight.Medium)
                Text("${pattern.direction} · confidence ${"%.2f".format(pattern.confidence)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = pattern.enabled, onCheckedChange = { onToggle() })
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun NotificationRow(event: NotificationEvent, expanded: Boolean, onToggleExpand: () -> Unit, onClick: (() -> Unit)?) {
    val isParsed = event.ignoredReason == null
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick ?: onToggleExpand),
        colors = CardDefaults.cardColors(containerColor = if (isParsed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(event.appLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(event.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(text = event.ignoredReason ?: "parsed", style = MaterialTheme.typography.labelSmall, color = if (isParsed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (event.title.isNotBlank()) {
                Text(event.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis)
            }
            val preview = event.bodyPreview()
            if (preview.isNotBlank()) {
                Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis)
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Captured: ${formatLogTime(event.capturedAt)}", style = MaterialTheme.typography.labelSmall)
                Text("Posted: ${formatLogTime(event.postedAt)}", style = MaterialTheme.typography.labelSmall)
                Text("Key: ${event.idempotencyKey}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(formatLogTime(event.capturedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
