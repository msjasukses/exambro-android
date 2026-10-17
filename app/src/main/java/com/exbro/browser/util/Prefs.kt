package com.exbro.browser.util

import android.content.Context
import android.content.SharedPreferences
import com.exbro.browser.BuildConfig

/**
 * Konfigurasi ujian.
 *
 * Hanya link URL ujian yang disimpan dan bisa diubah pengawas. Seluruh proteksi
 * dikunci menyala di dalam kode agar tidak ada yang bisa mematikannya dari layar
 * konfigurasi.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("exbro_config", Context.MODE_PRIVATE)

    var examUrl: String
        get() = sp.getString(KEY_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_URL, value).apply()

    /** Durasi pengerjaan dalam menit. 0 berarti tanpa batas waktu. */
    var examDurationMinutes: Int
        get() = sp.getInt(KEY_DURATION, 0)
        set(value) = sp.edit().putInt(KEY_DURATION, value).apply()

    /**
     * Waktu sesi ujian dimulai (epoch millis), dicatat saat pengawas menekan
     * Masuk Ujian. Disimpan agar hitungan mundur tidak ter-reset bila aplikasi
     * sempat tertutup lalu dibuka lagi di tengah ujian.
     */
    var sessionStartAt: Long
        get() = sp.getLong(KEY_START_AT, 0L)
        set(value) = sp.edit().putLong(KEY_START_AT, value).apply()

    /** Batas waktu ujian (epoch millis), atau 0 bila tanpa batas. */
    fun deadlineAt(): Long {
        val minutes = examDurationMinutes
        val start = sessionStartAt
        return if (minutes <= 0 || start <= 0L) 0L else start + minutes * 60_000L
    }

    /** Kiosk Mode selalu aktif: tombol Home & Recents dikunci. */
    val kioskEnabled: Boolean = true


    /** Mode ketat selalu aktif: seluruh resource di luar white-list diblokir. */
    val strictResourceFilter: Boolean = true

    init {
        // Versi lama menyimpan PIN per HP dari layar setup. PIN itu dibersihkan agar
        // semua HP memakai PIN cadangan yang sama dari build.
        if (sp.contains(KEY_LEGACY_PIN)) sp.edit().remove(KEY_LEGACY_PIN).apply()
    }

    /**
     * PIN cadangan, dipakai hanya bila server PIN tidak terjangkau. Sama untuk semua
     * HP; ditetapkan saat build dari fallback.pin di exbro-api.properties.
     */
    val exitPin: String
        get() = BuildConfig.EXIT_FALLBACK_PIN

    /**
     * ID acak per instalasi (bukan ID perangkat). Dipakai server untuk membatasi
     * percobaan PIN salah per HP, supaya satu siswa tidak mengunci HP lain.
     */
    val installId: String
        get() = sp.getString(KEY_INSTALL_ID, null)
            ?: java.util.UUID.randomUUID().toString().also {
                sp.edit().putString(KEY_INSTALL_ID, it).apply()
            }

    companion object {
        private const val KEY_URL = "exam_url"
        private const val KEY_DURATION = "exam_duration"
        private const val KEY_START_AT = "session_start_at"
        /** Hanya untuk membersihkan PIN per HP dari versi lama. */
        private const val KEY_LEGACY_PIN = "exit_pin"
        private const val KEY_INSTALL_ID = "install_id"

    }
}
