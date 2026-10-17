package com.exbro.browser.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.exbro.browser.R
import com.exbro.browser.databinding.ActivityExamBinding
import com.exbro.browser.security.ExitPinVerifier
import com.exbro.browser.security.KioskController
import com.exbro.browser.security.SecurityGuard
import com.exbro.browser.security.ViolationReporter
import com.exbro.browser.security.UrlWhitelist
import com.exbro.browser.util.Prefs
import java.io.ByteArrayInputStream

/**
 * Layar ujian terkunci.
 *
 * Proteksi yang aktif di sini:
 *  - FLAG_SECURE (anti screenshot & perekaman layar)
 *  - White-list URL pada shouldOverrideUrlLoading / shouldInterceptRequest
 *  - Anti copy-paste (LongClick + ActionMode dimatikan, seleksi teks diblokir via CSS/JS)
 *  - Tombol Back dikunci (OnBackPressedCallback + onKeyDown)
 *  - Kiosk Mode / screen pinning sehingga tombol Home & Recents tidak berfungsi
 *  - Watchdog berkala: deteksi layar eksternal dan hilangnya fokus
 */
class ExamActivity : AppCompatActivity() {

    private lateinit var binding: ActivityExamBinding
    private lateinit var prefs: Prefs
    private lateinit var whitelist: UrlWhitelist

    private val handler = Handler(Looper.getMainLooper())
    private var violations = 0
    private var exiting = false

    /**
     * True sejak halaman ujian gagal dimuat (error jaringan, 404/500, sertifikat
     * ditolak) sampai ada halaman yang berhasil dimuat lagi. Selama true, PIN
     * cadangan diterima tanpa menunggu jawaban server.
     */
    private var examPageFailed = false

    /** Penanda error pada pemuatan halaman yang sedang berjalan. */
    private var currentLoadFailed = false

    /** True bila sentuhan terakhir diterima saat layar tertutup jendela aplikasi lain. */
    private var lastTouchObscured = false

    /** True bila sistem menolak menyembunyikan jendela melayang (dicatat di diagnostik). */
    private var overlayHideFailed = false

    /** True setelah ujian dihentikan karena pelanggaran; keluar tetap butuh PIN. */
    private var examBlocked = false

    /** True bila ujian memang dimulai dalam mode multi-jendela (mis. desktop windowing). */
    private var startedInMultiWindow = false

    private var kioskEverActive = false
    private var kioskRetries = 0
    private var lastKioskAttemptAt = 0L

    /** Batas waktu ujian (epoch millis); 0 berarti tanpa batas. */
    private var deadlineAt = 0L
    private var warned5Min = false
    private var warned1Min = false

    private val ticker = object : Runnable {
        override fun run() {
            onCountdownTick()
            if (!exiting) handler.postDelayed(this, 1_000L)
        }
    }

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    /** Catatan console & kejadian penting, dibaca pengawas lewat panel diagnostik. */
    private val diagnostics = ArrayDeque<String>()

    /** Host luar white-list yang resource-nya ikut dimuat halaman ujian. */
    private val externalHosts = linkedSetOf<String>()

    private val watchdog = object : Runnable {
        override fun run() {
            enforceRuntimeSecurity()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // --- Anti-Screenshot: harus dipasang sebelum konten ditampilkan ---
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Android 12+: sembunyikan paksa semua jendela melayang (bubble, chat head,
        // filter layar) milik aplikasi lain selama layar ujian tampil.
        //
        // Dibungkus runCatching: bila izin HIDE_OVERLAY_WINDOWS ditolak ROM tertentu,
        // ujian tetap berjalan dengan deteksi sentuhan tertutup sebagai gantinya,
        // bukan crash seperti sebelumnya.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setHideOverlayWindows(true) }
                .onFailure { overlayHideFailed = true }
        }

        binding = ActivityExamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        whitelist = UrlWhitelist(prefs.examUrl, emptyList())
        deadlineAt = prefs.deadlineAt()

        if (prefs.examUrl.isBlank()) {
            toast(getString(R.string.err_url_empty))
            finish()
            return
        }

        startedInMultiWindow =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode
        if (startedInMultiWindow) {
            logDiagnostic("Ujian dimulai dalam mode multi-jendela bawaan perangkat")
        }

        applyImmersiveMode()
        lockBackButton()
        setupWebView()
        setupHeaderButtons()

        binding.tvHost.text = UrlWhitelist.hostOf(prefs.examUrl)
        updateStatus()

        if (prefs.kioskEnabled) enterKiosk()

        if (overlayHideFailed) {
            logDiagnostic("Sistem menolak menyembunyikan jendela melayang; memakai deteksi sentuhan")
        }

        ViolationReporter.record(
            this, "sesi-mulai", "Ujian dimulai", UrlWhitelist.hostOf(prefs.examUrl)
        )
        flushViolations(force = true)

        binding.webView.loadUrl(prefs.examUrl)
    }

    // ------------------------------------------------------------------
    // Tampilan penuh: sembunyikan status bar & navigation bar
    // ------------------------------------------------------------------
    private fun applyImmersiveMode() {
        // decorFitsSystemWindows dibiarkan true agar windowSoftInputMode=adjustResize
        // tetap bekerja: layar menyusut saat keyboard muncul, bukan tertutup keyboard.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ------------------------------------------------------------------
    // Lock Back Button
    // ------------------------------------------------------------------
    private fun lockBackButton() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Sengaja tidak melakukan apa pun: siswa tidak boleh kembali/keluar.
                registerViolation("tombol-kembali", getString(R.string.violation_back))
            }
        })
    }

    // ------------------------------------------------------------------
    // Anti jendela melayang (bubble / chat head / filter layar)
    // ------------------------------------------------------------------

    /**
     * Setiap sentuhan membawa penanda apakah jendela ujian sedang tertutup jendela
     * aplikasi lain. Penanda ini tetap jalan di Android lama yang belum punya
     * setHideOverlayWindows, dan tidak membuang sentuhan (berbeda dengan
     * filterTouchesWhenObscured yang dulu membuat keyboard tidak muncul).
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouchObscured = isTouchObscured(ev)
        if (lastTouchObscured) showOverlayWarning()
        return super.dispatchTouchEvent(ev)
    }

    private fun isTouchObscured(ev: MotionEvent): Boolean {
        if (ev.flags and MotionEvent.FLAG_WINDOW_IS_OBSCURED != 0) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ev.flags and MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED != 0
    }

    private fun showOverlayWarning() {
        if (exiting || binding.overlayPanel.visibility == View.VISIBLE) return
        binding.overlayPanel.visibility = View.VISIBLE
        registerViolation("jendela-melayang", getString(R.string.violation_overlay))
        logDiagnostic("Jendela melayang terdeteksi menutupi layar ujian")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_HOME -> {
                registerViolation("tombol-navigasi", getString(R.string.violation_navkey))
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ------------------------------------------------------------------
    // Kiosk Mode
    // ------------------------------------------------------------------
    private fun enterKiosk() {
        val started = KioskController.enter(this)
        kioskEverActive = kioskEverActive || started
        lastKioskAttemptAt = System.currentTimeMillis()
        if (!started) {
            toast(getString(R.string.kiosk_failed))
            logDiagnostic("Kiosk mode gagal diaktifkan")
        }
    }

    /**
     * Memasang kembali kiosk hanya bila sebelumnya memang pernah aktif lalu lepas,
     * dengan jumlah percobaan dan jeda terbatas.
     *
     * Tanpa batas ini, dialog sistem "Sematkan layar" muncul lagi setiap 3 detik
     * ketika pengguna membatalkannya, sehingga HP terasa terkunci total.
     */
    private fun maintainKiosk() {
        if (!prefs.kioskEnabled || KioskController.isLocked(this)) {
            kioskRetries = 0
            return
        }
        if (!kioskEverActive || kioskRetries >= MAX_KIOSK_RETRIES) return
        if (System.currentTimeMillis() - lastKioskAttemptAt < KIOSK_RETRY_DELAY_MS) return

        kioskRetries++
        logDiagnostic("Kiosk lepas, memasang ulang (percobaan $kioskRetries dari $MAX_KIOSK_RETRIES)")
        enterKiosk()
        if (kioskRetries >= MAX_KIOSK_RETRIES && !KioskController.isLocked(this)) {
            toast(getString(R.string.kiosk_pin_cancelled))
        }
    }

    // ------------------------------------------------------------------
    // WebView + White-list URL + Anti copy-paste
    // ------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() = with(binding.webView) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportZoom(false)
            // Popup diizinkan agar jendela peringatan milik aplikasi CBT tetap terbuka;
            // tujuannya diarahkan ke WebView yang sama lewat onCreateWindow.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            saveFormData = false
            cacheMode = WebSettings.LOAD_DEFAULT
            // Sama seperti Chrome di HP: media butuh gestur pengguna untuk berjalan.
            mediaPlaybackRequiresUserGesture = true

            // Beberapa aplikasi CBT mematikan skrip anti-kecurangannya bila mendeteksi
            // WebView. Penanda "; wv" dan "Version/x.x" dibuang agar situs melihat
            // lingkungan yang sama seperti Google Chrome di HP.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+\\s*"), "")
            // Server lokal kerap mencampur http dan https untuk asetnya sendiri.
            mixedContentMode = if (whitelist.isLocalServer()) {
                WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else {
                WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER

        // Unduhan diblokir sepenuhnya selama ujian.
        setDownloadListener { url, _, _, _, _ ->
            registerViolation("unduhan", getString(R.string.violation_download, UrlWhitelist.hostOf(url)))
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.progress = newProgress
                binding.progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            // --- Dialog JavaScript: peringatan anti-kecurangan milik aplikasi CBT ---
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@ExamActivity)
                    .setMessage(message.orEmpty())
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .show()
                return true
            }

            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult
            ): Boolean {
                AlertDialog.Builder(this@ExamActivity)
                    .setMessage(message.orEmpty())
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .show()
                return true
            }

            override fun onJsPrompt(
                view: WebView?, url: String?, message: String?,
                defaultValue: String?, result: JsPromptResult
            ): Boolean {
                val input = EditText(this@ExamActivity).apply { setText(defaultValue.orEmpty()) }
                AlertDialog.Builder(this@ExamActivity)
                    .setMessage(message.orEmpty())
                    .setView(input)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result.confirm(input.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                    .show()
                return true
            }

            override fun onJsBeforeUnload(
                view: WebView?, url: String?, message: String?, result: JsResult
            ): Boolean {
                // Konfirmasi "yakin meninggalkan halaman?" selalu dibatalkan:
                // siswa tidak boleh keluar dari halaman ujian.
                result.cancel()
                return true
            }

            // --- Layar penuh: dibutuhkan skrip yang memanggil requestFullscreen() ---
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                fullscreenCallback = callback
                fullscreenView = view
                binding.fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                binding.fullscreenContainer.visibility = View.VISIBLE
            }

            override fun onHideCustomView() {
                binding.fullscreenContainer.removeAllViews()
                binding.fullscreenContainer.visibility = View.GONE
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                fullscreenCallback = null
            }

            /**
             * Aplikasi CBT ini tidak memakai proctoring, jadi permintaan kamera/
             * mikrofon dari halaman selalu ditolak dan dicatat. Aplikasi pun tidak
             * meminta izin CAMERA maupun RECORD_AUDIO kepada sistem.
             */
            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
                logDiagnostic("Permintaan media ditolak: ${request.resources.joinToString()}")
            }

            // --- Popup diarahkan ke WebView yang sama ---
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@ExamActivity)
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView, request: WebResourceRequest
                    ): Boolean {
                        val target = request.url.toString()
                        if (whitelist.isAllowed(target)) binding.webView.loadUrl(target)
                        else registerViolation(
                            "link-luar",
                            getString(R.string.violation_url, UrlWhitelist.hostOf(target))
                        )
                        popup.destroy()
                        return true
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            // --- Pesan console dikumpulkan agar bisa dibaca lewat panel diagnostik ---
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                logDiagnostic("[${message.messageLevel()}] ${message.message()} @${message.lineNumber()}")
                return true
            }
        }

        webViewClient = object : WebViewClient() {

            // --- White-list URL: navigasi utama ---
            @RequiresApi(Build.VERSION_CODES.N)
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = handleNavigation(request.url.toString())

            @Deprecated("Dipakai pada perangkat di bawah API 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleNavigation(url)

            /**
             * Mode ketat hanya memblokir *dokumen* (halaman utama dan iframe) di luar
             * white-list. Skrip, CSS, font, dan gambar dari host lain tetap dimuat —
             * memblokirnya membuat skrip anti-kecurangan milik aplikasi CBT yang
             * di-host di CDN ikut mati tanpa pesan apa pun. Host luar yang dimuat
             * tetap dicatat dan bisa dilihat pengawas lewat panel diagnostik.
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (whitelist.isAllowed(url)) return null

                val host = UrlWhitelist.hostOf(url)
                if (!prefs.strictResourceFilter) return null

                return if (request.isForMainFrame) {
                    logDiagnostic("Dokumen luar diblokir: $url")
                    blockedResponse()
                } else {
                    externalHosts += host.ifEmpty { url }
                    null
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.progress.visibility = View.VISIBLE
                if (url != null && !url.startsWith("about:")) {
                    currentLoadFailed = false
                    hideError()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progress.visibility = View.GONE
                injectAntiCopyScript()
                binding.tvHost.text = UrlWhitelist.hostOf(url ?: prefs.examUrl)

                // Halaman berhasil dimuat tanpa error: PIN cadangan kembali nonaktif.
                if (examPageFailed && !currentLoadFailed && url != null && !url.startsWith("about:")) {
                    examPageFailed = false
                    logDiagnostic("Halaman ujian berhasil dimuat: PIN cadangan kembali nonaktif")
                }
            }

            /**
             * Server CBT di jaringan sekolah umumnya memakai sertifikat terbitan
             * sendiri sehingga WebView menolaknya diam-diam. Sertifikat seperti itu
             * diterima HANYA untuk host ujian yang beralamat lokal; host lain tetap
             * ditolak agar proteksi terhadap situs luar tidak bocor.
             */
            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                val host = UrlWhitelist.hostOf(error.url ?: "")
                if (whitelist.isAllowed(error.url) && UrlWhitelist.isPrivateHost(host)) {
                    handler.proceed()
                    toast(getString(R.string.ssl_local_accepted))
                } else {
                    handler.cancel()
                    showError(getString(R.string.ssl_rejected, host.ifEmpty { error.url ?: "-" }))
                }
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                if (!request.isForMainFrame) return
                showLoadFailure(
                    request.url.toString(),
                    error.description?.toString() ?: "-",
                    error.errorCode.toString()
                )
            }

            /** Jalur lama untuk Android 5.x yang belum punya WebResourceError. */
            @Suppress("OverridingDeprecatedMember", "DEPRECATION")
            @Deprecated("Dipakai pada perangkat di bawah API 23")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) return
                showLoadFailure(
                    failingUrl ?: prefs.examUrl,
                    description ?: "-",
                    errorCode.toString()
                )
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                response: WebResourceResponse
            ) {
                super.onReceivedHttpError(view, request, response)
                if (!request.isForMainFrame) return
                showLoadFailure(
                    request.url.toString(),
                    response.reasonPhrase ?: "HTTP error",
                    "HTTP ${response.statusCode}"
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Penanganan kegagalan memuat halaman
    // ------------------------------------------------------------------
    private fun showLoadFailure(url: String, description: String, code: String) {
        val hint = getString(
            if (whitelist.isLocalServer()) R.string.load_failed_hint_local
            else R.string.load_failed_hint_online
        )
        showError(getString(R.string.load_failed_detail, hint, url, "$description ($code)"))
    }

    private fun showError(message: String) {
        currentLoadFailed = true
        if (!examPageFailed) {
            examPageFailed = true
            logDiagnostic("Halaman ujian gagal dimuat: PIN cadangan aktif")
        }
        binding.tvError.text = message
        binding.errorPanel.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE
        // Tombol http:// hanya relevan bila server lokal sedang diakses lewat https.
        binding.btnTryHttp.visibility =
            if (whitelist.isLocalServer() && prefs.examUrl.startsWith("https://", true)) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun hideError() {
        binding.errorPanel.visibility = View.GONE
    }

    /** Mengembalikan true bila navigasi dibatalkan karena berada di luar white-list. */
    private fun handleNavigation(url: String): Boolean {
        if (whitelist.isAllowed(url)) return false
        registerViolation("link-luar", getString(R.string.violation_url, UrlWhitelist.hostOf(url).ifEmpty { url }))
        return true // link eksternal diabaikan
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** CSS + JS untuk mematikan seleksi teks, context menu, copy/cut/paste, dan drag. */
    private fun injectAntiCopyScript() {
        val js = """
            (function() {
              var style = document.getElementById('exbro-guard');
              if (!style) {
                style = document.createElement('style');
                style.id = 'exbro-guard';
                style.innerHTML = '*{-webkit-user-select:none!important;' +
                  'user-select:none!important;-webkit-touch-callout:none!important;}' +
                  'input,textarea,select,[contenteditable]{-webkit-user-select:text!important;' +
                  'user-select:text!important;-webkit-touch-callout:default!important;}';
                document.head.appendChild(style);
              }

              // Kolom isian dikecualikan: memblokir selectstart/contextmenu di sini
              // membuat caret gagal ditempatkan sehingga keyboard tidak muncul.
              var isField = function(el) {
                while (el && el !== document) {
                  var tag = (el.tagName || '').toLowerCase();
                  if (tag === 'input' || tag === 'textarea' || tag === 'select' ||
                      el.isContentEditable) { return true; }
                  el = el.parentNode;
                }
                return false;
              };

              // PENTING: hanya preventDefault, tanpa stopPropagation, dan dipasang di
              // fase bubble. Dengan begitu skrip anti-kecurangan milik aplikasi CBT
              // tetap menerima event copy/paste/contextmenu dan bisa mencatatnya —
              // aksinya saja yang dibatalkan. Versi sebelumnya memakai fase capture
              // plus stopPropagation sehingga handler milik situs tidak pernah jalan.
              var block = function(e){ e.preventDefault(); return false; };

              // Selalu diblokir di mana pun, termasuk di dalam kolom isian.
              ['copy','cut','paste'].forEach(function(ev){
                document.addEventListener(ev, block, false);
              });

              // Hanya diblokir di luar kolom isian.
              ['contextmenu','selectstart','dragstart'].forEach(function(ev){
                document.addEventListener(ev, function(e){
                  if (!isField(e.target)) { return block(e); }
                }, false);
              });

              document.addEventListener('keydown', function(e){
                var k = (e.key || '').toLowerCase();
                if ((e.ctrlKey || e.metaKey) && ['c','v','x','s','p','u'].indexOf(k) >= 0) {
                  return block(e);
                }
                if ((e.ctrlKey || e.metaKey) && k === 'a' && !isField(e.target)) {
                  return block(e);
                }
                if (k === 'printscreen' || k === 'f12') { return block(e); }
              }, false);
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    // ------------------------------------------------------------------
    // Watchdog: dipanggil tiap beberapa detik selama ujian
    // ------------------------------------------------------------------
    private fun enforceRuntimeSecurity() {
        if (examBlocked) return
        clearClipboard()
        flushViolations()

        // Layar terbagi memungkinkan siswa membuka browser lain di sisi layar.
        // Perangkat Android 16 dengan desktop windowing bisa menjalankan aplikasi
        // dalam mode multi-jendela sejak awal; keadaan itu tidak dihitung pelanggaran,
        // yang dihentikan hanya bila siswa berpindah ke layar terbagi saat ujian.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInMultiWindowMode && !startedInMultiWindow) {
            terminateExam(getString(R.string.terminate_multiwindow))
            return
        }

        if (SecurityGuard.hasExternalDisplay(this)) {
            terminateExam(getString(R.string.terminate_mirroring))
            return
        }

        maintainKiosk()
    }

    private fun clearClipboard() = runCatching {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.clearPrimaryClip()
        } else {
            @Suppress("DEPRECATION")
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    /**
     * Menghentikan ujian karena pelanggaran. Aplikasi sengaja TIDAK menutup diri
     * sendiri: layar ujian dikunci panel merah, sesi login direset, dan keluar tetap
     * harus lewat tombol yang meminta PIN pengawas.
     */
    private fun terminateExam(reason: String) {
        if (exiting || examBlocked) return
        examBlocked = true
        handler.removeCallbacks(ticker)
        logDiagnostic("Ujian dihentikan: $reason")

        binding.webView.loadUrl("about:blank")
        clearWebSession()

        ViolationReporter.record(this, "ujian-dihentikan", reason, currentPage())
        flushViolations(force = true)

        binding.tvBlockedReason.text = reason
        binding.overlayPanel.visibility = View.GONE
        binding.blockedPanel.visibility = View.VISIBLE
    }

    /**
     * Mencatat pelanggaran: tampil di header, masuk panel diagnostik, dan diantre
     * untuk dikirim ke server agar pengawas bisa melihat rekapnya.
     */
    private fun registerViolation(type: String, message: String) {
        violations++
        updateStatus()
        toast(message)
        logDiagnostic("Pelanggaran [$type]: $message")
        ViolationReporter.record(this, type, message, currentPage())
    }

    private fun currentPage(): String =
        runCatching { binding.webView.url }.getOrNull() ?: prefs.examUrl

    /** Mengirim antrean pelanggaran ke server; aman dipanggil berkali-kali. */
    private fun flushViolations(force: Boolean = false) {
        ViolationReporter.flush(this, prefs.examUrl, prefs.installId, force)
    }

    private fun updateStatus() {
        val kiosk = if (prefs.kioskEnabled) getString(R.string.kiosk_on)
        else getString(R.string.kiosk_off)

        binding.tvStatus.text = if (deadlineAt <= 0L) {
            getString(R.string.status_bar, kiosk, violations)
        } else {
            getString(R.string.status_bar_timer, kiosk, formatRemaining(remainingMillis()), violations)
        }
    }

    // ------------------------------------------------------------------
    // Durasi pengerjaan: hitung mundur & logout otomatis
    // ------------------------------------------------------------------

    private fun remainingMillis(): Long =
        (deadlineAt - System.currentTimeMillis()).coerceAtLeast(0L)

    private fun formatRemaining(millis: Long): String {
        val total = millis / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%02d:%02d", m, s)
    }

    private fun startCountdown() {
        if (deadlineAt <= 0L) return
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun onCountdownTick() {
        val remaining = remainingMillis()

        if (remaining == 0L) {
            onTimeUp()
            return
        }

        // Peringatan sekali saja pada 5 menit dan 1 menit terakhir.
        val minutesLeft = remaining / 60_000
        if (minutesLeft < 5 && !warned5Min) {
            warned5Min = true
            toast(getString(R.string.time_warning, 5))
        }
        if (minutesLeft < 1 && !warned1Min) {
            warned1Min = true
            toast(getString(R.string.time_warning_last))
        }

        updateStatus()
    }

    /** Waktu habis: logout dari aplikasi CBT, reset sesi, lepas kiosk, tutup aplikasi. */
    private fun onTimeUp() {
        if (exiting) return
        exiting = true
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(watchdog)
        updateStatus()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.time_up_title)
            .setMessage(R.string.time_up_message)
            .setCancelable(false)
            .setPositiveButton(R.string.close, null)
            .create()
        dialog.show()

        forceLogout {
            clearWebSession()
            // Aplikasi tetap ditutup sendiri meski tidak ada yang menekan tombol.
            handler.postDelayed({
                if (dialog.isShowing) dialog.dismiss()
                KioskController.exit(this)
                finishAffinity()
            }, TIME_UP_CLOSE_DELAY_MS)
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            handler.removeCallbacksAndMessages(null)
            dialog.dismiss()
            KioskController.exit(this)
            finishAffinity()
        }
    }

    // ------------------------------------------------------------------
    // Tombol header: Muat Ulang & Keluar (Keluar tetap meminta PIN pengawas)
    // ------------------------------------------------------------------
    private fun setupHeaderButtons() {
        binding.btnRefresh.setOnClickListener {
            toast(getString(R.string.reloading))
            hideError()
            binding.webView.reload()
        }
        binding.btnExit.setOnClickListener { promptExitPin() }

        binding.btnBlockedExit.setOnClickListener { promptExitPin() }

        binding.btnOverlayOk.setOnClickListener {
            // Sentuhan pada tombol ini sendiri ikut diperiksa: bila masih tertutup
            // jendela lain, panel tetap ditampilkan.
            if (lastTouchObscured) {
                toast(getString(R.string.overlay_still_present))
            } else {
                binding.overlayPanel.visibility = View.GONE
                logDiagnostic("Jendela melayang sudah tidak terdeteksi")
            }
        }
        binding.header.setOnLongClickListener { showDiagnostics(); true }

        binding.btnRetry.setOnClickListener {
            hideError()
            binding.webView.loadUrl(prefs.examUrl)
        }

        // Server CBT lokal sering hanya melayani HTTP biasa; tombol ini mengganti
        // skema tanpa mengubah host sehingga white-list tetap berlaku.
        binding.btnTryHttp.setOnClickListener {
            val httpUrl = prefs.examUrl.replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "http://")
            prefs.examUrl = httpUrl
            hideError()
            binding.webView.loadUrl(httpUrl)
        }
    }

    private fun promptExitPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.hint_pin)
        }

        // Tombol Keluar sengaja tidak menutup dialog otomatis: verifikasi ke server
        // berjalan asinkron, dan dialog harus tetap terbuka bila PIN salah.
        // Saat halaman ujian gagal dimuat, PIN cadangan aktif dan pengawas diberi tahu.
        val baseMessage = getString(
            if (examPageFailed) R.string.exit_message_fallback else R.string.exit_message
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(baseMessage)
            .setView(input)
            .setPositiveButton(R.string.exit_confirm, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setOnClickListener {
                val pin = input.text.toString()
                if (pin.isEmpty()) return@setOnClickListener

                // Halaman ujian gagal dimuat: PIN cadangan langsung diterima tanpa
                // menunggu server, yang kemungkinan besar sedang bermasalah.
                // PIN server tetap bisa dipakai lewat jalur di bawah.
                if (examPageFailed && pin == prefs.exitPin) {
                    logDiagnostic("PIN cadangan diterima karena halaman ujian gagal dimuat")
                    dialog.dismiss()
                    finishExam()
                    return@setOnClickListener
                }

                if (!ExitPinVerifier.isConfigured) {
                    checkLocalPin(pin, input, dialog)
                    return@setOnClickListener
                }

                confirm.isEnabled = false
                input.isEnabled = false
                dialog.setMessage(getString(R.string.pin_checking))

                ExitPinVerifier.verify(prefs.examUrl, pin, prefs.installId) { result ->
                    if (isFinishing || !dialog.isShowing) return@verify
                    confirm.isEnabled = true
                    input.isEnabled = true
                    dialog.setMessage(baseMessage)

                    when (result) {
                        ExitPinVerifier.Result.Valid -> {
                            logDiagnostic("PIN keluar diterima server")
                            dialog.dismiss()
                            finishExam()
                        }
                        ExitPinVerifier.Result.Invalid -> {
                            logDiagnostic("PIN keluar ditolak server")
                            input.text.clear()
                            toast(getString(R.string.err_pin_wrong))
                        }
                        is ExitPinVerifier.Result.Unavailable -> {
                            // Hanya jawaban server bertanda tangan yang final. Kondisi lain
                            // memakai PIN lokal agar pengawas tidak terkunci saat server mati.
                            logDiagnostic("Server PIN tidak tersedia (${result.reason}), memakai PIN lokal")
                            toast(getString(R.string.pin_server_unavailable))
                            checkLocalPin(pin, input, dialog)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun checkLocalPin(pin: String, input: EditText, dialog: AlertDialog) {
        if (pin == prefs.exitPin) {
            dialog.dismiss()
            finishExam()
        } else {
            input.text.clear()
            toast(getString(R.string.err_pin_wrong))
        }
    }

    /** Keluar ujian: logout dari aplikasi web, reset sesi, lepas kiosk, tutup layar. */
    private fun finishExam() {
        exiting = true
        handler.removeCallbacks(watchdog)

        ViolationReporter.record(
            this, "sesi-selesai", "Keluar dengan PIN pengawas", currentPage()
        )
        flushViolations(force = true)

        forceLogout {
            clearWebSession()
            toast(getString(R.string.session_reset))
            KioskController.exit(this)
            finish()
        }
    }

    // ------------------------------------------------------------------
    // Reset sesi login
    // ------------------------------------------------------------------

    /**
     * Upaya logout di sisi aplikasi web: mencari tautan logout pada halaman aktif
     * lalu membukanya sebentar agar server ikut menghapus sesi. Bila tautan tidak
     * ditemukan, langsung lanjut ke pembersihan sisi klien.
     */
    private fun forceLogout(onDone: () -> Unit) {
        val js = """
            (function(){
              try {
                var a = document.querySelectorAll('a[href]');
                for (var i = 0; i < a.length; i++) {
                  var h = (a[i].getAttribute('href') || '').toLowerCase();
                  var t = (a[i].textContent || '').toLowerCase();
                  if (h.indexOf('logout') >= 0 || h.indexOf('signout') >= 0 ||
                      h.indexOf('sign-out') >= 0 || h.indexOf('keluar') >= 0 ||
                      t.indexOf('logout') >= 0 || t.indexOf('keluar') >= 0) {
                    return a[i].href;
                  }
                }
              } catch (e) {}
              return '';
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js) { result ->
            val url = result?.trim('"')?.takeIf {
                it.isNotBlank() && it != "null" && whitelist.isAllowed(it)
            }
            if (url == null) {
                onDone()
            } else {
                binding.webView.loadUrl(url)
                handler.postDelayed({ onDone() }, LOGOUT_WAIT_MS)
            }
        }
    }

    /** Hapus seluruh jejak sesi di sisi klien sehingga siswa wajib login ulang. */
    private fun clearWebSession() {
        binding.webView.apply {
            stopLoading()
            clearHistory()
            clearFormData()
            clearSslPreferences()
            clearCache(true)
            loadUrl("about:blank")
        }

        CookieManager.getInstance().apply {
            removeSessionCookies(null)
            removeAllCookies(null)
            flush()
        }

        // localStorage, sessionStorage, IndexedDB, dan Web SQL milik situs ujian.
        WebStorage.getInstance().deleteAllData()

        WebViewDatabase.getInstance(this).apply {
            clearFormData()
            clearHttpAuthUsernamePassword()
        }
    }

    // ------------------------------------------------------------------
    // Siklus hidup
    // ------------------------------------------------------------------
    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        startCountdown()
    }

    override fun onPause() {
        super.onPause()
        binding.webView.onPause()
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(watchdog)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!exiting) registerViolation("keluar-aplikasi", getString(R.string.violation_leave))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        } else if (!exiting && !isImeVisible()) {
            // Fokus hilang: notifikasi ditarik, split screen, atau aplikasi lain muncul.
            // Munculnya keyboard tidak dihitung sebagai pelanggaran.
            registerViolation("fokus-hilang", getString(R.string.violation_focus))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        binding.webView.apply {
            stopLoading()
            clearHistory()
            clearCache(true)
            clearFormData()
            destroy()
        }
        CookieManager.getInstance().removeAllCookies(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Diagnostik & izin media
    // ------------------------------------------------------------------

    private fun logDiagnostic(line: String) {
        val stamp = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
        diagnostics.addLast("$stamp  $line")
        while (diagnostics.size > MAX_DIAGNOSTIC_LINES) diagnostics.removeFirst()
    }

    /** Tahan header untuk melihat pesan console & host luar yang dimuat halaman ujian. */
    private fun showDiagnostics() {
        val body = buildString {
            append(getString(R.string.diag_ua)).append('\n')
            append(binding.webView.settings.userAgentString).append("\n\n")

            append(getString(R.string.diag_external)).append('\n')
            append(if (externalHosts.isEmpty()) "-" else externalHosts.joinToString("\n"))
            append("\n\n")

            append(getString(R.string.diag_console)).append('\n')
            append(if (diagnostics.isEmpty()) "-" else diagnostics.joinToString("\n"))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.diag_title)
            .setMessage(body)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun isImeVisible(): Boolean =
        ViewCompat.getRootWindowInsets(binding.root)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 3_000L

        /** Jeda menunggu server memproses halaman logout sebelum data klien dihapus. */
        private const val LOGOUT_WAIT_MS = 1_500L

        /** Jeda sebelum aplikasi menutup diri setelah waktu ujian habis. */
        private const val TIME_UP_CLOSE_DELAY_MS = 6_000L

        /** Batas pemasangan ulang kiosk agar dialog "Sematkan layar" tidak muncul berulang. */
        private const val MAX_KIOSK_RETRIES = 2
        private const val KIOSK_RETRY_DELAY_MS = 30_000L

        private const val MAX_DIAGNOSTIC_LINES = 60
    }
}
