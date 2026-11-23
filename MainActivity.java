package br.motorista.gravador;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 77;

    private TextView status;
    private TextView logView;
    private TextView estimate;
    private EditText etPkgs;
    private EditText etStart;
    private EditText etStop;
    private EditText etCid;
    private EditText etSec;
    private CheckBox cbBack;
    private CheckBox cbCalib;
    private CheckBox cbDrive;
    private CheckBox cbWifi;
    private CheckBox cbDel;
    private Spinner spRes;
    private Spinner spFps;
    private Spinner spVk;
    private Spinner spAk;
    private Spinner spDuration;
    private AlertDialog codeDialog;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(12), 0, dp(2));
        return t;
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(18);
        t.setPadding(0, dp(24), 0, dp(4));
        return t;
    }

    private String[] strs(int[] a, String suffix) {
        String[] out = new String[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] + suffix;
        return out;
    }

    private Spinner spinner(String[] items, int sel) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items));
        s.setSelection(sel);
        return s;
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sp = Prefs.sp(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        sv.addView(root);

        TextView title = new TextView(this);
        title.setText("Gravador de Corridas");
        title.setTextSize(22);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        // ---------- Preparação ----------
        root.addView(section("Preparação (uma vez)"));
        root.addView(btn("1. Permissões (câmera, microfone, notificação)", v ->
                requestPermissions(new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS}, 1)));

        root.addView(btn("2. Liberar 'configurações restritas' (Info do app > ⋮)", v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }));

        root.addView(btn("3. Ativar Acessibilidade (Gravador de Corridas)", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));

        root.addView(btn("4. Tirar da otimização de bateria", v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        }));

        root.addView(btn("Testar: iniciar / parar gravação agora", v -> {
            if (RecorderService.recording) {
                RecorderService.requestStop(false);
            } else {
                try {
                    startForegroundService(new Intent(this, RecorderService.class)
                            .setAction(RecorderService.ACTION_START));
                } catch (Exception e) {
                    toast("Falhou: " + e);
                }
            }
            v.postDelayed(this::refresh, 1500);
        }));

        // ---------- Onde salvar ----------
        root.addView(section("Onde salvar"));
        root.addView(btn("Escolher pasta de gravação", v -> pickFolder()));
        root.addView(btn("Voltar para a galeria (Movies/Corridas)", v -> {
            Prefs.sp(this).edit().remove("tree").putBoolean("folder_set", true).apply();
            refresh();
        }));

        // ---------- Qualidade ----------
        root.addView(section("Duração máxima de cada gravação"));
        String[] durationLabels = {"10 minutos", "20 minutos", "30 minutos", "40 minutos",
                "50 minutos", "1 hora", "2 horas", "3 horas"};
        long[] durationValues = {10L, 20L, 30L, 40L, 50L, 60L, 120L, 180L};
        int selectedDuration = sp.getInt("duration_minutes", 180);
        int durationIndex = 7;
        for (int i = 0; i < durationValues.length; i++) {
            if (durationValues[i] == selectedDuration) durationIndex = i;
        }
        spDuration = spinner(durationLabels, durationIndex);
        root.addView(spDuration);

        root.addView(section("Qualidade da gravação"));
        root.addView(label("Resolução do vídeo (até 4K; o celular usa o maior tamanho que a câmera aceitar)"));
        spRes = spinner(Prefs.RES_LABELS, Prefs.res(this));
        root.addView(spRes);
        root.addView(label("Quadros por segundo"));
        spFps = spinner(strs(Prefs.FPS, " fps"), Prefs.idx(Prefs.FPS, Prefs.fps(this), 2));
        root.addView(spFps);
        root.addView(label("Taxa de bits do vídeo"));
        spVk = spinner(strs(Prefs.VK, " kbps"), Prefs.idx(Prefs.VK, Prefs.vk(this), 2));
        root.addView(spVk);
        root.addView(label("Taxa de bits do áudio"));
        spAk = spinner(strs(Prefs.AK, " kbps"), Prefs.idx(Prefs.AK, Prefs.ak(this), 2));
        root.addView(spAk);
        estimate = new TextView(this);
        estimate.setPadding(0, dp(8), 0, 0);
        root.addView(estimate);
        AdapterView.OnItemSelectedListener sel = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateEstimate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        spVk.setOnItemSelectedListener(sel);
        spAk.setOnItemSelectedListener(sel);

        cbBack = new CheckBox(this);
        cbBack.setText("Usar câmera traseira (padrão: frontal, grava o interior)");
        cbBack.setChecked(sp.getBoolean("back", false));
        root.addView(cbBack);

        // ---------- Google Drive ----------
        root.addView(section("Google Drive"));
        cbDrive = new CheckBox(this);
        cbDrive.setText("Enviar cada gravação ao Google Drive (pasta 'Corridas')");
        cbDrive.setChecked(sp.getBoolean("drive_on", false));
        root.addView(cbDrive);
        cbWifi = new CheckBox(this);
        cbWifi.setText("Só enviar no Wi-Fi (desmarcado = usa dados móveis)");
        cbWifi.setChecked(sp.getBoolean("wifi_only", false));
        root.addView(cbWifi);
        cbDel = new CheckBox(this);
        cbDel.setText("Apagar do celular depois de enviar");
        cbDel.setChecked(sp.getBoolean("del_after", false));
        root.addView(cbDel);
        root.addView(label("Client ID (Google Cloud > cliente 'TVs e dispositivos de entrada limitada')"));
        etCid = new EditText(this);
        etCid.setSingleLine(true);
        etCid.setText(sp.getString("cid", ""));
        root.addView(etCid);
        root.addView(label("Client secret"));
        etSec = new EditText(this);
        etSec.setSingleLine(true);
        etSec.setText(sp.getString("csec", ""));
        root.addView(etSec);
        root.addView(btn("Conectar ao Google Drive", v -> connectDrive()));
        root.addView(btn("Enviar pendentes agora", v -> {
            try {
                startForegroundService(new Intent(this, UploadService.class));
            } catch (Exception e) {
                toast("Falhou: " + e);
            }
            v.postDelayed(this::refresh, 2000);
        }));

        // ---------- Detecção ----------
        root.addView(section("Detecção automática"));
        cbCalib = new CheckBox(this);
        cbCalib.setText("Modo calibração: registrar textos das telas no log");
        cbCalib.setChecked(sp.getBoolean("calib", false));
        root.addView(cbCalib);

        root.addView(label("Apps monitorados (parte do nome do pacote, separados por vírgula)"));
        etPkgs = new EditText(this);
        etPkgs.setText(Prefs.raw(this, "pkgs", Prefs.DEF_PKGS));
        root.addView(etPkgs);

        root.addView(label("Textos que indicam CORRIDA EM ANDAMENTO (iniciam a gravação)"));
        etStart = new EditText(this);
        etStart.setText(Prefs.raw(this, "start", Prefs.DEF_START));
        root.addView(etStart);

        root.addView(label("Textos que indicam CORRIDA ENCERRADA (param a gravação em 20 s)"));
        etStop = new EditText(this);
        etStop.setText(Prefs.raw(this, "stop", Prefs.DEF_STOP));
        root.addView(etStop);

        root.addView(btn("SALVAR TODAS AS CONFIGURAÇÕES", v -> saveAll()));

        root.addView(section("Log"));
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        root.addView(logView);

        root.addView(btn("Atualizar log", v -> refresh()));
        root.addView(btn("Limpar log", v -> {
            new File(getFilesDir(), "log.txt").delete();
            refresh();
        }));

        setContentView(sv);
        updateEstimate();

        if (!sp.getBoolean("folder_set", false)) askFolderFirstTime();
    }

    private void saveAll() {
        Prefs.sp(this).edit()
                .putBoolean("back", cbBack.isChecked())
                .putBoolean("calib", cbCalib.isChecked())
                .putBoolean("drive_on", cbDrive.isChecked())
                .putBoolean("wifi_only", cbWifi.isChecked())
                .putBoolean("del_after", cbDel.isChecked())
                .putInt("res", spRes.getSelectedItemPosition())
                .putInt("fps", Prefs.FPS[spFps.getSelectedItemPosition()])
                .putInt("vkbps", Prefs.VK[spVk.getSelectedItemPosition()])
                .putInt("akbps", Prefs.AK[spAk.getSelectedItemPosition()])
                .putInt("duration_minutes", new int[]{10,20,30,40,50,60,120,180}[spDuration.getSelectedItemPosition()])
                .putString("cid", etCid.getText().toString().trim())
                .putString("csec", etSec.getText().toString().trim())
                .putString("pkgs", etPkgs.getText().toString())
                .putString("start", etStart.getText().toString())
                .putString("stop", etStop.getText().toString())
                .apply();
        toast("Configurações salvas");
        refresh();
    }

    private void updateEstimate() {
        int vk = Prefs.VK[spVk.getSelectedItemPosition()];
        int ak = Prefs.AK[spAk.getSelectedItemPosition()];
        double mbPerHour = (vk + ak) * 1000.0 / 8.0 * 3600.0 / 1000000.0;
        estimate.setText(String.format(Locale.US, "Tamanho estimado: ≈ %.0f MB por hora (%.2f GB)",
                mbPerHour, mbPerHour / 1000.0));
    }

    // ---------- Pasta ----------
    private void askFolderFirstTime() {
        new AlertDialog.Builder(this)
                .setTitle("Onde salvar as gravações?")
                .setCancelable(false)
                .setItems(new String[]{
                        "Galeria do celular (Movies/Corridas)",
                        "Escolher outra pasta (cartão SD, Downloads...)"}, (d, which) -> {
                    if (which == 0) {
                        Prefs.sp(this).edit().remove("tree").putBoolean("folder_set", true).apply();
                        refresh();
                    } else {
                        pickFolder();
                    }
                })
                .show();
    }

    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_FOLDER);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FOLDER) return;
        SharedPreferences.Editor ed = Prefs.sp(this).edit();
        if (res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) {
                toast("Não consegui guardar a permissão da pasta: " + e);
            }
            ed.putString("tree", uri.toString()).putBoolean("folder_set", true);
        } else if (!Prefs.sp(this).getBoolean("folder_set", false)) {
            ed.putBoolean("folder_set", true).remove("tree");
        }
        ed.apply();
        refresh();
    }

    private String folderLabel() {
        String t = Prefs.sp(this).getString("tree", "");
        if (t.isEmpty()) return "Galeria (Movies/Corridas)";
        String last = Uri.parse(t).getLastPathSegment();
        return last == null ? t : Uri.decode(last);
    }

    // ---------- Drive ----------
    private void connectDrive() {
        final String cid = etCid.getText().toString().trim();
        final String sec = etSec.getText().toString().trim();
        if (cid.isEmpty() || sec.isEmpty()) {
            toast("Preencha Client ID e Client secret");
            return;
        }
        Prefs.sp(this).edit()
                .putString("cid", cid).putString("csec", sec)
                .remove("drive_rt").remove("drive_at").remove("drive_folder")
                .apply();
        new Thread(() -> {
            try {
                final JSONObject dc = DriveAuth.requestDeviceCode(cid);
                final String code = dc.getString("user_code");
                final String url = dc.optString("verification_url", "https://www.google.com/device");
                runOnUiThread(() -> showCodeDialog(code, url));
                final String err = DriveAuth.pollForRefreshToken(this, dc);
                runOnUiThread(() -> {
                    if (codeDialog != null) codeDialog.dismiss();
                    toast(err == null ? "Google Drive conectado" : "Não conectou: " + err);
                    refresh();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (codeDialog != null) codeDialog.dismiss();
                    toast("Erro: " + e);
                });
            }
        }).start();
    }

    private void showCodeDialog(final String code, final String url) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("codigo", code));
        } catch (Exception ignored) {
        }
        codeDialog = new AlertDialog.Builder(this)
                .setTitle("Conectar ao Google Drive")
                .setMessage("1. Abra: " + url + "\n2. Digite o código:\n\n" + code
                        + "\n\n(o código já foi copiado)\n3. Entre na conta Google e toque em Permitir.\n"
                        + "Esta janela fecha sozinha quando conectar.")
                .setPositiveButton("Abrir página", (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))))
                .setNegativeButton("Fechar", null)
                .show();
    }

    // ---------- Status ----------
    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private boolean accessibilityOn() {
        String s = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName());
    }

    private void refresh() {
        SharedPreferences sp = Prefs.sp(this);
        boolean driveOk = !sp.getString("drive_rt", "").isEmpty();
        status.setText("Acessibilidade: " + (accessibilityOn() ? "ATIVA" : "desligada")
                + "\nGravando agora: " + (RecorderService.recording ? "SIM" : "não")
                + "\nPasta: " + folderLabel()
                + "\nGoogle Drive: " + (driveOk ? "conectado" : "não conectado")
                + (sp.getBoolean("drive_on", false) ? " (envio ligado)" : " (envio desligado)")
                + "\nPendentes para enviar: " + UploadQueue.count(this));
        logView.setText(readLog());
    }

    private String readLog() {
        File f = new File(getFilesDir(), "log.txt");
        if (!f.exists()) return "(vazio)";
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (s.length() > 6000) s = s.substring(s.length() - 6000);
            return s;
        } catch (Exception e) {
            return "erro ao ler log: " + e;
        }
    }
}
