package id.local.transfermonitor.protocol

data class StreamAddress(
    val owner: String,
    val name: String,
) {
    val address: String get() = "$owner:$name"

    override fun toString(): String = address

    companion object {
        private val PATTERN = Regex("^[a-zA-Z0-9_-]+:[a-zA-Z0-9_./-]+$")

        fun parse(address: String): StreamAddress? {
            if (!PATTERN.matches(address)) return null
            val colonIndex = address.indexOf(':')
            return StreamAddress(
                owner = address.substring(0, colonIndex),
                name = address.substring(colonIndex + 1),
            )
        }

        // App-level streams
        val EVENTS = StreamAddress("app", "events")
        val DETECTIONS = StreamAddress("app", "detections")
        val DELIVERIES = StreamAddress("app", "deliveries")
        val MONITORED = StreamAddress("app", "monitored")

        // Runtime introspection streams
        val CONNECTIONS = StreamAddress("runtime", "connections")
        val STREAMS = StreamAddress("runtime", "streams")
    }
}
