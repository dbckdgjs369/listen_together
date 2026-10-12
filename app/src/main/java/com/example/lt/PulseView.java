package com.example.lt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * 듣는 중 화면 가운데의 원판. 퍼지는 테두리와 막대 다섯 개.
 *
 * **실제 소리 크기를 그리는 게 아니다.** 소리는 PlayerService 가 AudioTrack 에 바로
 * 밀어넣어서 화면 쪽에는 샘플이 없다. 이건 "지금 받고 있다"는 표시일 뿐이라
 * 연결이 끊기면 멈춘다 — 멈춘 그림이 곧 "소리가 안 오고 있다" 는 신호가 된다.
 */
public class PulseView extends View {

    private final Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float density;
    private boolean active;
    /** 소리를 끈 상태. 원판은 남기고 움직임만 멈춰 "연결은 됐는데 꺼 둠" 으로 보이게 한다. */
    private boolean muted;
    private int barColor;

    /** 막대마다 다른 박자로 움직이게 한다. 같으면 한 덩어리로 보인다. */
    private static final float[] SPEED = {1.3f, 0.9f, 1.6f, 1.1f, 0.7f};
    private static final float[] PHASE = {0.2f, 1.9f, 0.7f, 2.6f, 1.3f};

    public PulseView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.2f * density);
        barColor = getResources().getColor(R.color.accent, null);
    }

    public void setActive(boolean on) {
        if (on == active) return;
        active = on;
        refresh();
    }

    public void setMuted(boolean m) {
        if (m == muted) return;
        muted = m;
        refresh();
    }

    private void refresh() {
        barColor = getResources().getColor(
                active && !muted ? R.color.accent : R.color.text_faint, null);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float rad = Math.min(w, h) / 2f * 0.8f;
        disc.setShader(new RadialGradient(w / 2f, h / 2f, rad,
                new int[]{0xFF2E2658, 0xFF1C1A36, 0xFF15151F},
                new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float rad = Math.min(getWidth(), getHeight()) / 2f * 0.8f;
        long t = SystemClock.uptimeMillis();

        boolean moving = active && !muted;
        if (moving) {
            for (int k = 0; k < 2; k++) {
                float p = ((t % 2400) / 2400f + k * 0.5f) % 1f;
                ring.setColor(0x6C5CE7 | ((int) ((1f - p) * 0x70) << 24));
                c.drawCircle(cx, cy, rad * (0.98f + 0.24f * p), ring);
            }
        }
        c.drawCircle(cx, cy, rad, disc);

        float bw = 7 * density, gap = 6 * density;
        float minH = 12 * density, maxH = 54 * density;
        float left = cx - (5 * bw + 4 * gap) / 2f;
        bar.setColor(barColor);
        for (int i = 0; i < 5; i++) {
            float h = minH;
            if (moving) {
                double s = Math.sin(t / 1000.0 * 2 * Math.PI * SPEED[i] * 0.8 + PHASE[i]);
                h = minH + (maxH - minH) * (float) (0.5 + 0.5 * s);
            }
            float x = left + i * (bw + gap);
            r.set(x, cy - h / 2f, x + bw, cy + h / 2f);
            c.drawRoundRect(r, bw / 2f, bw / 2f, bar);
        }
        if (moving && isShown()) postInvalidateOnAnimation();
    }
}
