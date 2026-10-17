<?php
/**
 * Salin menjadi config.php lalu isi. File ini hanya mengembalikan array dan tidak
 * menghasilkan output apa pun bila dibuka langsung lewat browser.
 */
return [
    // Harus SAMA persis dengan api.key di exbro-api.properties pada proyek aplikasi.
    'api_key' => 'GANTI_DENGAN_API_KEY',

    // PIN keluar awal untuk semua HP. Setelah PIN diganti lewat admin.php, PIN baru
    // disimpan di pin-store.php dan nilai ini tidak dipakai lagi.
    // Selama masih GANTI_... dan belum ada PIN dari admin.php, endpoint menjawab 503
    // dan aplikasi memakai PIN lokal.
    'exit_pin' => 'GANTI_DENGAN_PIN',

    // Opsional: lama periode PIN otomatis dalam menit (1-1440). Bawaan 15 menit.
    // 'rotate_minutes' => 15,

    // Opsional: simpan catatan pelanggaran ke MySQL, bukan ke berkas log.
    // Tabel exbro_violations dibuat otomatis saat catatan pertama masuk.
    // 'violation_mysql' => [
    //     'dsn' => 'mysql:host=127.0.0.1;dbname=NAMA_DATABASE;charset=utf8mb4',
    //     'user' => 'USER_DATABASE',
    //     'password' => 'PASSWORD_DATABASE',
    // ],

    // Hash password untuk halaman admin.php (ganti PIN). Biarkan kosong, lalu buka
    // admin.php: halaman itu akan membuatkan baris hash untuk ditempel di sini.
    'admin_password_hash' => '',

    // Batas PIN salah dalam window_seconds sebelum dijawab 429.
    'max_failures_per_device' => 10,
    'max_failures_per_ip' => 300,
    'window_seconds' => 300,
];
