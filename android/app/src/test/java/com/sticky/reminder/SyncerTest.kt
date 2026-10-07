package com.sticky.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncerTest {
    /** Drive'ın küçük bir taklidi: tek dosya, artan sürüm numarası. */
    private class FakeRemote : Remote {
        var id: String? = null
        var version = 0
        var content: String? = null
        var uploads = 0
        var failUpload = false
        var failFetch = false

        /** fetch ile yükleme kontrolü arasında başka bir cihaz dosyayı bu kadar kez değiştirir. */
        var interfere = 0
        var interferingContent: String = SyncJson.encode(emptyList())

        fun write(newContent: String): String {
            id = id ?: "file-1"
            version += 1
            content = newContent
            return version.toString()
        }

        override fun fetch(): RemoteFile? {
            if (failFetch) throw SyncException("Ağ hatası: bağlantı yok")
            val c = content ?: return null
            return RemoteFile(id!!, version.toString(), c)
        }

        override fun currentVersion(knownId: String?): String? {
            if (interfere > 0) {
                interfere -= 1
                write(interferingContent)
            }
            return if (content == null) null else version.toString()
        }

        override fun upload(existingId: String?, content: String): String {
            if (failUpload) throw SyncException("Drive 500: hata")
            uploads += 1
            return write(content)
        }
    }

    /** [TaskStore]'un SQL'deki koşullu yazma kurallarının bellek içi karşılığı. */
    private class FakeStore : LocalSyncStore {
        val rows = linkedMapOf<String, SyncTask>()
        val dirty = mutableSetOf<String>()

        fun edit(task: SyncTask) {
            rows[task.id] = task
            dirty += task.id
        }

        fun pending() = dirty.size

        fun visibleTitles() = rows.values.filter { !it.deleted }.map { it.title }.sorted()

        override fun snapshot() = rows.values.toList()

        override fun applyRemote(incoming: List<SyncTask>, base: List<SyncTask>) {
            val baseById = base.associateBy { it.id }
            for (t in incoming) {
                val known = baseById[t.id]
                if (known == null) {
                    if (t.id !in rows) rows[t.id] = t.also { dirty -= t.id }
                } else if (rows[t.id]?.updatedAt == known.updatedAt) {
                    rows[t.id] = t
                    dirty -= t.id
                }
            }
        }

        override fun markSynced(merged: List<SyncTask>) {
            for (t in merged) if (rows[t.id]?.updatedAt == t.updatedAt) dirty -= t.id
        }
    }

    private fun task(id: String, title: String = id, at: Long, deleted: Boolean = false) =
        SyncTask(id = id, title = title, deleted = deleted, updatedAt = at)

    @Test
    fun ilkSenkronDosyayiOlustururVeBekleyenleriTemizler() {
        val (store, remote) = FakeStore() to FakeRemote()
        store.edit(task("a", at = 100))
        store.edit(task("b", at = 100))
        assertEquals(2, store.pending())

        assertEquals(Outcome(0, true), Syncer.run(store, remote))
        assertEquals(1, remote.uploads)
        assertEquals(0, store.pending())
        assertEquals(2, SyncJson.decode(remote.content!!).size)
    }

    @Test
    fun degisiklikYoksaYuklemeYapilmaz() {
        val (store, remote) = FakeStore() to FakeRemote()
        store.edit(task("a", at = 100))
        Syncer.run(store, remote)
        assertEquals(Outcome(0, false), Syncer.run(store, remote))
        assertEquals(1, remote.uploads)
    }

    @Test
    fun bosIkiTarafDosyaOlusturmaz() {
        val (store, remote) = FakeStore() to FakeRemote()
        assertEquals(Outcome(0, false), Syncer.run(store, remote))
        assertNull(remote.content)
    }

    @Test
    fun ikinciCihazGorevleriCekerYuklemeYapmaz() {
        val remote = FakeRemote()
        val (a, b) = FakeStore() to FakeStore()
        a.edit(task("x", "telefondan", 100))
        Syncer.run(a, remote)

        assertEquals(Outcome(1, false), Syncer.run(b, remote))
        assertEquals(listOf("telefondan"), b.visibleTitles())
        assertEquals(0, b.pending())
        assertEquals(1, remote.uploads)
    }

    @Test
    fun ikiCihazBirbirininEklemeleriniGorur() {
        val remote = FakeRemote()
        val (a, b) = FakeStore() to FakeStore()
        a.edit(task("a1", "a-gorevi", 100))
        Syncer.run(a, remote)
        b.edit(task("b1", "b-gorevi", 200))
        Syncer.run(b, remote)
        Syncer.run(a, remote)
        assertEquals(listOf("a-gorevi", "b-gorevi"), a.visibleTitles())
        assertEquals(listOf("a-gorevi", "b-gorevi"), b.visibleTitles())
    }

    @Test
    fun silmeDigerCihazaYayilirVeGeriGelmez() {
        val remote = FakeRemote()
        val (a, b) = FakeStore() to FakeStore()
        a.edit(task("x", "silinecek", 100))
        Syncer.run(a, remote)
        Syncer.run(b, remote)
        assertEquals(listOf("silinecek"), b.visibleTitles())

        a.edit(task("x", "silinecek", 300, deleted = true))
        Syncer.run(a, remote)
        Syncer.run(b, remote)
        assertTrue(b.visibleTitles().isEmpty())
        Syncer.run(b, remote)
        Syncer.run(a, remote)
        assertTrue(a.visibleTitles().isEmpty() && b.visibleTitles().isEmpty())
    }

    @Test
    fun cakisanDuzenlemedeEnYeniKazanir() {
        val remote = FakeRemote()
        val (a, b) = FakeStore() to FakeStore()
        a.edit(task("x", "ilk", 100))
        Syncer.run(a, remote)
        Syncer.run(b, remote)

        a.edit(task("x", "a-surumu", 300))
        b.edit(task("x", "b-surumu", 400)) // daha yeni
        Syncer.run(a, remote)
        Syncer.run(b, remote)
        Syncer.run(a, remote)
        assertEquals(listOf("b-surumu"), a.visibleTitles())
        assertEquals(listOf("b-surumu"), b.visibleTitles())
    }

    @Test
    fun dosyaAradaDegisirseYenidenBirlestirir() {
        val remote = FakeRemote()
        val store = FakeStore()
        store.edit(task("l", "yerel", 100))

        // Başka bir cihaz, bizim indirmemizle yüklememiz arasında kendi görevini yükler.
        remote.interfere = 1
        remote.interferingContent = SyncJson.encode(listOf(task("o", "diger-cihaz", 150)))

        val out = Syncer.run(store, remote)
        assertTrue(out.uploaded)
        assertEquals(listOf("diger-cihaz", "yerel"), store.visibleTitles())
        val remoteTitles = SyncJson.decode(remote.content!!).map { it.title }
        assertTrue("yerel" in remoteTitles && "diger-cihaz" in remoteTitles)
        assertEquals(0, store.pending())
    }

    @Test
    fun dosyaSurekliDegisirseHataVerirVeBekleyenlerKalir() {
        val remote = FakeRemote().apply { interfere = 10 }
        val store = FakeStore().apply { edit(task("a", at = 100)) }
        try {
            Syncer.run(store, remote)
            fail("hata beklendi")
        } catch (e: SyncException) {
            assertTrue(e.message!!, e.message!!.contains("sürekli değişiyor"))
            assertTrue(e.retryable)
        }
        assertEquals(0, remote.uploads)
        assertEquals(1, store.pending())
    }

    @Test
    fun yuklemeHatasindaDegisikliklerBekleyenKalir() {
        val remote = FakeRemote().apply { failUpload = true }
        val store = FakeStore().apply { edit(task("a", at = 100)) }
        try {
            Syncer.run(store, remote)
            fail("hata beklendi")
        } catch (_: SyncException) {
        }
        assertEquals(1, store.pending())

        remote.failUpload = false
        Syncer.run(store, remote)
        assertEquals(0, store.pending())
    }

    @Test
    fun agHatasindaYerelVeriBozulmaz() {
        val remote = FakeRemote().apply { failFetch = true }
        val store = FakeStore().apply { edit(task("a", at = 100)) }
        try {
            Syncer.run(store, remote)
            fail("hata beklendi")
        } catch (e: SyncException) {
            assertTrue(e.message!!.contains("Ağ hatası"))
        }
        assertEquals(listOf("a"), store.visibleTitles())
        assertEquals(1, store.pending())
    }

    @Test
    fun dahaYeniSurumluDosyayaDokunulmaz() {
        val remote = FakeRemote()
        val newer = """{"version":2,"tasks":[]}"""
        remote.write(newer)
        val store = FakeStore().apply { edit(task("a", at = 100)) }
        try {
            Syncer.run(store, remote)
            fail("hata beklendi")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("daha yeni"))
        }
        assertEquals(0, remote.uploads)
        assertEquals(newer, remote.content)
    }

    @Test
    fun bozukDosyaUzerineYazilmaz() {
        val remote = FakeRemote()
        remote.write("bozuk içerik")
        val store = FakeStore().apply { edit(task("a", at = 100)) }
        try {
            Syncer.run(store, remote)
            fail("hata beklendi")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(0, remote.uploads)
        assertEquals("bozuk içerik", remote.content)
        assertFalse(store.pending() == 0)
    }

    @Test
    fun senkronSirasindaDegisenSatiraDokunulmaz() {
        // Birleştirme anındaki sürüm (base) ile satırın şimdiki sürümü farklıysa uzak veri yazılmaz.
        val store = FakeStore()
        store.edit(task("x", "eski", 100))
        val base = store.snapshot()
        store.edit(task("x", "kullanıcı", 200)) // senkron sürerken düzenlendi
        store.applyRemote(listOf(task("x", "uzak", 300)), base)
        assertEquals(listOf("kullanıcı"), store.visibleTitles())
        assertEquals(1, store.pending())
    }
}
