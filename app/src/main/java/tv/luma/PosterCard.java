package tv.luma;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import java.util.function.Consumer;
import static tv.luma.Models.*;

/** D-pad previews keep the poster height and expand only the selected card. */
public final class PosterCard extends LinearLayout {
    private final Glass g;
    private final Title title;
    private final ImageView backdrop,logo;
    private final FrameLayout overlay;
    private final TextView heading,metadata;
    private final Consumer<PosterCard> preview;
    private final Runnable reveal=this::expand;
    private ValueAnimator animator;
    private FrameLayout slot;
    private boolean requested,expanded;
    private float progress;

    public PosterCard(Glass glass,Title item,Runnable focused,Runnable click,Consumer<PosterCard> preview){
        super(glass.activity);g=glass;title=item;this.preview=preview;
        setOrientation(VERTICAL);setPadding(g.u(3),g.u(3),g.u(3),g.u(3));
        setFocusable(true);setClickable(true);setDefaultFocusHighlightEnabled(false);
        setId(View.generateViewId());setContentDescription(title.name);setTooltipText(title.name);
        setClipToOutline(true);setBackground(g.panel(16,false));
        FrameLayout art=new FrameLayout(getContext());g.material(art,13);
        TextView placeholder=g.text(title.name,15,Glass.MUTED,true);placeholder.setGravity(Gravity.CENTER);
        art.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        ImageView poster=g.image(title.poster,g.u(title.cardWidth()*2));poster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.addView(poster,new FrameLayout.LayoutParams(-1,-1));
        backdrop=g.image("",g.u(650));backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);backdrop.setVisibility(INVISIBLE);
        art.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));
        overlay=new FrameLayout(getContext());overlay.setVisibility(INVISIBLE);
        overlay.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00080e1b,0x20080e1b,0xf5080e1b}));
        heading=g.text(title.name,18,Glass.WHITE,true);heading.setMaxLines(1);heading.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams h=new FrameLayout.LayoutParams(-1,g.u(38),Gravity.BOTTOM);h.setMargins(g.u(12),0,g.u(12),g.u(29));overlay.addView(heading,h);
        logo=new ImageView(getContext()){@Override public void setImageBitmap(Bitmap bitmap){super.setImageBitmap(bitmap);heading.setVisibility(bitmap==null?VISIBLE:INVISIBLE);}};
        logo.setScaleType(ImageView.ScaleType.FIT_START);overlay.addView(logo,new FrameLayout.LayoutParams(h));
        metadata=g.text("",11,Glass.MINT,false);metadata.setSingleLine(true);metadata.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams m=new FrameLayout.LayoutParams(-1,g.u(20),Gravity.BOTTOM);m.setMargins(g.u(12),0,g.u(12),g.u(9));overlay.addView(metadata,m);
        art.addView(overlay,new FrameLayout.LayoutParams(-1,-1));addView(art,new LinearLayout.LayoutParams(-1,g.u(title.artworkHeight())));
        TextView caption=g.text(title.name,12,Glass.WHITE,true);caption.setMaxLines(2);caption.setEllipsize(TextUtils.TruncateAt.END);
        caption.setPadding(g.u(7),g.u(7),g.u(5),0);addView(caption,new LinearLayout.LayoutParams(-1,-1));
        setOnClickListener(v->click.run());
        setOnFocusChangeListener((v,hasFocus)->{
            updateMaterial(hasFocus);removeCallbacks(reveal);
            if(hasFocus){if(focused!=null)focused.run();if(!"landscape".equals(title.posterShape()))postDelayed(reveal,350);}
            else animatePreview(false);
        });
        updateMetadata(title);
    }

    public void useGridSlot(FrameLayout value){slot=value;}
    public boolean isExpanded(){return expanded;}
    public int collapsedWidth(){return g.u(title.cardWidth());}
    public void setPreview(Title meta){
        String image=text(meta.json,"landscapePoster");if(image.isEmpty())image=meta.background;
        if(!image.isEmpty())Glass.load(backdrop,image,g.u(650));
        String brand=text(meta.json,"logo");if(!brand.isEmpty())Glass.load(logo,brand,g.u(400));
        updateMetadata(meta);
    }
    private void updateMetadata(Title meta){
        String rating=text(meta.json,"imdbRating");metadata.setText((meta.year.isEmpty()?"":meta.year+"  ·  ")+meta.type+(rating.isEmpty()?"":"  ·  "+rating));
    }
    private void updateMaterial(boolean focus){
        GradientDrawable base=new GradientDrawable();base.setColor(0xff183241);base.setCornerRadius(g.u(16));
        setBackground(progress>0?new LayerDrawable(new android.graphics.drawable.Drawable[]{base,g.panel(16,focus)}):g.panel(16,focus));
        setElevation(focus?g.u(8):0);
    }
    private void expand(){
        if(!hasFocus()||!isShown())return;
        if(!requested){requested=true;setPreview(title);if(preview!=null)preview.accept(this);}
        animatePreview(true);
    }
    private void animatePreview(boolean value){
        expanded=value;if(animator!=null)animator.cancel();
        animator=ValueAnimator.ofFloat(progress,value?1:0);animator.setDuration(220);animator.setInterpolator(new DecelerateInterpolator());
        updateMaterial(hasFocus());
        animator.addUpdateListener(a->{boolean was=progress>0;progress=(float)a.getAnimatedValue();applyWidth();if(was!=(progress>0))updateMaterial(hasFocus());});animator.start();
    }
    private void applyWidth(){
        ViewGroup.LayoutParams params=getLayoutParams();if(params==null)return;
        int target=g.u(title.artworkHeight()*16f/9f+6);
        int width=Math.round(collapsedWidth()+(target-collapsedWidth())*progress);params.width=width;setLayoutParams(params);
        backdrop.setVisibility(progress>0?VISIBLE:INVISIBLE);overlay.setVisibility(progress>0?VISIBLE:INVISIBLE);overlay.setAlpha(progress);
        if(slot!=null){
            View boundary=(View)slot.getParent();
            setTranslationX(boundary==null?0:Math.min(0,boundary.getWidth()-slot.getLeft()-width));
            slot.setTranslationZ(progress>0?g.u(18):0);
        }else if(expanded)post(()->{if(hasFocus()&&isAttachedToWindow())requestRectangleOnScreen(new Rect(0,0,getWidth(),getHeight()),false);});
    }
    private void reset(){removeCallbacks(reveal);if(animator!=null)animator.cancel();expanded=false;progress=0;applyWidth();updateMaterial(false);}
    @Override protected void onDetachedFromWindow(){reset();super.onDetachedFromWindow();}
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE&&backdrop!=null)reset();}
}
