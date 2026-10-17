<?php
/**
 * ExBro / CBT Cyber Edu - fungsi bersama untuk verify-pin.php dan admin.php.
 *
 * File ini tidak menghasilkan output dan menjawab 404 bila dibuka langsung.
 */
declare(strict_types=1);

if (realpath(__FILE__) === realpath((string) ($_SERVER['SCRIPT_FILENAME'] ?? ''))) {
    http_response_code(404);
    exit;
}

const EXBRO_PIN_STORE = __DIR__ . '/pin-store.php';
const EXBRO_PIN_MIN = 4;
const EXBRO_PIN_MAX = 8;

/** Panjang PIN yang dibuat otomatis (tombol Generate dan PIN otomatis). */
const EXBRO_GENERATED_PIN_LENGTH = 6;

/** Periode bawaan PIN otomatis: 15 menit. Bisa diubah lewat rotate_minutes di config.php. */
const EXBRO_ROTATE_DEFAULT_SECONDS = 900;

/**
 * Masa tenggang setelah PIN otomatis berganti: PIN periode sebelumnya masih
 * diterima selama detik-detik awal periode baru, agar pengawas yang membaca PIN
 * menjelang pergantian tidak langsung ditolak.
 */
const EXBRO_ROTATE_GRACE_SECONDS = 120;

/** Isi config.php, atau array kosong bila file belum ada. */
function exbro_config(): array
{
    static $config = null;
    if ($config === null) {
        $file = __DIR__ . '/config.php';
        $loaded = is_file($file) ? require $file : [];
        $config = is_array($loaded) ? $loaded : [];
    }
    return $config;
}

function exbro_is_placeholder(string $value): bool
{
    return $value === '' || strpos($value, 'GANTI_') === 0;
}

/** Lama satu periode PIN otomatis dalam detik (1–1440 menit, bawaan 15 menit). */
function exbro_rotate_period(): int
{
    $minutes = (int) (exbro_config()['rotate_minutes'] ?? 0);
    return ($minutes >= 1 && $minutes <= 1440) ? $minutes * 60 : EXBRO_ROTATE_DEFAULT_SECONDS;
}

/**
 * Isi pin-store.php. Formatnya baris PHP yang langsung exit, diikuti JSON, jadi
 * dibuka lewat browser hanya menghasilkan 404 kosong. Dibaca sebagai teks biasa
 * (bukan require) agar PIN baru tidak tertahan cache opcache.
 *
 * Bentuk data:
 *   PIN tetap    : {"mode":"static","pin":"123456","changed_at":<epoch>}
 *   PIN otomatis : {"mode":"rotating","secret":"<64 hex>","changed_at":<epoch>}
 * Data lama tanpa "mode" tetapi berisi "pin" dianggap PIN tetap.
 */
function exbro_read_store(): ?array
{
    if (!is_file(EXBRO_PIN_STORE)) {
        return null;
    }
    $raw = @file_get_contents(EXBRO_PIN_STORE);
    if ($raw === false) {
        return null;
    }
    $pos = strpos($raw, '?>');
    if ($pos === false) {
        return null;
    }
    $data = json_decode(trim(substr($raw, $pos + 2)), true);
    return is_array($data) ? $data : null;
}

/** Menulis pin-store.php secara atomik (tulis file sementara lalu rename). */
function exbro_write_store(array $data): bool
{
    $payload = "<?php http_response_code(404); exit; ?>\n" . json_encode($data) . "\n";

    // Berakhiran .php agar, bila rename gagal dan file tertinggal, isinya tetap
    // tidak bisa dibaca lewat web.
    $tmp = __DIR__ . '/pin-store.tmp-' . bin2hex(random_bytes(6)) . '.php';
    if (@file_put_contents($tmp, $payload, LOCK_EX) === false) {
        return false;
    }
    @chmod($tmp, 0640);
    if (!@rename($tmp, EXBRO_PIN_STORE)) {
        @unlink($tmp);
        return false;
    }
    return true;
}

/** Menyimpan PIN tetap (diketik admin atau hasil tombol Generate). */
function exbro_save_pin(string $pin): bool
{
    return exbro_write_store(['mode' => 'static', 'pin' => $pin, 'changed_at' => time()]);
}

/**
 * Mengaktifkan PIN otomatis dengan kode rahasia baru. Dipanggil juga oleh tombol
 * "Ganti PIN sekarang": kode rahasia baru langsung menghasilkan PIN yang berbeda.
 */
function exbro_enable_rotation(): bool
{
    return exbro_write_store([
        'mode' => 'rotating',
        'secret' => bin2hex(random_bytes(32)),
        'changed_at' => time(),
    ]);
}

/** PIN acak dengan sumber acak kriptografis, termasuk kemungkinan diawali angka 0. */
function exbro_generate_pin(): string
{
    $max = (int) str_repeat('9', EXBRO_GENERATED_PIN_LENGTH);
    return str_pad((string) random_int(0, $max), EXBRO_GENERATED_PIN_LENGTH, '0', STR_PAD_LEFT);
}

/**
 * PIN otomatis untuk satu periode. Cara kerjanya sama dengan HOTP (RFC 4226),
 * memakai HMAC-SHA256: kode rahasia + nomor periode menjadi PIN 6 digit. Hanya
 * bergantung pada jam server, bukan jam HP.
 */
function exbro_rotating_pin(string $secretHex, int $counter): string
{
    $hash = hash_hmac('sha256', pack('J', $counter), (string) hex2bin($secretHex), true);
    $offset = ord($hash[strlen($hash) - 1]) & 0x0f;
    $value = ((ord($hash[$offset]) & 0x7f) << 24)
        | (ord($hash[$offset + 1]) << 16)
        | (ord($hash[$offset + 2]) << 8)
        | ord($hash[$offset + 3]);
    $modulo = 10 ** EXBRO_GENERATED_PIN_LENGTH;
    return str_pad((string) ($value % $modulo), EXBRO_GENERATED_PIN_LENGTH, '0', STR_PAD_LEFT);
}

/**
 * Keadaan PIN saat ini.
 *
 * @return array{
 *   mode: string,        "rotating" | "static"
 *   source: string,      "store" (diatur lewat admin.php) | "config" (exit_pin di config.php)
 *   pin: string,         PIN yang ditampilkan ke admin ('' bila belum ada)
 *   changed_at: ?int,
 *   expires_at: ?int,    khusus PIN otomatis: saat PIN berganti
 *   period: ?int,        khusus PIN otomatis: lama periode (detik)
 *   accepted: string[]   PIN yang diterima verify-pin.php saat ini
 * }
 */
function exbro_pin_state(?int $now = null): array
{
    $now = $now ?? time();
    $store = exbro_read_store();

    if ($store !== null && ($store['mode'] ?? '') === 'rotating'
        && is_string($store['secret'] ?? null) && preg_match('/^[0-9a-f]{64}$/', $store['secret'])) {
        $period = exbro_rotate_period();
        $counter = intdiv($now, $period);
        $pin = exbro_rotating_pin($store['secret'], $counter);
        $accepted = [$pin];
        if ($now - $counter * $period < EXBRO_ROTATE_GRACE_SECONDS) {
            $accepted[] = exbro_rotating_pin($store['secret'], $counter - 1);
        }
        return [
            'mode' => 'rotating',
            'source' => 'store',
            'pin' => $pin,
            'changed_at' => (int) ($store['changed_at'] ?? 0),
            'expires_at' => ($counter + 1) * $period,
            'period' => $period,
            'accepted' => $accepted,
        ];
    }

    if ($store !== null && is_string($store['pin'] ?? null) && $store['pin'] !== '') {
        return [
            'mode' => 'static',
            'source' => 'store',
            'pin' => $store['pin'],
            'changed_at' => (int) ($store['changed_at'] ?? 0),
            'expires_at' => null,
            'period' => null,
            'accepted' => [$store['pin']],
        ];
    }

    $pin = (string) (exbro_config()['exit_pin'] ?? '');
    return [
        'mode' => 'static',
        'source' => 'config',
        'pin' => exbro_is_placeholder($pin) ? '' : $pin,
        'changed_at' => null,
        'expires_at' => null,
        'period' => null,
        'accepted' => exbro_is_placeholder($pin) ? [] : [$pin],
    ];
}

/**
 * Kompatibilitas untuk admin.php / verify-pin.php versi lama yang mungkin masih ada
 * di server selama proses unggah, agar urutan unggah file tidak menyebabkan error.
 *
 * @return array{pin: string, changed_at: ?int, source: string}
 */
function exbro_current_pin(): array
{
    $state = exbro_pin_state();
    return ['pin' => $state['pin'], 'changed_at' => $state['changed_at'], 'source' => $state['source']];
}

// --- Catatan pelanggaran siswa --------------------------------------------------

/**
 * Berkas log pelanggaran. Formatnya sama dengan pin-store.php: baris PHP yang
 * langsung exit, diikuti satu baris JSON per kejadian, sehingga tidak bisa diunduh
 * lewat browser. Dipakai bila MySQL tidak dikonfigurasi.
 */
const EXBRO_VIOLATION_LOG = __DIR__ . '/violations-log.php';
const EXBRO_VIOLATION_PREFIX = "<?php http_response_code(404); exit; ?>\n";

/** Jumlah baris terakhir yang disimpan pada mode berkas. */
const EXBRO_VIOLATION_MAX_ROWS = 5000;

/** Maksimal kejadian per pengiriman dari satu HP. */
const EXBRO_VIOLATION_MAX_BATCH = 50;

/**
 * Koneksi MySQL opsional. Diaktifkan dengan mengisi violation_mysql di config.php:
 *   'violation_mysql' => [
 *       'dsn' => 'mysql:host=127.0.0.1;dbname=cbt;charset=utf8mb4',
 *       'user' => 'cbt', 'password' => 'rahasia',
 *   ],
 * Tabel exbro_violations dibuat otomatis bila belum ada.
 */
function exbro_violation_db(): ?PDO
{
    static $pdo = false;
    if ($pdo !== false) {
        return $pdo;
    }
    $cfg = exbro_config()['violation_mysql'] ?? null;
    if (!is_array($cfg) || empty($cfg['dsn'])) {
        $pdo = null;
        return null;
    }
    try {
        $db = new PDO(
            (string) $cfg['dsn'],
            (string) ($cfg['user'] ?? ''),
            (string) ($cfg['password'] ?? ''),
            [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_EMULATE_PREPARES => false]
        );
        $db->exec(
            'CREATE TABLE IF NOT EXISTS exbro_violations (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                at_time INT NOT NULL,
                received_at INT NOT NULL,
                device VARCHAR(64) NOT NULL,
                type VARCHAR(48) NOT NULL,
                detail VARCHAR(255) NOT NULL,
                page VARCHAR(255) NOT NULL,
                app_version VARCHAR(48) NOT NULL,
                android VARCHAR(48) NOT NULL,
                ip VARCHAR(45) NOT NULL,
                INDEX idx_device (device),
                INDEX idx_at (at_time)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4'
        );
        $pdo = $db;
    } catch (Throwable $e) {
        $pdo = null;
    }
    return $pdo;
}

/**
 * Menyimpan kejadian pelanggaran. Mengembalikan jumlah baris yang tersimpan.
 *
 * @param array<int, array<string, mixed>> $rows
 */
function exbro_violation_save(array $rows): int
{
    if ($rows === []) {
        return 0;
    }

    $db = exbro_violation_db();
    if ($db !== null) {
        $stmt = $db->prepare(
            'INSERT INTO exbro_violations
             (at_time, received_at, device, type, detail, page, app_version, android, ip)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)'
        );
        $saved = 0;
        foreach ($rows as $r) {
            $stmt->execute([
                $r['at_time'], $r['received_at'], $r['device'], $r['type'],
                $r['detail'], $r['page'], $r['app_version'], $r['android'], $r['ip'],
            ]);
            $saved++;
        }
        return $saved;
    }

    $handle = @fopen(EXBRO_VIOLATION_LOG, 'c+');
    if ($handle === false) {
        return 0;
    }
    flock($handle, LOCK_EX);
    if (filesize(EXBRO_VIOLATION_LOG) === 0) {
        fwrite($handle, EXBRO_VIOLATION_PREFIX);
    }
    fseek($handle, 0, SEEK_END);
    foreach ($rows as $r) {
        fwrite($handle, json_encode($r, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES) . "\n");
    }
    fflush($handle);
    flock($handle, LOCK_UN);
    fclose($handle);
    exbro_violation_trim();
    return count($rows);
}

/** Memangkas berkas log agar tidak tumbuh tanpa batas. */
function exbro_violation_trim(): void
{
    $lines = exbro_violation_lines();
    if (count($lines) <= EXBRO_VIOLATION_MAX_ROWS) {
        return;
    }
    $keep = array_slice($lines, -EXBRO_VIOLATION_MAX_ROWS);
    @file_put_contents(EXBRO_VIOLATION_LOG, EXBRO_VIOLATION_PREFIX . implode("\n", $keep) . "\n", LOCK_EX);
}

/** @return string[] baris JSON mentah dari berkas log */
function exbro_violation_lines(): array
{
    if (!is_file(EXBRO_VIOLATION_LOG)) {
        return [];
    }
    $raw = @file_get_contents(EXBRO_VIOLATION_LOG);
    if ($raw === false) {
        return [];
    }
    $pos = strpos($raw, '?>');
    $body = $pos === false ? $raw : substr($raw, $pos + 2);
    return array_values(array_filter(array_map('trim', explode("\n", $body)), static function ($l) {
        return $l !== '';
    }));
}

/**
 * Kejadian terbaru lebih dulu.
 *
 * @return array<int, array<string, mixed>>
 */
function exbro_violation_recent(int $limit = 200, string $device = ''): array
{
    $db = exbro_violation_db();
    if ($db !== null) {
        $sql = 'SELECT at_time, received_at, device, type, detail, page, app_version, android, ip
                FROM exbro_violations';
        $params = [];
        if ($device !== '') {
            $sql .= ' WHERE device = ?';
            $params[] = $device;
        }
        $sql .= ' ORDER BY at_time DESC, id DESC LIMIT ' . max(1, min(2000, $limit));
        $stmt = $db->prepare($sql);
        $stmt->execute($params);
        return $stmt->fetchAll(PDO::FETCH_ASSOC);
    }

    $rows = [];
    foreach (array_reverse(exbro_violation_lines()) as $line) {
        $row = json_decode($line, true);
        if (!is_array($row)) {
            continue;
        }
        if ($device !== '' && ($row['device'] ?? '') !== $device) {
            continue;
        }
        $rows[] = $row;
        if (count($rows) >= $limit) {
            break;
        }
    }
    return $rows;
}

// --- Catatan percobaan gagal (dipakai pembatasan PIN salah & login admin) ---------

/**
 * Membuka catatan kegagalan dengan kunci eksklusif dan membuang entri yang sudah
 * lewat jendela waktu. Wajib ditutup dengan exbro_failures_close().
 */
function exbro_failures_open(int $windowSeconds): ?array
{
    $file = sys_get_temp_dir() . DIRECTORY_SEPARATOR . 'exbropinapi-failures.json';
    $handle = @fopen($file, 'c+');
    if ($handle === false) {
        return null;
    }
    flock($handle, LOCK_EX);
    $raw = stream_get_contents($handle);
    $data = json_decode($raw === false ? '' : $raw, true);
    if (!is_array($data)) {
        $data = [];
    }

    $now = time();
    foreach ($data as $key => $stamps) {
        $data[$key] = array_values(array_filter(
            (array) $stamps,
            static function ($t) use ($now, $windowSeconds): bool {
                return is_int($t) && $t > $now - $windowSeconds;
            }
        ));
        if ($data[$key] === []) {
            unset($data[$key]);
        }
    }

    return ['handle' => $handle, 'data' => $data, 'now' => $now];
}

function exbro_failures_count(array $state, string $key): int
{
    return count($state['data'][$key] ?? []);
}

function exbro_failures_add(array &$state, string $key): void
{
    $state['data'][$key][] = $state['now'];
}

function exbro_failures_clear(array &$state, string $key): void
{
    unset($state['data'][$key]);
}

function exbro_failures_close(array $state): void
{
    $handle = $state['handle'];
    ftruncate($handle, 0);
    rewind($handle);
    fwrite($handle, (string) json_encode($state['data']));
    fflush($handle);
    flock($handle, LOCK_UN);
    fclose($handle);
}
