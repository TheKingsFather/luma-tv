package tv.luma;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import org.json.*;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import static tv.luma.Models.*;

public final class MainActivity extends Activity {
    private LumaApp app;
    private Glass g;
    private FrameLayout root,shell,browser;
    private PlayerPanel playerPanel;
    private TextView returnToPlayer;
    private final Map<String,BrowsePage> browsePages=new LinkedHashMap<>();
    private BrowsePage listing;
    private static final class BrowsePage {
        String key,page;FrameLayout root;LinearLayout rows;View focus,navFocus;
        TextView heroTitle,heroSubtitle,heroDescription;ImageView heroArt;Title hero;
        Catalog catalog;boolean valid=true,loading;
    }
    private final Handler main=new Handler(Looper.getMainLooper());
    private int screen=0,searchSerial=0,pairGeneration=0,sourceRequest=0;
    private String page="Home",pairCode="";
    private boolean active=false,pairing=false,connecting=false;
    private long pairCreated;
    private Runnable pairPoll;
    private TextView pairStatus,pairCodeView,heroTitle,heroSubtitle,heroDescription;
    private ImageView pairQr,heroArt;
    private Title hero,detail;
    private View navFocus;
    private LinearLayout rows;
    private Catalog selectedCatalog;
    private int catalogOffset=0;
    private final Map<String,List<Title>> rowCache=new LinkedHashMap<>();
    private String detailVideo="";
    private String homeTab="Home",detailOrigin="Home",lastQuery="";
    private int selectedSeason=-1;
    private Dialog sourcesDialog,exitDialog;
    private final List<Dialog> browseDialogs=new ArrayList<>();


    @Override public void onCreate(Bundle state){super.onCreate(state);app=(LumaApp)getApplication();g=new Glass(this);shell=g.root();browser=new FrameLayout(this);shell.addView(browser,new FrameLayout.LayoutParams(-1,-1));returnToPlayer=g.button("Return to player",false,this::restorePlayer);g.at(shell,returnToPlayer,192,48,220,30);returnToPlayer.setVisibility(View.GONE);setContentView(shell);Glass.fullscreen(this);PlayerActivity.Request pending=getIntent().getBooleanExtra("playPending",false)?app.playback:null;if(app.connected()||app.guest||pending!=null)showHome("Home");else showPairing();if(pending!=null)play(pending.title,pending.videoId,pending.streams,pending.index);}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("playPending",false)&&app.playback!=null){PlayerActivity.Request p=app.playback;play(p.title,p.videoId,p.streams,p.index);}}
    @Override protected void onStart(){super.onStart();active=true;if(pairing&&pairPoll!=null)main.post(pairPoll);}
    @Override protected void onResume(){super.onResume();Glass.fullscreen(this);if(playerPanel!=null)playerPanel.onResume();}
    @Override protected void onStop(){super.onStop();active=false;if(playerPanel!=null)playerPanel.onStop();if(pairPoll!=null)main.removeCallbacks(pairPoll);}
    @Override protected void onDestroy(){screen++;invalidateBrowse();if(playerPanel!=null)playerPanel.release();main.removeCallbacksAndMessages(null);if(exitDialog!=null)exitDialog.dismiss();for(Dialog dialog:new ArrayList<>(browseDialogs))dialog.dismiss();browseDialogs.clear();super.onDestroy();}
    private FrameLayout newScreen(){screen++;pairing=false;pairCode="";navFocus=null;heroTitle=null;hero=null;if(pairPoll!=null)main.removeCallbacks(pairPoll);if(listing!=null&&root==listing.root)listing.focus=root.findFocus();root=g.root();browser.removeAllViews();browser.addView(root,new FrameLayout.LayoutParams(-1,-1));return root;}

    private BrowsePage rememberListing(String key){
        BrowsePage p=new BrowsePage();p.key=key;p.page=page;p.root=root;p.rows=rows;p.navFocus=navFocus;
        p.heroTitle=heroTitle;p.heroSubtitle=heroSubtitle;p.heroDescription=heroDescription;p.heroArt=heroArt;p.hero=hero;p.catalog=selectedCatalog;
        browsePages.put(key,p);listing=p;return p;
    }
    private void invalidateBrowse(){for(BrowsePage p:browsePages.values())p.valid=false;browsePages.clear();listing=null;}
    private boolean restoreListing(BrowsePage p){
        if(p==null||!p.valid)return false;
        screen++;pairing=false;if(pairPoll!=null)main.removeCallbacks(pairPoll);
        if(listing!=null&&root==listing.root)listing.focus=root.findFocus();
        listing=p;root=p.root;rows=p.rows;page=p.page;selectedCatalog=p.catalog;navFocus=p.navFocus;
        heroTitle=p.heroTitle;heroSubtitle=p.heroSubtitle;heroDescription=p.heroDescription;heroArt=p.heroArt;hero=p.hero;
        browser.removeAllViews();browser.addView(root,new FrameLayout.LayoutParams(-1,-1));
        View target=p.focus!=null?p.focus:p.navFocus;if(target!=null)root.post(()->{if(root==p.root&&target.isAttachedToWindow())target.requestFocus();});return true;
    }
    private void backToBrowse(){if(!restoreListing(listing))showHome(homeTab);}
    private <T> void browseTask(BrowsePage p,Callable<T> call,Consumer<T> success,Consumer<Exception> failure){
        app.io.execute(()->{try{T result=call.call();main.post(()->{if(!isDestroyed()&&p.valid)success.accept(result);});}catch(Exception e){main.post(()->{if(!isDestroyed()&&p.valid)failure.accept(e);});}});
    }
    void play(Title title,String id,List<Stream> streams,int index){
        if(playerPanel!=null){playerPanel.release();shell.removeView(playerPanel.view());}
        playerPanel=new PlayerPanel(this,title,id,streams,index);shell.addView(playerPanel.view(),new FrameLayout.LayoutParams(-1,-1));browser.setVisibility(View.INVISIBLE);returnToPlayer.setVisibility(View.GONE);
    }
    void miniPlayer(){if(playerPanel==null)return;backToBrowse();browser.setVisibility(View.VISIBLE);returnToPlayer.setVisibility(View.VISIBLE);playerPanel.setMini(true);}
    void restorePlayer(){if(playerPanel==null)return;browser.setVisibility(View.INVISIBLE);returnToPlayer.setVisibility(View.GONE);playerPanel.setMini(false);}
    void stopPlayer(){if(playerPanel==null)return;boolean full=!playerPanel.isMini();PlayerPanel old=playerPanel;playerPanel=null;old.release();shell.removeView(old.view());browser.setVisibility(View.VISIBLE);returnToPlayer.setVisibility(View.GONE);if(full)backToBrowse();else if(listing!=null&&root==listing.root&&listing.focus!=null)listing.focus.requestFocus();}
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        if(playerPanel!=null&&playerPanel.dispatchKeyEvent(event))return true;return super.dispatchKeyEvent(event);
    }
    private <T> void task(Callable<T> call,Consumer<T> success,Consumer<Exception> failure){int token=screen;app.io.execute(()->{try{T result=call.call();main.post(()->{if(!isDestroyed()&&screen==token)success.accept(result);});}catch(Exception e){main.post(()->{if(!isDestroyed()&&screen==token)failure.accept(e);});}});}
    private void toast(String text){g.notice(text);}
    private void brand(){LinearLayout row=g.row();row.addView(g.icon("logo",Glass.MINT,29));g.gap(row,10);TextView logo=g.text("luma",27,Glass.WHITE,true);logo.setLetterSpacing(.02f);row.addView(logo);g.at(root,row,175,48,52,30);}
    private void footer(String label){TextView hint=g.text(label,12,Glass.MUTED,false);g.at(root,hint,1100,20,56,684);}

    private void showPairing(){
        if(playerPanel!=null)stopPlayer();invalidateBrowse();newScreen();page="Connect";pairing=true;connecting=false;brand();
        TextView top=g.text("YOUR STREMIO, REIMAGINED",11,Glass.MUTED,true);top.setLetterSpacing(.15f);top.setGravity(Gravity.RIGHT);g.at(root,top,340,24,884,44);
        TextView eyebrow=g.text("A LITTLE GLASS. A LOT OF CINEMA.",11,Glass.MINT,true);eyebrow.setLetterSpacing(.16f);g.at(root,eyebrow,650,28,80,179);
        TextView headline=g.text("Everything you love.\nIn a new light.",59,Glass.WHITE,true);headline.setLineSpacing(g.u(2),1);headline.setLetterSpacing(-.035f);g.at(root,headline,720,160,76,220);
        TextView intro=g.text("Connect Stremio to bring your library, add-ons\nand the things you’re watching to your TV.",19,Glass.MUTED,false);intro.setLineSpacing(g.u(8),1);g.at(root,intro,650,70,80,402);
        LinearLayout steps=g.row();step(steps,"01","Open your phone’s camera");g.gap(steps,28);step(steps,"02","Scan & connect");g.at(root,steps,660,44,80,504);
        TextView explore=g.button("Explore the interface   →",false,()->{newScreen();showLoading("Opening your cinema…");task(()->{app.refresh();return true;},v->showHome("Home"),e->{toast(StremioClient.readable(e));showPairing();});});g.at(root,explore,242,48,80,586);
        FrameLayout panel=new FrameLayout(this);g.material(panel,32);g.at(root,panel,342,478,850,143);
        TextView scan=g.text("CONNECT WITH STREMIO",11,Glass.MINT,true);scan.setLetterSpacing(.12f);scan.setGravity(Gravity.CENTER);g.at(panel,scan,300,30,21,27);
        FrameLayout qrShell=new FrameLayout(this);qrShell.setBackground(rounded(0xfff9fffc,20));g.at(panel,qrShell,258,258,42,75);
        pairQr=new ImageView(this);pairQr.setScaleType(ImageView.ScaleType.FIT_CENTER);pairQr.setContentDescription("Scan this QR code with your phone to connect Stremio");g.at(qrShell,pairQr,238,238,10,10);
        pairCodeView=g.text("· · · ·",24,Glass.WHITE,true);pairCodeView.setLetterSpacing(.2f);pairCodeView.setGravity(Gravity.CENTER);g.at(panel,pairCodeView,300,36,21,350);
        pairStatus=g.text(app.sessionUnreadable?"Session unavailable. Connect again.":"Creating your secure connection…",12,Glass.MUTED,false);pairStatus.setGravity(Gravity.CENTER);g.at(panel,pairStatus,304,40,19,395);
        TextView url=g.text("Or enter the code at link.stremio.com",11,Glass.MUTED,false);url.setGravity(Gravity.CENTER);g.at(root,url,360,24,842,642);
        TextView regenerate=g.button("New code",false,this::createPairCode);g.at(root,regenerate,124,40,960,82);regenerate.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,g.u(13));
        footer("Your phone handles sign-in. Luma never asks for your Stremio password.");explore.requestFocus();createPairCode();
    }
    private void step(LinearLayout parent,String number,String label){TextView n=g.text(number,12,Glass.MINT,true);n.setGravity(Gravity.CENTER);n.setBackground(g.panel(18,false));parent.addView(n,new LinearLayout.LayoutParams(g.u(36),g.u(36)));g.gap(parent,10);parent.addView(g.text(label,13,Glass.WHITE,false));}
    private void createPairCode(){
        if(!pairing||connecting)return;final int generation=++pairGeneration;pairCode="";if(pairPoll!=null)main.removeCallbacks(pairPoll);pairQr.setImageDrawable(null);pairStatus.setText("Creating your secure connection…");pairCodeView.setText("· · · ·");
        task(()->{JSONObject link=app.api.createLink();String url=text(link,"link");if(!url.startsWith("https://link.stremio.com/"))throw new IllegalStateException("Unexpected pairing link");return link;},link->{
            if(generation!=pairGeneration)return;pairCode=text(link,"code");if(pairCode.isEmpty()){pairStatus.setText("Couldn’t create a code. Select New code.");return;}
            pairCreated=System.currentTimeMillis();pairCodeView.setText(pairCode.toUpperCase(Locale.ROOT));
            try{BitMatrix matrix=new MultiFormatWriter().encode(text(link,"link"),BarcodeFormat.QR_CODE,640,640,Collections.singletonMap(EncodeHintType.MARGIN,2));Bitmap bitmap=Bitmap.createBitmap(640,640,Bitmap.Config.ARGB_8888);int[] pixels=new int[640*640];for(int y=0;y<640;y++)for(int x=0;x<640;x++)pixels[y*640+x]=matrix.get(x,y)?0xff112a2e:0xfff9fffc;bitmap.setPixels(pixels,0,640,0,0,640,640);pairQr.setImageBitmap(bitmap);}catch(Exception e){pairStatus.setText("Use the code below to connect.");}
            pairStatus.setText("●  Waiting for your phone");pairStatus.setTextColor(Glass.MINT);startPolling();
        },e->{if(generation!=pairGeneration)return;pairStatus.setText("Can’t connect. Check internet, then select New code.");pairStatus.setTextColor(Glass.MUTED);});
    }
    private void startPolling(){
        final String code=pairCode;final int token=screen;
        pairPoll=()->{
            if(!pairing||!active||screen!=token||!code.equals(pairCode)||connecting)return;
            if(System.currentTimeMillis()-pairCreated>600000){pairStatus.setText("Code expired. Select New code to continue.");return;}
            task(()->app.api.readLink(code),key->{
                if(!code.equals(pairCode))return;
                if(key.isEmpty()){main.postDelayed(pairPoll,2500);return;}
                connecting=true;pairStatus.setText("Connected. Bringing your library in…");
                task(()->{app.connect(key);return true;},v->{toast("Connected to Stremio");showHome("Home");},e->{connecting=false;pairStatus.setText("Couldn’t load your account. Select New code.");});
            },e->{if(e instanceof StremioClient.ApiException){pairStatus.setText("Code expired. Select New code to continue.");return;}pairStatus.setText("Reconnecting…");main.postDelayed(pairPoll,5000);});
        };main.postDelayed(pairPoll,1500);
    }
    private void showLoading(String message){brand();TextView t=g.text(message,28,Glass.WHITE,false);t.setGravity(Gravity.CENTER);g.at(root,t,1000,100,140,290);}
    private Drawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(g.u(radius));return d;}

    private void header(String selected){
        LinearLayout nav=g.row();g.material(nav,30);g.pad(nav,10,6);
        FrameLayout search=headerControl("Search","search",false,this::showSearch);
        nav.addView(search,new LinearLayout.LayoutParams(g.u(48),g.u(44)));g.gap(nav,8);
        for(String item:new String[]{"Home","Movies","Series","Live","Library"}){
            FrameLayout tab=headerControl(item,null,item.equals(selected),()->showHome(item));
            nav.addView(tab,new LinearLayout.LayoutParams(0,g.u(44),1));
            if(item.equals(selected))navFocus=tab;
        }
        g.at(root,nav,620,56,330,28);
        TextView settings=g.button("⚙",false,this::showSettings);settings.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,g.u(22));settings.setPadding(0,0,0,0);settings.setContentDescription("Settings and account");g.at(root,settings,50,50,1122,30);
    }
    private FrameLayout headerControl(String label,String icon,boolean selected,Runnable action){
        FrameLayout control=new FrameLayout(this);g.focus(control,24,null);control.setBackground(null);control.setContentDescription(label);control.setOnClickListener(v->action.run());
        TextView text=icon==null?g.text(label,18,selected?Glass.MINT:Glass.MUTED,false):null;
        if(text!=null){text.setGravity(Gravity.CENTER);text.setSingleLine(true);control.addView(text,new FrameLayout.LayoutParams(-1,g.u(36)));}
        else{View glyph=g.icon(icon,Glass.WHITE,22);FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(g.u(22),g.u(22),Gravity.CENTER);control.addView(glyph,p);}
        if(selected){View dot=new View(this);dot.setBackground(rounded(Glass.MINT,2));FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(g.u(4),g.u(4),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);p.bottomMargin=g.u(4);control.addView(dot,p);}
        control.setOnFocusChangeListener((v,focused)->{v.setBackground(focused?g.panel(24,true):null);v.setElevation(focused?g.u(8):0);if(text!=null)text.setTextColor(selected?Glass.MINT:focused?Glass.WHITE:Glass.MUTED);});
        return control;
    }
    private void showHome(String selected){
        if(!selected.equals(homeTab))invalidateBrowse();
        if(restoreListing(browsePages.get("home:"+selected)))return;
        newScreen();page=selected;homeTab=selected;detail=null;header(selected);rowCache.clear();
        heroArt=g.image("",900);heroArt.setAlpha(.65f);g.at(root,heroArt,1280,600,0,86);root.removeView(heroArt);root.addView(heroArt,0,new FrameLayout.LayoutParams(-1,g.u(660)));
        View scrim=new View(this);scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff0a1725,0xee0b1828,0x50091423}));root.addView(scrim,1,new FrameLayout.LayoutParams(-1,g.u(660)));
        View bottomFade=new View(this);bottomFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00101d2d,0xff0a1420}));FrameLayout.LayoutParams fade=new FrameLayout.LayoutParams(-1,g.u(400));fade.topMargin=g.u(310);root.addView(bottomFade,2,fade);
        TextView eyebrow=g.text("YOUR NEXT GREAT WATCH",11,Glass.MINT,true);eyebrow.setLetterSpacing(.14f);g.at(root,eyebrow,650,24,56,134);
        heroTitle=g.text("A world worth\ngetting lost in.",45,Glass.WHITE,true);heroTitle.setLetterSpacing(-.025f);heroTitle.setMaxLines(2);heroTitle.setEllipsize(TextUtils.TruncateAt.END);g.at(root,heroTitle,650,117,52,172);
        heroSubtitle=g.text(app.connected()?"Connected to "+app.accountName:"Explore cinema · Connect to make it yours",13,Glass.MINT,false);g.at(root,heroSubtitle,680,24,56,298);
        heroDescription=g.text("Your films, series and favourites, together in a different light.",14,Glass.MUTED,false);heroDescription.setMaxLines(2);heroDescription.setEllipsize(TextUtils.TruncateAt.END);g.at(root,heroDescription,650,48,56,331);
        ScrollView scroll=new ScrollView(this);scroll.setClipChildren(true);scroll.setClipToPadding(true);scroll.setVerticalScrollBarEnabled(false);scroll.setFillViewport(false);rows=g.column();rows.setPadding(g.u(50),g.u(12),g.u(50),g.u(35));scroll.addView(rows);g.at(root,scroll,1280,291,0,386);
        footer("↑ ↓  Browse rows     ← →  Explore     OK  Open title                                   "+(app.connected()?"STREMIO CONNECTED":"PREVIEW · Connect in Settings"));
        if(navFocus!=null)navFocus.requestFocus();
        final BrowsePage p=rememberListing("home:"+selected);
        if("Library".equals(selected)){
            if(!app.connected()){empty(rows,"Your library lives here","Scan to connect Stremio and bring your saved titles to Luma.","Connect Stremio",this::showPairing);return;}
            List<Title> saved=app.libraryTitles(false);if(saved.isEmpty())empty(rows,"Make room for your favourites","Save a title to your library and find it here.","Refresh library",()->refreshHome(selected));else addTitleRow(rows,"Your library",saved,null);
            browseTask(p,()->{app.refresh();return app.libraryTitles(false);},items->{p.rows.removeAllViews();if(items.isEmpty())empty(p.rows,"Your library is ready","Save a film or series from its title page.","Explore movies",()->showHome("Movies"));else addTitleRow(p.rows,"Your library",items,null,p);},e->toast("Showing saved library. "+StremioClient.readable(e)));return;
        }
        List<Title> continuing=app.libraryTitles(true);if("Home".equals(selected)&&!continuing.isEmpty())addTitleRow(rows,"Continue watching",continuing,null);
        TextView loading=g.text("Finding something good…",18,Glass.MUTED,false);rows.addView(loading);g.spacer(rows,15);
        if("Live".equals(selected)||app.addons.isEmpty())browseTask(p,()->{if("Live".equals(selected))app.refreshAddons();else app.refresh();return true;},v->{p.rows.removeView(loading);loadCatalogRows(selected,p);},e->{p.rows.removeView(loading);if(!app.addons.isEmpty()){loadCatalogRows(selected,p);if(root==p.root)toast("Using saved add-ons. Refresh in Settings to retry.");}else empty(p.rows,"Your cinema couldn’t load",StremioClient.readable(e),"Try again",()->refreshHome(selected));});
        else {p.rows.removeView(loading);loadCatalogRows(selected,p);}
    }
    private void refreshHome(String selected){task(()->{app.refresh();return true;},v->{invalidateBrowse();showHome(selected);},e->toast(StremioClient.readable(e)));}
    private void loadCatalogRows(String selected,BrowsePage p){
        List<Catalog> choices=new ArrayList<>();for(Catalog c:app.api.catalogs(app.addons))if(c.matches(selected))choices.add(c);
        if("Live".equals(selected))for(Addon a:app.addons){boolean live=false;JSONArray types=a.manifest.optJSONArray("types");if(types!=null)for(int i=0;i<types.length();i++)if(liveType(types.optString(i)))live=true;
            if(live&&objects(a.manifest.optJSONArray("catalogs")).isEmpty()){p.rows.addView(g.text(a.name+" · No catalogs enabled. Configure this provider in Stremio, then refresh.",14,Glass.MUTED,false));g.spacer(p.rows,18);}}
        if(choices.isEmpty()){empty(p.rows,"Live".equals(selected)?"Bring your channels along":"No catalogs here yet","Add a compatible catalog in Stremio, then refresh Luma.","Refresh add-ons",()->refreshHome(selected));return;}
        int count=0;for(Catalog c:choices){if(!"Live".equals(selected)&&count++>=12)break;LinearLayout section=g.column();p.rows.addView(section);TextView label=g.text(c.name+"  ·  "+c.addon.name,13,Glass.MUTED,false);section.addView(label);g.spacer(section,18);
            loadCatalogRow(selected,p,c,section);}
    }
    private void loadCatalogRow(String selected,BrowsePage p,Catalog c,LinearLayout section){
        browseTask(p,()->app.api.catalog(c,"",0),items->{section.removeAllViews();if(items.isEmpty()){section.addView(g.text(c.name+" · Nothing here yet",14,Glass.MUTED,false));g.spacer(section,20);}else{addTitleRow(section,c.name+("Home".equals(selected)?" · "+("series".equals(c.type)?"Series":"movie".equals(c.type)?"Movies":c.type):""),items,()->showCatalog(c),p);if(p.hero==null)updateHero(p,items.get(0));}},e->{section.removeAllViews();section.addView(g.button(c.name+" · Retry",false,()->loadCatalogRow(selected,p,c,section)));g.spacer(section,15);});
    }
    private void updateHero(BrowsePage p,Title title){p.hero=title;if(p.heroTitle==null)return;p.heroTitle.setText(title.name);p.heroSubtitle.setText(("series".equals(title.type)?"SERIES":"movie".equals(title.type)?"FILM":title.type.toUpperCase(Locale.ROOT))+(!title.year.isEmpty()?"   ·   "+title.year:"")+"   ·   YOUR STREMIO");p.heroDescription.setText(title.description.isEmpty()?"Press OK to explore this title, find a source and settle in.":title.description);String bg=title.background;if(bg.isEmpty()&&title.id.startsWith("tt"))bg="https://images.metahub.space/background/large/"+StremioClient.path(title.id)+"/img";Glass.load(p.heroArt,bg,g.u(1100));}
    private void addTitleRow(LinearLayout parent,String name,List<Title> titles,Runnable more){addTitleRow(parent,name,titles,more,listing);}
    private void addTitleRow(LinearLayout parent,String name,List<Title> titles,Runnable more,BrowsePage p){
        LinearLayout label=g.row();TextView heading=g.text(name,20,Glass.WHITE,true);label.addView(heading,new LinearLayout.LayoutParams(0,-2,1));if(more!=null){TextView all=g.button("View all  →",false,more);all.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,g.u(12));all.setMinHeight(0);all.setPadding(g.u(15),g.u(7),g.u(15),g.u(7));label.addView(all,new LinearLayout.LayoutParams(-2,g.u(32)));}parent.addView(label);g.spacer(parent,12);
        HorizontalScrollView scroller=new HorizontalScrollView(this);scroller.setHorizontalScrollBarEnabled(false);scroller.setClipToPadding(false);scroller.setClipChildren(false);LinearLayout cards=g.row();cards.setPadding(g.u(6),g.u(8),g.u(10),g.u(8));scroller.addView(cards);
        int height=0;for(Title title:titles.subList(0,Math.min(30,titles.size()))){View card=poster(title,()->{if(p!=null)updateHero(p,title);});cards.addView(card,new LinearLayout.LayoutParams(g.u(title.cardWidth()),g.u(title.cardHeight())));height=Math.max(height,title.cardHeight());g.gap(cards,17);}
        parent.addView(scroller,new LinearLayout.LayoutParams(-1,g.u(height+20)));g.spacer(parent,20);
    }
    private View poster(Title title,Runnable focused){
        return new PosterCard(g,title,focused,()->showDetails(title),card->{
            if(!title.background.isEmpty())return;
            app.io.execute(()->{try{Title meta=app.api.meta(app.addons,title);main.post(()->{if(!isDestroyed())card.setPreview(meta);});}catch(Exception ignored){}});
        });
    }
    private void empty(LinearLayout parent,String title,String message,String action,Runnable click){LinearLayout panel=g.column();g.pad(panel,22,20);g.material(panel,22);panel.addView(g.text(title,23,Glass.WHITE,true));g.spacer(panel,10);panel.addView(g.text(message,15,Glass.MUTED,false));g.spacer(panel,18);panel.addView(g.button(action,false,click),new LinearLayout.LayoutParams(-2,g.u(45)));parent.addView(panel,new LinearLayout.LayoutParams(-1,-2));}

    private void showCatalog(Catalog catalog){
        String key="catalog:"+catalog.addon.base+":"+catalog.type+":"+catalog.id;if(restoreListing(browsePages.get(key)))return;
        newScreen();page="Catalog";selectedCatalog=catalog;catalogOffset=0;brand();TextView back=g.button("←  Back",false,()->showHome(homeTab));g.at(root,back,130,46,1098,30);g.at(root,g.text(catalog.name,38,Glass.WHITE,true),1000,55,54,113);g.at(root,g.text(catalog.addon.name+"  ·  "+catalog.type,14,Glass.MUTED,false),1000,28,56,175);
        ScrollView scroller=new ScrollView(this);rows=g.column();g.pad(rows,50,12);scroller.addView(rows);g.at(root,scroller,1280,445,0,221);rememberListing(key);loadCatalogPage(catalog,0);back.requestFocus();footer("Choose a title with your remote.");
    }
    private void loadCatalogPage(Catalog c,int offset){
        final BrowsePage p=listing;if(p==null||!p.valid||p.loading)return;p.loading=true;
        TextView loading=g.text("Loading titles…",17,Glass.MUTED,false);p.rows.addView(loading);
        browseTask(p,()->app.api.catalog(c,"",offset),items->{p.loading=false;p.rows.removeView(loading);addGrid(p.rows,items);if(items.isEmpty())p.rows.addView(g.text("You’ve reached the end.",15,Glass.MUTED,false));else if(c.supportsExtra("skip")){TextView more=g.button("Load more",false,()->{});more.setOnClickListener(v->{p.rows.removeView(more);loadCatalogPage(c,offset+items.size());});p.rows.addView(more);}},e->{p.loading=false;p.rows.removeView(loading);TextView retry=g.button("Try loading again",false,()->{});retry.setOnClickListener(v->{p.rows.removeView(retry);loadCatalogPage(c,offset);});p.rows.addView(retry);});
    }
    private void addGrid(LinearLayout parent,List<Title> titles){
        LinearLayout line=null;int used=0;for(Title title:titles){int width=title.cardWidth()+17;if(line==null||used+width>1168){line=g.row();line.setGravity(Gravity.TOP);line.setPadding(g.u(6),g.u(8),g.u(6),g.u(16));parent.addView(line);used=0;}FrameLayout slot=new FrameLayout(this);slot.setClipChildren(false);slot.setClipToPadding(false);PosterCard card=(PosterCard)poster(title,null);card.useGridSlot(slot);slot.addView(card,new FrameLayout.LayoutParams(g.u(title.cardWidth()),g.u(title.cardHeight())));line.addView(slot,new LinearLayout.LayoutParams(g.u(title.cardWidth()),g.u(title.cardHeight())));g.gap(line,17);used+=width;}
    }

    private void showSearch(){
        if(restoreListing(browsePages.get("search")))return;newScreen();page="Search";brand();TextView back=g.button("←  Back",false,()->showHome(homeTab));g.at(root,back,130,46,1098,30);g.at(root,g.text("Find your next obsession.",36,Glass.WHITE,true),1000,55,54,110);
        EditText input=new EditText(this);input.setSingleLine();input.setTextColor(Glass.WHITE);input.setHintTextColor(Glass.MUTED);input.setHint("Search films and series");input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,g.u(20));input.setBackground(g.panel(26,false));g.pad(input,24,12);input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);g.at(root,input,950,58,56,185);
        TextView search=g.button("Search",true,()->performSearch(input.getText().toString()));g.at(root,search,145,56,1032,185);
        input.setOnEditorActionListener((v,action,event)->{if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH||event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(),0);performSearch(input.getText().toString());search.requestFocus();return true;}return false;});
        ScrollView scroller=new ScrollView(this);rows=g.column();g.pad(rows,50,16);scroller.addView(rows);g.at(root,scroller,1280,402,0,265);rows.addView(g.text("Search across your Stremio catalog add-ons.",16,Glass.MUTED,false));rememberListing("search");footer("Type with the on-screen keyboard or your phone’s TV remote.");search.requestFocus();
    }
    private void performSearch(String query){query=query.trim();if(query.isEmpty())return;final String q=query;lastQuery=q;final int serial=++searchSerial;final BrowsePage p=listing;p.rows.removeAllViews();p.rows.addView(g.text("Searching for “"+q+"”…",18,Glass.MUTED,false));browseTask(p,()->app.api.search(app.addons,q),items->{if(serial!=searchSerial)return;p.rows.removeAllViews();if(items.isEmpty())empty(p.rows,"No matches this time","Try another title or add a search catalog in Stremio.","Back to Home",()->showHome("Home"));else addGrid(p.rows,items);},e->{if(serial!=searchSerial)return;p.rows.removeAllViews();empty(p.rows,"Search couldn’t finish",StremioClient.readable(e),"Try again",()->performSearch(q));});}

    private void showDetails(Title title){
        detailOrigin=page;selectedSeason=-1;newScreen();page="Details";detail=title;renderDetails(title,false);task(()->app.api.meta(app.addons,title),meta->{detail=meta;renderDetails(meta,true);},e->toast("Showing available title information. "+StremioClient.readable(e)));
    }
    private void renderDetails(Title title,boolean loaded){
        root.removeAllViews();root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff122f3a,0xff171a32,0xff0a1420}));ImageView art=g.image(title.background,g.u(1200));g.at(root,art,1280,720,0,0);View shade=new View(this);shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff0a1420,0xf00a1420,0xa0101423,0x850b1120}));g.at(root,shade,1280,720,0,0);
        brand();TextView back=g.button("←  Back",false,this::backFromDetails);g.at(root,back,130,46,1098,30);
        String kind="series".equals(title.type)?"SERIES":"movie".equals(title.type)?"FILM":title.type.toUpperCase(Locale.ROOT);TextView eyebrow=g.text(kind+"   /   "+(title.year.isEmpty()?"ON YOUR STREMIO":title.year),12,Glass.MINT,true);eyebrow.setLetterSpacing(.12f);g.at(root,eyebrow,850,25,56,135);
        TextView name=g.text(title.name,47,Glass.WHITE,true);name.setLetterSpacing(-.025f);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);g.at(root,name,850,114,52,178);
        String rating=text(title.json,"imdbRating"),runtime=text(title.json,"runtime");JSONArray genres=title.json.optJSONArray("genres");String facts=(!rating.isEmpty()?"★ "+rating+"    ":"")+runtime;if(genres!=null)for(int i=0;i<Math.min(3,genres.length());i++)facts+="    "+genres.optString(i);g.at(root,g.text(facts,14,Glass.MINT,false),850,28,56,303);
        TextView description=g.text(title.description.isEmpty()?(loaded?"Discover available sources for this title.":"Loading title information…"):title.description,17,Glass.MUTED,false);description.setMaxLines(4);description.setEllipsize(TextUtils.TruncateAt.END);description.setLineSpacing(g.u(6),1);g.at(root,description,800,112,56,347);
        LinearLayout actions=g.row();List<Video> videos=title.videos();String initial=title.id;
        JSONObject item=app.libraryItem(title.id);JSONObject state=item==null?null:item.optJSONObject("state");String last=state==null?"":text(state,"video_id");if("series".equals(title.type)){
            if(!last.isEmpty())initial=last;else for(Video v:videos)if(v.season>0&&!v.isFuture()){initial=v.id;break;}
        }
        final String video=initial;detailVideo=video;long resume=app.resume(title.id,video);
        TextView play=g.button((resume>0?"▶  Resume":"▶  Watch now"),true,()->{
            if("series".equals(title.type)&&video.equals(title.id)){toast("Choose an episode below when the episode list has loaded.");return;}
            findStreams(title,video,null,false);
        });actions.addView(play,new LinearLayout.LayoutParams(-2,g.u(51)));g.gap(actions,14);
        TextView sources=g.button("Choose source",false,()->findStreams(title,video,null,true));actions.addView(sources,new LinearLayout.LayoutParams(-2,g.u(51)));g.gap(actions,14);
        if(app.connected()){TextView libraryButton=g.button(app.saved(title.id)?"✓  In library":"＋  Library",false,()->{});libraryButton.setOnClickListener(v->{libraryButton.setEnabled(false);app.toggleLibrary(title,saved->{libraryButton.setEnabled(true);libraryButton.setText(saved?"✓  In library":"＋  Library");toast(saved?"Saved to library":"Removed from library");});});actions.addView(libraryButton,new LinearLayout.LayoutParams(-2,g.u(51)));}
        g.at(root,actions,1160,57,56,479);
        if(!videos.isEmpty()){
            HorizontalScrollView episodes=new HorizontalScrollView(this);episodes.setHorizontalScrollBarEnabled(false);episodes.setClipToPadding(false);LinearLayout line=g.row();line.setPadding(g.u(6),g.u(8),g.u(10),g.u(10));episodes.addView(line);
            SortedSet<Integer> seasons=new TreeSet<>();for(Video v:videos)seasons.add(v.season);if(selectedSeason<0){selectedSeason=seasons.contains(1)?1:seasons.first();for(Video v:videos)if(v.id.equals(video))selectedSeason=v.season;}
            populateEpisodes(line,title,videos,selectedSeason);
            TextView seasonButton=g.button(selectedSeason==0?"Specials  ▾":"Season "+selectedSeason+"  ▾",false,()->{
                List<Integer> numbers=new ArrayList<>(seasons);String[] names=new String[numbers.size()];for(int i=0;i<names.length;i++)names[i]=numbers.get(i)==0?"Specials":"Season "+numbers.get(i);
                modal(GlassDialog.choices(g,"Choose a season",title.name,names,numbers.indexOf(selectedSeason),index->{selectedSeason=numbers.get(index);renderDetails(title,loaded);})).show();
            });seasonButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,g.u(13));seasonButton.setPadding(g.u(14),g.u(4),g.u(14),g.u(4));seasonButton.setMinHeight(0);g.at(root,seasonButton,145,35,56,550);g.at(root,episodes,1174,98,50,591);
        }else{
            String credits="";JSONArray cast=title.json.optJSONArray("cast");if(cast!=null){List<String> names=new ArrayList<>();for(int i=0;i<Math.min(5,cast.length());i++)names.add(cast.optString(i));credits="STARRING   "+String.join("  ·  ",names);}TextView castText=g.text(credits,13,Glass.MUTED,false);castText.setMaxLines(2);g.at(root,castText,1080,66,56,578);
        }
        play.requestFocus();
    }
    private void populateEpisodes(LinearLayout line,Title title,List<Video> videos,int season){for(Video v:videos){if(v.season!=season)continue;String label=(v.isFuture()?"UPCOMING  ·  ":"")+"E"+v.episode+"\n"+(v.name.isEmpty()?"Episode "+v.episode:v.name);TextView episode=g.button(label,false,()->{if(v.isFuture()){toast("This episode hasn’t aired yet.");return;}findStreams(title,v.id,v.json.optJSONArray("streams"),false);});episode.setMaxLines(2);episode.setEllipsize(TextUtils.TruncateAt.END);episode.setGravity(Gravity.CENTER_VERTICAL);episode.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,g.u(13));episode.setPadding(g.u(17),g.u(10),g.u(17),g.u(10));line.addView(episode,new LinearLayout.LayoutParams(g.u(208),g.u(75)));g.gap(line,12);}}
    private void backFromDetails(){backToBrowse();}
    private void findStreams(Title title,String videoId,JSONArray embedded,boolean forceList){
        if(!app.connected()&&app.guest){toast("Connect Stremio to use your streaming add-ons.");showPairing();return;}
        if(videoId.equals(title.id)&&"series".equals(title.type)){toast("Choose an episode first.");return;}
        if(embedded==null&&title.id.equals(videoId))embedded=title.json.optJSONArray("streams");final JSONArray included=embedded;
        final int token=++sourceRequest;
        sourcesDialog=dialog("Finding your sources","Checking your connected add-ons…");sourcesDialog.show();
        task(()->app.api.streams(app.addons,title,videoId,included),result->{if(token!=sourceRequest||sourcesDialog==null||!sourcesDialog.isShowing())return;sourcesDialog.dismiss();sourcesDialog=null;
            if(result.streams.isEmpty()){String explanation=result.torrents>0?"Your add-ons returned torrent sources. Luma plays direct video links; configure an add-on that provides playable HTTP streams in Stremio.":result.failedProviders>0?"Some add-ons didn’t respond. Check your add-on setup in Stremio and try again.":"No playable source was found. Add a streaming provider in Stremio, then refresh your add-ons in Luma Settings.";Dialog d=dialog("No playable sources",explanation);d.show();return;}
            if(!forceList&&!app.settings.getBoolean("chooseSources",false)){startPlayer(title,videoId,result.streams,0);return;}
            showSources(title,videoId,result.streams);
        },e->{if(token!=sourceRequest)return;if(sourcesDialog!=null){sourcesDialog.dismiss();sourcesDialog=null;}toast(StremioClient.readable(e));});
    }
    private GlassDialog modal(GlassDialog dialog){
        browseDialogs.add(dialog);dialog.setOnDismissListener(d->browseDialogs.remove(dialog));return dialog;
    }
    private Dialog dialog(String title,String message){return modal(GlassDialog.message(g,title,message));}
    private void showSources(Title title,String videoId,List<Stream> streams){
        List<GlassDialog.Choice> choices=new ArrayList<>();
        for(Stream stream:streams)choices.add(new GlassDialog.Choice(stream.name,stream.provider+" · "+stream.description));
        modal(GlassDialog.choices(g,"Choose your source",title.name+" · "+streams.size()+" playable sources",choices,-1,index->startPlayer(title,videoId,streams,index))).show();
    }
    private void startPlayer(Title title,String videoId,List<Stream> streams,int index){
        play(title,videoId,streams,index);
    }

    private void showSettings(){
        newScreen();page="Settings";brand();TextView back=g.button("←  Back",false,this::backToBrowse);g.at(root,back,130,46,1098,30);g.at(root,g.text("Make yourself at home.",36,Glass.WHITE,true),1100,60,54,115);
        LinearLayout account=g.column();g.pad(account,28,25);g.material(account,28);account.addView(g.text("YOUR STREMIO",11,Glass.MINT,true));g.spacer(account,20);account.addView(g.text(app.connected()?app.accountName:"A cinema with your name on it.",25,Glass.WHITE,true));g.spacer(account,12);account.addView(g.text(app.connected()?app.addons.size()+" connected add-ons · Library sync on":"Scan a code to bring your library and add-ons.",15,Glass.MUTED,false));g.spacer(account,25);
        TextView connect=g.button(app.connected()?"Refresh Stremio":"Connect Stremio",true,()->{if(!app.connected()){showPairing();return;}toast("Refreshing your library and add-ons…");task(()->{app.refresh();return true;},v->{toast("Stremio is up to date");invalidateBrowse();showSettings();},e->toast(StremioClient.readable(e)));});account.addView(connect,new LinearLayout.LayoutParams(-2,g.u(48)));g.spacer(account,14);
        if(app.connected())account.addView(g.button("Disconnect this TV",false,()->modal(GlassDialog.confirm(g,"Disconnect Stremio?","Your account stays on Stremio. The connection and saved account data will be removed from this TV.","Keep connected","Disconnect",()->{if(playerPanel!=null)stopPlayer();invalidateBrowse();app.disconnect();showPairing();})).show()),new LinearLayout.LayoutParams(-2,g.u(48)));
        g.at(root,account,510,356,56,207);
        LinearLayout prefs=g.column();g.pad(prefs,25,23);g.material(prefs,28);prefs.addView(g.text("PLAYBACK",11,Glass.MINT,true));g.spacer(prefs,18);
        toggle(prefs,"Always choose a source","chooseSources",false);toggle(prefs,"Play the next episode","autoplay",true);toggle(prefs,"Subtitles by default","subtitles",true);
        TextView language=g.button("Audio language: "+app.settings.getString("audioLanguage","en"),false,()->showAudioLanguages());prefs.addView(language,new LinearLayout.LayoutParams(-1,g.u(47)));g.spacer(prefs,11);
        prefs.addView(g.button("View connected add-ons",false,this::showAddons),new LinearLayout.LayoutParams(-1,g.u(47)));
        g.at(root,prefs,595,356,590,207);
        TextView note=g.text("Luma TV  0.5.0   ·   Glass, made for your big screen.",13,Glass.MUTED,false);g.at(root,note,1000,26,58,612);footer("Add or configure providers in Stremio on your phone, then refresh them here.");connect.requestFocus();
    }
    private void showAudioLanguages(){
        String[] codes={"en","fr","es","de","pt","hi","it"};
        modal(GlassDialog.choices(g,"Preferred audio language","Used when a source has multiple audio tracks.",new String[]{"English","French","Spanish","German","Portuguese","Hindi","Italian"},Arrays.asList(codes).indexOf(app.settings.getString("audioLanguage","en")),index->{app.settings.edit().putString("audioLanguage",codes[index]).apply();showSettings();})).show();
    }
    private void toggle(LinearLayout parent,String name,String key,boolean fallback){boolean enabled=app.settings.getBoolean(key,fallback);TextView button=g.button(name+"    "+(enabled?"On":"Off"),false,()->{app.settings.edit().putBoolean(key,!enabled).apply();showSettings();});button.setGravity(Gravity.CENTER_VERTICAL);button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,g.u(14));parent.addView(button,new LinearLayout.LayoutParams(-1,g.u(47)));g.spacer(parent,11);}
    private void showAddons(){newScreen();page="Addons";brand();TextView back=g.button("←  Settings",false,this::showSettings);g.at(root,back,165,46,1063,30);g.at(root,g.text("Your connected add-ons",36,Glass.WHITE,true),1100,56,54,115);g.at(root,g.text("Manage them in Stremio. Refresh here to bring your changes across.",15,Glass.MUTED,false),1100,30,56,176);ScrollView scroller=new ScrollView(this);LinearLayout list=g.column();g.pad(list,56,12);scroller.addView(list);g.at(root,scroller,1280,433,0,225);for(Addon a:app.addons){LinearLayout box=g.column();g.pad(box,22,18);g.material(box,20);box.addView(g.text(a.name,21,Glass.WHITE,true));g.spacer(box,8);box.addView(g.text(text(a.manifest,"description"),14,Glass.MUTED,false));list.addView(box);g.spacer(list,14);}back.requestFocus();}
    private void showExitConfirmation(){
        if(exitDialog!=null&&exitDialog.isShowing())return;
        exitDialog=ExitDialog.show(g,this::finish);
    }

    @Override public void onBackPressed(){if(playerPanel!=null&&!playerPanel.isMini()){playerPanel.onBackPressed();return;}if(playerPanel!=null&&playerPanel.view().hasFocus()){restorePlayer();return;}if(pairing){showExitConfirmation();}else if("Home".equals(page)){showExitConfirmation();}else if("Details".equals(page))backFromDetails();else if("Addons".equals(page))showSettings();else if("Settings".equals(page))backToBrowse();else showHome(homeTab.equals(page)?"Home":homeTab);}
}
