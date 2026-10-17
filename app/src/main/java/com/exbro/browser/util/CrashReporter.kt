package com.exbro.browser.util

import android.content.Context
import android.os.Build
import com.exbro.browser.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Menyimpan penyebab aplikasi berhenti mendadak ke berkas internal, lalu
 * menampilkannya di layar setup saat aplikasi dibuka lagi.
 *
 * Tanpa ini, crash di HP siswa hanya terlihat sebagai "aplikasi tertutup sendiri"
 * dan baru bisa dibaca lewat kabel USB.
 */
object CrashReporter {

    private const val FILE_NAME = "crash-terakhir.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(appContext, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val waktu = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.US).format(Date())

        val text = buildString {
            appendLine("Waktu    : $waktu")
            appendLine("Aplikasi : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("HP       : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android  : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread   : ${thread.name}")
            appendLine()
            append(stack)
        }
        File(context.filesDir, FILE_NAME).writeText(text)
    }

    /** Laporan crash terakhir, atau null bila tidak ada. */
    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }
}
