package tv.luma;

import android.app.Activity;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/** Shared glass material, ambient light and large-screen remote affordances. */
public final class Glass {
    public static final int WHITE=0xfff4f8ff,MUTED=0xffaab9ce,MINT=0xffc0f4e1,BG=0xff080e1b;
    private static final ExecutorService IMAGES=Executors.newFixedThreadPool(4);
    private static final ExecutorService BACKDROPS=Executors.newFixedThreadPool(2);
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final LruCache<String,Bitmap> CACHE=new LruCache<String,Bitmap>(24*1024*1024){@Override protected int sizeOf(String k,Bitmap b){return b.getByteCount();}};
    public final Activity activity;
    public final float scale;
    public Glass(Activity a){activity=a;scale=Math.min(a.getResources().getDisplayMetrics().widthPixels/1280f,a.getResources().getDisplayMetrics().heightPixels/720f);}
    public int u(float n){return Math.round(n*scale);}
    public TextView text(String value,float size,int color,boolean bold){
        TextView v=new TextView(activity);v.setText(value);v.setTextColor(color);v.setTextSize(TypedValue.COMPLEX_UNIT_PX,u(size));v.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));v.setFontFeatureSettings("kern");v.setIncludeFontPadding(false);v.setLineSpacing(u(4),1);return v;
    }
    public FrameLayout root(){FrameLayout root=new FrameLayout(activity);root.setBackground(new Ambient());root.setClipChildren(true);return root;}
    public LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);l.setClipChildren(false);l.setClipToPadding(false);return l;}
    public LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);l.setClipChildren(false);l.setClipToPadding(false);return l;}
    public void at(FrameLayout parent,View child,float w,float h,float x,float y){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(w<0?(int)w:u(w),h<0?(int)h:u(h));p.leftMargin=u(x);p.topMargin=u(y);parent.addView(child,p);}
    public void pad(View v,int horizontal,int vertical){v.setPadding(u(horizontal),u(vertical),u(horizontal),u(vertical));}
    public Drawable panel(int radius,boolean active){return new Material(u(radius),active,false);}
    public void material(View v,int radius){v.setBackground(panel(radius,false));v.setClipToOutline(true);}
    public TextView button(String label,boolean primary,Runnable action){
        TextView b=text(label,16,primary?BG:WHITE,true);b.setGravity(Gravity.CENTER);b.setMinHeight(u(50));b.setPadding(u(24),u(12),u(24),u(12));b.setBackground(new Material(u(25),false,primary));
        b.setFocusable(true);b.setClickable(true);b.setDefaultFocusHighlightEnabled(false);b.setId(View.generateViewId());b.setContentDescription(label);b.setOnClickListener(v->action.run());
        b.setOnFocusChangeListener((v,focused)->{v.setBackground(new Material(u(25),focused,primary));v.animate().scaleX(focused?1.04f:1).scaleY(focused?1.04f:1).setDuration(150).start();v.setElevation(focused?u(12):u(2));});return b;
    }
    public void focus(View v,int radius,Runnable changed){
        v.setId(View.generateViewId());v.setFocusable(true);v.setClickable(true);v.setDefaultFocusHighlightEnabled(false);v.setBackground(panel(radius,false));
        v.setOnFocusChangeListener((view,focused)->{view.setBackground(panel(radius,focused));view.animate().scaleX(focused?1.045f:1).scaleY(focused?1.045f:1).setDuration(160).start();view.setElevation(focused?u(12):0);if(focused&&changed!=null)changed.run();});
    }
    public void spacer(LinearLayout l,int h){Space s=new Space(activity);l.addView(s,new LinearLayout.LayoutParams(1,u(h)));}
    public void gap(LinearLayout l,int w){Space s=new Space(activity);l.addView(s,new LinearLayout.LayoutParams(u(w),1));}
    public ImageView image(String url,int width){ImageView image=new RemoteImage(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);load(image,url,width);return image;}
    public static void load(ImageView view,String url,int width){
        if(view instanceof RemoteImage){((RemoteImage)view).remote(url,width);return;}
        fetch(view,url,width);
    }
    private static void fetch(ImageView view,String url,int width){
        view.setTag(url);view.setImageDrawable(null);if(url==null||url.isEmpty())return;String key=url+"@"+width;Bitmap cached=CACHE.get(key);if(cached!=null){view.setImageBitmap(cached);view.setAlpha(1f);return;}
        (width>600?BACKDROPS:IMAGES).execute(()->{HttpURLConnection c=null;try{
            URL target=new URL(url);if(!target.getProtocol().equals("https")&&!target.getProtocol().equals("http"))return;
            c=(HttpURLConnection)target.openConnection();c.setConnectTimeout(8000);c.setReadTimeout(10000);c.setRequestProperty("User-Agent","LumaTV/0.3");
            byte[] bytes;try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>12*1024*1024)return;out.write(buffer,0,n);}bytes=out.toByteArray();}
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);int sample=1;while(options.outWidth/sample>Math.max(width,200)*2)sample*=2;
            options.inJustDecodeBounds=false;options.inSampleSize=sample;Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)return;CACHE.put(key,bitmap);
            MAIN.post(()->{if(url.equals(view.getTag())){view.setImageBitmap(bitmap);view.setAlpha(1f);}});
        }catch(Exception ignored){}finally{if(c!=null)c.disconnect();}});
    }
    /** Only visible cards enter the download queue; off-screen catalogs cannot starve the hero. */
    private static final class RemoteImage extends ImageView {
        String url="";int width;boolean requested;ViewTreeObserver.OnPreDrawListener watcher;
        final Rect visible=new Rect();
        RemoteImage(Context c){super(c);}
        void remote(String url,int width){this.url=url==null?"":url;this.width=width;requested=false;setTag(this.url);setImageDrawable(null);watch();}
        void watch(){if(!isAttachedToWindow()||watcher!=null||url.isEmpty()||requested)return;watcher=()->{if(!requested&&isShown()&&getWidth()>0&&getGlobalVisibleRect(visible)){requested=true;getViewTreeObserver().removeOnPreDrawListener(watcher);watcher=null;fetch(this,url,width);}return true;};getViewTreeObserver().addOnPreDrawListener(watcher);invalidate();}
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();watch();}
        @Override protected void onDetachedFromWindow(){if(watcher!=null&&getViewTreeObserver().isAlive())getViewTreeObserver().removeOnPreDrawListener(watcher);watcher=null;super.onDetachedFromWindow();}
    }
    public View icon(String shape,int color,int size){if("logo".equals(shape)){ImageView logo=new ImageView(activity);logo.setImageResource(tv.luma.R.drawable.icon);logo.setLayoutParams(new LinearLayout.LayoutParams(u(size),u(size)));return logo;}Icon v=new Icon(activity,shape,color);v.setLayoutParams(new LinearLayout.LayoutParams(u(size),u(size)));return v;}
    public static void fullscreen(Activity a){a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
    private static final class Ambient extends Drawable {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas c){Rect b=getBounds();float w=b.width(),h=b.height();c.drawColor(BG);
            glow(c,w*.07f,h*.48f,w*.65f,0xff173d4d);glow(c,w*.86f,h*.3f,w*.55f,0xff332b58);glow(c,w*.67f,h*1.08f,w*.55f,0xff103c3f);
            p.setShader(new LinearGradient(0,0,w,h,new int[]{0x061fffff,0x001fffff,0x0921ffbe},null,Shader.TileMode.CLAMP));c.drawRect(b,p);p.setShader(null);
        }
        void glow(Canvas c,float x,float y,float radius,int color){p.setShader(new RadialGradient(x,y,radius,new int[]{color,Color.TRANSPARENT},null,Shader.TileMode.CLAMP));c.drawRect(getBounds(),p);}
        @Override public void setAlpha(int a){}@Override public void setColorFilter(ColorFilter f){}@Override public int getOpacity(){return PixelFormat.OPAQUE;}
    }
    private static final class Material extends Drawable {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final float radius;final boolean focus,primary;
        Material(float radius,boolean focus,boolean primary){this.radius=radius;this.focus=focus;this.primary=primary;}
        @Override public void draw(Canvas c){RectF r=new RectF(getBounds());r.inset(1.5f,1.5f);
            p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(r.left,r.top,r.right,r.bottom,primary?new int[]{0xffd6ffef,0xffb2e4d7}:focus?new int[]{0xb052737d,0xbd364559,0xba293849}:new int[]{0x75435168,0x78263549,0x851b2b3b},null,Shader.TileMode.CLAMP));c.drawRoundRect(r,radius,radius,p);p.setShader(null);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(focus?2.7f:1.3f);p.setShader(new LinearGradient(r.left,r.top,r.right,r.bottom,new int[]{focus?0xffd6fff0:0x60f0ffff,focus?0xa0d3fff4:0x12ffffff,focus?0xffa6cfcf:0x30ffffff},null,Shader.TileMode.CLAMP));c.drawRoundRect(r,radius,radius,p);p.setShader(null);p.setStyle(Paint.Style.FILL);
        }
        @Override public void getOutline(Outline outline){outline.setRoundRect(getBounds(),radius);}
        @Override public void setAlpha(int a){}@Override public void setColorFilter(ColorFilter f){}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    public static final class Icon extends View {
        final String shape;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final int color;
        Icon(Context c,String shape,int color){super(c);this.shape=shape;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){float s=Math.min(getWidth(),getHeight());c.save();c.translate((getWidth()-s)/2,(getHeight()-s)/2);c.scale(s/24,s/24);p.setColor(color);p.setStrokeWidth(1.6f);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            Path path=new Path();switch(shape){
                case "search":c.drawCircle(10,10,6,p);c.drawLine(14.5f,14.5f,21,21,p);break;
                case "play":p.setStyle(Paint.Style.FILL);path.moveTo(7,4);path.lineTo(21,12);path.lineTo(7,20);path.close();c.drawPath(path,p);break;
                case "logo":p.setStrokeWidth(3);path.moveTo(6,4);path.lineTo(6,20);path.lineTo(20,20);c.drawPath(path,p);c.drawLine(12,4,21,13,p);break;
                case "settings":c.drawCircle(12,12,4,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;c.drawLine(12+(float)Math.cos(a)*8,12+(float)Math.sin(a)*8,12+(float)Math.cos(a)*10,12+(float)Math.sin(a)*10,p);}c.drawCircle(12,12,8,p);break;
                case "check":path.moveTo(4,12);path.lineTo(9,17);path.lineTo(20,6);c.drawPath(path,p);break;
                default:c.drawCircle(12,12,9,p);c.drawLine(12,7,12,13,p);c.drawPoint(12,17,p);
            }c.restore();}
    }
}
