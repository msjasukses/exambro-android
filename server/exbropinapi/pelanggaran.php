<?php
/**
 * ExBro / CBT Cyber Edu - rekap pelanggaran siswa.
 *
 * Buka di browser: https://<domain>/exbropinapi/pelanggaran.php
 * Memakai sesi login yang sama dengan admin.php; bila belum login, pengguna
 * diarahkan ke sana.
 *
 * Tambahkan ?format=csv untuk mengunduh rekap sebagai berkas CSV.
 */
declare(strict_types=1);

require __DIR__ . '/common.php';

const VIEW_SESSION_TIMEOUT = 900;
const VIEW_DEFAULT_LIMIT = 200;

date_default_timezone_set('Asia/Jakarta');

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

$idle = time() - (int) ($_SESSION['last_seen'] ?? 0) > VIEW_SESSION_TIMEOUT;
if (empty($_SESSION['auth']) || $idle) {
    header('Location: admin.php', true, 303);
    exit;
}
$_SESSION['last_seen'] = time();

function e(string $value): string
{
    return htmlspecialchars($value, ENT_QUOTES, 'UTF-8');
}

$device = preg_match('/^[A-Za-z0-9-]{8,64}$/', (string) ($_GET['device'] ?? ''))
    ? (string) $_GET['device'] : '';
$limit = max(20, min(2000, (int) ($_GET['limit'] ?? VIEW_DEFAULT_LIMIT)));
$rows = exbro_violation_recent($limit, $device);

// --- Unduhan CSV ---------------------------------------------------------------
if (($_GET['format'] ?? '') === 'csv') {
    header('Content-Type: text/csv; charset=utf-8');
    header('Content-Disposition: attachment; filename="pelanggaran-' . date('Ymd-Hi') . '.csv"');
    $out = fopen('php://output', 'w');
    fputcsv($out, ['Waktu', 'Diterima', 'Perangkat', 'Jenis', 'Keterangan', 'Halaman', 'Versi aplikasi', 'Android', 'IP']);
    foreach ($rows as $r) {
        fputcsv($out, [
            date('d-m-Y H:i:s', (int) ($r['at_time'] ?? 0)),
            date('d-m-Y H:i:s', (int) ($r['received_at'] ?? 0)),
            (string) ($r['device'] ?? ''),
            (string) ($r['type'] ?? ''),
            (string) ($r['detail'] ?? ''),
            (string) ($r['page'] ?? ''),
            (string) ($r['app_version'] ?? ''),
            (string) ($r['android'] ?? ''),
            (string) ($r['ip'] ?? ''),
        ]);
    }
    fclose($out);
    exit;
}

header('Content-Type: text/html; charset=utf-8');
header('Cache-Control: no-store');
header('X-Frame-Options: DENY');
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
header("Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'");

$storage = exbro_violation_db() !== null ? 'MySQL' : 'berkas log';
$devices = [];
foreach (exbro_violation_recent(2000) as $r) {
    $d = (string) ($r['device'] ?? '');
    if ($d !== '') {
        $devices[$d] = ($devices[$d] ?? 0) + 1;
    }
}
arsort($devices);
?><!doctype html>
<html lang="id">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>Rekap Pelanggaran · CBT Cyber Edu</title>
<style>
  :root { --primary:#1B4F8A; --bg:#F1F4F8; --card:#fff; --text:#1d2433; --muted:#5b6475; --danger:#B3261E; }
  * { box-sizing:border-box; }
  body { margin:0; font:14px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif; background:var(--bg); color:var(--text); }
  header { background:var(--primary); color:#fff; padding:18px 20px; }
  header h1 { margin:0; font-size:18px; }
  header p { margin:2px 0 0; opacity:.85; font-size:13px; }
  main { max-width:1000px; margin:20px auto; padding:0 16px; }
  .card { background:var(--card); border-radius:12px; padding:18px; box-shadow:0 1px 3px rgba(0,0,0,.08); }
  .bar { display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin-bottom:14px; font-size:13px; }
  select, input[type=number] { padding:8px 10px; border:1px solid #c9d1dc; border-radius:8px; font-size:14px; }
  button, a.btn { display:inline-block; padding:9px 14px; border:0; border-radius:8px; background:var(--primary); color:#fff; font-size:14px; font-weight:600; cursor:pointer; text-decoration:none; }
  a.btn.secondary { background:#fff; color:var(--primary); border:1px solid var(--primary); }
  table { width:100%; border-collapse:collapse; font-size:13px; }
  th, td { text-align:left; padding:8px 10px; border-bottom:1px solid #e3e8ef; vertical-align:top; }
  th { background:var(--bg); font-size:12px; text-transform:uppercase; letter-spacing:.03em; color:var(--muted); }
  td.jenis { color:var(--danger); font-weight:600; white-space:nowrap; }
  td.waktu { white-space:nowrap; font-variant-numeric:tabular-nums; }
  .kosong { padding:28px 10px; text-align:center; color:var(--muted); }
  .meta { margin-top:12px; font-size:12px; color:var(--muted); }
  .wrap { overflow-x:auto; }
</style>
</head>
<body>
<header>
  <h1>Rekap Pelanggaran</h1>
  <p>CBT Cyber Edu · ExBro</p>
</header>
<main>
<div class="card">
  <form class="bar" method="get">
    <label for="device">Perangkat</label>
    <select id="device" name="device">
      <option value="">Semua perangkat</option>
      <?php foreach ($devices as $d => $count): ?>
        <option value="<?= e((string) $d) ?>"<?= $device === (string) $d ? ' selected' : '' ?>>
          <?= e(substr((string) $d, 0, 8)) ?>… (<?= (int) $count ?>)
        </option>
      <?php endforeach; ?>
    </select>
    <label for="limit">Tampilkan</label>
    <input id="limit" name="limit" type="number" min="20" max="2000" step="20" value="<?= (int) $limit ?>">
    <button type="submit">Terapkan</button>
    <a class="btn secondary" href="?format=csv&amp;device=<?= e($device) ?>&amp;limit=<?= (int) $limit ?>">Unduh CSV</a>
    <a class="btn secondary" href="admin.php">Halaman PIN</a>
  </form>

  <div class="wrap">
  <table>
    <tr>
      <th>Waktu</th><th>Perangkat</th><th>Jenis</th><th>Keterangan</th><th>Halaman</th><th>Android</th>
    </tr>
    <?php if ($rows === []): ?>
      <tr><td colspan="6" class="kosong">Belum ada catatan pelanggaran.</td></tr>
    <?php endif; ?>
    <?php foreach ($rows as $r): ?>
      <tr>
        <td class="waktu"><?= e(date('d-m-Y H:i:s', (int) ($r['at_time'] ?? 0))) ?></td>
        <td><?= e(substr((string) ($r['device'] ?? ''), 0, 8)) ?>…</td>
        <td class="jenis"><?= e((string) ($r['type'] ?? '')) ?></td>
        <td><?= e((string) ($r['detail'] ?? '')) ?></td>
        <td><?= e((string) ($r['page'] ?? '')) ?></td>
        <td><?= e((string) ($r['android'] ?? '')) ?></td>
      </tr>
    <?php endforeach; ?>
  </table>
  </div>

  <p class="meta">
    Menampilkan <?= count($rows) ?> catatan terakhir · Penyimpanan: <?= e($storage) ?> ·
    Waktu yang ditampilkan adalah waktu kejadian di HP siswa (WIB).
  </p>
</div>
</main>
</body>
</html>
