package com.kafein.kscd.upload;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Envoie UN fichier (multipart/form-data) en tâche de fond.
 *
 * - Démarré par WorkManager quand le réseau est disponible (contrainte).
 * - Passe au premier plan avec une notification de progression (comme la sauvegarde WhatsApp),
 *   ce qui lui permet de dépasser la limite de 10 minutes et de survivre à la mise en veille.
 * - Result.retry() => WorkManager relance automatiquement avec un délai exponentiel
 *   (30 s, 1 min, 2 min, 4 min ... plafonné à 5 h), y compris après un redémarrage du téléphone.
 */
public class UploadWorker extends Worker {

    static final String TAG = "KscdUpload";
    static final String KEY_ID = "id";
    static final String WORK_TAG = "kscd-upload";
    static final String CHANNEL_ID = "kscd_upload";
    static final String DEFAULT_TITLE = "Envoi de la vidéo";

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 180_000;
    private static final long PROGRESS_INTERVAL_MS = 750;
    private static final int MAX_RESPONSE_CHARS = 4096;
    private static final long BACKOFF_SECONDS = 30;

    private enum Kind { SUCCESS, RETRY, FAIL, STOPPED }

    private static final class Outcome {
        final Kind kind;
        final int httpCode;
        final String body;
        final String error;

        Outcome(Kind kind, int httpCode, String body, String error) {
            this.kind = kind;
            this.httpCode = httpCode;
            this.body = body;
            this.error = error;
        }
    }

    private String runId;
    private int notificationId;
    private long lastProgressAt;

    public UploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    // =================================================================== planification

    static String workName(String id) {
        return "kscd-upload-" + id;
    }

    /**
     * (Re)programme l'envoi. REPLACE : une seule tâche par envoi.
     * startAt : timestamp (ms) avant lequel l'envoi ne démarre pas, 0 = dès que possible.
     */
    static void schedule(Context ctx, String id, boolean wifiOnly, long startAt) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(wifiOnly ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest.Builder builder = new OneTimeWorkRequest.Builder(UploadWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .setInputData(new Data.Builder().putString(KEY_ID, id).build())
                .addTag(WORK_TAG);

        long delay = startAt - System.currentTimeMillis();
        if (delay > 0) builder.setInitialDelay(delay, TimeUnit.MILLISECONDS);

        WorkManager.getInstance(ctx).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, builder.build());
    }

    static void cancel(Context ctx, String id) {
        WorkManager.getInstance(ctx).cancelUniqueWork(workName(id));
    }

    // =================================================================== exécution

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        String id = getInputData().getString(KEY_ID);
        if (id == null) return Result.failure();

        JSONObject rec = FileQueue.get(ctx, id);
        if (rec == null || FileQueue.isFinished(rec.optString("status"))) {
            return Result.success(); // annulé ou déjà envoyé : rien à faire
        }

        runId = getId().toString();
        notificationId = progressNotificationId(id);
        int attempt = rec.optInt("attempts", 0) + 1;
        int maxAttempts = Math.max(1, rec.optInt("maxAttempts", 20));

        // Prend la main (le runId empêche un ancien Worker d'écraser l'état)
        if (FileQueue.startRun(ctx, id,
                "status", FileQueue.RUNNING,
                "runId", runId,
                "attempts", attempt,
                "lastError", null) == null) {
            return Result.success();
        }

        ensureChannel(ctx);
        long total = fileLength(ctx, rec.optString("localPath"));
        startForeground(ctx, rec, 0, total);

        Outcome outcome;
        try {
            outcome = upload(ctx, id, rec, total);
        } catch (FileNotFoundException | SecurityException e) {
            outcome = new Outcome(Kind.FAIL, 0, null, "Fichier introuvable ou illisible : " + e.getMessage());
        } catch (IOException e) {
            outcome = isStopped()
                    ? new Outcome(Kind.STOPPED, 0, null, null)
                    : new Outcome(Kind.RETRY, 0, null, "Erreur réseau : " + e);
        } catch (Exception e) {
            Log.e(TAG, "Erreur inattendue pour " + id, e);
            outcome = new Outcome(Kind.FAIL, 0, null, "Erreur inattendue : " + e);
        } finally {
            cancelNotification(ctx, notificationId);
        }

        return finish(ctx, id, rec, outcome, attempt, maxAttempts);
    }

    private Result finish(Context ctx, String id, JSONObject rec, Outcome o, int attempt, int maxAttempts) {
        Integer httpCode = o.httpCode > 0 ? o.httpCode : null;

        switch (o.kind) {
            case SUCCESS:
                if (FileQueue.updateForRun(ctx, id, runId,
                        "status", FileQueue.SUCCEEDED,
                        "progress", 100,
                        "httpCode", httpCode,
                        "response", o.body,
                        "lastError", null,
                        "runId", null) != null) {
                    if (rec.optBoolean("ownsFile")) deleteQuietly(rec.optString("localPath"));
                    String group = rec.optString("group", "");
                    if (group.isEmpty()) {
                        notifyFinished(ctx, id, rec, true, null);
                    } else {
                        notifyGroupIfDone(ctx, group, rec);
                    }
                }
                Log.i(TAG, "Envoi réussi " + id);
                return Result.success();

            case STOPPED:
                // Arrêt par le système (Doze, quota, réseau perdu...) ou annulation :
                // cette interruption ne compte pas comme une tentative.
                FileQueue.updateForRun(ctx, id, runId,
                        "status", FileQueue.QUEUED,
                        "attempts", attempt - 1,
                        "lastError", "Interrompu, reprise automatique");
                return Result.retry();

            case RETRY:
                if (attempt < maxAttempts) {
                    FileQueue.updateForRun(ctx, id, runId,
                            "status", FileQueue.QUEUED,
                            "httpCode", httpCode,
                            "response", o.body,
                            "lastError", o.error);
                    Log.w(TAG, "Envoi " + id + " : tentative " + attempt + "/" + maxAttempts + " échouée, relance. " + o.error);
                    return Result.retry();
                }
                return fail(ctx, id, rec, httpCode, o.body,
                        o.error + " (abandon après " + attempt + " tentatives)");

            case FAIL:
            default:
                return fail(ctx, id, rec, httpCode, o.body, o.error);
        }
    }

    private Result fail(Context ctx, String id, JSONObject rec, Integer httpCode, String body, String error) {
        // Le fichier local est conservé pour permettre une relance manuelle (KscdUpload.retry)
        if (FileQueue.updateForRun(ctx, id, runId,
                "status", FileQueue.FAILED,
                "httpCode", httpCode,
                "response", body,
                "lastError", error,
                "runId", null) != null) {
            notifyFinished(ctx, id, rec, false, error);
        }
        Log.e(TAG, "Envoi " + id + " abandonné : " + error);
        return Result.failure();
    }

    // =================================================================== HTTP

    private Outcome upload(Context ctx, String id, JSONObject rec, long fileSize) throws Exception {
        String path = rec.getString("localPath");
        String boundary = "KscdBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] head = multipartHead(boundary, rec).getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(rec.getString("url")).openConnection();
        try {
            conn.setRequestMethod(rec.optString("httpMethod", "POST").toUpperCase(Locale.ROOT));
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("Accept", "application/json, */*");
            // Identifiant stable : permet au serveur d'ignorer un doublon après une relance
            conn.setRequestProperty("X-Upload-Id", id);
            applyHeaders(conn, rec.optJSONObject("headers"));
            applyHeaders(conn, FileQueue.getGlobalHeaders(ctx)); // prioritaires (jeton à jour)

            // Streaming : la vidéo n'est jamais chargée entièrement en mémoire
            if (fileSize >= 0) {
                conn.setFixedLengthStreamingMode(head.length + fileSize + tail.length);
            } else {
                conn.setChunkedStreamingMode(BUFFER_SIZE);
            }

            long sent = 0;
            try (InputStream in = openInput(ctx, path);
                 OutputStream out = conn.getOutputStream()) {
                out.write(head);
                byte[] buffer = new byte[BUFFER_SIZE];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    if (isStopped()) {
                        conn.disconnect();
                        return new Outcome(Kind.STOPPED, 0, null, null);
                    }
                    out.write(buffer, 0, n);
                    sent += n;
                    reportProgress(ctx, id, rec, sent, fileSize, false);
                }
                out.write(tail);
                out.flush();
            }
            reportProgress(ctx, id, rec, sent, fileSize, true);

            int code = conn.getResponseCode();
            String body = readBody(conn, code);

            if (code >= 200 && code < 300) {
                return new Outcome(Kind.SUCCESS, code, body, null);
            }
            if (code == 401) {
                // Jeton expiré : on réessaie, l'app enverra un nouveau jeton via setGlobalHeaders
                return new Outcome(Kind.RETRY, code, body, "Authentification refusée (401), en attente d'un nouveau jeton");
            }
            if (code == 408 || code == 425 || code == 429 || code >= 500) {
                return new Outcome(Kind.RETRY, code, body, "Erreur serveur temporaire (" + code + ")");
            }
            // 400, 403, 404, 413... : relancer ne changera rien
            return new Outcome(Kind.FAIL, code, body, "Refusé par le serveur (" + code + ")");
        } finally {
            conn.disconnect();
        }
    }

    private static String multipartHead(String boundary, JSONObject rec) {
        StringBuilder sb = new StringBuilder();
        JSONObject params = rec.optJSONObject("params");
        if (params != null) {
            Iterator<String> keys = params.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (params.isNull(key)) continue;
                sb.append("--").append(boundary).append("\r\n")
                  .append("Content-Disposition: form-data; name=\"").append(escape(key)).append("\"\r\n\r\n")
                  .append(params.optString(key)).append("\r\n");
            }
        }
        sb.append("--").append(boundary).append("\r\n")
          .append("Content-Disposition: form-data; name=\"").append(escape(rec.optString("fileKey", "file")))
          .append("\"; filename=\"").append(escape(rec.optString("fileName", "video.mp4"))).append("\"\r\n")
          .append("Content-Type: ").append(rec.optString("mimeType", "video/mp4")).append("\r\n\r\n");
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\r", "").replace("\n", "").replace("\"", "%22");
    }

    private static void applyHeaders(HttpURLConnection conn, JSONObject headers) {
        if (headers == null) return;
        Iterator<String> keys = headers.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!headers.isNull(key)) conn.setRequestProperty(key, headers.optString(key));
        }
    }

    private static String readBody(HttpURLConnection conn, int code) {
        InputStream stream = null;
        try {
            stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) return null;
            StringBuilder sb = new StringBuilder();
            Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
            char[] buf = new char[1024];
            int n;
            while ((n = reader.read(buf)) != -1 && sb.length() < MAX_RESPONSE_CHARS) {
                sb.append(buf, 0, Math.min(n, MAX_RESPONSE_CHARS - sb.length()));
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { }
            }
        }
    }

    // =================================================================== progression

    private void reportProgress(Context ctx, String id, JSONObject rec, long sent, long total, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastProgressAt < PROGRESS_INTERVAL_MS) return;
        lastProgressAt = now;

        // 99 % max tant que le serveur n'a pas répondu
        int percent = total > 0 ? (int) Math.min(99, sent * 100 / total) : -1;
        FileQueue.updateForRun(ctx, id, runId,
                "progress", percent < 0 ? null : percent,
                "bytesSent", sent,
                "totalBytes", total < 0 ? null : total);
        notify(ctx, notificationId, buildProgressNotification(ctx, rec, percent, sent, total));
    }

    // =================================================================== notifications

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Envoi des vidéos", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progression des envois de vidéos en arrière-plan");
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    /** Passe le Worker au premier plan (notification permanente). */
    private void startForeground(Context ctx, JSONObject rec, int percent, long total) {
        Notification notification = buildProgressNotification(ctx, rec, total > 0 ? percent : -1, 0, total);
        try {
            ForegroundInfo info = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? new ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    : new ForegroundInfo(notificationId, notification);
            setForegroundAsync(info).get();
        } catch (Exception e) {
            // Android 12+ refuse le premier plan si la tâche démarre alors que l'app est en arrière-plan
            // (ex. relance programmée). L'envoi continue quand même, limité à ~10 min par tentative ;
            // s'il est interrompu, il reprend à la tentative suivante.
            Log.w(TAG, "Premier plan refusé, envoi en arrière-plan simple : " + e);
            notify(ctx, notificationId, notification);
        }
    }

    private static Notification buildProgressNotification(Context ctx, JSONObject rec, int percent, long sent, long total) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(rec.optString("title", DEFAULT_TITLE))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setContentIntent(launchIntent(ctx));
        if (percent >= 0) {
            b.setProgress(100, percent, false)
             .setContentText(percent + " % · " + formatMb(sent) + " / " + formatMb(total));
        } else {
            b.setProgress(0, 0, true)
             .setContentText(sent > 0 ? formatMb(sent) + " envoyés" : "Préparation…");
        }
        return b.build();
    }

    private static void notifyFinished(Context ctx, String id, JSONObject rec, boolean ok, String error) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(ok ? android.R.drawable.stat_sys_upload_done : android.R.drawable.stat_notify_error)
                .setContentTitle(ok ? "Vidéo envoyée" : "Échec de l'envoi de la vidéo")
                .setContentText(ok ? rec.optString("fileName") : error)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(launchIntent(ctx));
        if (ok) b.setTimeoutAfter(8000);
        else b.setStyle(new NotificationCompat.BigTextStyle().bigText(error));
        notify(ctx, finishedNotificationId(id), b.build());
    }

    /**
     * Envoi d'un groupe réussi : une seule notification quand tous les envois du groupe ont réussi
     * (pas de notification par envoi). Un échec dans le groupe a déjà sa propre notification.
     */
    private static void notifyGroupIfDone(Context ctx, String group, JSONObject rec) {
        List<JSONObject> uploads = FileQueue.listGroup(ctx, group);
        for (JSONObject upload : uploads) {
            if (!FileQueue.SUCCEEDED.equals(upload.optString("status"))) return;
        }

        int count = uploads.size();
        String title = rec.optString("groupTitle", "");
        NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title.isEmpty() ? "Envois terminés" : title)
                .setContentText(count + (count > 1 ? " vidéos envoyées" : " vidéo envoyée"))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(launchIntent(ctx));
        notify(ctx, groupNotificationId(group), b.build());
        Log.i(TAG, "Groupe " + group + " terminé : " + count + " envoi(s)");
    }

    static int groupNotificationId(String group) {
        return (group.hashCode() & 0x0fffffff) | 0x30000000;
    }

    static int progressNotificationId(String id) {
        return (id.hashCode() & 0x3fffffff) | 0x10000000;
    }

    static int finishedNotificationId(String id) {
        return (id.hashCode() & 0x0fffffff) | 0x20000000;
    }

    private static void notify(Context ctx, int notifId, Notification n) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            // Sans la permission POST_NOTIFICATIONS (Android 13+), la notification est ignorée
            // mais l'envoi continue.
            if (nm != null) nm.notify(notifId, n);
        } catch (Exception e) {
            Log.w(TAG, "Notification impossible : " + e);
        }
    }

    static void cancelNotification(Context ctx, int notifId) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notifId);
        } catch (Exception ignored) {
        }
    }

    private static PendingIntent launchIntent(Context ctx) {
        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (intent == null) return null;
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static String formatMb(long bytes) {
        if (bytes < 0) return "?";
        return String.format(Locale.FRANCE, "%.1f Mo", bytes / (1024.0 * 1024.0));
    }

    // =================================================================== fichiers

    /** Taille du fichier, -1 si inconnue. Accepte chemin absolu, file:// et content://. */
    static long fileLength(Context ctx, String path) {
        if (path == null || path.isEmpty()) return -1;
        if (path.startsWith("content://")) {
            try (AssetFileDescriptor afd = ctx.getContentResolver().openAssetFileDescriptor(Uri.parse(path), "r")) {
                return afd == null ? -1 : afd.getLength();
            } catch (Exception e) {
                return -1;
            }
        }
        File f = toFile(path);
        return f.isFile() ? f.length() : -1;
    }

    static InputStream openInput(Context ctx, String path) throws IOException {
        if (path.startsWith("content://")) {
            InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(path));
            if (in == null) throw new FileNotFoundException(path);
            return in;
        }
        return new FileInputStream(toFile(path));
    }

    static File toFile(String path) {
        if (path.startsWith("file://")) {
            String p = Uri.parse(path).getPath();
            return new File(p == null ? path.substring(7) : p);
        }
        return new File(path);
    }

    static void deleteQuietly(String path) {
        if (path == null || path.isEmpty() || path.startsWith("content://")) return;
        try {
            File f = toFile(path);
            if (f.isFile() && !f.delete()) Log.w(TAG, "Suppression impossible : " + f);
        } catch (Exception ignored) {
        }
    }
}
