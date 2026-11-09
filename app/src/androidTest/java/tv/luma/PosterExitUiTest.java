package tv.luma;

import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Safe on an owner's TV: only generated views on a debug-only activity. */
@RunWith(AndroidJUnit4.class)
public class PosterExitUiTest {
    private final Instrumentation i=InstrumentationRegistry.getInstrumentation();
    private UiFixtureActivity activity;
    private Glass g;
    private Dialog dialog;
    @Before public void setup(){
        activity=(UiFixtureActivity)i.startActivitySync(new Intent().setClassName(i.getTargetContext(),"tv.luma.UiFixtureActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        onMain(()->g=new Glass(activity));
    }
    @After public void cleanup(){onMain(()->{if(dialog!=null)dialog.dismiss();activity.finish();});}
    private void onMain(Runnable action){i.runOnMainSync(action);i.waitForIdleSync();}
    private void waitFor(BooleanSupplier condition)throws Exception{
        long end=System.currentTimeMillis()+5000;AtomicBoolean value=new AtomicBoolean();
        while(System.currentTimeMillis()<end){i.runOnMainSync(()->value.set(condition.getAsBoolean()));if(value.get())return;Thread.sleep(40);}fail("UI condition timed out");
    }
    private PosterCard card(String type)throws Exception{
        Models.Title title=new Models.Title(new JSONObject().put("id","fixture").put("name","Blue Horizon").put("type",type).put("releaseInfo","2026").put("imdbRating","8.1"));
        PosterCard card=new PosterCard(g,title,null,()->{},null);
        try{
            java.lang.reflect.Field field=PosterCard.class.getDeclaredField("backdrop");field.setAccessible(true);
            Bitmap art=Bitmap.createBitmap(640,360,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(art);canvas.drawColor(0xff225b70);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(0xff9cb9bd);canvas.drawCircle(440,100,55,paint);paint.setColor(0xff14283b);canvas.drawRect(0,230,640,360,paint);
            ((ImageView)field.get(card)).setImageBitmap(art);
        }catch(Exception e){throw new AssertionError(e);}
        return card;
    }
    @Test public void delayedFocusExpandsAndLosingFocusRestoresPoster()throws Exception{
        final PosterCard[] cards=new PosterCard[2];
        onMain(()->{try{
            HorizontalScrollView scroll=new HorizontalScrollView(activity);LinearLayout row=g.row();scroll.addView(row);g.at(activity.surface,scroll,700,240,60,100);
            for(int n=0;n<2;n++){cards[n]=card("movie");row.addView(cards[n],new LinearLayout.LayoutParams(cards[n].collapsedWidth(),g.u(216)));g.gap(row,18);}
            cards[0].requestFocus();assertFalse(cards[0].isExpanded());
        }catch(Exception e){throw new AssertionError(e);}});
        waitFor(()->cards[0].isExpanded()&&cards[0].getWidth()>cards[0].collapsedWidth()*2);
        capture("poster-expansion-fixture.png");
        onMain(()->{assertEquals(g.u(216),cards[0].getHeight());cards[1].requestFocus();});
        waitFor(()->!cards[0].isExpanded()&&cards[0].getWidth()==cards[0].collapsedWidth());
        onMain(()->cards[0].requestFocus());Thread.sleep(80);onMain(()->cards[1].requestFocus());Thread.sleep(400);
        onMain(()->assertFalse(cards[0].isExpanded()));
    }
    @Test public void landscapeCardsKeepTheirDimensions()throws Exception{
        final PosterCard[] c=new PosterCard[1];onMain(()->{try{c[0]=card("tv");g.at(activity.surface,c[0],232,189,60,100);c[0].requestFocus();}catch(Exception e){throw new AssertionError(e);}});
        Thread.sleep(700);onMain(()->{assertFalse(c[0].isExpanded());assertEquals(g.u(232),c[0].getWidth());});
    }
    @Test public void horizontalRowRevealsTheExpandedTrailingCard()throws Exception{
        final PosterCard[] last=new PosterCard[1];final HorizontalScrollView[] scroll=new HorizontalScrollView[1];
        onMain(()->{try{
            scroll[0]=new HorizontalScrollView(activity);LinearLayout row=g.row();scroll[0].addView(row);g.at(activity.surface,scroll[0],500,240,60,100);
            for(int n=0;n<6;n++){PosterCard c=card("movie");row.addView(c,new LinearLayout.LayoutParams(c.collapsedWidth(),g.u(216)));g.gap(row,18);last[0]=c;}
            last[0].requestFocus();
        }catch(Exception e){throw new AssertionError(e);}});
        waitFor(()->last[0].isExpanded()&&last[0].getWidth()>last[0].collapsedWidth()*2&&scroll[0].getScrollX()>0);
        Thread.sleep(350);onMain(()->assertTrue(last[0].getLeft()-scroll[0].getScrollX()+last[0].getWidth()<=scroll[0].getWidth()+2));
    }
    @Test public void gridPreviewStaysWithinItsRowAndPreservesSlotWidth()throws Exception{
        final PosterCard[] c=new PosterCard[1];final FrameLayout[] slot=new FrameLayout[1];final LinearLayout[] row=new LinearLayout[1];
        onMain(()->{try{
            row[0]=g.row();row[0].setPadding(0,0,0,0);g.at(activity.surface,row[0],600,216,60,100);
            row[0].addView(new View(activity),new LinearLayout.LayoutParams(g.u(450),1));
            slot[0]=new FrameLayout(activity);slot[0].setClipChildren(false);row[0].addView(slot[0],new LinearLayout.LayoutParams(g.u(125),g.u(216)));
            c[0]=card("movie");c[0].useGridSlot(slot[0]);slot[0].addView(c[0],new FrameLayout.LayoutParams(g.u(125),g.u(216)));c[0].requestFocus();
        }catch(Exception e){throw new AssertionError(e);}});
        waitFor(()->c[0].isExpanded()&&c[0].getWidth()>g.u(270));
        onMain(()->{assertEquals(g.u(125),slot[0].getWidth());assertTrue(slot[0].getLeft()+c[0].getTranslationX()+c[0].getWidth()<=row[0].getWidth()+1);});
    }
    @Test public void exitDialogDefaultsToStayAndBackDoesNotExit()throws Exception{
        AtomicBoolean exited=new AtomicBoolean();onMain(()->dialog=ExitDialog.show(g,()->exited.set(true)));
        waitFor(()->dialog.getCurrentFocus() instanceof TextView);
        onMain(()->assertEquals("Stay",((TextView)dialog.getCurrentFocus()).getText().toString()));
        capture("exit-dialog-fixture.png");i.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);i.waitForIdleSync();
        onMain(()->{assertFalse(dialog.isShowing());assertFalse(exited.get());assertFalse(activity.isFinishing());});
    }
    @Test public void exitActionRunsOnlyAfterConfirmation(){
        AtomicBoolean exited=new AtomicBoolean();onMain(()->{dialog=ExitDialog.show(g,()->exited.set(true));assertFalse(exited.get());TextView exit=find(dialog.getWindow().getDecorView(),"Exit Luma");assertNotNull(exit);exit.performClick();assertTrue(exited.get());assertFalse(dialog.isShowing());});
    }
    private static TextView find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int n=0;n<group.getChildCount();n++){TextView result=find(group.getChildAt(n),text);if(result!=null)return result;}}return null;}
    private void capture(String name)throws Exception{Thread.sleep(300);Bitmap image=i.getUiAutomation().takeScreenshot();assertNotNull(image);try(FileOutputStream out=new FileOutputStream(new File(i.getTargetContext().getCacheDir(),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
}
