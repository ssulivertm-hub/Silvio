package br.motorista.gravador;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

class DriveAuth {
    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final String TOKEN = "https://oauth2.googleapis.com/token";
    static final String FORM = "application/x-www-form-urlencoded";

    static JSONObject requestDeviceCode(String clientId) throws Exception {
        Http.Res r = Http.request("POST", "https://oauth2.googleapis.com/device/code", null, FORM,
                Http.form("client_id", clientId, "scope", SCOPE).getBytes(StandardCharsets.UTF_8));
        if (r.code != 200) throw new IOException("device/code " + r.code + ": " + r.body);
        return new JSONObject(r.body);
    }

    /** Retorna null se conectou; senão o motivo do erro. Bloqueante: chame em thread. */
    static String pollForRefreshToken(Context c, JSONObject dc) throws Exception {
        SharedPreferences sp = Prefs.sp(c);
        String cid = sp.getString("cid", "");
        String sec = sp.getString("csec", "");
        int interval = dc.optInt("interval", 5);
        long deadline = SystemClock.elapsedRealtime() + dc.optLong("expires_in", 900) * 1000L;
        while (SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(interval * 1000L);
            Http.Res r = Http.request("POST", TOKEN, null, FORM,
                    Http.form("client_id", cid,
                            "client_secret", sec,
                            "device_code", dc.getString("device_code"),
                            "grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                            .getBytes(StandardCharsets.UTF_8));
            JSONObject j;
            try {
                j = new JSONObject(r.body);
            } catch (Exception e) {
                j = new JSONObject();
            }
            if (r.code == 200 && j.has("refresh_token")) {
                sp.edit()
                        .putString("drive_rt", j.getString("refresh_token"))
                        .putString("drive_at", j.optString("access_token", ""))
                        .putLong("drive_exp", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000L)
                        .apply();
                return null;
            }
            String err = j.optString("error", "http " + r.code);
            if (err.equals("authorization_pending")) continue;
            if (err.equals("slow_down")) {
                interval += 5;
                continue;
            }
            return err;
        }
        return "tempo esgotado";
    }

    static synchronized String accessToken(Context c) throws Exception {
        SharedPreferences sp = Prefs.sp(c);
        String at = sp.getString("drive_at", "");
        long exp = sp.getLong("drive_exp", 0);
        if (!at.isEmpty() && System.currentTimeMillis() < exp - 60000L) return at;
        String rt = sp.getString("drive_rt", "");
        if (rt.isEmpty()) throw new IOException("Drive não conectado");
        Http.Res r = Http.request("POST", TOKEN, null, FORM,
                Http.form("client_id", sp.getString("cid", ""),
                        "client_secret", sp.getString("csec", ""),
                        "refresh_token", rt,
                        "grant_type", "refresh_token").getBytes(StandardCharsets.UTF_8));
        if (r.code != 200) {
            if (r.body.contains("invalid_grant")) {
                sp.edit().remove("drive_rt").remove("drive_at").apply();
                throw new IOException("Autorização do Drive expirou: conecte de novo no app");
            }
            throw new IOException("token " + r.code + ": " + r.body);
        }
        JSONObject j = new JSONObject(r.body);
        String na = j.getString("access_token");
        sp.edit().putString("drive_at", na)
                .putLong("drive_exp", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000L)
                .apply();
        return na;
    }
}
