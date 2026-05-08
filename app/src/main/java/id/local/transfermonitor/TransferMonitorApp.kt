package id.local.transfermonitor

import android.app.Application
import id.local.transfermonitor.data.TransferDatabase

class TransferMonitorApp : Application() {
    val database: TransferDatabase by lazy { TransferDatabase(this) }
}
