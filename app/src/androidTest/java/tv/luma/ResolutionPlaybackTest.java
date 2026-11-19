package tv.luma;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static tv.luma.Models.*;

/** Real decoder checks in the separate, unpaired debug app with generated media. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class ResolutionPlaybackTest {
    private final Instrumentation i=InstrumentationRegistry.getInstrumentation();
    private final LumaApp app=(LumaApp)i.getTargetContext().getApplicationContext();
    private final List<File> clips=new ArrayList<>();
    private StremioClient.Transport oldTransport;
    private List<Addon> oldAddons;
    private boolean oldGuest;
    private MainActivity activity;

    @Before public void setup(){
        Assume.assumeFalse("Resolution fixtures require an unpaired debug app",app.connected());
        oldTransport=app.api.transport;oldAddons=app.addons;oldGuest=app.guest;
        app.guest=true;app.addons=Collections.emptyList();
        app.api.transport=(url,body)->new JSONObject().put("metas",new JSONArray()).put("subtitles",new JSONArray());
        activity=(MainActivity)i.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    @After public void cleanup(){
        if(activity!=null)onMain(activity::finish);
        if(oldTransport!=null){app.api.transport=oldTransport;app.addons=oldAddons;app.guest=oldGuest;}
        for(File clip:clips)clip.delete();
    }
    private void onMain(Runnable action){i.runOnMainSync(action);i.waitForIdleSync();}
    private static Object field(Object owner,String name){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    private Object panel(){return field(activity,"playerPanel");}
    private ExoPlayer engine(){return (ExoPlayer)field(panel(),"player");}
    private TextView badge(String mode){return ((PlayerPanel)panel()).view().findViewWithTag("luma.resolution."+mode);}
    private File clip(String name)throws Exception{
        File file=new File(app.getCacheDir(),name);clips.add(file);
        try(InputStream in=i.getContext().getAssets().open(name);OutputStream out=new FileOutputStream(file)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
        return file;
    }
    private void play1080()throws Exception{
        File file=clip("resolution-1080p.mp4");
        Title title=new Title(new JSONObject().put("id","resolution-fixture").put("name","Resolution verification").put("type","movie"));
        Stream stream=new Stream(new JSONObject().put("url",file.toURI().toString()).put("name","Advertised 8K"),"Generated fixture");
        onMain(()->activity.play(title,title.id,Collections.singletonList(stream),0));
        waitFor(()->engine().getPlaybackState()==Player.STATE_READY&&"1080p".contentEquals(badge("full").getText()));
        onMain(()->engine().pause());
    }
    private void waitFor(BooleanSupplier condition)throws Exception{
        long end=System.currentTimeMillis()+20000;AtomicBoolean done=new AtomicBoolean();
        while(System.currentTimeMillis()<end){i.runOnMainSync(()->done.set(condition.getAsBoolean()));if(done.get())return;Thread.sleep(60);}fail("Timed out waiting for decoded resolution");
    }
    private void assertResolution(String label,String dimensions){
        for(String mode:new String[]{"full","mini"}){TextView badge=badge(mode);assertEquals(label,badge.getText().toString());assertTrue(badge.getContentDescription().toString().contains(dimensions));assertFalse(badge.isFocusable());}
    }
    @Test public void decodedSizesUpdateBothModesWithoutUsingAdvertisedQuality()throws Exception{
        play1080();ExoPlayer original=engine();
        onMain(()->assertResolution("1080p","1920 × 1080"));capture("resolution-full-1080p.png");
        onMain(activity::miniPlayer);
        for(int height:new int[]{1440,2160}){
            File file=clip("resolution-"+height+"p.mp4");
            onMain(()->{engine().setMediaItem(MediaItem.fromUri(file.toURI().toString()));engine().prepare();engine().play();});
            waitFor(()->engine().getPlaybackState()==Player.STATE_READY&&(height+"p").contentEquals(badge("mini").getText()));
            onMain(()->{engine().pause();assertSame(original,engine());assertResolution(height+"p",height==1440?"2560 × 1440":"3840 × 2160");});
        }
        onMain(()->{
            ViewGroup bar=(ViewGroup)field(panel(),"miniBar");Rect badgeBounds=new Rect();badge("mini").getHitRect(badgeBounds);
            assertTrue(badge("mini").isShown());assertTrue(badgeBounds.right<=bar.getWidth());assertTrue(badgeBounds.bottom<=bar.getHeight());
            for(int n=0;n<bar.getChildCount();n++){View child=bar.getChildAt(n);if(child==badge("mini"))continue;Rect bounds=new Rect();child.getHitRect(bounds);assertFalse("Resolution must not overlap a mini-player control",Rect.intersects(badgeBounds,bounds));}
        });
        capture("resolution-mini-2160p.png");
        for(int n=0;n<3;n++)onMain(()->{activity.restorePlayer();assertSame(original,engine());assertResolution("2160p","3840 × 2160");activity.miniPlayer();});
        onMain(activity::restorePlayer);capture("resolution-full-2160p.png");
    }
    @Test public void switchingToAudioClearsThePreviousVideoResolution()throws Exception{
        play1080();File audio=clip("resolution-audio.m4a");
        Stream stream=new Stream(new JSONObject().put("url",audio.toURI().toString()).put("name","Audio-only fixture"),"Generated fixture");
        onMain(()->{
            Object p=panel();((PlayerActivity.Request)field(p,"request")).streams.add(stream);
            try{Method menu=p.getClass().getDeclaredMethod("sourceMenu");menu.setAccessible(true);menu.invoke(p);}catch(Exception e){throw new AssertionError(e);}
            List<?> dialogs=(List<?>)field(p,"dialogs");Dialog dialog=(Dialog)dialogs.get(dialogs.size()-1);View choice=find(dialog.getWindow().getDecorView(),"Audio-only fixture");assertNotNull(choice);
            while(!choice.isClickable())choice=(View)choice.getParent();choice.performClick();assertResolution("—","unavailable");
        });
        waitFor(()->engine().getPlaybackState()==Player.STATE_READY);
        onMain(()->{engine().pause();assertResolution("—","unavailable");activity.miniPlayer();assertResolution("—","unavailable");});
    }
    @Test public void endingAndReleasingPlaybackClearTheResolution()throws Exception{
        play1080();
        onMain(()->{engine().seekTo(engine().getDuration());engine().play();});
        waitFor(()->engine().getPlaybackState()==Player.STATE_ENDED);
        onMain(()->{
            assertResolution("—","unavailable");TextView full=badge("full"),mini=badge("mini");activity.stopPlayer();assertNull(panel());assertEquals("—",full.getText().toString());assertEquals("—",mini.getText().toString());
        });
    }
    private static View find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){View match=find(group.getChildAt(n),text);if(match!=null)return match;}}return null;}
    private void capture(String name)throws Exception{
        i.waitForIdleSync();Bitmap bitmap=i.getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(FileOutputStream out=new FileOutputStream(new File(app.getFilesDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
}
