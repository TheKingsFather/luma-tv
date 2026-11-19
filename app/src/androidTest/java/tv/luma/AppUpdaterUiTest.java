package tv.luma;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Generated APKs and an unpaired debug account; never grants install permission. */
@RunWith(AndroidJUnit4.class)
public class AppUpdaterUiTest {
    private final Instrumentation i=InstrumentationRegistry.getInstrumentation();
    private final LumaApp app=(LumaApp)i.getTargetContext().getApplicationContext();
    private MainActivity activity;private AppUpdater.Transport oldTransport;private StremioClient.Transport oldApi;
    private String oldToken;private List<Models.Addon> oldAddons;private boolean oldGuest;
    private static final class Installer implements AppUpdateScreen.Installer {
        boolean allowed;int permissions,installs;File apk;
        public boolean allowed(){return allowed;}public void permission(){permissions++;}public void install(File file){installs++;apk=file;}
    }
    @Before public void setup()throws Exception{
        Assume.assumeFalse("Updater fixtures require an unpaired debug app",app.connected());
        oldTransport=app.updater.transport;oldToken=app.updater.token;oldApi=app.api.transport;oldAddons=app.addons;oldGuest=app.guest;
        app.updater.token="";app.guest=true;app.addons=Collections.emptyList();app.api.transport=(url,body)->new JSONObject().put("metas",new JSONArray());
        activity=(MainActivity)i.startActivitySync(new Intent(app,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(()->{try{Method method=MainActivity.class.getDeclaredMethod("showSettings");method.setAccessible(true);method.invoke(activity);}catch(Exception e){throw new AssertionError(e);}});
    }
    @After public void cleanup(){
        if(activity!=null)onMain(activity::finish);
        if(oldTransport!=null){app.updater.transport=oldTransport;app.updater.token=oldToken;app.api.transport=oldApi;app.addons=oldAddons;app.guest=oldGuest;}
        File[] files=new File(app.getCacheDir(),"updates").listFiles();if(files!=null)for(File file:files)file.delete();
    }
    private void onMain(Runnable action){i.runOnMainSync(action);i.waitForIdleSync();}
    private AppUpdateScreen screen(){try{Field field=MainActivity.class.getDeclaredField("updates");field.setAccessible(true);return (AppUpdateScreen)field.get(activity);}catch(Exception e){throw new AssertionError(e);}}
    private View dialog(){AppUpdateScreen screen=screen();return screen==null||screen.dialog==null?null:screen.dialog.getWindow().getDecorView();}
    private void check(){onMain(()->find(activity.getWindow().getDecorView(),"Check for app updates").performClick());}
    private void choose(String label){onMain(()->{View button=find(dialog(),label);assertNotNull(label,button);button.performClick();});}
    private void waitFor(BooleanSupplier condition,long timeout)throws Exception{long end=System.currentTimeMillis()+timeout;AtomicBoolean done=new AtomicBoolean();while(System.currentTimeMillis()<end){i.runOnMainSync(()->done.set(condition.getAsBoolean()));if(done.get())return;Thread.sleep(50);}fail("Updater condition timed out");}
    private void title(String text)throws Exception{waitFor(()->find(dialog(),text)!=null,20000);}
    private byte[] fixture(String name)throws Exception{File file=new File(app.getFilesDir(),"update-"+name+".apk");assertTrue("Prepare APK fixtures first: "+name,file.isFile());return Files.readAllBytes(file.toPath());}
    private static String sha(byte[] bytes)throws Exception{return AppUpdater.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private JSONObject metadata(byte[] apk)throws Exception{
        long[] current=AppUpdater.version(UpdatePackage.currentVersion(app));String next=current[0]+"."+current[1]+"."+(current[2]+1);
        return new JSONObject().put("tag_name","v"+next).put("assets",new JSONArray().put(new JSONObject().put("id",777).put("name","Luma-TV-"+next+".apk").put("size",apk.length).put("digest","sha256:"+sha(apk))));
    }
    private void feed(byte[] apk,JSONObject metadata){
        byte[] json=metadata.toString().getBytes(StandardCharsets.UTF_8);app.updater.transport=(path,token,binary)->new AppUpdater.Response(new ByteArrayInputStream(binary?apk:json),()->{});
    }
    @Test public void livePrivateReleaseCheckShowsTheAccessPrompt()throws Exception{
        app.updater.transport=new AppUpdater.HttpTransport();check();waitFor(()->find(dialog(),"Release access")!=null,45000);
        onMain(()->{EditText token=dialog().findViewWithTag("luma.update.token");assertNotNull(token);assertFalse(token.isSaveEnabled());assertTrue(token.hasFocus());});capture("update-access.png");
    }
    @Test public void tokenInputSupportsRemoteNavigationAndStaysInMemory()throws Exception{
        JSONObject current=new JSONObject().put("tag_name","v"+UpdatePackage.currentVersion(app));byte[] json=current.toString().getBytes(StandardCharsets.UTF_8);
        app.updater.transport=(path,token,binary)->{if(token.isEmpty())throw new AppUpdater.AuthenticationRequired();assertEquals("fixture-read-token",token);return new AppUpdater.Response(new ByteArrayInputStream(json),()->{});};
        check();title("Release access");onMain(()->((EditText)dialog().findViewWithTag("luma.update.token")).setText("fixture-read-token"));
        i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);i.waitForIdleSync();onMain(()->assertEquals("Cancel",((TextView)screen().dialog.getCurrentFocus()).getText().toString()));
        i.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);i.waitForIdleSync();onMain(()->assertTrue(dialog().findViewWithTag("luma.update.token").hasFocus()));
        choose("Check with token");waitFor(()->contains(dialog(),"You’re up to date"),15000);
        onMain(()->{assertEquals("fixture-read-token",app.updater.token);assertFalse(app.settings.getAll().containsValue("fixture-read-token"));});
    }
    @Test public void verifiedApkPermissionAndInstallerHandoffKeepPrivateFilesScoped()throws Exception{
        byte[] apk=fixture("candidate");feed(apk,metadata(apk));check();title("Update available");capture("update-available.png");
        Installer host=new Installer();onMain(()->screen().installer=host);choose("Download");title("Ready to install");capture("update-ready.png");
        choose("Install update");title("Allow app updates");choose("Open settings");onMain(()->{assertEquals(1,host.permissions);screen().onResult(AppUpdateScreen.PERMISSION,Activity.RESULT_CANCELED);});
        title("Ready to install");assertEquals(0,host.installs);choose("Install update");title("Allow app updates");choose("Open settings");
        onMain(()->{host.allowed=true;screen().onResult(AppUpdateScreen.PERMISSION,Activity.RESULT_OK);assertEquals(1,host.installs);});
        assertArrayEquals(apk,Files.readAllBytes(host.apk.toPath()));Intent intent=UpdatePackage.installIntent(app,host.apk);Uri uri=intent.getData();
        assertEquals("content",uri.getScheme());assertEquals(app.getPackageName()+".updates",uri.getAuthority());assertEquals(Intent.ACTION_INSTALL_PACKAGE,intent.getAction());assertTrue((intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
        assertNotNull("Android installer must be available",app.getPackageManager().resolveActivity(intent,0));
        try(InputStream in=app.getContentResolver().openInputStream(uri)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);assertEquals(sha(apk),sha(out.toByteArray()));}
        File privateFile=new File(app.getFilesDir(),"private.txt");Files.write(privateFile.toPath(),new byte[]{1});
        assertThrows(IllegalArgumentException.class,()->FileProvider.getUriForFile(app,app.getPackageName()+".updates",privateFile));privateFile.delete();
        onMain(()->screen().onResult(AppUpdateScreen.INSTALL,Activity.RESULT_CANCELED));title("Ready to install");choose("Later");assertFalse(host.apk.exists());
    }
    @Test public void checksumSignaturePackageAndVersionFailuresNeverReachTheInstaller()throws Exception{
        byte[] candidate=fixture("candidate");JSONObject broken=metadata(candidate);broken.getJSONArray("assets").getJSONObject(0).put("digest","sha256:"+String.join("",Collections.nCopies(64,"0")));
        feed(candidate,broken);check();title("Update available");Installer host=new Installer();host.allowed=true;onMain(()->screen().installer=host);choose("Download");waitFor(()->contains(dialog(),"integrity check"),15000);assertEquals(0,host.installs);choose("Close");
        byte[] wrongKey=fixture("wrong-key");feed(wrongKey,metadata(wrongKey));check();title("Update available");choose("Download");waitFor(()->contains(dialog(),"different signing key"),20000);assertEquals(0,host.installs);choose("Close");
        byte[] other=fixture("other-package");feed(other,metadata(other));check();title("Update available");choose("Download");waitFor(()->contains(dialog(),"different app"),20000);assertEquals(0,host.installs);choose("Close");
        byte[] current=fixture("current");feed(current,metadata(current));check();title("Update available");choose("Download");waitFor(()->contains(dialog(),"not newer"),20000);assertEquals(0,host.installs);
        File[] remaining=new File(app.getCacheDir(),"updates").listFiles();assertNotNull(remaining);assertEquals(0,remaining.length);
    }
    @Test public void cancelingDownloadClosesItAndDoesNotReopenTheDialog()throws Exception{
        byte[] apk=fixture("candidate"),json=metadata(apk).toString().getBytes(StandardCharsets.UTF_8);CountDownLatch opened=new CountDownLatch(1);AtomicInteger closed=new AtomicInteger();
        app.updater.transport=(path,token,binary)->{
            if(!binary)return new AppUpdater.Response(new ByteArrayInputStream(json),()->{});
            InputStream slow=new InputStream(){final ByteArrayInputStream source=new ByteArrayInputStream(apk);public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}public int read(byte[] b,int off,int length)throws IOException{opened.countDown();try{Thread.sleep(60);}catch(InterruptedException e){throw new InterruptedIOException("Canceled");}return source.read(b,off,Math.min(length,512));}};
            return new AppUpdater.Response(slow,closed::incrementAndGet);
        };
        check();title("Update available");choose("Download");assertTrue(opened.await(5,TimeUnit.SECONDS));i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);i.waitForIdleSync();
        waitFor(()->{File[] files=new File(app.getCacheDir(),"updates").listFiles();return screen().dialog==null&&files!=null&&files.length==0;},5000);assertEquals(1,closed.get());Thread.sleep(300);onMain(()->assertNull(screen().dialog));
    }
    private static View find(View view,String label){if(view==null)return null;if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){View match=find(group.getChildAt(n),label);if(match!=null)return match;}}return null;}
    private static boolean contains(View view,String text){if(view==null)return false;if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++)if(contains(group.getChildAt(n),text))return true;}return false;}
    private void capture(String name)throws Exception{i.waitForIdleSync();Thread.sleep(350);Bitmap image=i.getUiAutomation().takeScreenshot();assertNotNull(image);try(OutputStream out=new FileOutputStream(new File(app.getFilesDir(),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
}
