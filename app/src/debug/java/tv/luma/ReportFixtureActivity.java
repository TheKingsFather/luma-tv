package tv.luma;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.io.*;
import java.util.Collections;
import org.json.JSONObject;

/** Debug-only, shell-protected end-to-end fixture. No account or external stream. */
public final class ReportFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LumaApp app=(LumaApp)getApplication();
        if(!getPackageName().endsWith(".debug")||app.connected()){finish();return;}
        try{
            File clip=new File(getCacheDir(),"owner-report-fixture.mp4");
            try(InputStream in=getAssets().open("private-reports-fixture.mp4");OutputStream out=new FileOutputStream(clip)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            Models.Title title=new Models.Title(new JSONObject().put("id","owner-report-fixture").put("name","Private reporting test (generated video)").put("type","tv"));
            Models.Stream stream=new Models.Stream(new JSONObject().put("url",clip.toURI().toString()),"Generated fixture");
            app.playback=new PlayerActivity.Request(title,title.id,Collections.singletonList(stream),0);
            startActivity(new Intent(this,MainActivity.class).putExtra("playPending",true));
        }catch(Exception e){/* Fixture never logs report data. */}finally{finish();}
    }
}
