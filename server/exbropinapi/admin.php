<?php
/**
 * ExBro / CBT Cyber Edu - halaman pengaturan PIN keluar.
 *
 * Buka di browser: https://<domain>/exbropinapi/admin.php
 *
 * Tiga cara mengatur PIN, semuanya langsung berlaku untuk semua HP tanpa build
 * ulang APK:
 *   - PIN tetap diketik admin.
 *   - Tombol Generate: PIN acak 6 digit.
 *   - PIN otomatis: berganti sendiri tiap 15 menit, dihitung dari kode rahasia dan
 *     jam server (tanpa cron). Halaman memuat ulang dirinya saat PIN berganti.
 *
 * Data PIN disimpan di pin-store.php (tidak bisa dibaca lewat web). Akses
 * dilindungi password admin yang hash-nya disimpan di config.php sebagai
 * admin_password_hash.
 */
declare(strict_types=1);

require __DIR__ . '/common.php';

const ADMIN_SESSION_TIMEOUT = 900;    // 15 menit tanpa aktivitas
const ADMIN_SESSION_MAX = 14400;      // 4 jam sejak login, walau halaman terus dimuat ulang
const ADMIN_LOGIN_MAX_FAILURES = 5;   // per IP
const ADMIN_LOGIN_WINDOW = 900;       // 15 menit
const ADMIN_PASSWORD_MIN = 10;
const ADMIN_AUTO_REFRESH_MAX = 300;   // muat ulang paling lambat tiap 5 menit saat PIN otomatis aktif

date_default_timezone_set('Asia/Jakarta');

header('Content-Type: text/html; charset=utf-8');
header('Cache-Control: no-store');
header('X-Frame-Options: DENY');
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
header("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");

$isHttps = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
    || strtolower((string) ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '')) === 'https';

session_name('exbro_pin_admin');
session_set_cookie_params([
    'lifetime' => 0,
    'path' => rtrim(str_replace('\\', '/', dirname((string) $_SERVER['SCRIPT_NAME'])), '/') . '/',
    'secure' => $isHttps,
    'httponly' => true,
    'samesite' => 'Strict',
]);
session_start();

function e(string $value): string
{
    return htmlspecialchars($value, ENT_QUOTES, 'UTF-8');
}

function redirect_self(): void
{
    header('Location: admin.php', true, 303);
    exit;
}

/** $showPin: tampilkan PIN aktif secara menonjol pada muatan halaman berikutnya. */
function flash(string $type, string $message, bool $showPin = false): void
{
    $_SESSION['flash'] = ['type' => $type, 'message' => $message, 'show_pin' => $showPin];
}

function minutes_label(int $seconds): string
{
    return intdiv($seconds, 60) . ' menit';
}

if (empty($_SESSION['csrf'])) {
    $_SESSION['csrf'] = bin2hex(random_bytes(32));
}

$config = exbro_config();
$passwordHash = (string) ($config['admin_password_hash'] ?? '');
$isPost = ($_SERVER['REQUEST_METHOD'] ?? '') === 'POST';
$action = $isPost ? (string) ($_POST['action'] ?? '') : '';
$csrfOk = $isPost && hash_equals((string) $_SESSION['csrf'], (string) ($_POST['csrf'] ?? ''));
$saveError = 'Gagal menyimpan PIN. Pastikan folder exbropinapi dapat ditulis oleh PHP.';

$error = null;
$generatedHash = null;

// Sesi admin kedaluwarsa setelah tidak aktif, atau setelah batas maksimal sejak login.
if (!empty($_SESSION['auth'])) {
    $idle = time() - (int) ($_SESSION['last_seen'] ?? 0) > ADMIN_SESSION_TIMEOUT;
    $tooOld = time() - (int) ($_SESSION['login_at'] ?? 0) > ADMIN_SESSION_MAX;
    if ($idle || $tooOld) {
        unset($_SESSION['auth'], $_SESSION['last_seen'], $_SESSION['login_at']);
        flash('error', $idle
            ? 'Sesi berakhir karena tidak aktif. Silakan masuk lagi.'
            : 'Sesi berakhir (maksimal 4 jam). Silakan masuk lagi.');
    }
}
$isAuth = !empty($_SESSION['auth']);

if ($isPost && !$csrfOk) {
    $error = 'Formulir kedaluwarsa. Muat ulang halaman lalu coba lagi.';
} elseif ($config === []) {
    // Ditangani di tampilan.
} elseif ($passwordHash === '') {
    // Mode awal: hanya membuatkan hash password, tidak menyimpan apa pun.
    if ($action === 'generate_hash') {
        $pw = (string) ($_POST['password'] ?? '');
        $pw2 = (string) ($_POST['password_confirm'] ?? '');
        if (strlen($pw) < ADMIN_PASSWORD_MIN) {
            $error = 'Password admin minimal ' . ADMIN_PASSWORD_MIN . ' karakter.';
        } elseif (!hash_equals($pw, $pw2)) {
            $error = 'Kedua password tidak sama.';
        } else {
            $generatedHash = password_hash($pw, PASSWORD_DEFAULT);
        }
    }
} elseif ($action === 'login') {
    $state = exbro_failures_open(ADMIN_LOGIN_WINDOW);
    $ipKey = 'a:' . (string) ($_SERVER['REMOTE_ADDR'] ?? 'unknown');
    if ($state === null) {
        $error = 'Penyimpanan sementara server tidak tersedia.';
    } elseif (exbro_failures_count($state, $ipKey) >= ADMIN_LOGIN_MAX_FAILURES) {
        exbro_failures_close($state);
        $error = 'Terlalu banyak percobaan password salah. Coba lagi 15 menit lagi.';
    } elseif (password_verify((string) ($_POST['password'] ?? ''), $passwordHash)) {
        exbro_failures_clear($state, $ipKey);
        exbro_failures_close($state);
        session_regenerate_id(true);
        $_SESSION['auth'] = true;
        $_SESSION['last_seen'] = time();
        $_SESSION['login_at'] = time();
        $_SESSION['csrf'] = bin2hex(random_bytes(32));
        redirect_self();
    } else {
        exbro_failures_add($state, $ipKey);
        exbro_failures_close($state);
        $error = 'Password admin salah.';
    }
} elseif ($action === 'logout') {
    $_SESSION = [];
    session_regenerate_id(true);
    flash('ok', 'Anda sudah keluar dari halaman admin.');
    redirect_self();
} elseif ($isAuth && $action === 'change_pin') {
    $pin = (string) ($_POST['pin'] ?? '');
    $pin2 = (string) ($_POST['pin_confirm'] ?? '');
    if (!preg_match('/^[0-9]{' . EXBRO_PIN_MIN . ',' . EXBRO_PIN_MAX . '}$/', $pin)) {
        $error = 'PIN harus ' . EXBRO_PIN_MIN . '–' . EXBRO_PIN_MAX . ' digit angka.';
    } elseif (!hash_equals($pin, $pin2)) {
        $error = 'Kedua PIN tidak sama.';
    } elseif (!exbro_save_pin($pin)) {
        $error = $saveError;
    } else {
        flash('ok', 'PIN keluar berhasil diganti dan langsung berlaku untuk semua HP.', true);
        redirect_self();
    }
} elseif ($isAuth && ($action === 'generate_pin' || $action === 'disable_rotation')) {
    if (!exbro_save_pin(exbro_generate_pin())) {
        $error = $saveError;
    } else {
        flash('ok', $action === 'disable_rotation'
            ? 'PIN otomatis dimatikan. PIN tetap baru dibuat dan langsung berlaku untuk semua HP.'
            : 'PIN acak baru berhasil dibuat dan langsung berlaku untuk semua HP.', true);
        redirect_self();
    }
} elseif ($isAuth && ($action === 'enable_rotation' || $action === 'rotate_now')) {
    if (!exbro_enable_rotation()) {
        $error = $saveError;
    } else {
        flash('ok', $action === 'rotate_now'
            ? 'PIN otomatis diganti sekarang. PIN lama tidak berlaku lagi.'
            : 'PIN otomatis aktif. PIN berganti sendiri tiap ' . minutes_label(exbro_rotate_period()) . '.');
        redirect_self();
    }
}

$flash = $_SESSION['flash'] ?? null;
unset($_SESSION['flash']);
$showSavedPin = !empty($flash['show_pin']);

if ($config === []) {
    $view = 'no_config';
} elseif ($passwordHash === '') {
    $view = 'setup';
} elseif (!empty($_SESSION['auth'])) {
    $view = 'form';
    $_SESSION['last_seen'] = time();
} else {
    $view = 'login';
}

$now = time();
$current = exbro_pin_state($now);
$hasActivePin = $current['pin'] !== '';
$isRotating = $current['mode'] === 'rotating';
$periodLabel = minutes_label($isRotating ? (int) $current['period'] : exbro_rotate_period());

if ($isRotating) {
    $status = 'PIN otomatis aktif: berganti sendiri tiap ' . $periodLabel . '.';
} elseif ($current['source'] === 'store') {
    $status = 'PIN terakhir diganti: ' . date('d-m-Y H:i', (int) $current['changed_at']) . ' WIB';
} elseif ($hasActivePin) {
    $status = 'PIN aktif masih dari config.php (belum pernah diganti lewat halaman ini).';
} else {
    $status = 'Belum ada PIN aktif. HP memakai PIN lokal sampai PIN dibuat di sini.';
}

// Saat PIN otomatis aktif, halaman memuat ulang dirinya tepat setelah PIN berganti
// (dan paling lambat tiap 5 menit) agar PIN yang tampil selalu yang berlaku.
$refreshSeconds = null;
if ($view === 'form' && $isRotating) {
    $refreshSeconds = max(5, min((int) $current['expires_at'] - $now + 2, ADMIN_AUTO_REFRESH_MAX));
}

$csrf = (string) $_SESSION['csrf'];
?><!doctype html>
<html lang="id">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<?php if ($refreshSeconds !== null): ?>
<meta http-equiv="refresh" content="<?= $refreshSeconds ?>">
<?php endif; ?>
<title>Ganti PIN Keluar · CBT Cyber Edu</title>
<style>
  :root { --primary:#1B4F8A; --bg:#F1F4F8; --card:#fff; --text:#1d2433; --muted:#5b6475; --ok:#1B7F3B; --danger:#B3261E; }
  * { box-sizing:border-box; }
  body { margin:0; font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; background:var(--bg); color:var(--text); }
  header { background:var(--primary); color:#fff; padding:18px 20px; }
  header h1 { margin:0; font-size:18px; }
  header p { margin:2px 0 0; opacity:.85; font-size:13px; }
  main { max-width:440px; margin:24px auto; padding:0 16px; }
  .card { background:var(--card); border-radius:12px; padding:22px; box-shadow:0 1px 3px rgba(0,0,0,.08); }
  .card + .card { margin-top:16px; }
  h2 { margin:0 0 6px; font-size:17px; }
  h3 { margin:0 0 4px; font-size:15px; }
  p.hint { margin:0 0 16px; color:var(--muted); font-size:13px; }
  label { display:block; font-weight:600; font-size:13px; margin:14px 0 6px; }
  input { width:100%; padding:11px 12px; border:1px solid #c9d1dc; border-radius:8px; font-size:16px; }
  input:focus { outline:2px solid var(--primary); border-color:transparent; }
  button { width:100%; margin-top:20px; padding:12px; border:0; border-radius:8px; background:var(--primary); color:#fff; font-size:15px; font-weight:600; cursor:pointer; }
  button.secondary { background:#fff; color:var(--primary); border:1px solid var(--primary); margin-top:10px; }
  button.link { background:none; color:var(--primary); margin-top:8px; padding:8px; font-weight:500; }
  .msg { border-radius:8px; padding:10px 12px; margin-bottom:16px; font-size:14px; }
  .msg.ok { background:#e6f4ea; color:var(--ok); }
  .msg.error { background:#fce8e6; color:var(--danger); }
  .status { background:var(--bg); border-radius:8px; padding:10px 12px; font-size:13px; color:var(--muted); }
  code { display:block; background:#1d2433; color:#e8eef7; padding:12px; border-radius:8px; font-size:12px; word-break:break-all; margin:12px 0; }
  ol { padding-left:20px; margin:8px 0 0; font-size:13px; color:var(--muted); }
  .pin-box { margin-top:14px; text-align:center; border:2px dashed var(--primary); border-radius:10px; padding:14px; }
  .pin-box small { display:block; color:var(--muted); font-size:12px; }
  .pin { font:700 34px/1.2 ui-monospace,"Cascadia Mono",Consolas,monospace; letter-spacing:6px; color:var(--primary); margin:6px 0; }
  details.reveal { margin-top:12px; font-size:13px; }
  details.reveal summary { cursor:pointer; color:var(--primary); font-weight:600; }
</style>
</head>
<body>
<header>
  <h1>Ganti PIN Keluar</h1>
  <p>CBT Cyber Edu · ExBro</p>
</header>
<main>
<div class="card">
<?php if ($flash): ?>
  <div class="msg <?= e((string) $flash['type']) ?>"><?= e((string) $flash['message']) ?></div>
<?php endif; ?>
<?php if ($error): ?>
  <div class="msg error"><?= e($error) ?></div>
<?php endif; ?>

<?php if ($view === 'no_config'): ?>
  <h2>config.php belum ada</h2>
  <p class="hint">Unggah config.php ke folder exbropinapi terlebih dahulu.</p>

<?php elseif ($view === 'setup'): ?>
  <h2>Buat password admin</h2>
  <?php if ($generatedHash !== null): ?>
    <p class="hint">Password <b>belum</b> tersimpan. Tambahkan baris berikut ke config.php di server, di dalam tanda <b>[ ]</b>, lalu simpan dan muat ulang halaman ini.</p>
    <code>'admin_password_hash' =&gt; '<?= e($generatedHash) ?>',</code>
    <ol>
      <li>Salin baris di atas persis, termasuk tanda kutip tunggal dan koma.</li>
      <li>Setelah disimpan, halaman ini akan meminta password yang barusan Anda buat.</li>
    </ol>
  <?php else: ?>
    <p class="hint">Halaman ini belum punya password admin. Isi password di bawah; halaman hanya membuatkan kode untuk ditempel ke config.php dan tidak menyimpan apa pun ke server.</p>
    <form method="post" autocomplete="off">
      <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
      <input type="hidden" name="action" value="generate_hash">
      <label for="password">Password admin (minimal <?= ADMIN_PASSWORD_MIN ?> karakter)</label>
      <input id="password" name="password" type="password" minlength="<?= ADMIN_PASSWORD_MIN ?>" required autocomplete="new-password">
      <label for="password_confirm">Ulangi password</label>
      <input id="password_confirm" name="password_confirm" type="password" minlength="<?= ADMIN_PASSWORD_MIN ?>" required autocomplete="new-password">
      <button type="submit">Buat kode password</button>
    </form>
  <?php endif; ?>

<?php elseif ($view === 'login'): ?>
  <h2>Masuk admin</h2>
  <p class="hint">Masukkan password admin untuk mengganti PIN keluar.</p>
  <form method="post">
    <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
    <input type="hidden" name="action" value="login">
    <label for="password">Password admin</label>
    <input id="password" name="password" type="password" required autofocus autocomplete="current-password">
    <button type="submit">Masuk</button>
  </form>

<?php elseif ($isRotating): ?>
  <h2>PIN keluar otomatis</h2>
  <div class="status"><?= e($status) ?></div>
  <!-- PIN otomatis selalu ditampilkan: masa berlakunya singkat, dan halaman ini
       memang dipakai sebagai layar PIN untuk pengawas. -->
  <div class="pin-box">
    <small>PIN keluar yang berlaku sekarang</small>
    <div class="pin"><?= e($current['pin']) ?></div>
    <small>Berlaku sampai pukul <b><?= e(date('H:i:s', (int) $current['expires_at'])) ?> WIB</b>.
      Halaman ini memuat ulang sendiri saat PIN berganti.</small>
  </div>
  <form method="post">
    <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
    <input type="hidden" name="action" value="rotate_now">
    <button type="submit">Ganti PIN sekarang</button>
  </form>
  <p class="hint" style="margin-top:14px"><a href="pelanggaran.php">Lihat rekap pelanggaran siswa</a></p>
  <form method="post">
    <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
    <input type="hidden" name="action" value="disable_rotation">
    <button type="submit" class="secondary">Matikan PIN otomatis</button>
  </form>
  <form method="post">
    <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
    <input type="hidden" name="action" value="logout">
    <button type="submit" class="link">Keluar dari halaman admin</button>
  </form>

<?php else: ?>
  <h2>PIN keluar baru</h2>
  <div class="status"><?= e($status) ?></div>
  <?php if ($showSavedPin && $hasActivePin): ?>
    <div class="pin-box">
      <small>PIN keluar yang berlaku sekarang</small>
      <div class="pin"><?= e($current['pin']) ?></div>
      <small>Catat PIN ini dan bagikan hanya kepada pengawas ujian.</small>
    </div>
  <?php elseif ($hasActivePin): ?>
    <!-- Disembunyikan di balik tombol agar tidak terbaca orang di sekitar layar. -->
    <details class="reveal">
      <summary>Tampilkan PIN aktif</summary>
      <div class="pin-box">
        <div class="pin"><?= e($current['pin']) ?></div>
      </div>
    </details>
  <?php endif; ?>
  <form method="post">
    <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
    <input type="hidden" name="action" value="generate_pin">
    <button type="submit">Generate PIN acak</button>
  </form>
  <form method="post">
    <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
    <input type="hidden" name="action" value="enable_rotation">
    <button type="submit" class="secondary">Aktifkan PIN otomatis (berganti tiap <?= e($periodLabel) ?>)</button>
  </form>
  <p class="hint" style="margin-top:14px"><a href="pelanggaran.php">Lihat rekap pelanggaran siswa</a></p>
</div>

<div class="card">
  <h3>Atau ketik PIN sendiri</h3>
  <form method="post" autocomplete="off">
    <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
    <input type="hidden" name="action" value="change_pin">
    <label for="pin">PIN baru (<?= EXBRO_PIN_MIN ?>–<?= EXBRO_PIN_MAX ?> digit angka)</label>
    <input id="pin" name="pin" type="password" inputmode="numeric" pattern="[0-9]{<?= EXBRO_PIN_MIN ?>,<?= EXBRO_PIN_MAX ?>}" minlength="<?= EXBRO_PIN_MIN ?>" maxlength="<?= EXBRO_PIN_MAX ?>" required autocomplete="new-password">
    <label for="pin_confirm">Ulangi PIN baru</label>
    <input id="pin_confirm" name="pin_confirm" type="password" inputmode="numeric" pattern="[0-9]{<?= EXBRO_PIN_MIN ?>,<?= EXBRO_PIN_MAX ?>}" minlength="<?= EXBRO_PIN_MIN ?>" maxlength="<?= EXBRO_PIN_MAX ?>" required autocomplete="new-password">
    <button type="submit">Simpan PIN</button>
  </form>
  <form method="post">
    <input type="hidden" name="csrf" value="<?= e($csrf) ?>">
    <input type="hidden" name="action" value="logout">
    <button type="submit" class="link">Keluar dari halaman admin</button>
  </form>
<?php endif; ?>
</div>
</main>
</body>
</html>
