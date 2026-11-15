package tv.luma;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.crypto.*;
import javax.crypto.spec.*;
import static org.junit.Assert.*;

/** Generated-media fixtures restricted to the separate debug package. */
@RunWith(AndroidJUnit4.class)
public class PrivateReportsDeviceTest {
    private final Instrumentation i=InstrumentationRegistry.getInstrumentation();
    private final LumaApp app=(LumaApp)i.getTargetContext().getApplicationContext();
    private MainActivity activity;private File clip;private KeyPair owner;
    @Before public void setup()throws Exception{
        assertTrue(app.getPackageName().endsWith(".debug"));assertFalse(app.connected());
        app.reports.revoke();KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);owner=generator.generateKeyPair();app.reports.pair(owner.getPublic().getEncoded());
        app.guest=true;app.addons=Collections.singletonList(new Models.Addon(new JSONObject("{\"transportUrl\":\"https://fixture.invalid/manifest.json\",\"manifest\":{\"id\":\"fixture\",\"name\":\"Private reports fixture\",\"catalogs\":[]}}")));
        app.api.transport=(url,body)->new JSONObject().put("subtitles",new JSONArray());
        clip=new File(app.getCacheDir(),"private-reports-fixture.mp4");
        try(InputStream in=app.getAssets().open("private-reports-fixture.mp4");OutputStream out=new FileOutputStream(clip)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
        activity=(MainActivity)i.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    @After public void cleanup()throws Exception{if(activity!=null){main(activity::finish);i.waitForIdleSync();}app.reports.revoke();if(clip!=null)clip.delete();}
    @Test public void actualPlaybackExcludesPauseSeekAndBackgroundAndWritesOwnerCiphertext()throws Exception{
        Models.Title title=new Models.Title(new JSONObject().put("id","fixture-private").put("type","tv").put("name","Private playback verification"));
        Models.Stream stream=new Models.Stream(new JSONObject().put("url",clip.toURI().toString()).put("name","secret-provider-token"),"secret-provider");
        main(()->activity.play(title,title.id,Collections.singletonList(stream),0));
        waitFor(()->engine()!=null&&engine().isPlaying()&&engine().getCurrentPosition()>300);
        Thread.sleep(1700);main(()->engine().pause());long before=total();assertTrue(before>=1500);
        Thread.sleep(1300);assertEquals(before,total());
        main(()->engine().seekTo(20000));Thread.sleep(500);assertEquals(before,total());
        main(()->engine().play());waitFor(()->engine().isPlaying());long resumed=total();Thread.sleep(1700);main(()->engine().pause());long after=total();assertTrue("active interval="+(after-resumed),after-resumed>=1400);assertTrue("active interval="+(after-resumed),after-resumed<2600);
        main(()->{engine().play();activity.miniPlayer();});Thread.sleep(1200);main(()->panel().onStop());long stopped=total();Thread.sleep(1200);assertEquals(stopped,total());
        main(()->{panel().onResume();activity.restorePlayer();});waitFor(()->engine().isPlaying());Thread.sleep(1100);main(()->engine().pause());long expected=total();main(activity::stopPlayer);
        ReportStore store=(ReportStore)field(app.reports,"store");long deadline=SystemClock.elapsedRealtime()+5000;
        while(store.snapshots().isEmpty()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(60);
        List<byte[]> reports=store.snapshots();assertEquals(1,reports.size());byte[] bytes=reports.get(0);
        String raw=new String(bytes,StandardCharsets.ISO_8859_1);assertFalse(raw.contains("Private playback"));assertFalse(raw.contains("secret-provider"));
        JSONObject row=new JSONObject(new String(decrypt(bytes),StandardCharsets.UTF_8));assertEquals(title.name,row.getString("title"));assertTrue(row.getBoolean("finished"));
        assertTrue(row.getLong("watched_ms")>=4500);assertEquals(expected,row.getLong("watched_ms"));assertFalse(row.has("url"));assertFalse(row.has("video_id"));
    }
    @Test public void disablingReportsStopsCollectionAndRevocationDeletesHistory()throws Exception{
        app.reports.setEnabled(false);
        Models.Title title=new Models.Title(new JSONObject().put("id","fixture").put("name","Unrecorded title"));
        app.reports.checkpoint(UUID.randomUUID().toString(),title,"fixture",System.currentTimeMillis(),15000,true,false);
        Thread.sleep(250);ReportStore store=(ReportStore)field(app.reports,"store");assertTrue(store.snapshots().isEmpty());
        app.reports.setEnabled(true);app.reports.checkpoint(UUID.randomUUID().toString(),title,"fixture",System.currentTimeMillis(),15000,true,false);
        long deadline=SystemClock.elapsedRealtime()+5000;while(store.snapshots().isEmpty()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(60);
        assertEquals(1,store.snapshots().size());app.reports.revoke();assertNull(app.reports.owner());assertTrue(store.snapshots().isEmpty());
    }
    @Test public void platformCryptoAndAtomicStorage()throws Exception{
        File folder=new File(app.getCacheDir(),"crypto-storage-fixture-"+UUID.randomUUID());
        ReportStore store=new ReportStore(folder);store.pair(owner.getPublic().getEncoded());
        byte[] plain="{\"fixture\":true}".getBytes(StandardCharsets.UTF_8);
        store.save(UUID.randomUUID().toString(),plain);assertArrayEquals(plain,decrypt(store.snapshots().get(0)));store.revoke();
    }
    private void main(Runnable r){i.runOnMainSync(r);i.waitForIdleSync();}
    private PlayerPanel panel(){return (PlayerPanel)field(activity,"playerPanel");}
    private ExoPlayer engine(){return panel()==null?null:(ExoPlayer)field(panel(),"player");}
    private long total(){AtomicLong result=new AtomicLong();main(()->result.set(((WatchClock)field(panel(),"reportClock")).total()));return result.get();}
    private static Object field(Object owner,String name){try{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    private void waitFor(BooleanSupplier condition)throws Exception{long end=SystemClock.elapsedRealtime()+15000;AtomicBoolean ready=new AtomicBoolean();while(SystemClock.elapsedRealtime()<end){main(()->ready.set(condition.getAsBoolean()));if(ready.get())return;Thread.sleep(60);}fail("Playback did not become ready");}
    private byte[] decrypt(byte[] envelope)throws Exception{
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(envelope));byte[] magic=new byte[8];in.readFully(magic);int length=in.readUnsignedShort();byte[] wrapped=new byte[length],nonce=new byte[12];in.readFully(wrapped);in.readFully(nonce);
        Cipher wrap=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");wrap.init(Cipher.DECRYPT_MODE,owner.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(wrap.doFinal(wrapped),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(Arrays.copyOf(envelope,10+length+12));byte[] encrypted=new byte[in.readInt()];in.readFully(encrypted);return cipher.doFinal(encrypted);
    }
}
