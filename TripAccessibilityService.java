package br.motorista.gravador;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TripAccessibilityService extends AccessibilityService {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView bubble;
    private TextView tripPanel;
    private String currentDestination = "";
    private GradientDrawable bubbleBg;
    private long lastScan = 0;
    private String lastCalib = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateBubble();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AppLog.add(this, "Acessibilidade conectada");
        addBubble();
        handler.post(tick);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (wm != null && tripPanel != null) {
            try { wm.removeView(tripPanel); } catch (Exception ignored) {}
        }
        if (wm != null && bubble != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void addBubble() {
        try {
            wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            tripPanel = new TextView(this);
            tripPanel.setTextColor(Color.WHITE);
            tripPanel.setTextSize(13);
            tripPanel.setPadding(dp(12), dp(10), dp(12), dp(10));
            tripPanel.setBackground(roundBg("#E6202938"));
            tripPanel.setVisibility(View.GONE);
            tripPanel.setOnClickListener(v -> openDestinationInMaps());
            WindowManager.LayoutParams panelLp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            panelLp.y = dp(52);
            wm.addView(tripPanel, panelLp);

            bubble = new TextView(this);
            bubble.setText("REC");
            bubble.setTextColor(Color.WHITE);
            bubble.setTextSize(11);
            bubble.setGravity(Gravity.CENTER);
            bubbleBg = new GradientDrawable();
            bubbleBg.setShape(GradientDrawable.OVAL);
            bubbleBg.setColor(Color.parseColor("#88000000"));
            bubble.setBackground(bubbleBg);
            bubble.setOnClickListener(v -> {
                if (RecorderService.recording) {
                    RecorderService.requestStop(false);
                } else {
                    startRecorder();
                }
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    dp(44), dp(44),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = dp(4);
            lp.y = dp(160);
            wm.addView(bubble, lp);
        } catch (Exception e) {
            AppLog.add(this, "Botão flutuante falhou: " + e);
        }
    }

    private void updateBubble() {
        if (bubbleBg == null) return;
        if (RecorderService.recording) {
            bubbleBg.setColor(Color.parseColor(RecorderService.stopPending ? "#CCFF9800" : "#CCE53935"));
        } else {
            bubbleBg.setColor(Color.parseColor("#88000000"));
        }
    }

    private void startRecorder() {
        Intent i = new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_START);
        try {
            startForegroundService(i);
        } catch (Exception e) {
            AppLog.add(this, "Falha ao iniciar gravação: " + e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (pkg.equals(getPackageName())) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastScan < 600) return;
        lastScan = now;

        boolean calib = Prefs.sp(this).getBoolean("calib", false);
        boolean watched = containsAny(pkg.toLowerCase(Locale.ROOT), Prefs.pkgs(this));
        if (!watched && !calib) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        CharSequence rp = root.getPackageName();
        if (rp == null || !rp.toString().equals(pkg)) return;

        StringBuilder sb = new StringBuilder();
        collect(root, sb, 0);
        String text = sb.toString().toLowerCase(Locale.ROOT);

        if (calib) logCalibration(pkg, text);
        if (watched) {
            evaluate(pkg, text);
            updateTripPanel(sb.toString());
        } else if (tripPanel != null) {
            tripPanel.setVisibility(View.GONE);
        }
    }

    private GradientDrawable roundBg(String color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor(color));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#667FFFFFF"));
        return bg;
    }

    private String firstMatch(String source, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(source);
        return m.find() ? m.group(1) : "";
    }

    private void updateTripPanel(String raw) {
        if (tripPanel == null) return;
        String flat = raw.replace('\n', ' ').replace('\r', ' ');
        String fare = firstMatch(flat, "R\\$\\s*([0-9]+(?:[.,][0-9]{1,2})?)");
        String km = firstMatch(flat, "([0-9]+(?:[.,][0-9]+)?)\\s?km\\b");
        String mins = firstMatch(flat, "([0-9]{1,3})\\s?(?:min|minutos)\\b");
        String hours = firstMatch(flat, "([0-9]+(?:[.,][0-9]+)?)\\s?h(?:ora)?\\b");
        String destination = extractDestination(flat);
        if (!destination.isEmpty()) currentDestination = destination;

        if (fare.isEmpty() && km.isEmpty() && mins.isEmpty() && hours.isEmpty()
                && currentDestination.isEmpty()) {
            tripPanel.setVisibility(View.GONE);
            return;
        }

        double fareValue = parseNumber(fare);
        double kmValue = parseNumber(km);
        double minutesValue = parseNumber(mins);
        if (minutesValue <= 0 && !hours.isEmpty()) minutesValue = parseNumber(hours) * 60.0;
        String perKm = (fareValue > 0 && kmValue > 0)
                ? String.format(Locale.forLanguageTag("pt-BR"), "R$ %.2f/km", fareValue / kmValue)
                : "R$/km: indisponível";
        String perHour = (fareValue > 0 && minutesValue > 0)
                ? String.format(Locale.forLanguageTag("pt-BR"), "R$ %.2f/h", fareValue * 60.0 / minutesValue)
                : "R$/h: indisponível";

        StringBuilder panel = new StringBuilder("ANÁLISE DA CORRIDA\n");
        panel.append("Valor: ").append(fare.isEmpty() ? "não identificado" : "R$ " + fare);
        panel.append("   •   ").append(perKm).append("   •   ").append(perHour).append('\n');
        panel.append("Distância: ").append(km.isEmpty() ? "não identificada" : km + " km");
        panel.append("   •   Tempo: ").append(minutesValue <= 0 ? "não identificado" : Math.round(minutesValue) + " min");
        if (!currentDestination.isEmpty()) panel.append("\nDestino: ").append(currentDestination);
        panel.append("\nToque para abrir o destino no Google Maps");
        tripPanel.setText(panel.toString());
        tripPanel.setVisibility(View.VISIBLE);
    }

    private double parseNumber(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            String normalized = value.trim();
            if (normalized.contains(",") && normalized.contains(".")) normalized = normalized.replace(".", "");
            normalized = normalized.replace(',', '.');
            return Double.parseDouble(normalized);
        } catch (Exception ignored) { return 0; }
    }

    private String extractDestination(String text) {
        Matcher m = Pattern.compile("(?:destino|deixar em|drop.?off|para)\\s*[:\\-]?\\s*([^|]{8,100})",
                Pattern.CASE_INSENSITIVE).matcher(text);
        if (!m.find()) return "";
        String value = m.group(1).trim();
        value = value.split("(?:R\\$|\\b[0-9]+(?:[.,][0-9]+)?\\s?km\\b|\\b[0-9]+\\s?min\\b)")[0].trim();
        return value.length() > 90 ? value.substring(0, 90) : value;
    }

    private void openDestinationInMaps() {
        if (currentDestination == null || currentDestination.trim().isEmpty()) return;
        try {
            Uri uri = Uri.parse("https://www.google.com/maps/search/?api=1&query="
                    + Uri.encode(currentDestination));
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            AppLog.add(this, "Não foi possível abrir o destino no Maps: " + e);
        }
    }

    private void evaluate(String pkg, String text) {
        boolean active = containsAny(text, Prefs.startKw(this));
        boolean idle = containsAny(text, Prefs.stopKw(this));
        if (active) {
            if (!RecorderService.recording) {
                AppLog.add(this, "Início de corrida detectado em " + pkg);
                startRecorder();
            } else if (RecorderService.stopPending) {
                RecorderService.cancelStop();
            }
        } else if (idle && RecorderService.recording && !RecorderService.stopPending) {
            AppLog.add(this, "Fim de corrida detectado em " + pkg);
            RecorderService.requestStop(true);
        }
    }

    private void logCalibration(String pkg, String text) {
        if (pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.inputmethod")
                || pkg.startsWith("com.oplus") || pkg.startsWith("com.coloros")
                || pkg.startsWith("com.realme") || pkg.startsWith("com.heytap")) return;
        String key = pkg + text.hashCode();
        if (key.equals(lastCalib)) return;
        lastCalib = key;
        String shown = text.length() > 700 ? text.substring(0, 700) : text;
        AppLog.add(this, "CAL " + pkg + " :: " + shown);
    }

    private boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 25 || sb.length() > 6000) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) sb.append(t).append(" | ");
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) sb.append(d).append(" | ");
        int c = n.getChildCount();
        for (int i = 0; i < c; i++) {
            AccessibilityNodeInfo child = n.getChild(i);
            if (child != null) collect(child, sb, depth + 1);
        }
    }
}
