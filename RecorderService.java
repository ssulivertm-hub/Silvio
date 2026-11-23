package br.motorista.gravador;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.FileDescriptor;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("deprecation")
public class RecorderService extends Service {

    public static final String ACTION_START = "br.motorista.gravador.START";
    private static final String CHANNEL = "gravacao";
    private static final long STOP_DELAY_MS = 20000L;

    public static volatile boolean recording = false;
    public static volatile boolean stopPending = false;
    private static volatile RecorderService instance = null;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stopRunnable = this::finishNow;

    private Camera camera;
    private SurfaceTexture dummy;
    private MediaRecorder recorder;
    private Uri outUri;
    private ParcelFileDescriptor outPfd;
    private boolean outVideo;
    private boolean outIsDoc;
    private String outName;
    private String outMime;
    private String outUriStr;

    /** Pede parada. delayed=true espera 20 s (pega o desembarque). */
    public static void requestStop(final boolean delayed) {
        final RecorderService s = instance;
        if (s == null) return;
        s.handler.post(() -> s.stopInternal(delayed));
    }

    /** Cancela uma parada agendada (a corrida continua). */
    public static void cancelStop() {
        final RecorderService s = instance;
        if (s == null) return;
        s.handler.post(s::cancelPendingStop);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action) && !recording) {
            beginRecording();
        } else if (!recording) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (recording) {
            try {
                if (recorder != null) recorder.stop();
            } catch (RuntimeException ignored) {
            }
            String u = outUriStr;
            String n = outName;
            String m = outMime;
            releaseAll(false);
            recording = false;
            enqueueUpload(u, n, m, false);
        }
        stopPending = false;
        handler.removeCallbacksAndMessages(null);
        instance = null;
        super.onDestroy();
    }

    private void beginRecording() {
        boolean fgVideo = true;
        try {
            startForegroundCompat(true);
        } catch (Exception e) {
            AppLog.add(this, "Android negou serviço com câmera: " + e);
            fgVideo = false;
            try {
                startForegroundCompat(false);
            } catch (Exception e2) {
                AppLog.add(this, "Android negou serviço com microfone: " + e2);
                stopSelf();
                return;
            }
        }
        int res = Prefs.res(this);
        boolean ok = fgVideo && tryStartVideo(res);
        if (!ok && fgVideo && res > 2) {
            AppLog.add(this, "Resolução pedida falhou; tentando 720p");
            ok = tryStartVideo(2);
        }
        if (!ok) ok = tryStartAudio();
        if (!ok) {
            AppLog.add(this, "Não foi possível iniciar a gravação");
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }
        recording = true;
        stopPending = false;
        handler.postDelayed(stopRunnable, maxDurationMs());
        AppLog.add(this, outVideo ? "GRAVANDO vídeo + áudio" : "GRAVANDO só áudio");
    }

    private long maxDurationMs() {
        int minutes = Prefs.sp(this).getInt("duration_minutes", 180);
        if (minutes != 10 && minutes != 20 && minutes != 30 && minutes != 40
                && minutes != 50 && minutes != 60 && minutes != 120 && minutes != 180) {
            minutes = 180;
        }
        return minutes * 60L * 1000L;
    }

    private void stopInternal(boolean delayed) {
        if (!recording) {
            stopSelf();
            return;
        }
        if (delayed) {
            if (stopPending) return;
            stopPending = true;
            handler.removeCallbacks(stopRunnable);
            handler.postDelayed(stopRunnable, STOP_DELAY_MS);
            AppLog.add(this, "Fim da corrida detectado: parando em 20 s");
        } else {
            finishNow();
        }
    }

    private void cancelPendingStop() {
        if (recording && stopPending) {
            stopPending = false;
            handler.removeCallbacks(stopRunnable);
            handler.postDelayed(stopRunnable, maxDurationMs());
            AppLog.add(this, "Parada cancelada: corrida continua");
        }
    }

    private void finishNow() {
        handler.removeCallbacks(stopRunnable);
        stopPending = false;
        if (recording) {
            try {
                if (recorder != null) recorder.stop();
            } catch (RuntimeException e) {
                AppLog.add(this, "Erro ao finalizar arquivo: " + e);
            }
            String u = outUriStr;
            String n = outName;
            String m = outMime;
            releaseAll(false);
            recording = false;
            AppLog.add(this, "Gravação salva: " + n);
            enqueueUpload(u, n, m, true);
        }
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void enqueueUpload(String uriStr, String name, String mime, boolean startNow) {
        if (uriStr == null || !Prefs.sp(this).getBoolean("drive_on", false)) return;
        UploadQueue.add(this, uriStr, name, mime);
        if (!startNow) return;
        try {
            startForegroundService(new Intent(this, UploadService.class));
        } catch (Exception e) {
            AppLog.add(this, "Envio ao Drive ficou pendente: " + e);
        }
    }

    private void startForegroundCompat(boolean video) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Gravação", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Gravando corrida")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        if (video) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        startForeground(1, n, type);
    }

    private int findCamera(int facing) {
        int n = Camera.getNumberOfCameras();
        for (int i = 0; i < n; i++) {
            Camera.CameraInfo ci = new Camera.CameraInfo();
            Camera.getCameraInfo(i, ci);
            if (ci.facing == facing) return i;
        }
        return -1;
    }

    /** Maior tamanho suportado com área <= pedida; se nenhum, o menor disponível. */
    private Camera.Size pickSize(Camera.Parameters p, int[] want) {
        List<Camera.Size> list = p.getSupportedVideoSizes();
        if (list == null || list.isEmpty()) list = p.getSupportedPreviewSizes();
        if (list == null || list.isEmpty()) return null;
        long wantArea = (long) want[0] * want[1];
        Camera.Size best = null;
        Camera.Size smallest = null;
        for (Camera.Size s : list) {
            long a = (long) s.width * s.height;
            if (smallest == null || a < (long) smallest.width * smallest.height) smallest = s;
            if (a <= wantArea && (best == null || a > (long) best.width * best.height)) best = s;
        }
        return best != null ? best : smallest;
    }

    private Camera.Size pickPreview(Camera.Parameters p, int w, int h) {
        List<Camera.Size> list = p.getSupportedPreviewSizes();
        if (list == null) return null;
        long maxArea = (long) w * h;
        Camera.Size prev = null;
        for (Camera.Size s : list) {
            long a = (long) s.width * s.height;
            boolean sameAspect = (long) s.width * h == (long) s.height * w;
            if (sameAspect && a <= maxArea && (prev == null || a > (long) prev.width * prev.height)) prev = s;
        }
        return prev;
    }

    private boolean tryStartVideo(int resIdx) {
        try {
            boolean back = Prefs.sp(this).getBoolean("back", false);
            int facing = back ? Camera.CameraInfo.CAMERA_FACING_BACK : Camera.CameraInfo.CAMERA_FACING_FRONT;
            int id = findCamera(facing);
            if (id < 0) id = findCamera(back ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK);
            if (id < 0) return false;

            camera = Camera.open(id);
            Camera.Parameters params = camera.getParameters();
            int[] want = Prefs.RES[resIdx];
            Camera.Size best = pickSize(params, want);
            int vw = best != null ? best.width : want[0];
            int vh = best != null ? best.height : want[1];
            try {
                params.setRecordingHint(true);
                Camera.Size prev = pickPreview(params, vw, vh);
                if (prev != null) params.setPreviewSize(prev.width, prev.height);
                camera.setParameters(params);
            } catch (RuntimeException e) {
                AppLog.add(this, "Ajuste de preview ignorado: " + e);
            }

            dummy = new SurfaceTexture(0);
            camera.setPreviewTexture(dummy);
            camera.startPreview();
            camera.unlock();

            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(id, info);

            int vk = Prefs.vk(this);
            int ak = Prefs.ak(this);
            int fps = Prefs.fps(this);

            recorder = new MediaRecorder();
            recorder.setCamera(camera);
            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setVideoSize(vw, vh);
            recorder.setVideoFrameRate(fps);
            recorder.setVideoEncodingBitRate(vk * 1000);
            recorder.setAudioEncodingBitRate(ak * 1000);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioChannels(1);
            recorder.setOrientationHint(info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ? 270 : 90);
            recorder.setOutputFile(openOutput(true));
            recorder.prepare();
            recorder.start();
            AppLog.add(this, "Config: pedido " + want[0] + "x" + want[1] + ", usando " + vw + "x" + vh
                    + " | vídeo " + vk + " kbps | áudio " + ak + " kbps | " + fps + " fps");
            return true;
        } catch (Exception e) {
            AppLog.add(this, "Vídeo falhou (" + e + ")");
            releaseAll(true);
            return false;
        }
    }

    private boolean tryStartAudio() {
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(Prefs.ak(this) * 1000);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioChannels(1);
            recorder.setOutputFile(openOutput(false));
            recorder.prepare();
            recorder.start();
            return true;
        } catch (Exception e) {
            AppLog.add(this, "Áudio falhou: " + e);
            releaseAll(true);
            return false;
        }
    }

    private FileDescriptor openOutput(boolean video) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        outName = "corrida_" + stamp + (video ? ".mp4" : ".m4a");
        outMime = video ? "video/mp4" : "audio/mp4";
        outIsDoc = false;

        String tree = Prefs.sp(this).getString("tree", "");
        if (!tree.isEmpty()) {
            try {
                Uri t = Uri.parse(tree);
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t));
                Uri doc = DocumentsContract.createDocument(getContentResolver(), parent, outMime, outName);
                if (doc != null) {
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(doc, "rw");
                    if (pfd != null) {
                        outUri = doc;
                        outPfd = pfd;
                        outIsDoc = true;
                        outVideo = video;
                        outUriStr = doc.toString();
                        return pfd.getFileDescriptor();
                    }
                    DocumentsContract.deleteDocument(getContentResolver(), doc);
                }
            } catch (Exception e) {
                AppLog.add(this, "Pasta escolhida indisponível (" + e + "); usando a galeria");
            }
        }

        ContentValues v = new ContentValues();
        Uri collection;
        if (video) {
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, outName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, outMime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Corridas");
            collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        } else {
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, outName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, outMime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/Corridas");
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        }
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        outUri = getContentResolver().insert(collection, v);
        if (outUri == null) throw new IOException("MediaStore recusou o arquivo");
        outPfd = getContentResolver().openFileDescriptor(outUri, "rw");
        if (outPfd == null) throw new IOException("Sem descritor de arquivo");
        outVideo = video;
        outUriStr = outUri.toString();
        return outPfd.getFileDescriptor();
    }

    private void releaseAll(boolean discard) {
        if (recorder != null) {
            try {
                recorder.reset();
            } catch (Exception ignored) {
            }
            try {
                recorder.release();
            } catch (Exception ignored) {
            }
            recorder = null;
        }
        if (camera != null) {
            try {
                camera.lock();
            } catch (Exception ignored) {
            }
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
        }
        if (dummy != null) {
            dummy.release();
            dummy = null;
        }
        if (outPfd != null) {
            try {
                outPfd.close();
            } catch (IOException ignored) {
            }
            outPfd = null;
        }
        if (outUri != null) {
            try {
                if (discard) {
                    if (outIsDoc) {
                        DocumentsContract.deleteDocument(getContentResolver(), outUri);
                    } else {
                        getContentResolver().delete(outUri, null, null);
                    }
                } else if (!outIsDoc) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    getContentResolver().update(outUri, v, null, null);
                }
            } catch (Exception ignored) {
            }
            outUri = null;
        }
    }
}
