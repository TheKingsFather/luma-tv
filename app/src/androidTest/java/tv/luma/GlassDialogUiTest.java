package tv.luma;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Generated modal fixtures only: no account, settings or library mutations. */
@RunWith(AndroidJUnit4.class)
public class GlassDialogUiTest {
    private final Instrumentation i=InstrumentationRegistry.getInstrumentation();
    private UiFixtureActivity activity;
    private Glass g;
    private TextView opener;
    private final List<GlassDialog> dialogs=new ArrayList<>();
    private GlassDialog dialog;
    @Before public void setup(){
        activity=(UiFixtureActivity)i.startActivitySync(new Intent().setClassName(i.getTargetContext(),"tv.luma.UiFixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(()->{g=new Glass(activity);opener=g.button("Open menu",false,()->{});g.at(activity.surface,opener,180,50,60,60);opener.requestFocus();});
    }
    @After public void cleanup(){onMain(()->{for(GlassDialog d:dialogs)d.dismiss();activity.finish();});}
    private void onMain(Runnable action){i.runOnMainSync(action);i.waitForIdleSync();}
    private void show(GlassDialog d){dialogs.add(d);dialog=d;d.show();}
    private void key(int key){i.sendKeyDownUpSync(key);i.waitForIdleSync();}
    private void waitFor(BooleanSupplier condition)throws Exception{
        long end=System.currentTimeMillis()+6000;AtomicBoolean done=new AtomicBoolean();
        while(System.currentTimeMillis()<end){i.runOnMainSync(()->done.set(condition.getAsBoolean()));if(done.get())return;Thread.sleep(40);}fail("Modal condition timed out");
    }
    @Test public void selectedChoiceGetsFocusAndRemoteSelectionRunsOnce()throws Exception{
        AtomicInteger selected=new AtomicInteger(-1);
        onMain(()->show(GlassDialog.choices(g,"Embedded subtitles","Tracks included with your current source.",new String[]{"Off","English","French"},1,selected::set)));
        waitFor(()->dialog.getCurrentFocus()!=null);
        onMain(()->{assertTrue(dialog.getCurrentFocus().isSelected());assertTrue(dialog.getCurrentFocus().getContentDescription().toString().startsWith("English"));});
        capture("subtitles-dialog-fixture.png");key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER);
        onMain(()->{assertEquals(2,selected.get());assertFalse(dialog.isShowing());});
        waitFor(()->opener.hasFocus()&&activity.hasWindowFocus());
    }
    @Test public void backAndCloseDoNotChooseAnOption()throws Exception{
        AtomicInteger selected=new AtomicInteger(-1);
        onMain(()->show(GlassDialog.choices(g,"Audio tracks","",new String[]{"English","French"},0,selected::set)));
        key(KeyEvent.KEYCODE_BACK);onMain(()->{assertEquals(-1,selected.get());assertFalse(dialog.isShowing());});
        waitFor(()->opener.hasFocus()&&activity.hasWindowFocus());
        onMain(()->{show(GlassDialog.choices(g,"Audio tracks","",new String[]{"English"},0,selected::set));find(dialog.getWindow().getDecorView(),"Close").performClick();assertEquals(-1,selected.get());assertFalse(dialog.isShowing());});
    }
    @Test public void longSourceListRevealsCurrentItemAndSupportsFooterNavigation()throws Exception{
        List<GlassDialog.Choice> choices=new ArrayList<>();
        for(int n=0;n<30;n++)choices.add(new GlassDialog.Choice("Source "+(n+1)+" · 4K HDR", "Cinema provider · 2160p · Dolby audio · A long source description remains readable within this row"));
        onMain(()->show(GlassDialog.choices(g,"Choose your source","Blue Horizon · 30 playable sources",choices,27,n->{})));
        waitFor(()->{View focus=dialog.getCurrentFocus();Rect visible=new Rect();return focus!=null&&focus.getLocalVisibleRect(visible)&&visible.height()==focus.getHeight();});
        Rect header=new Rect();AtomicInteger scrollY=new AtomicInteger();
        Thread.sleep(400);capture("sources-dialog-fixture.png");
        onMain(()->{View heading=find(dialog.getWindow().getDecorView(),"Choose your source");int[] xy=new int[2];heading.getLocationOnScreen(xy);header.set(xy[0],xy[1],xy[0]+heading.getWidth(),xy[1]+heading.getHeight());scrollY.set(findScroll(dialog.getWindow().getDecorView()).getScrollY());});
        Bitmap before=i.getUiAutomation().takeScreenshot();int[] headerBefore=new int[header.width()*header.height()];before.getPixels(headerBefore,0,header.width(),header.left,header.top,header.width(),header.height());before.recycle();
        onMain(()->findScroll(dialog.getWindow().getDecorView()).scrollTo(0,0));Thread.sleep(200);
        Bitmap after=i.getUiAutomation().takeScreenshot();int[] headerAfter=new int[headerBefore.length];after.getPixels(headerAfter,0,header.width(),header.left,header.top,header.width(),header.height());after.recycle();
        assertArrayEquals("Scrolling source rows must not paint over the heading",headerBefore,headerAfter);
        onMain(()->findScroll(dialog.getWindow().getDecorView()).scrollTo(0,scrollY.get()));
        capture("sources-dialog-fixture.png");key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_DOWN);
        onMain(()->{assertEquals("Close",((TextView)dialog.getCurrentFocus()).getText().toString());assertTrue(dialog.getWindow().getDecorView().getHeight()<=activity.getResources().getDisplayMetrics().heightPixels);});
        key(KeyEvent.KEYCODE_DPAD_UP);onMain(()->assertTrue(dialog.getCurrentFocus().getContentDescription().toString().startsWith("Source 30")));
    }
    @Test public void openingChildMenuDismissesParentAndKeepsChildInteractive()throws Exception{
        AtomicInteger selected=new AtomicInteger(-1);final GlassDialog[] parent=new GlassDialog[1];
        onMain(()->{parent[0]=GlassDialog.choices(g,"Audio & subtitles","",new String[]{"Embedded subtitles"},-1,n->show(GlassDialog.choices(g,"Embedded subtitles","",new String[]{"Off","English"},1,selected::set)));show(parent[0]);});
        key(KeyEvent.KEYCODE_DPAD_CENTER);
        waitFor(()->!parent[0].isShowing()&&dialog.isShowing()&&dialog.getCurrentFocus()!=null);
        key(KeyEvent.KEYCODE_DPAD_UP);key(KeyEvent.KEYCODE_DPAD_CENTER);onMain(()->assertEquals(0,selected.get()));
    }
    @Test public void confirmationDefaultsToCancelAndOnlyExplicitAcceptActs()throws Exception{
        AtomicInteger accepted=new AtomicInteger();
        onMain(()->show(GlassDialog.confirm(g,"Disconnect Stremio?","Your account stays on Stremio.","Keep connected","Disconnect",accepted::incrementAndGet)));
        onMain(()->assertEquals("Keep connected",((TextView)dialog.getCurrentFocus()).getText().toString()));
        key(KeyEvent.KEYCODE_BACK);onMain(()->assertEquals(0,accepted.get()));
        onMain(()->{show(GlassDialog.confirm(g,"Disconnect Stremio?","Your account stays on Stremio.","Keep connected","Disconnect",accepted::incrementAndGet));find(dialog.getWindow().getDecorView(),"Disconnect").performClick();assertEquals(1,accepted.get());assertFalse(dialog.isShowing());});
    }
    @Test public void messageUsesSharedSurfaceAndCloseKeepsOwnerOpen()throws Exception{
        onMain(()->show(GlassDialog.message(g,"No playable sources","Your add-ons did not return a playable source. Try another provider.")));
        onMain(()->assertEquals("Close",((TextView)dialog.getCurrentFocus()).getText().toString()));
        capture("message-dialog-fixture.png");key(KeyEvent.KEYCODE_DPAD_CENTER);onMain(()->{assertFalse(dialog.isShowing());assertFalse(activity.isFinishing());});
    }
    @Test public void statusNoticeReusesSurfaceWithoutStealingFocusAndFades()throws Exception{
        onMain(()->{g.notice("Sleep timer set");g.notice("Saved to library");assertTrue(opener.hasFocus());View notice=activity.getWindow().getDecorView().findViewWithTag("luma.notice");assertEquals("Saved to library",((TextView)notice).getText().toString());assertFalse(notice.isFocusable());});
        waitFor(()->activity.getWindow().getDecorView().findViewWithTag("luma.notice").getVisibility()==View.GONE);
        onMain(()->assertTrue(opener.hasFocus()));
    }
    private static TextView find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){TextView match=find(group.getChildAt(n),text);if(match!=null)return match;}}return null;}
    private static ScrollView findScroll(View view){if(view instanceof ScrollView)return (ScrollView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){ScrollView match=findScroll(group.getChildAt(n));if(match!=null)return match;}}return null;}
    private void capture(String name)throws Exception{Thread.sleep(250);Bitmap bitmap=i.getUiAutomation().takeScreenshot();assertNotNull(bitmap);try(FileOutputStream out=new FileOutputStream(new File(i.getTargetContext().getCacheDir(),name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}}
}
