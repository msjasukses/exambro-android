package com.exbro.browser

import android.app.Application
import com.exbro.browser.util.CrashReporter

/** Dipasang paling awal agar crash di layar mana pun ikut terekam. */
class ExamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
