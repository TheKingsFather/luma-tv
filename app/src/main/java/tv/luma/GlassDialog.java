package tv.luma;

import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.IntConsumer;

/** One modal surface for browse, player, settings and confirmation flows. */
public final class GlassDialog extends Dialog {
    public static final class Choice {
        final String title,description;
        public Choice(String title){this(title,"");}
        public Choice(String title,String description){this.title=title;this.description=description==null?"":description;}
    }
    private final Glass g;
    private final LinearLayout box,actions;
    private final List<View> rows=new ArrayList<>(),buttons=new ArrayList<>();
    private View initial;
    private ScrollView scroll;
    private TextView messageView;
    private EditText input;

    public GlassDialog(Glass g,String title,String message){
        super(g.activity);this.g=g;requestWindowFeature(Window.FEATURE_NO_TITLE);
        box=g.column();g.pad(box,30,26);box.setBackground(g.surface(28));box.setClipToOutline(true);
        LinearLayout brand=g.row();brand.addView(g.icon("logo",Glass.MINT,28));g.gap(brand,10);
        TextView wordmark=g.text("LUMA",11,Glass.MINT,true);wordmark.setLetterSpacing(.2f);brand.addView(wordmark);box.addView(brand);
        g.spacer(box,18);TextView heading=g.text(title,28,Glass.WHITE,true);heading.setMaxLines(2);heading.setEllipsize(TextUtils.TruncateAt.END);box.addView(heading);
        if(message!=null&&!message.isEmpty()){g.spacer(box,10);messageView=g.text(message,15,Glass.MUTED,false);messageView.setMaxLines(4);messageView.setEllipsize(TextUtils.TruncateAt.END);messageView.setLineSpacing(g.u(4),1);box.addView(messageView);}
        actions=g.row();actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        setContentView(box);setCanceledOnTouchOutside(false);
    }
    public static GlassDialog choices(Glass g,String title,String message,String[] labels,int selected,IntConsumer action){
        List<Choice> choices=new ArrayList<>();for(String label:labels)choices.add(new Choice(label));return choices(g,title,message,choices,selected,action);
    }
    public static GlassDialog choices(Glass g,String title,String message,List<Choice> choices,int selected,IntConsumer action){
        GlassDialog dialog=new GlassDialog(g,title,message);dialog.addChoices(choices,selected,action);dialog.action("Close",false,null,false);return dialog;
    }
    public static GlassDialog message(Glass g,String title,String message){return new GlassDialog(g,title,message).action("Close",false,null,true);}
    public static GlassDialog confirm(Glass g,String title,String message,String cancel,String accept,Runnable action){
        return new GlassDialog(g,title,message).action(cancel,true,null,true).action(accept,false,action,false);
    }
    public void messageText(String text){if(messageView!=null)messageView.setText(text);}
    public GlassDialog input(EditText field){
        input=field;field.setId(View.generateViewId());g.spacer(box,18);box.addView(field,new LinearLayout.LayoutParams(-1,g.u(54)));initial=field;
        field.setOnKeyListener((view,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_DOWN&&!buttons.isEmpty()){if(event.getAction()==KeyEvent.ACTION_DOWN)buttons.get(0).requestFocus();return true;}return false;});
        field.setOnEditorActionListener((view,action,event)->{if(action!=android.view.inputmethod.EditorInfo.IME_ACTION_DONE||buttons.isEmpty())return false;((android.view.inputmethod.InputMethodManager)g.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(field.getWindowToken(),0);buttons.get(buttons.size()-1).requestFocus();return true;});
        return this;
    }
    private void addChoices(List<Choice> choices,int selected,IntConsumer action){
        g.spacer(box,18);scroll=new ScrollView(g.activity);scroll.setVerticalScrollBarEnabled(false);scroll.setClipChildren(true);scroll.setClipToPadding(true);
        LinearLayout list=g.column();list.setPadding(g.u(5),g.u(5),g.u(5),g.u(5));scroll.addView(list);
        int height=10;
        for(int n=0;n<choices.size();n++){
            final int index=n;Choice choice=choices.get(n);boolean checked=n==selected,detail=!choice.description.isEmpty();
            FrameLayout row=new FrameLayout(g.activity);g.focus(row,18,null);row.setBackground(g.panel(18,checked));
            row.setOnFocusChangeListener((v,focused)->{v.setBackground(g.panel(18,focused||checked));v.setElevation(focused?g.u(4):0);});
            row.setSelected(checked);row.setContentDescription(choice.title+(checked?", selected":"")+(detail?", "+choice.description:""));
            TextView label=g.text(choice.title.replace('\n',' '),17,checked?Glass.MINT:Glass.WHITE,true);label.setSingleLine();label.setEllipsize(TextUtils.TruncateAt.END);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,g.u(25));lp.leftMargin=g.u(18);lp.rightMargin=g.u(50);lp.topMargin=g.u(detail?10:15);row.addView(label,lp);
            if(detail){TextView subtitle=g.text(choice.description.replace('\n',' '),13,Glass.MUTED,false);subtitle.setMaxLines(2);subtitle.setEllipsize(TextUtils.TruncateAt.END);FrameLayout.LayoutParams sub=new FrameLayout.LayoutParams(-1,g.u(36));sub.leftMargin=g.u(18);sub.rightMargin=g.u(50);sub.topMargin=g.u(37);row.addView(subtitle,sub);}
            if(checked){View mark=g.icon("check",Glass.MINT,22);FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(g.u(22),g.u(22),Gravity.RIGHT|Gravity.CENTER_VERTICAL);p.rightMargin=g.u(18);row.addView(mark,p);}
            row.setOnClickListener(v->{if(!isShowing())return;dismiss();action.accept(index);});
            int rowHeight=detail?82:56;list.addView(row,new LinearLayout.LayoutParams(-1,g.u(rowHeight)));rows.add(row);height+=rowHeight;
            if(n+1<choices.size()){g.spacer(list,8);height+=8;}
        }
        FrameLayout viewport=new FrameLayout(g.activity);viewport.setOutlineProvider(ViewOutlineProvider.BOUNDS);viewport.setClipToOutline(true);viewport.setClipChildren(true);
        viewport.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        box.addView(viewport,new LinearLayout.LayoutParams(-1,g.u(Math.min(height,300))));
        if(!rows.isEmpty())initial=rows.get(selected>=0&&selected<rows.size()?selected:0);
    }
    public GlassDialog action(String label,boolean primary,Runnable callback,boolean focus){
        if(actions.getParent()==null){g.spacer(box,22);box.addView(actions,new LinearLayout.LayoutParams(-1,g.u(50)));}
        if(!buttons.isEmpty())g.gap(actions,12);
        TextView button=g.button(label,primary,()->{if(!isShowing())return;dismiss();if(callback!=null)callback.run();});
        button.setMinHeight(0);button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,g.u(15));button.setPadding(g.u(22),g.u(10),g.u(22),g.u(10));
        actions.addView(button,new LinearLayout.LayoutParams(-2,g.u(50)));buttons.add(button);if(focus||initial==null)initial=button;return this;
    }
    @Override public void show(){
        if(g.activity.isFinishing()||g.activity.isDestroyed())return;
        super.show();Window window=getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));window.setLayout(g.u(740),-2);window.setGravity(Gravity.CENTER);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.72f);window.getDecorView().setSystemUiVisibility(g.activity.getWindow().getDecorView().getSystemUiVisibility());}
        for(int n=0;n<rows.size();n++){View row=rows.get(n);row.setNextFocusLeftId(row.getId());row.setNextFocusRightId(row.getId());row.setNextFocusUpId(rows.get(Math.max(0,n-1)).getId());row.setNextFocusDownId(n+1<rows.size()?rows.get(n+1).getId():buttons.get(0).getId());}
        for(int n=0;n<buttons.size();n++){View button=buttons.get(n);button.setNextFocusLeftId(buttons.get(Math.max(0,n-1)).getId());button.setNextFocusRightId(buttons.get(Math.min(buttons.size()-1,n+1)).getId());button.setNextFocusDownId(button.getId());button.setNextFocusUpId(rows.isEmpty()?(input==null?button.getId():input.getId()):rows.get(rows.size()-1).getId());}
        if(input!=null&&!buttons.isEmpty()){input.setNextFocusDownId(buttons.get(0).getId());input.setNextFocusUpId(input.getId());}
        if(initial!=null){initial.requestFocus();if(scroll!=null)scroll.post(()->{if(isShowing())scroll.scrollTo(0,Math.max(0,initial.getTop()-g.u(12)));});}
    }
}
