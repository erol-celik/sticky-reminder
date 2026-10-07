package com.sticky.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TaskLogicTest {
    private val now = 1_000L

    private fun ms(text: String) = Deadline.parse(text)!!

    private fun row(id: String, title: String = id) = TaskLogic.newRow(NewTask(title), now, id)

    private fun recurringRow(id: String, recurrence: String) =
        TaskLogic.newRow(NewTask(id, recurring = true, recurrence = recurrence), now, id)

    private fun patch(row: TaskRow, p: TaskPatch, at: Long = now) = TaskLogic.applyPatch(row, p, at)

    private fun list(rows: List<TaskRow>, at: Long = now, utcOffsetMin: Int = 0) =
        TaskLogic.sorted(rows.map { TaskLogic.toTask(it, at, utcOffsetMin) })

    private fun titles(tasks: List<Task>) = tasks.map { it.title }

    @Test
    fun ekleVeVarsayilanlar() {
        val r = TaskLogic.newRow(NewTask("  süt al  "), now, "a")
        assertEquals("süt al", r.title)
        assertEquals("blue", r.color)
        assertFalse(r.done || r.recurring)
        assertNull(r.recurrence)
    }

    @Test
    fun bosBaslikReddedilir() {
        try {
            TaskLogic.newRow(NewTask("   "), now)
            fail("boş başlık kabul edildi")
        } catch (_: IllegalArgumentException) {
        }
        try {
            patch(row("a"), TaskPatch(title = " "))
            fail("boş başlık kabul edildi")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun siralamaTekrarlayanAcikYapilan() {
        val sonra = patch(row("sonra"), TaskPatch(deadline = "20.12.2026 10:00"))
        val once = patch(row("önce"), TaskPatch(deadline = "01.12.2026 10:00"))
        val tarihsiz = row("tarihsiz")
        val bitti = patch(row("bitti"), TaskPatch(done = true))
        val gunluk = recurringRow("günlük", "daily")

        val tasks = list(listOf(sonra, once, tarihsiz, bitti, gunluk))
        assertEquals(listOf("günlük", "önce", "sonra", "tarihsiz", "bitti"), titles(tasks))
    }

    @Test
    fun okunamayanDeadlineMetniKorunurVeSondaSiralanir() {
        val a = patch(row("yarın yap"), TaskPatch(deadline = "yarın akşam"))
        val b = patch(row("tarihli"), TaskPatch(deadline = "05.05.2027"))

        val tasks = list(listOf(a, b))
        assertEquals(listOf("tarihli", "yarın yap"), titles(tasks))
        assertEquals("yarın akşam", tasks[1].deadline)
        assertNull(tasks[1].deadlineKey)
        assertTrue(tasks[0].deadlineKey != null)
    }

    @Test
    fun yapilanlarEnYeniUstte() {
        val a = patch(row("a"), TaskPatch(done = true), 100)
        val b = patch(row("b"), TaskPatch(done = true), 200)
        assertEquals(listOf("b", "a"), titles(list(listOf(a, b), at = 300)))
    }

    @Test
    fun yapildiGeriAlinabilir() {
        val r = patch(patch(row("a"), TaskPatch(done = true)), TaskPatch(done = false))
        val t = list(listOf(r))[0]
        assertFalse(t.done)
        assertNull(t.doneAt)
    }

    @Test
    fun gunlukTekrarlayanErtesiGunSifirlanir() {
        val r = patch(recurringRow("su iç", "daily"), TaskPatch(done = true), ms("07.10.2026 12:00"))
        assertTrue(list(listOf(r), ms("07.10.2026 23:59"))[0].done)
        assertFalse(list(listOf(r), ms("08.10.2026 00:00"))[0].done)
    }

    @Test
    fun haftalikTekrarlayanPazartesiSifirlanir() {
        // 07.10.2026 çarşamba
        val r = patch(recurringRow("çamaşır", "weekly"), TaskPatch(done = true), ms("07.10.2026 12:00"))
        assertTrue(list(listOf(r), ms("11.10.2026 23:00"))[0].done) // pazar
        assertFalse(list(listOf(r), ms("12.10.2026 00:00"))[0].done) // pazartesi
    }

    @Test
    fun tekrarlayanSifirlamaYerelSaatDilimineGore() {
        // UTC+3: UTC 07.10 22:00 = yerel 08.10 01:00
        val r = patch(recurringRow("a", "daily"), TaskPatch(done = true), ms("07.10.2026 22:00"))
        assertTrue(list(listOf(r), ms("08.10.2026 10:00"), utcOffsetMin = 180)[0].done)
        // UTC 08.10 21:00 = yerel 09.10 00:00
        assertFalse(list(listOf(r), ms("08.10.2026 21:00"), utcOffsetMin = 180)[0].done)
    }

    @Test
    fun tekrarlayanGorevdeDeadlineOlmaz() {
        var r = patch(row("a"), TaskPatch(deadline = "01.01.2027"))
        r = patch(r, TaskPatch(recurring = true))
        assertTrue(r.recurring)
        assertEquals("daily", r.recurrence)
        assertEquals("", r.deadline)

        r = patch(r, TaskPatch(deadline = "02.02.2027"))
        assertEquals("", r.deadline)
    }

    @Test
    fun tekrarlamayiKapatincaPeriyotSilinir() {
        val r = patch(recurringRow("a", "weekly"), TaskPatch(recurring = false))
        assertFalse(r.recurring)
        assertNull(r.recurrence)
    }

    @Test
    fun haftalikPeriyotKorunur() {
        val r = patch(recurringRow("a", "weekly"), TaskPatch(notes = "x"))
        assertEquals("weekly", r.recurrence)
    }

    @Test
    fun etiketlerTemizlenir() {
        val r = patch(row("a"), TaskPatch(tags = listOf("#iş", " iş ", "", "ev")))
        assertEquals(listOf("iş", "ev"), r.tags)
    }

    @Test
    fun guncellemeZamaniVeDoneAtYazilir() {
        val r = patch(row("a"), TaskPatch(done = true), 5_000)
        assertEquals(5_000L, r.updatedAt)
        assertEquals(5_000L, r.doneAt)
    }
}
