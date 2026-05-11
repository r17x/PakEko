package id.local.transfermonitor.transport

import org.json.JSONArray
import org.json.JSONObject

sealed class PondMessage {
    abstract val type: String

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("type", type)
        encodeFields(json)
        return json
    }

    protected abstract fun encodeFields(json: JSONObject)

    fun toFrame(): ByteArray {
        val payload = toJson().toString().toByteArray(Charsets.UTF_8)
        return payload
    }

    // Client → Server messages

    data class Hello(
        val version: Int,
        val encodings: List<String>,
        val capabilities: List<String>,
        val lastSessionId: String?,
    ) : PondMessage() {
        override val type = "hello"
        override fun encodeFields(json: JSONObject) {
            json.put("version", version)
            json.put("encodings", JSONArray(encodings))
            json.put("capabilities", JSONArray(capabilities))
            if (lastSessionId != null) json.put("last_session_id", lastSessionId)
        }
    }

    data class Subscribe(
        val stream: String,
        val active: Boolean,
    ) : PondMessage() {
        override val type = "subscribe"
        override fun encodeFields(json: JSONObject) {
            json.put("stream", stream)
            json.put("active", active)
        }
    }

    // Server → Client messages

    data class Welcome(
        val version: Int,
        val encoding: String,
        val sessionId: String,
        val streams: List<StreamInfo>,
    ) : PondMessage() {
        override val type = "welcome"
        override fun encodeFields(json: JSONObject) {
            json.put("version", version)
            json.put("encoding", encoding)
            json.put("session_id", sessionId)
            val streamsArray = JSONArray()
            streams.forEach { stream ->
                streamsArray.put(JSONObject().apply {
                    put("address", stream.address)
                    put("mode", stream.mode)
                    put("description", stream.description)
                })
            }
            json.put("streams", streamsArray)
        }
    }

    data class Data(
        val stream: String,
        val seq: Long,
        val payload: JSONObject,
    ) : PondMessage() {
        override val type = "data"
        override fun encodeFields(json: JSONObject) {
            json.put("stream", stream)
            json.put("seq", seq)
            json.put("payload", payload)
        }
    }

    data class AppEvent(
        val status: String,
        val detail: JSONObject?,
    ) : PondMessage() {
        override val type = "app_event"
        override fun encodeFields(json: JSONObject) {
            json.put("status", status)
            if (detail != null) json.put("detail", detail)
        }
    }

    data class Error(
        val code: String,
        val message: String,
    ) : PondMessage() {
        override val type = "error"
        override fun encodeFields(json: JSONObject) {
            json.put("code", code)
            json.put("message", message)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 1

        fun decode(json: JSONObject): PondMessage {
            val type = json.optString("type", "")
            return when (type) {
                "hello" -> Hello(
                    version = json.getInt("version"),
                    encodings = json.getJSONArray("encodings").toStringList(),
                    capabilities = json.optJSONArray("capabilities")?.toStringList() ?: emptyList(),
                    lastSessionId = if (json.has("last_session_id")) json.getString("last_session_id") else null,
                )
                "subscribe" -> Subscribe(
                    stream = json.getString("stream"),
                    active = json.getBoolean("active"),
                )
                "welcome" -> Welcome(
                    version = json.getInt("version"),
                    encoding = json.getString("encoding"),
                    sessionId = json.getString("session_id"),
                    streams = json.optJSONArray("streams")?.toStreamInfoList() ?: emptyList(),
                )
                "data" -> Data(
                    stream = json.getString("stream"),
                    seq = json.getLong("seq"),
                    payload = json.getJSONObject("payload"),
                )
                "app_event" -> AppEvent(
                    status = json.getString("status"),
                    detail = json.optJSONObject("detail"),
                )
                "error" -> Error(
                    code = json.getString("code"),
                    message = json.getString("message"),
                )
                else -> throw PondProtocolException("Unknown message type: $type")
            }
        }

        private fun JSONArray.toStringList(): List<String> =
            (0 until length()).map { getString(it) }

        private fun JSONArray.toStreamInfoList(): List<StreamInfo> =
            (0 until length()).map { i ->
                val obj = getJSONObject(i)
                StreamInfo(
                    address = obj.getString("address"),
                    mode = obj.getString("mode"),
                    description = obj.optString("description", ""),
                )
            }
    }
}

data class StreamInfo(
    val address: String,
    val mode: String,
    val description: String,
)
