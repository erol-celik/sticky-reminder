package com.sticky.reminder

import java.util.UUID

const val DEFAULT_COLOR = "blue"
private const val DAY_MS = 86_400_000L

/** Veritabanındaki hâliyle görev satırı. `done`, dönem sıfırlaması uygulanmamış ham değerdir. */
data class TaskRow(
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
    val updatedAt: Long,
)

/** Arayüze giden görev. `done` etkin değerdir; `deadlineKey` yazılan metnin okunabilen hâlidir. */
data class Task(
    val id: String,
    val title: String,
    val notes: String,
    val color: String,
    val tags: List<String>,
    val deadline: String,
    val deadlineKey: Long?,
    val recurring: Boolean,
    val recurrence: String?,
    val done: Boolean,
    val doneAt: Long?,
    val updatedAt: Long,
)

data class NewTask(
    val title: String,
    val recurring: Boolean = false,
    val recurrence: String? = null,
)

data class TaskPatch(
    val title: String? = null,
    val notes: String? = null,
    val color: String? = null,
    val tags: List<String>? = null,
    val deadline: String? = null,
    val recurring: Boolean? = null,
    val recurrence: String? = null,
    val done: Boolean? = null,
)

/** Veritabanından bağımsız saf kurallar; Windows tarafındaki `store.rs` ile aynı davranır. */
object TaskLogic {

    /**
     * Tekrarlayan görevin "yapıldı" işareti, işaretlendiği dönem (gün/hafta) bittiyse
     * kendiliğinden geçersiz sayılır. Okuma anında hesaplanır.
     * [utcOffsetMin]: yerel saatin UTC'den farkı, dakika olarak (UTC+3 için 180).
     */
    fun effectiveDone(row: TaskRow, nowMs: Long, utcOffsetMin: Int): Boolean {
        if (!row.done) return false
        if (!row.recurring) return true
        val doneAt = row.doneAt ?: return false
        val localDay = { ms: Long -> Math.floorDiv(ms + utcOffsetMin * 60_000L, DAY_MS) }
        val a = localDay(doneAt)
        val n = localDay(nowMs)
        return when (row.recurrence) {
            // 1970-01-01 perşembe; +3 ile haftalar pazartesi başlar.
            "weekly" -> Math.floorDiv(a + 3, 7L) == Math.floorDiv(n + 3, 7L)
            else -> a == n
        }
    }

    fun toTask(row: TaskRow, nowMs: Long, utcOffsetMin: Int) = Task(
        id = row.id,
        title = row.title,
        notes = row.notes,
        color = row.color,
        tags = row.tags,
        deadline = row.deadline,
        deadlineKey = Deadline.parse(row.deadline),
        recurring = row.recurring,
        recurrence = row.recurrence,
        done = effectiveDone(row, nowMs, utcOffsetMin),
        doneAt = row.doneAt,
        updatedAt = row.updatedAt,
    )

    private fun rank(t: Task) = when {
        t.recurring -> 0
        !t.done -> 1
        else -> 2
    }

    private fun byDeadline(a: Task, b: Task): Int {
        val x = a.deadlineKey
        val y = b.deadlineKey
        return when {
            x != null && y != null -> x.compareTo(y)
            x != null -> -1
            y != null -> 1
            else -> 0
        }
    }

    /**
     * Sıra: tekrarlayanlar (ekleme sırasıyla), açık görevler (deadline'a göre, deadline'sızlar
     * ve okunamayanlar sonda), yapılanlar (en yeni üstte). Girdi ekleme sırasında gelmelidir.
     */
    fun sorted(tasks: List<Task>): List<Task> = tasks.sortedWith { a, b ->
        val byRank = rank(a).compareTo(rank(b))
        when {
            byRank != 0 -> byRank
            rank(a) == 1 -> byDeadline(a, b)
            rank(a) == 2 -> compareValues(b.doneAt, a.doneAt)
            else -> 0
        }
    }

    fun normalizeTags(tags: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (raw in tags) {
            val tag = raw.trim().trimStart('#').trim()
            if (tag.isNotEmpty() && tag !in out) out += tag
        }
        return out
    }

    private fun normalizeRecurrence(value: String?) = if (value == "weekly") "weekly" else "daily"

    private fun cleanTitle(title: String): String {
        val t = title.trim()
        require(t.isNotEmpty()) { "başlık boş olamaz" }
        return t
    }

    fun newRow(new: NewTask, nowMs: Long, id: String = UUID.randomUUID().toString()) = TaskRow(
        id = id,
        title = cleanTitle(new.title),
        recurring = new.recurring,
        recurrence = if (new.recurring) normalizeRecurrence(new.recurrence) else null,
        updatedAt = nowMs,
    )

    fun applyPatch(row: TaskRow, patch: TaskPatch, nowMs: Long): TaskRow {
        var r = row
        patch.title?.let { r = r.copy(title = cleanTitle(it)) }
        patch.notes?.let { r = r.copy(notes = it) }
        patch.color?.let { r = r.copy(color = it) }
        patch.tags?.let { r = r.copy(tags = normalizeTags(it)) }
        patch.deadline?.let { r = r.copy(deadline = it.trim()) }
        patch.recurring?.let { r = r.copy(recurring = it) }
        r = if (r.recurring) {
            // Tekrarlayan görevlerde deadline yoktur.
            r.copy(recurrence = normalizeRecurrence(patch.recurrence ?: r.recurrence), deadline = "")
        } else {
            r.copy(recurrence = null)
        }
        patch.done?.let { r = r.copy(done = it, doneAt = if (it) nowMs else null) }
        return r.copy(updatedAt = nowMs)
    }
}
