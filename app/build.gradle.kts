import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Kredensial penandatanganan dibaca dari keystore.properties di root project.
// File tersebut berisi password sehingga tidak ikut di-commit (lihat .gitignore).
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists() &&
    keystoreProps.getProperty("storeFile").isNullOrBlank().not()

// Verifikasi PIN keluar ke server CBT. api.url dan api.key dibaca dari
// exbro-api.properties yang tidak di-commit. Tanpa file itu, aplikasi hanya
// memakai PIN lokal dari layar setup.
val apiPropsFile = rootProject.file("exbro-api.properties")
val apiProps = Properties().apply {
    if (apiPropsFile.exists()) apiPropsFile.inputStream().use { load(it) }
}
fun apiProp(name: String): String =
    apiProps.getProperty(name, "").trim().replace("\\", "\\\\").replace("\"", "\\\"")

// PIN cadangan untuk semua HP, dipakai hanya bila server PIN tidak terjangkau.
val fallbackPin: String = apiProps.getProperty("fallback.pin", "").trim().ifEmpty {
    logger.warn("fallback.pin belum diisi di exbro-api.properties; memakai PIN cadangan bawaan 112233.")
    "112233"
}
require(fallbackPin.matches(Regex("\\d{4,8}"))) {
    "fallback.pin di exbro-api.properties harus 4-8 digit angka."
}

// Nomor versi otomatis dari waktu build (WIB). versionCode selalu naik sehingga APK
// baru selalu bisa menimpa yang lama, dan versionName terlihat di Setelan > Aplikasi
// untuk memastikan build mana yang terpasang di sebuah HP.
val buildTime: ZonedDateTime = ZonedDateTime.now(ZoneId.of("Asia/Jakarta"))
val autoVersionCode: Int =
    ((buildTime.toEpochSecond() - 1_704_067_200L) / 60L).toInt() // menit sejak 01-01-2024
val autoVersionName: String =
    "1.0." + buildTime.format(DateTimeFormatter.ofPattern("yyyyMMdd.HHmm"))

android {
    namespace = "com.exbro.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.exbro.browser"
        minSdk = 21
        targetSdk = 34
        versionCode = autoVersionCode
        versionName = autoVersionName

        buildConfigField("String", "EXIT_PIN_API_URL", "\"${apiProp("api.url")}\"")
        buildConfigField("String", "EXIT_PIN_API_KEY", "\"${apiProp("api.key")}\"")
        buildConfigField("String", "EXIT_FALLBACK_PIN", "\"$fallbackPin\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Build debug (mis. tombol Run di Android Studio) memakai nama paket terpisah
        // dan kunci debug. Tanpa akhiran ini, HP yang pernah dipasangi build debug akan
        // menolak APK rilis dengan pesan "Aplikasi tidak terinstal" karena tanda tangan
        // berbeda. Dengan akhiran, keduanya bisa terpasang berdampingan.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_label", "CyberEdu Debug")
        }
        release {
            // Tanpa keystore.properties, assembleRelease tetap berjalan tetapi
            // menghasilkan APK unsigned yang belum bisa dipasang ke perangkat.
            signingConfig = if (hasReleaseKeystore) signingConfigs.getByName("release") else null
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.google.android.material:material:1.12.0")
}
