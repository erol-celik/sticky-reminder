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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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
     * Çıkış: Google'a bu uygulamanın erişimini iptal ettirir. Zaten izin yoksa ya da iptal
     * edilemezse false döner; bu durumda erişim Google Hesabım'dan kaldırılabilir.
     */
    suspend fun revoke(context: Context): Boolean = try {
        val result = rawAuthorize(context)
        val token = result.accessToken
        if (result.hasResolution() || token == null) {
            true // izin ekranı gerekiyorsa uygulamanın zaten erişimi yoktur
        } else {
            // Play Services' revokeAccess hesap bilgisi ister ve bu yapılandırmada vermez; erişim
            // belirtecini Google'ın iptal uç noktasına göndermek aynı izni geri alır.
            val revoked = withContext(Dispatchers.IO) { revokeToken(token) }
            client(context).clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
            revoked
        }
    } catch (e: Exception) {
        false
    }

    private fun revokeToken(token: String): Boolean {
        val connection = URL("https://oauth2.googleapis.com/revoke").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write("token=${URLEncoder.encode(token, "UTF-8")}".toByteArray()) }
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }
}
