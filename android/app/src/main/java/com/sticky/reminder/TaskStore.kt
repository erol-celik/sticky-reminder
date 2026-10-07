package com.sticky.reminder

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.util.TimeZone

private const val DB_NAME = "sticky.db"
private const val DB_VERSION = 1
private const val SYNC_COLS =
    "id, title, notes, color, tags, deadline, recurring, recurrence, done, done_at, deleted, updated_at"
private const val COLS =
    "id, title, notes, color, tags, deadline, recurring, recurrence, done, done_at, updated_at"

/**
 * Yerel SQLite veritabanı; şema Windows tarafıyla (store.rs) birebir aynıdır.
 * Silme, görevi kaldırmaz; "silindi" işaretiyle (tombstone) saklar. `dirty`, senkronlanmamış
 * (yerelde değişmiş) görevleri işaretler.
 */
class TaskStore private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION), LocalSyncStore {

    companion object {
        @Volatile
        private var instance: TaskStore? = null

        /** Arayüz, senkron çalışanı ve (ileride) widget aynı bağlantıyı paylaşır. */
        fun get(context: Context): TaskStore =
            instance ?: synchronized(this) {
                instance ?: TaskStore(context).also { instance = it }
            }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // Senkron arka planda yazarken arayüz okuyabilsin.
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS tasks (
                id         TEXT PRIMARY KEY,
                title      TEXT NOT NULL,
                notes      TEXT NOT NULL DEFAULT '',
                color      TEXT NOT NULL DEFAULT 'blue',
                tags       TEXT NOT NULL DEFAULT '[]',
                deadline   TEXT NOT NULL DEFAULT '',
                recurring  INTEGER NOT NULL DEFAULT 0,
                recurrence TEXT,
                done       INTEGER NOT NULL DEFAULT 0,
                done_at    INTEGER,
                deleted    INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                dirty      INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun list(nowMs: Long = System.currentTimeMillis()): List<Task> {
        val offset = utcOffsetMin(nowMs)
        val rows = readableDatabase.rawQuery(
            "SELECT $COLS FROM tasks WHERE deleted = 0 ORDER BY rowid",
            null,
        ).use { c -> generateSequence { if (c.moveToNext()) rowOf(c) else null }.toList() }
        return TaskLogic.sorted(rows.map { TaskLogic.toTask(it, nowMs, offset) })
    }

    fun add(new: NewTask, nowMs: Long = System.currentTimeMillis()): String {
        val row = TaskLogic.newRow(new, nowMs)
        writableDatabase.insertOrThrow("tasks", null, values(row).apply { put("dirty", 1) })
        return row.id
    }

    fun update(id: String, patch: TaskPatch, nowMs: Long = System.currentTimeMillis()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = db.rawQuery(
                "SELECT $COLS FROM tasks WHERE id = ? AND deleted = 0",
                arrayOf(id),
            ).use { c -> if (c.moveToFirst()) rowOf(c) else null }
                ?: throw NoSuchElementException("görev bulunamadı")
            val updated = TaskLogic.applyPatch(current, patch, nowMs)
            db.update("tasks", values(updated).apply { put("dirty", 1) }, "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun delete(id: String, nowMs: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("deleted", 1)
            put("updated_at", nowMs)
            put("dirty", 1)
        }
        val changed = writableDatabase.update("tasks", values, "id = ? AND deleted = 0", arrayOf(id))
        if (changed == 0) throw NoSuchElementException("görev bulunamadı")
    }

    /** Henüz senkronlanmamış (yerelde değişmiş) görev sayısı. */
    fun pendingCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM tasks WHERE dirty = 1", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Senkron için silinenler (tombstone) dahil tüm satırlar. */
    override fun snapshot(): List<SyncTask> = readableDatabase.rawQuery(
        "SELECT $SYNC_COLS FROM tasks ORDER BY rowid",
        null,
    ).use { c -> generateSequence { if (c.moveToNext()) syncTaskOf(c) else null }.toList() }

    /**
     * Uzaktan gelen görevleri yerele yazar. [base], birleştirmenin dayandığı [snapshot]'tır:
     * kullanıcı o andan sonra bir görevi değiştirdiyse (updated_at farklıysa) o satıra
     * dokunulmaz, değişiklik kaybolmaz ve bir sonraki senkronda gider.
     */
    override fun applyRemote(incoming: List<SyncTask>, base: List<SyncTask>) {
        val baseById = base.associateBy { it.id }
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in incoming) {
                val values = syncValues(t).apply { put("dirty", 0) }
                val known = baseById[t.id]
                if (known == null) {
                    db.insertWithOnConflict("tasks", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                } else {
                    db.update("tasks", values, "id = ? AND updated_at = ?", arrayOf(t.id, known.updatedAt.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Başarılı senkrondan sonra çağrılır: birleşik sürümle aynı updated_at'e sahip satırların
     * "bekleyen" işaretini kaldırır. Senkron sürerken değiştirilen satırlar bekleyen kalır.
     */
    override fun markSynced(merged: List<SyncTask>) {
        val db = writableDatabase
        val clean = ContentValues().apply { put("dirty", 0) }
        db.beginTransaction()
        try {
            for (t in merged) {
                db.update("tasks", clean, "id = ? AND updated_at = ?", arrayOf(t.id, t.updatedAt.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun utcOffsetMin(nowMs: Long) = TimeZone.getDefault().getOffset(nowMs) / 60_000

    private fun syncTaskOf(c: Cursor) = SyncTask(
        id = c.getString(0),
        title = c.getString(1),
        notes = c.getString(2),
        color = c.getString(3),
        tags = decodeTags(c.getString(4)),
        deadline = c.getString(5),
        recurring = c.getInt(6) != 0,
        recurrence = if (c.isNull(7)) null else c.getString(7),
        done = c.getInt(8) != 0,
        doneAt = if (c.isNull(9)) null else c.getLong(9),
        deleted = c.getInt(10) != 0,
        updatedAt = c.getLong(11),
    )

    private fun syncValues(t: SyncTask) = ContentValues().apply {
        put("id", t.id)
        put("title", t.title)
        put("notes", t.notes)
        put("color", t.color)
        put("tags", encodeTags(t.tags))
        put("deadline", t.deadline)
        put("recurring", if (t.recurring) 1 else 0)
        put("recurrence", t.recurrence)
        put("done", if (t.done) 1 else 0)
        put("done_at", t.doneAt)
        put("deleted", if (t.deleted) 1 else 0)
        put("updated_at", t.updatedAt)
    }

    private fun rowOf(c: Cursor) = TaskRow(
        id = c.getString(0),
        title = c.getString(1),
        notes = c.getString(2),
        color = c.getString(3),
        tags = decodeTags(c.getString(4)),
        deadline = c.getString(5),
        recurring = c.getInt(6) != 0,
        recurrence = if (c.isNull(7)) null else c.getString(7),
        done = c.getInt(8) != 0,
        doneAt = if (c.isNull(9)) null else c.getLong(9),
        updatedAt = c.getLong(10),
    )

    private fun values(r: TaskRow) = ContentValues().apply {
        put("id", r.id)
        put("title", r.title)
        put("notes", r.notes)
        put("color", r.color)
        put("tags", encodeTags(r.tags))
        put("deadline", r.deadline)
        put("recurring", if (r.recurring) 1 else 0)
        put("recurrence", r.recurrence)
        put("done", if (r.done) 1 else 0)
        put("done_at", r.doneAt)
        put("updated_at", r.updatedAt)
    }

    private fun encodeTags(tags: List<String>): String = JSONArray(tags).toString()

    private fun decodeTags(json: String): List<String> = try {
        val array = JSONArray(json)
        List(array.length()) { array.getString(it) }
    } catch (e: org.json.JSONException) {
        emptyList()
    }
}
