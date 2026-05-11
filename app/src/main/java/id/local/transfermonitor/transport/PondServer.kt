package id.local.transfermonitor.transport

import id.local.transfermonitor.data.TransportSettings
import id.local.transfermonitor.protocol.AppRuntime
import id.local.transfermonitor.protocol.StreamRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

class PondServer(
    private val runtime: AppRuntime,
) {
    private val registry = StreamRegistry(runtime)
    private var serverSocket: ServerSocket? = null
    private var serverScope: CoroutineScope? = null
    private val clients = ConcurrentHashMap<String, PondClientSession>()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _connections = MutableStateFlow<JSONObject>(JSONObject().put("clients", JSONArray()))
    val connections: StateFlow<JSONObject> = _connections.asStateFlow()

    var port: Int = TransportSettings.DEFAULT_PORT
        private set
    var bindAll: Boolean = true
        private set

    fun start(port: Int = TransportSettings.DEFAULT_PORT, bindAll: Boolean = true) {
        if (_isRunning.value) return

        this.port = port
        this.bindAll = bindAll

        val bindAddress = if (bindAll) {
            InetAddress.getByName("0.0.0.0")
        } else {
            InetAddress.getLoopbackAddress()
        }

        val socket = ServerSocket(port, 50, bindAddress)
        serverSocket = socket

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope
        _isRunning.value = true

        scope.launch {
            try {
                while (isActive && !socket.isClosed) {
                    val clientSocket = socket.accept()
                    val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val session = PondClientSession(
                        socket = clientSocket,
                        runtime = runtime,
                        registry = registry,
                        scope = clientScope,
                        onDisconnect = ::removeClient,
                    )
                    clients[session.sessionId] = session
                    updateClientState()
                    session.start()
                }
            } catch (_: Exception) {
                // ServerSocket closed
            }
        }
    }

    fun stop() {
        clients.values.forEach { it.close() }
        clients.clear()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverScope?.cancel()
        serverScope = null
        _isRunning.value = false
        updateClientState()
    }

    private fun removeClient(session: PondClientSession) {
        clients.remove(session.sessionId)
        updateClientState()
    }

    private fun updateClientState() {
        val clientsArray = JSONArray()
        clients.values.forEach { clientsArray.put(it.toInfoJson()) }
        _connections.value = JSONObject().put("clients", clientsArray)
        runtime.updateConnectionInfo(_connections.value)
    }
}
