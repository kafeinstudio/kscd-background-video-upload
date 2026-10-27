package com.kafein.kscd.upload;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * File d'envoi persistante (JSON dans SharedPreferences).
 *
 * C'est la source de vérité de l'état des envois : le Worker l'écrit, le plugin la lit
 * pour le JavaScript. Elle survit à la fermeture de l'app et au redémarrage du téléphone
 * (WorkManager, de son côté, conserve la planification des tâches).
 */
final class FileQueue {

    static final String QUEUED = "QUEUED";       // en attente (réseau, relance programmée...)
    static final String RUNNING = "RUNNING";     // envoi en cours
    static final String SUCCEEDED = "SUCCEEDED"; // envoyé
    static final String FAILED = "FAILED";       // abandon (erreur définitive ou trop de tentatives)
    static final String CANCELLED = "CANCELLED"; // annulé par l'app

    interface Listener {
        void onQueueChanged();
    }

    private static final String PREFS = "kscd_upload_queue";
    private static final String KEY_UPLOADS = "uploads";
    private static final String KEY_GLOBAL_HEADERS = "globalHeaders";

    private static final Object LOCK = new Object();
    private static JSONObject cache;
    private static volatile Listener listener;

    private FileQueue() {
    }

    static void setListener(Listener l) {
        listener = l;
    }

    static Listener getListener() {
        return listener;
    }

    static boolean isFinished(String status) {
        return SUCCEEDED.equals(status) || FAILED.equals(status) || CANCELLED.equals(status);
    }

    // ---------------------------------------------------------------- lecture

    /** Copie de l'enregistrement, ou null s'il n'existe pas. */
    static JSONObject get(Context ctx, String id) {
        synchronized (LOCK) {
            JSONObject rec = uploads(ctx).optJSONObject(id);
            return rec == null ? null : copy(rec);
        }
    }

    /** Tous les enregistrements, du plus ancien au plus récent. */
    /** Envois d'un même groupe (option "group" d'enqueue). */
    static List<JSONObject> listGroup(Context ctx, String group) {
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject rec : list(ctx)) {
            if (group.equals(rec.optString("group", ""))) result.add(rec);
        }
        return result;
    }

    static List<JSONObject> list(Context ctx) {
        List<JSONObject> result = new ArrayList<>();
        synchronized (LOCK) {
            JSONObject all = uploads(ctx);
            Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                JSONObject rec = all.optJSONObject(keys.next());
                if (rec != null) result.add(copy(rec));
            }
        }
        Collections.sort(result, (a, b) -> Long.compare(a.optLong("createdAt"), b.optLong("createdAt")));
        return result;
    }

    /** Version exposée au JavaScript : sans les en-têtes (jetons d'authentification). */
    static JSONObject toPublic(JSONObject rec) {
        JSONObject pub = copy(rec);
        pub.remove("headers");
        pub.remove("runId");
        return pub;
    }

    // ---------------------------------------------------------------- écriture

    static void put(Context ctx, JSONObject rec) throws JSONException {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            if (!rec.has("createdAt")) rec.put("createdAt", now);
            rec.put("updatedAt", now);
            uploads(ctx).put(rec.getString("id"), copy(rec));
            persist(ctx);
        }
        notifyListener();
    }

    /**
     * Met à jour des champs : update(ctx, id, "status", QUEUED, "progress", 0).
     * Renvoie l'enregistrement à jour, ou null s'il n'existe pas.
     */
    static JSONObject update(Context ctx, String id, Object... keyValues) {
        return updateIf(ctx, id, null, false, keyValues);
    }

    /**
     * Mise à jour faite par un Worker : ignorée si l'envoi a été annulé/terminé entre-temps,
     * ou si un autre Worker (relance) a pris la main (runId différent).
     */
    static JSONObject updateForRun(Context ctx, String id, String runId, Object... keyValues) {
        return updateIf(ctx, id, runId, true, keyValues);
    }

    /** Démarrage d'un Worker : prend la main sur l'envoi s'il n'est pas terminé. */
    static JSONObject startRun(Context ctx, String id, Object... keyValues) {
        return updateIf(ctx, id, null, true, keyValues);
    }

    private static JSONObject updateIf(Context ctx, String id, String expectedRunId,
                                       boolean skipIfFinished, Object... keyValues) {
        JSONObject result;
        synchronized (LOCK) {
            JSONObject rec = uploads(ctx).optJSONObject(id);
            if (rec == null) return null;
            if (skipIfFinished && isFinished(rec.optString("status"))) return null;
            if (expectedRunId != null && !expectedRunId.equals(rec.optString("runId"))) return null;
            try {
                for (int i = 0; i + 1 < keyValues.length; i += 2) {
                    Object value = keyValues[i + 1];
                    rec.put((String) keyValues[i], value == null ? JSONObject.NULL : value);
                }
                rec.put("updatedAt", System.currentTimeMillis());
            } catch (JSONException e) {
                return null;
            }
            persist(ctx);
            result = copy(rec);
        }
        notifyListener();
        return result;
    }

    /**
     * Retire de la file les envois terminés et les renvoie (pour supprimer leurs fichiers).
     * Les envois d'un groupe encore en cours sont gardés : il faut tous les connaître pour
     * afficher la notification de fin du groupe.
     */
    static List<JSONObject> clearFinished(Context ctx) {
        List<JSONObject> removed = new ArrayList<>();
        synchronized (LOCK) {
            JSONObject all = uploads(ctx);
            List<String> ids = new ArrayList<>();
            Iterator<String> keys = all.keys();
            while (keys.hasNext()) ids.add(keys.next());

            Set<String> activeGroups = new HashSet<>();
            for (String id : ids) {
                JSONObject rec = all.optJSONObject(id);
                String group = rec == null ? "" : rec.optString("group", "");
                if (!group.isEmpty() && !isFinished(rec.optString("status"))) activeGroups.add(group);
            }

            for (String id : ids) {
                JSONObject rec = all.optJSONObject(id);
                if (rec != null && isFinished(rec.optString("status"))
                        && !activeGroups.contains(rec.optString("group", ""))) {
                    removed.add(copy(rec));
                    all.remove(id);
                }
            }
            if (!removed.isEmpty()) persist(ctx);
        }
        if (!removed.isEmpty()) notifyListener();
        return removed;
    }

    // ---------------------------------------------------------------- en-têtes globaux

    static JSONObject getGlobalHeaders(Context ctx) {
        String raw = prefs(ctx).getString(KEY_GLOBAL_HEADERS, "{}");
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void setGlobalHeaders(Context ctx, JSONObject headers) {
        // commit() : le jeton doit être écrit avant qu'un Worker ne relise la valeur
        prefs(ctx).edit().putString(KEY_GLOBAL_HEADERS, headers == null ? "{}" : headers.toString()).commit();
    }

    // ---------------------------------------------------------------- interne

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject uploads(Context ctx) {
        if (cache == null) {
            String raw = prefs(ctx).getString(KEY_UPLOADS, "{}");
            try {
                cache = new JSONObject(raw);
            } catch (JSONException e) {
                cache = new JSONObject();
            }
        }
        return cache;
    }

    private static void persist(Context ctx) {
        prefs(ctx).edit().putString(KEY_UPLOADS, cache.toString()).apply();
    }

    private static JSONObject copy(JSONObject rec) {
        try {
            return new JSONObject(rec.toString());
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static void notifyListener() {
        Listener l = listener;
        if (l == null) return;
        try {
            l.onQueueChanged();
        } catch (Exception ignored) {
            // la WebView peut être en cours de destruction
        }
    }
}
