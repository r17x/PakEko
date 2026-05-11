package id.local.transfermonitor.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val LOG_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("dd MMM HH:mm:ss")
        .withZone(ZoneId.systemDefault())

fun formatLogTime(epochMillis: Long): String =
    LOG_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis))
