<?php
/**
 * ExBro / CBT Cyber Edu - verifikasi PIN keluar.
 *
 * Letakkan folder exbropinapi di web root server CBT sehingga dapat diakses di
 *   http(s)://<host-link-ujian>/exbropinapi/verify-pin.php
 *
 * Protokol:
 *   Request  POST application/json
 *            {"nonce": "<32 hex>", "device": "<id perangkat>", "pin_hmac": "<64 hex>"}
 *            pin_hmac = HMAC-SHA256(api_key, nonce + "|" + pin)
 *   Response 200 {"result": "valid"|"invalid",
 *                 "sig": HMAC-SHA256(api_key, nonce + "|" + result)}
 *            400 payload tidak valid, 405 bukan POST, 429 terlalu banyak PIN salah,
 *            503 belum dikonfigurasi.
 *
 * PIN yang dicocokkan adalah PIN yang diatur lewat admin.php (PIN tetap, atau PIN
 * otomatis yang berganti tiap periode), atau exit_pin di config.php bila belum
 * pernah diatur.
 *
 * PIN tidak pernah dikirim apa adanya dan jawaban ditandatangani memakai nonce dari
 * aplikasi, sehingga tetap aman walau koneksinya HTTP biasa di jaringan sekolah.
 */
declare(strict_types=1);

require __DIR__ . '/common.php';

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');

function respond(int $status, array $body): void
{
    http_response_code($status);
    echo json_encode($body);
    exit;
}

if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
    respond(405, ['error' => 'method_not_allowed']);
}

$config = exbro_config();
$apiKey = (string) ($config['api_key'] ?? '');
// PIN yang sah saat ini: PIN tetap, atau PIN otomatis periode berjalan (ditambah
// periode sebelumnya selama masa tenggang).
$acceptedPins = exbro_pin_state()['accepted'];
if ($config === [] || exbro_is_placeholder($apiKey) || $acceptedPins === []) {
    respond(503, ['error' => 'not_configured']);
}

$maxPerDevice = (int) ($config['max_failures_per_device'] ?? 10);
$maxPerIp = (int) ($config['max_failures_per_ip'] ?? 300);
$window = (int) ($config['window_seconds'] ?? 300);

$payload = json_decode((string) file_get_contents('php://input'), true);
$field = static function ($payload, string $name): string {
    return (is_array($payload) && isset($payload[$name]) && is_string($payload[$name]))
        ? $payload[$name]
        : '';
};
$nonce = $field($payload, 'nonce');
$device = $field($payload, 'device');
$pinHmac = $field($payload, 'pin_hmac');

if (!preg_match('/^[0-9a-f]{32}$/', $nonce)
    || !preg_match('/^[A-Za-z0-9-]{8,64}$/', $device)
    || !preg_match('/^[0-9a-f]{64}$/', $pinHmac)) {
    respond(400, ['error' => 'bad_request']);
}

// Pembatasan PIN salah per perangkat dan per IP. Per perangkat supaya satu siswa
// yang menebak-nebak PIN tidak mengunci HP lain yang keluar lewat IP publik sama.
$state = exbro_failures_open($window);
if ($state === null) {
    respond(503, ['error' => 'storage_unavailable']);
}

$deviceKey = 'd:' . $device;
$ipKey = 'i:' . (string) ($_SERVER['REMOTE_ADDR'] ?? 'unknown');

if (exbro_failures_count($state, $deviceKey) >= $maxPerDevice
    || exbro_failures_count($state, $ipKey) >= $maxPerIp) {
    exbro_failures_close($state);
    respond(429, ['error' => 'too_many_attempts']);
}

$valid = false;
foreach ($acceptedPins as $candidate) {
    if (hash_equals(hash_hmac('sha256', $nonce . '|' . $candidate, $apiKey), $pinHmac)) {
        $valid = true;
    }
}

if ($valid) {
    exbro_failures_clear($state, $deviceKey);
} else {
    exbro_failures_add($state, $deviceKey);
    exbro_failures_add($state, $ipKey);
}
exbro_failures_close($state);

$result = $valid ? 'valid' : 'invalid';
respond(200, [
    'result' => $result,
    'sig' => hash_hmac('sha256', $nonce . '|' . $result, $apiKey),
]);
