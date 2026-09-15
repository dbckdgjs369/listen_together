package com.example.lt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 호스트의 WAV 스트림을 받아 AudioTrack 으로 바로 재생한다.
 * 브라우저 <audio> 가 잡는 2~5초 버퍼를 피하려고 직접 소켓을 읽는다.
 */
public class PlayerService extends Service {

    public static final String TAG = CaptureService.TAG;
    public static final String EXTRA_HOST = "host";
    private static final String CHANNEL_ID = "lt_player";

    private volatile boolean running;
    private Socket sock;
    private AudioTrack track;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CaptureService.ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        final String host = intent != null ? intent.getStringExtra(EXTRA_HOST) : null;
        if (host == null) { stopSelf(); return START_NOT_STICKY; }

        startForegroundCompat(host);
        acquireLocks();
        running = true;
        new Thread(new Runnable() {
            @Override public void run() { playLoop(host); }
        }, "lt-player").start();
        return START_NOT_STICKY;
    }

    private void startForegroundCompat(String host) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "같이 듣기", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, PlayerService.class).setAction(CaptureService.ACTION_STOP);
        android.app.PendingIntent pi = android.app.PendingIntent.getService(this, 1, stop,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("같이 듣는 중")
                .setContentText(host)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .addAction(new Notification.Action.Builder(null, "나가기", pi).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(2, n);
        }
    }

    /** 참여자도 화면을 끈 채 몇 시간을 듣는다. 호스트와 같은 이유로 CPU·Wi-Fi 를 잠근다. */
    private void acquireLocks() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lt:player");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();

            WifiManager wm = (WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "lt:player");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
            Log.i(TAG, "player locks acquired");
        } catch (Exception e) {
            Log.w(TAG, "player lock acquire failed: " + e);
        }
    }

    private void releaseLocks() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        wifiLock = null;
        wakeLock = null;
    }

    private void playLoop(String host) {
        int minBuf = AudioTrack.getMinBufferSize(
                44100, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(44100)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        track.play();

        while (running) {
            try {
                sock = new Socket();
                sock.connect(new InetSocketAddress(host, CaptureService.PORT), 4000);
                sock.setTcpNoDelay(true);
                sock.getOutputStream().write(
                        ("GET /stream HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n")
                                .getBytes("UTF-8"));
                sock.getOutputStream().flush();

                InputStream in = sock.getInputStream();
                skipHeaders(in);
                skipFully(in, 44);   // WAV 헤더
                Log.i(TAG, "player connected to " + host);

                byte[] buf = new byte[minBuf];
                int read;
                while (running && (read = in.read(buf)) > 0) {
                    track.write(buf, 0, read);
                }
            } catch (Exception e) {
                Log.w(TAG, "player: " + e);
            }
            closeSock();
            if (!running) break;
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            Log.i(TAG, "player reconnecting…");
        }
    }

    /** 응답 헤더 끝(\r\n\r\n)까지 한 바이트씩 넘긴다. */
    private void skipHeaders(InputStream in) throws Exception {
        int state = 0;
        while (state < 4) {
            int c = in.read();
            if (c < 0) throw new Exception("stream closed in headers");
            if ((state == 0 || state == 2) && c == '\r') state++;
            else if ((state == 1 || state == 3) && c == '\n') state++;
            else state = 0;
        }
    }

    private void skipFully(InputStream in, int n) throws Exception {
        int left = n;
        while (left > 0) {
            long s = in.skip(left);
            if (s <= 0) { if (in.read() < 0) throw new Exception("eof"); left--; }
            else left -= s;
        }
    }

    private void closeSock() {
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        sock = null;
    }

    @Override
    public void onDestroy() {
        running = false;
        closeSock();
        if (track != null) { try { track.stop(); track.release(); } catch (Exception ignored) { } }
        releaseLocks();
        Log.i(TAG, "player stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
