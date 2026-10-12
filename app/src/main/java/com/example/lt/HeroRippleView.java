package com.example.lt;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

/**
 * 홈 '내 소리 공유하기' 카드 뒤 장식. 왼쪽 위 스피커 아이콘에서 동그란 물결이
 * 천천히 퍼져 나간다 — 이 폰의 소리가 주변으로 번진다는 그림.
 *
 * 글씨 뒤로 지나가므로 아주 옅게 그리고, 멀어질수록 사라지게 한다.
 * 카드 모서리가 둥글어서 그 모양대로 잘라 그린다.
 */
public class HeroRippleView extends View {

    private static final long PERIOD = 5200;
    private static final int RINGS = 3;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final float density;
    private float cx, cy;

    public HeroRippleView(Context c, AttributeSet a) {
        super(c, a);
        density = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.4f * density);
        ring.setColor(0xFFFFFFFF);
    }

    /**
     * 스스로는 크기를 요구하지 않는다. 기본값대로 두면 받을 수 있는 최대 높이(화면 전체)를
     * 차지해서 카드가 화면을 꽉 채운다. 0 을 내면 카드는 글 높이로 정해지고,
     * 그다음 FrameLayout 이 이 뷰를 카드 크기에 맞춰 다시 잰다.
     */
    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize(0, w), resolveSize(0, h));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // 스피커 아이콘 가운데: 카드 안쪽 여백 22dp + 아이콘 48dp 의 절반
        cx = 46 * density;
        cy = 46 * density;
        float r = 24 * density;
        clip.reset();
        clip.addRoundRect(0, 0, w, h, r, r, Path.Direction.CW);
        glow.setShader(new RadialGradient(cx, cy, 90 * density,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.clipPath(clip);
        c.drawCircle(cx, cy, 90 * density, glow);

        long t = SystemClock.uptimeMillis();
        float from = 30 * density, to = Math.max(getWidth(), getHeight()) * 1.1f;
        for (int k = 0; k < RINGS; k++) {
            float p = ((t % PERIOD) / (float) PERIOD + k / (float) RINGS) % 1f;
            // 처음엔 빠르게 나가다 멀리서 느려진다. 소리가 퍼지며 잦아드는 느낌
            float e = 1f - (1f - p) * (1f - p);
            float fade = (1f - p);
            ring.setAlpha((int) (fade * fade * 0.38f * 255));
            c.drawCircle(cx, cy, from + (to - from) * e, ring);
        }
        c.restore();
        if (isShown()) postInvalidateOnAnimation();
    }

    /**
     * 홈이 가려져 있는 동안(공유·듣는 화면) 그리기를 멈췄다가 다시 보이면 이어 돌린다.
     * 이게 없으면 앱이 켜질 때 홈이 잠깐 숨어 있던 사이 멈춰서 영영 안 움직인다.
     */
    @Override
    public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible) postInvalidateOnAnimation();
    }
}
