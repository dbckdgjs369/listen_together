package com.example.lt;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PerformanceHintManager;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import android.app.PendingIntent;

import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 재생 중인 오디오를 AudioPlaybackCapture 로 가로채서
 *  (1) RMS/피크를 로그로 찍고  (검증용: 무음이면 캡처 차단된 앱)
 *  (2) 로컬 HTTP 로 WAV 스트림을 뿌린다.
 */
public class CaptureService extends Service {

    public static final String TAG = "LT";
    public static final int PORT = 7980;
    public static final int BEACON_PORT = 7981;
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";
    public static final String ACTION_STOP = "com.example.lt.STOP";

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int BITS = 16;
    private static final String CHANNEL_ID = "lt_capture";
    private static final int BYTES_PER_SEC = SAMPLE_RATE * CHANNELS * BITS / 8;

    /**
     * 청크 1개 ≈ 0.1초. 50개 = 5초.
     *
     * 링버퍼(3초)보다 반드시 커야 한다. 스레드가 밀렸다 풀리는 순간 read() 가 밀린
     * 3초치를 연달아 뱉는데, 큐가 그보다 작으면 우리가 그걸 버린다 —
     * 링버퍼를 키운 효과가 바로 그 자리에서 사라진다.
     */
    private static final int QUEUE_CHUNKS = 50;
    /** 5초치를 연속으로 버린 클라이언트는 사실상 죽은 것으로 보고 끊는다. */
    private static final int MAX_DROPS = 50;

    private MediaProjection projection;
    private AudioRecord record;
    private AudioTrack keepAlive;
    private ServerSocket server;
    private volatile boolean running;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private final List<Client> clients = new CopyOnWriteArrayList<>();

    /**
     * 청취자 1명. 각자 자기 큐와 자기 송신 스레드를 갖는다.
     *
     * 캡처 스레드가 소켓에 직접 쓰면, 느린 클라이언트 하나 때문에 TCP 송신 버퍼가 차고
     * write() 가 블록되면서 전원의 오디오가 멈춘다. 그래서 캡처 스레드는 큐에 넣기만 하고
     * 절대 블록되지 않는다. 밀리면 그 사람 것만 버린다 — 오디오는 늦은 소리보다
     * 끊긴 소리가 낫다.
     */
    private final class Client {
        final Socket sock;
        final OutputStream out;
        final String who;
        final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CHUNKS);
        volatile boolean alive = true;
        private int drops;
        /** 진단용 누적치. 캡처가 읽은 양과 비교하면 어디서 새는지 드러난다. */
        volatile long sentBytes;
        volatile int droppedChunks;

        Client(Socket sock, OutputStream out) {
            this.sock = sock;
            this.out = out;
            this.who = String.valueOf(sock.getInetAddress());
            new Thread(new Runnable() {
                @Override public void run() { sendLoop(); }
            }, "lt-send-" + who).start();
        }

        /** 캡처 스레드에서 호출된다. 절대 블록되지 않아야 한다. */
        void offer(byte[] chunk) {
            if (!alive) return;
            if (queue.offer(chunk)) { drops = 0; return; }
            queue.poll();                 // 가장 오래된 것을 버리고
            queue.offer(chunk);           // 최신 것을 넣는다 (지연 누적 방지)
            droppedChunks++;
            if (++drops >= MAX_DROPS) {
                Log.w(TAG, "client too slow, dropping: " + who);
                close();
            }
        }

        private void sendLoop() {
            try {
                while (alive) {
                    byte[] chunk = queue.poll(1, TimeUnit.SECONDS);
                    if (chunk == null) continue;
                    out.write(chunk);
                    out.flush();
                    sentBytes += chunk.length;
                }
            } catch (Exception e) {
                if (alive) Log.i(TAG, "client gone: " + who + " (" + e + ")");
            } finally {
                close();
            }
        }

        void close() {
            if (!alive) return;
            alive = false;
            clients.remove(this);
            queue.clear();
            try { out.close(); } catch (Exception ignored) { }
            try { sock.close(); } catch (Exception ignored) { }
            Log.i(TAG, "listener left (" + clients.size() + " left)");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }

        // 1) API 34+ 는 MediaProjection 생성 전에 FGS 가 떠 있어야 한다.
        startForegroundCompat();

        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (data == null) { stopSelf(); return START_NOT_STICKY; }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) {
            Log.e(TAG, "getMediaProjection returned null");
            stopSelf();
            return START_NOT_STICKY;
        }
        // API 34+ 는 캡처 시작 전에 콜백 등록이 필수.
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                Log.w(TAG, "projection stopped by system/user");
                stopSelf();
            }
        }, new Handler(Looper.getMainLooper()));

        running = true;
        acquireLocks();
        startKeepAliveTrack();
        startCapture();
        startServer();
        startBeacon();
        return START_NOT_STICKY;
    }

    /**
     * 화면이 꺼진 채 몇 시간을 버텨야 하므로 두 가지를 잠근다.
     *  - PARTIAL_WAKE_LOCK: CPU 가 깊은 절전에 들어가 캡처 스레드가 멈추는 걸 막는다.
     *  - WifiLock(FULL_LOW_LATENCY): 화면 꺼짐 시 Wi-Fi 칩이 절전으로 가며
     *    지연이 튀거나 소켓이 끊기는 걸 막는다.
     */
    private void acquireLocks() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lt:capture");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();

            WifiManager wm = (WifiManager)
                    getApplicationContext().getSystemService(WIFI_SERVICE);
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "lt:capture");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();

            Log.i(TAG, "locks acquired (wake=" + wakeLock.isHeld()
                    + " wifi=" + wifiLock.isHeld() + ")");
        } catch (Exception e) {
            Log.w(TAG, "lock acquire failed: " + e);
        }
    }

    private void releaseLocks() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) { }
        wifiLock = null;
        wakeLock = null;
    }

    private void startForegroundCompat() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "오디오 캡처", NotificationManager.IMPORTANCE_LOW));
        Intent stop = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        // 알림을 누르면 앱으로 돌아온다. SINGLE_TOP 이라 이미 떠 있으면 새로 만들지 않는다.
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("같이듣기 — 내 소리 공유 중")
                .setContentText("http://" + MainActivity.localIp() + ":" + PORT)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "공유 중지", pi).build())
                .setOngoing(true)
                .build();
        // mediaPlayback 을 함께 선언해 본다. 오디오를 다루는 프로세스로 취급되면
        // 백그라운드 강등이 완화될 여지가 있다. 이 타입은 조건이 맞지 않으면 거부될 수
        // 있으므로, 실패하면 mediaProjection 단독으로 조용히 되돌린다.
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                startForeground(1, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                                | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } catch (Exception e) {
                Log.w(TAG, "mediaPlayback FGS 거부됨, mediaProjection 단독: " + e);
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }
        } else {
            startForeground(1, n);
        }
    }

    /**
     * 무음을 계속 출력해서 우리를 "소리를 내고 있는 앱"으로 만든다.
     *
     * 캡처 스레드가 포그라운드 서비스인데도 cpu:/background 로 강등되는 게 끊김의
     * 원인인데, 버퍼·우선순위·FGS 타입으로는 전부 못 풀었다(실측). 남은 가설은
     * 제조사 정책이 "실제로 오디오를 출력 중인 프로세스"는 다르게 취급한다는 것이다.
     *
     * 출력은 전부 0이라 아무도 못 듣는다. 우리 캡처 믹스에 섞여도 0을 더하는 것뿐이다.
     * 이게 cgroup 을 바꾸지 못하면 미련 없이 걷어내면 된다.
     */
    private void startKeepAliveTrack() {
        try {
            int min = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            keepAlive = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setBufferSizeInBytes(min * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            keepAlive.setVolume(0f);
            keepAlive.play();

            final byte[] zeros = new byte[min];
            new Thread(new Runnable() {
                @Override public void run() {
                    // write() 가 AudioTrack 소비 속도에 맞춰 알아서 블록해준다.
                    while (running) {
                        if (keepAlive.write(zeros, 0, zeros.length) < 0) break;
                    }
                }
            }, "lt-keepalive").start();
            Log.i(TAG, "keepalive 무음 트랙 재생 시작");
        } catch (Exception e) {
            Log.w(TAG, "keepalive 실패(무시하고 진행): " + e);
        }
    }

    private void startCapture() {
        AudioPlaybackCaptureConfiguration.Builder cb =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);

        // addMatchingUid 를 한 번이라도 부르면 화이트리스트가 된다 —
        // 지정하지 않은 앱의 소리는 전혀 들어오지 않는다.
        // 선택이 비어 있으면(=모든 앱 허용) 아무것도 부르지 않는다.
        Set<String> pkgs = MainActivity.loadSelectedApps(this);
        int matched = 0;
        for (String pkg : pkgs) {
            try {
                cb.addMatchingUid(getPackageManager().getPackageUid(pkg, 0));
                matched++;
            } catch (Exception e) {
                Log.w(TAG, "uid 조회 실패, 건너뜀: " + pkg);
            }
        }
        Log.i(TAG, matched == 0
                ? "capture scope = 모든 앱"
                : "capture scope = " + matched + "개 앱 " + pkgs);

        AudioPlaybackCaptureConfiguration config = cb.build();

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();

        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);

        // 링버퍼와 읽는 단위를 분리한다.
        //
        // 링버퍼 3초 — 측정으로 정한 값이다. 우리 캡처 스레드는 포그라운드 서비스인데도
        // 앱이 백그라운드로 가는 순간 cpuset:/moderate + cpu:/background 로 강등된다
        // (실측 확인). 그 상태에서 최근앱 전환 애니메이션이 CPU 를 몰아 쓰면 스레드가
        // 1~2초씩 스케줄을 못 받고, 그동안 링버퍼가 넘쳐 그 구간 오디오가 영구히 사라진다.
        // nice 값(THREAD_PRIORITY_URGENT_AUDIO)은 걸려 있지만 cgroup cpu.shares 가
        // 상위 제약이라 우선순위로는 풀리지 않는다 — 버퍼로 버티는 수밖에 없다.
        // 0.5초로는 부족했고(실측), 3초면 관측된 최대 스톨(2.4초)을 덮는다. 비용은 530KB.
        //
        // 읽는 단위 0.1초: 여유와 무관하게 자주 읽으므로 지연은 그대로다.
        int ringSize = Math.max(minBuf * 2, BYTES_PER_SEC * 3);
        final int chunkSize = BYTES_PER_SEC / 10;

        record = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(ringSize)
                .setAudioPlaybackCaptureConfig(config)
                .build();
        record.startRecording();

        // 요청한 크기를 HAL 이 그대로 주지 않을 수 있다. 실제로 잡힌 값을 확인해야
        // "버퍼를 키웠는데 왜 그대로냐"를 헛짚지 않는다.
        int actualFrames = record.getBufferSizeInFrames();
        int actualMs = actualFrames * 1000 / SAMPLE_RATE;
        Log.i(TAG, "capture started, state=" + record.getRecordingState()
                + " ring요청=" + (ringSize * 1000 / BYTES_PER_SEC) + "ms"
                + " ring실제=" + actualMs + "ms(" + actualFrames + "프레임)"
                + " chunk=" + chunkSize
                + (actualMs < 1000 ? "  <<< 버퍼 요청이 잘렸다" : ""));

        new Thread(new Runnable() {
            @Override public void run() {
                // 오디오 스레드를 UI 애니메이션보다 앞에 둔다.
                // (cgroup 강등은 못 막지만, 같은 그룹 안에서는 여전히 유효하다)
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
                captureLoop(chunkSize);
            }
        }, "lt-capture").start();
    }

    /**
     * 스케줄러에게 "이 스레드는 0.1초마다 깨어나야 한다"고 알린다.
     *
     * cgroup 강등을 우회하는 공식 경로다(ADPF, API 31+). 목표 시간을 넘긴 작업을
     * 보고하면 커널이 주파수·코어 배치를 올려준다. 그래픽용으로 만들어진 API라
     * 오디오에 얼마나 듣는지는 불확실하지만, 실패해도 손해가 없다.
     */
    private PerformanceHintManager.Session createHintSession(long targetNanos) {
        if (Build.VERSION.SDK_INT < 31) return null;
        try {
            PerformanceHintManager phm = getSystemService(PerformanceHintManager.class);
            if (phm == null) return null;
            PerformanceHintManager.Session s = phm.createHintSession(
                    new int[]{ Process.myTid() }, targetNanos);
            Log.i(TAG, s != null ? "perf hint session 등록됨" : "perf hint 미지원");
            return s;
        } catch (Exception e) {
            Log.w(TAG, "perf hint 실패: " + e);
            return null;
        }
    }

    private void captureLoop(int bufSize) {
        byte[] buf = new byte[bufSize];
        long windowSamples = 0;
        double windowSumSq = 0;
        int windowPeak = 0;
        long lastLog = System.nanoTime();

        // 청크 1개 = 0.1초. 이 주기를 지켜야 한다고 스케줄러에게 알린다.
        final long targetNanos = 100_000_000L;
        PerformanceHintManager.Session hint = createHintSession(targetNanos);

        // 무음 채우기용. 한 청크(0.1초)만큼의 0.
        final byte[] silence = new byte[bufSize];
        long filledBytes = 0;

        long readBytes = 0;
        while (running) {
            long iterStart = System.nanoTime();
            int read = record.read(buf, 0, buf.length);
            if (read <= 0) continue;
            readBytes += read;

            // 보내는 게 먼저다. 계측은 그 다음.
            broadcast(buf, read);

            // 스레드가 밀린 만큼 무음을 끼워넣어 스트림의 시간을 보존한다.
            //
            // 사라진 소리는 살릴 수 없다. 하지만 지금은 소리와 함께 '시간'까지
            // 사라져서, 받는 쪽 <audio> 가 언더런으로 재버퍼링에 들어간다.
            // 그래서 1초 구멍이 청취자에게는 몇 초 버벅임이 된다.
            // 빈 만큼 0을 밀어넣으면 1초 구멍은 1초로 끝난다.
            //
            // 누적 기준(스트림 시작 시각)이 아니라 이번 한 바퀴만 본다.
            // 누적으로 하면 샘플레이트 오차 ±2% 가 쌓여 멀쩡할 때도 무음이 끼어든다.
            long iterNanos = System.nanoTime() - iterStart;
            long shortfall = BYTES_PER_SEC * iterNanos / 1_000_000_000L - read;
            if (shortfall > BYTES_PER_SEC / 5) {          // 0.2초 넘게 빈 경우만
                shortfall = Math.min(shortfall, BYTES_PER_SEC * 5);   // 폭주 방지
                int frame = CHANNELS * BITS / 8;
                long fill = shortfall - shortfall % frame;            // 프레임 정렬
                while (fill > 0) {
                    int n = (int) Math.min(fill, silence.length);
                    broadcast(silence, n);
                    filledBytes += n;
                    fill -= n;
                }
            }

            // 검증용 계측이 실시간 경로를 무겁게 만들면 안 된다.
            // 전 샘플(초당 88,200회) 대신 16개마다 하나만 본다 — RMS/피크 판정에는 충분하다.
            ByteBuffer bb = ByteBuffer.wrap(buf, 0, read).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i + 2 <= read; i += 32) {
                int s = bb.getShort(i);
                windowSumSq += (double) s * s;
                windowSamples++;
                int a = s < 0 ? -(s + 1) : s;   // -32768 이 32768 로 튀는 것 방지
                if (a > windowPeak) windowPeak = a;
            }

            long now = System.nanoTime();
            // 한 바퀴가 목표(0.1초)를 넘겼으면 그대로 보고한다 — 넘긴 만큼 부스트를 요청하는 셈이다.
            if (hint != null) {
                try { hint.reportActualWorkDuration(Math.max(1, now - iterStart)); }
                catch (Exception ignored) { }
            }
            if (now - lastLog >= 1_000_000_000L) {
                int rms = windowSamples > 0 ? (int) Math.sqrt(windowSumSq / windowSamples) : 0;
                // 이 간격이 1.0초에서 벌어지면 캡처 스레드가 밀렸다는 뜻 — 끊김의 선행 지표다.
                long gapMs = (now - lastLog) / 1_000_000L;

                // 세 지점을 한 줄에서 비교한다.
                //  cap : 캡처가 실제로 읽은 양 (기대치 대비 %) — 100% 미만이면 링버퍼 유실
                //  net : 클라이언트에게 실제로 보낸 양 — cap 과 같으면 폰 밖으로는 온전히 나감
                //  drop: 우리 큐가 버린 청크 — 0 이 아니면 송신이 못 따라감
                long expected = BYTES_PER_SEC * gapMs / 1000;
                int capPct = expected > 0 ? (int) (readBytes * 100 / expected) : 100;

                StringBuilder net = new StringBuilder();
                for (Client c : clients) {
                    long s = c.sentBytes; c.sentBytes = 0;
                    int d = c.droppedChunks; c.droppedChunks = 0;
                    int netPct = expected > 0 ? (int) (s * 100 / expected) : 100;
                    net.append(String.format("  net=%d%% drop=%d", netPct, d));
                }

                // fill: 우리가 끼워넣은 무음 (cap 이 빈 자리를 메운 양).
                // 이게 생기면 net 은 100% 로 돌아온다 — 소리는 잃었어도 시간은 안 잃었다는 뜻이다.
                int fillPct = expected > 0 ? (int) (filledBytes * 100 / expected) : 0;

                Log.i(TAG, String.format(
                        "AUDIO rms=%5d peak=%5d clients=%d gap=%dms cap=%d%% fill=%d%%%s%s",
                        rms, windowPeak, clients.size(), gapMs, capPct, fillPct,
                        net.toString(),
                        capPct < 95 ? "  <<< 캡처 유실" : ""));
                windowSamples = 0; windowSumSq = 0; windowPeak = 0;
                readBytes = 0;
                filledBytes = 0;
                lastLog = now;
            }
        }
    }

    private void broadcast(byte[] buf, int len) {
        if (clients.isEmpty()) return;
        // 청크를 한 번만 복사해 모두가 공유한다. 아무도 쓰지 않으므로 안전하다.
        byte[] chunk = Arrays.copyOf(buf, len);
        for (Client c : clients) c.offer(chunk);
    }

    private void startServer() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    server = new ServerSocket(PORT);
                    Log.i(TAG, "http server on " + MainActivity.localIp() + ":" + PORT);
                    while (running) {
                        Socket s = server.accept();
                        handleClient(s);
                    }
                } catch (Exception e) {
                    if (running) Log.e(TAG, "server error", e);
                }
            }
        }, "lt-server").start();
    }

    /**
     * 앱을 깐 참여자가 주소를 입력하지 않게, 존재를 1초마다 브로드캐스트한다.
     *
     * 255.255.255.255 로 한 번 보내면 커널이 고른 인터페이스 하나로만 나간다.
     * 핫스팟(swlan0)을 켜면 거기 붙은 참여자에게 안 닿아서 자동 발견이 죽는다.
     * 그래서 인터페이스마다 그 서브넷의 브로드캐스트 주소로 따로 보내고,
     * 비콘에 담는 IP 도 해당 인터페이스의 주소로 맞춘다 — 참여자가 자기가 붙어 있는
     * 망의 주소를 받아야 접속이 된다.
     */
    private void startBeacon() {
        new Thread(new Runnable() {
            @Override public void run() {
                DatagramSocket ds = null;
                try {
                    ds = new DatagramSocket();
                    ds.setBroadcast(true);
                    while (running) {
                        sendBeaconOnAllInterfaces(ds);
                        Thread.sleep(1000);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "beacon: " + e);
                } finally {
                    if (ds != null) ds.close();
                }
            }
        }, "lt-beacon").start();
    }

    private void sendBeaconOnAllInterfaces(DatagramSocket ds) {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress bcast = ia.getBroadcast();      // IPv6 면 null
                    if (bcast == null) continue;
                    InetAddress local = ia.getAddress();
                    if (!(local instanceof Inet4Address)) continue;
                    byte[] msg = ("LT1|" + Build.MODEL + "|" + local.getHostAddress())
                            .getBytes("UTF-8");
                    try {
                        ds.send(new DatagramPacket(msg, msg.length, bcast, BEACON_PORT));
                    } catch (Exception ignored) {
                        // 인터페이스 하나가 막혀도 나머지는 계속 보낸다
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "beacon sweep: " + e);
        }
    }

    private void handleClient(Socket s) {
        try {
            s.setTcpNoDelay(true);
            byte[] tmp = new byte[2048];
            int n = s.getInputStream().read(tmp);
            String req = n > 0 ? new String(tmp, 0, n, "UTF-8") : "";
            String path = "/";
            int sp1 = req.indexOf(' ');
            int sp2 = sp1 >= 0 ? req.indexOf(' ', sp1 + 1) : -1;
            if (sp1 >= 0 && sp2 > sp1) path = req.substring(sp1 + 1, sp2);

            OutputStream out = s.getOutputStream();

            if (!path.startsWith("/stream")) {
                // 참여자용 페이지. 모바일 브라우저는 사용자 제스처가 있어야 재생을 시작한다.
                byte[] html = page().getBytes("UTF-8");
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + html.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes("UTF-8"));
                out.write(html);
                out.flush();
                s.close();
                return;
            }

            String headers = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: audio/wav\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(headers.getBytes("UTF-8"));
            out.write(wavHeader());
            out.flush();
            clients.add(new Client(s, out));
            Log.i(TAG, "listener connected: " + s.getInetAddress() + " (" + clients.size() + ")");
        } catch (Exception e) {
            Log.e(TAG, "client setup failed", e);
        }
    }

    private String page() {
        return "<!doctype html><html lang=ko><head>"
                + "<meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>같이 듣기</title><style>"
                + "body{margin:0;height:100vh;display:flex;flex-direction:column;"
                + "align-items:center;justify-content:center;gap:24px;font-family:-apple-system,"
                + "system-ui,sans-serif;background:#111;color:#eee}"
                + "button{font-size:22px;padding:22px 52px;border:0;border-radius:999px;"
                + "background:#fff;color:#111;font-weight:600}"
                + "#s{font-size:15px;color:#888;min-height:22px}</style></head><body>"
                + "<div style='font-size:19px'>🎧 같이 듣기</div>"
                + "<button id=b>재생</button><div id=s>버튼을 눌러주세요</div>"
                + "<audio id=a playsinline></audio>"
                + "<script>"
                + "var a=document.getElementById('a'),b=document.getElementById('b'),"
                + "s=document.getElementById('s');"
                // want: 사용자가 '듣겠다'고 한 상태. 끊겨도 이게 true 면 계속 재시도한다.
                + "var want=false,tries=0,lastT=-1,lastAt=0,timer=null;"
                + "var OK='재생 중 — 화면을 꺼도 계속 들립니다';"
                + "function connect(){"
                + "s.textContent=tries?('재접속 중… ('+tries+'회)'):'연결 중…';"
                + "a.src='/stream?t='+Date.now()+'-'+tries;"
                + "lastT=-1;lastAt=Date.now();"
                + "a.play().then(function(){tries=0;s.textContent=OK;b.textContent='재생 중';"
                + "if('mediaSession' in navigator){navigator.mediaSession.metadata="
                + "new MediaMetadata({title:'같이 듣기',artist:'호스트의 소리'});}"
                + "}).catch(function(e){s.textContent='재생 실패: '+e.name;retry();});}"
                // 지수적으로는 안 늘리고 최대 5초에서 멈춘다. 여행 중 재접속은 빠를수록 좋다.
                + "function retry(){if(!want||timer)return;tries++;"
                + "timer=setTimeout(function(){timer=null;connect();},Math.min(1000*tries,5000));}"
                + "b.onclick=function(){if(want)return;want=true;tries=0;connect();};"
                + "a.onerror=function(){if(want){s.textContent='스트림 끊김 — 재접속';retry();}};"
                + "a.onended=function(){if(want)retry();};"
                + "a.onstalled=function(){if(want)s.textContent='버퍼링…';};"
                + "a.onplaying=function(){tries=0;s.textContent=OK;};"
                // 감시견: 이벤트가 아예 안 뜬 채 재생만 멈추는 경우(가장 흔한 실패)를 잡는다.
                // currentTime 이 4초간 안 늘면 죽은 것으로 보고 다시 붙는다.
                + "setInterval(function(){if(!want||timer)return;var now=Date.now();"
                + "if(a.currentTime!==lastT){lastT=a.currentTime;lastAt=now;return;}"
                + "if(now-lastAt>4000){s.textContent='멈춤 감지 — 재접속';retry();}},1000);"
                // 화면을 다시 켰을 때 즉시 복구를 시도한다.
                + "document.addEventListener('visibilitychange',function(){"
                + "if(!document.hidden&&want&&a.paused){tries=0;connect();}});"
                + "</script></body></html>";
    }

    /** 길이를 모르는 스트림이므로 크기 필드는 0xFFFFFFFF 로 둔다. */
    private byte[] wavHeader() {
        int byteRate = SAMPLE_RATE * CHANNELS * BITS / 8;
        ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes());
        b.putInt(-1);
        b.put("WAVE".getBytes());
        b.put("fmt ".getBytes());
        b.putInt(16);
        b.putShort((short) 1);          // PCM
        b.putShort((short) CHANNELS);
        b.putInt(SAMPLE_RATE);
        b.putInt(byteRate);
        b.putShort((short) (CHANNELS * BITS / 8));
        b.putShort((short) BITS);
        b.put("data".getBytes());
        b.putInt(-1);
        return b.array();
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) { }
        for (Client c : clients) c.close();
        clients.clear();
        if (record != null) { try { record.stop(); record.release(); } catch (Exception ignored) { } }
        if (keepAlive != null) { try { keepAlive.stop(); keepAlive.release(); } catch (Exception ignored) { } }
        if (projection != null) projection.stop();
        releaseLocks();
        Log.i(TAG, "service destroyed");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
