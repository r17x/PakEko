package id.local.transfermonitor.protocol

import android.content.Context
import id.local.transfermonitor.data.CapturedNotification
import id.local.transfermonitor.data.NotificationEvent
import id.local.transfermonitor.data.PaymentDetection
import id.local.transfermonitor.data.TransferDatabase
import id.local.transfermonitor.data.UserPattern
import id.local.transfermonitor.data.WebhookSettings
import id.local.transfermonitor.parser.PaymentNotificationParser
import id.local.transfermonitor.parser.PaymentParseDecision
import id.local.transfermonitor.util.WebhookDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class AppRuntime(
    private val context: Context,
    private val database: TransferDatabase,
    private val webhookSettings: WebhookSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val intentChannel = Channel<AppIntent>(Channel.UNLIMITED)

    private val _events = MutableSharedFlow<NotificationEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<NotificationEvent> = _events.asSharedFlow()

    private val _detections = MutableSharedFlow<PaymentDetection>(replay = 0, extraBufferCapacity = 64)
    val detections: SharedFlow<PaymentDetection> = _detections.asSharedFlow()

    private val _monitoredApps = MutableStateFlow<Set<String>>(emptySet())
    val monitoredApps: StateFlow<Set<String>> = _monitoredApps.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<NotificationEvent>>(emptyList())
    val recentEvents: StateFlow<List<NotificationEvent>> = _recentEvents.asStateFlow()

    private val _recentDetections = MutableStateFlow<List<PaymentDetection>>(emptyList())
    val recentDetections: StateFlow<List<PaymentDetection>> = _recentDetections.asStateFlow()

    private val _connectionInfo = MutableStateFlow<JSONObject>(JSONObject().put("clients", JSONArray()))
    val connectionInfo: StateFlow<JSONObject> = _connectionInfo.asStateFlow()

    private val _statusMessage = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 16)
    val statusMessage: SharedFlow<String> = _statusMessage.asSharedFlow()

    private val _userPatterns = MutableStateFlow<List<UserPattern>>(emptyList())
    val userPatterns: StateFlow<List<UserPattern>> = _userPatterns.asStateFlow()

    init {
        scope.launch {
            _monitoredApps.value = database.monitoredPackages()
            _userPatterns.value = database.allPatterns()
            refreshData()

            for (intent in intentChannel) {
                processIntent(intent)
            }
        }
    }

    fun send(intent: AppIntent) {
        intentChannel.trySend(intent)
    }

    private suspend fun processIntent(intent: AppIntent) {
        when (intent) {
            is AppIntent.CaptureNotification -> handleCapture(intent)
            is AppIntent.ParseStoredEvent -> handleParseStoredEvent(intent)
            is AppIntent.SendWebhook -> handleSendWebhook(intent)
            is AppIntent.RetryWebhooks -> handleRetryWebhooks()
            is AppIntent.AddMonitoredApp -> handleAddMonitoredApp(intent)
            is AppIntent.RemoveMonitoredApp -> handleRemoveMonitoredApp(intent)
            is AppIntent.ConfigureWebhook -> handleConfigureWebhook(intent)
            is AppIntent.ReparseAll -> handleReparseAll()
            is AppIntent.DeleteAll -> handleDeleteAll()
            is AppIntent.RefreshData -> refreshData()
            is AppIntent.SaveUserPattern -> handleSaveUserPattern(intent)
            is AppIntent.DeleteUserPattern -> handleDeleteUserPattern(intent)
            is AppIntent.ToggleUserPattern -> handleToggleUserPattern(intent)
        }
    }

    private suspend fun handleCapture(intent: AppIntent.CaptureNotification) {
        val isMonitored = intent.packageName in _monitoredApps.value

        val parseDecision = if (isMonitored) {
            PaymentNotificationParser.parse(
                packageName = intent.packageName,
                title = intent.title,
                text = intent.text,
                bigText = intent.bigText,
                subText = intent.subText,
                userPatterns = _userPatterns.value,
            )
        } else {
            PaymentParseDecision.notMonitored(intent.packageName)
        }

        val captured = CapturedNotification(
            packageName = intent.packageName,
            appLabel = intent.appLabel,
            title = intent.title,
            text = intent.text,
            bigText = intent.bigText,
            subText = intent.subText,
            postedAt = intent.postedAt,
            rawHash = intent.rawHash,
            idempotencyKey = intent.idempotencyKey,
            ignoredReason = parseDecision.ignoredReason,
            parsedAmount = parseDecision.parsedAmount,
            confidence = parseDecision.confidence,
            bankCode = parseDecision.bankCode,
        )

        val result = database.insertCapturedNotification(captured)

        if (!result.duplicate) {
            refreshData()
        }

        result.detection?.let { detection ->
            _detections.emit(detection)
            WebhookDispatcher.sendConfigured(context, database, detection)
        }
    }

    private suspend fun handleParseStoredEvent(intent: AppIntent.ParseStoredEvent) {
        val result = database.parseStoredEvent(intent.eventId, _userPatterns.value)
        when {
            result == null -> _statusMessage.emit("Notification not found")
            result.detection == null -> _statusMessage.emit("Notification ignored: ${result.ignoredReason ?: "not parsed"}")
            result.detection.webhookStatus == "sent" -> _statusMessage.emit("Parsed ${result.detection.amount}; webhook already sent")
            webhookSettings.url().isNotBlank() -> {
                _statusMessage.emit("Parsed amount detected — send webhook?")
            }
            else -> _statusMessage.emit("Parsed locally; no webhook URL saved")
        }
        refreshData()
    }

    private suspend fun handleSendWebhook(intent: AppIntent.SendWebhook) {
        val detection = _recentDetections.value.find { it.id == intent.detectionId }
        if (detection != null) {
            _statusMessage.emit("Sending webhook...")
            val result = WebhookDispatcher.sendConfigured(
                context = context,
                database = database,
                detection = detection,
                allowPendingRetry = intent.allowPendingRetry,
            )
            _statusMessage.emit(
                if (result.sent) "Sent: ${result.message}"
                else "Send failed: ${result.message}"
            )
            refreshData()
        }
    }

    private suspend fun handleRetryWebhooks() {
        if (webhookSettings.url().isBlank()) {
            _statusMessage.emit("Save a webhook URL first")
            return
        }
        _statusMessage.emit("Retrying webhook deliveries...")
        val result = WebhookDispatcher.retryRecordedDeliveries(context, database)
        _statusMessage.emit("Retried ${result.attemptedCount}: ${result.sentCount} sent, ${result.failedCount} failed")
        refreshData()
    }

    private suspend fun handleAddMonitoredApp(intent: AppIntent.AddMonitoredApp) {
        database.addApp(intent.packageName, intent.appLabel)
        _monitoredApps.value = database.monitoredPackages()
    }

    private suspend fun handleRemoveMonitoredApp(intent: AppIntent.RemoveMonitoredApp) {
        database.removeApp(intent.packageName)
        _monitoredApps.value = database.monitoredPackages()
    }

    private suspend fun handleConfigureWebhook(intent: AppIntent.ConfigureWebhook) {
        webhookSettings.setUrl(intent.url)
        _statusMessage.emit(
            if (intent.url.isBlank()) "Webhook disabled"
            else "Webhook URL saved"
        )
    }

    private suspend fun handleReparseAll() {
        try {
            val result = database.reparseStoredEvents(_monitoredApps.value, _userPatterns.value)
            refreshData()
            _statusMessage.emit("Reparsed ${result.eventCount} events: ${result.detectionCount} detections, ${result.ignoredCount} ignored")
        } catch (error: Exception) {
            _statusMessage.emit("Reparse failed: ${error.message ?: error::class.java.simpleName}")
        }
    }

    private suspend fun handleDeleteAll() {
        database.deleteAll()
        refreshData()
    }

    private suspend fun handleSaveUserPattern(intent: AppIntent.SaveUserPattern) {
        val pattern = intent.pattern
        if (pattern.id == 0L) {
            database.insertPattern(
                packageName = pattern.packageName,
                label = pattern.label,
                direction = pattern.direction,
                matchField = pattern.matchField,
                titleText = pattern.titleText,
                segmentsJson = pattern.segmentsJson,
                confidence = pattern.confidence,
            )
        } else {
            database.updatePattern(pattern)
        }
        _userPatterns.value = database.allPatterns()
        handleReparseAll()
        _statusMessage.emit("Pattern saved")
    }

    private suspend fun handleDeleteUserPattern(intent: AppIntent.DeleteUserPattern) {
        database.deletePattern(intent.patternId)
        _userPatterns.value = database.allPatterns()
        handleReparseAll()
        _statusMessage.emit("Pattern deleted")
    }

    private suspend fun handleToggleUserPattern(intent: AppIntent.ToggleUserPattern) {
        database.toggleEnabled(intent.patternId, intent.enabled)
        _userPatterns.value = database.allPatterns()
        handleReparseAll()
    }

    private fun refreshData() {
        _recentEvents.value = database.recentEvents()
        _recentDetections.value = database.recentDetections()
    }

    fun updateConnectionInfo(info: JSONObject) {
        _connectionInfo.value = info
    }

    fun shutdown() {
        intentChannel.close()
    }
}
