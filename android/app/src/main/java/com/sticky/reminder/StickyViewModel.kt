package com.sticky.reminder

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(val tasks: List<Task> = emptyList(), val pending: Int = 0)

/** Alt çubukta gösterilen senkron durumu. */
data class SyncUi(
    val signedIn: Boolean = false,
    val signingIn: Boolean = false,
    val syncing: Boolean = false,
    /** Son başarılı senkronun zamanı (ms). */
    val lastOkAt: Long? = null,
    val error: String? = null,
)

class StickyViewModel(private val app: Application) : AndroidViewModel(app) {
    private val store = TaskStore.get(app)
    private val prefs = SyncPrefs(app)

    // Tek iş parçacığı: değişiklikler ve okumalar verildiği sırayla uygulanır.
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val signingIn = MutableStateFlow(false)

    private val prefChanges = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.register(listener)
        trySend(Unit)
        awaitClose { prefs.unregister(listener) }
    }

    private val workInfos = WorkManager.getInstance(app).getWorkInfosByTagFlow(SyncScheduler.TAG)

    val sync: StateFlow<SyncUi> = combine(
        prefChanges,
        workInfos.map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } },
        signingIn,
    ) { _, running, signing ->
        SyncUi(
            signedIn = prefs.signedIn,
            signingIn = signing,
            syncing = running,
            lastOkAt = prefs.lastOkAt.takeIf { it > 0 },
            error = prefs.lastError,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SyncUi(signedIn = prefs.signedIn))

    init {
        // Bir senkron bittiğinde (iş durumu değişince) liste ve bekleyen sayısı yenilenir.
        viewModelScope.launch { workInfos.collect { refresh() } }
    }

    fun refresh() = execute(syncAfter = false) { }
    fun add(title: String) = execute { store.add(NewTask(title)) }
    fun update(id: String, patch: TaskPatch) = execute { store.update(id, patch) }
    fun delete(id: String) = execute { store.delete(id) }

    /** Değişikliği uygular, listeyi yeniler, ardından (giriş yapılmışsa) senkron planlar. */
    private fun execute(syncAfter: Boolean = true, change: () -> Unit) {
        viewModelScope.launch(dispatcher) {
            try {
                change()
            } catch (e: Exception) {
                Log.e("Sticky", "işlem başarısız", e)
            }
            _state.value = UiState(store.list(), store.pendingCount())
            if (syncAfter) {
                runCatching { updateStickyWidgets(app) } // widget da aynı değişikliği göstersin
                SyncScheduler.afterChange(app)
            }
        }
    }

    /** Elle "Kaydet": gönderir ve getirir. */
    fun syncNow() = SyncScheduler.now(app)

    /** Google girişini başlatır. İzin ekranı gerekirse [launch] ile açılır. */
    fun signIn(context: Context, launch: (PendingIntent) -> Unit) {
        if (signingIn.value) return
        signingIn.value = true
        prefs.lastError = null
        viewModelScope.launch {
            try {
                when (val auth = GoogleAuth.authorize(context)) {
                    is GoogleAuth.Result.Token -> {
                        completeSignIn()
                        signingIn.value = false
                    }
                    // Sonuç onSignInResult'a gelir; signingIn o zamana kadar açık kalır.
                    is GoogleAuth.Result.NeedsConsent -> launch(auth.pendingIntent)
                }
            } catch (e: Exception) {
                prefs.lastError = "Giriş başlatılamadı: ${e.message ?: e.javaClass.simpleName}"
                signingIn.value = false
            }
        }
    }

    fun onSignInResult(context: Context, data: Intent?) {
        try {
            GoogleAuth.tokenFromIntent(context, data)
            completeSignIn()
        } catch (e: ApiException) {
            prefs.lastError =
                if (e.statusCode == CommonStatusCodes.CANCELED) "Giriş iptal edildi" else "Giriş başarısız (${e.statusCode})"
        } catch (e: Exception) {
            prefs.lastError = "Giriş iptal edildi"
        } finally {
            signingIn.value = false
        }
    }

    /**
     * Çıkış: bu cihazda senkronu durdurur. Yerel görevlere ve Google'daki izne dokunulmaz;
     * izin diğer cihazlarla ortaktır, iptal etmek onları da çıkarırdı.
     */
    fun signOut() {
        prefs.signedIn = false
        prefs.lastOkAt = 0
        prefs.lastError = null
        WorkManager.getInstance(app).cancelAllWorkByTag(SyncScheduler.TAG)
        viewModelScope.launch { GoogleAuth.forgetCachedToken(app) }
    }

    private fun completeSignIn() {
        prefs.signedIn = true
        prefs.lastError = null
        SyncScheduler.ensurePeriodic(app)
        SyncScheduler.now(app)
    }

    override fun onCleared() {
        executor.shutdown()
    }
}
