package id.local.transfermonitor.transport

import id.local.transfermonitor.data.NotificationEvent
import id.local.transfermonitor.data.PaymentDetection
import id.local.transfermonitor.protocol.AppRuntime
import id.local.transfermonitor.protocol.StreamAddress
import id.local.transfermonitor.protocol.StreamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PondClientSession(
    private val socket: Socket,
    private val runtime: AppRuntime,
    private val registry: StreamRegistry,
    private val scope: CoroutineScope,
    private val onDisconnect: (PondClientSession) -> Unit,
) {
    val sessionId: String = UUID.randomUUID().toString()
    val connectedAt: Long = System.currentTimeMillis()
    var bytesSent: Long = 0L
        private set
    var bytesReceived: Long = 0L
        private set

    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()
    private val closed = AtomicBoolean(false)
    private val subscriptions = ConcurrentHashMap<String, Job>()
    private val seqCounters = ConcurrentHashMap<String, AtomicLong>()
    private var handshakeComplete = false

    fun start() {
        scope.launch {
            try {
                handleConnection()
            } catch (_: Exception) {
                // Connection closed or protocol error
            } finally {
                close()
            }
        }
    }

    private suspend fun handleConnection() {
        val firstFrame = PondFrame.read(input)
        bytesReceived += firstFrame.payload.size + 5
        val firstJson = JSONObject(String(firstFrame.payload, Charsets.UTF_8))
        val firstMessage = PondMessage.decode(firstJson)

        if (firstMessage !is PondMessage.Hello) {
            sendMessageSync(PondMessage.Error("protocol_error", "First message must be hello"))
            return
        }

        if (firstMessage.version != PondMessage.PROTOCOL_VERSION) {
            sendMessageSync(PondMessage.Error("unsupported_version", "Only version ${PondMessage.PROTOCOL_VERSION} is supported"))
            return
        }

        val welcome = PondMessage.Welcome(
            version = PondMessage.PROTOCOL_VERSION,
            encoding = "json",
            sessionId = sessionId,
            streams = registry.catalog(),
        )
        sendMessageSync(welcome)
        handshakeComplete = true

        sendMessageSync(PondMessage.AppEvent(status = "started", detail = null))

        while (scope.isActive && !socket.isClosed) {
            val frame = PondFrame.read(input)
            bytesReceived += frame.payload.size + 5
            val json = JSONObject(String(frame.payload, Charsets.UTF_8))
            val message = PondMessage.decode(json)
            handleMessage(message)
        }
    }

    private suspend fun handleMessage(message: PondMessage) {
        when (message) {
            is PondMessage.Subscribe -> handleSubscribe(message)
            else -> {
                // Ignore unknown client messages (forward compatibility)
            }
        }
    }

    private fun handleSubscribe(message: PondMessage.Subscribe) {
        val address = StreamAddress.parse(message.stream) ?: return

        if (message.active) {
            if (subscriptions.containsKey(message.stream)) return

            val snapshot = registry.snapshot(address)
            if (snapshot != null) {
                val seq = nextSeq(message.stream)
                sendMessageSync(PondMessage.Data(
                    stream = message.stream,
                    seq = seq,
                    payload = snapshot,
                ))
            }

            val job = when (address) {
                StreamAddress.EVENTS -> scope.launch {
                    runtime.events.collect { event ->
                        val seq = nextSeq(message.stream)
                        val payload = JSONObject().put("event", StreamRegistry.eventToJson(event))
                        sendMessageSync(PondMessage.Data(message.stream, seq, payload))
                    }
                }
                StreamAddress.DETECTIONS -> scope.launch {
                    runtime.detections.collect { detection ->
                        val seq = nextSeq(message.stream)
                        val payload = JSONObject().put("detection", StreamRegistry.detectionToJson(detection))
                        sendMessageSync(PondMessage.Data(message.stream, seq, payload))
                    }
                }
                StreamAddress.MONITORED -> scope.launch {
                    runtime.monitoredApps.collect { packages ->
                        val seq = nextSeq(message.stream)
                        val payload = JSONObject().put("packages", org.json.JSONArray(packages.toList()))
                        sendMessageSync(PondMessage.Data(message.stream, seq, payload))
                    }
                }
                StreamAddress.CONNECTIONS -> scope.launch {
                    runtime.connectionInfo.collect { info ->
                        val seq = nextSeq(message.stream)
                        sendMessageSync(PondMessage.Data(message.stream, seq, info))
                    }
                }
                StreamAddress.STREAMS -> scope.launch {
                    // Streams catalog is static, send once
                    val catalogSnapshot = registry.snapshot(address)
                    if (catalogSnapshot != null) {
                        val seq = nextSeq(message.stream)
                        sendMessageSync(PondMessage.Data(message.stream, seq, catalogSnapshot))
                    }
                }
                else -> null
            }

            job?.let { subscriptions[message.stream] = it }
        } else {
            subscriptions.remove(message.stream)?.cancel()
            seqCounters.remove(message.stream)
        }
    }

    private fun nextSeq(stream: String): Long =
        seqCounters.getOrPut(stream) { AtomicLong(0) }.incrementAndGet()

    @Synchronized
    private fun sendMessageSync(message: PondMessage) {
        try {
            val payload = message.toFrame()
            PondFrame.write(output, payload)
            bytesSent += payload.size + 5
        } catch (_: Exception) {
            close()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        subscriptions.values.forEach { it.cancel() }
        subscriptions.clear()
        try { socket.close() } catch (_: Exception) {}
        onDisconnect(this)
    }

    fun toInfoJson(): JSONObject =
        JSONObject().apply {
            put("session_id", sessionId)
            put("connected_since", connectedAt)
            put("bytes_sent", bytesSent)
            put("bytes_received", bytesReceived)
            put("subscriptions", org.json.JSONArray(subscriptions.keys.toList()))
        }
}
