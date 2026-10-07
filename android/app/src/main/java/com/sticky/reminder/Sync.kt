package com.sticky.reminder

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Senkronda taşınan görev: silinenler (tombstone) dahil tüm alanlar. Windows tarafındaki
 * `sync.rs` ile aynı JSON biçimini üretir.
 */
data class SyncTask(
    val id: String,
    val title: String,
    val notes: String = "",
    val color: String = DEFAULT_COLOR,
    val tags: List<String> = emptyList(),
    val deadline: String = "",
    val recurring: Boolean = false,
    val recurrence: String? = null,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val deleted: Boolean = false,
    val updatedAt: Long,
)

data class MergeResult(
    /** Birleşik liste: yerelin sırası, ardından yalnızca uzakta olanlar. */
    val merged: List<SyncTask>,
    /** Birleşik sonuç uzaktaki dosyadan farklıysa yüklenmeli. */
    val uploadNeeded: Boolean,
    /** Yerel veritabanına yazılması gerekenler (yerelden farklı olanlar). */
    val applyLocal: List<SyncTask>,
)

object SyncMerge {
    /**
     * Görev bazında birleştirme: en yeni `updatedAt` kazanır. Eşitlikte silinmiş olan kazanır,
     * o da değilse uzaktaki sürüm kazanır (uzaktaki dosya ortak durumdur, böylece iki cihaz
     * aynı sonuca yakınsar). Kurallar `sync.rs` ile birebir aynıdır.
     */
    fun merge(local: List<SyncTask>, remote: List<SyncTask>): MergeResult {
        val localById = local.associateBy { it.id }
        val remoteById = remote.associateBy { it.id }

        val merged = buildList {
            for (l in local) {
                val r = remoteById[l.id]
                add(if (r == null) l else pick(l, r))
            }
            for (r in remote) if (r.id !in localById) add(r)
        }

        return MergeResult(
            merged = merged,
            uploadNeeded = merged.any { remoteById[it.id] != it },
            applyLocal = merged.filter { localById[it.id] != it },
        )
    }

    private fun pick(local: SyncTask, remote: SyncTask): SyncTask = when {
        local.updatedAt > remote.updatedAt -> local
        local.updatedAt < remote.updatedAt -> remote
        local.deleted && !remote.deleted -> local
        else -> remote
    }
}

/** Drive'daki `sticky.json` dosyasının biçimi. */
object SyncJson {
    /** Daha yeni bir sürüm okunursa dosya bozulmasın diye senkron durur. */
    const val FORMAT_VERSION = 1

    fun encode(tasks: List<SyncTask>): String = JSONObject()
        .put("version", FORMAT_VERSION)
        .put("tasks", JSONArray().also { array -> tasks.forEach { array.put(taskToJson(it)) } })
        .toString()

    /** Bozuk ya da daha yeni sürümden dosya için [IllegalArgumentException] fırlatır. */
    fun decode(json: String): List<SyncTask> {
        try {
            val root = JSONObject(json)
            val version = root.getInt("version")
            require(version <= FORMAT_VERSION) {
                "sticky.json daha yeni bir sürümden (v$version); bu uygulamayı güncelle"
            }
            val array = root.getJSONArray("tasks")
            return List(array.length()) { taskFromJson(array.getJSONObject(it)) }
        } catch (e: JSONException) {
            throw IllegalArgumentException("sticky.json okunamadı: ${e.message}", e)
        }
    }

    fun taskToJson(t: SyncTask): JSONObject = JSONObject()
        .put("id", t.id)
        .put("title", t.title)
        .put("notes", t.notes)
        .put("color", t.color)
        .put("tags", JSONArray(t.tags))
        .put("deadline", t.deadline)
        .put("recurring", t.recurring)
        .put("recurrence", t.recurrence ?: JSONObject.NULL)
        .put("done", t.done)
        .put("doneAt", t.doneAt ?: JSONObject.NULL)
        .put("deleted", t.deleted)
        .put("updatedAt", t.updatedAt)

    /** Eksik alanlar varsayılana düşer, bilinmeyen alanlar yok sayılır. */
    fun taskFromJson(o: JSONObject): SyncTask = SyncTask(
        id = o.getString("id"),
        title = o.getString("title"),
        notes = if (o.isNull("notes")) "" else o.getString("notes"),
        color = if (o.isNull("color")) DEFAULT_COLOR else o.getString("color"),
        tags = o.optJSONArray("tags")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        deadline = if (o.isNull("deadline")) "" else o.getString("deadline"),
        recurring = o.optBoolean("recurring", false),
        recurrence = if (o.isNull("recurrence")) null else o.getString("recurrence"),
        done = o.optBoolean("done", false),
        doneAt = if (o.isNull("doneAt")) null else o.getLong("doneAt"),
        deleted = o.optBoolean("deleted", false),
        updatedAt = o.getLong("updatedAt"),
    )
}
