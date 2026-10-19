package com.example.lt;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 화면은 셋이고(activity_main.xml), 지금 상태가 그중 하나를 고른다.
 *
 *   공유 중이면        → 공유 중 화면
 *   듣는 중이면        → 듣는 중 화면 (뒤로 가면 홈 + 위쪽 미니 플레이어)
 *   둘 다 아니면       → 홈
 *
 * 사용자가 화면을 "고르지" 않는다. 알림에서 공유를 끄거나 서비스가 죽어도
 * 0.5초 안에 화면이 맞는 쪽으로 돌아간다 — 상태와 화면이 어긋날 틈이 없다.
 *
 * 그 밖의 입력은 전부 바텀시트(Dialog + 우리 레이아웃)로 받는다.
 */
public class MainActivity extends Activity {

    private static final int REQ_PERMS = 100;
    private static final int REQ_PROJECTION = 101;
    private static final int REQ_HOTSPOT_PERMS = 102;

    static final String PREFS = "lt";
    /**
     * 예전에 쓰던 "공유할 앱" 화이트리스트 키. **이제 읽지 않는다.**
     * 지우는 코드만 남긴 이유는, 예전 버전에서 앱을 골라둔 사용자가 업데이트하면
     * 화면에는 선택 UI 가 없는데 소리는 계속 걸러지는 유령 상태가 되기 때문이다.
     */
    private static final String KEY_APPS_LEGACY = "shared_apps";
    /** 호스트가 정한 방 이름. 기기 모델명(SM-S911N)보다 사람이 알아보기 쉽다. */
    private static final String KEY_ROOM_NAME = "room_name";

    private static final int SCREEN_HOME = 0, SCREEN_HOST = 1, SCREEN_LISTEN = 2;
    private int screen = -1;

    private View screenHome, screenHost, screenListen;
    private AdBanner adBanner;

    // 홈
    private LinearLayout roomList;
    private View emptyRooms, miniPlayer;
    /**
     * 방이 이만큼 안 보이면 '공용 Wi-Fi 는 막혀 있을 수 있다'는 안내를 띄운다.
     * 비콘은 1초마다 오므로 몇 초면 충분하다. 카페·호텔 Wi-Fi 는 기기끼리의 통신을
     * 막아서(AP isolation) 공유 중인 방이 옆에 있어도 영영 안 뜬다 — 앱으로는 못 뚫고
     * 호스트 핫스팟으로 옮기는 수밖에 없다.
     */
    private static final long EMPTY_HINT_MS = 8000;
    private View emptyHint;
    private long emptySince;
    private TextView miniText;
    private View miniDot;

    // 공유 중
    private TextView livePill, codeText, pwText, hostHint, listenerCount, hotspotState;
    private View pwChip, btnSendLink, btnShowQr;

    // 듣는 중
    private PulseView pulse;
    private TextView listenName, listenState, statLatency, statSignal, statQuality;
    private View listenDot, stats;
    private Button btnRetryPw;

    private TextView snack;

    /**
     * 지금 듣고 있는 방 이름. 서비스는 주소만 알아서 여기 따로 들고 있는다.
     * static 인 이유: 화면을 돌리거나 앱을 나갔다 오면 액티비티가 새로 뜨는데,
     * 그때 "192.168.0.2" 로 바뀌어 보이면 다른 방에 붙은 줄 안다.
     */
    private static String joinedName;
    /** 듣는 중인데 홈을 보고 있는가. 뒤로 가기로 켜지고, 미니 플레이어를 누르면 꺼진다. */
    private boolean browsing;
    /** 공유 중에 홈을 보고 있다. 공유는 계속된다. 홈 위쪽 막대를 누르면 공유 화면으로 돌아간다. */
    private boolean hostBrowsing;

    /** 떠 있는 시트. 한 번에 하나만 띄운다 — 겹치면 뒤의 것이 dim 아래 갇힌다. */
    private Dialog sheet;
    /** 핫스팟 시트가 떠 있을 때만 null 이 아니다. 핫스팟 콜백이 여기로 결과를 쓴다. */
    private Dialog hsSheet;
    private ImageView hsQr;
    private TextView hsInfo, hsStatus;
    private Button hsToggle;

    /**
     * 발견된 방 하나.
     *
     * 예전엔 `Map<ip, 기기명>` 이었다. 그때는 IP 가 곧 방이었기 때문인데, 호스트가
     * **인터페이스마다 비콘을 따로 쏘면서**(Wi-Fi + 핫스팟) 같은 방이 주소만 다른
     * 두 줄로 뜬다. 방 ID 로 묶어야 한 줄이 된다.
     */
    private static final class Room {
        final String id;
        String name = "";
        boolean locked;
        /**
         * 이 방으로 가는 주소 -> 그 주소로 비콘을 마지막으로 본 시각(elapsedRealtime).
         * 어느 망에 붙어 있느냐에 따라 되는 게 다르니 전부 들고 있는다.
         * 시각을 주소마다 따로 두는 이유는 pruneRooms 에 적어놨다.
         */
        final LinkedHashMap<String, Long> ips = new LinkedHashMap<>();
        Room(String id) { this.id = id; }
    }

    /** 비콘이 이만큼 끊기면 목록에서 내린다. 비콘은 1초에 한 번씩 온다. */
    private static final long ROOM_TTL_MS = 6000;

    /** 방ID -> 방. 비콘으로 채워진다. 발견 순서를 유지하려고 LinkedHashMap 이다. */
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean discovering;
    /** 목록을 마지막으로 그릴 때 듣고 있던 주소. 바뀌면 "듣는 중" 표시를 옮겨 그린다. */
    private String drawnHost;
    /** 목록을 마지막으로 그릴 때 실제로 소리를 받고 있었는가. 바뀌면 "듣는 중" 표시를 고친다. */
    private boolean drawnLive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 앱 선택 기능을 걷어냈으니 남아 있던 선택도 같이 지운다.
        // 안 지우면 예전에 골라둔 사용자는 화면에 아무 표시도 없이 소리가 걸러진다.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_APPS_LEGACY).apply();

        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);

        // targetSdk 35+ 는 edge-to-edge 가 강제라 상태바·내비바가 내용 위를 덮는다.
        // 인셋을 직접 먹이지 않으면 제목이 시계에 붙고 맨 아래 버튼이 내비바에 깔린다.
        // 안드로이드 10 이하는 edge-to-edge 가 아니라 시스템이 알아서 비켜 준다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }

        screenHome = findViewById(R.id.screenHome);
        screenHost = findViewById(R.id.screenHost);
        screenListen = findViewById(R.id.screenListen);

        roomList = findViewById(R.id.roomList);
        emptyRooms = findViewById(R.id.emptyRooms);
        emptyHint = findViewById(R.id.emptyHint);
        miniPlayer = findViewById(R.id.miniPlayer);
        miniText = findViewById(R.id.miniText);
        miniDot = findViewById(R.id.miniDot);

        livePill = findViewById(R.id.livePill);
        codeText = findViewById(R.id.codeText);
        pwText = findViewById(R.id.pwText);
        pwChip = findViewById(R.id.pwChip);
        hostHint = findViewById(R.id.hostHint);
        listenerCount = findViewById(R.id.listenerCount);
        hotspotState = findViewById(R.id.hotspotState);
        btnSendLink = findViewById(R.id.btnSendLink);
        btnShowQr = findViewById(R.id.btnShowQr);

        pulse = findViewById(R.id.pulse);
        // 가운데 원판을 누르면 소리만 끈다. 웹 듣기 페이지의 가운데 누르기와 같은 동작.
        pulse.setOnClickListener(v -> {
            if (!listenLive()) return;
            PlayerService.muted = !PlayerService.muted;
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            sync();
        });
        listenName = findViewById(R.id.listenName);
        listenState = findViewById(R.id.listenState);
        listenDot = findViewById(R.id.listenDot);
        stats = findViewById(R.id.stats);
        statLatency = findViewById(R.id.statLatency);
        statSignal = findViewById(R.id.statSignal);
        statQuality = findViewById(R.id.statQuality);
        btnRetryPw = findViewById(R.id.btnRetryPw);

        snack = findViewById(R.id.snack);

        // 홈
        findViewById(R.id.heroShare).setOnClickListener(v -> {
            // 이미 공유 중이면 방을 또 만들지 않고 공유 화면으로 돌아간다.
            if (CaptureService.sharing) { hostBrowsing = false; sync(); }
            else openCreateSheet();
        });
        findViewById(R.id.btnSettings).setOnClickListener(v -> openSettings());
        findViewById(R.id.btnCode).setOnClickListener(v -> openCodeSheet());
        miniPlayer.setOnClickListener(v -> { browsing = false; hostBrowsing = false; sync(); });

        // 공유 중
        findViewById(R.id.btnHostBack).setOnClickListener(v -> goHome());
        findViewById(R.id.btnSettingsHost).setOnClickListener(v -> openSettings());
        codeText.setOnClickListener(v -> copyCode());
        btnSendLink.setOnClickListener(v -> sendLink());
        btnShowQr.setOnClickListener(v -> openInviteQr());
        findViewById(R.id.rowHotspot).setOnClickListener(v -> openHotspotSheet());
        findViewById(R.id.btnStop).setOnClickListener(v -> confirm(
                R.string.host_stop_title, R.string.host_stop_body,
                R.string.host_stop, true, this::stopSharing));

        // 듣는 중
        findViewById(R.id.btnListenBack).setOnClickListener(v -> goHome());
        findViewById(R.id.btnLeave).setOnClickListener(v -> leave());
        btnRetryPw.setOnClickListener(v -> {
            String host = PlayerService.currentHost;
            if (host != null) openPin(joinedName != null ? joinedName : host, host);
        });

        buildScanDots();
        breathe(findViewById(R.id.liveDot));
        breathe(miniDot);

        refreshHosts();
        sync();

        adBanner = new AdBanner(this, findViewById(R.id.adBar));

        // 화면 회전 등으로 다시 만들어질 땐 같은 링크로 또 들어가지 않는다.
        if (savedInstanceState == null) openLink(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        openLink(intent);
    }

    /**
     * 초대 링크(…/listen_together/#코드)로 열렸으면 그 방에 바로 들어간다.
     * 코드는 # 뒤라 서버로는 안 가고 여기서만 읽힌다. 코드가 없거나 틀리면 그냥 홈이다.
     */
    private void openLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        android.net.Uri u = intent.getData();
        String raw = u == null ? null : u.getFragment();
        String[] ipPw = raw == null ? null : RoomCode.decode(raw);
        if (ipPw == null) return;
        join(ipPw[0], ipPw[1], nameForIp(ipPw[0],
                getString(R.string.room_fallback, raw.trim().toUpperCase(Locale.ROOT))));
    }

    @Override protected void onResume() {
        super.onResume();
        adBanner.resume();
        startDiscovery();
        // 화면을 다시 볼 때마다 새로 센다. 꺼져 있던 동안은 방을 찾지 않았다.
        emptySince = SystemClock.elapsedRealtime();
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    @Override protected void onPause() {
        adBanner.pause();
        super.onPause();
        discovering = false;
        ui.removeCallbacks(ticker);
    }

    @Override protected void onDestroy() {
        if (sheet != null) sheet.dismiss();
        adBanner.destroy();
        super.onDestroy();
    }

    /**
     * 0.5초마다 서비스 상태를 화면에 비춘다. 화면이 꺼져 있을 땐 멈춘다.
     *
     * 굳이 폴링인 이유는 PoC 라서다 — 브로드캐스트나 바인딩을 끌어오는 것보다
     * 상태 몇 개 읽는 게 싸고, 틀릴 여지도 없다.
     */
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (pruneRooms()) refreshHosts();
            if (hotspotClosedNote) { hotspotClosedNote = false; notice(R.string.host_hotspot_closed); }
            long now = SystemClock.elapsedRealtime();
            if (!rooms.isEmpty()) emptySince = now;
            int hint = rooms.isEmpty() && now - emptySince > EMPTY_HINT_MS ? View.VISIBLE : View.GONE;
            if (emptyHint.getVisibility() != hint) emptyHint.setVisibility(hint);
            sync();
            renderHotspotSheet();
            ui.postDelayed(this, 500);
        }
    };

    // ── 화면 고르기 ─────────────────────────────────

    private void sync() {
        String host = PlayerService.currentHost;
        // 호스트가 공유를 끄는 등으로 서비스가 스스로 멈췄으면 이유를 한 번 알린다.
        int ended = PlayerService.endedRes;
        if (ended != 0 && host == null) {
            PlayerService.endedRes = 0;
            joinedName = null;
            notice(ended);
        }
        if (host == null) browsing = false;
        if (!CaptureService.sharing) hostBrowsing = false;
        int want = CaptureService.sharing && !hostBrowsing ? SCREEN_HOST
                : (host != null && !browsing) ? SCREEN_LISTEN : SCREEN_HOME;
        if (want != screen) show(want);

        if (want == SCREEN_HOST) renderHost();
        else if (want == SCREEN_LISTEN) renderListen();
        else renderHome();
    }

    private void show(int which) {
        screen = which;
        View[] all = {screenHome, screenHost, screenListen};
        for (int i = 0; i < all.length; i++) {
            View v = all[i];
            if (i == which) {
                v.setAlpha(0f);
                v.setVisibility(View.VISIBLE);
                v.animate().alpha(1f).setDuration(180).start();
            } else {
                v.setVisibility(View.GONE);
            }
        }
        pulse.setActive(false);
        updateBackHandling();
    }

    /** 공유·듣기는 그대로 두고 홈만 보여준다. */
    private void goHome() {
        if (screen == SCREEN_HOST) hostBrowsing = true;
        else if (screen == SCREEN_LISTEN) browsing = true;
        sync();
    }

    /**
     * 공유 중·듣는 중 화면에서 뒤로 가기는 앱을 닫지 않고 홈으로 간다. 소리는 계속 난다.
     *
     * targetSdk 36 은 예측형 뒤로 가기가 기본이라 onBackPressed 가 **안 불린다.**
     * 33+ 는 콜백을 걸었다 뗐다 해야 한다. 늘 걸어두면 홈에서 뒤로 가기로 앱을 못 나간다.
     */
    private OnBackInvokedCallback backCb;

    private void updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return;
        OnBackInvokedDispatcher d = getOnBackInvokedDispatcher();
        boolean want = screen != SCREEN_HOME;
        if (want && backCb == null) {
            backCb = this::goHome;
            d.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCb);
        } else if (!want && backCb != null) {
            d.unregisterOnBackInvokedCallback(backCb);
            backCb = null;
        }
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (screen != SCREEN_HOME) { goHome(); return; }
        super.onBackPressed();
    }

    // ── 홈 ─────────────────────────────────────────

    private void renderHome() {
        String host = PlayerService.currentHost;
        boolean sharing = CaptureService.sharing;
        miniPlayer.setVisibility(host != null || sharing ? View.VISIBLE : View.GONE);
        if (sharing) {
            String code = currentCode();
            setIf(miniText, code != null ? getString(R.string.mini_sharing_code, code)
                    : getString(R.string.mini_sharing));
        } else if (host != null) {
            setIf(miniText, getString(!listenLive() ? R.string.mini_reconnecting
                            : PlayerService.muted ? R.string.mini_muted : R.string.mini_listening,
                    joinedName != null ? joinedName : host));
        }
        miniDot.setBackgroundTintList(ColorStateList.valueOf(
                color(sharing || listenLive() ? R.color.live : R.color.text_faint)));
        if (!Objects.equals(host, drawnHost) || listenLive() != drawnLive) refreshHosts();
    }

    private void refreshHosts() {
        drawnHost = PlayerService.currentHost;
        drawnLive = listenLive();
        roomList.removeAllViews();
        for (Room r : rooms.values()) roomList.addView(roomRow(r));
        emptyRooms.setVisibility(rooms.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 이름 첫 글자 칸의 색. 방 ID 로 고르니 같은 방은 목록이 바뀌어도 같은 색이다. */
    private static final int[][] AVATAR = {
            {0xFF2B2552, 0xFFA99BFF}, {0xFF163A2E, 0xFF6BE8B2}, {0xFF3A2A1A, 0xFFFFB86B},
            {0xFF1A2E3F, 0xFF6BC4FF}, {0xFF3A1F2B, 0xFFFF8FB8},
    };

    private View roomRow(final Room r) {
        View row = getLayoutInflater().inflate(R.layout.item_room, roomList, false);
        TextView av = row.findViewById(R.id.avatar);
        int[] pal = AVATAR[Math.floorMod(r.id.hashCode(), AVATAR.length)];
        GradientDrawable g = new GradientDrawable();
        g.setColor(pal[0]);
        g.setCornerRadius(dp(14));
        av.setBackground(g);
        av.setTextColor(pal[1]);
        String name = r.name.isEmpty() ? getString(R.string.room_unnamed) : r.name;
        av.setText(name.substring(0, name.offsetByCodePoints(0, 1)));

        ((TextView) row.findViewById(R.id.name)).setText(name);
        row.findViewById(R.id.lock).setVisibility(r.locked ? View.VISIBLE : View.GONE);

        // 지금 듣고 있는 방이면 바로 표시한다. 목록에서 자기 자리를 잃지 않게.
        boolean current = r.ips.containsKey(PlayerService.currentHost);
        boolean live = current && listenLive();
        TextView sub = row.findViewById(R.id.sub);
        sub.setText(live ? R.string.room_listening : current ? R.string.room_reconnecting
                : r.locked ? R.string.room_locked : R.string.room_open);
        sub.setTextColor(color(live ? R.color.live : R.color.text_dim));

        row.setOnClickListener(v -> tapRoom(r));
        return row;
    }

    /** "근처의 방" 옆의 점 세 개. 찾고 있다는 표시다 — 빈 목록이 멈춘 화면처럼 보이지 않게. */
    private void buildScanDots() {
        LinearLayout box = findViewById(R.id.scanDots);
        for (int i = 0; i < 3; i++) {
            View d = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(5), dp(5));
            lp.leftMargin = dp(4);
            d.setLayoutParams(lp);
            d.setBackgroundResource(R.drawable.bg_dot);
            d.setBackgroundTintList(ColorStateList.valueOf(color(R.color.text_dim)));
            box.addView(d);
            ObjectAnimator a = ObjectAnimator.ofFloat(d, View.ALPHA, 1f, 0.2f);
            a.setDuration(600);
            a.setStartDelay(i * 200L);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.start();
        }
    }

    private static void breathe(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, View.ALPHA, 1f, 0.35f);
        a.setDuration(900);
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.start();
    }

    // ── 공유 중 ─────────────────────────────────────

    /**
     * 방 코드 하나. 친구에게 불러주고, 맥·PC 에서는 고정 페이지에 넣는 값이다.
     * 주소가 여럿이면(Wi-Fi + 핫스팟) 첫 번째 것으로 만든다 — 순서는 lanIps 에 적어놨다.
     * 붙을 수 있는 주소가 하나도 없으면 null. 그때 코드를 보여주면 아무도 못 들어오는 코드다.
     */
    private static String currentCode() {
        List<String> lan = lanIps();
        return lan.isEmpty() ? null : RoomCode.encode(lan.get(0), CaptureService.roomPw);
    }

    private void renderHost() {
        setIf(livePill, getString(R.string.host_live_pill, CaptureService.roomName));

        String code = currentCode();
        if (code == null) {
            codeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            setIf(codeText, getString(R.string.host_need_wifi));
            setIf(hostHint, getString(R.string.host_hint_no_wifi));
        } else {
            codeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 46);
            setIf(codeText, code);
            // 주소만 밝게 칠한다. 언어마다 문장 속 위치가 달라서 끼운 뒤에 찾는다.
            String site = siteShort();
            SpannableStringBuilder sb = new SpannableStringBuilder(
                    getString(R.string.host_hint_code, site));
            int at = sb.toString().indexOf(site);
            if (at >= 0) {
                sb.setSpan(new ForegroundColorSpan(color(R.color.text)), at, at + site.length(), 0);
            }
            setIf(hostHint, sb);
        }
        btnSendLink.setEnabled(code != null);
        btnShowQr.setEnabled(code != null);
        btnSendLink.setAlpha(code != null ? 1f : 0.4f);
        btnShowQr.setAlpha(code != null ? 1f : 0.4f);

        String pw = CaptureService.roomPw;
        pwChip.setVisibility(pw.isEmpty() ? View.GONE : View.VISIBLE);
        setIf(pwText, pw);

        int n = CaptureService.listeners;
        setIf(listenerCount, n > 0 ? getResources().getQuantityString(R.plurals.host_listener_count, n, n)
                : getString(R.string.host_no_listeners));
        listenerCount.setTextColor(color(n > 0 ? R.color.live : R.color.text_dim));
        setIf(hotspotState, getString(reservation != null || hotspotUp()
                ? R.string.host_hotspot_on : R.string.host_hotspot_off));
    }

    /** `https://` 와 끝의 `/` 를 뗀 주소. 화면에서 읽을 때는 그쪽이 짧고 덜 무섭다. */
    private static String siteShort() {
        String s = RoomCode.SITE.replaceFirst("^https?://", "");
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private void copyCode() {
        String code = currentCode();
        if (code == null) return;
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.host_code_label), code));
        // 13+ 는 시스템이 복사됐다고 직접 띄운다. 겹쳐 띄우면 같은 말을 두 번 한다.
        if (Build.VERSION.SDK_INT < 33) notice(R.string.host_code_copied);
    }

    /**
     * 메신저로 보낼 초대. 링크는 고정 페이지의 `#코드` 라, 받은 사람이 누르기만 하면
     * 페이지가 코드를 풀어 호스트로 보내준다 — 비밀번호도 코드 안에 들어 있다.
     */
    private void sendLink() {
        String code = currentCode();
        if (code == null) return;
        String text = getString(R.string.host_invite, CaptureService.roomName,
                RoomCode.SITE + "#" + code, code, CaptureService.roomPw);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, getString(R.string.host_send_link)));
    }

    /** 친구 폰 카메라로 찍게 하는 초대 QR. 내용은 링크 보내기와 같은 `#코드` 링크다. */
    private void openInviteQr() {
        String code = currentCode();
        if (code == null) return;
        Dialog d = sheet(R.layout.sheet_qr);
        ((TextView) d.findViewById(R.id.qrTitle)).setText(R.string.qr_title);
        ((TextView) d.findViewById(R.id.qrLead)).setText(R.string.qr_lead);
        ((ImageView) d.findViewById(R.id.qrImage)).setImageBitmap(qrBitmap(RoomCode.SITE + "#" + code));
        ((TextView) d.findViewById(R.id.qrFoot)).setText(
                getString(R.string.qr_foot, code, CaptureService.roomPw));
        d.findViewById(R.id.btnClose).setOnClickListener(v -> d.dismiss());
        d.show();
    }

    // ── 듣는 중 ─────────────────────────────────────

    /** 지금 호스트에 붙어 소리를 받고 있는가. 서비스 상태 중 이 상태는 player_listening 하나뿐이다. */
    private static boolean listenLive() {
        return PlayerService.currentHost != null && !PlayerService.denied
                && PlayerService.stateRes == R.string.player_listening;
    }

    private void renderListen() {
        String host = PlayerService.currentHost;
        if (host == null) return;
        setIf(listenName, joinedName != null ? joinedName : host);

        int st = PlayerService.stateRes;
        boolean denied = PlayerService.denied;
        // 서비스가 쓰는 문구 중 "붙어서 소리를 받는 중" 은 이것 하나다. 나머지는 전부 실패나 대기.
        boolean playing = listenLive();
        int c = color(denied ? R.color.danger : playing ? R.color.live : R.color.text_dim);

        boolean muted = playing && PlayerService.muted;
        if (muted) c = color(R.color.text_dim);

        int line = denied ? st : muted ? R.string.listen_muted
                : playing ? R.string.listen_connected : st;
        setIf(listenState, getString(line != 0 ? line : R.string.player_connecting));
        listenState.setTextColor(c);
        listenDot.setBackgroundTintList(ColorStateList.valueOf(c));
        pulse.setActive(playing);
        pulse.setMuted(muted);
        pulse.setContentDescription(getString(muted ? R.string.listen_unmute : R.string.listen_mute));

        // 거절은 '재시도 중인 실패'와 다르다. 그대로 두면 끝나지 않는 대기로 보이니
        // 할 수 있는 일(비밀번호 다시 입력)을 바로 내민다.
        btnRetryPw.setVisibility(denied ? View.VISIBLE : View.GONE);
        stats.setVisibility(denied ? View.GONE : playing ? View.VISIBLE : View.INVISIBLE);
        if (!playing) return;

        int lat = PlayerService.latencyMs;
        setIf(statLatency, lat >= 0 ? getString(R.string.listen_seconds,
                String.format(Locale.ROOT, "%.2f", lat / 1000f)) : "—");
        int net = PlayerService.netPct;
        // 실시간 대비 받은 양. 1초 단위라 출렁이므로 셋으로만 뭉뚱그린다.
        setIf(statSignal, net < 0 ? "—" : getString(net >= 90 ? R.string.listen_signal_good
                : net >= 70 ? R.string.listen_signal_fair : R.string.listen_signal_poor));
        statSignal.setTextColor(color(net >= 0 && net < 70 ? R.color.danger : R.color.text));
        int rate = PlayerService.sampleRate;
        setIf(statQuality, rate <= 0 ? "—" : rate % 1000 == 0 ? rate / 1000 + "kHz"
                : String.format(Locale.ROOT, "%.1fkHz", rate / 1000f));
    }

    // ── 시트 ───────────────────────────────────────

    /** 바텀시트 하나를 만든다. 띄우는 건(show) 부른 쪽이 내용을 채운 뒤에 한다. */
    private Dialog sheet(int layout) {
        if (sheet != null) sheet.dismiss();
        final Dialog d = new Dialog(this, R.style.Sheet);
        final View v = getLayoutInflater().inflate(layout, null);
        // 떠 있는 창(floating)은 시스템이 내비바 위에서 잘라서 시트 밑에 검은 띠가 생긴다.
        // 그래서 창은 화면 전체로 깔고, 시트는 그 안에서 바닥에 붙인다. 빈 곳을 누르면 닫힌다.
        final FrameLayout scrim = new FrameLayout(this);
        scrim.setOnClickListener(x -> d.dismiss());
        v.setClickable(true);
        scrim.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        d.setContentView(scrim);
        Window w = d.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                w.setDecorFitsSystemWindows(false);
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            } else {
                // 안드로이드 10 에는 위 기능이 없어 옛 플래그로 창을 내비바 밑까지 깐다.
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
            // 대화창 테마는 내비바를 검게 칠한다. 투명하게 해야 그 밑의 시트 색이 보인다.
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
            w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            w.setNavigationBarContrastEnforced(false);
        }
        // 창이 내비바·키보드 밑까지 깔리니 그만큼은 직접 띄운다. 키보드가 올라오면 시트가 따라 올라간다.
        final int basePad = v.getPaddingBottom();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            scrim.setOnApplyWindowInsetsListener((view, insets) -> {
                int nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
                int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                        v.getPaddingRight(), basePad + Math.max(nav, ime));
                return WindowInsets.CONSUMED;
            });
        } else {
            // 옛 방식은 내비바와 키보드가 한 값에 합쳐져 온다.
            // 키보드 쪽은 창이 ADJUST_RESIZE 일 때만 들어온다 — focusInput() 도 그걸 지켜야 한다.
            scrim.setOnApplyWindowInsetsListener((view, insets) -> {
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        basePad + insets.getSystemWindowInsetBottom());
                return insets.consumeSystemWindowInsets();
            });
        }
        d.setOnDismissListener(x -> {
            if (sheet == d) sheet = null;
            if (hsSheet == d) {
                hsSheet = null;
                hsQr = null;
                hsInfo = hsStatus = null;
                hsToggle = null;
            }
        });
        sheet = d;
        return d;
    }

    /** 텍스트 입력이 있는 시트는 키보드를 같이 올린다. 한 번 더 눌러야 하는 칸은 마찰이다. */
    private static void focusInput(Dialog d, EditText in) {
        in.requestFocus();
        Window w = d.getWindow();
        if (w != null) {
            // 안드로이드 10 은 ADJUST_NOTHING 이면 키보드 높이를 아예 안 알려 준다. sheet() 참고.
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                            : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE));
        }
    }

    private void confirm(int title, int body, int ok, boolean danger, final Runnable then) {
        final Dialog d = sheet(R.layout.sheet_confirm);
        ((TextView) d.findViewById(R.id.cTitle)).setText(title);
        ((TextView) d.findViewById(R.id.cBody)).setText(body);
        Button b = d.findViewById(R.id.cOk);
        b.setText(ok);
        if (danger) {
            b.setBackgroundResource(R.drawable.bg_btn_danger);
            b.setTextColor(color(R.color.danger));
        }
        b.setOnClickListener(v -> { d.dismiss(); then.run(); });
        d.findViewById(R.id.cCancel).setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private void openCreateSheet() {
        final Dialog d = sheet(R.layout.sheet_create);
        final EditText in = d.findViewById(R.id.nameInput);
        // 안 정하면 기기 모델명(SM-S911N)이 그대로 목록에 뜬다. 카페에서 그걸 보고
        // 어느 게 친구 방인지 아는 사람은 없다. 그래서 지난번 이름을 채워 둔다.
        in.setHint(Build.MODEL);
        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ROOM_NAME, "");
        in.setText(saved);
        in.setSelection(saved.length());
        // 동의 창 버튼 이름이 버전마다 다르다. 13 까지는 '지금 시작', 14 부터는 '화면 공유'.
        ((TextView) d.findViewById(R.id.consentText)).setText(Build.VERSION.SDK_INT >= 34
                ? R.string.create_consent_new : R.string.create_consent_old);
        final Button start = d.findViewById(R.id.btnStart);
        start.setOnClickListener(v -> {
            // 다음에도 같은 이름을 쓰게 저장한다. 매번 다시 적게 하면 결국 아무도 안 적는다.
            // 동의 창을 다녀오는 사이 액티비티가 새로 뜰 수 있어 필드가 아니라 저장소에 둔다.
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_ROOM_NAME, in.getText().toString().trim()).apply();
            d.dismiss();
            requestAndShare();
        });
        in.setOnEditorActionListener((tv, id, ev) -> { start.performClick(); return true; });
        focusInput(d, in);
        d.show();
    }

    /**
     * 비밀번호 4자리. 다 치면 버튼 없이 바로 들어간다.
     *
     * 맞는지는 호스트만 안다 — 여기서는 형식도 안 따진다. 틀리면 PlayerService 가
     * 401 을 받아 denied 를 세우고, 듣는 중 화면이 "다시 입력" 을 내민다.
     * 검사를 두 곳에 두면 한쪽이 바뀔 때 다른 쪽이 조용히 어긋난다.
     */
    private void openPin(final String title, final String ip) {
        final Dialog d = sheet(R.layout.sheet_pin);
        ((TextView) d.findViewById(R.id.pinTitle)).setText(title);
        if (PlayerService.denied && PlayerService.stateRes != 0
                && ip.equals(PlayerService.currentHost)) {
            TextView lead = d.findViewById(R.id.pinLead);
            lead.setText(getString(R.string.pin_retry, getString(PlayerService.stateRes)));
            lead.setTextColor(color(R.color.danger));
        }

        final StringBuilder typed = new StringBuilder();
        LinearLayout boxRow = d.findViewById(R.id.pinBoxes);
        final TextView[] boxes = new TextView[4];
        for (int i = 0; i < 4; i++) {
            TextView b = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(56), dp(64));
            lp.leftMargin = lp.rightMargin = dp(5);
            b.setLayoutParams(lp);
            b.setBackgroundResource(R.drawable.bg_pin);
            b.setGravity(Gravity.CENTER);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            b.setTypeface(null, android.graphics.Typeface.BOLD);
            b.setTextColor(color(R.color.text));
            boxRow.addView(b);
            boxes[i] = b;
        }
        final Runnable paint = () -> {
            for (int i = 0; i < 4; i++) {
                boxes[i].setText(i < typed.length() ? String.valueOf(typed.charAt(i)) : "");
                boxes[i].setActivated(i == typed.length());
            }
        };
        paint.run();

        LinearLayout pad = d.findViewById(R.id.keypad);
        String[][] keys = {{"1", "2", "3"}, {"4", "5", "6"}, {"7", "8", "9"}, {"", "0", "<"}};
        for (String[] rowKeys : keys) {
            LinearLayout row = new LinearLayout(this);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
            for (final String k : rowKeys) {
                View key;
                if (k.equals("<")) {
                    ImageView iv = new ImageView(this);
                    iv.setImageResource(R.drawable.ic_backspace);
                    iv.setImageTintList(ColorStateList.valueOf(color(R.color.text_dim)));
                    iv.setScaleType(ImageView.ScaleType.CENTER);
                    iv.setContentDescription(getString(R.string.pin_delete));
                    key = iv;
                } else {
                    TextView t = new TextView(this);
                    t.setText(k);
                    t.setGravity(Gravity.CENTER);
                    t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                    t.setTextColor(color(R.color.text));
                    key = t;
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                lp.leftMargin = lp.rightMargin = dp(3);
                key.setLayoutParams(lp);
                if (k.isEmpty()) { row.addView(key); continue; }
                key.setBackgroundResource(R.drawable.bg_key);
                key.setOnClickListener(v -> {
                    if (k.equals("<")) {
                        if (typed.length() > 0) typed.setLength(typed.length() - 1);
                    } else if (typed.length() < 4) {
                        typed.append(k);
                    }
                    paint.run();
                    if (typed.length() == 4) {
                        final String pw = typed.toString();
                        // 마지막 숫자가 칸에 찍히는 걸 보여주고 닫는다. 바로 닫으면 눌렸는지 모른다.
                        ui.postDelayed(() -> { d.dismiss(); join(ip, pw, title); }, 150);
                    }
                });
                row.addView(key);
            }
            pad.addView(row);
        }
        d.show();
    }

    /**
     * 방 코드로 들어가기. 자동 발견이 막힌 망(공공 AP 의 클라이언트 격리 등)에서 쓴다.
     * 코드에 비밀번호가 들어 있어서 따로 묻지 않는다.
     */
    private void openCodeSheet() {
        final Dialog d = sheet(R.layout.sheet_code);
        final EditText in = d.findViewById(R.id.codeInput);
        final TextView err = d.findViewById(R.id.codeError);
        final Button go = d.findViewById(R.id.btnGo);
        in.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                err.setVisibility(View.GONE);
                go.setEnabled(s.toString().replaceAll("[\\s\\-_.]", "").length() >= 4);
            }
        });
        go.setOnClickListener(v -> {
            String raw = in.getText().toString();
            String[] ipPw = RoomCode.decode(raw);
            if (ipPw == null) {
                err.setVisibility(View.VISIBLE);
                shake(in);
                return;
            }
            d.dismiss();
            join(ipPw[0], ipPw[1], nameForIp(ipPw[0],
                    getString(R.string.room_fallback, raw.trim().toUpperCase(Locale.ROOT))));
        });
        in.setOnEditorActionListener((tv, id, ev) -> {
            if (go.isEnabled()) go.performClick();
            return true;
        });
        focusInput(d, in);
        d.show();
    }

    private void openDirectSheet() {
        final Dialog d = sheet(R.layout.sheet_direct);
        final EditText ip = d.findViewById(R.id.ipInput);
        final EditText pw = d.findViewById(R.id.pwInput);
        final Button go = d.findViewById(R.id.btnGo);
        go.setOnClickListener(v -> {
            // 사용자가 넣는 건 대개 `http://192.168.0.2:7980` 통째다. 서비스가 알아서 벗긴다.
            String host = PlayerService.normalizeHost(ip.getText().toString());
            if (host == null) { shake(ip); return; }
            d.dismiss();
            join(host, pw.getText().toString().trim(), nameForIp(host, host));
        });
        pw.setOnEditorActionListener((tv, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_GO) { go.performClick(); return true; }
            return false;
        });
        focusInput(d, ip);
        d.show();
    }

    /** 목록에 이미 뜬 방이면 그 이름을 쓴다. 코드로 들어와도 화면에는 사람이 정한 이름이 뜨게. */
    private String nameForIp(String ip, String fallback) {
        for (Room r : rooms.values()) {
            if (r.ips.containsKey(ip) && !r.name.isEmpty()) return r.name;
        }
        return fallback;
    }

    private void openSettings() {
        final Dialog d = sheet(R.layout.sheet_settings);

        // 삼성은 포그라운드 서비스마저 장시간 뒤에 죽인다. 이미 제외돼 있으면 할 일이 없다.
        PowerManager pm = getSystemService(PowerManager.class);
        boolean exempt = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        ((TextView) d.findViewById(R.id.batterySub)).setText(exempt
                ? R.string.settings_sheet_battery_exempt : R.string.settings_sheet_battery_warn);
        d.findViewById(R.id.batteryChev).setVisibility(exempt ? View.GONE : View.VISIBLE);
        View rowBatt = d.findViewById(R.id.rowBattery);
        rowBatt.setClickable(!exempt);
        if (!exempt) rowBatt.setOnClickListener(v -> { d.dismiss(); askIgnoreBatteryOptimization(); });

        d.findViewById(R.id.rowDirect).setOnClickListener(v -> openDirectSheet());

        // 주소를 전부 보여준다. 어느 쪽으로 들어올지는 친구 사정이라 우리가 못 고른다.
        List<String> ips = lanIps();
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("http://").append(ip).append(':').append(CaptureService.PORT);
            if (CaptureService.sharing) {
                String code = RoomCode.encode(ip, CaptureService.roomPw);
                if (code != null) sb.append("   ").append(code);
            }
        }
        ((TextView) d.findViewById(R.id.netInfo)).setText(
                ips.isEmpty() ? getString(R.string.settings_sheet_no_wifi) : sb.toString());

        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        ((TextView) d.findViewById(R.id.version)).setText(getString(R.string.app_name) + " " + ver);
        d.show();
    }

    private static void shake(View v) {
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0, 14, -14, 10, -10, 5, -5, 0)
                .setDuration(360).start();
    }

    // ── 알림 한 줄 ───────────────────────────────────

    /** 한 문장을 읽을 만큼은 되고, 화면을 오래 가리지는 않을 만큼. */
    private static final long NOTICE_MS = 3500;

    private final Runnable hideSnack = () -> snack.animate().alpha(0f).setDuration(200)
            .withEndAction(() -> snack.setVisibility(View.GONE)).start();

    /**
     * 잠깐 띄우는 알림.
     *
     * 핫스팟이 권한 예외로 실패했을 때 화면에 아무 일도 안 일어난 것처럼 보이면
     * 기종 문제로 오진하게 된다 — 실제로 그럴 뻔했다. 실패는 반드시 말로 남긴다.
     * 핫스팟 시트가 떠 있으면 시트 안에도 쓴다. 시트의 dim 아래 알림은 안 보인다.
     */
    private void notice(int msg) {
        notice(getString(msg));
    }

    private void notice(String msg) {
        snack.setText(msg);
        ui.removeCallbacks(hideSnack);
        snack.animate().cancel();
        if (snack.getVisibility() != View.VISIBLE) {
            snack.setAlpha(0f);
            snack.setTranslationY(dp(12));
            snack.setVisibility(View.VISIBLE);
        }
        snack.animate().alpha(1f).translationY(0).setDuration(200).start();
        ui.postDelayed(hideSnack, NOTICE_MS);

        if (hsStatus != null) {
            hsStatus.setText(msg);
            hsStatus.setVisibility(View.VISIBLE);
        }
    }

    // ── 호스트 ─────────────────────────────────────

    private void requestAndShare() {
        String[] needed = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.RECORD_AUDIO};
        for (String p : needed) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(needed, REQ_PERMS);
                return;
            }
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        // 선택지를 없애면(createConfigForDefaultDisplay) 동의 창이 "전체 화면"으로 고정되면서
        // 비밀번호·결제정보 경고가 뜬다 — 음악 공유 앱으로는 치명적인 첫인상이다.
        // "앱 하나 공유"를 고를 수 있게 두면 문구가 훨씬 순해진다.
        // 오디오 범위는 어차피 우리 화이트리스트(addMatchingUid)가 따로 거르므로
        // 여기서는 사용자가 덜 놀라는 쪽을 택한다.
        // 14 부터는 "앱 하나 공유" 가 기본으로 골라져 있다. 그걸로 들어가면 그 앱 소리만
        // 잡히거나 아예 안 잡혀서, 공유는 켜졌는데 아무 소리도 안 가는 방이 된다. 전체로 못 박는다.
        Intent consent = Build.VERSION.SDK_INT >= 34
                ? mpm.createScreenCaptureIntent(
                        android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                : mpm.createScreenCaptureIntent();
        startActivityForResult(consent, REQ_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS && requestCode != REQ_HOTSPOT_PERMS) return;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                notice(requestCode == REQ_PERMS
                        ? R.string.share_no_permission
                        : Build.VERSION.SDK_INT >= 33
                                ? R.string.hotspot_need_nearby
                                : R.string.hotspot_need_location);
                return;
            }
        }
        if (requestCode == REQ_PERMS) requestAndShare();
        else tryLocalHotspot();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            notice(R.string.share_cancelled);
            return;
        }
        String name = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_ROOM_NAME, "");

        // 반대 방향 가드. 듣는 중이었다면 여기서 끊는다 — 공유 버튼을 누른 순간이 아니라
        // **동의가 끝난 뒤**다. 동의를 취소했는데 듣던 것만 날아가면 손해만 본다.
        // 이쪽은 의도가 분명하니 되묻지 않고, 사라진 이유만 알린다.
        if (PlayerService.currentHost != null) {
            stopService(new Intent(this, PlayerService.class));
            PlayerService.currentHost = null;
            joinedName = null;
            notice(R.string.share_switched);
        }

        Intent svc = new Intent(this, CaptureService.class);
        svc.putExtra(CaptureService.EXTRA_CODE, resultCode);
        svc.putExtra(CaptureService.EXTRA_DATA, data);
        svc.putExtra(CaptureService.EXTRA_ROOM_NAME, name);
        startForegroundService(svc);
        sync();
    }

    /**
     * 삼성은 포그라운드 서비스마저 장시간 뒤에 죽인다. 코드로는 못 막고
     * 사용자가 화이트리스트에 넣어주는 수밖에 없다.
     */
    private void askIgnoreBatteryOptimization() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            notice(R.string.settings_sheet_battery_already);
            return;
        }
        try {
            startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            // 일부 기기는 위 인텐트를 막는다. 그럴 땐 목록 화면이라도 연다.
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void stopSharing() {
        stopService(new Intent(this, CaptureService.class));
        CaptureService.sharing = false;   // 서비스가 죽기 전 화면부터 정직하게
        sync();
    }

    // ── 핫스팟 ─────────────────────────────────────

    private static WifiManager.LocalOnlyHotspotReservation reservation;
    /** 우리가 띄운 핫스팟의 이름·비밀번호. 시트를 닫았다 열어도 QR 을 다시 그리려고 들고 있는다. */
    private static String qrSsid, qrPw;
    /** 공유와 함께 핫스팟이 꺼졌다는 걸 화면이 다음 틱에 알려주게 남겨 두는 표시. */
    private static volatile boolean hotspotClosedNote;

    /**
     * 공유가 끝나면 CaptureService 가 부른다. 공유 없는 핫스팟은 쓸모가 없고, 듣던 사람
     * 폰만 인터넷 없는 Wi-Fi 에 계속 붙잡아 둔다. 앱이 켠 것만 끈다 — 설정에서 켠
     * 핫스팟은 끌 수도 없고, 사용자가 인터넷을 나누려고 켠 것일 수 있다.
     * 화면이 꺼져 있어도 꺼야 하므로 static 이다 (reservation 도 그래서 static).
     */
    static void closeHotspotWithSharing() {
        if (reservation == null) return;
        try { reservation.close(); } catch (Exception ignored) { }
        reservation = null;
        qrSsid = qrPw = null;
        hotspotClosedNote = true;
        Log.i(CaptureService.TAG, "HOTSPOT closed with sharing");
    }

    /**
     * 핫스팟 요청 일련번호. 감시 타이머가 **자기가 건 요청**에만 반응하게 하는 표다.
     * 콜백이 오면 번호를 올려 타이머를 무효로 만든다 — 안 그러면 실패 메시지를
     * 띄운 12초 뒤에 "응답하지 않습니다" 가 그 위를 덮는다.
     */
    private long hotspotReq;

    private void openHotspotSheet() {
        Dialog d = sheet(R.layout.sheet_hotspot);
        hsSheet = d;
        hsQr = d.findViewById(R.id.hsQr);
        hsInfo = d.findViewById(R.id.hsInfo);
        hsStatus = d.findViewById(R.id.hsStatus);
        hsToggle = d.findViewById(R.id.hsToggle);
        hsToggle.setOnClickListener(v -> {
            if (reservation != null || hotspotUp()) hotspotOff();
            else tryLocalHotspot();
        });
        renderHotspotSheet();
        d.show();
    }

    private void renderHotspotSheet() {
        if (hsSheet == null) return;
        boolean ours = reservation != null;
        boolean on = ours || hotspotUp();
        if (ours && qrSsid != null) {
            // `WIFI:T:WPA;S:이름;P:비번;;` 는 안드로이드가 시스템 차원에서 아는 포맷이라,
            // 상대는 **기본 카메라로 찍기만 하면** 접속 알림이 뜬다. 앱도 필요 없다.
            hsQr.setImageBitmap(qrBitmap(
                    "WIFI:T:WPA;S:" + qrEscape(qrSsid) + ";P:" + qrEscape(qrPw) + ";;"));
            hsQr.setVisibility(View.VISIBLE);
            setIf(hsInfo, getString(R.string.hotspot_manual, qrSsid, qrPw));
            hsInfo.setVisibility(View.VISIBLE);
        } else {
            hsQr.setVisibility(View.GONE);
            if (on) {
                // 설정에서 켠 핫스팟은 SSID/비번을 읽는 공개 API 가 없어 QR 만 못 그린다.
                setIf(hsInfo, getString(R.string.hotspot_on_info));
                hsInfo.setVisibility(View.VISIBLE);
            } else {
                hsInfo.setVisibility(View.GONE);
            }
        }
        String label = getString(on ? R.string.hotspot_turn_off : R.string.hotspot_turn_on);
        if (!label.contentEquals(hsToggle.getText())) {
            // 배경을 매번 갈면 누르는 도중의 물결(ripple)이 끊긴다. 상태가 바뀔 때만.
            hsToggle.setText(label);
            hsToggle.setBackgroundResource(on ? R.drawable.bg_btn_ghost : R.drawable.bg_btn_primary);
        }
    }

    private void hotspotOff() {
        if (reservation != null) {
            reservation.close();
            reservation = null;
            qrSsid = qrPw = null;
            notice(R.string.hotspot_turned_off);
            renderHotspotSheet();
            return;
        }
        // 설정에서 켠 핫스팟은 우리가 못 끈다. 끌 게 없는데 아무 말도 없으면
        // 눌렀는데 안 꺼진 것처럼 보인다 — 그것도 침묵으로 속이는 것이다.
        notice(R.string.hotspot_cant_turn_off);
    }

    /** 핫스팟 이름·비밀번호가 바뀌면 부른다. ssid 가 null 이면 QR 을 내린다. */
    private void showQr(String ssid, String pw) {
        qrSsid = ssid;
        qrPw = pw;
        renderHotspotSheet();
    }

    private static String qrEscape(String s) {
        // 값 안의 `;` `:` `\` `,` 는 이 포맷에서 구분자라 escape 해야 한다.
        // LocalOnlyHotspot 비번은 영숫자뿐이라 지금은 걸릴 일이 없지만,
        // 사용자가 SSID 를 정하게 되는 순간 조용히 깨질 자리다.
        return s.replace("\\", "\\\\").replace(";", "\\;")
                .replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"");
    }

    private String qrPayload;
    private Bitmap qrCache;

    /** QR 그림. 핫스팟 시트는 0.5초마다 다시 그리니 같은 내용이면 저번 그림을 쓴다. */
    private Bitmap qrBitmap(String payload) {
        if (payload.equals(qrPayload) && qrCache != null) return qrCache;
        try {
            int size = 640;
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, size, size,
                            Collections.singletonMap(
                                    com.google.zxing.EncodeHintType.MARGIN, 0));
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF0D0F14 : 0xFFFFFFFF;
            }
            qrCache = Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565);
            qrPayload = payload;
            return qrCache;
        } catch (Throwable t) {
            Log.w(CaptureService.TAG, "QR 생성 실패: " + t);
            return null;
        }
    }

    /**
     * 우리가 띄운 핫스팟의 SSID·비밀번호. 못 읽으면 null.
     *
     * **버전마다 읽는 법이 다르다.** `getSoftApConfiguration()` 은 API 30 부터라
     * 안드로이드 10 에서 부르면 `NoSuchMethodError` 가 난다 — 예외가 아니라 Error 라
     * 무심코 `catch (Exception)` 으로 감싸면 그대로 앱이 죽는다. 노트9 에서 핫스팟은
     * 멀쩡히 떴는데 QR 만 안 나오던 게 이것 때문이었다. 그런데 QR 이 이 기능의 전부다 —
     * 비번을 불러주지 않아도 되게 하려고 만든 것이니까.
     */
    private static String[] readApConfig(WifiManager.LocalOnlyHotspotReservation res) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                SoftApConfiguration c = res.getSoftApConfiguration();
                if (c == null || c.getPassphrase() == null) return null;
                return new String[]{String.valueOf(c.getSsid()), c.getPassphrase()};
            }
            @SuppressWarnings("deprecation")
            android.net.wifi.WifiConfiguration c = res.getWifiConfiguration();
            if (c == null || c.SSID == null || c.preSharedKey == null) return null;
            // 구형 WifiConfiguration 은 SSID 를 따옴표로 감싸서 주는 경우가 있다.
            // 그대로 QR 에 넣으면 따옴표까지 이름의 일부가 되어 접속이 안 된다.
            return new String[]{unquote(c.SSID), unquote(c.preSharedKey)};
        } catch (Throwable t) {
            Log.w(CaptureService.TAG, "HOTSPOT config unreadable", t);
            return null;
        }
    }

    private static String unquote(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private void tryLocalHotspot() {
        // 이미 켜져 있으면 다시 요청하지 않는다. 그냥 부르면 시스템이
        // IllegalStateException("Caller already has an active LocalOnlyHotspot request")
        // 를 던진다 — 버튼을 두 번 누른 것뿐인데 예외가 나던 자리다.
        if (reservation != null) {
            String[] cfg = readApConfig(reservation);
            if (cfg != null) {
                showQr(cfg[0], cfg[1]);
                notice(R.string.hotspot_already_on);
            } else {
                notice(R.string.hotspot_on_no_pw);
            }
            return;
        }

        // API 33+ 는 NEARBY_WIFI_DEVICES 하나면 된다. 위치 권한은 12 이하에서만 묻는다 —
        // 매니페스트에 neverForLocation 을 달아 둬서 시스템도 위치를 따로 검사하지 않는다.
        String[] need = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(need, REQ_HOTSPOT_PERMS);
                return;
            }
        }
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);

        // 여기서 미리 막지 않으면 **콜백이 영영 안 온다.** 비행기모드면 시스템이
        // 요청을 받아놓고 조용히 버린다(WifiController: "drop softap requests when in
        // airplane mode") — onStarted 도 onFailed 도 안 불린다. 그러면 화면은
        // "요청 중…" 에서 멈춰 있다가 아무 설명 없이 원래대로 돌아간다.
        // 실패를 못 알리느니 아예 시작을 안 하고 이유를 말하는 편이 낫다.
        if (Settings.Global.getInt(getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0) {
            notice(R.string.hotspot_airplane);
            return;
        }
        if (wm == null) {
            notice(R.string.hotspot_no_wifi);
            return;
        }
        // **Wi-Fi 가 켜져 있는지는 보지 않는다.** 한때 여기서 막았는데 틀렸다 —
        // 노트9 에서 Wi-Fi 를 끈 채로 핫스팟이 멀쩡히 떴다(swlan0 192.168.43.1).
        // 핫스팟은 Wi-Fi 접속(STA)과 별개로 도는 기능이라 켜져 있을 이유가 없다.

        // 설정에서 켠 핫스팟이 이미 떠 있으면 요청해도 ERROR_INCOMPATIBLE_MODE 로 떨어진다.
        // 그런데 사용자한테 이건 실패가 아니다 — 핫스팟은 켜져 있고, 방 코드도 이미
        // 그 주소로 나와 있으며, 앱은 그걸로 잘 돈다. "실패" 라고 하면 멀쩡한 상황을
        // 고장난 것처럼 보이게 만든다.
        if (hotspotUp()) {
            notice(R.string.hotspot_system_on);
            return;
        }

        notice(R.string.hotspot_starting);
        // 콜백이 안 오는 길이 또 있을 수 있다. 비행기모드는 위에서 걸렀지만
        // 그게 유일한 경우라는 보장이 없으니, 잠잠하면 잠잠하다고 말하게 한다.
        // 말없이 원래 화면으로 돌아가는 것보다 낫다.
        final long ticket = ++hotspotReq;
        ui.postDelayed(() -> {
            if (ticket == hotspotReq && reservation == null) {
                notice(R.string.hotspot_no_response);
            }
        }, 12000);
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                    hotspotReq++;   // 감시 타이머 무효화
                    reservation = res;
                    String[] cfg = readApConfig(res);
                    Log.i(CaptureService.TAG, "HOTSPOT ok cfg="
                            + (cfg == null ? "null" : cfg[0] + "/" + cfg[1]));
                    if (cfg == null) {
                        // 설정을 못 읽으면 QR 은 없다. 그래도 핫스팟은 켜졌으니
                        // 어디서 비번을 보는지는 알려준다.
                        notice(R.string.hotspot_on_check_settings);
                        showQr(null, null);
                    } else {
                        notice(R.string.hotspot_turned_on);
                        showQr(cfg[0], cfg[1]);
                    }
                }

                @Override
                public void onFailed(int reason) {
                    hotspotReq++;   // 감시 타이머 무효화
                    String why;
                    switch (reason) {
                        case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                            why = getString(R.string.hotspot_err_no_channel); break;
                        case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:
                            why = getString(R.string.hotspot_err_generic); break;
                        case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                            why = getString(R.string.hotspot_err_incompatible); break;
                        case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                            why = getString(R.string.hotspot_err_disallowed); break;
                        default:
                            why = getString(R.string.hotspot_err_unknown, String.valueOf(reason));
                    }
                    Log.w(CaptureService.TAG, "HOTSPOT failed: " + reason + " " + why);
                    notice(getString(R.string.hotspot_failed, why));
                }

                @Override
                public void onStopped() {
                    Log.i(CaptureService.TAG, "HOTSPOT stopped");
                    reservation = null;
                    showQr(null, null);
                    notice(R.string.hotspot_stopped);
                }
            }, ui);
        } catch (Throwable t) {
            hotspotReq++;   // 감시 타이머 무효화
            Log.e(CaptureService.TAG, "HOTSPOT threw", t);
            notice(getString(R.string.hotspot_exception, String.valueOf(t)));
        }
    }

    // ── 참여자 ─────────────────────────────────────

    private void join(final String ip, final String pw, final String name) {
        // 마지막 관문이다. tapRoom 이 먼저 물어보지만 코드·주소 입력은 여기로 바로 온다.
        // stopSharing() 이 sharing 을 그 자리에서 내리므로 이 재귀는 한 번만 돈다.
        if (CaptureService.sharing) {
            confirmStopSharing(() -> join(ip, pw, name));
            return;
        }
        joinedName = name;
        browsing = false;
        Intent svc = new Intent(this, PlayerService.class);
        svc.putExtra(PlayerService.EXTRA_HOST, ip);
        svc.putExtra(PlayerService.EXTRA_PW, pw);
        startForegroundService(svc);
        // 여기서 "듣는 중" 이라고 단정하지 않는다. 실제로 붙었는지는 서비스만 안다.
        // 서비스가 상태를 쓰기 전까지는 홈에 머문다. 틱을 기다리지 않고 곧바로 한 번 더 본다.
        sync();
        ui.postDelayed(this::sync, 150);
    }

    private void leave() {
        stopService(new Intent(this, PlayerService.class));
        PlayerService.currentHost = null;
        joinedName = null;
        sync();
    }

    /**
     * 공유와 듣기를 동시에 하면 소리가 돈다.
     *
     * 캡처는 모든 앱의 USAGE_MEDIA 를 먹고 우리 재생도 USAGE_MEDIA 라,
     * 내가 듣는 소리가 내 캡처에 다시 잡혀 나간다. 서로의 방에 들어가면 그 루프가
     * 양쪽으로 돈다 — 실제로 소리가 계속 나왔다.
     *
     * 그래서 한 번에 하나만 허용한다. excludeUid 로 내 재생만 캡처에서 빼도 루프는
     * 끊기지만, 내 폰은 A 를 내보내는데 내 귀엔 B 가 들리는 설명 안 되는 상태가 남는다.
     * 루프를 끊는 것보다 그 상태를 못 만들게 하는 쪽이 맞다.
     */
    private void confirmStopSharing(final Runnable then) {
        confirm(R.string.share_switch_title, R.string.share_switch_body,
                R.string.share_switch_ok, false, () -> { stopSharing(); then.run(); });
    }

    /**
     * 호스트가 1초마다 뿌리는 UDP 비콘을 받아 목록을 만든다.
     *
     * **멀티캐스트 락이 있어야 한다.** 안드로이드 Wi-Fi 스택은 자기 MAC 앞으로 오지
     * 않은 프레임을 기본으로 버린다 — 브로드캐스트가 정확히 거기 해당한다. 락 없이도
     * 처음 얼마간은 들어오다가 라디오가 절전으로 내려가는 순간 조용히 끊긴다.
     * 화면 켜둔 채 40초를 재봤더니 35초쯤 수신이 죽었고, 그 뒤로는 새로 쏜 비콘이
     * 한 발도 안 들어왔다. 스레드는 멀쩡히 recvfrom 에 잠들어 있었다 — 소켓 위가
     * 아니라 아래에서 걸러진 것이다.
     *
     * 예전엔 이게 안 보였다. 한 번 담은 방을 지우지 않았으니 수신이 죽어도 목록은
     * 그대로 남아 멀쩡해 보였다. 방을 만료시키기 시작하니까 드러났다.
     */
    private void startDiscovery() {
        if (discovering) return;
        discovering = true;
        new Thread(() -> {
            DatagramSocket ds = null;
            WifiManager.MulticastLock mcast = null;
            try {
                WifiManager wm = (WifiManager)
                        getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    mcast = wm.createMulticastLock("lt:discover");
                    // 참조 계수를 끄면 acquire/release 가 몇 번 겹쳐도 상태가 하나다.
                    // 화면을 껐다 켤 때마다 이 스레드가 새로 뜨므로 겹칠 수 있다.
                    mcast.setReferenceCounted(false);
                    mcast.acquire();
                }
                ds = new DatagramSocket(null);
                ds.setReuseAddress(true);
                ds.setBroadcast(true);
                ds.setSoTimeout(1500);
                ds.bind(new java.net.InetSocketAddress(CaptureService.BEACON_PORT));
                byte[] buf = new byte[256];
                while (discovering) {
                    try {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        ds.receive(p);
                        final String[] f =
                                new String(p.getData(), 0, p.getLength(), "UTF-8").split("\\|");
                        // 쪼개는 것까지만 여기서 하고 목록은 UI 스레드에서만 건드린다.
                        // 만료 청소가 UI 스레드에서 도는데, 양쪽이 같은 맵을 동시에
                        // 고치면 순회하다 터진다.
                        ui.post(() -> { if (absorb(f)) refreshHosts(); });
                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                ui.post(() -> notice(R.string.home_discovery_failed));
            } finally {
                if (ds != null) ds.close();
                // 쥔 채로 두면 화면을 꺼도 Wi-Fi 가 계속 깨어 있어 배터리를 먹는다.
                if (mcast != null && mcast.isHeld()) mcast.release();
            }
        }, "lt-discover").start();
    }

    /**
     * 비콘 한 줄을 목록에 흡수한다. **화면을 새로 그려야 하면 true.**
     *
     * 비콘은 초당 인터페이스 수만큼 온다. 바뀐 게 없는데도 매번 다시 그리면
     * 목록을 누르려는 순간 뷰가 갈려서 탭이 빗나간다. 그래서 변화가 있을 때만 알린다.
     *
     * LT1 은 방 개념이 없던 예전 빌드다. 받아주긴 하되 잠금 없는 방으로 취급한다 —
     * 안 받아주면 구버전을 쓰는 상대가 목록에서 통째로 사라져서, 앱이 고장난 것처럼 보인다.
     */
    private boolean absorb(String[] f) {
        final String id, name, ip;
        final boolean locked;
        if (f.length >= 5 && "LT2".equals(f[0])) {
            id = f[1]; name = f[2]; ip = f[3]; locked = "1".equals(f[4]);
        } else if (f.length >= 3 && "LT1".equals(f[0])) {
            id = f[2]; name = f[1]; ip = f[2]; locked = false;
        } else {
            return false;
        }
        if (id.isEmpty() || ip.isEmpty() || isMine(ip)) return false;

        Room r = rooms.get(id);
        boolean changed = false;
        if (r == null) { r = new Room(id); rooms.put(id, r); changed = true; }
        if (!name.equals(r.name)) { r.name = name; changed = true; }
        if (r.locked != locked) { r.locked = locked; changed = true; }
        // 이미 아는 주소면 시각만 새로 찍는다 — 그건 화면에 안 보이니 다시 그리지 않는다.
        if (r.ips.put(ip, SystemClock.elapsedRealtime()) == null) changed = true;
        return changed;
    }

    /**
     * 끊긴 방과 죽은 주소를 목록에서 내린다. **뭔가 지웠으면 true.**
     *
     * 공유를 껐다 켜면 방 ID 가 새로 생긴다. 예전 줄이 안 지워져서,
     * 시험 중에 같은 폰 하나가 목록에 **세 줄**로 떴다 — 어느 줄이 살아 있는지
     * 알 방법이 없었다. 친구가 공유를 다시 켜기만 해도 이렇게 된다.
     *
     * 주소는 방과 따로 만료시킨다. 호스트가 핫스팟에서 Wi-Fi 로 옮기면 방 ID 는
     * 그대로인데 예전 주소만 죽는데, openRoom 은 **첫 번째** 주소로 붙는다.
     * 죽은 주소가 앞자리에 남아 있으면 멀쩡한 방을 눌러도 계속 실패한다.
     */
    private boolean pruneRooms() {
        long now = SystemClock.elapsedRealtime();
        boolean changed = false;
        Iterator<Room> it = rooms.values().iterator();
        while (it.hasNext()) {
            Room r = it.next();
            Iterator<Map.Entry<String, Long>> ai = r.ips.entrySet().iterator();
            while (ai.hasNext()) {
                Map.Entry<String, Long> a = ai.next();
                // 지금 듣고 있는 주소는 남긴다. 붙어서 소리가 나는 중인데 목록에서
                // 사라지면, "듣는 중" 을 붙일 자리도 같이 없어진다.
                // 끊긴 뒤에는 붙잡지 않는다 — 닫힌 방이 "듣는 중" 으로 계속 떠 있었다.
                if (a.getKey().equals(PlayerService.currentHost) && listenLive()) continue;
                if (now - a.getValue() > ROOM_TTL_MS) { ai.remove(); changed = true; }
            }
            if (r.ips.isEmpty()) { it.remove(); changed = true; }
        }
        return changed;
    }

    private void tapRoom(final Room r) {
        // 비밀번호를 다 치게 한 뒤에 "공유를 멈출까요?" 를 묻는 건 순서가 나쁘다. 먼저 묻는다.
        if (CaptureService.sharing) {
            confirmStopSharing(() -> openRoom(r));
            return;
        }
        openRoom(r);
    }

    private void openRoom(final Room r) {
        // 공유 중이면 먼저 멈출지 묻는다. 비밀번호를 다 친 뒤에 물으면 취소할 때 친 게 헛수고가 된다.
        if (CaptureService.sharing) {
            confirmStopSharing(() -> openRoom(r));
            return;
        }
        // 이미 듣고 있는 방이면 다시 붙지 않고 듣는 화면으로 돌아간다.
        if (r.ips.containsKey(PlayerService.currentHost) && !PlayerService.denied) {
            browsing = false;
            sync();
            return;
        }
        // 주소가 여럿이면 첫 번째로 시도한다. 안 되면 설정 › 주소로 직접 들어가기가 있다.
        final String ip = r.ips.keySet().iterator().next();
        String name = r.name.isEmpty() ? ip : r.name;
        if (!r.locked) { join(ip, "", name); return; }
        openPin(name, ip);
    }

    // ── 공용 ───────────────────────────────────────

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int color(int resId) {
        return getResources().getColor(resId, getTheme());
    }

    /**
     * 바뀌었을 때만 쓴다. 0.5초마다 같은 글자를 다시 넣으면 길게 눌러 고르던
     * 선택이 풀리고, 레이아웃도 매번 다시 잰다.
     */
    private static void setIf(TextView v, CharSequence s) {
        if (!android.text.TextUtils.equals(v.getText(), s)) v.setText(s);
    }

    /**
     * 내 비콘인지 판정한다. 호스트가 인터페이스마다 다른 주소를 담아 보내므로
     * localIp() 하나와 비교하면 자기 자신이 목록에 뜬다.
     */
    static boolean isMine(String ip) {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && ip.equals(a.getHostAddress())) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 핫스팟이 이미 떠 있나. **인터페이스 이름으로 본다** — 켜졌는지 묻는 공개 API 가
     * 없다(`getWifiApState` 는 @hide). 판정 규칙은 아래 localIps 와 같은 것을 쓴다.
     */
    static boolean hotspotUp() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName();
                if (!name.startsWith("swlan") && !name.startsWith("ap")) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * 내 IPv4 주소를 **전부** 우선순위대로 내놓는다.
     *  1) 핫스팟(swlan0/ap0) — 켜져 있다면 참여자는 여기에 들어와 있다
     *  2) 일반 Wi-Fi(wlan0)
     *  3) 그 외 (모바일 데이터 rmnet 은 다른 기기가 못 붙지만 없는 것보단 낫다)
     * STA+AP 가 동시에 뜨므로 단순히 "먼저 잡히는 것"을 고르면 틀린 주소를 광고한다.
     *
     * 하나만 고르지 않는 이유: 핫스팟과 Wi-Fi 가 같이 떠 있을 때 친구가 **어느 쪽에
     * 들어와 있는지 우리는 모른다.** 골라주면 절반은 틀린 주소를 보게 되고, 브라우저로
     * 들어오는 사람은 앱의 자동 발견을 못 쓰니 그 주소 하나가 전부다.
     */
    static List<String> localIps() {
        return scanIps(false);
    }

    /**
     * 친구가 실제로 붙을 수 있는 주소(핫스팟·Wi-Fi)만. 비면 **아무도 못 들어오는 상태**다.
     *
     * 화면의 방 코드는 이걸로만 만든다. LTE 만 켜진 폰에서 localIps 는 마지막 수단으로
     * 192.0.0.8 을 내놓는데, 그걸로 만든 코드는 누구에게도 안 풀린다 — 보여주면 함정이다.
     */
    static List<String> lanIps() {
        return scanIps(true);
    }

    private static List<String> scanIps(boolean lanOnly) {
        List<String> hotspot = new ArrayList<String>();
        List<String> wifi = new ArrayList<String>();
        List<String> other = new ArrayList<String>();
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : ifs) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName();
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (name.startsWith("swlan") || name.startsWith("ap")) {
                        hotspot.add(ip);
                    } else if (name.startsWith("wlan")) {
                        wifi.add(ip);
                    } else {
                        other.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        List<String> all = new ArrayList<String>();
        all.addAll(hotspot);
        all.addAll(wifi);
        // other 는 목록에 섞지 않는다. 실측해보니 rmnet_data8 192.0.0.8(모바일 데이터
        // 내부용 clat 주소)과 rndis0(USB 테더링)이 같이 잡히는데, 둘 다 친구 폰에서
        // 못 붙는 주소다. 나열하면 셋 중 둘이 함정이라 목록이 오히려 방해가 된다.
        // 붙을 수 있는 게 하나도 없을 때만 마지막 수단으로 내놓는다.
        if (all.isEmpty() && !lanOnly) all.addAll(other);
        return all;
    }

    /** 대표 주소 하나. 알림처럼 한 줄밖에 못 쓰는 자리에서만 쓴다. */
    static String localIp() {
        List<String> all = localIps();
        return all.isEmpty() ? "0.0.0.0" : all.get(0);
    }
}
