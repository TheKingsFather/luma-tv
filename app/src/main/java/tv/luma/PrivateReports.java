package tv.luma;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.*;

/** Separate from Stremio sync: no account, stream, provider, or subtitle data. */
public final class PrivateReports {
    public static final int PORT=37821;
    private final SharedPreferences settings;
    private final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32));
    private ReportStore store;
    private ReportServer server;
    private volatile boolean failure;
    private volatile long epoch;
    public PrivateReports(Context context){
        settings=context.getSharedPreferences("private-reports",Context.MODE_PRIVATE);
        try{store=new ReportStore(context.getNoBackupFilesDir());
            server=new ReportServer(context.getPackageName().endsWith(".debug")?PORT+1:PORT,store::owner,()->{
                JSONArray rows=new JSONArray();for(byte[] bytes:store.snapshots())rows.put(Base64.getEncoder().encodeToString(bytes));
                return new JSONObject().put("version",1).put("records",rows).toString().getBytes(StandardCharsets.UTF_8);
            });
        }catch(Exception e){failure=true;}
    }
    public boolean enabled(){return store!=null&&store.owner()!=null&&settings.getBoolean("enabled",false);}
    public long epoch(){return epoch;}
    public PublicKey owner(){return store==null?null:store.owner();}
    public String fingerprint(){try{return owner()==null?"":ReportCrypto.fingerprint(owner());}catch(Exception e){return "";}}
    public String status(){return failure?"Private reports need attention":owner()==null?"Private reports: not paired":enabled()?"Private reports: on · owner access only":"Private reports: paused";}
    public synchronized void pair(byte[] encoded)throws Exception{
        if(store==null||server==null)throw new IllegalStateException("Private reporting is unavailable");
        store.pair(encoded);if(!settings.edit().putBoolean("enabled",true).commit())throw new IllegalStateException("Could not save report preference");epoch++;failure=false;
    }
    public synchronized void setEnabled(boolean enabled){if(settings.getBoolean("enabled",false)!=enabled){settings.edit().putBoolean("enabled",enabled).commit();epoch++;}}
    public synchronized void revoke()throws Exception{setEnabled(false);if(store!=null)store.revoke();}
    public synchronized void checkpoint(String session,Models.Title title,String video,long started,long watched,boolean finished,boolean ended){
        if(!enabled()||watched<=0)return;
        try{
            JSONObject row=new JSONObject().put("version",1).put("session",session).put("title",safeTitle(title.name)).put("type",safeTitle(title.type))
                .put("started_at_ms",started).put("updated_at_ms",System.currentTimeMillis()).put("watched_ms",watched)
                .put("finished",finished).put("ended",ended);
            for(Models.Video item:title.videos())if(item.id.equals(video)){row.put("season",item.season).put("episode",item.episode);break;}
            byte[] plaintext=row.toString().getBytes(StandardCharsets.UTF_8);PublicKey expected=store.owner();long expectedEpoch=epoch;
            writer.execute(()->{synchronized(PrivateReports.this){try{
                // A queued snapshot must never cross revocation or owner changes.
                if(enabled()&&epoch==expectedEpoch&&store.owner()==expected){store.save(session,plaintext);failure=false;}
            }catch(Exception e){failure=true;}finally{Arrays.fill(plaintext,(byte)0);}}});
        }catch(Exception e){failure=true;}
    }
    private static String safeTitle(String value){
        if(value==null)return "";
        String cleaned=value.replaceAll("[\\p{Cntrl}]"," ").replaceAll("https?://\\S+","[address omitted]");
        return cleaned.substring(0,Math.min(256,cleaned.length()));
    }
}
