package tv.luma;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static tv.luma.Models.*;

/** Real channel playback and remote input in the unpaired debug package. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class PlayerControlsTest {
    private final Instrumentation i=InstrumentationRegistry.getInstrumentation();
    private final LumaApp app=(LumaApp)i.getTargetContext().getApplicationContext();
    private StremioClient.Transport oldTransport;
    private List<Addon> oldAddons;
    private boolean oldGuest;
    private MainActivity activity;
    private File clip;
    @Before public void setup()throws Exception{
        Assume.assumeFalse("Control fixtures require an unpaired debug app",app.connected());
        oldTransport=app.api.transport;oldAddons=app.addons;oldGuest=app.guest;
        app.guest=true;app.addons=Collections.emptyList();
        app.api.transport=(url,body)->new JSONObject().put("metas",new JSONArray()).put("subtitles",new JSONArray());
        activity=(MainActivity)i.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        clip=new File(app.getCacheDir(),"controls-channel.mp4");
        try(InputStream in=i.getContext().getAssets().open("resolution-1080p.mp4");OutputStream out=new FileOutputStream(clip)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
        Title title=new Title(new JSONObject().put("id","controls-channel").put("name","Channel controls verification").put("type","tv"));
        Stream stream=new Stream(new JSONObject().put("url",clip.toURI().toString()),"Generated fixture");
        onMain(()->activity.play(title,title.id,Collections.singletonList(stream),0));
        waitFor(()->engine().isPlaying(),15000,"Channel did not start playing");
        onMain(()->engine().setRepeatMode(Player.REPEAT_MODE_ONE));
    }
    @After public void cleanup(){
        if(activity!=null)onMain(activity::finish);
        if(oldTransport!=null){app.api.transport=oldTransport;app.addons=oldAddons;app.guest=oldGuest;}
        if(clip!=null)clip.delete();
    }
    private void onMain(Runnable action){i.runOnMainSync(action);i.waitForIdleSync();}
    private static Object field(Object owner,String name){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    private Object panel(){return field(activity,"playerPanel");}
    private ExoPlayer engine(){return (ExoPlayer)field(panel(),"player");}
    private View controls(){return (View)field(panel(),"controls");}
    private View top(){return (View)field(panel(),"top");}
    private void key(int code){i.sendKeyDownUpSync(code);i.waitForIdleSync();}
    private void waitFor(BooleanSupplier condition,long timeout,String message)throws Exception{
        long end=System.currentTimeMillis()+timeout;AtomicBoolean done=new AtomicBoolean();
        while(System.currentTimeMillis()<end){i.runOnMainSync(()->done.set(condition.getAsBoolean()));if(done.get())return;Thread.sleep(60);}fail(message);
    }
    private void waitForHidden()throws Exception{
        waitFor(()->controls().getVisibility()==View.INVISIBLE&&top().getVisibility()==View.INVISIBLE,8500,"Playback controls did not hide without moving focus");
        onMain(()->{assertFalse(controls().hasFocus());assertTrue(engine().isPlaying());});
    }
    @Test public void channelPlaybackHidesControlsWithoutRemoteInput()throws Exception{
        onMain(()->assertTrue(((View)field(panel(),"playButton")).hasFocus()));
        waitForHidden();key(KeyEvent.KEYCODE_DPAD_CENTER);
        onMain(()->{assertEquals(View.VISIBLE,controls().getVisibility());assertTrue(((View)field(panel(),"playButton")).hasFocus());assertTrue("First OK reveals controls without pausing",engine().getPlayWhenReady());});
        waitForHidden();
    }
    @Test public void pausedControlsStayVisibleAndResumeRestartsTheFadeTimer()throws Exception{
        key(KeyEvent.KEYCODE_DPAD_CENTER);waitFor(()->!engine().getPlayWhenReady(),2000,"Remote OK did not pause");
        Thread.sleep(6600);onMain(()->{assertEquals(View.VISIBLE,controls().getVisibility());assertFalse(engine().isPlaying());});
        key(KeyEvent.KEYCODE_DPAD_CENTER);waitFor(()->engine().isPlaying(),2000,"Remote OK did not resume");waitForHidden();
    }
    @Test public void anOpenPlayerMenuPreventsFadingUntilItCloses()throws Exception{
        onMain(()->{find(controls(),"Sleep").performClick();engine().pause();engine().play();});
        Thread.sleep(6600);
        onMain(()->{assertEquals(View.VISIBLE,controls().getVisibility());List<?> dialogs=(List<?>)field(panel(),"dialogs");Dialog dialog=(Dialog)dialogs.get(dialogs.size()-1);assertTrue(dialog.isShowing());dialog.dismiss();});
        waitForHidden();
    }
    private static View find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){View match=find(group.getChildAt(n),text);if(match!=null)return match;}}return null;}
}
