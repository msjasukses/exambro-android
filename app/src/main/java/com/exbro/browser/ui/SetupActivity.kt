package com.exbro.browser.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.exbro.browser.R
import com.exbro.browser.databinding.ActivitySetupBinding
import com.exbro.browser.security.SecurityGuard
import com.exbro.browser.security.UrlWhitelist
import com.exbro.browser.util.CrashReporter
import com.exbro.browser.util.Prefs

/**
 * Halaman konfigurasi pengawas: cukup memasukkan link URL ujian.
 *
 * PIN keluar diverifikasi ke server CBT (lihat ExitPinVerifier) dan ujian berjalan
 * tanpa batas waktu. Kiosk Mode dan mode ketat dikunci menyala di [Prefs].
 * Pemeriksaan keamanan perangkat tetap dijalankan sesaat sebelum ujian dimulai;
 * yang ditampilkan hanya dialog penolakan bila perangkat tidak memenuhi syarat.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.etUrl.setText(prefs.examUrl)
        binding.btnStart.setOnClickListener { startExam() }

        CrashReporter.read(this)?.let { showCrashReport(it) }
    }

    /**
     * Menampilkan penyebab aplikasi berhenti mendadak pada pemakaian sebelumnya,
     * beserta tombol salin agar bisa dikirim ke pembuat aplikasi tanpa kabel USB.
     */
    private fun showCrashReport(report: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.crash_title)
            .setMessage(getString(R.string.crash_hint) + "\n\n" + report.take(4000))
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("CyberEdu crash", report))
                Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.crash_delete) { _, _ -> CrashReporter.clear(this) }
            .show()
    }

    private fun startExam() {
        val rawUrl = binding.etUrl.text.toString().trim()
        if (rawUrl.isEmpty()) {
            binding.tilUrl.error = getString(R.string.err_url_empty)
            return
        }

        val url = normalizeUrl(rawUrl)
        if (UrlWhitelist.hostOf(url).isEmpty()) {
            binding.tilUrl.error = getString(R.string.err_url_invalid)
            return
        }
        binding.tilUrl.error = null

        // Pemeriksaan tetap berjalan di latar; hanya temuan yang menghalangi ujian
        // yang ditampilkan ke pengawas.
        val blockers = SecurityGuard.blockers(SecurityGuard.scan(this))
        if (blockers.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.blocked_title)
                .setMessage(
                    getString(R.string.blocked_message) + "\n\n" +
                        blockers.joinToString("\n\n") { "• ${it.title}\n  ${it.detail}" }
                )
                .setPositiveButton(R.string.close, null)
                .setCancelable(false)
                .show()
            return
        }

        prefs.examUrl = url
        // Kolom durasi sudah dihapus. Durasi lama yang mungkin masih tersimpan di HP
        // dinolkan agar logout otomatis tidak terpicu diam-diam.
        prefs.examDurationMinutes = 0
        prefs.sessionStartAt = System.currentTimeMillis()

        startActivity(Intent(this, ExamActivity::class.java))
    }

    /**
     * Melengkapi skema bila pengawas hanya mengetik alamat. Server CBT di jaringan
     * lokal (mis. 192.168.10.158) hampir selalu melayani HTTP biasa, jadi alamat
     * lokal tanpa skema memakai http:// agar tidak gagal karena HTTPS yang tidak ada.
     */
    private fun normalizeUrl(input: String): String {
        if (input.startsWith("http://", true) || input.startsWith("https://", true)) return input
        val host = UrlWhitelist.hostOf("http://$input")
        return if (UrlWhitelist.isPrivateHost(host)) "http://$input" else "https://$input"
    }
}
