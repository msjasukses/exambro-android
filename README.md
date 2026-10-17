# ExBro — Exam Browser Android

Browser ujian terkunci untuk Android. Pengawas memasukkan **link URL ujian**, aplikasi lalu
mengunci perangkat pada halaman tersebut dengan proteksi anti-screenshot, white-list URL,
anti copy-paste, kunci tombol Back, kiosk mode, dan deteksi aplikasi ganda.

- `applicationId`: `com.exbro.browser`
- minSdk 24 (Android 7.0), targetSdk 34, Kotlin + ViewBinding

## Cara build

Gradle wrapper sudah disertakan (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`),
jadi tidak perlu memasang Gradle terpisah.

```bash
./gradlew assembleDebug     # APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # pasang langsung ke perangkat yang tersambung
```

- Butuh **JDK 17 atau 21**. JDK 25 belum didukung Gradle 8.13 — build gagal dengan pesan yang hanya
  berisi nomor versi JDK. Bila `jbr` bawaan Android Studio Anda JDK 25, arahkan ke JDK 21:
  ```bash
  JAVA_HOME="/c/Users/<user>/.jdks/jbr-21.0.11" ./gradlew assembleDebug
  ```
  Di Android Studio: *Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK*.
- Toolchain: Gradle 8.13, AGP 8.13.2, Kotlin 2.1.20, compileSdk 34, JVM target 17.
- `local.properties` (berisi `sdk.dir`) dibuat otomatis Android Studio dan di-ignore Git.
- Jalankan pada perangkat fisik — sebagian proteksi (FLAG_SECURE, lock task) tidak berlaku penuh di emulator.

Status build terakhir yang diverifikasi: `assembleDebug` **BUILD SUCCESSFUL** (APK 5,96 MB), hanya
menyisakan tiga peringatan deprecation pada `WebSettings` yang memang disengaja untuk pengerasan.

### APK release bertanda tangan

1. Buat keystore (sekali seumur aplikasi, simpan baik-baik):
   ```bash
   keytool -genkeypair -v -keystore exbro-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias exbro
   ```
2. Salin `keystore.properties.example` menjadi `keystore.properties`, isi password Anda.
   File ini beserta `*.jks` sudah masuk `.gitignore`.
3. Build:
   ```bash
   ./gradlew assembleRelease
   ```
   Hasil: `app/build/outputs/apk/release/app-release.apk`.
4. Verifikasi tanda tangan:
   ```bash
   apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

Bila `keystore.properties` belum dibuat, `assembleRelease` tetap berhasil tetapi menghasilkan
`app-release-unsigned.apk` yang belum bisa dipasang ke perangkat.

Build release memakai R8 (`isMinifyEnabled` + `isShrinkResources`); keep rule untuk `SecureWebView`,
`ExamDeviceAdminReceiver`, dan konstruktor `View` sudah ada di `app/proguard-rules.pro`.
Status terverifikasi: `assembleRelease` **BUILD SUCCESSFUL**, lint vital lolos, APK 1,98 MB.

## Alur pemakaian

1. **SetupActivity** (pengawas): isi link URL ujian, kata kunci domain tambahan (opsional),
   PIN keluar, dan opsi proteksi. Tekan **Periksa Ulang Perangkat** untuk pemindaian pra-ujian.
2. Bila ditemukan temuan berkategori **BLOKIR** (aplikasi ganda, root, screen mirroring),
   tombol *Mulai Ujian* menolak melanjutkan.
3. **ExamActivity**: sesi ujian terkunci. Keluar hanya dengan **ketuk header 5×** lalu memasukkan PIN.

## Fitur proteksi & letak implementasinya

| Fitur | Implementasi |
|---|---|
| Anti-Screenshot | `WindowManager.LayoutParams.FLAG_SECURE` dipasang di `onCreate` sebelum `setContentView` — [ExamActivity.kt](app/src/main/java/com/exbro/browser/ui/ExamActivity.kt). Halaman setup juga dilindungi karena memuat PIN. Device Owner menambah `setScreenCaptureDisabled`. |
| White-list URL | [UrlWhitelist.kt](app/src/main/java/com/exbro/browser/security/UrlWhitelist.kt) dipakai pada `shouldOverrideUrlLoading` (navigasi utama) dan `shouldInterceptRequest` (mode ketat). Host URL ujian otomatis masuk white-list; skema non-http/https (`intent:`, `tel:`, `file:`, `market:`) selalu ditolak. |
| Anti copy-paste | [SecureWebView.kt](app/src/main/java/com/exbro/browser/ui/SecureWebView.kt): `performLongClick` dikunci, `startActionMode` memakai callback kosong (menu di-`clear()` lalu mode di-`finish()`), sentuhan multi-jari diabaikan. Ditambah injeksi CSS/JS `user-select:none` + blokir `contextmenu/copy/cut/paste/selectstart` dan Ctrl+C/V/X/A/S/P/U pada `onPageFinished`, serta pembersihan clipboard berkala. |
| Lock Back Button | `OnBackPressedCallback(true)` yang tidak melakukan apa pun (pengganti modern `onBackPressed()`), diperkuat `onKeyDown` yang menelan `KEYCODE_BACK`, `MENU`, `SEARCH`, `APP_SWITCH`. |
| Kiosk Mode (Pin Screen) | [KioskController.kt](app/src/main/java/com/exbro/browser/security/KioskController.kt): `startLockTask()` + `android:lockTaskMode="if_whitelisted"`. Bila aplikasi menjadi **Device Owner**: `setLockTaskPackages`, `setStatusBarDisabled`, `setKeyguardDisabled`, `setLockTaskFeatures(NONE)`, larangan pasang aplikasi. Watchdog memasang ulang lock task bila terlepas. |
| Deteksi aplikasi ganda | [DualAppDetector.kt](app/src/main/java/com/exbro/browser/security/DualAppDetector.kt): 7 lapis — user-id dari UID, jalur `dataDir`, nama proses, jalur storage eksternal, paket kloner terpasang (`<queries>` di manifest), signature container di `/proc/self/maps`, dan profil pengguna ganda. Dicek sebelum ujian dan tiap 3 detik selama ujian. |
| Proteksi tambahan | [SecurityGuard.kt](app/src/main/java/com/exbro/browser/security/SecurityGuard.kt): root/Magisk, USB debugging, opsi pengembang, layar eksternal / screen mirroring, emulator, layanan aksesibilitas (perekam layar). Unduhan diblokir, cookie & cache dibersihkan saat sesi berakhir, `filterTouchesWhenObscured` menahan overlay tap-jacking. |

## Mengaktifkan kiosk penuh (Device Owner)

Tanpa Device Owner, Android menampilkan dialog konfirmasi screen pinning sekali di awal dan
siswa masih bisa melepasnya dengan menahan Back+Recents. Untuk penguncian penuh:

```bash
adb shell dpm set-device-owner com.exbro.browser/.admin.ExamDeviceAdminReceiver
```

Syarat: perangkat baru di-factory reset, belum ada akun Google yang login, aplikasi sudah terpasang.
Melepasnya: `adb shell dpm remove-active-admin com.exbro.browser/.admin.ExamDeviceAdminReceiver`.

## Catatan

- PIN keluar default `112233` — ganti pada layar setup sebelum dipakai.
- `usesCleartextTraffic="true"` agar server CBT lokal berbasis `http://` (mis. `http://192.168.1.10/cbt`)
  tetap bisa diakses. Set ke `false` di manifest bila seluruh server ujian sudah HTTPS.
- Gestur screenshot vendor (tiga jari, tombol fisik) tetap tertahan `FLAG_SECURE`: hasil tangkapan
  layar akan gagal atau menghasilkan gambar hitam.
- Mode ketat (blokir seluruh resource di luar white-list) dapat mematahkan halaman yang memuat
  font/CDN eksternal — aktifkan hanya bila server ujian melayani seluruh asetnya sendiri.
