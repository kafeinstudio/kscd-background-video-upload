/*
 * Aides de test pour kscd-background-video-upload.
 * À coller dans la console chrome://inspect (APK debug). Rien de ce fichier n'est inclus dans l'app.
 *
 * Côté PC :
 *   php -d upload_max_filesize=2G -d post_max_size=2G -d max_execution_time=0 \
 *       -S 0.0.0.0:8090 plugins-local/kscd-background-video-upload/test/server.php
 *   adb reverse tcp:8090 tcp:8090
 *
 * Suivre WorkManager :
 *   adb logcat -s KscdUpload WM-WorkerWrapper WM-Processor WM-SystemJobScheduler WM-DiagnosticsWrkr
 *   adb shell am broadcast -a androidx.work.diagnostics.REQUEST_DIAGNOSTICS -p <package.de.l.app>
 *   adb shell dumpsys jobscheduler | grep -A20 <package.de.l.app>
 *   adb shell dumpsys activity services androidx.work.impl.foreground.SystemForegroundService
 *
 * Simuler :
 *   adb shell svc wifi disable && adb shell svc data disable     # coupure réseau
 *   adb shell svc wifi enable                                     # retour réseau
 *   adb shell am force-stop <package.de.l.app>                    # arrêt forcé (annule les tâches, voulu par Android)
 *   adb reboot                                                    # redémarrage
 */

var TEST_URL = 'http://localhost:8090/upload';

// Affiche chaque changement d'état (progression, succès, échec…).
KscdUpload.subscribe(function (uploads) {
    console.table(uploads.map(function (u) {
        return {id: u.id, status: u.status, progress: u.progress, attempts: u.attempts, httpCode: u.httpCode, lastError: u.lastError};
    }));
});

// Autorisation d'afficher la notification de progression (Android 13+).
KscdUpload.requestNotificationPermission().then(function (granted) { console.log('notifications :', granted); });

/** Met un fichier en file d'envoi vers le serveur de test. */
function testEnqueue(filePath, id) {
    return KscdUpload.enqueue({
        id      : id || ('test-' + Date.now()),
        url     : TEST_URL,
        filePath: filePath,
        params  : {object_type: 'order', object_id: 'TEST', typeVideo: 'recommendation'},
        title   : 'Test envoi vidéo'
    }).then(function (res) { console.log('enqueue :', res); return res; }, console.error);
}

/** Liste les vidéos des interventions en cache (chemins utilisables par testEnqueue). */
function findVideos() {
    var OrderService = angular.element(document.body).injector().get('OrderService');
    var paths = [];
    (OrderService.getAll() || []).forEach(function (order) {
        (order.videos || []).forEach(function (v) { paths.push(v.url); });
    });
    console.log(paths);
    return paths;
}

/** Filme une vidéo et la met directement en file. */
function filmer() {
    navigator.device.capture.captureVideo(function (files) {
        console.log('capturé :', files[0].fullPath);
        testEnqueue(files[0].fullPath);
    }, console.error, {limit: 1});
}

/** Change le comportement du serveur de test : 'ok' | '500' | '401' | 'slow' | '400'. */
function serverMode(mode) {
    return fetch('http://localhost:8090/mode?set=' + mode).then(function (r) { return r.json(); }).then(console.log);
}

// Raccourcis
function list()               { return KscdUpload.list().then(console.table); }
function retry(id)            { return KscdUpload.retry(id).then(console.log, console.error); }
function cancel(id)           { return KscdUpload.cancel(id).then(console.log, console.error); }
function clearFinished()      { return KscdUpload.clearFinished().then(console.log); }
function setToken(token)      { return KscdUpload.setGlobalHeaders({Authorization: 'Bearer ' + token}).then(console.log); }

/*
 * Scénarios
 *  1. serverMode('ok');   filmer();                       → SUCCEEDED, fichier dans test/uploads/
 *  2. serverMode('slow'); filmer(); puis balayer l'app    → le Worker continue (logcat + notification)
 *  3. serverMode('500');  filmer();                        → attempts augmente (30 s, 60 s…) ; serverMode('ok') → succès
 *     (pendant 'slow', le serveur PHP est bloqué : éditer test/mode.txt au lieu de serverMode)
 *  4. serverMode('401');  filmer(); setToken('ok');        → relance immédiate puis succès
 *  5. serverMode('400');  filmer();                        → FAILED définitif ; serverMode('ok'); retry(id) → succès
 *  6. serverMode('slow'); filmer(); couper le réseau       → QUEUED, reprise au retour du réseau
 *  7. serverMode('500');  filmer(); adb reboot ; serverMode('ok') → reprise sans ouvrir l'app
 */
