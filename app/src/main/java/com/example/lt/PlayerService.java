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
import android.media.AudioTimestamp;
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

    /**
     * AudioTrack 버퍼를 minBuf 의 몇 배로 잡을지. **청취자 지연의 지배적 항목이다.**
     *
     * 2배(160ms)일 때 실측 true=217ms 였다. 그 아래 믹서·HAL 은 57ms 뿐이라
     * 줄일 여지는 거의 전부 여기에 있다.
     *
     * 대신 이게 Wi-Fi 가 흔들릴 때 버티는 여유이기도 하다. 줄이면 `under` 가
     * 올라가고, 그건 지연보다 나쁘다. **`under` 가 0 을 유지하는 선까지만 내린다.**
     */
    private static final int BUFFER_MULT = 1;

    /**
     * 지금 듣고 있는 호스트 주소. **null 이면 청취 중이 아니다.**
     * 화면이 이걸 읽어 "지금 누구 소리를 듣고 있는지"를 보여준다.
     *
     * 이게 없던 시절, 죽은 주소를 붙잡은 서비스가 뒤에서 조용히 재시도만 하는데
     * 화면은 아무 말이 없었다. 사용자는 "나가기"를 눌러야 한다는 걸 알 길이 없었다.
     */
    public static volatile String currentHost;
    /** 청취 상태 한 줄. 화면이 그대로 띄운다. */
    public static volatile String stateText = "";

    private volatile boolean running;
    /** 접속 대상. 루프가 매 회차 다시 읽으므로 도중에 갈아끼울 수 있다. */
    private volatile String host;
    private Socket sock;
    private AudioTrack track;
    /** 지금 track 이 물고 있는 샘플레이트. 호스트가 바뀌면 track 을 다시 만든다. */
    private int trackRate;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CaptureService.ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String want = normalizeHost(
                intent != null ? intent.getStringExtra(EXTRA_HOST) : null);
        if (want == null) {
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }

        if (running) {
            // 예전엔 여기서 그냥 무시했다. 그래서 한 번 잘못된 주소로 시작하면
            // 그 뒤로 뭘 눌러도 먹히지 않았다 — 자동 발견 목록을 탭해도, 주소를
            // 다시 입력해도. 실패한 주소를 붙잡은 서비스가 영원히 우선했다.
            if (want.equals(host)) return START_NOT_STICKY;
            Log.i(TAG, "player 호스트 교체: " + host + " → " + want);
            host = want;
            setState("연결 중…");
            startForegroundCompat(want);
            closeSock();     // 읽기를 끊어 루프가 새 주소로 다시 붙게 한다
            return START_NOT_STICKY;
        }

        host = want;
        setState("연결 중…");
        startForegroundCompat(want);
        acquireLocks();
        running = true;
        new Thread(new Runnable() {
            @Override public void run() { playLoop(); }
        }, "lt-player").start();
        return START_NOT_STICKY;
    }

    /**
     * 사용자가 직접 입력하는 건 대개 알림에 뜨는 `http://192.168.0.2:7980` 통째다.
     * 그대로 넘기면 **그런 이름의 서버를 찾다가** UnknownHostException 이 난다 —
     * 포트는 이미 CaptureService.PORT 로 따로 주고 있으니 앞뒤 장식은 방해물이다.
     */
    static String normalizeHost(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        // 콜론이 하나뿐일 때만 포트로 본다. IPv6 주소는 콜론이 여럿이라 건드리면 깨진다.
        int colon = s.indexOf(':');
        if (colon >= 0 && colon == s.lastIndexOf(':')) s = s.substring(0, colon);
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    /** 상태를 한 곳에서만 바꾼다. 화면은 이 두 값만 읽는다. */
    private void setState(String s) {
        stateText = s;
        currentHost = host;
    }

    private void startForegroundCompat(String host) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "같이 듣기", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, PlayerService.class).setAction(CaptureService.ACTION_STOP);
        android.app.PendingIntent pi = android.app.PendingIntent.getService(this, 1, stop,
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        // 알림을 누르면 앱으로 돌아온다.
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent openPi = android.app.PendingIntent.getActivity(this, 0, open,
                android.app.PendingIntent.FLAG_IMMUTABLE
                        | android.app.PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("같이 듣는 중")
                .setContentText(host)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openPi)
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

    private void playLoop() {
        int minBuf = 0;

        while (running) {
            // 매 회차 현재 대상을 다시 읽는다. onStartCommand 가 도중에 바꿔치기하면
            // 소켓이 끊기고 여기로 떨어져, 다음 바퀴에서 새 주소로 붙는다.
            String host = this.host;
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

                // WAV 헤더를 버리지 않고 읽어서 **호스트가 실제로 보내는 샘플레이트**를
                // 쓴다. 44100 을 박아두면 호스트만 48000 으로 올렸을 때 소리가 느려지고,
                // 그 증상으로 원인을 찾기가 고약하다. 버전이 어긋나도 알아서 맞는다.
                byte[] wav = new byte[44];
                readFully(in, wav);
                int rate = le32(wav, 24);
                if (rate < 8000 || rate > 192000) {
                    Log.w(TAG, "WAV 헤더의 샘플레이트가 이상하다(" + rate + ") — 48000 으로 가정");
                    rate = 48000;
                }

                if (track == null || rate != trackRate) {
                    if (track != null) { track.stop(); track.release(); }
                    minBuf = AudioTrack.getMinBufferSize(rate,
                            AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                    track = buildTrack(rate, minBuf);
                    trackRate = rate;
                    track.play();
                }
                Log.i(TAG, "player connected to " + host);
                setState("듣는 중");

                // ── 지연 계측 ──────────────────────────────
                // 우리가 AudioTrack 에 밀어넣은 프레임 수와, 실제로 스피커까지 나간
                // 프레임 수(getPlaybackHeadPosition)의 차이가 곧 플레이어 내부 지연이다.
                // 브라우저 <audio> 는 이 값을 안 열어주기 때문에, 네이티브가 빠르다는
                // 주장을 숫자로 받칠 수 있는 유일한 지점이다.
                long writtenFrames = 0;
                long recvBytes = 0;
                long lastLog = System.nanoTime();
                int headAtStart = track.getPlaybackHeadPosition();
                AudioTimestamp ts = new AudioTimestamp();
                boolean firstChunk = true;
                long connectedAt = System.nanoTime();

                byte[] buf = new byte[minBuf];
                int read;
                while (running && (read = in.read(buf)) > 0) {
                    track.write(buf, 0, read);
                    writtenFrames += read / 4;      // 16bit 스테레오 = 프레임당 4바이트
                    recvBytes += read;

                    if (firstChunk) {
                        firstChunk = false;
                        Log.i(TAG, "PLAY 첫 오디오까지 "
                                + (System.nanoTime() - connectedAt) / 1_000_000L + "ms");
                    }

                    long now = System.nanoTime();
                    if (now - lastLog >= 1_000_000_000L) {
                        long gapMs = (now - lastLog) / 1_000_000L;
                        long played = (track.getPlaybackHeadPosition() - headAtStart)
                                & 0xFFFFFFFFL;
                        // 언더런이 나면 head 가 앞질러 음수가 된다 — 0 으로 눌러 읽는다.
                        long backlogFrames = Math.max(0, writtenFrames - played);
                        long bufMs = backlogFrames * 1000 / rate;
                        long expected = (long) rate * 4 * gapMs / 1000;
                        int netPct = expected > 0 ? (int) (recvBytes * 100 / expected) : 100;
                        int under = Build.VERSION.SDK_INT >= 24 ? track.getUnderrunCount() : -1;

                        // buf 는 **우리 버퍼만** 센다. 그 아래 AudioFlinger 믹서·HAL·
                        // 블루투스 코덱이 더하는 지연은 안 잡힌다 — 블루투스면 거기서만
                        // 100~200ms 가 더 붙는다. buf 만 보고 "지연 150ms" 라고 하면
                        // 과소평가다.
                        //
                        // getTimestamp() 는 "이 프레임이 이 시각에 실제로 나갔다"를 준다.
                        // 지금 쓴 마지막 프레임이 언제 스피커로 나갈지 역산하면 그게 진짜 지연이다.
                        long trueMs = -1;
                        if (track.getTimestamp(ts) && ts.framePosition > 0) {
                            long pending = writtenFrames - ts.framePosition;
                            long presentAt = ts.nanoTime + pending * 1_000_000_000L / rate;
                            trueMs = (presentAt - now) / 1_000_000L;
                        }

                        // buf  : 우리 AudioTrack 에 쌓인 양
                        // true : 스피커까지 나가는 데 걸리는 실제 지연 (-1 = 미지원)
                        // net  : 호스트에게서 받은 양 (실시간 대비)
                        // under: 버퍼가 비어 끊긴 횟수 (누적)
                        Log.i(TAG, String.format(
                                "PLAY buf=%dms true=%dms net=%d%% under=%d gap=%dms",
                                bufMs, trueMs, netPct, under, gapMs));
                        recvBytes = 0;
                        lastLog = now;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "player: " + e);
                // 실패를 화면까지 올린다. 조용히 재시도만 하면 사용자 눈에는
                // "듣는 중" 인데 소리만 안 나는 상태로 보인다. 그게 제일 나쁘다.
                setState(e instanceof java.net.UnknownHostException
                        ? "주소를 찾을 수 없음 — 재시도 중"
                        : "연결 실패 — 재시도 중");
            }
            closeSock();
            if (!running) break;
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            Log.i(TAG, "player reconnecting…");
        }
    }

    /**
     * 고속 경로(fast mixer)를 요청해서 만든다.
     *
     * 조건은 **샘플레이트가 기기 네이티브와 일치**하는 것이다. 44100 이던 시절엔
     * 리샘플링이 걸려 거부됐다(mode=0 실측). 호스트를 48000 으로 올린 이유가 이거다.
     * 허용 여부는 로그의 `고속경로=` 로 바로 판정된다 — 요청값을 믿지 않는다.
     */
    private AudioTrack buildTrack(int rate, int minBuf) {
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(minBuf * BUFFER_MULT)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();

        AudioManager am = getSystemService(AudioManager.class);
        String nativeRate = am != null
                ? am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) : "?";
        String nativeBurst = am != null
                ? am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) : "?";
        int mode = t.getPerformanceMode();
        int frames = t.getBufferSizeInFrames();
        Log.i(TAG, "player track: 버퍼요청=" + (minBuf * BUFFER_MULT / 4 * 1000 / rate) + "ms"
                + " 실제=" + (frames * 1000 / rate) + "ms(" + frames + "프레임)"
                + " 고속경로=" + (mode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                        ? "허용됨" : "거부됨(mode=" + mode + ")")
                + " 기기네이티브=" + nativeRate + "Hz/" + nativeBurst + "프레임"
                + " 스트림=" + rate + "Hz");
        return t;
    }

    /** n 바이트를 다 채울 때까지 읽는다. read() 가 한 번에 다 준다는 보장이 없다. */
    private void readFully(InputStream in, byte[] b) throws Exception {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new Exception("eof in wav header");
            off += n;
        }
    }

    /** WAV 헤더는 리틀엔디안이다. */
    private int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8
                | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
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
        currentHost = null;
        stateText = "";
        closeSock();
        if (track != null) { try { track.stop(); track.release(); } catch (Exception ignored) { } }
        releaseLocks();
        Log.i(TAG, "player stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
