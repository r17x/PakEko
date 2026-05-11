package id.local.transfermonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.local.transfermonitor.data.MatchField
import id.local.transfermonitor.data.NotificationEvent
import id.local.transfermonitor.data.PatternDirection
import id.local.transfermonitor.data.PatternSegment
import id.local.transfermonitor.data.SegmentRole
import id.local.transfermonitor.data.UserPattern
import id.local.transfermonitor.parser.AmountParser
import id.local.transfermonitor.parser.SegmentParser
import id.local.transfermonitor.parser.SegmentSerializer
import id.local.transfermonitor.protocol.AppIntent
import id.local.transfermonitor.protocol.AppRuntime

private val AMOUNT_REGEX = Regex("[0-9][0-9.,\\s]*")
private val WHITESPACE_SPLIT = Regex("(?<=\\s)|(?=\\s)")

private data class TestResult(val event: NotificationEvent, val matched: Boolean, val amount: Long?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PatternBuilderScreen(runtime: AppRuntime, packageName: String, eventId: Long, onBack: () -> Unit) {
    val allEvents by runtime.recentEvents.collectAsState()
    val event = remember(allEvents, eventId) { allEvents.find { it.id == eventId } }

    if (event == null) { onBack(); return }

    val bodyFields = remember(event) {
        listOf("text" to event.text, "bigText" to event.bigText, "subText" to event.subText).filter { it.second.isNotBlank() }
    }
    var selectedField by remember { mutableStateOf(bodyFields.maxByOrNull { it.second.length }?.first ?: "text") }
    val selectedText = remember(bodyFields, selectedField) { bodyFields.firstOrNull { it.first == selectedField }?.second ?: event.text }
    var segments by remember(selectedText) {
        mutableStateOf(selectedText.split(WHITESPACE_SPLIT).filter { it.isNotEmpty() }.map { token ->
            PatternSegment(token, if (token.trim().matches(AMOUNT_REGEX)) SegmentRole.AMOUNT else SegmentRole.LITERAL)
        })
    }
    var matchTitle by remember { mutableStateOf(true) }
    var direction by remember { mutableStateOf(PatternDirection.INCOMING) }
    var label by remember { mutableStateOf("") }
    var confidence by remember { mutableFloatStateOf(0.95f) }
    var testResults by remember { mutableStateOf<List<TestResult>?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Build Pattern", fontWeight = FontWeight.SemiBold) }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Source notification
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Source Notification", style = MaterialTheme.typography.titleSmall)
                        if (event.title.isNotBlank()) {
                            Text("title", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                            Text(event.title, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                        bodyFields.forEach { (fieldName, value) ->
                            Text(fieldName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                            Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                        }
                    }
                }
            }
            // Title pattern toggle
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Match title exactly")
                    Switch(checked = matchTitle, onCheckedChange = { matchTitle = it })
                }
            }
            // Field selector
            if (bodyFields.size > 1) {
                item {
                    Text("Body field", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        bodyFields.forEach { (fieldName, _) -> FilterChip(selected = selectedField == fieldName, onClick = { selectedField = fieldName }, label = { Text(fieldName) }) }
                    }
                }
            }
            // Token chip builder
            item {
                Text("Tag tokens", style = MaterialTheme.typography.titleSmall)
                Text("Tap to cycle: literal > amount > sender > wildcard", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    SegmentRole.entries.forEach { role -> LegendChip(role.name.lowercase().replaceFirstChar { it.uppercase() }, segmentRoleColor(role)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { segments = segments.map { it.copy(role = SegmentRole.LITERAL) } }) { Text("All literal", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { segments = segments.map { it.copy(role = SegmentRole.WILDCARD) } }) { Text("All wildcard", style = MaterialTheme.typography.labelSmall) }
                }
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    segments.forEachIndexed { index, segment ->
                        if (segment.text.isBlank()) return@forEachIndexed
                        FilterChip(
                            selected = segment.role != SegmentRole.LITERAL,
                            onClick = { segments = segments.toMutableList().apply { this[index] = segment.copy(role = segment.role.next()) } },
                            label = { Text(if (segment.role == SegmentRole.WILDCARD) "*" else segment.text.trim().take(20), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                            colors = FilterChipDefaults.filterChipColors(containerColor = segmentRoleColor(segment.role)),
                        )
                    }
                }
            }
            // Pattern structure preview
            item {
                Text("Pattern structure", style = MaterialTheme.typography.titleSmall)
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(segments.joinToString(" ") { seg -> when (seg.role) { SegmentRole.LITERAL -> "\"${seg.text.trim().take(20)}\""; SegmentRole.AMOUNT -> "[AMOUNT]"; SegmentRole.SENDER -> "[SENDER]"; SegmentRole.WILDCARD -> "[*]" } }, modifier = Modifier.padding(12.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            // Direction
            item {
                Text("Direction", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(PatternDirection.INCOMING, PatternDirection.OUTGOING).forEach { dir -> FilterChip(selected = direction == dir, onClick = { direction = dir }, label = { Text(dir.name.lowercase()) }) }
                }
            }
            // Label
            item { OutlinedTextField(value = label, onValueChange = { label = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Pattern label") }, placeholder = { Text("${packageName.substringAfterLast('.')} ${direction.name.lowercase()}") }, singleLine = true) }
            // Advanced
            item {
                var showAdvanced by remember { mutableStateOf(false) }
                Column {
                    TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide advanced" else "Show advanced") }
                    if (showAdvanced) {
                        Text("Confidence: ${"%.2f".format(confidence)}", style = MaterialTheme.typography.titleSmall)
                        Slider(value = confidence, onValueChange = { confidence = it }, valueRange = 0.80f..0.99f, steps = 18)
                    }
                }
            }
            // Test + Save buttons
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            testResults = allEvents.filter { it.packageName == packageName }.map { testEvent ->
                                val titleMatch = !matchTitle || event.title.isBlank() || testEvent.title.trim().equals(event.title.trim(), ignoreCase = true)
                                if (!titleMatch) return@map TestResult(testEvent, false, null)
                                val captures = listOf(testEvent.text, testEvent.bigText, testEvent.subText)
                                    .filter { it.isNotBlank() }
                                    .firstNotNullOfOrNull { body -> SegmentParser.parse(segments, body) }
                                TestResult(testEvent, captures != null, captures?.get("amount")?.let { AmountParser.parseAmount(it) })
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Test") }
                    Button(
                        onClick = {
                            val patternLabel = label.ifBlank { "${packageName.substringAfterLast('.')} ${direction.name.lowercase()}" }
                            runtime.send(AppIntent.SaveUserPattern(UserPattern(
                                id = 0L, packageName = packageName, label = patternLabel, direction = direction,
                                matchField = MatchField.BODY, titleText = if (matchTitle && event.title.isNotBlank()) event.title.trim() else null,
                                segmentsJson = SegmentSerializer.toJson(segments), confidence = confidence.toDouble(),
                                enabled = true, createdAt = 0L, updatedAt = 0L,
                            )))
                            onBack()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Save") }
                }
            }
            // Test results
            testResults?.let { results ->
                item {
                    HorizontalDivider()
                    Text("Test: ${results.count { it.matched }}/${results.size} matched", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                items(results) { r ->
                    Text(
                        "${if (r.matched) "\u2713" else "\u2717"} ${r.event.title.take(30)}${r.amount?.let { " \u2192 $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.matched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun SegmentRole.next(): SegmentRole = when (this) {
    SegmentRole.LITERAL -> SegmentRole.AMOUNT; SegmentRole.AMOUNT -> SegmentRole.SENDER
    SegmentRole.SENDER -> SegmentRole.WILDCARD; SegmentRole.WILDCARD -> SegmentRole.LITERAL
}

@Composable
private fun segmentRoleColor(role: SegmentRole): Color = when (role) {
    SegmentRole.LITERAL -> MaterialTheme.colorScheme.surfaceVariant
    SegmentRole.AMOUNT -> Color(0xFF2196F3).copy(alpha = 0.3f)
    SegmentRole.SENDER -> Color(0xFF9C27B0).copy(alpha = 0.3f)
    SegmentRole.WILDCARD -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
}

@Composable
private fun LegendChip(label: String, color: Color) {
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}
