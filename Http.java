package br.motorista.gravador;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

class Http {
    static class Res {
        final int code;
        final String body;

        Res(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(kv[i])).append('=').append(enc(kv[i + 1]));
        }
        return sb.toString();
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toString("UTF-8");
    }

    static Res request(String method, String url, String bearer, String ctype, byte[] body) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setRequestMethod(method);
        if (bearer != null) h.setRequestProperty("Authorization", "Bearer " + bearer);
        if (ctype != null) h.setRequestProperty("Content-Type", ctype);
        if (body != null) {
            h.setDoOutput(true);
            h.setFixedLengthStreamingMode(body.length);
            OutputStream o = h.getOutputStream();
            o.write(body);
            o.close();
        }
        int code = h.getResponseCode();
        InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream();
        String s = in == null ? "" : readAll(in);
        h.disconnect();
        return new Res(code, s);
    }
}
