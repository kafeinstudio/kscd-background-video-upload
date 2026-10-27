package com.kafein.kscd.upload;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Pont JavaScript <-> file d'envoi native.
 * Le JS ne fait que déposer des envois dans la file ; tout le travail est fait par UploadWorker,
 * même quand l'app (et donc la WebView) est fermée.
 */
public class KscdUploadPlugin extends CordovaPlugin implements FileQueue.Listener {

    private static final String TAG = UploadWorker.TAG;
    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
    private static final String POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS";
    private static final int REQ_NOTIFICATIONS = 7301;
    private static final String STORAGE_DIR = "kscd-uploads";

    private volatile CallbackContext subscriber;
    private CallbackContext permissionCallback;

    private interface Task {
        void run() throws Exception;
    }

    @Override
    protected void pluginInitialize() {
        FileQueue.setListener(this);
        UploadWorker.ensureChannel(ctx());
    }

    private Context ctx() {
        return cordova.getActivity().getApplicationContext();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext cb) {
        switch (action) {
            case "enqueue": {
                JSONObject options = args.optJSONObject(0);
                runAsync(cb, () -> enqueue(options == null ? new JSONObject() : options, cb));
                return true;
            }
            case "retry":
                runAsync(cb, () -> retry(idArg(args), Math.max(0, args.optLong(1, 0)), cb));
                return true;
            case "cancel":
                runAsync(cb, () -> cancel(idArg(args), cb));
                return true;
            case "list":
                runAsync(cb, () -> cb.success(publicList()));
                return true;
            case "setGlobalHeaders": {
                JSONObject headers = args.optJSONObject(0);
                runAsync(cb, () -> setGlobalHeaders(headers == null ? new JSONObject() : headers, cb));
                return true;
            }
            case "clearFinished":
                runAsync(cb, () -> clearFinished(cb));
                return true;
            case "subscribe":
                subscriber = cb;
                runAsync(cb, this::pushUpdate);
                return true;
            case "unsubscribe":
                CallbackContext old = subscriber;
                subscriber = null;
                if (old != null) {
                    PluginResult end = new PluginResult(PluginResult.Status.NO_RESULT);
                    end.setKeepCallback(false);
                    old.sendPluginResult(end);
                }
                cb.success();
                return true;
            case "requestNotificationPermission":
                requestNotificationPermission(cb);
                return true;
            default:
                return false;
        }
    }

    // =================================================================== actions

    private void enqueue(JSONObject o, CallbackContext cb) throws Exception {
        Context ctx = ctx();
        String url = o.optString("url", "").trim();
        String filePath = o.optString("filePath", "").trim();
        if (url.isEmpty()) throw new IllegalArgumentException("Option 'url' obligatoire");
        if (filePath.isEmpty()) throw new IllegalArgumentException("Option 'filePath' obligatoire");

        String id = o.isNull("id") ? UUID.randomUUID().toString() : o.getString("id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Identifiant invalide (lettres, chiffres, '-' et '_' uniquement) : " + id);
        }

        // Même id déjà en file ou déjà envoyé : on ne renvoie pas (évite les doublons en cas de double clic)
        JSONObject existing = FileQueue.get(ctx, id);
        if (existing != null) {
            String status = existing.optString("status");
            if (!FileQueue.FAILED.equals(status) && !FileQueue.CANCELLED.equals(status)) {
                JSONObject result = FileQueue.toPublic(existing);
                result.put("alreadyQueued", true);
                cb.success(result);
                return;
            }
            if (existing.optBoolean("ownsFile")) UploadWorker.deleteQuietly(existing.optString("localPath"));
        }

        String fileName = o.optString("fileName", "").trim();
        if (fileName.isEmpty()) fileName = displayName(ctx, filePath, id);

        // Copie dans le stockage privé : la vidéo reste disponible même si l'utilisateur
        // la supprime de la galerie ou si l'URI content:// temporaire expire.
        boolean copy = o.optBoolean("copyToAppStorage", true);
        String localPath;
        if (copy) {
            localPath = copyToAppStorage(ctx, filePath, id, fileName).getAbsolutePath();
        } else {
            try (InputStream ignored = UploadWorker.openInput(ctx, filePath)) {
                localPath = filePath; // vérifie juste que le fichier est lisible
            }
        }

        boolean wifiOnly = o.optBoolean("wifiOnly", false);
        long startAt = Math.max(0, o.optLong("startAt", 0));
        JSONObject rec = new JSONObject();
        rec.put("id", id);
        rec.put("url", url);
        rec.put("sourcePath", filePath);
        rec.put("localPath", localPath);
        rec.put("ownsFile", copy);
        rec.put("fileName", fileName);
        rec.put("fileKey", o.optString("fileKey", "file"));
        rec.put("mimeType", o.optString("mimeType", "video/mp4"));
        rec.put("httpMethod", o.optString("httpMethod", "POST"));
        rec.put("headers", o.optJSONObject("headers") != null ? o.getJSONObject("headers") : new JSONObject());
        rec.put("params", o.optJSONObject("params") != null ? o.getJSONObject("params") : new JSONObject());
        rec.put("title", o.optString("title", UploadWorker.DEFAULT_TITLE));
        rec.put("wifiOnly", wifiOnly);
        rec.put("group", o.optString("group", "").trim());
        rec.put("groupTitle", o.optString("groupTitle", "").trim());
        rec.put("startAt", startAt > 0 ? startAt : JSONObject.NULL);
        rec.put("maxAttempts", Math.max(1, o.optInt("maxAttempts", 20)));
        rec.put("status", FileQueue.QUEUED);
        rec.put("progress", 0);
        rec.put("bytesSent", 0);
        long total = UploadWorker.fileLength(ctx, localPath);
        rec.put("totalBytes", total < 0 ? JSONObject.NULL : total);
        rec.put("attempts", 0);
        rec.put("httpCode", JSONObject.NULL);
        rec.put("lastError", JSONObject.NULL);
        rec.put("response", JSONObject.NULL);

        FileQueue.put(ctx, rec);
        UploadWorker.schedule(ctx, id, wifiOnly, startAt);
        Log.i(TAG, "Envoi mis en file : " + id + " (" + fileName + ")"
                + (startAt > System.currentTimeMillis()
                        ? " prévu à " + new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(new Date(startAt))
                        : ""));

        JSONObject result = FileQueue.toPublic(rec);
        result.put("alreadyQueued", false);
        cb.success(result);
    }

    /**
     * Relance un envoi en échec (FAILED) ou en attente (QUEUED, y compris programmé).
     * startAt : nouvelle heure de départ (ms), 0 = tout de suite.
     */
    private void retry(String id, long startAt, CallbackContext cb) throws Exception {
        Context ctx = ctx();
        JSONObject rec = requireRecord(ctx, id);
        String status = rec.optString("status");
        if (!FileQueue.FAILED.equals(status) && !FileQueue.QUEUED.equals(status)) {
            throw new IllegalStateException("Impossible de relancer un envoi à l'état " + status);
        }
        if (UploadWorker.fileLength(ctx, rec.optString("localPath")) < 0
                && !rec.optString("localPath").startsWith("content://")) {
            throw new IllegalStateException("Le fichier local n'existe plus, envoi impossible");
        }
        JSONObject updated = FileQueue.update(ctx, id,
                "status", FileQueue.QUEUED,
                "attempts", 0,
                "progress", 0,
                "bytesSent", 0,
                "httpCode", null,
                "lastError", null,
                "runId", null,
                "startAt", startAt > 0 ? startAt : null);
        UploadWorker.cancelNotification(ctx, UploadWorker.finishedNotificationId(id));
        UploadWorker.schedule(ctx, id, rec.optBoolean("wifiOnly"), startAt);
        cb.success(FileQueue.toPublic(updated));
    }

    private void cancel(String id, CallbackContext cb) throws Exception {
        Context ctx = ctx();
        JSONObject rec = requireRecord(ctx, id);
        if (FileQueue.SUCCEEDED.equals(rec.optString("status"))) {
            cb.success(FileQueue.toPublic(rec)); // déjà envoyé : rien à annuler
            return;
        }
        // D'abord l'état (le Worker en cours le verra et n'écrasera rien), puis la tâche
        JSONObject updated = FileQueue.update(ctx, id,
                "status", FileQueue.CANCELLED,
                "runId", null,
                "lastError", "Annulé");
        UploadWorker.cancel(ctx, id);
        if (rec.optBoolean("ownsFile")) UploadWorker.deleteQuietly(rec.optString("localPath"));
        UploadWorker.cancelNotification(ctx, UploadWorker.progressNotificationId(id));
        cb.success(FileQueue.toPublic(updated));
    }

    /**
     * En-têtes ajoutés à tous les envois (y compris ceux déjà en file).
     * Les envois bloqués sur un 401 sont relancés immédiatement avec le nouveau jeton.
     */
    private void setGlobalHeaders(JSONObject headers, CallbackContext cb) {
        Context ctx = ctx();
        FileQueue.setGlobalHeaders(ctx, headers);
        int relaunched = 0;
        for (JSONObject rec : FileQueue.list(ctx)) {
            if (FileQueue.QUEUED.equals(rec.optString("status")) && rec.optInt("httpCode") == 401) {
                UploadWorker.schedule(ctx, rec.optString("id"), rec.optBoolean("wifiOnly"), rec.optLong("startAt", 0));
                relaunched++;
            }
        }
        if (relaunched > 0) Log.i(TAG, relaunched + " envoi(s) relancé(s) après changement de jeton");
        cb.success();
    }

    private void clearFinished(CallbackContext cb) {
        Context ctx = ctx();
        List<JSONObject> removed = FileQueue.clearFinished(ctx);
        for (JSONObject rec : removed) {
            if (rec.optBoolean("ownsFile")) UploadWorker.deleteQuietly(rec.optString("localPath"));
            UploadWorker.cancelNotification(ctx, UploadWorker.finishedNotificationId(rec.optString("id")));
        }
        cb.success(removed.size());
    }

    // =================================================================== permission notifications

    private void requestNotificationPermission(CallbackContext cb) {
        if (Build.VERSION.SDK_INT < 33 || cordova.hasPermission(POST_NOTIFICATIONS)) {
            cb.sendPluginResult(new PluginResult(PluginResult.Status.OK, true));
            return;
        }
        permissionCallback = cb;
        cordova.requestPermission(this, REQ_NOTIFICATIONS, POST_NOTIFICATIONS);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        if (requestCode != REQ_NOTIFICATIONS || permissionCallback == null) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        permissionCallback.sendPluginResult(new PluginResult(PluginResult.Status.OK, granted));
        permissionCallback = null;
    }

    // =================================================================== abonnement JS

    @Override
    public void onQueueChanged() {
        pushUpdate();
    }

    private void pushUpdate() {
        CallbackContext cb = subscriber;
        if (cb == null) return;
        PluginResult result = new PluginResult(PluginResult.Status.OK, publicList());
        result.setKeepCallback(true);
        cb.sendPluginResult(result);
    }

    private JSONArray publicList() {
        JSONArray array = new JSONArray();
        for (JSONObject rec : FileQueue.list(ctx())) array.put(FileQueue.toPublic(rec));
        return array;
    }

    @Override
    public void onReset() {
        subscriber = null; // rechargement de la page : le JS se réabonnera
    }

    @Override
    public void onDestroy() {
        subscriber = null;
        if (FileQueue.getListener() == this) FileQueue.setListener(null);
    }

    // =================================================================== utilitaires

    private void runAsync(CallbackContext cb, Task task) {
        cordova.getThreadPool().execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                Log.e(TAG, "Erreur plugin", e);
                cb.error(e.getMessage() != null ? e.getMessage() : e.toString());
            }
        });
    }

    private static String idArg(JSONArray args) {
        return args.isNull(0) ? null : args.optString(0);
    }

    private static JSONObject requireRecord(Context ctx, String id) {
        if (id == null) throw new IllegalArgumentException("Identifiant d'envoi manquant");
        JSONObject rec = FileQueue.get(ctx, id);
        if (rec == null) throw new IllegalArgumentException("Envoi inconnu : " + id);
        return rec;
    }

    private static File copyToAppStorage(Context ctx, String source, String id, String fileName) throws IOException {
        // noBackup : les vidéos en transit ne doivent pas partir dans la sauvegarde Google
        File dir = new File(ctx.getNoBackupFilesDir(), STORAGE_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Impossible de créer " + dir);

        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 && fileName.length() - dot <= 6 ? fileName.substring(dot) : ".mp4";
        File dest = new File(dir, id + ext);

        try (InputStream in = UploadWorker.openInput(ctx, source);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete(); // ex. stockage plein
            throw e;
        }
        return dest;
    }

    private static String displayName(Context ctx, String path, String id) {
        if (path.startsWith("content://")) {
            try (Cursor c = ctx.getContentResolver().query(Uri.parse(path),
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
            } catch (Exception ignored) {
            }
            return id + ".mp4";
        }
        String name = UploadWorker.toFile(path).getName();
        return name.isEmpty() ? id + ".mp4" : name;
    }
}
