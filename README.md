# kscd-background-video-upload

Envoi de vidéos en tâche de fond (Android, WorkManager) :

- file d'envoi persistante (survit à la fermeture de l'app et au redémarrage du téléphone)
- relance automatique : attente du réseau, délai exponentiel (30 s → 1 min → 2 min… plafonné à 5 h)
- notification permanente avec progression pendant l'envoi (comme la sauvegarde WhatsApp)
- envoi en streaming (la vidéo n'est jamais chargée en mémoire)

## Installation

```bash
ionic cordova plugin add https://github.com/kafeinstudio/kscd-background-video-upload.git
```

Le `package.json` de l'app recevra une entrée `github:kafeinstudio/kscd-background-video-upload`,
à figer sur un commit (`…#<sha>`) comme les autres plugins kafeinstudio.

Version de WorkManager : variable `WORK_VERSION` (défaut `2.10.0`).

Exemple d'intégration AngularJS : copier `example/video-upload.service.js` dans `www/js/`, l'inclure dans `index.html`
et ajouter `'kscd.videoUpload'` aux dépendances du module Angular.

## API JavaScript (`window.KscdUpload`, toutes les méthodes renvoient une Promise)

| Méthode | Rôle |
|---|---|
| `enqueue(options)` | Met un fichier en file. Voir les options dans `www/kscd-upload.js` |
| `setGlobalHeaders({Authorization: 'Bearer …'})` | Jeton utilisé par **tous** les envois, y compris ceux déjà en file. Relance immédiatement les envois bloqués en 401 |
| `list()` | État de tous les envois |
| `subscribe(cb)` | `cb(uploads)` appelé à chaque changement |
| `retry(id, startAt?)` | Relance (état `FAILED` ou `QUEUED`) : tout de suite, ou à `startAt` (timestamp ms) |
| `cancel(id)` | Annule et supprime la copie locale |
| `clearFinished()` | Nettoie l'historique des envois terminés |
| `requestNotificationPermission()` | Android 13+ : permission d'afficher la notification |

États : `QUEUED` (en attente / relance programmée), `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`.

Options utiles d'`enqueue` :

- `startAt` : timestamp (ms) avant lequel l'envoi ne démarre pas (envoi programmé, conservé après
  redémarrage du téléphone ; l'heure de départ réelle peut être retardée de quelques minutes par Android).
- `group` / `groupTitle` : une seule notification « envois terminés » quand tous les envois du groupe
  ont réussi, au lieu d'une notification par envoi.

## Règles de relance

| Situation | Comportement |
|---|---|
| Pas de réseau | Attente, démarrage au retour du réseau |
| Coupure pendant l'envoi, timeout, erreur DNS/SSL | Relance |
| HTTP 408, 425, 429, 5xx | Relance |
| HTTP 401 | Relance ; relance immédiate dès que `setGlobalHeaders` fournit un nouveau jeton |
| HTTP 400, 403, 404, 413… | Échec définitif (`FAILED`), relance manuelle possible |
| Fichier supprimé ou illisible | Échec définitif |
| App tuée, Doze, redémarrage | Reprise automatique (ne compte pas comme une tentative) |
| `maxAttempts` atteint (20 par défaut) | Échec définitif |

## Contrat côté serveur

Requête `POST` `multipart/form-data` :

- champ fichier : `file` (option `fileKey`)
- champs additionnels : ceux de `params` (ex. `objectId`)
- en-tête **`X-Upload-Id`** : identifiant unique et stable de l'envoi. **Le serveur doit l'utiliser
  pour ignorer un doublon** : si la connexion coupe juste après la réception, la relance renverra
  la même vidéo.
- répondre `2xx` en cas de succès. Le corps de la réponse (4 Ko max) est disponible dans
  `upload.response` côté JS.
- vérifier la taille maximale acceptée (nginx `client_max_body_size`, PHP `upload_max_filesize` /
  `post_max_size`, timeouts) : un `413` est considéré comme définitif.

Limite connue : sans protocole d'upload reprenable (envoi par morceaux, tus), chaque relance
repart de 0 %.

## Play Console

L'usage du service de premier plan `dataSync` doit être déclaré dans
**Play Console → Contenu de l'application → Services de premier plan** :
« Envoi, à l'initiative de l'utilisateur, des vidéos vers le serveur ; une
notification affiche la progression. » Une courte vidéo de démonstration peut être demandée.

## R8 / minification

WorkManager fournit ses propres règles ProGuard. Ajouter par sécurité dans `proguard-rules.pro` :

```proguard
-keep class com.kafein.kscd.upload.** { *; }
```

## À savoir

- **Android 12+** : si une relance démarre alors que l'app est en arrière-plan, Android peut refuser
  le premier plan. L'envoi se fait alors sans notification permanente, limité à ~10 min par
  tentative ; s'il est coupé, il reprend à la tentative suivante (en général dès que l'app revient
  au premier plan).
- **Android 15** : le type `dataSync` est limité à ~6 h par 24 h ; au-delà la tâche est arrêtée
  puis reprise plus tard.
- **Xiaomi, Huawei, Oppo, Samsung…** : l'optimisation de batterie peut retarder les tâches. Guider
  l'utilisateur via `cordova.plugins.diagnostic` si besoin.
- Par défaut la vidéo est **copiée** dans le stockage privé de l'app (`copyToAppStorage: true`),
  puis supprimée après envoi réussi. Prévoir l'espace disque nécessaire.

## Tests à faire en release (APK signée, appareil réel)

1. Envoi normal, app ouverte → notification, progression, `SUCCEEDED`
2. Lancer l'envoi puis fermer l'app (balayage) → l'envoi continue
3. Mode avion pendant l'envoi → `QUEUED`, reprise seule au retour du réseau
4. Redémarrer le téléphone avec un envoi en attente → reprise
5. Serveur qui renvoie 500 → relances successives visibles dans `attempts`
6. Jeton expiré (401) → nouveau login → relance immédiate
7. Accélérer les tests : `adb shell cmd jobscheduler run -f <package> <jobId>` ou
   `adb shell dumpsys jobscheduler | grep <package>` pour voir les tâches programmées
