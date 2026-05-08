package id.local.transfermonitor.util

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import id.local.transfermonitor.monitor.BankNotificationListenerService

fun Context.hasNotificationListenerAccess(): Boolean {
    val componentName = ComponentName(this, BankNotificationListenerService::class.java)
    val enabledListeners =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()

    return enabledListeners.split(":")
        .mapNotNull { ComponentName.unflattenFromString(it) }
        .any { it == componentName }
}
