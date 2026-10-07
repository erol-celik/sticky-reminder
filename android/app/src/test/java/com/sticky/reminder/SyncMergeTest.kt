package com.sticky.reminder

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SyncMergeTest {
    private fun task(id: String, updatedAt: Long, deleted: Boolean = false) =
        SyncTask(id = id, title = id, deleted = deleted, updatedAt = updatedAt)

    private fun byId(tasks: List<SyncTask>) = tasks.sortedBy { it.id }

    @Test
    fun paylasilanBirlestirmeSenaryolari() {
        // Gradle birim testleri modül klasöründe (android/app) çalışır.
        val file = File("../../shared/sync-cases.json")
        assertTrue("paylaşılan senaryo dosyası bulunamadı: ${file.absolutePath}", file.exists())
        val cases = JSONObject(file.readText()).getJSONArray("cases")
        assertTrue(cases.length() >= 10)

        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            fun tasks(key: String, from: JSONObject = case): List<SyncTask> {
                val array = from.getJSONArray(key)
                return List(array.length()) { SyncJson.taskFromJson(array.getJSONObject(it)) }
            }

            val result = SyncMerge.merge(tasks("local"), tasks("remote"))
            val expect = case.getJSONObject("expect")

            assertEquals("$name: merged", byId(tasks("merged", expect)), byId(result.merged))
            assertEquals("$name: uploadNeeded", expect.getBoolean("uploadNeeded"), result.uploadNeeded)
            val expectedApply = expect.getJSONArray("applyLocal").let { a -> List(a.length()) { a.getString(it) } }
            assertEquals("$name: applyLocal", expectedApply.sorted(), result.applyLocal.map { it.id }.sorted())
        }
    }

    @Test
    fun birlestirmeIkiYondeAyniSonucaYakinsar() {
        val a = listOf(task("x", 200), task("y", 100))
        val b = listOf(task("x", 100), task("z", 300))
        assertEquals(byId(SyncMerge.merge(a, b).merged), byId(SyncMerge.merge(b, a).merged))
    }

    @Test
    fun birlestirmeIkinciKezDegisiklikGerektirmez() {
        val local = listOf(task("x", 200), task("y", 100))
        val remote = listOf(task("x", 100), task("z", 300))
        val first = SyncMerge.merge(local, remote)
        val second = SyncMerge.merge(first.merged, first.merged)
        assertFalse(second.uploadNeeded)
        assertTrue(second.applyLocal.isEmpty())
    }

    @Test
    fun jsonGidisDonus() {
        val t = SyncTask(
            id = "a", title = "su iç", notes = "iki bardak", color = "navy",
            tags = listOf("ev", "sağlık"), recurring = true, recurrence = "weekly",
            done = true, doneAt = 7, updatedAt = 5,
        )
        val json = SyncJson.encode(listOf(t))
        assertTrue(json.contains("\"updatedAt\":5") && json.contains("\"doneAt\":7"))
        assertEquals(listOf(t), SyncJson.decode(json))
    }

    @Test
    fun nullAlanlarGidisDonusteKorunur() {
        val t = SyncTask(id = "a", title = "t", updatedAt = 1)
        val decoded = SyncJson.decode(SyncJson.encode(listOf(t))).single()
        assertEquals(null, decoded.recurrence)
        assertEquals(null, decoded.doneAt)
        assertEquals(t, decoded)
    }

    @Test
    fun dahaYeniSurumReddedilir() {
        try {
            SyncJson.decode("""{"version":2,"tasks":[]}""")
            fail("daha yeni sürüm kabul edildi")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("daha yeni"))
        }
    }

    @Test
    fun eksikVeBilinmeyenAlanlarHosgorulur() {
        val tasks = SyncJson.decode(
            """{"version":1,"tasks":[{"id":"a","title":"t","updatedAt":1,"yeniAlan":42}],"baska":true}""",
        )
        assertEquals(DEFAULT_COLOR, tasks[0].color)
        assertTrue(!tasks[0].deleted && tasks[0].tags.isEmpty())
    }

    @Test
    fun bozukJsonHataVerir() {
        for (bad in listOf("değil", """{"version":1}""", """{"tasks":[]}""")) {
            try {
                SyncJson.decode(bad)
                fail("kabul edildi: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }
}
