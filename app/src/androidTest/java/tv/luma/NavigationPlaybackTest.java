package tv.luma;

import android.app.Instrumentation;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
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
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;
import static tv.luma.Models.*;

/** Isolated UI/video regressions. Never run fixtures against an owner's connected account. */
@RunWith(AndroidJUnit4.class)
public class NavigationPlaybackTest {
    private final Instrumentation i=InstrumentationRegistry.getInstrumentation();
    private final LumaApp app=(LumaApp)i.getTargetContext().getApplicationContext();
    private StremioClient.Transport oldTransport;private List<Addon> oldAddons;private boolean oldGuest;
    private MainActivity activity;private File clip;
    private final AtomicInteger requests=new AtomicInteger();
    private final CountDownLatch releaseLate=new CountDownLatch(1),lateStarted=new CountDownLatch(1);
    private boolean delay;
    @Before public void setup()throws Exception{
        Assume.assumeFalse("Use an unpaired emulator for account-free fixtures",app.connected());
        oldTransport=app.api.transport;oldAddons=app.addons;oldGuest=app.guest;
        app.guest=true;app.addons=Collections.singletonList(new Addon(new JSONObject("{\"transportUrl\":\"https://fixture.invalid/manifest.json\",\"manifest\":{\"id\":\"fixture\",\"name\":\"Fixtures\",\"types\":[\"tv\",\"movie\"],\"resources\":[\"catalog\"],\"catalogs\":[{\"id\":\"channels\",\"type\":\"tv\",\"name\":\"Fixture channels\",\"extraSupported\":[\"skip\"]},{\"id\":\"late\",\"type\":\"tv\",\"name\":\"Late channels\"},{\"id\":\"movies\",\"type\":\"movie\",\"name\":\"Fixture movies\"}]}}")));
        app.api.transport=(url,body)->{if(url.contains("/late.json")&&delay){lateStarted.countDown();if(!releaseLate.await(15,TimeUnit.SECONDS))throw new IOException("Fixture timed out");}requests.incrementAndGet();JSONArray metas=new JSONArray();for(int n=0;n<40;n++)metas.put(new JSONObject().put("id",(url.contains("skip=")?"page2-":"fixture-")+n).put("name",url.contains("/late.json")?"Late channel "+n:"Fixture "+n));return new JSONObject().put("metas",metas);};
        i.runOnMainSync(()->androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true));
    }
    @After public void cleanup(){releaseLate.countDown();if(activity!=null){i.runOnMainSync(activity::finish);i.waitForIdleSync();}if(oldTransport!=null){app.api.transport=oldTransport;app.addons=oldAddons;app.guest=oldGuest;}if(clip!=null)clip.delete();}
    private void open()throws Exception{activity=(MainActivity)i.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitFor(()->firstPoster(root())!=null);}
    private void live()throws Exception{onMain(()->invoke(activity,"showHome",new Class<?>[]{String.class},"Live"));waitFor(()->firstPoster(root())!=null&&find(root(),"Late channel 0")!=null);}
    private void onMain(Runnable r){i.runOnMainSync(r);i.waitForIdleSync();}
    private static Object field(Object owner,String name){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    private static Object invoke(Object owner,String name,Class<?>[] types,Object...args){try{Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,args);}catch(Exception e){throw new AssertionError(e);}}
    private View root(){return (View)field(activity,"root");}
    private void waitFor(BooleanSupplier condition)throws Exception{long end=System.currentTimeMillis()+15000;AtomicBoolean done=new AtomicBoolean();while(System.currentTimeMillis()<end){i.runOnMainSync(()->done.set(condition.getAsBoolean()));if(done.get())return;Thread.sleep(60);}fail("Timed out waiting for UI/video condition");}
    private static View find(View v,String label){if(v instanceof TextView&&label.contentEquals(((TextView)v).getText()))return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++){View match=find(g.getChildAt(n),label);if(match!=null)return match;}}return null;}
    private static View firstPoster(View v){if(v instanceof LinearLayout&&v.isClickable()&&v.getContentDescription()!=null)return v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++){View match=firstPoster(g.getChildAt(n));if(match!=null)return match;}}return null;}
    private static <T> T first(View v,Class<T> type){if(type.isInstance(v))return type.cast(v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++){T match=first(g.getChildAt(n),type);if(match!=null)return match;}}return null;}
    private ExoPlayer engine(){Object panel=field(activity,"playerPanel");return panel==null?null:(ExoPlayer)field(panel,"player");}
    private void playFixture()throws Exception{
        clip=new File(app.getCacheDir(),"mini-player-fixture.mp4");try(InputStream in=i.getContext().getAssets().open("playback-fixture.mp4");OutputStream out=new FileOutputStream(clip)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        Title t=new Title(new JSONObject().put("id","fixture-video").put("name","Mini player verification").put("type","tv"));Stream stream=new Stream(new JSONObject().put("url",clip.toURI().toString()),"Fixture");
        onMain(()->activity.play(t,t.id,Collections.singletonList(stream),0));waitFor(()->engine()!=null&&engine().getPlaybackState()==Player.STATE_READY&&engine().getCurrentPosition()>250);
    }
    @Test public void backRestoresIdenticalCardsFocusAndBothScrollAxesWithoutRequests()throws Exception{
        open();live();AtomicReference<View> savedRoot=new AtomicReference<>(),savedPoster=new AtomicReference<>();AtomicInteger x=new AtomicInteger(),y=new AtomicInteger();
        onMain(()->{savedRoot.set(root());HorizontalScrollView h=first(root(),HorizontalScrollView.class);LinearLayout cards=(LinearLayout)h.getChildAt(0);View poster=cards.getChildAt(12);poster.requestFocus();h.scrollTo(750,0);ScrollView v=first(root(),ScrollView.class);v.scrollTo(0,45);x.set(h.getScrollX());y.set(v.getScrollY());savedPoster.set(poster);poster.performClick();});
        int count=requests.get();onMain(activity::onBackPressed);
        onMain(()->{assertSame(savedRoot.get(),root());assertSame(savedPoster.get(),root().findFocus());assertEquals(x.get(),first(root(),HorizontalScrollView.class).getScrollX());assertEquals(y.get(),first(root(),ScrollView.class).getScrollY());assertEquals(count,requests.get());});
    }
    @Test public void catalogPaginationAndCardsSurviveDetailsAndSectionSwitchInvalidatesThem()throws Exception{
        open();live();onMain(()->find(root(),"View all  →").performClick());waitFor(()->find(root(),"Load more")!=null);onMain(()->find(root(),"Load more").performClick());waitFor(()->find(root(),"Fixture 39")!=null&&find(root(),"Load more")!=null);
        AtomicReference<View> catalog=new AtomicReference<>();onMain(()->{catalog.set(root());firstPoster(root()).performClick();});onMain(activity::onBackPressed);onMain(()->assertSame(catalog.get(),root()));
        onMain(()->invoke(activity,"showHome",new Class<?>[]{String.class},"Movies"));waitFor(()->firstPoster(root())!=null);onMain(()->invoke(activity,"showHome",new Class<?>[]{String.class},"Live"));waitFor(()->firstPoster(root())!=null);onMain(()->assertNotSame(catalog.get(),root()));
    }
    @Test public void lateCatalogCompletesOnRetainedPageWithoutReplacingDetails()throws Exception{
        delay=true;open();assertTrue(lateStarted.await(5,TimeUnit.SECONDS));AtomicReference<View> home=new AtomicReference<>(),details=new AtomicReference<>();onMain(()->{home.set(root());firstPoster(root()).performClick();details.set(root());});releaseLate.countDown();waitFor(()->find(home.get(),"Late channel 0")!=null);onMain(()->assertSame(details.get(),root()));onMain(activity::onBackPressed);onMain(()->assertSame(home.get(),root()));
    }
    @Test public void miniAndFullReuseEnginePositionTracksAndPauseStateWhileBrowsing()throws Exception{
        open();live();playFixture();AtomicReference<ExoPlayer> original=new AtomicReference<>();onMain(()->{original.set(engine());engine().pause();engine().seekTo(3000);engine().setVolume(.4f);activity.miniPlayer();assertTrue(((PlayerPanel)field(activity,"playerPanel")).isMini());assertFalse(engine().getPlayWhenReady());});
        onMain(()->invoke(activity,"showHome",new Class<?>[]{String.class},"Movies"));waitFor(()->firstPoster(root())!=null);
        for(int n=0;n<3;n++)onMain(()->{activity.restorePlayer();assertSame(original.get(),engine());assertTrue(engine().getCurrentPosition()>=2900);assertEquals(.4f,engine().getVolume(),.01f);assertFalse(engine().getPlayWhenReady());activity.miniPlayer();});
        onMain(()->{activity.restorePlayer();engine().play();});waitFor(()->engine().getCurrentPosition()>3400);onMain(activity::miniPlayer);capture("mini-player.png");onMain(activity::stopPlayer);onMain(()->assertNull(field(activity,"playerPanel")));
    }
    @Test public void liveCompletionReturnsToCachedCardsInFullAndMiniModes()throws Exception{
        open();live();AtomicReference<View> saved=new AtomicReference<>();onMain(()->saved.set(root()));
        for(boolean mini:new boolean[]{false,true}){playFixture();onMain(()->{if(mini)activity.miniPlayer();engine().seekTo(engine().getDuration());});waitFor(()->field(activity,"playerPanel")==null);onMain(()->assertSame(saved.get(),root()));}
    }
    @Test public void leavingAppPausesAndResumesOnlyPreviouslyPlayingVideo()throws Exception{
        open();live();playFixture();onMain(()->{PlayerPanel panel=(PlayerPanel)field(activity,"playerPanel");panel.onStop();assertFalse(engine().getPlayWhenReady());panel.onResume();assertTrue(engine().getPlayWhenReady());engine().pause();panel.onStop();panel.onResume();assertFalse(engine().getPlayWhenReady());activity.stopPlayer();assertNull(field(activity,"playerPanel"));});
    }
    private Dialog playerDialog(){List<?> list=(List<?>)field(field(activity,"playerPanel"),"dialogs");return (Dialog)list.get(list.size()-1);}
    private void choose(Dialog dialog,String label){View text=find(dialog.getWindow().getDecorView(),label);assertNotNull(text);View target=text;while(!target.isClickable()&&target.getParent() instanceof View)target=(View)target.getParent();assertTrue(target.isClickable());target.performClick();}
    @Test public void nestedPlayerSubtitlesKeepDialogStateAndDoNotRestartPlayback()throws Exception{
        open();live();playFixture();AtomicReference<ExoPlayer> original=new AtomicReference<>();AtomicReference<Dialog> parent=new AtomicReference<>();
        onMain(()->{original.set(engine());engine().pause();Object panel=field(activity,"playerPanel");invoke(panel,"trackMenu",new Class<?>[]{});parent.set(playerDialog());choose(parent.get(),"Embedded subtitles");});
        waitFor(()->!parent.get().isShowing()&&playerDialog().isShowing());
        onMain(()->{Object panel=field(activity,"playerPanel");assertTrue((Boolean)field(panel,"dialogOpen"));assertTrue(playerDialog() instanceof GlassDialog);choose(playerDialog(),"Off");});
        waitFor(()->!(Boolean)field(field(activity,"playerPanel"),"dialogOpen"));
        onMain(()->{assertSame(original.get(),engine());assertFalse(engine().getPlayWhenReady());assertTrue(engine().getTrackSelectionParameters().disabledTrackTypes.contains(androidx.media3.common.C.TRACK_TYPE_TEXT));});
    }
    @Test public void playerSourceAndSleepMenusCancelCleanlyAndReleaseDismissesOpenMenus()throws Exception{
        open();live();playFixture();AtomicReference<ExoPlayer> original=new AtomicReference<>();AtomicReference<Dialog> last=new AtomicReference<>();
        onMain(()->{engine().pause();original.set(engine());invoke(field(activity,"playerPanel"),"sourceMenu",new Class<?>[]{});assertTrue(playerDialog() instanceof GlassDialog);playerDialog().cancel();});
        waitFor(()->!(Boolean)field(field(activity,"playerPanel"),"dialogOpen"));
        onMain(()->{assertSame(original.get(),engine());assertFalse(engine().getPlayWhenReady());invoke(field(activity,"playerPanel"),"sleepMenu",new Class<?>[]{});choose(playerDialog(),"In 30 minutes");});
        waitFor(()->!(Boolean)field(field(activity,"playerPanel"),"dialogOpen"));
        onMain(()->{Object panel=field(activity,"playerPanel");assertEquals(2,field(panel,"sleepChoice"));invoke(panel,"sleepMenu",new Class<?>[]{});last.set(playerDialog());activity.stopPlayer();assertFalse(last.get().isShowing());assertNull(field(activity,"playerPanel"));});
    }
    private void capture(String name)throws Exception{i.waitForIdleSync();Thread.sleep(300);Bitmap image=i.getUiAutomation().takeScreenshot();assertNotNull(image);try(FileOutputStream out=new FileOutputStream(new File(app.getFilesDir(),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
}
