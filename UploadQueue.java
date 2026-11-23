package br.motorista.gravador;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class UploadQueue {
    private static File file(Context c) {
        return new File(c.getFilesDir(), "queue.txt");
    }

    static synchronized void add(Context c, String uri, String name, String mime) {
        try {
            FileWriter w = new FileWriter(file(c), true);
            w.write(uri + "\t" + name + "\t" + mime + "\n");
            w.close();
        } catch (IOException ignored) {
        }
    }

    static synchronized List<String[]> read(Context c) {
        List<String[]> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length >= 3) out.add(p);
            }
            r.close();
        } catch (IOException ignored) {
        }
        return out;
    }

    static synchronized void remove(Context c, String uri) {
        List<String[]> all = read(c);
        try {
            FileWriter w = new FileWriter(file(c), false);
            for (String[] p : all) {
                if (!p[0].equals(uri)) w.write(p[0] + "\t" + p[1] + "\t" + p[2] + "\n");
            }
            w.close();
        } catch (IOException ignored) {
        }
    }

    static synchronized int count(Context c) {
        return read(c).size();
    }
}
