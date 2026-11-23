package br.motorista.gravador;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

class DriveUploader {

    static class EmptyFile extends IOException {
        EmptyFile(String m) {
            super(m);
        }
    }

    static String ensureFolder(Context c, String token) throws Exception {
        android.content.SharedPreferences sp = Prefs.sp(c);
        String id = sp.getString("drive_folder", "");
        if (!id.isEmpty()) return id;

        String q = "name='Corridas' and mimeType='application/vnd.google-apps.folder' and trashed=false";
        Http.Res r = Http.request("GET",
                "https://www.googleapis.com/drive/v3/files?q=" + Http.enc(q)
                        + "&fields=" + Http.enc("files(id)") + "&pageSize=1",
                token, null, null);
        if (r.code != 200) throw new IOException("listar pasta " + r.code + ": " + r.body);
        JSONArray arr = new JSONObject(r.body).optJSONArray("files");
        if (arr != null && arr.length() > 0) {
            id = arr.getJSONObject(0).getString("id");
        } else {
            JSONObject meta = new JSONObject();
            meta.put("name", "Corridas");
            meta.put("mimeType", "application/vnd.google-apps.folder");
            Http.Res cr = Http.request("POST", "https://www.googleapis.com/drive/v3/files?fields=id", token,
                    "application/json; charset=UTF-8", meta.toString().getBytes(StandardCharsets.UTF_8));
            if (cr.code != 200) throw new IOException("criar pasta " + cr.code + ": " + cr.body);
            id = new JSONObject(cr.body).getString("id");
        }
        sp.edit().putString("drive_folder", id).apply();
        return id;
    }

    static void upload(Context c, String token, String folderId, Uri src, String name, String mime) throws Exception {
        ContentResolver cr = c.getContentResolver();
        ParcelFileDescriptor pfd = cr.openFileDescriptor(src, "r");
        if (pfd == null) throw new IOException("não abriu o arquivo");
        long size = pfd.getStatSize();
        pfd.close();
        if (size <= 0) throw new EmptyFile("arquivo vazio");

        JSONObject meta = new JSONObject();
        meta.put("name", name);
        meta.put("parents", new JSONArray().put(folderId));
        byte[] mb = meta.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection h = (HttpURLConnection) new URL(
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable").openConnection();
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setRequestProperty("Authorization", "Bearer " + token);
        h.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        h.setRequestProperty("X-Upload-Content-Type", mime);
        h.setRequestProperty("X-Upload-Content-Length", String.valueOf(size));
        h.setFixedLengthStreamingMode(mb.length);
        OutputStream o = h.getOutputStream();
        o.write(mb);
        o.close();
        int code = h.getResponseCode();
        if (code != 200) {
            InputStream es = h.getErrorStream();
            String err = es == null ? "" : Http.readAll(es);
            h.disconnect();
            if (code == 404) Prefs.sp(c).edit().remove("drive_folder").apply();
            throw new IOException("iniciar envio " + code + ": " + err);
        }
        String loc = h.getHeaderField("Location");
        h.disconnect();
        if (loc == null) throw new IOException("Drive não devolveu o endereço de envio");

        HttpURLConnection p = (HttpURLConnection) new URL(loc).openConnection();
        p.setRequestMethod("PUT");
        p.setDoOutput(true);
        p.setConnectTimeout(20000);
        p.setReadTimeout(120000);
        p.setRequestProperty("Content-Type", mime);
        p.setFixedLengthStreamingMode(size);
        InputStream in = cr.openInputStream(src);
        if (in == null) throw new IOException("não leu o arquivo");
        OutputStream po = p.getOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) po.write(buf, 0, n);
        po.close();
        in.close();
        int pc = p.getResponseCode();
        p.disconnect();
        if (pc != 200 && pc != 201) throw new IOException("envio terminou com código " + pc);
    }
}
