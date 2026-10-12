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
    public static final String EXTRA_ROOM_NAME = "roomName";
    public static final String ACTION_STOP = "com.example.lt.STOP";

    /**
     * 방 이름 최대 길이.
     *
     * 수신 버퍼가 256바이트인데 한글은 UTF-8 로 글자당 3바이트다. 20자면 60바이트라
     * 나머지 필드(ID·IP·잠금)를 다 합쳐도 한참 남는다. 길이를 안 자르면 긴 이름
     * 하나가 패킷을 넘겨서 **그 방만 조용히 안 보이는** 상태가 된다.
     */
    private static final int MAX_ROOM_NAME = 20;
    /** 비밀번호를 이 횟수만큼 틀린 주소는 방이 끝날 때까지 받지 않는다. */
    private static final int PW_MAX_FAILS = 10;
    /**
     * 이보다 작은, 끝이 정해진 Range 요청은 "떠보기"로 보고 딱 그만큼만 준다.
     * 사파리의 첫 요청이 `bytes=0-1` 이다. 그 이상은 스트림으로 취급한다.
     */
    private static final long PROBE_MAX = 64 * 1024;

    /**
     * 기기 네이티브 샘플레이트에 맞춘다. 요즘 안드로이드 기기는 거의 다 48000 이다.
     *
     * 44100 이던 시절엔 캡처·재생 양쪽에서 AudioFlinger 가 리샘플링을 했고,
     * 그 때문에 청취자가 **고속 경로(fast mixer)에서 탈락**했다(`mode=0` 실측).
     * 44100 을 쓸 이유는 애초에 없었다 — CD 시절 관성이다.
     */
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int BITS = 16;
    private static final String CHANNEL_ID = "lt_capture";
    private static final int BYTES_PER_SEC = SAMPLE_RATE * CHANNELS * BITS / 8;

    /**
     * 캡처를 읽어 보내는 단위. **이게 곧 청취자 지연의 한 축이다.**
     *
     * 0.1초였을 때 총 지연이 약 250ms 였고, 그중 100ms 가 여기였다.
     * 20ms 로 줄이면 그만큼 바로 깎인다. 대신 같은 시간에 패킷이 5배로 늘어난다.
     */
    private static final int CHUNK_MS = 20;

    /**
     * 큐는 **시간**으로 5초를 유지한다. 개수로 고정하면 안 된다 —
     * CHUNK_MS 를 줄이는 순간 큐가 같이 쪼그라들어 링버퍼(3초)보다 작아진다.
     *
     * 링버퍼보다 반드시 커야 한다. 스레드가 밀렸다 풀리는 순간 read() 가 밀린
     * 3초치를 연달아 뱉는데, 큐가 그보다 작으면 우리가 그걸 버린다 —
     * 링버퍼를 키운 효과가 바로 그 자리에서 사라진다.
     */
    private static final int QUEUE_CHUNKS = 5000 / CHUNK_MS;
    /** 5초치를 연속으로 버린 클라이언트는 사실상 죽은 것으로 보고 끊는다. */
    private static final int MAX_DROPS = QUEUE_CHUNKS;

    private MediaProjection projection;
    private AudioRecord record;
    private AudioTrack keepAlive;
    private ServerSocket server;
    private volatile boolean running;

    /**
     * 공유를 끈 뒤에도 서버를 이만큼은 남겨 두고 모든 요청에 410 으로 답한다.
     *
     * 서버를 바로 닫으면 웹 청취자는 "호스트가 끝냈다"와 "내 Wi-Fi 가 잠깐 끊겼다"를
     * 구분하지 못한다 — 둘 다 그냥 연결 실패라서 영원히 재접속만 한다. 410 은 서버가
     * 직접 한 대답이라 네트워크 끊김일 수가 없다. 끊긴 스트림을 본 페이지는 곧바로
     * /check 를 물어보므로 몇 초면 충분하다.
     */
    private static final long FAREWELL_MS = 5000;
    /** 작별 인사 중인 서버. 그 사이 공유를 다시 켜면 포트를 넘겨받으려고 먼저 닫는다. */
    private static volatile ServerSocket lingering;
    private volatile long farewellUntil;

    /**
     * 공유 중인가. 화면이 버튼 라벨("공유 시작"/"공유 중지")을 정하는 데 쓴다.
     *
     * getRunningServices() 로 매번 물어볼 수도 있지만 그건 전체 서비스 목록을 훑는
     * 호출이라 0.5초마다 때릴 물건이 아니다. PlayerService.currentHost 와 같은 방식이다.
     */
    public static volatile boolean sharing;
    /** 지금 듣고 있는 사람 수. 호스트 화면에 그대로 띄운다. */
    public static volatile int listeners;

    /**
     * 방 하나 = 공유 한 번. 세 값 다 공유를 시작할 때 새로 정해지고 중지하면 버린다.
     *
     * roomId 가 따로 있는 이유는 **한 방이 인터페이스 수만큼 비콘을 쏘기 때문**이다.
     * Wi-Fi 와 핫스팟이 같이 켜져 있으면 같은 방이 주소만 다른 두 줄로 목록에 뜬다.
     * 받는 쪽이 이 ID 로 묶어야 한 줄이 된다.
     */
    public static volatile String roomId = "";
    /** 목록에 뜨는 이름. 호스트가 정하고, 비워두면 기기 모델명을 쓴다. */
    public static volatile String roomName = "";
    /** 입장 비밀번호 4자리. 공유할 때마다 새로 뽑는다. */
    public static volatile String roomPw = "";

    /**
     * 비밀번호를 틀린 주소 → {누적 실패 횟수, 다음 시도 허용 시각(ms)}.
     *
     * 4자리는 1만 가지뿐이라 스크립트로 몇 분이면 전부 훑을 수 있다. 사람이 손으로
     * 오타 내는 건 그대로 두고 자동 대입만 못 하게, 틀릴 때마다 다음 시도까지의
     * 대기를 2배씩 늘린다(최대 8초). 응답을 붙잡고 자는 게 아니라 **거절을 즉시
     * 돌려주므로** 공격자가 스레드를 쌓아 올릴 수도 없다.
     */
    private final java.util.Map<String, long[]> pwFails =
            new java.util.concurrent.ConcurrentHashMap<>();

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
            listeners = clients.size();
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

        // 방을 먼저 연다. 알림이 비밀번호를 싣고, 비콘이 이름과 잠금 상태를 싣는다 —
        // 둘 다 이 값들이 이미 정해져 있어야 한다. 순서가 뒤바뀌면 첫 알림에 빈칸이
        // 뜨거나 첫 비콘이 이름 없이 나간다.
        roomName = sanitizeRoomName(intent.getStringExtra(EXTRA_ROOM_NAME));
        roomId = newRoomId();
        roomPw = newPw();
        pwFails.clear();
        Log.i(TAG, "방 열림 id=" + roomId + " 이름=" + roomName);

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
     * 방 이름을 비콘에 실을 수 있는 모양으로 다듬는다.
     *
     * `|` 를 반드시 걷어내야 한다. 비콘이 `|` 로 필드를 나누는데 이름에 그게 섞이면
     * 받는 쪽 split 이 한 칸씩 밀려서 **IP 자리에 이름 조각이 들어간다.** 길이 검사는
     * 통과하므로 목록에는 멀쩡히 뜨고, 누르면 그런 주소를 찾다가 조용히 실패한다.
     * 원본이 normalizeHost() 로 30분을 태웠던 것과 같은 종류의 함정이다.
     */
    static String sanitizeRoomName(String raw) {
        String s = raw == null ? "" : raw.trim();
        s = s.replace('|', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        if (s.isEmpty()) s = Build.MODEL;
        if (s.length() > MAX_ROOM_NAME) s = s.substring(0, MAX_ROOM_NAME).trim();
        return s;
    }

    /** 방 식별자 6자리 16진수. 사람이 볼 일은 없고 목록에서 같은 방을 묶는 데만 쓴다. */
    private static String newRoomId() {
        byte[] b = new byte[3];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(java.util.Locale.US, "%02x", x));
        return sb.toString();
    }

    /**
     * 입장 비밀번호 4자리.
     *
     * 호스트가 직접 정하게 두지 않는 이유는 사람이 고르면 `1234`·`0000` 으로 몰리기
     * 때문이다. 불러주기만 하면 되는 값이라 외울 필요도 없다.
     */
    private static String newPw() {
        return String.format(java.util.Locale.US, "%04d",
                new java.security.SecureRandom().nextInt(10000));
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

        // 접힌 알림은 한 줄뿐이라 대표 주소만, 펼치면 전부 보인다.
        // 핫스팟과 Wi-Fi 가 같이 떠 있으면 친구가 어느 쪽에 있는지 우리는 모른다.
        java.util.List<String> ips = MainActivity.localIps();
        StringBuilder all = new StringBuilder();
        for (String ip : ips) {
            if (all.length() > 0) all.append('\n');
            all.append("http://").append(ip).append(':').append(PORT);
        }
        if (all.length() == 0) all.append("네트워크 없음");

        // 비밀번호를 알림에 싣는다. 친구한테 불러주려고 매번 앱을 열게 하지 않는다 —
        // 화면을 끄고 주머니에 넣은 뒤에 늦게 합류하는 사람이 생기는 게 흔한 경우다.
        String pwLine = roomPw.isEmpty() ? "" : "비밀번호 " + roomPw + "\n";
        // 맥·PC 용. 코드 하나에 주소와 비밀번호가 다 들어 있다.
        String code = RoomCode.encode(MainActivity.localIp(), roomPw);
        String codeLine = code == null ? "" : "방 코드 " + code + "  (" + RoomCode.SITE + ")\n";

        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("이음 — " + roomName)
                .setContentText(roomPw.isEmpty() ? "http://" + MainActivity.localIp() + ":" + PORT
                        : "비밀번호 " + roomPw + " · " + MainActivity.localIp())
                .setStyle(new Notification.BigTextStyle().bigText(
                        pwLine + codeLine
                                + (ips.size() > 1 ? "되는 것으로 하나 골라 접속:\n" + all : all.toString())))
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

        // 앱별 화이트리스트(addMatchingUid)는 걷어냈다. 체크박스 11개를 세워두고
        // "여기서 고른 것만 나갑니다"라고 설명하는 비용이, 그걸로 얻는 것보다 컸다.
        // 어차피 USAGE_MEDIA/GAME 필터가 알림음·통화를 이미 막고 있어서
        // 실제로 새어나갈 게 음악·영상뿐이다.
        //
        // addMatchingUid 는 **한 번이라도 부르면** 화이트리스트가 되므로,
        // 지금처럼 아무것도 부르지 않는 것이 "모든 앱 허용"이다.
        Log.i(TAG, "capture scope = 모든 앱 (USAGE_MEDIA/GAME/UNKNOWN)");

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
        // 링버퍼 크기와 읽는 단위는 별개다. 링은 "밀렸을 때 버틸 여유",
        // 청크는 "평소 지연". 그래서 링은 크게, 청크는 작게 간다.
        int ringSize = Math.max(minBuf * 2, BYTES_PER_SEC * 3);
        final int chunkSize = BYTES_PER_SEC * CHUNK_MS / 1000;

        record = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(ringSize)
                .setAudioPlaybackCaptureConfig(config)
                .build();
        record.startRecording();
        sharing = true;

        // 요청한 크기를 HAL 이 그대로 주지 않을 수 있다. 실제로 잡힌 값을 확인해야
        // "버퍼를 키웠는데 왜 그대로냐"를 헛짚지 않는다.
        int actualFrames = record.getBufferSizeInFrames();
        int actualMs = actualFrames * 1000 / SAMPLE_RATE;
        Log.i(TAG, "capture started, state=" + record.getRecordingState()
                + " ring요청=" + (ringSize * 1000 / BYTES_PER_SEC) + "ms"
                + " ring실제=" + actualMs + "ms(" + actualFrames + "프레임)"
                + " chunk=" + CHUNK_MS + "ms(" + chunkSize + "B)"
                + " queue=" + QUEUE_CHUNKS + "개=" + (QUEUE_CHUNKS * CHUNK_MS) + "ms"
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
     * 스케줄러에게 "이 스레드는 CHUNK_MS 마다 깨어나야 한다"고 알린다.
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

        // 청크 1개를 읽는 주기를 지켜야 한다고 스케줄러에게 알린다.
        final long targetNanos = CHUNK_MS * 1_000_000L;
        PerformanceHintManager.Session hint = createHintSession(targetNanos);

        // 무음 채우기용. 한 청크만큼의 0.
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
            if (hint != null && Build.VERSION.SDK_INT >= 31) {
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
                    ServerSocket old = lingering;
                    if (old != null) { lingering = null; try { old.close(); } catch (Exception ignored) { } }
                    server = new ServerSocket();
                    server.setReuseAddress(true);
                    server.bind(new java.net.InetSocketAddress(PORT));
                    Log.i(TAG, "http server on " + MainActivity.localIp() + ":" + PORT);
                    while (running || System.currentTimeMillis() < farewellUntil) {
                        final Socket s = server.accept();
                        // 받는 스레드에서 직접 처리하면 안 된다. handleClient 는 요청을
                        // 읽으려고 블로킹하는데, 브라우저는 주소창에 입력하는 순간
                        // **TCP 만 미리 열어두고(preconnect) 요청은 나중에** 보낸다.
                        // 그 빈 연결 하나가 뒤의 모든 접속을 줄 세웠다 — 브라우저로
                        // 들어가면 페이지가 한참 뒤에야 뜨던 원인이 이것이다.
                        new Thread(new Runnable() {
                            @Override public void run() { handleClient(s); }
                        }, "lt-client").start();
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
                    // LT2|방ID|방이름|주소|잠김
                    //
                    // 비밀번호 자체는 절대 싣지 않는다 — 비콘은 서브넷 전체가 듣는다.
                    // 잠김 여부만 알려서 목록에 자물쇠를 띄우게 한다. 주소를 계속
                    // 뿌려도 되는 건 이제 문에 자물쇠가 달렸기 때문이다.
                    byte[] msg = ("LT2|" + roomId + "|" + roomName + "|"
                            + local.getHostAddress() + "|" + (roomPw.isEmpty() ? "0" : "1"))
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
            // 붙어놓고 아무 말 없는 연결을 영원히 물고 있지 않는다. 스레드를 나눠도
            // 이게 없으면 그런 연결이 스레드째 쌓인다. 요청 한 줄 보내는 데 5초면
            // 넉넉하다 — 같은 랜 안이라 왕복이 밀리초 단위다.
            s.setSoTimeout(5000);
            byte[] tmp = new byte[2048];
            int n = s.getInputStream().read(tmp);
            String req = n > 0 ? new String(tmp, 0, n, "UTF-8") : "";
            String path = "/";
            int sp1 = req.indexOf(' ');
            int sp2 = sp1 >= 0 ? req.indexOf(' ', sp1 + 1) : -1;
            if (sp1 >= 0 && sp2 > sp1) path = req.substring(sp1 + 1, sp2);

            OutputStream out = s.getOutputStream();
            String peer = String.valueOf(s.getInetAddress().getHostAddress());

            // 공유를 끈 뒤 작별 인사 중. 비밀번호도 이미 버렸으니 무엇을 물어도 '끝났다'.
            if (!running) {
                byte[] body = "호스트가 공유를 종료했어요.".getBytes("UTF-8");
                out.write(("HTTP/1.1 410 Gone\r\n"
                        + "Content-Type: text/plain; charset=utf-8\r\n"
                        + "Content-Length: " + body.length + "\r\n"
                        + "Connection: close\r\n\r\n").getBytes("UTF-8"));
                out.write(body);
                out.flush();
                s.close();
                return;
            }

            // 비밀번호만 확인하고 끊는 창구. 브라우저 전용이다.
            //
            // <audio> 는 실패해도 **HTTP 상태 코드를 안 알려준다.** 그래서 페이지가
            // "비밀번호가 틀림" 과 "Wi-Fi 가 끊김" 을 구분하지 못하고, 틀린 비밀번호로
            // 영원히 재접속하면서 '재접속 중…' 만 띄운다. 그 둘을 가르려고 둔다.
            if (path.startsWith("/check")) {
                int deny = checkPw(peer, param(path, "pw"));
                if (deny != 0) writeDenied(out, deny);
                else out.write(("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
                        .getBytes("UTF-8"));
                out.flush();
                s.close();
                return;
            }

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

            // ★검사는 반드시 여기다. 목록 화면에만 비밀번호를 걸면 아무 의미가 없다 —
            //   브라우저에 http://<주소>:7980/stream 을 그대로 치면 그냥 나오기 때문이다.
            //   소리가 나가는 길은 이 한 곳뿐이라, 자물쇠도 여기 하나만 달면 된다.
            int deny = checkPw(peer, param(path, "pw"));
            if (deny != 0) {
                Log.w(TAG, "입장 거부 " + peer + " → " + deny);
                writeDenied(out, deny);
                out.flush();
                s.close();
                return;
            }

            // 사파리는 미디어를 받을 때 `Range: bytes=0-` 를 보내고 206 을 기대하는
            // 성향이 강하다. 끝이 없는 스트림이라 진짜 길이는 없지만, 아주 큰 값을
            // 총 길이로 적어주면 "받다 만 파일"로 취급해 계속 재생한다.
            // iOS 판 서버(ios/LTBroadcast/Server.swift)에 먼저 넣었던 처리를 옮겨온 것.
            //
            // ★요청한 범위를 **그대로** 돌려줘야 한다. 사파리는 본 요청 전에
            //   `bytes=0-1` 로 2바이트만 떠보는데, 거기에 `0-(huge-1)` 을 끝없이
            //   흘려보내면 응답이 요청과 안 맞아 <audio> 가 에러를 낸다.
            //   그래서 끝이 정해진 작은 요청은 "WAV 헤더 + 무음" 으로 된 가상 파일에서
            //   정확히 그만큼 잘라주고 끊는다. 청취자로 세지 않는다.
            long huge = 0x7FFFFFFFFFFFL;
            long[] range = parseRange(req);          // null = Range 없음
            byte[] wav = wavHeader();
            long start = range == null ? 0 : Math.min(range[0], huge - 1);
            long end = range == null ? -1 : range[1];

            if (range != null && end >= start && end - start < PROBE_MAX) {
                int len = (int) (end - start + 1);
                byte[] body = new byte[len];         // 헤더 뒤는 0 = 무음
                for (int i = 0; i < len && start + i < wav.length; i++) {
                    body[i] = wav[(int) (start + i)];
                }
                out.write(("HTTP/1.1 206 Partial Content\r\n"
                        + "Content-Range: bytes " + start + "-" + end + "/" + huge + "\r\n"
                        + "Content-Length: " + len + "\r\n"
                        + "Content-Type: audio/wav\r\n"
                        + "Accept-Ranges: bytes\r\n"
                        + "Cache-Control: no-cache\r\n"
                        + "Connection: close\r\n\r\n").getBytes("UTF-8"));
                out.write(body);
                out.flush();
                s.close();
                Log.i(TAG, "range probe " + peer + " bytes=" + start + "-" + end);
                return;
            }

            boolean hasRange = range != null;
            String headers = (hasRange
                    ? "HTTP/1.1 206 Partial Content\r\n"
                      + "Content-Range: bytes " + start + "-" + (huge - 1) + "/" + huge + "\r\n"
                    : "HTTP/1.1 200 OK\r\n")
                    + "Content-Type: audio/wav\r\n"
                    + "Accept-Ranges: bytes\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(headers.getBytes("UTF-8"));
            if (start < wav.length) {
                out.write(wav, (int) start, wav.length - (int) start);
            } else {
                // 헤더 뒤 중간부터 달라고 하면 헤더는 빼고 PCM 만 보낸다. 받는 쪽은
                // 오프셋으로 프레임 경계를 계산하므로, 경계가 맞게 0 을 채워 맞춘다.
                int frame = CHANNELS * BITS / 8;
                int pad = (int) ((frame - (start - wav.length) % frame) % frame);
                if (pad > 0) out.write(new byte[pad]);
            }
            out.flush();
            clients.add(new Client(s, out));
            listeners = clients.size();
            // 206 인지를 로그에 남긴다. 아이폰이 붙었을 때 Range 를 실제로 보냈는지는
            // 귀로는 알 수 없고, 이 한 글자가 있어야 로그로 판정할 수 있다.
            Log.i(TAG, "listener connected: " + s.getInetAddress() + " (" + clients.size() + ")"
                    + (hasRange ? " range=206" : " range=없음"));
        } catch (java.net.SocketTimeoutException e) {
            // 요청을 안 보내고 열어만 둔 연결. 브라우저 preconnect 가 대표적이라
            // 정상 동작에 가깝다 — 에러로 시끄럽게 굴 필요 없이 조용히 닫는다.
            Log.i(TAG, "요청 없는 연결을 닫음: " + s.getInetAddress());
            try { s.close(); } catch (Exception ignored) { }
        } catch (Exception e) {
            Log.e(TAG, "client setup failed", e);
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 입장 비밀번호 검사. **0 이면 통과**, 아니면 그대로 돌려줄 HTTP 상태 코드다.
     *
     * 실패한 주소를 점점 더 오래 기다리게 만든다(1→2→4→8초). 사람이 두어 번 오타
     * 내는 건 체감이 없고, 초당 수십 번 때리는 스크립트는 여기서 멈춘다.
     * 10번 틀리면 방이 끝날 때까지 그 주소를 안 받는다 — 방은 몇 시간짜리라
     * 영구 차단이 아니고, 잘못 막혀도 호스트가 공유를 다시 시작하면 풀린다.
     */
    private int checkPw(String peer, String given) {
        if (roomPw.isEmpty()) return 0;               // 잠그지 않은 방

        // ★맞는 비밀번호는 **무조건 먼저** 통과시킨다. 지연보다 위에 둬야 한다.
        //   지연을 먼저 보면, 한 번 오타 낸 사람이 곧바로 제대로 고쳐 넣어도
        //   "시도가 너무 많습니다" 를 받는다 — 본인은 방금 맞게 쳤는데.
        //   무차별 대입을 실제로 막는 건 지연이 아니라 아래의 10회 상한이다.
        //   지연은 속도만 늦출 뿐이고, 상한이 시도 횟수 자체를 1만분의 10으로 묶는다.
        if (roomPw.equals(given)) {
            pwFails.remove(peer);                     // 맞혔으면 과거는 없던 일로
            return 0;
        }

        long now = System.currentTimeMillis();
        long[] st = pwFails.get(peer);
        if (st != null) {
            if (st[0] >= PW_MAX_FAILS) return 429;
            if (now < st[1]) return 429;
        }

        // ★첫 실패에는 지연을 안 준다. 재생 페이지가 실패 직후 /check 로 이유를
        //   되묻는데(<audio> 가 상태 코드를 안 주므로), 여기서 바로 잠그면 그 질문이
        //   429 를 받아서 **틀린 비밀번호가 '시도 초과' 로 둔갑한다.**
        //   공격자가 얻는 건 공짜 시도 한 번뿐이고, 상한은 그대로 10회다.
        long fails = st == null ? 1 : st[0] + 1;
        long wait = fails <= 1 ? 0 : Math.min(1000L << Math.min(fails - 2, 3), 8000L);
        pwFails.put(peer, new long[]{fails, now + wait});
        return 401;
    }

    private void writeDenied(OutputStream out, int code) throws Exception {
        String reason = code == 401 ? "Unauthorized" : "Too Many Requests";
        byte[] body = (code == 401
                ? "비밀번호가 맞지 않습니다."
                : "시도가 너무 많습니다. 잠시 후 다시 해주세요.").getBytes("UTF-8");
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(body);
    }

    /**
     * `Range: bytes=시작-끝` 을 {시작, 끝} 으로. 끝이 없으면 -1, Range 가 없거나
     * 못 읽으면 null. `bytes=-500` 같은 끝에서부터 요청은 끝이 없는 스트림이라
     * 의미가 없으므로 처음부터로 본다.
     */
    private static long[] parseRange(String req) {
        for (String line : req.split("\r\n")) {
            int c = line.indexOf(':');
            if (c < 0 || !line.substring(0, c).trim().equalsIgnoreCase("range")) continue;
            String v = line.substring(c + 1).trim().toLowerCase(java.util.Locale.US);
            if (!v.startsWith("bytes=")) return null;
            v = v.substring(6);
            int comma = v.indexOf(',');
            if (comma >= 0) v = v.substring(0, comma);   // 여러 구간은 첫 구간만
            int dash = v.indexOf('-');
            if (dash < 0) return null;
            try {
                String a = v.substring(0, dash).trim(), b = v.substring(dash + 1).trim();
                if (a.isEmpty()) return new long[]{0, -1};
                return new long[]{Long.parseLong(a), b.isEmpty() ? -1 : Long.parseLong(b)};
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * `/stream?t=1730-0&pw=8317` 에서 값 하나를 꺼낸다. 없으면 빈 문자열.
     *
     * 재생 페이지가 캐시를 피하려고 `t=` 를 이미 붙이고 있어서, 경로를 통째로
     * 비교하던 예전 방식으로는 `pw` 를 읽을 수 없다.
     */
    private static String param(String path, String key) {
        int q = path.indexOf('?');
        if (q < 0) return "";
        for (String kv : path.substring(q + 1).split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) {
                try {
                    return java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return "";
                }
            }
        }
        return "";
    }

    /** 방 이름은 호스트가 적은 글이라 그대로 HTML 에 넣으면 태그가 먹는다. */
    private static String html(String t) {
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /**
     * 브라우저 참여자용 재생 페이지. 앱 '듣는 중' 화면과 같은 색·모양으로 맞춘다.
     *
     * 호스트 핫스팟에만 붙은 기기는 인터넷이 없으므로 글꼴·스크립트를 밖에서 받지 않는다.
     * 전부 이 문자열 하나에 들어 있어야 한다.
     */
    private String page() {
        return PAGE.replace("%ROOM%", html(roomName.isEmpty() ? "이음" : roomName));
    }

    private static final String PAGE = """
<!doctype html><html lang=ko><head>
<meta charset=utf-8>
<meta name=viewport content='width=device-width,initial-scale=1,viewport-fit=cover'>
<meta name=theme-color content='#07080C'>
<title>이음</title><style>
:root{--bg:#07080C;--surface:#171A22;--stroke:rgba(255,255,255,.08);--text:#F2F4F8;--dim:#9AA3B6;--faint:#5B6376;
--accent:#6C5CE7;--lav:#B7ACFF;--live:#3DDC97;--danger:#FF7A7A}
*{box-sizing:border-box;margin:0;padding:0}
html,body{min-height:100%}
body{background:var(--bg);color:var(--text);font-family:-apple-system,system-ui,'Apple SD Gothic Neo',sans-serif;
-webkit-font-smoothing:antialiased;display:flex;flex-direction:column;min-height:100vh;min-height:100dvh;overflow-x:hidden;
padding:env(safe-area-inset-top) 22px calc(24px + env(safe-area-inset-bottom))}
/* 천천히 흐르는 오로라. 흐림 필터 대신 방사형 그라데이션이라 폰에서도 가볍다 */
.aura{position:fixed;inset:0;z-index:-2;overflow:hidden;background:radial-gradient(120% 70% at 50% -10%,#16163A 0%,var(--bg) 62%)}
.aura i{position:absolute;border-radius:50%;will-change:transform}
.aura .a1{width:90vmax;height:90vmax;left:-40vmax;top:-35vmax;background:radial-gradient(circle,rgba(108,92,231,.55),transparent 62%);animation:d1 19s ease-in-out infinite alternate}
.aura .a2{width:80vmax;height:80vmax;right:-45vmax;top:20vh;background:radial-gradient(circle,rgba(45,108,223,.38),transparent 62%);animation:d2 23s ease-in-out infinite alternate}
.aura .a3{width:70vmax;height:70vmax;left:-10vmax;bottom:-45vmax;background:radial-gradient(circle,rgba(61,220,151,.22),transparent 62%);animation:d3 27s ease-in-out infinite alternate}
@keyframes d1{to{transform:translate(18vw,12vh) scale(1.15)}}
@keyframes d2{to{transform:translate(-16vw,-8vh) scale(1.1)}}
@keyframes d3{to{transform:translate(14vw,-10vh) scale(1.2)}}
/* 필름 입자. 매끈한 그라데이션에 종이 같은 질감을 준다 */
.grain{position:fixed;inset:-50%;z-index:-1;pointer-events:none;opacity:.08;
background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='180' height='180'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.85' numOctaves='2' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E")}
.top{height:60px;display:flex;align-items:center;flex:none}
.brand{font-size:21px;font-weight:700;letter-spacing:-.02em;display:flex;align-items:center}
/* 音 은 도장처럼 — 이름의 두 번째 뜻을 낙관 찍듯 곁들인다 */
.seal{display:inline-grid;place-items:center;width:22px;height:22px;margin-left:8px;border-radius:6px;
border:1.5px solid rgba(183,172,255,.75);color:var(--lav);font-size:12px;font-weight:700;transform:rotate(-6deg)}
main{flex:1;width:100%;max-width:380px;margin:0 auto;display:flex;flex-direction:column;justify-content:center}
main>*{animation:rise .8s cubic-bezier(.2,.8,.2,1) both}
main>*:nth-child(2){animation-delay:.08s}main>*:nth-child(3){animation-delay:.16s}
main>*:nth-child(4){animation-delay:.24s}main>*:nth-child(5){animation-delay:.32s}main>*:nth-child(6){animation-delay:.4s}
@keyframes rise{from{opacity:0;transform:translateY(14px)}}
.eyebrow{font-size:12.5px;font-weight:600;letter-spacing:.24em;color:var(--lav);opacity:.85}
.glass{background:rgba(23,26,40,.52);-webkit-backdrop-filter:blur(22px) saturate(140%);backdrop-filter:blur(22px) saturate(140%);
border:1px solid var(--stroke);border-radius:26px;box-shadow:0 20px 50px -20px rgba(0,0,0,.6),inset 0 1px 0 rgba(255,255,255,.05)}
.btn{position:relative;overflow:hidden;width:100%;height:56px;border-radius:18px;border:0;font:inherit;font-size:16px;font-weight:700;
letter-spacing:.02em;color:#fff;cursor:pointer;-webkit-tap-highlight-color:transparent;
background:linear-gradient(135deg,#8C7EF7 0%,#6C5CE7 45%,#4636B8 100%);
box-shadow:0 12px 30px -10px rgba(108,92,231,.85),inset 0 1px 0 rgba(255,255,255,.28);transition:transform .15s,filter .15s}
.btn:active{transform:scale(.98);filter:brightness(.92)}
/* 가끔 한 번씩 빛이 스치고 지나간다 */
.btn:after{content:'';position:absolute;top:0;bottom:0;width:40%;left:-60%;
background:linear-gradient(100deg,transparent,rgba(255,255,255,.28),transparent);animation:sheen 4.5s ease-in-out 1.2s infinite}
@keyframes sheen{0%,60%{left:-60%}100%{left:130%}}
.strands{display:block;width:100%;height:64px;overflow:visible}
.strands path{fill:none;stroke-width:2;stroke-linecap:round}
.strands .s1{animation:flow 6s linear infinite}.strands .s2{animation:flow 4.2s linear infinite reverse}
@keyframes flow{to{transform:translateX(120px)}}
@media (prefers-reduced-motion:reduce){*,*:before,*:after{animation:none!important}}
main{text-align:center;align-items:center}
/* 가운데 구슬. 쉴 때는 천천히 숨쉬고, 재생되면 물결이 퍼지고 띠가 돈다 */
.orb{position:relative;width:230px;height:230px;display:grid;place-items:center;flex:none}
.orb .ring{position:absolute;border-radius:50%;border:1px solid rgba(183,172,255,.16)}
.orb .r1{inset:0}.orb .r2{inset:26px;border-color:rgba(183,172,255,.24)}
.orb .wave{position:absolute;inset:50px;border-radius:50%;border:1.5px solid rgba(157,144,255,.6);opacity:0}
.orb .halo{position:absolute;inset:40px;border-radius:50%;opacity:0;transition:opacity .8s;
background:conic-gradient(from 0deg,transparent 0 55%,rgba(61,220,151,.9) 80%,transparent 100%);
-webkit-mask:radial-gradient(circle,transparent 66%,#000 67%);mask:radial-gradient(circle,transparent 66%,#000 67%)}
.core{position:relative;width:130px;height:130px;border-radius:50%;display:grid;place-items:center;
background:radial-gradient(circle at 34% 28%,#A99DFF 0%,#6C5CE7 42%,#35288F 78%,#1C1552 100%);
box-shadow:0 0 70px rgba(108,92,231,.55),0 0 0 1px rgba(255,255,255,.08) inset,inset -12px -16px 30px rgba(0,0,0,.35);
animation:breathe 5s ease-in-out infinite}
@keyframes breathe{50%{transform:scale(1.04);box-shadow:0 0 95px rgba(108,92,231,.7),0 0 0 1px rgba(255,255,255,.08) inset,inset -12px -16px 30px rgba(0,0,0,.35)}}
.eq{display:flex;gap:5px;align-items:center;height:46px}
.eq i{width:5px;height:10px;border-radius:3px;background:rgba(255,255,255,.92);transition:height .4s}
.eq i:nth-child(2){height:18px}.eq i:nth-child(3){height:26px}.eq i:nth-child(4){height:16px}.eq i:nth-child(5){height:9px}
[data-k=on] .eq i{animation:eq 1.1s ease-in-out infinite}
.eq i:nth-child(1){animation-delay:-.2s}.eq i:nth-child(2){animation-delay:-.6s}
.eq i:nth-child(3){animation-delay:-.1s}.eq i:nth-child(4){animation-delay:-.8s}.eq i:nth-child(5){animation-delay:-.4s}
@keyframes eq{0%,100%{height:10px}50%{height:42px}}
[data-k=on] .wave{animation:wave 3s ease-out infinite}
[data-k=on] .w2{animation-delay:1s}[data-k=on] .w3{animation-delay:2s}
@keyframes wave{from{transform:scale(1);opacity:.9}to{transform:scale(1.9);opacity:0}}
[data-k=on] .halo{opacity:1;animation:spin 3.2s linear infinite}
[data-k=wait] .halo{opacity:.6;animation:spin 1.4s linear infinite;filter:hue-rotate(95deg)}
@keyframes spin{to{transform:rotate(1turn)}}
/* 들어온 뒤에는 가운데 구슬이 음소거 버튼이다 */
.want .orb{cursor:pointer;-webkit-tap-highlight-color:transparent}
.want .orb:active .core{transform:scale(.96)}
[data-k=mute] .core{animation:none;filter:saturate(.4) brightness(.75);box-shadow:0 0 30px rgba(108,92,231,.25),inset -12px -16px 30px rgba(0,0,0,.35)}
[data-k=mute] .eq i{height:4px!important;opacity:.7}
[data-k=mute] .dot{display:block;background:var(--faint)}
.eyebrow{margin-top:26px}
h1{font-size:28px;font-weight:800;letter-spacing:-.03em;margin-top:8px;word-break:keep-all;line-height:1.25}
.st{display:inline-flex;gap:8px;align-items:center;justify-content:center;margin-top:10px;font-size:14px;color:var(--dim);min-height:21px;line-height:1.45}
.dot{width:7px;height:7px;border-radius:50%;flex:none;display:none}
[data-k=on] .st{color:var(--live)}[data-k=on] .dot{display:block;background:var(--live);box-shadow:0 0 10px var(--live)}
[data-k=wait] .dot{display:block;background:var(--lav);animation:blink 1.1s infinite}
[data-k=bad] .st{color:var(--danger)}
@keyframes blink{50%{opacity:.25}}
#form{margin-top:26px;width:100%;padding:18px 18px 20px}
.lab{font-size:12.5px;font-weight:600;color:var(--dim);letter-spacing:.04em}
.pin{position:relative;display:flex;gap:10px;justify-content:center;margin-top:12px}
.pin div{width:56px;height:64px;border-radius:16px;background:rgba(7,8,12,.55);border:1.5px solid rgba(255,255,255,.1);
display:grid;place-items:center;font-size:26px;font-weight:700;transition:border-color .2s,box-shadow .2s}
.pin div.on{border-color:var(--accent);box-shadow:0 0 0 4px rgba(108,92,231,.18)}
.pin div.on:empty:after{content:'';width:2px;height:26px;background:var(--lav);animation:blink 1s steps(1) infinite}
[data-k=bad] .pin div{border-color:rgba(255,122,122,.5);animation:shake .35s}
@keyframes shake{25%{transform:translateX(-4px)}75%{transform:translateX(4px)}}
.pin input{position:absolute;inset:0;width:100%;opacity:0;font-size:16px;border:0;background:none;color:transparent}
.btn{margin-top:16px}
.want .lab,.want .pin{display:none}
.want #form{background:none;border-color:transparent;box-shadow:none;-webkit-backdrop-filter:none;backdrop-filter:none;padding:0}
.want:not(.muted) .btn{margin-top:30px;background:rgba(23,26,40,.55);box-shadow:none;border:1px solid var(--stroke);color:var(--dim)}
.want:not(.muted) .btn:after{display:none}
.want.muted .btn{margin-top:30px}
/* 브라우저에 쌓인 소리 양. 따라잡기가 제대로 도는지 눈으로 보려고 둔다 */
.lag{margin-top:4px;font-size:12px;color:var(--faint);min-height:18px;font-variant-numeric:tabular-nums}
.fine{margin-top:16px;font-size:12.5px;color:var(--faint);line-height:1.55}
</style></head><body>
<div class=aura><i class=a1></i><i class=a2></i><i class=a3></i></div><div class=grain></div>
<header class=top><div class=brand>이음<span class=seal aria-hidden=true>音</span></div></header>
<main>
<div class=orb><span class="ring r1"></span><span class="ring r2"></span>
<span class="wave w1"></span><span class="wave w2"></span><span class="wave w3"></span><span class=halo></span>
<div class=core><div class=eq><i></i><i></i><i></i><i></i><i></i></div></div></div>
<div><p class=eyebrow>초대받은 방</p><h1>%ROOM%</h1>
<div class=st><span class=dot></span><span id=s>호스트가 알려준 비밀번호를 입력하세요</span></div>
<div class=lag id=lag></div></div>
<div id=form class=glass><div class=lab>비밀번호 4자리</div>
<div class=pin id=pin><div></div><div></div><div></div><div></div>
<input id=p type=tel inputmode=numeric autocomplete=one-time-code maxlength=4 aria-label='비밀번호 4자리'></div>
<button id=b class=btn>재생</button></div>
<p class=fine>화면을 꺼도 계속 들려요 · 볼륨은 폰 옆 버튼으로</p>
</main>
<audio id=a playsinline></audio>
<script>
var a=document.getElementById('a'),b=document.getElementById('b'),
s=document.getElementById('s'),p=document.getElementById('p'),
boxes=document.getElementById('pin').children,body=document.body;
// k: ''=안내, on=재생 중, mute=소리만 끔, wait=연결·재접속 중, bad=오류. 색·애니메이션이 이 값을 따른다.
function say(m,k){s.textContent=m;body.setAttribute('data-k',k||'');}
// 칸 네 개는 그림일 뿐이고, 실제 입력은 그 위에 투명하게 덮은 input 하나가 받는다.
function draw(){var v=p.value;for(var i=0;i<4;i++){boxes[i].textContent=v[i]||'';
boxes[i].className=(document.activeElement===p&&i===Math.min(v.length,3))?'on':'';}}
p.oninput=function(){p.value=p.value.replace(/\\D/g,'').slice(0,4);draw();
if(body.getAttribute('data-k')==='bad')say('호스트가 알려준 비밀번호를 입력하세요');};
p.onfocus=p.onblur=draw;
// 방 코드 페이지(site/)에서 넘어오면 주소에 ?pw= 가 붙어 있다. 채워만 둔다 —
// 재생은 사용자 제스처가 있어야 시작되므로 버튼은 직접 눌러야 한다.
var q=(new URLSearchParams(location.search).get('pw')||'').replace(/\\D/g,'');
if(q.length===4){p.value=q;say('재생을 누르세요');}
draw();
// want: 사용자가 '듣겠다'고 한 상태. 끊겨도 이게 true 면 계속 재시도한다.
var want=false,tries=0,lastT=-1,lastAt=0,timer=null,pw='';
// 호스트가 꺼졌는지 내 Wi-Fi 가 끊겼는지는 둘 다 '연결 실패'라 브라우저로는 못 가른다.
// 호스트가 직접 410 을 주면 확실한 종료다. 그 신호를 놓친 채 LOST_MS 동안 계속 실패하면
// '연결할 수 없어요'로 알리되, 진짜 끊김이었을 수도 있으니 LOST_RETRY 간격으로 계속 붙어 본다.
// 내 기기가 오프라인(navigator.onLine)인 동안은 확실히 내 쪽 문제라 이 시간에 넣지 않는다.
var LOST_MS=30000,LOST_RETRY=15000,failSince=0,lost=false;
// muted: 소리만 끈 상태. 연결은 그대로 두고 계속 받는다 — 다시 켜면 멈춘 자리가 아니라
// 지금 나오는 소리부터 들린다. 끊었다 붙으면 재접속 지연이 생기고 스트림도 새로 받아야 한다.
var muted=false;
var OK='재생 중 — 화면을 꺼도 계속 들려요';
function setWant(w){want=w;body.className=w?'want':'';p.disabled=w;b.textContent=w?'연결 중…':'재생';
failSince=0;lost=false;
if(!w){muted=false;a.muted=false;clearTimeout(timer);timer=null;a.removeAttribute('src');a.load();}}
// 붙는 데 성공했거나 호스트가 대답했다 — 호스트는 살아 있다.
function alive(){failSince=0;if(lost){lost=false;b.textContent='연결 중…';}}
// 소리가 나오고 있을 때의 상태 표시. 음소거 중이면 그쪽으로 보여준다.
function live(){body.classList.toggle('muted',muted);b.textContent=muted?'다시 듣기':'소리 끄기';
if(muted)say('소리 꺼짐 — 가운데를 누르면 다시 들려요','mute');else say(OK,'on');
if('mediaSession' in navigator)navigator.mediaSession.playbackState=muted?'paused':'playing';}
function toggleMute(){var k=body.getAttribute('data-k');if(!want||(k!=='on'&&k!=='mute'))return;
muted=!muted;a.muted=muted;live();}
function connect(){
if(!lost)say(tries?('재접속 중… ('+tries+'회)'):'연결 중…','wait');
a.src='/stream?pw='+encodeURIComponent(pw)+'&t='+Date.now()+'-'+tries;
lastT=-1;lastAt=Date.now();
a.play().then(function(){tries=0;alive();live();
if('mediaSession' in navigator){navigator.mediaSession.metadata=
new MediaMetadata({title:'이음',artist:document.querySelector('h1').textContent});
// 잠금화면·이어폰의 일시정지도 음소거로 받는다. 진짜로 멈추면 감시견이 '멈춤'으로 보고 다시 붙는다.
try{navigator.mediaSession.setActionHandler('pause',function(){if(!muted)toggleMute();});
navigator.mediaSession.setActionHandler('play',function(){if(muted)toggleMute();});}catch(e){}}
}).catch(function(e){fail('재생 실패: '+e.name);});}
// 왜 끊겼는지 서버에 따로 물어본다. <audio> 는 실패해도 상태 코드를
// 안 주기 때문에, 이게 없으면 비밀번호가 틀린 건지 Wi-Fi 가 끊긴 건지
// 구분을 못 하고 영원히 '재접속 중…' 만 띄운다.
function fail(msg){if(!want)return;
fetch('/check?pw='+encodeURIComponent(pw),{cache:'no-store'}).then(function(r){
if(!want)return;
if(r.status===204){alive();say(msg,'wait');retry();return;}
if(r.status===410){setWant(false);say('호스트가 공유를 종료했어요');return;}
setWant(false);p.value='';draw();
say(r.status===429?'시도가 너무 많아요 — 잠시 후 다시 해 주세요':'비밀번호가 맞지 않아요','bad');
}).catch(unreachable);}
// 호스트에 아예 닿지 않음. 누구 탓인지 가를 수 있는 만큼만 가른다.
// '재생 실패: NotSupportedError' 같은 브라우저 사정은 여기선 의미가 없어 재접속 횟수만 보여준다.
function unreachable(){if(!want)return;
if(!navigator.onLine){failSince=0;lost=false;b.textContent='연결 중…';say('Wi-Fi 연결을 확인해 주세요','wait');retry();return;}
var now=Date.now();if(!failSince)failSince=now;
if(now-failSince>=LOST_MS){if(!lost){lost=true;b.textContent='다시 시도';}
say('연결할 수 없어요 — 방이 끝났거나 다른 Wi-Fi 일 수 있어요','bad');}
else{b.textContent='연결 중…';say('재접속 중… ('+(tries+1)+'회)','wait');}
retry();}
// 지수적으로는 안 늘리고 최대 5초에서 멈춘다. 여행 중 재접속은 빠를수록 좋다.
function retry(){if(!want||timer)return;tries++;
timer=setTimeout(function(){timer=null;connect();},lost?LOST_RETRY:Math.min(1000*tries,5000));}
// 바로 다시 붙어 본다. 기다리던 재시도는 버린다.
function retryNow(){clearTimeout(timer);timer=null;tries=0;connect();}
// ★비밀번호를 서버에 먼저 물어보고 나서 재생하면 안 된다. fetch 를 기다리는
//   사이 사용자 제스처가 풀려서 아이폰이 play() 를 거부한다. 그래서 일단
//   재생을 걸고, 실패했을 때만 fail() 이 이유를 따져 묻는다.
b.onclick=function(){if(want){if(lost)retryNow();else toggleMute();return;}
var v=(p.value||'').replace(/\\D/g,'');
if(v.length!==4){say('비밀번호 4자리를 입력해 주세요','bad');p.focus();return;}
pw=v;tries=0;setWant(true);p.blur();connect();};
p.onkeydown=function(e){if(e.key==='Enter')b.click();};
a.onerror=function(){if(want)fail('스트림 끊김 — 재접속');};
// 호스트가 끄면 스트림이 '끝'으로 닫힌다. 그냥 다시 붙지 말고 왜 끝났는지부터 묻는다.
a.onended=function(){if(want)fail('스트림 끊김 — 재접속');};
a.onstalled=function(){if(want)say('버퍼링…','wait');};
a.onplaying=function(){tries=0;alive();live();};
// 가운데 구슬: 듣는 중이면 음소거 켜고 끄기, 아직이면 버튼과 똑같이 재생 시작
document.querySelector('.orb').onclick=function(){b.onclick();};
// 감시견: 이벤트가 아예 안 뜬 채 재생만 멈추는 경우(가장 흔한 실패)를 잡는다.
// currentTime 이 4초간 안 늘면 죽은 것으로 보고 다시 붙는다.
setInterval(function(){if(!want||timer)return;var now=Date.now();
if(a.currentTime!==lastT){lastT=a.currentTime;lastAt=now;return;}
if(now-lastAt>4000){if(!lost)say('멈춤 감지 — 재접속','wait');retry();}},1000);
// 따라잡기: <audio> 는 끊기지 않으려고 몇 초치를 쌓아 두고 튼다. 와이파이가 흔들려
// 한번 밀리면 그 뒤로도 밀린 자리부터 틀어서 지연이 계속 남는다.
// 받아 둔 끝(buffered.end)과 지금 위치의 차이를 보고, 많이 밀렸으면 끝 근처로 건너뛰고
// 조금 밀렸으면 살짝 빨리 틀어 티 안 나게 줄인다. 너무 바짝 붙으면 브라우저가 다시
// 버퍼링하느라 오히려 더 쌓이므로 LAG_KEEP 만큼은 남긴다.
var LAG_JUMP=1.0,LAG_FAST=0.6,LAG_KEEP=0.4,lagEl=document.getElementById('lag');
setInterval(function(){
if(!want||a.paused||!a.buffered.length){lagEl.textContent='';return;}
var end=a.buffered.end(a.buffered.length-1),lag=end-a.currentTime;
if(lag>LAG_JUMP){a.playbackRate=1;a.currentTime=end-LAG_KEEP;lag=LAG_KEEP;}
else if(lag>LAG_FAST)a.playbackRate=1.05;
else if(lag<LAG_KEEP)a.playbackRate=1;
lagEl.textContent='버퍼 '+lag.toFixed(1)+'초';},1000);
// 화면을 다시 켰을 때 즉시 복구를 시도한다.
document.addEventListener('visibilitychange',function(){
if(!document.hidden&&want&&a.paused)retryNow();});
// Wi-Fi 가 돌아오면 기다리지 않고 바로 붙는다.
window.addEventListener('online',function(){if(want&&a.paused)retryNow();});
</script></body></html>
""";

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
        // 시작하다 실패한 경우(권한 거절 등)는 공유가 '끝난' 게 아니다. 공유 전에 미리
        // 켜 둔 핫스팟까지 꺼 버리면 안 되므로, 실제로 돌던 공유가 끝날 때만 같이 끈다.
        if (running) MainActivity.closeHotspotWithSharing();
        running = false;
        sharing = false;
        listeners = 0;
        // 방을 닫으면 비밀번호도 같이 버린다. 다음 공유는 새 번호로 시작한다 —
        // 한 번 불러준 번호가 계속 살아 있으면 그게 곧 영구 열쇠가 된다.
        roomId = "";
        roomName = "";
        roomPw = "";
        pwFails.clear();
        // 서버는 바로 닫지 않는다. 스트림만 끊어서 청취자가 /check 를 묻게 하고,
        // FAREWELL_MS 동안 410 으로 '끝났다'고 답한 뒤 닫는다 (FAREWELL_MS 참고).
        final ServerSocket ss = server;
        if (ss != null) {
            farewellUntil = System.currentTimeMillis() + FAREWELL_MS;
            lingering = ss;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() {
                    if (lingering == ss) lingering = null;
                    try { ss.close(); } catch (Exception ignored) { }
                }
            }, FAREWELL_MS);
        }
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
