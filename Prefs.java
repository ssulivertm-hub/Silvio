package br.motorista.gravador;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Prefs {
    static final String DEF_PKGS = "uber, app99, taxis99, indriver";
    static final String DEF_START = "finalizar viagem, finalizar corrida, concluir viagem, encerrar viagem, em viagem, em corrida";
    static final String DEF_STOP = "avaliar passageiro, avalie o passageiro, avalie sua viagem, você está online, voce esta online";

    static final String[] RES_LABELS = {"240p (320x240)", "480p (720x480)", "720p (1280x720)", "1080p (1920x1080)", "4K (3840x2160)"};
    static final int[][] RES = {{320, 240}, {720, 480}, {1280, 720}, {1920, 1080}, {3840, 2160}};
    static final int[] VK = {320, 820, 1500, 3000, 6000, 12000, 25000};
    static final int[] AK = {32, 48, 64, 96, 128, 144};
    static final int[] FPS = {15, 24, 30};

    static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("gravador", Context.MODE_PRIVATE);
    }

    static String raw(Context c, String key, String def) {
        return sp(c).getString(key, def);
    }

    static int idx(int[] a, int v, int def) {
        for (int i = 0; i < a.length; i++) {
            if (a[i] == v) return i;
        }
        return def;
    }

    static int res(Context c) {
        int i = sp(c).getInt("res", 2);
        return (i < 0 || i >= RES.length) ? 2 : i;
    }

    static int vk(Context c) {
        return sp(c).getInt("vkbps", 1500);
    }

    static int ak(Context c) {
        return sp(c).getInt("akbps", 64);
    }

    static int fps(Context c) {
        return sp(c).getInt("fps", 30);
    }

    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String p : s.split(",")) {
            String t = p.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    static List<String> pkgs(Context c) {
        return split(raw(c, "pkgs", DEF_PKGS));
    }

    static List<String> startKw(Context c) {
        return split(raw(c, "start", DEF_START));
    }

    static List<String> stopKw(Context c) {
        return split(raw(c, "stop", DEF_STOP));
    }
}
