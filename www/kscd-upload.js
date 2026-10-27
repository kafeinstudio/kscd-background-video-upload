var exec = require('cordova/exec');

var SERVICE = 'KscdUpload';

function call(action, args) {
    return new Promise(function (resolve, reject) {
        exec(resolve, reject, SERVICE, action, args || []);
    });
}

module.exports = {
    /**
     * Met un fichier en file d'envoi. L'envoi continue même si l'app est fermée.
     *
     * options :
     *   url               (obligatoire) URL d'upload
     *   filePath          (obligatoire) file:///..., content://... ou chemin absolu
     *   id                identifiant unique (lettres, chiffres, "-"), UUID généré sinon
     *   fileName          nom envoyé au serveur (déduit du fichier sinon)
     *   fileKey           nom du champ multipart (défaut "file")
     *   mimeType          défaut "video/mp4"
     *   httpMethod        défaut "POST"
     *   headers           { "Nom": "valeur" } propres à cet envoi
     *   params            { "champ": "valeur" } champs multipart additionnels
     *   title             titre de la notification (défaut "Envoi de la vidéo")
     *   wifiOnly          true = n'envoie qu'en Wi-Fi / réseau non facturé (défaut false)
     *   group             identifiant de groupe : une seule notification quand tous les envois du groupe ont réussi
     *                     (au lieu d'une par envoi)
     *   groupTitle        titre de cette notification (défaut "Envois terminés")
     *   startAt           timestamp (ms) avant lequel l'envoi ne démarre pas (défaut : dès que possible)
     *   maxAttempts       nombre max de tentatives (défaut 20)
     *   copyToAppStorage  copie le fichier dans le stockage privé de l'app avant l'envoi (défaut true)
     *
     * Résout avec { id, localPath, alreadyQueued }
     */
    enqueue: function (options) {
        return call('enqueue', [options || {}]);
    },

    /**
     * Relance un envoi en échec (FAILED) ou en attente (QUEUED).
     * startAt (facultatif) : nouvelle heure de départ en ms ; absent = tout de suite.
     */
    retry: function (id, startAt) {
        return call('retry', [id, startAt || 0]);
    },

    /** Annule un envoi (en attente ou en cours) et supprime sa copie locale. */
    cancel: function (id) {
        return call('cancel', [id]);
    },

    /** Liste l'état de tous les envois connus. */
    list: function () {
        return call('list');
    },

    /**
     * En-têtes appliqués à TOUS les envois, y compris ceux déjà en file.
     * À appeler à chaque connexion / rafraîchissement de token.
     */
    setGlobalHeaders: function (headers) {
        return call('setGlobalHeaders', [headers || {}]);
    },

    /** Supprime de l'historique les envois terminés (réussis, échoués, annulés). */
    clearFinished: function () {
        return call('clearFinished');
    },

    /**
     * Reçoit la liste complète des envois à chaque changement (progression, succès, échec...).
     * onUpdate(uploads[])
     */
    subscribe: function (onUpdate, onError) {
        exec(onUpdate, onError || function () {}, SERVICE, 'subscribe', []);
    },

    unsubscribe: function () {
        return call('unsubscribe');
    },

    /**
     * Android 13+ : demande l'autorisation d'afficher la notification de progression.
     * Résout avec true/false. Sans elle, l'envoi fonctionne mais reste invisible.
     */
    requestNotificationPermission: function () {
        return call('requestNotificationPermission');
    }
};
