package com.exbro.browser.ui

import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.webkit.WebView

/**
 * WebView dengan proteksi anti copy-paste:
 *  - LongClick dimatikan sehingga menu seleksi teks tidak pernah muncul.
 *  - ActionMode (toolbar Copy / Paste / Share / Web Search) dibersihkan menunya
 *    lalu dibatalkan, baik pada mode float maupun primary.
 *
 * Catatan: pemblokiran sentuhan multi-jari sempat dipakai di sini, tetapi dibuang
 * karena ikut membuang sentuhan pada kolom input sehingga keyboard tidak muncul.
 * Fokus justru diminta secara eksplisit saat disentuh agar IME selalu tampil.
 */
class SecureWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    init {
        isLongClickable = false
        isHapticFeedbackEnabled = false
        isFocusable = true
        isFocusableInTouchMode = true
        setOnLongClickListener { true }
    }

    override fun performLongClick(): Boolean = true

    /** Pastikan WebView memegang fokus saat disentuh supaya keyboard muncul. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                if (!hasFocus()) requestFocus()
            }
        }
        return super.onTouchEvent(event)
    }

    override fun startActionMode(callback: ActionMode.Callback?): ActionMode? =
        super.startActionMode(EmptyCallback)?.also { it.finish() }

    override fun startActionMode(callback: ActionMode.Callback?, type: Int): ActionMode? =
        super.startActionMode(EmptyCallback, type)?.also { it.finish() }

    /** Callback yang selalu menolak pembuatan ActionMode dan mengosongkan menunya. */
    private object EmptyCallback : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            menu?.clear()
            return false
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            menu?.clear()
            return false
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false

        override fun onDestroyActionMode(mode: ActionMode?) = Unit
    }
}
