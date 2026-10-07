package com.sticky.reminder

/**
 * "GG.AA.YYYY" veya "GG.AA.YYYY SS:DD" metnini, yazılan zamanı saat dilimsiz sayarak
 * 1970'ten beri geçen milisaniyeye çevirir. Saat yazılmazsa günün sonu (23:59) alınır.
 * Okunamayan metin için null döner. Windows tarafındaki `deadline.rs` ile aynı kuraldır.
 */
object Deadline {
    private const val MAX_YEAR = 9999L

    fun parse(text: String): Long? {
        val trimmed = text.trim()
        val split = trimmed.indexOfFirst { it.isWhitespace() }
        val datePart = if (split < 0) trimmed else trimmed.substring(0, split)
        val timePart = if (split < 0) null else trimmed.substring(split).trim()

        val parts = datePart.split('.')
        if (parts.size != 3) return null
        val day = number(parts[0]) ?: return null
        val month = number(parts[1]) ?: return null
        val year = number(parts[2]) ?: return null

        var hour = 23L
        var minute = 59L
        if (timePart != null) {
            val colon = timePart.indexOf(':')
            if (colon < 0) return null
            hour = number(timePart.substring(0, colon)) ?: return null
            minute = number(timePart.substring(colon + 1)) ?: return null
        }

        if (year !in 1970..MAX_YEAR || month !in 1..12 || day < 1 ||
            day > daysInMonth(year, month) || hour > 23 || minute > 59
        ) {
            return null
        }

        val days = daysFromCivil(year, month, day)
        return ((days * 24 + hour) * 60 + minute) * 60_000
    }

    private fun number(s: String): Long? {
        if (s.isEmpty() || s.length > 4 || !s.all { it in '0'..'9' }) return null
        return s.toLong()
    }

    private fun isLeap(year: Long) = (year % 4 == 0L && year % 100 != 0L) || year % 400 == 0L

    private fun daysInMonth(year: Long, month: Long): Long = when (month) {
        1L, 3L, 5L, 7L, 8L, 10L, 12L -> 31
        4L, 6L, 9L, 11L -> 30
        else -> if (isLeap(year)) 29 else 28
    }

    /** 1970-01-01'den itibaren gün sayısı (Howard Hinnant'ın algoritması). */
    private fun daysFromCivil(year: Long, month: Long, day: Long): Long {
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12 // mart = 0
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }
}
