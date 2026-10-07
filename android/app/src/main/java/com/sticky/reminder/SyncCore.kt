package com.sticky.reminder

/**
 * Senkron hatası. [retryable]: ağ ya da sunucu geçici bir sorun yaşadı, sonra yeniden denenebilir.
 * Windows tarafındaki `syncer.rs` ile aynı akış ve kurallar.
 */
class SyncException(message: String, val retryable: Boolean = true, cause: Throwable? = null) :
    Exception(message, cause)

class RemoteFile(val id: String, val version: String, val content: String)

interface Remote {
    /** Dosyayı indirir; yoksa null. `version`, içeriğin en az o sürümden yeni olduğunu garanti eder. */
    fun fetch(): RemoteFile?

    /** Dosyanın şu anki sürümü; dosya yoksa null. Yükleme öncesi yarış kontrolü içindir. */
    fun currentVersion(knownId: String?): String?

    /** Dosyayı oluşturur ([existingId] yoksa) ya da günceller; yeni sürümü döndürür. */
    fun upload(existingId: String?, content: String): String
}

/** Senkronun dayandığı yerel depo ([TaskStore] bunu gerçekleştirir). */
interface LocalSyncStore {
    fun snapshot(): List<SyncTask>
    fun applyRemote(incoming: List<SyncTask>, base: List<SyncTask>)
    fun markSynced(merged: List<SyncTask>)
}

data class Outcome(
    /** Uzaktan gelip yerele yazılan görev sayısı. */
    val pulled: Int,
    val uploaded: Boolean,
)

object Syncer {
    /** Yükleme öncesi dosya başkası tarafından değişirse birleştirme bu kadar tekrarlanır. */
    private const val MAX_ATTEMPTS = 3

    /** Drive'daki dosyayı indirir, yerelle birleştirir, gerekirse geri yükler. */
    fun run(local: LocalSyncStore, remote: Remote): Outcome {
        var pulledTotal = 0
        repeat(MAX_ATTEMPTS) {
            val file = remote.fetch()
            val remoteTasks = if (file == null) emptyList() else SyncJson.decode(file.content)
            val snapshot = local.snapshot()
            val merge = SyncMerge.merge(snapshot, remoteTasks)

            local.applyRemote(merge.applyLocal, snapshot)
            pulledTotal += merge.applyLocal.size

            if (merge.uploadNeeded) {
                // Dosya, indirdiğimizden beri değiştiyse üzerine yazmayız; yeniden birleştiririz.
                if (remote.currentVersion(file?.id) != file?.version) return@repeat
                remote.upload(file?.id, SyncJson.encode(merge.merged))
            }

            local.markSynced(merge.merged)
            return Outcome(pulledTotal, merge.uploadNeeded)
        }
        throw SyncException("Drive dosyası sürekli değişiyor; birazdan yeniden denenecek")
    }
}
