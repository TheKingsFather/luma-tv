package tv.luma;

import android.view.*;
import android.widget.*;

/** Brief in-app feedback, using modal material without stealing focus. */
final class GlassNotice extends TextView {
    private static final String TAG="luma.notice";
    private final Runnable hide=()->animate().alpha(0f).setDuration(200).withEndAction(()->setVisibility(GONE)).start();
    private GlassNotice(Glass g){
        super(g.activity);setTag(TAG);setTextColor(Glass.WHITE);setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,g.u(16));
        setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));setGravity(Gravity.CENTER);setMaxLines(3);setEllipsize(android.text.TextUtils.TruncateAt.END);
        setPadding(g.u(24),g.u(14),g.u(24),g.u(14));setMaxWidth(g.u(820));setBackground(g.surface(18));setElevation(g.u(40));setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    }
    static void show(Glass g,String message){
        if(g.activity.isFinishing()||g.activity.isDestroyed())return;
        ViewGroup decor=(ViewGroup)g.activity.getWindow().getDecorView();GlassNotice notice=decor.findViewWithTag(TAG);
        if(notice==null){notice=new GlassNotice(g);FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);p.bottomMargin=g.u(42);decor.addView(notice,p);}
        notice.removeCallbacks(notice.hide);notice.animate().cancel();notice.animate().withEndAction(null);notice.setText(message);notice.setAlpha(1f);notice.setVisibility(VISIBLE);notice.postDelayed(notice.hide,4000);
    }
    @Override protected void onDetachedFromWindow(){removeCallbacks(hide);animate().cancel();super.onDetachedFromWindow();}
}
