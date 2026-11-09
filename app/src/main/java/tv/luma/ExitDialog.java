package tv.luma;
import android.app.Dialog;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Branded exit prompt shared by the browser and isolated device checks. */
public final class ExitDialog {
    private ExitDialog(){}
    public static Dialog show(Glass g,Runnable onExit){
        Dialog dialog=new Dialog(g.activity);
        LinearLayout box=g.column();g.pad(box,34,30);
        GradientDrawable base=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff203c4b,0xff192239});base.setCornerRadius(g.u(28));
        box.setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{base,g.panel(28,false)}));box.setClipToOutline(true);
        LinearLayout brand=g.row();brand.addView(g.icon("logo",Glass.MINT,42));g.gap(brand,14);
        TextView wordmark=g.text("LUMA",13,Glass.MINT,true);wordmark.setLetterSpacing(.2f);brand.addView(wordmark);box.addView(brand);
        g.spacer(box,24);box.addView(g.text("Leave Luma?",30,Glass.WHITE,true));g.spacer(box,12);
        TextView message=g.text("You can return to your library whenever you’re ready.",16,Glass.MUTED,false);message.setLineSpacing(g.u(5),1);box.addView(message);g.spacer(box,30);
        LinearLayout actions=g.row();actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        TextView leave=g.button("Exit Luma",false,()->{dialog.dismiss();onExit.run();});
        TextView stay=g.button("Stay",true,dialog::dismiss);
        actions.addView(leave,new LinearLayout.LayoutParams(g.u(160),g.u(50)));g.gap(actions,14);actions.addView(stay,new LinearLayout.LayoutParams(g.u(160),g.u(50)));box.addView(actions);
        leave.setNextFocusRightId(stay.getId());stay.setNextFocusLeftId(leave.getId());stay.setNextFocusRightId(stay.getId());leave.setNextFocusLeftId(leave.getId());
        dialog.setContentView(box);dialog.setCanceledOnTouchOutside(false);

        dialog.setOnShowListener(d->{Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setLayout(g.u(650),-2);window.setGravity(Gravity.CENTER);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.72f);window.getDecorView().setSystemUiVisibility(g.activity.getWindow().getDecorView().getSystemUiVisibility());}stay.requestFocus();});
        dialog.show();return dialog;
    }
}
