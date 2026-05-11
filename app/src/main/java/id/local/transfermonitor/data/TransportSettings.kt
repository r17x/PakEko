package id.local.transfermonitor.data

import android.content.Context

class TransportSettings(context: Context) {
    private val preferences =
        context.getSharedPreferences("transport_settings", Context.MODE_PRIVATE)

    fun port(): Int =
        preferences.getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(port: Int) {
        preferences.edit().putInt(KEY_PORT, port).apply()
    }

    fun bindAll(): Boolean =
        preferences.getBoolean(KEY_BIND_ALL, true)

    fun setBindAll(bindAll: Boolean) {
        preferences.edit().putBoolean(KEY_BIND_ALL, bindAll).apply()
    }

    companion object {
        private const val KEY_PORT = "transport_port"
        private const val KEY_BIND_ALL = "transport_bind_all"
        const val DEFAULT_PORT = 8765
    }
}
