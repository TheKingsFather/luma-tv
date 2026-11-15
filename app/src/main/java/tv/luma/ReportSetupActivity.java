package tv.luma;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;
import java.util.Base64;
import java.security.PublicKey;

/** Shell-only pairing entry. Ordinary apps cannot nominate a new report owner. */
public final class ReportSetupActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);Glass g=new Glass(this);setContentView(g.root());Glass.fullscreen(this);
        PrivateReports reports=((LumaApp)getApplication()).reports;
        String encoded=getIntent().getStringExtra("owner_public_key");
        if(encoded!=null){
            try{
                if(encoded.length()>2048)throw new IllegalArgumentException();
                byte[] bytes=Base64.getDecoder().decode(encoded);PublicKey key=ReportCrypto.publicKey(bytes);
                String fingerprint=ReportCrypto.fingerprint(key);
                if(reports.owner()!=null&&!reports.fingerprint().equals(fingerprint))throw new SecurityException();
                String readableFingerprint=fingerprint.substring(0,32)+"\n"+fingerprint.substring(32);
                GlassDialog dialog=GlassDialog.confirm(g,"Pair your private reports","Records titles and viewing time for this computer only. Compare:\n"+readableFingerprint+"\nStream links and account tokens are excluded.","Cancel","Pair this owner",()->{
                    try{reports.pair(bytes);finish();}catch(Exception e){g.notice("Private reporting could not be paired");}
                });dialog.setOnDismissListener(d->finish());dialog.show();
            }catch(Exception e){g.notice("Invalid owner key, or another owner is already paired");finish();}
            return;
        }
        LinearLayout body=g.column();g.pad(body,48,38);g.material(body,24);body.addView(g.text("Private viewing reports",28,Glass.WHITE,true));g.spacer(body,20);
        body.addView(g.text(reports.status()+"\n\nReports can be read only on your paired computer.\nKeep that computer's private key safe.\n\nTV retention: 90 days, up to 2,000 sessions.\nExisting Stremio library sync is separate.",17,Glass.MUTED,false));g.spacer(body,22);
        if(reports.owner()!=null){
            body.addView(g.button(reports.enabled()?"Pause reporting":"Resume reporting",false,()->{reports.setEnabled(!reports.enabled());finish();}));g.spacer(body,12);
            body.addView(g.button("Revoke owner and delete TV reports",false,()->GlassDialog.confirm(g,"Delete private reports?","This stops reporting, revokes access and deletes the TV's stored reports. Reports already collected on your computer remain there.","Keep reports","Delete and revoke",()->{try{reports.revoke();finish();}catch(Exception e){g.notice("Could not delete all private reports");}}).show()));
        }else body.addView(g.text("Run the owner setup tool on your computer to pair.",17,Glass.MINT,false));
        g.spacer(body,12);TextView close=g.button("Back",true,this::finish);body.addView(close);android.widget.FrameLayout root=(android.widget.FrameLayout)findViewById(android.R.id.content);android.widget.FrameLayout.LayoutParams layout=new android.widget.FrameLayout.LayoutParams(g.u(1120),-2,android.view.Gravity.CENTER);root.addView(body,layout);close.requestFocus();
    }
}
