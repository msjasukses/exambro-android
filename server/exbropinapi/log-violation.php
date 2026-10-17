<?php
/**
 * ExBro / CBT Cyber Edu - penerima catatan pelanggaran dari aplikasi.
 *
 * Protokol:
 *   Request  POST application/json
 *            {"nonce":"<32 hex>", "device":"<id>", "app":"<versi>", "android":"<versi>",
 *             "events_json":"[{\"at\":...,\"type\":...,\"detail\":...,\"page\":...}]",
 *             "sig":"HMAC-SHA256(api_key, nonce + '|' + events_json)"}
 *
 *            events dikirim sebagai teks JSON dan ditandatangani apa adanya, agar
 *            tanda tangan tidak bergantung pada cara PHP maupun Android menyusun
 *            ulang JSON.
 *   Response 200 {"saved": <jumlah>, "sig": HMAC(api_key, nonce + "|ok")}
 *            400 payload tidak valid, 401 tanda tangan salah, 405 bukan POST,
 *            429 terlalu banyak kiriman, 503 belum dikonfigurasi.
 *
 * Tanda tangan memakai API key yang sama dengan verify-pin.php, sehingga catatan
 * palsu dari luar aplikasi ditolak.
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

$apiKey = (string) (exbro_config()['api_key'] ?? '');
if (exbro_is_placeholder($apiKey)) {
    respond(503, ['error' => 'not_configured']);
}

$payload = json_decode((string) file_get_contents('php://input'), true);
$str = static function ($payload, string $name, int $max): string {
    $v = (is_array($payload) && isset($payload[$name]) && is_string($payload[$name])) ? $payload[$name] : '';
    return mb_substr($v, 0, $max);
};

$nonce = $str($payload, 'nonce', 32);
$device = $str($payload, 'device', 64);
$app = $str($payload, 'app', 48);
$android = $str($payload, 'android', 48);
$sig = $str($payload, 'sig', 64);
$eventsJson = (is_array($payload) && isset($payload['events_json']) && is_string($payload['events_json']))
    ? $payload['events_json'] : '';

if (!preg_match('/^[0-9a-f]{32}$/', $nonce)
    || !preg_match('/^[A-Za-z0-9-]{8,64}$/', $device)
    || !preg_match('/^[0-9a-f]{64}$/', $sig)
    || $eventsJson === ''
    || strlen($eventsJson) > 20000) {
    respond(400, ['error' => 'bad_request']);
}

// Tanda tangan dihitung atas teks JSON apa adanya, bukan hasil encode ulang.
$expected = hash_hmac('sha256', $nonce . '|' . $eventsJson, $apiKey);
if (!hash_equals($expected, $sig)) {
    respond(401, ['error' => 'bad_signature']);
}

$events = json_decode($eventsJson, true);
if (!is_array($events) || $events === [] || count($events) > EXBRO_VIOLATION_MAX_BATCH) {
    respond(400, ['error' => 'bad_events']);
}

// Pembatasan agar satu HP tidak membanjiri catatan.
$window = (int) (exbro_config()['window_seconds'] ?? 300);
$state = exbro_failures_open($window);
if ($state !== null) {
    $key = 'v:' . $device;
    if (exbro_failures_count($state, $key) >= 200) {
        exbro_failures_close($state);
        respond(429, ['error' => 'too_many_events']);
    }
    foreach ($events as $ignored) {
        exbro_failures_add($state, $key);
    }
    exbro_failures_close($state);
}

$now = time();
$ip = (string) ($_SERVER['REMOTE_ADDR'] ?? '');
$rows = [];
foreach ($events as $event) {
    if (!is_array($event)) {
        continue;
    }
    $at = (int) ($event['at'] ?? 0);
    // Waktu dari HP dipakai apa adanya, tetapi dibatasi agar tidak mustahil.
    if ($at < $now - 86400 || $at > $now + 3600) {
        $at = $now;
    }
    $rows[] = [
        'at_time' => $at,
        'received_at' => $now,
        'device' => $device,
        'type' => mb_substr((string) ($event['type'] ?? 'lain'), 0, 48),
        'detail' => mb_substr((string) ($event['detail'] ?? ''), 0, 255),
        'page' => mb_substr((string) ($event['page'] ?? ''), 0, 255),
        'app_version' => $app,
        'android' => $android,
        'ip' => $ip,
    ];
}

$saved = exbro_violation_save($rows);
respond(200, [
    'saved' => $saved,
    'sig' => hash_hmac('sha256', $nonce . '|ok', $apiKey),
]);
