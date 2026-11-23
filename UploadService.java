package br.motorista.gravador;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.util.List;

public class UploadService extends Service {

    private static volatile boolean running = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("envio", "Envio ao Drive",
                    NotificationManager.IMPORTANCE_LOW));
            Notification n = new Notification.Builder(this, "envio")
                    .setContentTitle("Enviando gravações ao Google Drive")
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true)
                    .build();
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } catch (Exception e) {
            AppLog.add(this, "Android negou o serviço de envio: " + e);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            new Thread(this::work).start();
        }
        return START_NOT_STICKY;
    }

    private boolean networkOk() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        Network n = cm.getActiveNetwork();
        if (n == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        if (nc == null || !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
        if (Prefs.sp(this).getBoolean("wifi_only", false)) {
            return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        }
        return true;
    }

    private void work() {
        try {
            List<String[]> q = UploadQueue.read(this);
            for (String[] item : q) {
                if (!networkOk()) {
                    AppLog.add(this, "Envio adiado: sem rede permitida (pendentes: " + q.size() + ")");
                    break;
                }
                try {
                    String token = DriveAuth.accessToken(this);
                    String folder = DriveUploader.ensureFolder(this, token);
                    Uri u = Uri.parse(item[0]);
                    AppLog.add(this, "Enviando " + item[1] + " ao Drive...");
                    DriveUploader.upload(this, token, folder, u, item[1], item[2]);
                    UploadQueue.remove(this, item[0]);
                    AppLog.add(this, "Enviado ao Drive: " + item[1]);
                    if (Prefs.sp(this).getBoolean("del_after", false)) deleteLocal(u);
                } catch (DriveUploader.EmptyFile ef) {
                    UploadQueue.remove(this, item[0]);
                    AppLog.add(this, "Arquivo vazio ignorado: " + item[1]);
                } catch (FileNotFoundException nf) {
                    UploadQueue.remove(this, item[0]);
                    AppLog.add(this, "Arquivo não existe mais, removido da fila: " + item[1]);
                } catch (Exception ex) {
                    AppLog.add(this, "Falha no envio (fica pendente): " + ex);
                    break;
                }
            }
        } finally {
            running = false;
            new Handler(Looper.getMainLooper()).post(() -> {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        }
    }

    private void deleteLocal(Uri u) {
        try {
            if (DocumentsContract.isDocumentUri(this, u)) {
                DocumentsContract.deleteDocument(getContentResolver(), u);
            } else {
                getContentResolver().delete(u, null, null);
            }
        } catch (Exception ex) {
            AppLog.add(this, "Não consegui apagar o arquivo local: " + ex);
        }
    }
}
