package com.exbro.browser.security

import android.net.Uri

/**
 * Filter white-list URL.
 *
 * Sebuah URL dianggap sah bila host-nya mengandung salah satu kata kunci domain ujian
 * (atau sama dengan / subdomain dari host URL ujian). Selain itu ditolak.
 */
class UrlWhitelist(baseUrl: String, extraKeywords: List<String>) {

    private val baseHost: String = hostOf(baseUrl)
    private val keywords: List<String> = buildList {
        if (baseHost.isNotEmpty()) add(baseHost)
        addAll(extraKeywords.map { it.lowercase().trim() }.filter { it.isNotEmpty() })
    }.distinct()

    fun isAllowed(url: String?): Boolean {
        if (url.isNullOrBlank()) return false

        val lower = url.lowercase().trim()

        // Skema internal WebView yang aman diabaikan (blank page / data hasil injeksi).
        if (lower.startsWith("about:blank")) return true

        // Skema berbahaya: intent://, tel:, sms:, mailto:, file://, content://, market://
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false

        val host = hostOf(lower)
        if (host.isEmpty()) return false

        return keywords.any { key ->
            host == key || host.endsWith(".$key") || host.contains(key)
        }
    }

    fun describe(): String = if (keywords.isEmpty()) "-" else keywords.joinToString(", ")

    /** True bila server ujian berada di jaringan lokal sekolah. */
    fun isLocalServer(): Boolean = isPrivateHost(baseHost)

    companion object {
        fun hostOf(url: String): String = try {
            Uri.parse(url.trim()).host?.lowercase()?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            ""
        }

        private val IPV4 = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

        /**
         * Host jaringan lokal: IP privat (10.x, 172.16-31.x, 192.168.x), loopback,
         * link-local, nama .local/.lan, atau alamat IPv4 apa pun tanpa nama domain.
         */
        fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase()
            if (h.isEmpty()) return false
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan")) return true
            if (!IPV4.matches(h)) return false

            val parts = h.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.any { it !in 0..255 }) return false

            return when (parts[0]) {
                10, 127 -> true
                172 -> parts[1] in 16..31
                192 -> parts[1] == 168
                169 -> parts[1] == 254
                else -> true // IPv4 langsung tanpa nama domain: perlakukan sebagai server lokal
            }
        }
    }
}
