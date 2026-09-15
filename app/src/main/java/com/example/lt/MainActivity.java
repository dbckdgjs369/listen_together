package com.example.lt;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 100;
    private static final int REQ_PROJECTION = 101;

    static final String PREFS = "lt";
    /** 선택된 패키지 목록. 비어 있으면 "모든 앱 허용". */
    static final String KEY_APPS = "shared_apps";

    /**
     * 목록에 띄울 후보. 설치되지 않은 것은 자동으로 숨긴다.
     * 캡처 대상이 되려면 앱이 캡처를 거부(ALLOW_CAPTURE_BY_NONE)하지 않아야 한다 —
     * 스포티파이는 거부하므로 골라도 무음이다.
     */
    private static final String[][] CANDIDATES = {
            {"com.google.android.apps.youtube.music", "YouTube Music"},
            {"com.google.android.youtube",            "YouTube"},
            {"com.iloen.melon",                       "멜론"},
            {"com.ktmusic.geniemusic",                "지니뮤직"},
            {"com.spotify.music",                     "Spotify (캡처 거부됨)"},
            {"com.neowiz.android.bugs",               "벅스"},
            {"skplanet.musicmate",                    "FLO"},
            {"com.sec.android.app.music",             "삼성 뮤직"},
            {"com.apple.android.music",               "Apple Music"},
            {"com.soundcloud.android",                "SoundCloud"},
            {"com.netflix.mediaclient",               "Netflix"},
    };

    private TextView status;
    private LinearLayout found;
    private EditText manual;

    /** ip -> 기기 이름. 비콘으로 채워진다. */
    private final Map<String, String> hosts = new LinkedHashMap<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean discovering;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(56, 72, 56, 56);

        root.addView(title("🎧 같이 듣기", 26));

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setTextColor(Color.DKGRAY);
        status.setPadding(0, 16, 0, 32);
        root.addView(status);

        // ── 내가 틀기 ───────────────────────────────
        root.addView(title("내가 틀기", 18));
        // 동의 창이 "화면 공유"라고 뜨는 걸 여기서 정확히 풀어준다.
        root.addView(hint("내 폰에서 나는 소리를 친구들에게 보냅니다.\n"
                + "화면은 가져가지 않습니다 — 안드로이드가 소리 공유에도\n"
                + "같은 동의를 요구할 뿐입니다.\n"
                + "알림음·통화는 전달되지 않고, 음악·영상 소리만 갑니다."));

        root.addView(appPicker());

        root.addView(button("공유 시작", new View.OnClickListener() {
            @Override public void onClick(View v) { requestAndShare(); }
        }));
        root.addView(button("공유 중지", new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, CaptureService.class));
                render("공유를 중지했습니다");
            }
        }));

        root.addView(button("배터리 최적화 제외 (장시간 필수)", new View.OnClickListener() {
            @Override public void onClick(View v) { askIgnoreBatteryOptimization(); }
        }));

        root.addView(button("핫스팟 켜기 (테스트)", new View.OnClickListener() {
            @Override public void onClick(View v) { tryLocalHotspot(); }
        }));
        root.addView(button("핫스팟 끄기", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (reservation != null) { reservation.close(); reservation = null; }
                render("핫스팟을 껐습니다");
            }
        }));

        // ── 같이 듣기 ───────────────────────────────
        root.addView(title("같이 듣기", 18));
        root.addView(hint("친구가 공유를 시작하면 아래에 자동으로 뜹니다."));

        found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        root.addView(found);

        manual = new EditText(this);
        manual.setHint("직접 입력 (예: 192.168.219.51)");
        manual.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        root.addView(manual);

        root.addView(button("듣기 시작", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String ip = manual.getText().toString().trim();
                if (!ip.isEmpty()) join(ip);
            }
        }));
        root.addView(button("나가기", new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, PlayerService.class));
                render("듣기를 종료했습니다");
            }
        }));

        // 내용이 화면보다 길어 잘리므로 스크롤로 감싼다.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        render("대기 중");
    }

    @Override protected void onResume() {
        super.onResume();
        startDiscovery();
    }

    @Override protected void onPause() {
        super.onPause();
        discovering = false;
    }

    // ── UI 조각 ────────────────────────────────────

    private TextView title(String t, int sp) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(Color.BLACK);
        v.setPadding(0, 32, 0, 0);
        return v;
    }

    private TextView hint(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTextColor(Color.GRAY);
        v.setPadding(0, 4, 0, 8);
        return v;
    }

    private Button button(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private void render(String state) {
        status.setText(state + "\n내 주소: http://" + localIp() + ":" + CaptureService.PORT);
    }

    // ── 공유할 앱 고르기 ───────────────────────────
    //
    // 시스템 동의 창의 "앱 하나 공유"는 영상 범위만 정할 뿐 오디오를 거르지 않는다.
    // (유튜브뮤직을 골라도 유튜브 소리가 그대로 나가는 게 확인됨)
    // 게다가 사용자가 거기서 뭘 골랐는지 앱이 알 방법이 없다.
    // 그래서 실제로 효과가 있는 선택은 여기서 받는다. 선택은 저장되므로 처음 한 번만 하면 된다.

    private LinearLayout appPicker() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, 16, 0, 8);

        box.addView(hint("공유할 앱 — 선택한 앱의 소리만 나갑니다."));

        final Set<String> selected = new HashSet<>(loadSelectedApps(this));
        final List<CheckBox> boxes = new ArrayList<>();

        final CheckBox all = new CheckBox(this);
        all.setText("모든 앱 허용 (고르지 않음)");
        all.setChecked(selected.isEmpty());
        box.addView(all);

        PackageManager pm = getPackageManager();
        for (String[] c : CANDIDATES) {
            final String pkg = c[0];
            if (!isInstalled(pm, pkg)) continue;      // 없는 앱은 숨긴다
            final CheckBox cb = new CheckBox(this);
            cb.setText(c[1]);
            cb.setChecked(selected.contains(pkg));
            cb.setEnabled(!all.isChecked());
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                    if (on) selected.add(pkg); else selected.remove(pkg);
                    saveSelectedApps(MainActivity.this, selected);
                    renderPickerSummary(selected);
                }
            });
            boxes.add(cb);
            box.addView(cb);
        }

        all.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean on) {
                for (CheckBox cb : boxes) {
                    cb.setEnabled(!on);
                    if (on) cb.setChecked(false);     // 리스너가 selected 를 비운다
                }
                if (on) { selected.clear(); saveSelectedApps(MainActivity.this, selected); }
                renderPickerSummary(selected);
            }
        });

        pickerSummary = hint("");
        box.addView(pickerSummary);
        renderPickerSummary(selected);
        return box;
    }

    private TextView pickerSummary;

    private void renderPickerSummary(Set<String> selected) {
        if (pickerSummary == null) return;
        pickerSummary.setText(selected.isEmpty()
                ? "지금: 모든 앱의 음악·영상 소리가 나갑니다"
                : "지금: " + selected.size() + "개 앱의 소리만 나갑니다");
        // 실행 중이면 새 선택을 즉시 반영해야 하므로 재시작이 필요하다.
        if (isCaptureRunning()) {
            pickerSummary.append("  · 적용하려면 공유를 다시 시작하세요");
        }
    }

    private boolean isCaptureRunning() {
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        for (android.app.ActivityManager.RunningServiceInfo s
                : am.getRunningServices(Integer.MAX_VALUE)) {
            if (CaptureService.class.getName().equals(s.service.getClassName())) return true;
        }
        return false;
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try { pm.getPackageInfo(pkg, 0); return true; }
        catch (Exception e) { return false; }
    }

    static Set<String> loadSelectedApps(android.content.Context ctx) {
        Set<String> s = ctx.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getStringSet(KEY_APPS, null);
        return s == null ? new HashSet<String>() : new HashSet<>(s);
    }

    private static void saveSelectedApps(android.content.Context ctx, Set<String> apps) {
        ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putStringSet(KEY_APPS, new HashSet<>(apps)).apply();
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
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                render("권한이 거부되었습니다");
                return;
            }
        }
        requestAndShare();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) return;
        if (resultCode != RESULT_OK || data == null) {
            render("화면 캡처 동의가 취소되었습니다");
            return;
        }
        Intent svc = new Intent(this, CaptureService.class);
        svc.putExtra(CaptureService.EXTRA_CODE, resultCode);
        svc.putExtra(CaptureService.EXTRA_DATA, data);
        startForegroundService(svc);
        render("공유 중");
    }

    /**
     * 삼성은 포그라운드 서비스마저 장시간 뒤에 죽인다. 코드로는 못 막고
     * 사용자가 화이트리스트에 넣어주는 수밖에 없다.
     */
    private void askIgnoreBatteryOptimization() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) {
            render("이미 배터리 최적화에서 제외되어 있습니다");
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

    // ── 핫스팟 (LocalOnlyHotspot 실현 가능성 확인용) ──

    private static WifiManager.LocalOnlyHotspotReservation reservation;

    private void tryLocalHotspot() {
        // API 33+ 는 NEARBY_WIFI_DEVICES 가 런타임 권한이다. 없으면 SecurityException.
        String[] need = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.NEARBY_WIFI_DEVICES,
                               Manifest.permission.ACCESS_FINE_LOCATION}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : need) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(need, 102);
                return;
            }
        }
        render("핫스팟 요청 중…");
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        try {
            wm.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation res) {
                    reservation = res;
                    String ssid = "?", pw = "?";
                    try {
                        SoftApConfiguration c = res.getSoftApConfiguration();
                        ssid = String.valueOf(c.getSsid());
                        pw = String.valueOf(c.getPassphrase());
                    } catch (Throwable t) {
                        ssid = "설정을 읽을 수 없음: " + t;
                    }
                    Log.i(CaptureService.TAG, "HOTSPOT ok ssid=" + ssid + " pw=" + pw);
                    render("핫스팟 켜짐\nSSID: " + ssid + "\n비번: " + pw
                            + "\nQR: WIFI:T:WPA;S:" + ssid + ";P:" + pw + ";;");
                }

                @Override
                public void onFailed(int reason) {
                    String why;
                    switch (reason) {
                        case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                            why = "사용 가능한 채널 없음"; break;
                        case WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC:
                            why = "일반 오류"; break;
                        case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                            why = "호환되지 않는 모드 (이미 테더링 중일 수 있음)"; break;
                        case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                            why = "테더링이 허용되지 않음"; break;
                        default:
                            why = "알 수 없음(" + reason + ")";
                    }
                    Log.w(CaptureService.TAG, "HOTSPOT failed: " + why);
                    render("핫스팟 실패 — " + why);
                }

                @Override
                public void onStopped() {
                    Log.i(CaptureService.TAG, "HOTSPOT stopped");
                    render("핫스팟이 중지되었습니다");
                }
            }, ui);
        } catch (Throwable t) {
            Log.e(CaptureService.TAG, "HOTSPOT threw", t);
            render("핫스팟 호출 예외 — " + t);
        }
    }

    // ── 참여자 ─────────────────────────────────────

    private void join(String ip) {
        Intent svc = new Intent(this, PlayerService.class);
        svc.putExtra(PlayerService.EXTRA_HOST, ip);
        startForegroundService(svc);
        render("듣는 중 — " + ip);
    }

    /** 호스트가 1초마다 뿌리는 UDP 비콘을 받아 목록을 만든다. */
    private void startDiscovery() {
        if (discovering) return;
        discovering = true;
        new Thread(new Runnable() {
            @Override public void run() {
                DatagramSocket ds = null;
                try {
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
                            String[] f = new String(p.getData(), 0, p.getLength(), "UTF-8").split("\\|");
                            if (f.length >= 3 && "LT1".equals(f[0]) && !isMine(f[2])) {
                                final String name = f[1], ip = f[2];
                                if (!name.equals(hosts.put(ip, name))) {
                                    ui.post(new Runnable() {
                                        @Override public void run() { refreshHosts(); }
                                    });
                                }
                            }
                        } catch (java.net.SocketTimeoutException ignored) {
                        }
                    }
                } catch (Exception e) {
                    ui.post(new Runnable() {
                        @Override public void run() {
                            status.append("\n자동 발견 실패 — 주소를 직접 입력하세요");
                        }
                    });
                } finally {
                    if (ds != null) ds.close();
                }
            }
        }, "lt-discover").start();
    }

    private void refreshHosts() {
        found.removeAllViews();
        for (final Map.Entry<String, String> e : hosts.entrySet()) {
            found.addView(button(e.getValue() + " 님의 소리  (" + e.getKey() + ")",
                    new View.OnClickListener() {
                        @Override public void onClick(View v) { join(e.getKey()); }
                    }));
        }
    }

    // ── 공용 ───────────────────────────────────────

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
     * 참여자가 붙어야 할 주소를 고른다. 우선순위가 중요하다.
     *  1) 핫스팟(swlan0/ap0) — 켜져 있다면 참여자는 여기에 들어와 있다
     *  2) 일반 Wi-Fi(wlan0)
     *  3) 그 외 (모바일 데이터 rmnet 은 다른 기기가 못 붙지만 없는 것보단 낫다)
     * STA+AP 가 동시에 뜨므로 단순히 "먼저 잡히는 것"을 고르면 틀린 주소를 광고한다.
     */
    static String localIp() {
        String hotspot = null, wifi = null, other = null;
        try {
            List<NetworkInterface> ifs = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : ifs) {
                if (nif.isLoopback() || !nif.isUp()) continue;
                String name = nif.getName();
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (name.startsWith("swlan") || name.startsWith("ap")) {
                        if (hotspot == null) hotspot = ip;
                    } else if (name.startsWith("wlan")) {
                        if (wifi == null) wifi = ip;
                    } else if (other == null) {
                        other = ip;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (hotspot != null) return hotspot;
        if (wifi != null) return wifi;
        return other != null ? other : "0.0.0.0";
    }
}
