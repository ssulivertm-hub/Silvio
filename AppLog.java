package br.motorista.gravador;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AppLog {
    static synchronized void add(Context c, String msg) {
        Log.i("Gravador", msg);
        try {
            File f = new File(c.getFilesDir(), "log.txt");
            if (f.exists() && f.length() > 300000) {
                f.delete();
            }
            String t = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date());
            FileWriter w = new FileWriter(f, true);
            w.write(t + "  " + msg + "\n");
            w.close();
        } catch (IOException ignored) {
        }
    }
}
