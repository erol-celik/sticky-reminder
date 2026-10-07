package com.sticky.reminder

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Drive yetkisi: Play Services `AuthorizationClient`. İlk seferde izin ekranı çıkar; sonrasında
 * erişim belirteçleri sessizce yenilenir, yani yeniden giriş istenmez. Yenileme belirteci
 * cihazda saklanmaz.
 */
object GoogleAuth {
    /** Yalnızca uygulamanın kendi gizli Drive klasörü. */
    private val SCOPE = Scope("https://www.googleapis.com/auth/drive.appdata")

    sealed interface Result {
        data class Token(val accessToken: String) : Result
        class NeedsConsent(val pendingIntent: PendingIntent) : Result
    }

    private fun request() = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    private fun client(context: Context) = Identity.getAuthorizationClient(context.applicationContext)

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
    }

    private suspend fun rawAuthorize(context: Context): AuthorizationResult =
        client(context).authorize(request()).await()

    suspend fun authorize(context: Context): Result {
        val result = rawAuthorize(context)
        val pending = result.pendingIntent
        val token = result.accessToken
        return when {
            result.hasResolution() && pending != null -> Result.NeedsConsent(pending)
            token != null -> Result.Token(token)
            else -> throw IllegalStateException("Google erişim belirteci vermedi")
        }
    }

    /** İzin ekranından dönen sonuçtan erişim belirtecini okur; reddedilmişse istisna fırlatır. */
    fun tokenFromIntent(context: Context, data: Intent?): String =
        client(context).getAuthorizationResultFromIntent(data).accessToken
            ?: throw IllegalStateException("Google erişim belirteci vermedi")

    /**
     * Çıkışta bu cihazdaki önbellekli erişim belirtecini siler. Google tarafındaki izin
     * **iptal edilmez**: izin tüm cihazlar için ortaktır (aynı Cloud projesi), iptal etmek
     * Windows'taki oturumu da düşürürdü. Tamamen kaldırmak için Google Hesabım > Güvenlik.
     */
    suspend fun forgetCachedToken(context: Context) {
        try {
            val token = rawAuthorize(context).takeUnless { it.hasResolution() }?.accessToken ?: return
            client(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
        } catch (_: Exception) {
            // Önbellek temizliği en iyi çabadır; çıkış yerelde zaten tamamlandı.
        }
    }
}
