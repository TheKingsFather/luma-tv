package tv.luma;

import android.app.Instrumentation;
import android.app.Activity;
import android.content.*;
import android.view.KeyEvent;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.google.zxing.*;
import com.google.zxing.common.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static tv.luma.Models.*;

@RunWith(AndroidJUnit4.class)
public class DeviceSmokeTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final LumaApp app=(LumaApp)instrumentation.getTargetContext().getApplicationContext();
    @Before public void enableRendering(){instrumentation.runOnMainSync(()->androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true));}
    @Test public void livePairingProducesOfficialScannableLinkAndWaits()throws Exception{
        JSONObject link=app.api.createLink();String code=text(link,"code"),url=text(link,"link");
        assertFalse(code.isEmpty());assertTrue(url.startsWith("https://link.stremio.com/"));
        BitMatrix matrix=new MultiFormatWriter().encode(url,BarcodeFormat.QR_CODE,400,400);int[] pixels=new int[160000];for(int y=0;y<400;y++)for(int x=0;x<400;x++)pixels[y*400+x]=matrix.get(x,y)?0xff000000:0xffffffff;
        Result decoded=new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(400,400,pixels))));assertEquals(url,decoded.getText());
        assertEquals("",app.api.readLink(code));
    }
    @Test public void liveCinemetaCatalogSearchAndSeriesMetadata()throws Exception{
        List<Addon> addons=app.api.publicAddons();assertFalse(addons.isEmpty());List<Catalog> catalogs=app.api.catalogs(addons);Catalog browse=null;for(Catalog c:catalogs)if(c.canBrowse()&&c.type.equals("movie")){browse=c;break;}assertNotNull(browse);
        List<Title> movies=app.api.catalog(browse,"",0);assertFalse(movies.isEmpty());assertFalse(movies.get(0).poster.isEmpty());
        List<Title> results=app.api.search(addons,"Breaking Bad");Title series=null;for(Title t:results)if(t.type.equals("series")&&t.name.equals("Breaking Bad")){series=t;break;}assertNotNull(series);
        Title detailed=app.api.meta(addons,series);assertTrue(detailed.videos().size()>20);assertFalse(detailed.description.isEmpty());
    }
    @Test public void keystoreRoundTripAndEncryptionAtRest()throws Exception{
        SessionStore store=new SessionStore(app);String name="instrumentation-fixture";String sample="fixture-not-a-real-auth-key";
        try{store.put(name,sample);assertEquals(sample,store.get(name));String ciphertext=app.getSharedPreferences("vault",0).getString(name,"");assertFalse(ciphertext.contains(sample));store.put(name,sample);assertNotEquals(ciphertext,app.getSharedPreferences("vault",0).getString(name,""));}finally{store.remove(name);}
    }
    @Test public void hardwarePlaybackPauseSeekAndLifecycle()throws Exception{
        // No account/library mutation during a local media test.
        Assume.assumeFalse(app.connected());
        File clip=new File(app.getCacheDir(),"playback-fixture.mp4");try(InputStream in=instrumentation.getContext().getAssets().open("playback-fixture.mp4");OutputStream out=new FileOutputStream(clip)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        Title title=new Title(new JSONObject().put("id","luma-test-fixture").put("name","Playback verification").put("type","movie"));Stream stream=new Stream(new JSONObject().put("url",clip.toURI().toString()).put("name","Local test fixture"),"Luma test");
        app.playback=new PlayerActivity.Request(title,title.id,Collections.singletonList(stream),0);
        Intent intent=new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("playPending",true);MainActivity activity=(MainActivity)instrumentation.startActivitySync(intent);
        try{
            Field panelField=MainActivity.class.getDeclaredField("playerPanel");panelField.setAccessible(true);Object panel=panelField.get(activity);Field field=PlayerPanel.class.getDeclaredField("player");field.setAccessible(true);AtomicBoolean ready=new AtomicBoolean();long end=System.currentTimeMillis()+20000;
            while(!ready.get()&&System.currentTimeMillis()<end){instrumentation.runOnMainSync(()->{try{ExoPlayer p=(ExoPlayer)field.get(panel);ready.set(p!=null&&p.getPlaybackState()==Player.STATE_READY&&p.getCurrentPosition()>300);}catch(Exception e){throw new RuntimeException(e);}});Thread.sleep(200);}
            assertTrue("Media3 should decode and play the local H.264/AAC fixture",ready.get());
            instrumentation.runOnMainSync(()->{try{ExoPlayer p=(ExoPlayer)field.get(panel);p.pause();p.seekTo(5000);assertFalse(p.getPlayWhenReady());}catch(Exception e){throw new RuntimeException(e);}});
            AtomicLong position=new AtomicLong();instrumentation.runOnMainSync(()->{try{position.set(((ExoPlayer)field.get(panel)).getCurrentPosition());}catch(Exception e){throw new RuntimeException(e);}});assertTrue(position.get()>=4900);
        }finally{instrumentation.runOnMainSync(activity::finish);instrumentation.waitForIdleSync();clip.delete();}
    }
    @Test public void remoteBrowsingOpensDetailsAndCapturesScreens()throws Exception{
        Assume.assumeFalse(app.connected());
        Intent intent=new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);MainActivity activity=(MainActivity)instrumentation.startActivitySync(intent);
        try{
            AtomicReference<View> explore=new AtomicReference<>();instrumentation.runOnMainSync(()->explore.set(findText(activity.getWindow().getDecorView(),"Explore the interface   →")));
            assertNotNull(explore.get());instrumentation.runOnMainSync(()->explore.get().performClick());
            AtomicReference<View> poster=new AtomicReference<>();long deadline=System.currentTimeMillis()+25000;
            while(poster.get()==null&&System.currentTimeMillis()<deadline){instrumentation.runOnMainSync(()->poster.set(firstPoster(activity.getWindow().getDecorView())));Thread.sleep(200);}
            assertNotNull("Catalog cards should load",poster.get());
            AtomicBoolean artReady=new AtomicBoolean();deadline=System.currentTimeMillis()+15000;
            while(!artReady.get()&&System.currentTimeMillis()<deadline){instrumentation.runOnMainSync(()->artReady.set(hasImage(poster.get())));Thread.sleep(150);}
            assertTrue("Visible poster artwork should load",artReady.get());capture(activity,"home.png");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->poster.get().performClick());
            AtomicReference<View> play=new AtomicReference<>();deadline=System.currentTimeMillis()+10000;
            while(play.get()==null&&System.currentTimeMillis()<deadline){instrumentation.runOnMainSync(()->play.set(findText(activity.getWindow().getDecorView(),"▶  Watch now")));Thread.sleep(100);}
            assertNotNull("Title opens a playable details page",play.get());Thread.sleep(1200);capture(activity,"details.png");
        }finally{instrumentation.runOnMainSync(activity::finish);instrumentation.waitForIdleSync();}
    }
    private View findText(View root,String label){if(root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View match=findText(group.getChildAt(i),label);if(match!=null)return match;}}return null;}
    private View firstPoster(View root){if(root instanceof LinearLayout&&root.isClickable()&&root.getContentDescription()!=null)return root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){View match=firstPoster(group.getChildAt(i));if(match!=null)return match;}}return null;}
    private boolean hasImage(View root){if(root instanceof ImageView&&((ImageView)root).getDrawable()!=null)return true;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)if(hasImage(group.getChildAt(i)))return true;}return false;}
    private void capture(Activity activity,String name)throws Exception{
        instrumentation.runOnMainSync(()->activity.getWindow().getDecorView().invalidate());instrumentation.waitForIdleSync();Thread.sleep(600);
        Bitmap bitmap=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(bitmap);
        try(FileOutputStream out=new FileOutputStream(new File(app.getFilesDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
}
