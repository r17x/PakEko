package id.local.transfermonitor.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import id.local.transfermonitor.parser.PaymentNotificationParser
import id.local.transfermonitor.parser.PaymentParseDecision
import id.local.transfermonitor.parser.SegmentSerializer
import id.local.transfermonitor.util.WebhookSendResult

class TransferDatabase(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createNotificationEventsTable(db)
        createPaymentDetectionsTable(db)
        createWebhookDeliveriesTable(db)
        createMonitoredAppsTable(db)
        createUserPatternsTable(db)
        createIndexes(db)
        seedBcaPatterns(db, System.currentTimeMillis())
        db.execSQL(
            "INSERT OR IGNORE INTO monitored_apps (package_name, app_label, added_at) VALUES ('$BCA_PACKAGE', 'myBCA', ${System.currentTimeMillis()})"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    private fun createNotificationEventsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE notification_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                app_label TEXT NOT NULL,
                title TEXT NOT NULL,
                text TEXT NOT NULL,
                big_text TEXT NOT NULL,
                sub_text TEXT NOT NULL,
                posted_at INTEGER NOT NULL,
                captured_at INTEGER NOT NULL,
                raw_hash TEXT NOT NULL,
                idempotency_key TEXT NOT NULL,
                ignored_reason TEXT
            )
            """.trimIndent()
        )
    }

    private fun createPaymentDetectionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE payment_detections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                notification_event_id INTEGER NOT NULL,
                idempotency_key TEXT NOT NULL,
                bank_code TEXT NOT NULL,
                amount INTEGER NOT NULL,
                currency TEXT NOT NULL,
                confidence REAL NOT NULL,
                parser_version TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(notification_event_id) REFERENCES notification_events(id)
            )
            """.trimIndent()
        )
    }

    private fun createWebhookDeliveriesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE webhook_deliveries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                payment_detection_id INTEGER NOT NULL,
                idempotency_key TEXT NOT NULL UNIQUE,
                webhook_url TEXT NOT NULL,
                status TEXT NOT NULL,
                http_status_code INTEGER,
                message TEXT NOT NULL,
                attempt_count INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                FOREIGN KEY(payment_detection_id) REFERENCES payment_detections(id)
            )
            """.trimIndent()
        )
    }

    private fun createMonitoredAppsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE monitored_apps (
                package_name TEXT PRIMARY KEY NOT NULL,
                app_label TEXT NOT NULL,
                added_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createUserPatternsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE user_patterns (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                label TEXT NOT NULL,
                direction TEXT NOT NULL,
                match_field TEXT NOT NULL,
                title_text TEXT,
                segments_json TEXT NOT NULL,
                confidence REAL NOT NULL DEFAULT 0.95,
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_user_patterns_package ON user_patterns(package_name)")
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE INDEX idx_notification_events_captured_at ON notification_events(captured_at DESC)"
        )
        db.execSQL(
            "CREATE INDEX idx_notification_events_idempotency_key ON notification_events(idempotency_key)"
        )
        db.execSQL(
            "CREATE INDEX idx_payment_detections_created_at ON payment_detections(created_at DESC)"
        )
        db.execSQL(
            "CREATE INDEX idx_payment_detections_idempotency_key ON payment_detections(idempotency_key)"
        )
        db.execSQL(
            "CREATE INDEX idx_webhook_deliveries_updated_at ON webhook_deliveries(updated_at DESC)"
        )
    }

    private fun seedBcaPatterns(db: SQLiteDatabase, now: Long) {
        data class SeedPattern(val label: String, val direction: PatternDirection, val titleText: String, val segmentsJson: String)
        val patterns = listOf(
            SeedPattern("BCA incoming (EN)", PatternDirection.INCOMING,
                "Financial Diary",
                SegmentSerializer.toJson(listOf(
                    PatternSegment("You received", SegmentRole.LITERAL),
                    PatternSegment("", SegmentRole.AMOUNT),
                    PatternSegment("from", SegmentRole.LITERAL),
                    PatternSegment("", SegmentRole.SENDER),
                    PatternSegment("at Account Transfer category.", SegmentRole.LITERAL),
                ))),
            SeedPattern("BCA incoming (ID)", PatternDirection.INCOMING,
                "Catatan Finansial",
                SegmentSerializer.toJson(listOf(
                    PatternSegment("Pemasukan sebesar", SegmentRole.LITERAL),
                    PatternSegment("", SegmentRole.AMOUNT),
                    PatternSegment("dari", SegmentRole.LITERAL),
                    PatternSegment("", SegmentRole.SENDER),
                    PatternSegment("di kategori Transfer Rekening.", SegmentRole.LITERAL),
                ))),
            SeedPattern("BCA outgoing (EN)", PatternDirection.OUTGOING,
                "Financial Diary",
                SegmentSerializer.toJson(listOf(
                    PatternSegment("You sent", SegmentRole.LITERAL),
                    PatternSegment("", SegmentRole.AMOUNT),
                    PatternSegment("", SegmentRole.WILDCARD),
                ))),
            SeedPattern("BCA outgoing (ID)", PatternDirection.OUTGOING,
                "Catatan Finansial",
                SegmentSerializer.toJson(listOf(
                    PatternSegment("Pengeluaran sebesar", SegmentRole.LITERAL),
                    PatternSegment("", SegmentRole.AMOUNT),
                    PatternSegment("di kategori", SegmentRole.LITERAL),
                    PatternSegment("", SegmentRole.WILDCARD),
                ))),
        )
        patterns.forEach { p ->
            db.execSQL(
                """INSERT INTO user_patterns (package_name, label, direction, match_field, title_text, segments_json, confidence, enabled, created_at, updated_at)
                   VALUES (?, ?, ?, 'body', ?, ?, 0.99, 1, ?, ?)""",
                arrayOf<Any>(BCA_PACKAGE, p.label, p.direction.name.lowercase(), p.titleText, p.segmentsJson, now, now),
            )
        }
    }

    fun insertCapturedNotification(captured: CapturedNotification): CaptureInsertResult {
        val now = System.currentTimeMillis()
        return writableDatabase.transaction {
            existingEventIdFor(captured)?.let { existingEventId ->
                return@transaction CaptureInsertResult(
                    eventId = existingEventId,
                    detection = null,
                    duplicate = true,
                )
            }

            val eventId = insert(
                "notification_events",
                null,
                ContentValues().apply {
                    put("package_name", captured.packageName)
                    put("app_label", captured.appLabel)
                    put("title", captured.title)
                    put("text", captured.text)
                    put("big_text", captured.bigText)
                    put("sub_text", captured.subText)
                    put("posted_at", captured.postedAt)
                    put("captured_at", now)
                    put("raw_hash", captured.rawHash)
                    put("idempotency_key", captured.idempotencyKey)
                    put("ignored_reason", captured.ignoredReason)
                }
            )

            var detection: PaymentDetection? = null
            if (captured.parsedAmount != null && captured.ignoredReason == null) {
                val detectionId = insert(
                    "payment_detections",
                    null,
                    ContentValues().apply {
                        put("notification_event_id", eventId)
                        put("idempotency_key", captured.idempotencyKey)
                        put("bank_code", captured.bankCode)
                        put("amount", captured.parsedAmount)
                        put("currency", "IDR")
                        put("confidence", captured.confidence)
                        put("parser_version", CURRENT_PARSER_VERSION)
                        put("status", "new")
                        put("created_at", now)
                    }
                )
                detection = PaymentDetection(
                    id = detectionId,
                    notificationEventId = eventId,
                    idempotencyKey = captured.idempotencyKey,
                    bankCode = captured.bankCode,
                    amount = captured.parsedAmount,
                    currency = "IDR",
                    confidence = captured.confidence,
                    parserVersion = CURRENT_PARSER_VERSION,
                    status = "new",
                    createdAt = now,
                    webhookStatus = null,
                    webhookStatusCode = null,
                    webhookMessage = null,
                    webhookAttemptCount = null,
                )
            }

            CaptureInsertResult(eventId = eventId, detection = detection, duplicate = false)
        }
    }

    fun recentEvents(limit: Int = 100): List<NotificationEvent> =
        readableDatabase.rawQuery(
            """
            SELECT id, package_name, app_label, title, text, big_text, sub_text,
                   posted_at, captured_at, raw_hash, idempotency_key, ignored_reason
            FROM notification_events
            ORDER BY captured_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toNotificationEvent())
                }
            }
        }

    fun filteredEvents(
        packageName: String? = null,
        onlyParsed: Boolean? = null,
        sinceMs: Long? = null,
        limit: Int = 200,
    ): List<NotificationEvent> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        packageName?.let {
            conditions.add("package_name = ?")
            args.add(it)
        }
        onlyParsed?.let { parsed ->
            if (parsed) conditions.add("ignored_reason IS NULL")
            else conditions.add("ignored_reason IS NOT NULL")
        }
        sinceMs?.let {
            conditions.add("captured_at >= ?")
            args.add(it.toString())
        }

        val where = if (conditions.isEmpty()) "" else "WHERE ${conditions.joinToString(" AND ")}"
        return readableDatabase.rawQuery(
            """
            SELECT id, package_name, app_label, title, text, big_text, sub_text,
                   posted_at, captured_at, raw_hash, idempotency_key, ignored_reason
            FROM notification_events
            $where
            ORDER BY captured_at DESC
            LIMIT ?
            """.trimIndent(),
            (args + limit.toString()).toTypedArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toNotificationEvent())
                }
            }
        }
    }

    fun recentDetections(limit: Int = 100): List<PaymentDetection> =
        readableDatabase.rawQuery(
            """
            SELECT d.id, d.notification_event_id, d.idempotency_key, d.bank_code, d.amount,
                   d.currency, d.confidence, d.parser_version, d.status, d.created_at,
                   wd.status, wd.http_status_code, wd.message, wd.attempt_count
            FROM payment_detections d
            LEFT JOIN webhook_deliveries wd ON wd.idempotency_key = d.idempotency_key
            ORDER BY d.created_at DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toPaymentDetection())
                }
            }
        }

    fun latestDetection(): PaymentDetection? =
        readableDatabase.rawQuery(
            """
            SELECT d.id, d.notification_event_id, d.idempotency_key, d.bank_code, d.amount,
                   d.currency, d.confidence, d.parser_version, d.status, d.created_at,
                   wd.status, wd.http_status_code, wd.message, wd.attempt_count
            FROM payment_detections d
            LEFT JOIN webhook_deliveries wd ON wd.idempotency_key = d.idempotency_key
            ORDER BY d.created_at DESC
            LIMIT 1
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toPaymentDetection() else null
        }

    fun retryableWebhookDetections(limit: Int = 100): List<PaymentDetection> =
        readableDatabase.rawQuery(
            """
            SELECT d.id, d.notification_event_id, d.idempotency_key, d.bank_code, d.amount,
                   d.currency, d.confidence, d.parser_version, d.status, d.created_at,
                   wd.status, wd.http_status_code, wd.message, wd.attempt_count
            FROM payment_detections d
            INNER JOIN webhook_deliveries wd ON wd.idempotency_key = d.idempotency_key
            WHERE wd.status IN (?, ?, ?)
            ORDER BY wd.updated_at ASC
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                WEBHOOK_STATUS_PENDING,
                WEBHOOK_STATUS_FAILED,
                WEBHOOK_STATUS_SKIPPED,
                limit.toString(),
            ),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toPaymentDetection())
                }
            }
        }

    fun parseStoredEvent(eventId: Long, userPatterns: List<UserPattern> = emptyList()): SingleParseResult? {
        val now = System.currentTimeMillis()
        return writableDatabase.transaction {
            val event = eventById(eventId) ?: return@transaction null
            val decision = PaymentNotificationParser.parse(
                packageName = event.packageName,
                title = event.title,
                text = event.text,
                bigText = event.bigText,
                subText = event.subText,
                userPatterns = userPatterns,
            )
            val duplicate = hasEarlierEventWithIdempotencyKey(event)
            val ignoredReason = if (duplicate) {
                "duplicate_notification"
            } else {
                decision.ignoredReason
            }

            updateEventIgnoredReason(event.id, ignoredReason)

            if (duplicate || decision.parsedAmount == null || decision.ignoredReason != null) {
                delete("payment_detections", "notification_event_id = ?", arrayOf(event.id.toString()))
                return@transaction SingleParseResult(
                    eventId = event.id,
                    detection = null,
                    ignoredReason = ignoredReason ?: "no_idr_amount",
                )
            }

            val detection = upsertPaymentDetection(
                event = event,
                bankCode = decision.bankCode,
                amount = decision.parsedAmount,
                confidence = decision.confidence,
                createdAt = now,
            )

            SingleParseResult(
                eventId = event.id,
                detection = detection,
                ignoredReason = null,
            )
        }
    }

    fun reparseStoredEvents(monitoredPackages: Set<String> = emptySet(), userPatterns: List<UserPattern> = emptyList()): ReparseResult {
        val events = allEvents()
        val now = System.currentTimeMillis()

        return writableDatabase.transaction {
            delete("payment_detections", null, null)

            var detectionCount = 0
            var ignoredCount = 0
            val seenIdempotencyKeys = mutableSetOf<String>()

            events.forEach { event ->
                val isMonitored = event.packageName in monitoredPackages
                val decision = if (isMonitored) {
                    PaymentNotificationParser.parse(
                        packageName = event.packageName,
                        title = event.title,
                        text = event.text,
                        bigText = event.bigText,
                        subText = event.subText,
                        userPatterns = userPatterns,
                    )
                } else {
                    PaymentParseDecision.notMonitored(event.packageName)
                }
                val duplicate = !seenIdempotencyKeys.add(event.idempotencyKey)
                val ignoredReason = if (duplicate) {
                    "duplicate_notification"
                } else {
                    decision.ignoredReason
                }

                update(
                    "notification_events",
                    ContentValues().apply {
                        if (ignoredReason == null) {
                            putNull("ignored_reason")
                        } else {
                            put("ignored_reason", ignoredReason)
                        }
                    },
                    "id = ?",
                    arrayOf(event.id.toString())
                )

                if (!duplicate && decision.parsedAmount != null && decision.ignoredReason == null) {
                    val detectionId = insert(
                        "payment_detections",
                        null,
                        ContentValues().apply {
                            put("notification_event_id", event.id)
                            put("idempotency_key", event.idempotencyKey)
                            put("bank_code", decision.bankCode)
                            put("amount", decision.parsedAmount)
                            put("currency", "IDR")
                            put("confidence", decision.confidence)
                            put("parser_version", CURRENT_PARSER_VERSION)
                            put("status", "new")
                            put("created_at", now)
                        }
                    )
                    update(
                        "webhook_deliveries",
                        ContentValues().apply {
                            put("payment_detection_id", detectionId)
                            put("updated_at", now)
                        },
                        "idempotency_key = ?",
                        arrayOf(event.idempotencyKey),
                    )
                    detectionCount += 1
                } else {
                    ignoredCount += 1
                }
            }

            ReparseResult(
                eventCount = events.size,
                detectionCount = detectionCount,
                ignoredCount = ignoredCount,
            )
        }
    }

    fun createWebhookDelivery(
        detection: PaymentDetection,
        webhookUrl: String,
        allowPendingRetry: Boolean = false,
    ): WebhookDelivery? {
        val now = System.currentTimeMillis()
        val cleanUrl = webhookUrl.trim()
        val status = if (cleanUrl.isBlank()) WEBHOOK_STATUS_SKIPPED else WEBHOOK_STATUS_PENDING
        val message = if (cleanUrl.isBlank()) "No webhook URL configured" else "Pending"

        return writableDatabase.transaction {
            val deliveryId = insertWithOnConflict(
                "webhook_deliveries",
                null,
                ContentValues().apply {
                    put("payment_detection_id", detection.id)
                    put("idempotency_key", detection.idempotencyKey)
                    put("webhook_url", cleanUrl)
                    put("status", status)
                    putNull("http_status_code")
                    put("message", message)
                    put("attempt_count", 0)
                    put("created_at", now)
                    put("updated_at", now)
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )

            if (deliveryId == -1L) {
                val existing = webhookDeliveryByIdempotencyKey(detection.idempotencyKey)
                    ?: return@transaction null

                if (
                    existing.status == WEBHOOK_STATUS_SENT ||
                    (existing.status == WEBHOOK_STATUS_PENDING && !allowPendingRetry)
                ) {
                    return@transaction null
                }

                if (cleanUrl.isBlank()) {
                    return@transaction existing
                }

                update(
                    "webhook_deliveries",
                    ContentValues().apply {
                        put("payment_detection_id", detection.id)
                        put("webhook_url", cleanUrl)
                        put("status", WEBHOOK_STATUS_PENDING)
                        putNull("http_status_code")
                        put("message", "Pending")
                        put("updated_at", now)
                    },
                    "id = ?",
                    arrayOf(existing.id.toString()),
                )

                existing.copy(
                    paymentDetectionId = detection.id,
                    webhookUrl = cleanUrl,
                    status = WEBHOOK_STATUS_PENDING,
                    httpStatusCode = null,
                    message = "Pending",
                    updatedAt = now,
                )
            } else {
                WebhookDelivery(
                    id = deliveryId,
                    paymentDetectionId = detection.id,
                    idempotencyKey = detection.idempotencyKey,
                    webhookUrl = cleanUrl,
                    status = status,
                    httpStatusCode = null,
                    message = message,
                    attemptCount = 0,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        }
    }

    fun updateWebhookDelivery(
        delivery: WebhookDelivery,
        result: WebhookSendResult,
    ) {
        val now = System.currentTimeMillis()
        writableDatabase.update(
            "webhook_deliveries",
            ContentValues().apply {
                put("status", if (result.sent) WEBHOOK_STATUS_SENT else WEBHOOK_STATUS_FAILED)
                if (result.statusCode == null) {
                    putNull("http_status_code")
                } else {
                    put("http_status_code", result.statusCode)
                }
                put("message", result.message)
                put("attempt_count", delivery.attemptCount + 1)
                put("updated_at", now)
            },
            "id = ?",
            arrayOf(delivery.id.toString()),
        )
    }

    fun deleteAll() {
        writableDatabase.transaction {
            delete("user_patterns", null, null)
            delete("webhook_deliveries", null, null)
            delete("payment_detections", null, null)
            delete("notification_events", null, null)
        }
    }

    private fun allEvents(): List<NotificationEvent> =
        readableDatabase.rawQuery(
            """
            SELECT id, package_name, app_label, title, text, big_text, sub_text,
                   posted_at, captured_at, raw_hash, idempotency_key, ignored_reason
            FROM notification_events
            ORDER BY captured_at ASC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toNotificationEvent())
                }
            }
        }

    private fun Cursor.toNotificationEvent(): NotificationEvent =
        NotificationEvent(
            id = getLong(0),
            packageName = getString(1),
            appLabel = getString(2),
            title = getString(3),
            text = getString(4),
            bigText = getString(5),
            subText = getString(6),
            postedAt = getLong(7),
            capturedAt = getLong(8),
            rawHash = getString(9),
            idempotencyKey = getString(10),
            ignoredReason = if (isNull(11)) null else getString(11),
        )

    private fun Cursor.toPaymentDetection(): PaymentDetection =
        PaymentDetection(
            id = getLong(0),
            notificationEventId = getLong(1),
            idempotencyKey = getString(2),
            bankCode = getString(3),
            amount = getLong(4),
            currency = getString(5),
            confidence = getDouble(6),
            parserVersion = getString(7),
            status = getString(8),
            createdAt = getLong(9),
            webhookStatus = if (isNull(10)) null else getString(10),
            webhookStatusCode = if (isNull(11)) null else getInt(11),
            webhookMessage = if (isNull(12)) null else getString(12),
            webhookAttemptCount = if (isNull(13)) null else getInt(13),
        )

    private fun SQLiteDatabase.webhookDeliveryByIdempotencyKey(idempotencyKey: String): WebhookDelivery? =
        rawQuery(
            """
            SELECT id, payment_detection_id, idempotency_key, webhook_url, status,
                   http_status_code, message, attempt_count, created_at, updated_at
            FROM webhook_deliveries
            WHERE idempotency_key = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(idempotencyKey),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toWebhookDelivery() else null
        }

    private fun Cursor.toWebhookDelivery(): WebhookDelivery =
        WebhookDelivery(
            id = getLong(0),
            paymentDetectionId = getLong(1),
            idempotencyKey = getString(2),
            webhookUrl = getString(3),
            status = getString(4),
            httpStatusCode = if (isNull(5)) null else getInt(5),
            message = getString(6),
            attemptCount = getInt(7),
            createdAt = getLong(8),
            updatedAt = getLong(9),
        )

    private fun SQLiteDatabase.eventById(eventId: Long): NotificationEvent? =
        rawQuery(
            """
            SELECT id, package_name, app_label, title, text, big_text, sub_text,
                   posted_at, captured_at, raw_hash, idempotency_key, ignored_reason
            FROM notification_events
            WHERE id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(eventId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toNotificationEvent() else null
        }

    private fun SQLiteDatabase.hasEarlierEventWithIdempotencyKey(event: NotificationEvent): Boolean =
        rawQuery(
            """
            SELECT 1
            FROM notification_events
            WHERE idempotency_key = ?
              AND id < ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(event.idempotencyKey, event.id.toString()),
        ).use { cursor -> cursor.moveToFirst() }

    private fun SQLiteDatabase.updateEventIgnoredReason(eventId: Long, ignoredReason: String?) {
        update(
            "notification_events",
            ContentValues().apply {
                if (ignoredReason == null) {
                    putNull("ignored_reason")
                } else {
                    put("ignored_reason", ignoredReason)
                }
            },
            "id = ?",
            arrayOf(eventId.toString()),
        )
    }

    private fun SQLiteDatabase.upsertPaymentDetection(
        event: NotificationEvent,
        bankCode: String,
        amount: Long,
        confidence: Double,
        createdAt: Long,
    ): PaymentDetection {
        val existingId = rawQuery(
            """
            SELECT id
            FROM payment_detections
            WHERE notification_event_id = ?
               OR idempotency_key = ?
            ORDER BY id ASC
            LIMIT 1
            """.trimIndent(),
            arrayOf(event.id.toString(), event.idempotencyKey),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

        val values = ContentValues().apply {
            put("notification_event_id", event.id)
            put("idempotency_key", event.idempotencyKey)
            put("bank_code", bankCode)
            put("amount", amount)
            put("currency", "IDR")
            put("confidence", confidence)
            put("parser_version", CURRENT_PARSER_VERSION)
            put("status", "new")
            put("created_at", createdAt)
        }

        val detectionId = if (existingId == null) {
            insert("payment_detections", null, values)
        } else {
            update(
                "payment_detections",
                values,
                "id = ?",
                arrayOf(existingId.toString()),
            )
            existingId
        }

        update(
            "webhook_deliveries",
            ContentValues().apply {
                put("payment_detection_id", detectionId)
                put("updated_at", createdAt)
            },
            "idempotency_key = ?",
            arrayOf(event.idempotencyKey),
        )

        return requireNotNull(paymentDetectionById(detectionId)) {
            "Detection insert failed"
        }
    }

    private fun SQLiteDatabase.paymentDetectionById(detectionId: Long): PaymentDetection? =
        rawQuery(
            """
            SELECT d.id, d.notification_event_id, d.idempotency_key, d.bank_code, d.amount,
                   d.currency, d.confidence, d.parser_version, d.status, d.created_at,
                   wd.status, wd.http_status_code, wd.message, wd.attempt_count
            FROM payment_detections d
            LEFT JOIN webhook_deliveries wd ON wd.idempotency_key = d.idempotency_key
            WHERE d.id = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(detectionId.toString()),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toPaymentDetection() else null
        }

    private fun SQLiteDatabase.existingEventIdFor(captured: CapturedNotification): Long? =
        rawQuery(
            """
            SELECT id
            FROM notification_events
            WHERE idempotency_key = ?
               OR (title = ? AND text = ?)
            ORDER BY captured_at ASC
            LIMIT 1
            """.trimIndent(),
            arrayOf(captured.idempotencyKey, captured.title, captured.text),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            val result = block()
            setTransactionSuccessful()
            result
        } finally {
            endTransaction()
        }
    }

    // --- MonitoredAppRepository methods ---

    fun monitoredPackages(): Set<String> =
        readableDatabase.rawQuery("SELECT package_name FROM monitored_apps", emptyArray()).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    fun allMonitoredApps(): List<MonitoredApp> =
        readableDatabase.rawQuery(
            "SELECT package_name, app_label, added_at FROM monitored_apps ORDER BY app_label ASC",
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(MonitoredApp(
                        packageName = cursor.getString(0),
                        appLabel = cursor.getString(1),
                        addedAt = cursor.getLong(2),
                    ))
                }
            }
        }

    fun addApp(packageName: String, appLabel: String) {
        writableDatabase.insertWithOnConflict(
            "monitored_apps", null,
            ContentValues().apply {
                put("package_name", packageName)
                put("app_label", appLabel)
                put("added_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun removeApp(packageName: String) {
        writableDatabase.delete("monitored_apps", "package_name = ?", arrayOf(packageName))
    }

    fun isMonitored(packageName: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM monitored_apps WHERE package_name = ? LIMIT 1",
            arrayOf(packageName)
        ).use { cursor -> cursor.moveToFirst() }

    // --- UserPatternRepository methods ---

    fun patternsForPackage(packageName: String): List<UserPattern> =
        queryPatterns("WHERE package_name = ?", arrayOf(packageName))

    fun allPatterns(): List<UserPattern> =
        queryPatterns(null, emptyArray())

    fun allEnabledPatterns(): List<UserPattern> =
        queryPatterns("WHERE enabled = 1", emptyArray())

    private fun queryPatterns(where: String?, args: Array<String>): List<UserPattern> =
        readableDatabase.rawQuery(
            "$PATTERN_SELECT_COLUMNS ${where ?: ""} ORDER BY id ASC",
            args,
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toUserPattern()) }
        }

    fun insertPattern(
        packageName: String, label: String, direction: PatternDirection,
        matchField: MatchField, titleText: String?, segmentsJson: String, confidence: Double,
    ): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insert("user_patterns", null,
            ContentValues().apply {
                put("package_name", packageName)
                put("label", label)
                put("direction", direction.name.lowercase())
                put("match_field", matchField.name.lowercase())
                if (titleText != null) put("title_text", titleText) else putNull("title_text")
                put("segments_json", segmentsJson)
                put("confidence", confidence)
                put("enabled", 1)
                put("created_at", now)
                put("updated_at", now)
            }
        )
    }

    fun updatePattern(pattern: UserPattern) {
        val now = System.currentTimeMillis()
        writableDatabase.update("user_patterns",
            ContentValues().apply {
                put("label", pattern.label)
                put("direction", pattern.direction.name.lowercase())
                put("match_field", pattern.matchField.name.lowercase())
                if (pattern.titleText != null) put("title_text", pattern.titleText) else putNull("title_text")
                put("segments_json", pattern.segmentsJson)
                put("confidence", pattern.confidence)
                put("enabled", if (pattern.enabled) 1 else 0)
                put("updated_at", now)
            },
            "id = ?", arrayOf(pattern.id.toString()),
        )
    }

    fun deletePattern(id: Long) {
        writableDatabase.delete("user_patterns", "id = ?", arrayOf(id.toString()))
    }

    fun toggleEnabled(id: Long, enabled: Boolean) {
        writableDatabase.update("user_patterns",
            ContentValues().apply {
                put("enabled", if (enabled) 1 else 0)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?", arrayOf(id.toString()),
        )
    }

    private fun Cursor.toUserPattern(): UserPattern =
        UserPattern(
            id = getLong(0), packageName = getString(1), label = getString(2),
            direction = PatternDirection.valueOf(getString(3).uppercase()),
            matchField = MatchField.valueOf(getString(4).uppercase()),
            titleText = if (isNull(5)) null else getString(5),
            segmentsJson = getString(6), confidence = getDouble(7),
            enabled = getInt(8) == 1, createdAt = getLong(9), updatedAt = getLong(10),
        )

    companion object {
        private const val DATABASE_NAME = "transfer_monitor.db"
        private const val DATABASE_VERSION = 1
        private const val BCA_PACKAGE = "com.bca.mybca.omni.android"
        private const val WEBHOOK_STATUS_PENDING = "pending"
        private const val WEBHOOK_STATUS_SENT = "sent"
        private const val WEBHOOK_STATUS_FAILED = "failed"
        private const val WEBHOOK_STATUS_SKIPPED = "skipped"
        private const val PATTERN_SELECT_COLUMNS = "SELECT id, package_name, label, direction, match_field, title_text, segments_json, confidence, enabled, created_at, updated_at FROM user_patterns"
    }
}
