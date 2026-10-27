<?php
/**
 * Serveur de test pour kscd-background-video-upload (routeur du serveur PHP intégré).
 *
 * Lancement (depuis la racine du projet) :
 *   php -d upload_max_filesize=2G -d post_max_size=2G -d max_execution_time=0 \
 *       -S 0.0.0.0:8090 plugins-local/kscd-background-video-upload/test/server.php
 *   adb reverse tcp:8090 tcp:8090
 *
 * Comportement piloté par test/mode.txt (modifiable à chaud, relu à chaque requête) :
 *   ok    → 200, fichier enregistré dans test/uploads/
 *   500   → erreur serveur (le Worker doit relancer avec backoff)
 *   401   → 401 sauf si "Authorization: Bearer ok" (tester setGlobalHeaders)
 *   slow  → attend 90 s avant de répondre (le Worker reste RUNNING : tester la fermeture de l'app)
 *   400   → erreur définitive (le Worker doit abandonner : FAILED)
 *
 * Le serveur intégré de PHP traite une requête à la fois : pendant "slow", changer le mode
 * en éditant mode.txt plutôt que via /mode.
 */

$dir      = __DIR__;
$uploads  = $dir . '/uploads';
$modeFile = $dir . '/mode.txt';

function logLine($msg)
{
    file_put_contents('php://stderr', '[' . date('H:i:s') . '] ' . $msg . PHP_EOL);
}

function respond($code, array $body)
{
    http_response_code($code);
    header('Content-Type: application/json');
    echo json_encode($body);
    logLine('→ ' . $code . ' ' . json_encode($body));
}

$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$mode = is_file($modeFile) ? trim(file_get_contents($modeFile)) : 'ok';

// GET /mode?set=500 : changer de mode sans éditer le fichier.
if ($path === '/mode') {
    if (isset($_GET['set'])) {
        file_put_contents($modeFile, $_GET['set']);
        $mode = $_GET['set'];
    }
    respond(200, ['mode' => $mode]);
    return true;
}

if ($path !== '/upload') {
    respond(404, ['error' => 'not_found', 'hint' => 'POST /upload ou GET /mode?set=ok|500|401|slow|400']);
    return true;
}

$uploadId = isset($_SERVER['HTTP_X_UPLOAD_ID']) ? $_SERVER['HTTP_X_UPLOAD_ID'] : '-';
$auth     = isset($_SERVER['HTTP_AUTHORIZATION']) ? $_SERVER['HTTP_AUTHORIZATION'] : '-';
$file     = isset($_FILES['file']) ? $_FILES['file'] : null;

logLine(sprintf(
    'POST /upload mode=%s X-Upload-Id=%s Authorization=%s params=%s file=%s',
    $mode,
    $uploadId,
    $auth,
    json_encode($_POST),
    $file ? sprintf('%s (%d octets, erreur=%d)', $file['name'], $file['size'], $file['error']) : 'aucun'
));

switch ($mode) {
    case '500':
        respond(500, ['error' => 'simulated_server_error']);
        return true;

    case '400':
        respond(400, ['error' => 'simulated_bad_request']);
        return true;

    case '401':
        if ($auth !== 'Bearer ok') {
            respond(401, ['error' => 'unauthenticated']);
            return true;
        }
        break;

    case 'slow':
        logLine('slow : attente de 90 s avant de répondre…');
        sleep(90);
        break;
}

if (!$file || $file['error'] !== UPLOAD_ERR_OK) {
    respond(422, ['error' => 'no_file', 'upload_error' => $file ? $file['error'] : null]);
    return true;
}

if (!is_dir($uploads)) {
    mkdir($uploads, 0755, true);
}

$name = preg_replace('/[^A-Za-z0-9._-]/', '_', $uploadId . '_' . $file['name']);
move_uploaded_file($file['tmp_name'], $uploads . '/' . $name);

respond(200, ['status' => 'ok', 'id' => $uploadId, 'size' => $file['size'], 'saved' => $name]);
return true;
