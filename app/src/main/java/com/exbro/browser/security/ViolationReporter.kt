package com.exbro.browser.security

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import com.exbro.browser.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
 * Mencatat pelanggaran siswa dan mengirimkannya ke server CBT.
 *
 * Kejadian diantre dulu di HP (bertahan walau aplikasi ditutup), lalu dikirim
 * berkelompok ke log-violation.php. Jadi catatan tidak hilang ketika jaringan
 * sedang putus atau server sedang mati.
 *
 * Isi kiriman ditandatangani HMAC memakai API key yang sama dengan verifikasi PIN,
 * sehingga catatan palsu dari luar aplikasi ditolak server.
 */
object ViolationReporter {

    private const val PREFS_NAME = "exbro_violations"
    private const val KEY_QUEUE = "queue"
    private const val MAX_QUEUE = 300
    private const val MAX_BATCH = 50
    private const val TIMEOUT_MS = 6_000
    private const val RETRY_DELAY_MS = 30_000L

    private val random = SecureRandom()
    private val lock = Any()

    @Volatile
    private var sending = false

    @Volatile
    private var lastAttemptAt = 0L

    val isConfigured: Boolean
        get() = BuildConfig.EXIT_PIN_API_KEY.isNotBlank() &&
            BuildConfig.EXIT_PIN_API_URL.isNotBlank()

    /** Menyimpan satu kejadian ke antrean. Tidak menyentuh jaringan. */
    fun record(context: Context, type: String, detail: String, page: String) {
        if (!isConfigured) return
        synchronized(lock) {
            val queue = readQueue(context)
            queue.put(
                JSONObject()
                    .put("at", System.currentTimeMillis() / 1000)
                    .put("type", type.take(48))
                    .put("detail", detail.take(255))
                    .put("page", page.take(255))
            )
            writeQueue(context, trim(queue))
        }
    }

    /**
     * Mengirim antrean ke server di thread latar. Aman dipanggil sering: pengiriman
     * berikutnya hanya dilakukan setelah [RETRY_DELAY_MS] bila yang sebelumnya gagal.
     */
    fun flush(context: Context, examUrl: String, deviceId: String, force: Boolean = false) {
        if (!isConfigured || sending) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAttemptAt < RETRY_DELAY_MS) return

        val batch = synchronized(lock) {
            val queue = readQueue(context)
            if (queue.length() == 0) return
            JSONArray().apply {
                for (i in 0 until minOf(queue.length(), MAX_BATCH)) put(queue.get(i))
            }
        }

        sending = true
        lastAttemptAt = now
        val appContext = context.applicationContext
        Thread {
            val sent = runCatching { send(examUrl, deviceId, batch) }.getOrDefault(false)
            if (sent) {
                synchronized(lock) {
                    val queue = readQueue(appContext)
                    val sisa = JSONArray()
                    for (i in batch.length() until queue.length()) sisa.put(queue.get(i))
                    writeQueue(appContext, sisa)
                }
            }
            sending = false
        }.start()
    }

    fun queueSize(context: Context): Int = synchronized(lock) { readQueue(context).length() }

    // ------------------------------------------------------------------

    private fun send(examUrl: String, deviceId: String, events: JSONArray): Boolean {
        val endpoint = resolveEndpoint(examUrl) ?: return false
        val nonce = randomHex(16)
        val eventsJson = events.toString()

        val body = JSONObject()
            .put("nonce", nonce)
            .put("device", deviceId)
            .put("app", BuildConfig.VERSION_NAME)
            .put("android", Build.VERSION.RELEASE ?: "")
            .put("events_json", eventsJson)
            .put("sig", hmacHex("$nonce|$eventsJson"))
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
            conn.outputStream.use { it.write(body) }
            return conn.responseCode == HttpURLConnection.HTTP_OK
        } finally {
            conn.disconnect()
        }
    }

    /** Endpoint catatan berada di folder yang sama dengan endpoint PIN. */
    private fun resolveEndpoint(examUrl: String): String? {
        val configured = BuildConfig.EXIT_PIN_API_URL.trim()
        val folder = configured.substringBeforeLast('/', "")
        val path = "$folder/log-violation.php"
        if (configured.startsWith("http://", true) || configured.startsWith("https://", true)) {
            return path
        }
        val exam = runCatching { Uri.parse(examUrl) }.getOrNull() ?: return null
        val scheme = exam.scheme ?: return null
        val authority = exam.encodedAuthority ?: return null
        return "$scheme://$authority/" + path.trimStart('/')
    }

    private fun readQueue(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_QUEUE, "[]") ?: "[]"
        return runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
    }

    private fun writeQueue(context: Context, queue: JSONArray) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_QUEUE, queue.toString()).apply()
    }

    /** Membuang kejadian terlama bila antrean sudah terlalu panjang. */
    private fun trim(queue: JSONArray): JSONArray {
        if (queue.length() <= MAX_QUEUE) return queue
        val trimmed = JSONArray()
        for (i in queue.length() - MAX_QUEUE until queue.length()) trimmed.put(queue.get(i))
        return trimmed
    }

    private fun hmacHex(message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(BuildConfig.EXIT_PIN_API_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Sama seperti pada ExitPinVerifier: hanya untuk server lokal sekolah. */
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
