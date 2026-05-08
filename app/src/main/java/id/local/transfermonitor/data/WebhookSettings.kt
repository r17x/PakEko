package id.local.transfermonitor.data

import android.content.Context

class WebhookSettings(context: Context) {
    private val preferences =
        context.getSharedPreferences("webhook_settings", Context.MODE_PRIVATE)

    fun url(): String =
        preferences.getString(KEY_WEBHOOK_URL, "").orEmpty()

    fun setUrl(url: String) {
        preferences.edit()
            .putString(KEY_WEBHOOK_URL, url.trim())
            .apply()
    }

    companion object {
        private const val KEY_WEBHOOK_URL = "webhook_url"
    }
}
