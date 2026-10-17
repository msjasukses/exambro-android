package com.exbro.browser.security

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.exbro.browser.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Verifikasi PIN keluar ke server CBT memakai API key.
 *
 * Protokol (pasangannya: server/exbropinapi/verify-pin.php):
 *   Request  POST {"nonce": hex32, "device": id, "pin_hmac": HMAC(key, nonce|pin)}
 *   Response 200  {"result": "valid"|"invalid", "sig": HMAC(key, nonce|result)}
 *
 * PIN tidak pernah dikirim apa adanya, dan jawaban server ditandatangani memakai
 * nonce baru di setiap percobaan. Jawaban "valid" karenanya tidak bisa dipalsukan
 * atau diputar ulang oleh pihak lain di jaringan sekolah, walau koneksinya HTTP
 * biasa atau sertifikat server lokal tidak tepercaya.
 *
 * Hanya jawaban bertanda tangan sah yang dianggap final. Selain itu (server mati,
 * timeout, belum dikonfigurasi, dibatasi, tanda tangan salah) hasilnya
 * [Result.Unavailable] dan pemanggil memakai PIN lokal sebagai cadangan.
 */
object ExitPinVerifier {

    sealed class Result {
        object Valid : Result()
        object Invalid : Result()
        data class Unavailable(val reason: String) : Result()
    }

    private const val TIMEOUT_MS = 6_000

    private val random = SecureRandom()

    val isConfigured: Boolean
        get() = BuildConfig.EXIT_PIN_API_KEY.isNotBlank() &&
            BuildConfig.EXIT_PIN_API_URL.isNotBlank()

    /** api.url boleh URL absolut, atau path yang ditempelkan ke origin link ujian. */
    fun resolveEndpoint(examUrl: String): String? {
        val configured = BuildConfig.EXIT_PIN_API_URL.trim()
        if (configured.startsWith("http://", true) || configured.startsWith("https://", true)) {
            return configured
        }
        val exam = runCatching { Uri.parse(examUrl) }.getOrNull() ?: return null
        val scheme = exam.scheme ?: return null
        val authority = exam.encodedAuthority ?: return null
        return "$scheme://$authority/" + configured.trimStart('/')
    }

    /** Berjalan di thread latar; [callback] dipanggil di main thread. */
    fun verify(examUrl: String, pin: String, deviceId: String, callback: (Result) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                verifyBlocking(examUrl, pin, deviceId)
            } catch (e: Exception) {
                Result.Unavailable(e.javaClass.simpleName)
            }
            main.post { callback(result) }
        }.start()
    }

    private fun verifyBlocking(examUrl: String, pin: String, deviceId: String): Result {
        if (!isConfigured) return Result.Unavailable("api_not_configured")
        val endpoint = resolveEndpoint(examUrl) ?: return Result.Unavailable("bad_endpoint")

        val nonce = randomHex(16)
        val body = JSONObject()
            .put("nonce", nonce)
            .put("device", deviceId)
            .put("pin_hmac", hmacHex("$nonce|$pin"))
            .toString()
            .toByteArray(Charsets.UTF_8)

        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            if (conn is HttpsURLConnection && UrlWhitelist.isPrivateHost(conn.url.host.orEmpty())) {
                trustLocalServer(conn)
            }
            conn.requestMethod = "POST"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.doOutput = true
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) return Result.Unavailable("http_$code")

            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val result = json.optString("result")
            val sig = json.optString("sig")
            if (!constantTimeEquals(sig, hmacHex("$nonce|$result"))) {
                return Result.Unavailable("bad_signature")
            }
            return when (result) {
                "valid" -> Result.Valid
                "invalid" -> Result.Invalid
                else -> Result.Unavailable("bad_result")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun hmacHex(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(BuildConfig.EXIT_PIN_API_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { random.nextBytes(it) }.toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /**
     * Server CBT lokal umumnya memakai sertifikat terbitan sendiri. Validasi TLS
     * dilonggarkan HANYA untuk host lokal, dan itu aman di sini karena keaslian
     * jawaban dijamin tanda tangan HMAC, bukan oleh sertifikat.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager", "BadHostnameVerifier")
    private fun trustLocalServer(conn: HttpsURLConnection) {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val context = SSLContext.getInstance("TLS").apply { init(null, trustAll, random) }
        conn.sslSocketFactory = context.socketFactory
        conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
    }
}
