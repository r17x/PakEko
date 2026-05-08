package id.local.transfermonitor.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import id.local.transfermonitor.data.NotificationEvent
import id.local.transfermonitor.data.PaymentDetection
import id.local.transfermonitor.data.TransferDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ExportResult(
    val fileName: String,
    val location: String,
    val eventCount: Int,
    val detectionCount: Int,
)

data class DatabaseExportResult(
    val fileName: String,
    val location: String,
)

object DebugExport {
    fun exportJson(
        context: Context,
        database: TransferDatabase,
        limit: Int = 500,
    ): ExportResult {
        val events = database.recentEvents(limit)
        val detections = database.recentDetections(limit)
        val fileName = "pakeko-${timestampForFile()}.json"
        val payload = JSONObject()
            .put("generated_at", Instant.now().toString())
            .put("event_count", events.size)
            .put("detection_count", detections.size)
            .put("events", JSONArray(events.map { it.toJson() }))
            .put("detections", JSONArray(detections.map { it.toJson() }))
            .toString(2)

        val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeToPublicDownloads(context, fileName, payload)
        } else {
            writeToAppDownloads(context, fileName, payload)
        }

        return ExportResult(
            fileName = fileName,
            location = location,
            eventCount = events.size,
            detectionCount = detections.size,
        )
    }

    fun exportSqliteDatabase(
        context: Context,
        database: TransferDatabase,
    ): DatabaseExportResult {
        val fileName = "pakeko-${timestampForFile()}.db"
        checkpointForExport(database)
        val source = File(database.readableDatabase.path)

        val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            writeFileToPublicDownloads(
                context = context,
                fileName = fileName,
                mimeType = "application/vnd.sqlite3",
                source = source,
            )
        } else {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            if (!directory.exists()) directory.mkdirs()
            val destination = File(directory, fileName)
            source.copyTo(destination, overwrite = true)
            destination.absolutePath
        }

        return DatabaseExportResult(fileName = fileName, location = location)
    }

    private fun writeToPublicDownloads(
        context: Context,
        fileName: String,
        payload: String,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = requireNotNull(
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "Could not create export file" }

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(payload.toByteArray(Charsets.UTF_8))
        } ?: error("Could not open export file")

        return "${Environment.DIRECTORY_DOWNLOADS}/$fileName"
    }

    private fun writeFileToPublicDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        source: File,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = requireNotNull(
            context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "Could not create database export file" }

        context.contentResolver.openOutputStream(uri)?.use { outputStream ->
            source.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: error("Could not open database export file")

        return "${Environment.DIRECTORY_DOWNLOADS}/$fileName"
    }

    private fun writeToAppDownloads(
        context: Context,
        fileName: String,
        payload: String,
    ): String {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        if (!directory.exists()) directory.mkdirs()
        val file = File(directory, fileName)
        file.writeText(payload, Charsets.UTF_8)
        return file.absolutePath
    }

    private fun NotificationEvent.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("package_name", packageName)
            .put("app_label", appLabel)
            .put("title", title)
            .put("text", text)
            .put("big_text", bigText)
            .put("sub_text", subText)
            .put("posted_at", Instant.ofEpochMilli(postedAt).toString())
            .put("captured_at", Instant.ofEpochMilli(capturedAt).toString())
            .put("raw_hash", rawHash)
            .put("idempotency_key", idempotencyKey)
            .put("ignored_reason", ignoredReason)

    private fun PaymentDetection.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("notification_event_id", notificationEventId)
            .put("idempotency_key", idempotencyKey)
            .put("bank_code", bankCode)
            .put("amount", amount)
            .put("currency", currency)
            .put("confidence", confidence)
            .put("parser_version", parserVersion)
            .put("status", status)
            .put("webhook_status", webhookStatus)
            .put("webhook_status_code", webhookStatusCode)
            .put("webhook_message", webhookMessage)
            .put("webhook_attempt_count", webhookAttemptCount)
            .put("created_at", Instant.ofEpochMilli(createdAt).toString())

    private fun checkpointForExport(database: TransferDatabase) {
        try {
            database.readableDatabase
                .rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray())
                .use { cursor -> if (cursor.moveToFirst()) Unit }
        } catch (_: Exception) {
            // The default journal mode may not use WAL. In that case the main db file is enough.
        }
    }

    private fun timestampForFile(): String =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
}
