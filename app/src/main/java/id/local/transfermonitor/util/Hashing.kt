package id.local.transfermonitor.util

import java.security.MessageDigest

fun sha256Hex(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

fun notificationIdempotencyKey(title: String, message: String): String =
    sha256Hex(listOf(title.trim(), message.trim()).joinToString(separator = "|"))
