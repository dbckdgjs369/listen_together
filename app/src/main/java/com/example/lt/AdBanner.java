package com.example.lt;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;

/**
 * 화면 맨 아래 고정 배너. 세 화면이 한 자리를 같이 쓴다.
 *
 * 광고가 실제로 들어왔을 때만 띠를 보인다. 못 받으면(오프라인·채움 없음) 계속 숨겨 둔다 —
 * 빈 회색 띠가 남는 것보다 아무것도 없는 게 낫다.
 */
final class AdBanner {
    private final View bar;
    private final AdView ad;

    AdBanner(Activity a, View bar) {
        this.bar = bar;
        FrameLayout box = bar.findViewById(R.id.adContainer);

        ad = new AdView(a);
        ad.setAdUnitId(BuildConfig.AD_BANNER_ID);
        ad.setAdSize(adaptiveSize(a));
        ad.setAdListener(new AdListener() {
            @Override public void onAdLoaded() { bar.setVisibility(View.VISIBLE); }
            @Override public void onAdFailedToLoad(LoadAdError e) {
                Log.i(CaptureService.TAG, "광고 없음: " + e.getCode() + " " + e.getMessage());
                bar.setVisibility(View.GONE);
            }
        });
        box.addView(ad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL));

        // 초기화는 디스크를 읽어서 메인 스레드에서 돌리면 첫 화면이 버벅인다.
        new Thread(() -> {
            MobileAds.initialize(a, status -> {});
            a.runOnUiThread(() -> ad.loadAd(new AdRequest.Builder().build()));
        }, "ads-init").start();
    }

    /** 폭에 맞춘 적응형 배너. 고정 320×50 보다 화면 폭을 다 써서 덜 어색하다. */
    private static AdSize adaptiveSize(Activity a) {
        DisplayMetrics m = a.getResources().getDisplayMetrics();
        int widthDp = (int) (m.widthPixels / m.density);
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(a, widthDp);
    }

    void resume() { ad.resume(); }
    void pause() { ad.pause(); }
    void destroy() { ad.destroy(); }
}
