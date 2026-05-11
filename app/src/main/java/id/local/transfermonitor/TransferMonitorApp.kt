package id.local.transfermonitor

import android.app.Application
import id.local.transfermonitor.data.TransferDatabase
import id.local.transfermonitor.data.WebhookSettings
import id.local.transfermonitor.protocol.AppRuntime
import id.local.transfermonitor.transport.PondServer

class TransferMonitorApp : Application() {
    val database: TransferDatabase by lazy { TransferDatabase(this) }
    val pondServer: PondServer by lazy { PondServer(runtime) }
    val runtime: AppRuntime by lazy {
        AppRuntime(
            context = this,
            database = database,
            webhookSettings = WebhookSettings(this),
        )
    }
}
