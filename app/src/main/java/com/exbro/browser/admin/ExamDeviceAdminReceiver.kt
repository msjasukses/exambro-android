package com.exbro.browser.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device admin receiver. Diperlukan agar aplikasi bisa dijadikan Device Owner
 * sehingga kiosk mode berjalan tanpa dialog konfirmasi sistem.
 *
 * Pasang lewat perangkat yang belum punya akun (factory reset), lalu:
 *   adb shell dpm set-device-owner com.exbro.browser/.admin.ExamDeviceAdminReceiver
 */
class ExamDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin aktif")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(TAG, "Device admin dinonaktifkan")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        Log.i(TAG, "Masuk lock task: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        Log.i(TAG, "Keluar lock task")
    }

    companion object {
        private const val TAG = "ExamDeviceAdmin"
    }
}
