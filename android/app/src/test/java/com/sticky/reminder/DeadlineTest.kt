package com.sticky.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeadlineTest {
    private val day = 86_400_000L

    @Test
    fun epochBasiSifirdir() {
        assertEquals(0L, Deadline.parse("01.01.1970 00:00"))
        assertEquals(day, Deadline.parse("02.01.1970 00:00"))
    }

    @Test
    fun saatYoksaGunSonu() {
        assertEquals(Deadline.parse("25.12.2026 23:59"), Deadline.parse("25.12.2026"))
    }

    @Test
    fun artikYil() {
        val a = Deadline.parse("28.02.2024 00:00")!!
        val b = Deadline.parse("01.03.2024 00:00")!!
        assertEquals(2 * day, b - a)
        val c = Deadline.parse("28.02.2025 00:00")!!
        val d = Deadline.parse("01.03.2025 00:00")!!
        assertEquals(day, d - c)
    }

    @Test
    fun tekHaneliVeBosluklar() {
        assertEquals(Deadline.parse("05.01.2026 09:05"), Deadline.parse("  5.1.2026   9:05 "))
    }

    @Test
    fun windowsTarafiyla_ayniAnahtar() {
        // Rust testlerindeki aynı değer: 07.10.2026 12:00 -> iki platformda da aynı ms olmalı.
        assertEquals(1_791_374_400_000L, Deadline.parse("07.10.2026 12:00"))
    }

    @Test
    fun gecersizlerNullDoner() {
        val bad = listOf(
            "", "abc", "31.04.2026", "29.02.2025", "1.1.2026 25:00", "1.1.2026 10:60",
            "1.13.2026", "0.1.2026", "1.1.1969", "1.1.2026.5", "1.1.2026 10", "yarın",
            "1.1.2026 10:00 fazla",
        )
        for (s in bad) assertNull("\"$s\" okunmamalı", Deadline.parse(s))
    }
}
