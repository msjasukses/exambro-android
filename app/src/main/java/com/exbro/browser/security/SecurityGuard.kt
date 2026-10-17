package com.exbro.browser.security

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import java.io.File

/** Tingkat keparahan temuan. BLOCKER menghentikan ujian, WARNING hanya ditampilkan. */
enum class Severity { BLOCKER, WARNING }

data class Threat(val severity: Severity, val title: String, val detail: String)

/**
 * Pemeriksaan lingkungan perangkat sebelum dan selama ujian berlangsung.
 * Dipanggil di SetupActivity (pra-ujian) dan berkala oleh watchdog di ExamActivity.
 */
object SecurityGuard {

    fun scan(context: Context): List<Threat> {
        val threats = mutableListOf<Threat>()

        if (isRooted()) {
            threats += Threat(
                Severity.BLOCKER,
                "Perangkat ter-root",
                "Ditemukan berkas su / paket manajemen root. Proteksi ujian dapat dilumpuhkan."
            )
        }

        if (isAdbEnabled(context)) {
            threats += Threat(
                Severity.WARNING,
                "USB debugging aktif",
                "Matikan opsi pengembang dan USB debugging sebelum ujian dimulai."
            )
        }

        if (isDeveloperOptionsEnabled(context)) {
            threats += Threat(
                Severity.WARNING,
                "Opsi pengembang aktif",
                "Nonaktifkan Developer Options pada Pengaturan perangkat."
            )
        }

        if (hasExternalDisplay(context)) {
            threats += Threat(
                Severity.BLOCKER,
                "Layar eksternal / screen mirroring",
                "Terdeteksi tampilan tambahan (cast, HDMI, atau perekam layar)."
            )
        }

        if (isEmulator()) {
            threats += Threat(
                Severity.WARNING,
                "Perangkat terindikasi emulator",
                "${Build.MANUFACTURER} ${Build.MODEL} (${Build.FINGERPRINT.take(48)})"
            )
        }

        activeAccessibilityServices(context).forEach { svc ->
            threats += Threat(
                Severity.WARNING,
                "Layanan aksesibilitas aktif",
                "$svc dapat membaca isi layar. Nonaktifkan sebelum ujian."
            )
        }

        return threats
    }

    fun blockers(threats: List<Threat>): List<Threat> = threats.filter { it.severity == Severity.BLOCKER }

    private fun isRooted(): Boolean {
        val paths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/system/app/Superuser.apk", "/system/xbin/daemonsu",
            "/data/local/bin/su", "/data/local/xbin/su", "/vendor/bin/su",
            "/system/bin/magisk", "/sbin/magisk", "/data/adb/magisk"
        )
        if (paths.any { runCatching { File(it).exists() }.getOrDefault(false) }) return true

        val tags = Build.TAGS
        if (tags != null && tags.contains("test-keys")) return true

        return runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("which", "su"))
            p.inputStream.bufferedReader().readLine() != null
        }.getOrDefault(false)
    }

    private fun isAdbEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrDefault(false)

    private fun isDeveloperOptionsEnabled(context: Context): Boolean = runCatching {
        Settings.Global.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) == 1
    }.getOrDefault(false)

    fun hasExternalDisplay(context: Context): Boolean = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.displays.count { it.state != Display.STATE_OFF } > 1
    }.getOrDefault(false)

    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        val product = Build.PRODUCT.lowercase()
        return fp.startsWith("generic") || fp.contains("vbox") || fp.contains("emulator") ||
            model.contains("emulator") || model.contains("android sdk built for") ||
            product.contains("sdk_gphone") || product.contains("emulator") ||
            Build.HARDWARE.lowercase().let { it.contains("goldfish") || it.contains("ranchu") }
    }

    private fun activeAccessibilityServices(context: Context): List<String> = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        if (enabled.isBlank()) emptyList()
        else enabled.split(':').map { it.substringBefore('/') }.filter { it.isNotBlank() }.distinct()
    }.getOrDefault(emptyList())
}
