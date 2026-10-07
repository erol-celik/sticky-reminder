package com.sticky.reminder

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

private const val FILE_NAME = "sticky.json"
private const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
private const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"

/** Dosyayı oluşturmak için `multipart/related` gövdesi: üst veri + içerik. */
internal fun multipartBody(boundary: String, content: String): String {
    val metadata = """{"name":"$FILE_NAME","parents":["appDataFolder"]}"""
    return "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
        "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$content\r\n--$boundary--"
}

/** Erişim belirteci Drive tarafından reddedildi (süresi dolmuş olabilir). */
class UnauthorizedException(val token: String) : Exception("Drive yetkisi reddedildi (401)")

/**
 * Drive v3 REST çağrıları: yalnızca uygulamanın gizli klasöründeki (appDataFolder) tek dosya.
 * Ek kütüphane yok; `HttpURLConnection` yeterli. Windows'taki `drive.rs` ile aynı çağrılar.
 */
class DriveRemote(private val accessToken: String) : Remote {

    private class Response(val status: Int, val body: String)

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    private fun networkError(e: IOException) =
        SyncException("Ağ hatası: ${e.message ?: e.javaClass.simpleName}", retryable = true, cause = e)

    private fun call(method: String, url: String, body: String? = null, contentType: String? = null): Response {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw networkError(e)
        }
        try {
            // Bazı HttpURLConnection sürümleri PATCH'i tanımaz; o durumda yöntem geçersiz kılma başlığı.
            try {
                connection.requestMethod = method
            } catch (_: ProtocolException) {
                connection.requestMethod = "POST"
                connection.setRequestProperty("X-HTTP-Method-Override", method)
            }
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType ?: "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(status, text)
        } catch (e: IOException) {
            throw networkError(e)
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(r: Response): Exception = when (r.status) {
        401 -> UnauthorizedException(accessToken)
        403, 429 -> SyncException("Drive erişimi reddedildi (${r.status}): ${shorten(r.body)}", retryable = true)
        in 500..599 -> SyncException("Drive ${r.status}: ${shorten(r.body)}", retryable = true)
        else -> SyncException("Drive ${r.status}: ${shorten(r.body)}", retryable = false)
    }

    private fun shorten(body: String) = body.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(200)

    private fun json(body: String): JSONObject = try {
        JSONObject(body)
    } catch (e: JSONException) {
        throw SyncException("Drive yanıtı okunamadı: ${e.message}", retryable = false, cause = e)
    }

    private fun versionOf(body: String): String {
        val o = json(body)
        if (o.isNull("version")) throw SyncException("Drive yanıtında sürüm yok", retryable = false)
        return o.getString("version")
    }

    /**
     * Dosyayı bulur: (id, sürüm). Birden fazla varsa (iki cihaz ilk senkronu aynı anda yaptıysa)
     * en eski oluşturulan kullanılır; böylece iki cihaz aynı dosyada buluşur.
     */
    private fun find(): Pair<String, String>? {
        val query = "name = '$FILE_NAME' and trashed = false"
        val url = "$FILES_URL?spaces=appDataFolder&orderBy=createdTime&pageSize=10" +
            "&fields=${enc("files(id,version)")}&q=${enc(query)}"
        val r = call("GET", url)
        if (r.status != 200) throw apiError(r)
        val files = json(r.body).optJSONArray("files")
        if (files == null || files.length() == 0) return null
        val first = files.getJSONObject(0)
        return first.getString("id") to first.getString("version")
    }

    override fun fetch(): RemoteFile? {
        val (id, version) = find() ?: return null
        val r = call("GET", "$FILES_URL/$id?alt=media")
        if (r.status != 200) throw apiError(r)
        return RemoteFile(id, version, r.body)
    }

    override fun currentVersion(knownId: String?): String? {
        if (knownId == null) return find()?.second
        val r = call("GET", "$FILES_URL/$knownId?fields=version")
        return when (r.status) {
            200 -> versionOf(r.body)
            404 -> null
            else -> throw apiError(r)
        }
    }

    override fun upload(existingId: String?, content: String): String {
        val r = if (existingId != null) {
            call(
                "PATCH",
                "$UPLOAD_URL/$existingId?uploadType=media&fields=id,version",
                content,
                "application/json; charset=UTF-8",
            )
        } else {
            val boundary = "sticky-" + UUID.randomUUID().toString().replace("-", "")
            call(
                "POST",
                "$UPLOAD_URL?uploadType=multipart&fields=id,version",
                multipartBody(boundary, content),
                "multipart/related; boundary=$boundary",
            )
        }
        if (r.status != 200) throw apiError(r)
        return versionOf(r.body)
    }
}
