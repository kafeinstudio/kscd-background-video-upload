/**
 * Exemple d'intégration Ionic v1 / AngularJS.
 *
 * 1. Copier ce fichier dans www/js/ et l'inclure dans index.html
 * 2. Ajouter 'kscd.videoUpload' aux dépendances du module principal
 * 3. Appeler VideoUpload.setAuthToken(token) après la connexion / le rafraîchissement du jeton
 */
angular.module('kscd.videoUpload', ['ionic'])

    .constant('VIDEO_UPLOAD_URL', 'https://api.example.com/videos') // À ADAPTER

    .factory('VideoUpload', function ($q, $rootScope, $ionicPlatform, VIDEO_UPLOAD_URL) {

        var state = { uploads: [] };

        function plugin() {
            return window.KscdUpload;
        }

        function ensurePlugin() {
            return plugin() ? $q.resolve(plugin()) : $q.reject(new Error('Plugin KscdUpload indisponible (navigateur ?)'));
        }

        // Abonnement : liste complète des envois à chaque changement (progression, succès, échec)
        $ionicPlatform.ready(function () {
            if (!plugin()) return;
            plugin().subscribe(function (uploads) {
                $rootScope.$applyAsync(function () {
                    state.uploads = uploads;
                    $rootScope.$broadcast('videoUpload:changed', uploads);
                });
            });
            plugin().requestNotificationPermission();
        });

        return {
            /** Liste à jour, utilisable directement dans les vues : VideoUpload.state.uploads */
            state: state,

            /** À appeler au login et à chaque nouveau jeton : les envois en attente l'utiliseront. */
            setAuthToken: function (token) {
                return ensurePlugin().then(function (p) {
                    return $q.when(p.setGlobalHeaders(token ? { Authorization: 'Bearer ' + token } : {}));
                });
            },

            /**
             * Met une vidéo en file d'envoi. Retourne immédiatement : l'envoi continue
             * même si l'utilisateur ferme l'app.
             *
             * fileUri : chemin renvoyé par media-capture, camera ou kscd-media-picker
             * objectId : rattachement côté serveur
             */
            send: function (fileUri, objectId, options) {
                options = options || {};
                return ensurePlugin().then(function (p) {
                    return $q.when(p.enqueue({
                        // id stable : un double clic ne crée pas deux envois
                        id: options.id || ('video-' + objectId + '-' + Date.now()),
                        url: VIDEO_UPLOAD_URL,
                        filePath: fileUri,
                        fileKey: 'file',
                        mimeType: options.mimeType || 'video/mp4',
                        params: angular.extend({ objectId: String(objectId) }, options.params || {}),
                        title: options.title || 'Envoi de la vidéo',
                        wifiOnly: !!options.wifiOnly,
                        maxAttempts: options.maxAttempts || 20
                    }));
                });
            },

            retry: function (id) {
                return ensurePlugin().then(function (p) { return $q.when(p.retry(id)); });
            },

            cancel: function (id) {
                return ensurePlugin().then(function (p) { return $q.when(p.cancel(id)); });
            },

            clearFinished: function () {
                return ensurePlugin().then(function (p) { return $q.when(p.clearFinished()); });
            },

            pendingCount: function () {
                return state.uploads.filter(function (u) {
                    return u.status === 'QUEUED' || u.status === 'RUNNING';
                }).length;
            }
        };
    });

/*
 * ------------------------------------------------------------------ Exemple dans un contrôleur
 *
 * .controller('InterventionCtrl', function ($scope, VideoUpload) {
 *
 *     $scope.uploads = VideoUpload.state;   // dans la vue : ng-repeat="u in uploads.uploads"
 *
 *     $scope.filmer = function (item) {
 *         navigator.device.capture.captureVideo(function (files) {
 *             VideoUpload.send(files[0].fullPath, item.id)
 *                 .then(function (res) {
 *                     if (res.alreadyQueued) console.log('Déjà en file', res.id);
 *                 })
 *                 .catch(function (err) { alert('Impossible de préparer l\'envoi : ' + err); });
 *         }, function (err) {
 *             console.warn('Capture annulée', err);
 *         }, { limit: 1 });
 *     };
 *
 *     $scope.relancer = function (u) { VideoUpload.retry(u.id); };
 *     $scope.annuler  = function (u) { VideoUpload.cancel(u.id); };
 * });
 *
 * Vue :
 *   <div ng-repeat="u in uploads.uploads track by u.id">
 *     {{u.fileName}} : {{u.status}} {{u.progress}} %
 *     <span ng-if="u.lastError">({{u.lastError}})</span>
 *     <button ng-if="u.status === 'FAILED'" ng-click="relancer(u)">Relancer</button>
 *   </div>
 */
