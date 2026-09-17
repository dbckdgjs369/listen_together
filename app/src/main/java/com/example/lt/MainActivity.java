package com.example.lt;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 100;
    private static final int REQ_PROJECTION = 101;

    static final String PREFS = "lt";
    /**
     * 예전에 쓰던 "공유할 앱" 화이트리스트 키. **이제 읽지 않는다.**
     * 지우는 코드만 남긴 이유는, 예전 버전에서 앱을 골라둔 사용자가 업데이트하면
     * 화면에는 선택 UI 가 없는데 소리는 계속 걸러지는 유령 상태가 되기 때문이다.
     */
    private static final String KEY_APPS_LEGACY = "shared_apps";
    /** 호스트가 정한 방 이름. 기기 모델명(SM-S911N)보다 사람이 알아보기 쉽다. */
    private static final String KEY_ROOM_NAME = "room_name";

    /** 상태 한 줄 + 옆의 점. */
    private TextView status;
    private View dot;
    /** 내 주소들. 모노스페이스로 띄우고 길게 눌러 복사할 수 있다. */
    private TextView addr;
    /** 공유 시작/중지를 겸하는 주 버튼. */
    private Button shareBtn;
    private Button battBtn;
    /** 청취 상태 한 줄 — PlayerService 가 쓰는 값을 그대로 비춘다. */
    private TextView joining;
    private LinearLayout joinCard;
    private Button leaveBtn;
    private TextView emptyHint;
    /** 핫스팟 접속용 QR. 꺼져 있을 땐 카드째 숨긴다. */
    private ImageView qr;
    private TextView qrHint;
    private LinearLayout qrCard;
    private LinearLayout found;
    private EditText manual;
    private EditText manualPw;
    /** 호스트가 정하는 방 이름. 공유 중에는 잠근다 — 서비스가 이미 값을 들고 떠났다. */
    private EditText roomNameField;
    /** 공유 중일 때만 뜨는 줄. 방 이름과 비밀번호를 크게 보여준다. */
    private TextView roomInfo;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 앱 선택 기능을 걷어냈으니 남아 있던 선택도 같이 지운다.
        // 안 지우면 예전에 골라둔 사용자는 화면에 아무 표시도 없이 소리가 걸러진다.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(KEY_APPS_LEGACY).apply();

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(40));

        // ── 헤더 ────────────────────────────────────
        TextView h1 = new TextView(this);
        h1.setText("같이 듣기");
        h1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        h1.setTypeface(Typeface.DEFAULT_BOLD);
        h1.setTextColor(color(R.color.text));
        h1.setLetterSpacing(-0.02f);
        root.addView(h1);

        TextView h2 = new TextView(this);
        h2.setText("이어폰 한쪽씩 나눠 끼지 않아도 됩니다");
        h2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        h2.setTextColor(color(R.color.text_dim));
        h2.setPadding(0, dp(5), 0, dp(22));
        root.addView(h2);

        // ── 상태 카드 ───────────────────────────────
        // 예전엔 상태가 회색 글씨 한 줄로 화면 맨 위에 붙어 있어서 눈에 안 들어왔다.
        // 이 앱에서 제일 자주 확인하는 정보라 카드로 올린다.
        LinearLayout stateCard = card();

        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);

        dot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dotLp.rightMargin = dp(9);
        dot.setLayoutParams(dotLp);
        stateRow.addView(dot);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        status.setTextColor(color(R.color.text));
        stateRow.addView(status);
        stateCard.addView(stateRow);

        // 공유 중에만 뜬다. 친구한테 불러줄 값이라 화면에서 제일 읽기 쉬워야 한다.
        roomInfo = new TextView(this);
        roomInfo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        roomInfo.setTextColor(color(R.color.text));
        roomInfo.setLineSpacing(dp(4), 1f);
        roomInfo.setPadding(0, dp(14), 0, 0);
        roomInfo.setVisibility(View.GONE);
        stateCard.addView(roomInfo);

        TextView addrLabel = label("내 주소 · 친구가 브라우저로 여기 들어오면 됩니다");
        addrLabel.setPadding(0, dp(16), 0, dp(4));
        stateCard.addView(addrLabel);

        addr = new TextView(this);
        addr.setTypeface(Typeface.MONOSPACE);
        addr.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        addr.setTextColor(color(R.color.text));
        addr.setLineSpacing(dp(3), 1f);
        // 길게 눌러 복사할 수 있게 둔다. 받아적게 하지 않는 게 이 앱의 방향이다.
        addr.setTextIsSelectable(true);
        stateCard.addView(addr);
        root.addView(stateCard);

        // ── 방 이름 ─────────────────────────────────
        // 안 정하면 기기 모델명(SM-S911N)이 그대로 목록에 뜬다. 카페에서 그걸 보고
        // 어느 게 친구 방인지 아는 사람은 없다.
        TextView nameLabel = label("방 이름 · 친구 목록에 이렇게 뜹니다");
        nameLabel.setPadding(dp(2), 0, dp(2), dp(6));
        root.addView(nameLabel);

        roomNameField = field("비워두면 " + Build.MODEL);
        roomNameField.setText(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_ROOM_NAME, ""));
        roomNameField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20)});
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.bottomMargin = dp(12);
        roomNameField.setLayoutParams(nameLp);
        root.addView(roomNameField);

        // ── 주 동작 ─────────────────────────────────
        shareBtn = primary("공유 시작", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (CaptureService.sharing) {
                    stopSharing();
                } else {
                    requestAndShare();
                }
            }
        });
        root.addView(shareBtn);

        // 동의 창이 "화면 녹화"라고 뜨는 걸 버튼 바로 밑에서 미리 풀어준다.
        // 놀란 뒤에 설명하는 것과 순서가 다르다.
        TextView consentNote = label("음악·영상 소리만 전달됩니다. 알림음·통화는 가지 않고,"
                + " 화면도 보내지 않습니다 — 안드로이드가 소리 공유에도 같은 동의를 요구할 뿐입니다.");
        consentNote.setPadding(dp(2), dp(10), dp(2), dp(22));
        root.addView(consentNote);

        // ── 핫스팟 (Wi-Fi 가 없을 때) ────────────────
        LinearLayout hsRow = new LinearLayout(this);
        hsRow.setOrientation(LinearLayout.HORIZONTAL);
        hsRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button hsOn = ghost("핫스팟 켜기", new View.OnClickListener() {
            @Override public void onClick(View v) { tryLocalHotspot(); }
        });
        Button hsOff = ghost("끄기", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (reservation != null) {
                    reservation.close();
                    reservation = null;
                    showQr(null, null);
                    render(CaptureService.sharing ? "공유 중" : "대기 중");
                    return;
                }
                showQr(null, null);
                // 설정에서 켠 핫스팟은 우리가 못 끈다. 끌 게 없는데 아무 말도 없으면
                // 눌렀는데 안 꺼진 것처럼 보인다 — 그것도 침묵으로 속이는 것이다.
                if (hotspotUp()) notice("설정에서 켠 핫스팟은 앱이 끄지 못합니다");
                else render(CaptureService.sharing ? "공유 중" : "대기 중");
            }
        });
        LinearLayout.LayoutParams w2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f);
        w2.rightMargin = dp(8);
        hsOn.setLayoutParams(w2);
        hsOff.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        hsRow.addView(hsOn);
        hsRow.addView(hsOff);
        root.addView(hsRow);

        // 핫스팟이 켜지면 여기에 QR 이 뜬다. 상대는 기본 카메라로 찍기만 하면 된다.
        qrCard = card();
        qrCard.setVisibility(View.GONE);
        qr = new ImageView(this);
        qr.setAdjustViewBounds(true);
        // 흰 여백(quiet zone)을 넉넉히 준다. 어두운 카드 위에 QR 을 바로 올리면
        // 스캐너가 코드 경계를 못 잡는 일이 있다 — 장식이 아니라 인식률 문제다.
        GradientDrawable qrBg = new GradientDrawable();
        qrBg.setColor(Color.WHITE);
        qrBg.setCornerRadius(dp(10));
        qr.setBackground(qrBg);
        qr.setPadding(dp(10), dp(10), dp(10), dp(10));
        qrCard.addView(qr);
        qrHint = label("");
        qrHint.setPadding(0, dp(12), 0, 0);
        qrHint.setGravity(Gravity.CENTER_HORIZONTAL);
        qrCard.addView(qrHint);
        LinearLayout.LayoutParams qrLp =
                (LinearLayout.LayoutParams) qrCard.getLayoutParams();
        qrLp.topMargin = dp(12);
        root.addView(qrCard);

        // 이미 제외돼 있으면 버튼을 아예 안 만든다. 할 일이 없는 버튼은 군더더기다.
        PowerManager pmgr = getSystemService(PowerManager.class);
        if (pmgr == null || !pmgr.isIgnoringBatteryOptimizations(getPackageName())) {
            battBtn = ghost("배터리 최적화 제외 (장시간 공유에 필요)", new View.OnClickListener() {
                @Override public void onClick(View v) { askIgnoreBatteryOptimization(); }
            });
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = dp(8);
            battBtn.setLayoutParams(bl);
            root.addView(battBtn);
        }

        root.addView(divider());

        // ── 같이 듣기 ───────────────────────────────
        root.addView(sectionTitle("같이 듣기"));

        // 지금 어디에 붙어 있는지를 제일 먼저 보여준다. 이게 없으면 사용자는
        // 자기가 이미 참여 중이라는 것도, 그래서 새 호스트를 누르려면 먼저
        // 나가야 한다는 것도 알 수 없다. 소리가 안 날 때 볼 곳이 여기다.
        joinCard = card();
        joining = new TextView(this);
        joining.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        joining.setTextColor(color(R.color.text));
        joining.setLineSpacing(dp(3), 1f);
        joinCard.addView(joining);

        leaveBtn = ghost("나가기", new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopService(new Intent(MainActivity.this, PlayerService.class));
                PlayerService.currentHost = null;
                refreshJoining();
            }
        });
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lb.topMargin = dp(12);
        leaveBtn.setLayoutParams(lb);
        joinCard.addView(leaveBtn);
        root.addView(joinCard);

        found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        root.addView(found);

        emptyHint = label("친구가 공유를 시작하면 여기에 자동으로 뜹니다.");
        emptyHint.setPadding(dp(2), dp(2), dp(2), dp(14));
        root.addView(emptyHint);

        manual = field("주소 직접 입력 (예: 192.168.219.51)");
        root.addView(manual);

        // 자동 발견이 막힌 망(공공 AP 의 클라이언트 격리 등)에서는 주소를 직접 넣게
        // 되는데, 그때도 비밀번호는 똑같이 필요하다. 목록 경로에만 입력칸을 두면
        // 여기로 들어온 사람은 401 만 받고 왜인지 모른다.
        manualPw = field("비밀번호 4자리");
        manualPw.setInputType(InputType.TYPE_CLASS_NUMBER);
        manualPw.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        LinearLayout.LayoutParams mpLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mpLp.topMargin = dp(8);
        manualPw.setLayoutParams(mpLp);
        root.addView(manualPw);

        Button listenBtn = ghost("이 주소로 듣기", new View.OnClickListener() {
            @Override public void onClick(View v) {
                String ip = manual.getText().toString().trim();
                if (!ip.isEmpty()) join(ip, manualPw.getText().toString().trim());
            }
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ll.topMargin = dp(8);
        listenBtn.setLayoutParams(ll);
        root.addView(listenBtn);

        // 내용이 화면보다 길어 잘리므로 스크롤로 감싼다.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setBackgroundColor(color(R.color.bg));
        scroll.setClipToPadding(false);
        scroll.addView(root);

        // targetSdk 35+ 는 edge-to-edge 가 강제라 상태바·내비바가 내용 위를 덮는다.
        // 인셋을 직접 먹이지 않으면 제목이 시계에 붙고 맨 아래 버튼이 내비바에 깔린다.
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(
                    View v, android.view.WindowInsets insets) {
                android.graphics.Insets bars =
                        insets.getInsets(android.view.WindowInsets.Type.systemBars());
                root.setPadding(dp(20), dp(20) + bars.top, dp(20), dp(32) + bars.bottom);
                return insets;
            }
        });
        setContentView(scroll);
        render("대기 중");
        refreshJoining();
    }

    @Override protected void onResume() {
        super.onResume();
        startDiscovery();
        ui.removeCallbacks(joinTicker);
        ui.post(joinTicker);
    }

    @Override protected void onPause() {
        super.onPause();
        discovering = false;
        ui.removeCallbacks(joinTicker);
    }

    // ── UI 조각 ────────────────────────────────────

    /**
     * 화면 조각을 만드는 최소한의 디자인 시스템.
     *
     * 예전 코드는 치수를 전부 **픽셀 상수**로 박아놨다(`setPadding(56, 72, ...)`).
     * 3배 밀도 기기에서 56px 은 19dp 라 의도와 다르게 좁았고, 기기가 바뀌면 또 달라졌다.
     * 여기서부터는 전부 dp 로만 쓴다.
     */
    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int color(int resId) {
        return getResources().getColor(resId, getTheme());
    }

    /** 둥근 카드. 이 앱의 정보 단위는 전부 카드에 담는다. */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color(R.color.surface));
        g.setCornerRadius(dp(18));
        g.setStroke(dp(1), color(R.color.stroke));
        c.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView sectionTitle(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(color(R.color.text));
        v.setPadding(0, dp(4), 0, dp(12));
        return v;
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        v.setTextColor(color(R.color.text_dim));
        v.setLineSpacing(dp(2), 1f);
        return v;
    }

    private View divider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(28);
        lp.bottomMargin = dp(24);
        v.setLayoutParams(lp);
        v.setBackgroundColor(color(R.color.stroke));
        return v;
    }

    /**
     * 채워진 주 버튼. `new Button(ctx, null, 0)` 으로 만드는 이유는 플랫폼 기본
     * 버튼 배경(회색 사각형)을 떼어내기 위해서다. 생성자에 스타일 0 을 주지 않으면
     * 우리가 setBackground 로 넣은 모양 위에 기본 배경이 남아 어긋난다.
     */
    private Button primary(String t, View.OnClickListener l) {
        Button b = new Button(this, null, 0);
        b.setText(t);
        b.setAllCaps(false);
        // 스타일 0 으로 만들면 배경뿐 아니라 **gravity 도 같이 날아간다**.
        // 안 넣으면 글자가 버튼 좌상단에 붙어 고장난 것처럼 보인다.
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.WHITE);
        b.setOnClickListener(l);
        b.setBackground(fillRipple(color(R.color.accent)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        b.setLayoutParams(lp);
        return b;
    }

    /** 테두리만 있는 보조 버튼. 주 버튼과 경쟁하지 않게 한다. */
    private Button ghost(String t, View.OnClickListener l) {
        Button b = new Button(this, null, 0);
        b.setText(t);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTextColor(color(R.color.text_dim));
        b.setOnClickListener(l);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), color(R.color.stroke));
        b.setBackground(new RippleDrawable(
                ColorStateList.valueOf(color(R.color.surface_alt)), g, null));
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));
        return b;
    }

    /** 입력칸. 카드와 같은 모양을 쓴다 — 화면에 모양이 하나 더 늘어날 이유가 없다. */
    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        e.setTextColor(color(R.color.text));
        e.setHintTextColor(color(R.color.text_dim));
        e.setSingleLine(true);
        e.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color(R.color.surface));
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), color(R.color.stroke));
        e.setBackground(g);
        return e;
    }

    private RippleDrawable fillRipple(int fill) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(16));
        return new RippleDrawable(
                ColorStateList.valueOf(color(R.color.accent_press)), g, null);
    }

    /** 점 하나로 상태를 말한다. 글자를 읽기 전에 색으로 먼저 안다. */
    private void setDot(int c) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(c);
        dot.setBackground(g);
    }

    /**
     * 잠깐 띄우는 알림. **주기 렌더보다 우선한다.**
     *
     * joinTicker 가 0.5 초마다 render("대기 중") 을 쓰기 때문에, 그냥 render 로 띄운
     * 메시지는 반 초 만에 지워진다. 핫스팟이 권한 예외로 실패했을 때 화면에는
     * 아무 일도 안 일어난 것처럼 보였다 — 로그를 안 봤으면 기종 문제로 오진할 뻔했다.
     *
     * 공유 중일 때도 이게 이긴다. 듣는 사람 수보다 "왜 안 됐는지" 가 급하다.
     */
    private void notice(String msg) {
        notice = msg;
        noticeUntil = SystemClock.elapsedRealtime() + NOTICE_MS;
        render(msg);
    }

    private String notice;
    private long noticeUntil;

    /** 한 문장을 읽을 만큼은 되고, 듣는 사람 수를 오래 가리지는 않을 만큼. */
    private static final long NOTICE_MS = 5000;

    private void render(String state) {
        boolean live = CaptureService.sharing;
        // 만료된 알림은 여기서 버린다. 그러면 다음 줄이 알아서 원래 상태를 되돌린다.
        if (notice != null && SystemClock.elapsedRealtime() >= noticeUntil) notice = null;

        if (notice != null) {
            status.setText(notice);
        } else if (live) {
            int n = CaptureService.listeners;
            status.setText(n > 0 ? "공유 중 · " + n + "명이 듣는 중" : "공유 중 · 기다리는 중");
        } else {
            status.setText(state);
        }
        setDot(color(live ? R.color.live : R.color.text_dim));
        shareBtn.setText(live ? "공유 중지" : "공유 시작");
        shareBtn.setBackground(fillRipple(color(live ? R.color.danger : R.color.accent)));

        // 공유 중에는 이름을 잠근다. 서비스는 시작할 때 값을 한 번 받아가므로,
        // 여기서 고쳐도 목록에 뜨는 이름은 안 바뀐다 — 칸이 열려 있으면 바뀐 줄 안다.
        roomNameField.setEnabled(!live);
        roomNameField.setAlpha(live ? 0.45f : 1f);

        if (live && !CaptureService.roomPw.isEmpty()) {
            // 비밀번호는 불러주라고 있는 값이다. 상태 줄 다음으로 크게 둔다.
            android.text.SpannableStringBuilder sb2 = new android.text.SpannableStringBuilder();
            sb2.append("방 이름   ").append(CaptureService.roomName).append('\n');
            int at = sb2.length();
            sb2.append("비밀번호  ").append(CaptureService.roomPw);
            sb2.setSpan(new android.text.style.RelativeSizeSpan(1.45f),
                    at + "비밀번호  ".length(), sb2.length(), 0);
            sb2.setSpan(new android.text.style.ForegroundColorSpan(color(R.color.live)),
                    at + "비밀번호  ".length(), sb2.length(), 0);
            roomInfo.setText(sb2);
            roomInfo.setVisibility(View.VISIBLE);
        } else {
            roomInfo.setVisibility(View.GONE);
        }

        // 주소를 전부 보여준다. 어느 쪽으로 들어올지는 친구 사정이라 우리가 못 고른다.
        List<String> ips = localIps();
        if (ips.isEmpty()) {
            addr.setText("네트워크 없음 — Wi-Fi 나 핫스팟을 켜세요");
            addr.setTextColor(color(R.color.danger));
            return;
        }
        addr.setTextColor(color(R.color.text));
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("http://").append(ip).append(':').append(CaptureService.PORT);
        }
        addr.setText(sb.toString());
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
                notice("권한이 거부되었습니다");
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
            notice("화면 캡처 동의가 취소되었습니다");
            return;
        }
        // 다음에도 같은 이름을 쓰게 저장한다. 매번 다시 적게 하면 결국 아무도 안 적는다.
        String name = roomNameField.getText().toString().trim();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_ROOM_NAME, name).apply();

        // 반대 방향 가드. 듣는 중이었다면 여기서 끊는다 — 공유 버튼을 누른 순간이 아니라
        // **동의가 끝난 뒤**다. 동의를 취소했는데 듣던 것만 날아가면 손해만 본다.
        // 이쪽은 의도가 분명하니 되묻지 않고, 사라진 이유만 알린다.
        if (PlayerService.currentHost != null) {
            stopService(new Intent(this, PlayerService.class));
            android.widget.Toast.makeText(this,
                    "듣기를 멈추고 공유를 시작합니다", android.widget.Toast.LENGTH_SHORT).show();
        }

        Intent svc = new Intent(this, CaptureService.class);
        svc.putExtra(CaptureService.EXTRA_CODE, resultCode);
        svc.putExtra(CaptureService.EXTRA_DATA, data);
        svc.putExtra(CaptureService.EXTRA_ROOM_NAME, name);
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
            notice("이미 배터리 최적화에서 제외되어 있습니다");
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

    /**
     * 핫스팟 요청 일련번호. 감시 타이머가 **자기가 건 요청**에만 반응하게 하는 표다.
     * 콜백이 오면 번호를 올려 타이머를 무효로 만든다 — 안 그러면 실패 메시지를
     * 띄운 12초 뒤에 "응답하지 않습니다" 가 그 위를 덮는다.
     */
    private long hotspotReq;

    /**
     * 핫스팟 접속 QR 을 띄운다. ssid 가 null 이면 감춘다.
     *
     * `WIFI:T:WPA;S:이름;P:비번;;` 는 안드로이드가 시스템 차원에서 아는 포맷이라,
     * 상대는 **기본 카메라로 찍기만 하면** 접속 알림이 뜬다. 앱도 필요 없다.
     * SSID·비번을 불러주고 받아적게 하는 것과는 마찰이 비교가 안 된다.
     */
    private void showQr(String ssid, String pw) {
        if (qr == null) return;
        if (ssid == null) {
            qrCard.setVisibility(View.GONE);
            qr.setImageBitmap(null);
            return;
        }
        qrCard.setVisibility(View.VISIBLE);
        // 값 안의 `;` `:` `\` `,` 는 이 포맷에서 구분자라 escape 해야 한다.
        // LocalOnlyHotspot 비번은 영숫자뿐이라 지금은 걸릴 일이 없지만,
        // 사용자가 SSID 를 정하게 되는 순간 조용히 깨질 자리다.
        String payload = "WIFI:T:WPA;S:" + qrEscape(ssid) + ";P:" + qrEscape(pw) + ";;";
        try {
            int size = 640;
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(payload, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    bmp.setPixel(x, y, m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            qr.setImageBitmap(bmp);
            qr.setVisibility(View.VISIBLE);
            qrHint.setText("친구 카메라로 이 QR 을 찍으면 접속됩니다."
                    + "\n안 되면 직접: " + ssid + " / " + pw);
            qrHint.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.w(CaptureService.TAG, "QR 생성 실패: " + t);
            qr.setVisibility(View.GONE);
            qrHint.setText("QR 생성 실패 — 직접 입력: " + ssid + " / " + pw);
            qrHint.setVisibility(View.VISIBLE);
        }
    }

    private static String qrEscape(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;")
                .replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"");
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
                notice("핫스팟은 이미 켜져 있습니다");
            } else {
                notice("핫스팟은 켜져 있으나 비밀번호를 읽을 수 없습니다 — 설정에서 확인하세요");
            }
            return;
        }

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
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);

        // 여기서 미리 막지 않으면 **콜백이 영영 안 온다.** 비행기모드면 시스템이
        // 요청을 받아놓고 조용히 버린다(WifiController: "drop softap requests when in
        // airplane mode") — onStarted 도 onFailed 도 안 불린다. 그러면 화면은
        // "요청 중…" 에서 멈춰 있다가 아무 설명 없이 원래대로 돌아간다.
        // 실패를 못 알리느니 아예 시작을 안 하고 이유를 말하는 편이 낫다.
        if (Settings.Global.getInt(getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0) {
            notice("비행기모드에서는 핫스팟을 켤 수 없습니다");
            return;
        }
        if (wm == null) {
            notice("이 기기에서 Wi-Fi 를 쓸 수 없습니다");
            return;
        }
        // **Wi-Fi 가 켜져 있는지는 보지 않는다.** 한때 여기서 막았는데 틀렸다 —
        // 노트9 에서 Wi-Fi 를 끈 채로 핫스팟이 멀쩡히 떴다(swlan0 192.168.43.1).
        // 핫스팟은 Wi-Fi 접속(STA)과 별개로 도는 기능이라 켜져 있을 이유가 없다.

        // 설정에서 켠 핫스팟이 이미 떠 있으면 요청해도 ERROR_INCOMPATIBLE_MODE 로 떨어진다.
        // 그런데 사용자한테 이건 실패가 아니다 — 핫스팟은 켜져 있고, 주소 칸에는 이미
        // 그 주소가 떠 있으며, 앱은 그걸로 잘 돈다. "실패" 라고 하면 멀쩡한 상황을
        // 고장난 것처럼 보이게 만든다. SSID/비번을 읽는 공개 API 가 없어 QR 만 못 그린다.
        if (hotspotUp()) {
            notice("핫스팟이 이미 켜져 있습니다 — 친구는 설정에 뜬 비밀번호로 붙으면 됩니다");
            return;
        }

        notice("핫스팟 요청 중…");
        // 콜백이 안 오는 길이 또 있을 수 있다. 비행기모드는 위에서 걸렀지만
        // 그게 유일한 경우라는 보장이 없으니, 잠잠하면 잠잠하다고 말하게 한다.
        // 말없이 원래 화면으로 돌아가는 것보다 낫다.
        final long ticket = ++hotspotReq;
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                if (ticket == hotspotReq && reservation == null) {
                    notice("핫스팟이 응답하지 않습니다 — 설정에서 직접 켜 보세요");
                }
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
                    final String s = cfg == null ? null : cfg[0];
                    final String p = cfg == null ? null : cfg[1];
                    if (cfg == null) {
                        // 설정을 못 읽으면 QR 은 없다. 그래도 핫스팟은 켜졌으니
                        // 어디서 비번을 보는지는 알려준다.
                        notice("핫스팟 켜짐 — 비밀번호는 설정 > 핫스팟에서 확인하세요");
                    } else {
                        notice("핫스팟 켜짐");
                    }
                    ui.post(new Runnable() {
                        @Override public void run() { showQr(s, p); }
                    });
                }

                @Override
                public void onFailed(int reason) {
                    hotspotReq++;   // 감시 타이머 무효화
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
                    notice("핫스팟 실패 — " + why);
                }

                @Override
                public void onStopped() {
                    Log.i(CaptureService.TAG, "HOTSPOT stopped");
                    notice("핫스팟이 중지되었습니다");
                }
            }, ui);
        } catch (Throwable t) {
            hotspotReq++;   // 감시 타이머 무효화
            Log.e(CaptureService.TAG, "HOTSPOT threw", t);
            notice("핫스팟 호출 예외 — " + t);
        }
    }

    // ── 참여자 ─────────────────────────────────────

    private void join(final String ip, final String pw) {
        // 마지막 관문이다. tapRoom 이 먼저 물어보지만 '주소 직접 입력' 은 여기로 바로 온다.
        // stopSharing() 이 sharing 을 그 자리에서 내리므로 이 재귀는 한 번만 돈다.
        if (CaptureService.sharing) {
            confirmStopSharing(new Runnable() {
                @Override public void run() { join(ip, pw); }
            });
            return;
        }

        Intent svc = new Intent(this, PlayerService.class);
        svc.putExtra(PlayerService.EXTRA_HOST, ip);
        svc.putExtra(PlayerService.EXTRA_PW, pw);
        startForegroundService(svc);
        // 여기서 "듣는 중" 이라고 단정하지 않는다. 실제로 붙었는지는 서비스만 안다.
        refreshJoining();
    }

    private void stopSharing() {
        stopService(new Intent(this, CaptureService.class));
        CaptureService.sharing = false;   // 서비스가 죽기 전 화면부터 정직하게
        render("대기 중");
    }

    /**
     * 공유와 듣기를 동시에 하면 소리가 돈다.
     *
     * 캡처는 모든 앱의 USAGE_MEDIA 를 먹고(CaptureService:443) 우리 재생도
     * USAGE_MEDIA 라(PlayerService:356), 내가 듣는 소리가 내 캡처에 다시 잡혀 나간다.
     * 서로의 방에 들어가면 그 루프가 양쪽으로 돈다 — 실제로 소리가 계속 나왔다.
     *
     * 그래서 한 번에 하나만 허용한다. excludeUid 로 내 재생만 캡처에서 빼도 루프는
     * 끊기지만, 내 폰은 A 를 내보내는데 내 귀엔 B 가 들리는 설명 안 되는 상태가 남는다.
     * 루프를 끊는 것보다 그 상태를 못 만들게 하는 쪽이 맞다.
     */
    private void confirmStopSharing(final Runnable then) {
        new AlertDialog.Builder(this)
                .setTitle("공유를 멈추고 들을까요?")
                .setMessage("공유하면서 동시에 들을 수는 없습니다."
                        + " 내 폰이 내보내는 소리와 듣는 소리가 서로 물려서 소리가 돕니다.\n\n"
                        + "다시 공유하려면 화면 녹화 동의를 한 번 더 받아야 합니다.")
                .setPositiveButton("멈추고 듣기", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        stopSharing();
                        then.run();
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /**
     * 서비스의 상태를 화면에 비춘다. 0.5초마다 돌며, 화면이 꺼져 있을 땐 멈춘다.
     *
     * 굳이 폴링인 이유는 PoC 라서다 — 브로드캐스트나 바인딩을 끌어오는 것보다
     * 상태 한 줄 읽는 게 싸고, 틀릴 여지도 없다.
     */
    private void refreshJoining() {
        if (joining == null) return;
        String host = PlayerService.currentHost;
        if (host == null) {
            joining.setText("참여 중이 아닙니다.");
            joining.setTextColor(color(R.color.text_dim));
            leaveBtn.setVisibility(View.GONE);
            joinCard.setVisibility(rooms.isEmpty() ? View.GONE : View.VISIBLE);
        } else if (PlayerService.denied) {
            // 거절은 '재시도 중인 실패'와 다르다. 여기서 "참여 중" 이라고 쓰면
            // 붙은 줄 알고 소리가 왜 안 나는지를 엉뚱한 데서 찾게 된다.
            joining.setText("⛔  " + host + "\n" + PlayerService.stateText
                    + "\n\n아래 칸에 비밀번호를 고쳐 넣고 다시 누르세요.");
            joining.setTextColor(color(R.color.danger));
            leaveBtn.setVisibility(View.VISIBLE);
            joinCard.setVisibility(View.VISIBLE);
        } else {
            joining.setText("▶  " + host + " 에 참여 중\n" + PlayerService.stateText
                    + "\n\n다른 사람을 누르면 그쪽으로 옮겨갑니다.");
            joining.setTextColor(color(R.color.text));
            leaveBtn.setVisibility(View.VISIBLE);
            joinCard.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 0.5초마다 서비스 상태를 화면에 비춘다. 화면이 꺼져 있을 땐 멈춘다.
     *
     * 호스트 쪽(공유 중/듣는 사람 수)도 같이 갱신한다. 예전엔 버튼을 누른 직후
     * 한 번만 그려서, 친구가 들어와도 화면은 그대로였다.
     */
    private final Runnable joinTicker = new Runnable() {
        @Override public void run() {
            if (pruneRooms()) refreshHosts();
            refreshJoining();
            render(CaptureService.sharing ? "공유 중" : "대기 중");
            ui.postDelayed(this, 500);
        }
    };

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
        new Thread(new Runnable() {
            @Override public void run() {
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
                            ui.post(new Runnable() {
                                @Override public void run() { if (absorb(f)) refreshHosts(); }
                            });
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
                    // 쥔 채로 두면 화면을 꺼도 Wi-Fi 가 계속 깨어 있어 배터리를 먹는다.
                    if (mcast != null && mcast.isHeld()) mcast.release();
                }
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
     * 공유를 껐다 켜면 방 ID 가 새로 생긴다(CaptureService:233). 예전 줄이 안 지워져서,
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
                // 사라지면, 화면에 ▶ 를 붙일 자리도 같이 없어진다.
                if (a.getKey().equals(PlayerService.currentHost)) continue;
                if (now - a.getValue() > ROOM_TTL_MS) { ai.remove(); changed = true; }
            }
            if (r.ips.isEmpty()) { it.remove(); changed = true; }
        }
        return changed;
    }

    private void refreshHosts() {
        found.removeAllViews();
        for (Room r : rooms.values()) found.addView(hostRow(r));
        if (emptyHint != null) {
            emptyHint.setVisibility(rooms.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * 발견된 호스트 한 줄. 버튼 하나에 이름과 IP 를 몰아넣는 대신 두 줄로 나눈다.
     * 이름은 크게(누를 대상), 주소는 작게(맞는지 확인할 값) — 역할이 다르다.
     */
    private View hostRow(final Room r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));

        GradientDrawable g = new GradientDrawable();
        g.setColor(color(R.color.surface));
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), color(R.color.stroke));
        row.setBackground(new RippleDrawable(
                ColorStateList.valueOf(color(R.color.surface_alt)), g, null));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView t = new TextView(this);
        // 지금 듣고 있는 대상이면 바로 표시한다. 목록에서 자기 자리를 잃지 않게.
        boolean current = r.ips.containsKey(PlayerService.currentHost);
        t.setText((current ? "▶  " : "") + (r.locked ? "🔒  " : "") + r.name);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color(current ? R.color.live : R.color.text));
        row.addView(t);

        TextView s = new TextView(this);
        // 주소가 여럿이면 다 보여준다. 어느 망에 붙어 있느냐에 따라 되는 게 다르고,
        // 우리가 대신 골라주면 틀렸을 때 사용자가 손쓸 방법이 없다.
        StringBuilder sb = new StringBuilder();
        for (String one : r.ips.keySet()) {
            if (sb.length() > 0) sb.append("  ·  ");
            sb.append(one);
        }
        s.setText(sb.toString());
        s.setTypeface(Typeface.MONOSPACE);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        s.setTextColor(color(R.color.text_dim));
        s.setPadding(0, dp(3), 0, 0);
        row.addView(s);

        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { tapRoom(r); }
        });
        return row;
    }

    /**
     * 방을 눌렀을 때. 잠겨 있으면 비밀번호를 먼저 받는다.
     *
     * 맞는지는 호스트만 안다 — 여기서는 형식도 안 따진다. 틀리면 PlayerService 가
     * 401 을 받아 상태 줄에 "비밀번호가 맞지 않습니다" 를 띄운다. 검사를 두 곳에
     * 두면 한쪽이 바뀔 때 다른 쪽이 조용히 어긋난다.
     */
    private void tapRoom(final Room r) {
        // 비밀번호를 다 치게 한 뒤에 "공유를 멈출까요?" 를 묻는 건 순서가 나쁘다. 먼저 묻는다.
        if (CaptureService.sharing) {
            confirmStopSharing(new Runnable() {
                @Override public void run() { openRoom(r); }
            });
            return;
        }
        openRoom(r);
    }

    private void openRoom(final Room r) {
        // 주소가 여럿이면 첫 번째로 시도한다. 실패하면 사용자가 아래 칸에
        // 다른 주소를 직접 넣을 수 있다 — 그래서 행에 전부 보여주고 있다.
        final String ip = r.ips.keySet().iterator().next();
        if (!r.locked) { join(ip, ""); return; }

        final EditText in = field("0000");
        in.setInputType(InputType.TYPE_CLASS_NUMBER);
        in.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        in.setGravity(Gravity.CENTER);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(24), dp(8), dp(24), 0);
        wrap.addView(in);

        new AlertDialog.Builder(this)
                .setTitle(r.name)
                .setMessage("호스트가 알려준 비밀번호 4자리를 입력하세요.")
                .setView(wrap)
                .setPositiveButton("듣기", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        join(ip, in.getText().toString().trim());
                    }
                })
                .setNegativeButton("취소", null)
                .show();
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
        if (all.isEmpty()) all.addAll(other);
        return all;
    }

    /** 대표 주소 하나. 알림처럼 한 줄밖에 못 쓰는 자리에서만 쓴다. */
    static String localIp() {
        List<String> all = localIps();
        return all.isEmpty() ? "0.0.0.0" : all.get(0);
    }
}
