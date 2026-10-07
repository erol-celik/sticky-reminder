package com.sticky.reminder

import android.content.Context
import android.content.SharedPreferences
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.GoogleAuthUtil
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Senkronun görünen durumu; arayüz bunu okur, çalışan yazar. */
class SyncPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /** Drive izni verildi mi (son yetkilendirme denemesi izin ekranı gerektirmedi). */
    var signedIn: Boolean
        get() = prefs.getBoolean("signedIn", false)
        set(value) = prefs.edit().putBoolean("signedIn", value).apply()

    /** Son başarılı senkronun zamanı (ms); hiç olmadıysa 0. */
    var lastOkAt: Long
        get() = prefs.getLong("lastOkAt", 0L)
        set(value) = prefs.edit().putLong("lastOkAt", value).apply()

    var lastError: String?
        get() = prefs.getString("lastError", null)
        set(value) = prefs.edit().putString("lastError", value).apply()

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
}

object SyncRunner {
    sealed interface Result {
        data class Ok(val outcome: Outcome) : Result
        data object SignedOut : Result
        data class Failed(val message: String, val retryable: Boolean) : Result
    }

    // İki tetikleyici üst üste binerse (ör. değişiklik + elle Kaydet) senkronlar sırayla çalışır.
    private val mutex = Mutex()

    suspend fun run(context: Context): Result = mutex.withLock {
        withContext(Dispatchers.IO) { runLocked(context.applicationContext) }
    }

    private suspend fun runLocked(context: Context): Result {
        val prefs = SyncPrefs(context)
        var token: String? = null
        return try {
            token = when (val auth = GoogleAuth.authorize(context)) {
                is GoogleAuth.Result.NeedsConsent -> {
                    // Arka planda izin ekranı açılamaz: kullanıcıdan uygulamadan giriş istenir.
                    prefs.signedIn = false
                    prefs.lastError = "Oturum sona erdi, yeniden giriş yap"
                    return Result.SignedOut
                }
                is GoogleAuth.Result.Token -> auth.accessToken
            }
            prefs.signedIn = true
            // Bu uygulamanın ağ kullanımı (TLS dahil): her senkronun harcadığı veriyi günlüğe yazar.
            val uid = Process.myUid()
            val rx0 = TrafficStats.getUidRxBytes(uid)
            val tx0 = TrafficStats.getUidTxBytes(uid)
            val outcome = Syncer.run(TaskStore.get(context), DriveRemote(token))
            Log.i(
                "StickyData",
                "senkron: indirilen=${TrafficStats.getUidRxBytes(uid) - rx0} B, " +
                    "gönderilen=${TrafficStats.getUidTxBytes(uid) - tx0} B, " +
                    "çekilen=${outcome.pulled}, yüklendi=${outcome.uploaded}",
            )
            prefs.lastOkAt = System.currentTimeMillis()
            prefs.lastError = null
            Result.Ok(outcome)
        } catch (e: UnauthorizedException) {
            // Önbellekteki erişim belirtecinin süresi dolmuş: sil, bir sonraki denemede yenisi alınır.
            token?.let { runCatching { GoogleAuthUtil.clearToken(context, it) } }
            fail(prefs, "Drive yetkisi yenileniyor, tekrar denenecek", retryable = true)
        } catch (e: SyncException) {
            fail(prefs, e.message ?: "Senkron başarısız", e.retryable)
        } catch (e: IllegalArgumentException) {
            // Drive'daki dosya bozuk ya da daha yeni sürümden: üzerine yazılmaz.
            fail(prefs, e.message ?: "Drive dosyası okunamadı", retryable = false)
        } catch (e: Exception) {
            fail(prefs, "Beklenmeyen hata: ${e.message ?: e.javaClass.simpleName}", retryable = true)
        }
    }

    private fun fail(prefs: SyncPrefs, message: String, retryable: Boolean): Result {
        prefs.lastError = message
        return Result.Failed(message, retryable)
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = SyncRunner.run(applicationContext)
        // Senkron uzaktan görev getirmiş olabilir; ana ekran widget'ı da yenilensin.
        runCatching { updateStickyWidgets(applicationContext) }
        return when (result) {
            is SyncRunner.Result.Ok, SyncRunner.Result.SignedOut -> Result.success()
            is SyncRunner.Result.Failed -> if (result.retryable) Result.retry() else Result.failure()
        }
    }
}

/**
 * Senkron tetikleyicileri. Hepsi "internet var" kısıtıyla kuyruğa girer: internet yokken bekler,
 * gelince çalışır (uygulama kapalı olsa bile).
 */
object SyncScheduler {
    const val TAG = "sync"
    private const val DEBOUNCE_SECONDS = 5L

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun request(delaySeconds: Long = 0) = OneTimeWorkRequest.Builder(SyncWorker::class.java)
        .setConstraints(network)
        .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
        .addTag(TAG)
        .build()

    private fun manager(context: Context) = WorkManager.getInstance(context.applicationContext)

    /** Yerelde değişiklik yapıldı: birkaç saniye sonra senkronla (art arda değişiklikler tek senkron olur). */
    fun afterChange(context: Context) {
        if (!SyncPrefs(context).signedIn) return
        manager(context).enqueueUniqueWork("sync-change", ExistingWorkPolicy.REPLACE, request(DEBOUNCE_SECONDS))
    }

    /** Elle "Kaydet". */
    fun now(context: Context) {
        manager(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, request())
    }

    /** Uygulama açılırken. */
    fun onStart(context: Context) {
        if (!SyncPrefs(context).signedIn) return
        manager(context).enqueueUniqueWork("sync-start", ExistingWorkPolicy.KEEP, request())
        ensurePeriodic(context)
    }

    /** Uygulama kapalıyken de diğer cihazdaki değişiklikler gelsin diye (en sık 15 dakikada bir). */
    fun ensurePeriodic(context: Context) {
        val periodic = PeriodicWorkRequest.Builder(SyncWorker::class.java, 15, TimeUnit.MINUTES)
            .setConstraints(network)
            .addTag(TAG)
            .build()
        manager(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
    }
}
