package tv.luma;
import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

/** Debug-only surface: device UI fixtures never touch the owner's library or settings. */
public final class UiFixtureActivity extends Activity {
    public FrameLayout surface;
    @Override public void onCreate(Bundle state){super.onCreate(state);surface=new Glass(this).root();setContentView(surface);Glass.fullscreen(this);}
}
