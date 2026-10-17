package com.exbro.browser.security

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.exbro.browser.admin.ExamDeviceAdminReceiver

/**
 * Kiosk Mode / Screen Pinning.
 *
 * Dua tingkat:
 *  - Device Owner (dipasang lewat `adb shell dpm set-device-owner ...`):
 *    lock task otomatis tanpa dialog, status bar & tombol Home benar-benar mati,
 *    keluar hanya lewat aplikasi.
 *  - Tanpa Device Owner: memakai screen pinning bawaan Android; sistem menampilkan
 *    dialog konfirmasi sekali, setelah itu Home/Recents ikut terkunci.
 */
object KioskController {

    private const val TAG = "KioskController"

    fun adminComponent(context: Context) =
        ComponentName(context, ExamDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean = runCatching {
        dpm(context).isDeviceOwnerApp(context.packageName)
    }.getOrDefault(false)

    fun isLocked(activity: Activity): Boolean = runCatching {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            am.isInLockTaskMode
        }
    }.getOrDefault(false)

    /** Aktifkan kiosk. Mengembalikan true bila lock task berhasil dimulai. */
    fun enter(activity: Activity): Boolean {
        val dpm = dpm(activity)
        val admin = adminComponent(activity)

        if (isDeviceOwner(activity)) {
            runCatching {
                dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setKeyguardDisabled(admin, true)
                    dpm.setStatusBarDisabled(admin, true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                }
                // Cegah pemasangan aplikasi lain & screenshot selama sesi ujian.
                dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_APPS)
                dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_ADD_USER)
                // Memblokir jendela melayang (bubble/chat head) milik aplikasi lain.
                dpm.addUserRestriction(admin, android.os.UserManager.DISALLOW_CREATE_WINDOWS)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setScreenCaptureDisabled(admin, true)
                }
            }.onFailure { Log.w(TAG, "Gagal menerapkan kebijakan device owner", it) }
        }

        return runCatching {
            activity.startLockTask()
            true
        }.onFailure { Log.w(TAG, "startLockTask gagal", it) }.getOrDefault(false)
    }

    /** Keluar dari kiosk (dipanggil hanya setelah PIN pengawas benar). */
    fun exit(activity: Activity) {
        val dpm = dpm(activity)
        val admin = adminComponent(activity)

        runCatching { activity.stopLockTask() }
            .onFailure { Log.w(TAG, "stopLockTask gagal", it) }

        if (isDeviceOwner(activity)) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setStatusBarDisabled(admin, false)
                    dpm.setKeyguardDisabled(admin, false)
                    dpm.setScreenCaptureDisabled(admin, false)
                }
                dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_INSTALL_APPS)
                dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_ADD_USER)
                dpm.clearUserRestriction(admin, android.os.UserManager.DISALLOW_CREATE_WINDOWS)
            }.onFailure { Log.w(TAG, "Gagal memulihkan kebijakan", it) }
        }
    }

    private fun dpm(context: Context) =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
}
