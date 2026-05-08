package id.local.transfermonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HashingTest {
    @Test
    fun notificationIdempotencyKeyUsesTitleAndMessageOnly() {
        val title = "Catatan Finansial"
        val message = "Pemasukan sebesar IDR 1,231.00 dari ***SKY ****LIA *IJ di kategori Transfer Rekening."

        val first = notificationIdempotencyKey(title = title, message = message)
        val duplicateWithWhitespace = notificationIdempotencyKey(
            title = " $title ",
            message = "\n$message\n",
        )
        val changedMessage = notificationIdempotencyKey(
            title = title,
            message = message.replace("1,231.00", "1,232.00"),
        )

        assertEquals(first, duplicateWithWhitespace)
        assertNotEquals(first, changedMessage)
    }
}
