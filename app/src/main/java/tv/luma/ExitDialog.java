package tv.luma;
import android.app.Dialog;

/** Exit uses the shared modal surface, with Stay focused. */
public final class ExitDialog {
    private ExitDialog(){}
    public static Dialog show(Glass g,Runnable onExit){
        GlassDialog dialog=GlassDialog.confirm(g,"Leave Luma?","You can return to your library whenever you’re ready.","Stay","Exit Luma",onExit);
        dialog.show();return dialog;
    }
}
