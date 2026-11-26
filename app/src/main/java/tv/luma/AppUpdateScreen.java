package tv.luma;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.text.InputType;
import android.widget.EditText;
import java.io.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Remote-friendly release check, verified download and Android install handoff. */
final class AppUpdateScreen {
    static final int PERMISSION=7201,INSTALL=7202;
    interface Installer {
        boolean allowed();
        void permission();
        void install(File file);
    }
    private final MainActivity owner;private final LumaApp app;private final Glass g;private final AppUpdater client;
    private final Handler main=new Handler(Looper.getMainLooper());
    private Future<?> work;private int generation;private boolean closed,permissionPending,installerOpen;
    private AppUpdater.Cancellation cancellation;
    private File staged;private AppUpdater.Release release;
    GlassDialog dialog;Installer installer;
    AppUpdateScreen(MainActivity owner,AppUpdater client){
        this.owner=owner;this.client=client;app=(LumaApp)owner.getApplication();g=owner.glass();
        installer=new Installer(){
            public boolean allowed(){return owner.getPackageManager().canRequestPackageInstalls();}
            public void permission(){owner.startActivityForResult(UpdatePackage.permissionIntent(owner),PERMISSION);}
            public void install(File file){owner.startActivityForResult(UpdatePackage.installIntent(owner,file),INSTALL);}
        };
    }
    private boolean active(int id){return !closed&&!owner.isFinishing()&&!owner.isDestroyed()&&id==generation&&dialog!=null&&dialog.isShowing();}
    private void stop(){generation++;if(cancellation!=null){cancellation.cancel();cancellation=null;}if(work!=null){work.cancel(true);work=null;}}
    private void show(GlassDialog next){
        if(closed||owner.isFinishing()||owner.isDestroyed())return;
        if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();}
        dialog=next;next.setOnDismissListener(d->{if(dialog==next){dialog=null;stop();}});next.show();
    }
    private int busy(String message){stop();cancellation=new AppUpdater.Cancellation();int id=generation;show(new GlassDialog(g,"App updates",message).action("Cancel",false,null,true));return id;}
    private void discard(){if(staged!=null){staged.delete();staged=null;}release=null;}
    private void error(Exception error){
        if(error instanceof AppUpdater.AuthenticationRequired){client.token="";tokenPrompt();return;}
        String message=error instanceof AppUpdater.UpdateException?error.getMessage():"The update could not finish. Check your connection and available space, then try again.";
        show(GlassDialog.message(g,"App updates",message));
    }
    void check(){
        discard();int id=busy("Checking GitHub for a new version…");
        AppUpdater.Cancellation cancel=cancellation;
        work=app.io.submit(()->{try{AppUpdater.Release update=client.check(UpdatePackage.currentVersion(owner),cancel);main.post(()->{if(!active(id))return;if(update==null)show(GlassDialog.message(g,"App updates","You’re up to date. Installed version: "+UpdatePackage.currentVersion(owner)+"."));else available(update);});}catch(Exception e){main.post(()->{if(active(id))error(e);});}});
    }
    private void tokenPrompt(){
        EditText token=new EditText(owner);token.setSingleLine(true);token.setTextColor(Glass.WHITE);token.setHintTextColor(Glass.MUTED);token.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,g.u(16));g.pad(token,16,10);g.material(token,14);
        token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);token.setHint("GitHub token");token.setContentDescription("GitHub token, Contents Read for luma-tv");
        token.setTag("luma.update.token");token.setSaveEnabled(false);token.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        token.setDefaultFocusHighlightEnabled(false);token.setOnFocusChangeListener((view,focused)->view.setBackground(g.panel(14,focused)));
        token.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        GlassDialog next=new GlassDialog(g,"Release access","Your GitHub repository is private. Enter a token with Contents: Read access to TheKingsFather/luma-tv. It is used only for this app session.").input(token)
            .action("Cancel",false,null,false).action("Check with token",true,()->{String value=token.getText().toString().trim();if(value.isEmpty()){tokenPrompt();return;}client.token=value;check();},false);
        show(next);
    }
    private void available(AppUpdater.Release update){
        show(new GlassDialog(g,"Update available","Luma TV "+update.version+" is available. Installed: "+UpdatePackage.currentVersion(owner)+". Download size: "+String.format(java.util.Locale.ROOT,"%.1f MB",update.size/1048576.0)+".")
            .action("Later",false,null,true).action("Download",true,()->download(update),false));
    }
    private void download(AppUpdater.Release update){
        discard();int id=busy("Downloading Luma TV "+update.version+"… 0%");
        AppUpdater.Cancellation cancel=cancellation;
        work=app.io.submit(()->{File file=null;try{
            file=client.download(update,new File(owner.getCacheDir(),"updates"),cancel,percent->main.post(()->{if(active(id))dialog.messageText("Downloading Luma TV "+update.version+"… "+percent+"%");}));
            UpdatePackage.validate(owner,file,update);File ready=file;file=null;
            main.post(()->{if(!active(id)){ready.delete();return;}staged=ready;release=update;ready("");});
        }catch(Exception e){if(file!=null)file.delete();main.post(()->{if(active(id))error(e);});}});
    }
    private void ready(String prefix){
        if(staged==null||release==null)return;
        show(new GlassDialog(g,"Ready to install",prefix+"Luma TV "+release.version+" has been verified. Android will ask you to confirm installation. Your account and preferences are kept.")
            .action("Later",false,this::discard,true).action("Install update",true,this::install,false));
    }
    private void install(){
        if(staged==null)return;
        if(!installer.allowed()){
            show(new GlassDialog(g,"Allow app updates","Android needs permission for Luma TV to install updates. Choose Allow from this source, then return to Luma to continue.")
                .action("Later",false,null,true).action("Open settings",true,()->{try{permissionPending=true;installer.permission();}catch(ActivityNotFoundException|SecurityException e){permissionPending=false;show(GlassDialog.message(g,"App updates","Android’s update permission settings could not be opened."));}},false));return;
        }
        try{installerOpen=true;installer.install(staged);}catch(ActivityNotFoundException|SecurityException|IllegalArgumentException e){installerOpen=false;show(GlassDialog.message(g,"App updates","Android’s package installer could not be opened. Please try again."));}
    }
    void onResult(int request,int result){
        if(closed)return;
        if(request==PERMISSION&&permissionPending){permissionPending=false;if(installer.allowed())install();else ready("Update permission was not enabled. ");}
        else if(request==INSTALL){installerOpen=false;if(result==Activity.RESULT_OK){discard();show(GlassDialog.message(g,"App updates","The update was installed. Reopen Luma TV to continue."));}else ready("Installation was not completed. ");}
    }
    void close(){closed=true;stop();if(dialog!=null){dialog.setOnDismissListener(null);dialog.dismiss();dialog=null;}if(!installerOpen)discard();}
}
