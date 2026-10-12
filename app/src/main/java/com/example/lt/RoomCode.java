package com.example.lt;

/**
 * 방 코드 — 호스트 주소와 입장 비밀번호를 짧은 글자 몇 개로 접는다.
 *
 * 맥·PC 참여자는 QR 을 못 찍고, `http://172.20.10.3:7980` 을 매번 치는 건 고역이다.
 * 그래서 고정 주소의 정적 페이지(site/)에 이 코드만 넣으면 페이지가 코드를 풀어
 * 호스트로 보내준다. **서버가 없다** — 푸는 건 브라우저 JS 가 한다.
 *
 * ★site/roomcode.js 와 한 글자도 다르면 안 된다. 한쪽만 고치면 코드가 엉뚱한
 *   주소로 풀려서 참여자는 ERR_ADDRESS_UNREACHABLE 만 보게 된다.
 *
 * 구성: value = 주소번호 × 10000 + 비밀번호, 이를 Crockford base32 로 쓴다.
 * 사설 대역은 세 개뿐이라 앞자리를 접을 수 있다. 가장 흔한 192.168.x.x 가 6글자다.
 *
 * 오타 검사 글자는 일부러 뺐다. 7글자는 불러주기에 길었다. 비밀번호 부분 오타는
 * 호스트가 "비밀번호가 맞지 않습니다" 로 잡아주고, 주소 부분 오타만 "연결할 수 없음" 이 된다.
 *
 *   192.168.c.d      → 0 ~ 65535
 *   172.16~31.x.x    → 그 뒤 2^20 칸
 *   10.x.x.x         → 그 뒤 2^24 칸
 *   그 외            → 그 뒤 2^32 칸 (길어지지만 풀리긴 한다)
 */
public final class RoomCode {

    /** 코드를 넣는 고정 페이지. site/ 를 올린 곳으로 바꾼다. */
    public static final String SITE = "https://dbckdgjs369.github.io/listen_together/";

    /** 헷갈리는 I L O U 를 뺀 32자. 입력할 때 O→0, I·L→1 로 받아준다. */
    private static final String ALPHA = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private static final long B172 = 1L << 16;
    private static final long B10 = B172 + (1L << 20);
    private static final long BANY = B10 + (1L << 24);

    private RoomCode() { }

    /** 주소를 못 읽으면 null. 비밀번호가 없는 방은 0 으로 싣는다 — 서버가 어차피 안 본다. */
    public static String encode(String ip, String pw) {
        long idx = ipIndex(ip);
        if (idx < 0) return null;
        int p = 0;
        if (pw != null && pw.matches("\\d{1,4}")) p = Integer.parseInt(pw);
        long value = idx * 10000 + p;

        StringBuilder sb = new StringBuilder();
        do {
            sb.append(ALPHA.charAt((int) (value & 31)));
            value >>>= 5;
        } while (value > 0);
        return sb.reverse().toString();
    }

    /**
     * 코드 → {ip, pw(4자리)}. 못 읽는 글자가 있으면 null.
     * 앱에서도 코드로 들어갈 수 있게 한다 — 자동 발견이 막힌 망(클라이언트 격리 등)에서
     * 주소와 비밀번호를 따로 치는 대신 호스트 화면의 코드 하나만 옮기면 된다.
     * 받아주는 모양은 site/roomcode.js 의 normalize 와 같다.
     */
    public static String[] decode(String raw) {
        if (raw == null) return null;
        String s = raw.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[\\s\\-_.]", "")
                .replace('O', '0').replace('I', '1').replace('L', '1');
        if (s.isEmpty() || s.length() > 11) return null;
        long value = 0;
        for (int i = 0; i < s.length(); i++) {
            int d = ALPHA.indexOf(s.charAt(i));
            if (d < 0) return null;
            value = value * 32 + d;
        }
        long idx = value / 10000;
        if (idx >= BANY + (1L << 32)) return null;
        String pw = String.format(java.util.Locale.ROOT, "%04d", value % 10000);
        return new String[]{ipFromIndex(idx), pw};
    }

    private static String ipFromIndex(long idx) {
        if (idx < B172) return "192.168." + (idx >> 8) + "." + (idx & 255);
        if (idx < B10) {
            long n = idx - B172;
            return "172." + (16 + (n >> 16)) + "." + ((n >> 8) & 255) + "." + (n & 255);
        }
        if (idx < BANY) {
            long n = idx - B10;
            return "10." + (n >> 16) + "." + ((n >> 8) & 255) + "." + (n & 255);
        }
        long n = idx - BANY;
        return (n >> 24) + "." + ((n >> 16) & 255) + "." + ((n >> 8) & 255) + "." + (n & 255);
    }


    private static long ipIndex(String ip) {
        if (ip == null) return -1;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return -1;
        int[] o = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                o[i] = Integer.parseInt(parts[i]);
                if (o[i] < 0 || o[i] > 255) return -1;
            }
        } catch (NumberFormatException e) {
            return -1;
        }
        if (o[0] == 192 && o[1] == 168) return (o[2] << 8) | o[3];
        if (o[0] == 172 && o[1] >= 16 && o[1] <= 31) {
            return B172 + (((long) (o[1] - 16) << 16) | (o[2] << 8) | o[3]);
        }
        if (o[0] == 10) return B10 + (((long) o[1] << 16) | (o[2] << 8) | o[3]);
        return BANY + (((long) o[0] << 24) | ((long) o[1] << 16) | (o[2] << 8) | o[3]);
    }
}
