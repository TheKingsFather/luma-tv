package tv.luma;

import android.app.Application;
import android.content.SharedPreferences;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import static tv.luma.Models.*;

public final class LumaApp extends Application {
    public final StremioClient api=new StremioClient();
    public final ExecutorService io=Executors.newFixedThreadPool(6);
    private final ExecutorService writes=Executors.newSingleThreadExecutor();
    private final Object libraryLock=new Object();
    private final Object flushLock=new Object();
    private final LinkedHashMap<String,JSONObject> library=new LinkedHashMap<>();
    private final LinkedHashMap<String,JSONObject> pending=new LinkedHashMap<>();
    public volatile List<Addon> addons=Collections.emptyList();
    public volatile String authKey="",accountName="";
    public volatile boolean guest=false,sessionUnreadable=false;
    public SharedPreferences settings;
    public volatile PlayerActivity.Request playback;
    private SessionStore vault;
    @Override public void onCreate(){super.onCreate();vault=new SessionStore(this);settings=getSharedPreferences("preferences",MODE_PRIVATE);
        try{
            String raw=vault.get("session");if(!raw.isEmpty()){JSONObject j=new JSONObject(raw);authKey=text(j,"key");accountName=text(j,"name");}
            String descriptors=vault.get("addons");List<Addon> list=new ArrayList<>();if(!descriptors.isEmpty())for(JSONObject j:objects(new JSONArray(descriptors)))list.add(new Addon(j));addons=list;
            String stored=vault.get("library");if(!stored.isEmpty())for(JSONObject j:objects(new JSONArray(stored)))library.put(text(j,"_id"),j);
            String queue=vault.get("pending");if(!queue.isEmpty())for(JSONObject j:objects(new JSONArray(queue)))pending.put(text(j,"_id"),j);
        }catch(Exception e){sessionUnreadable=true;authKey="";addons=Collections.emptyList();}
    }
    public boolean connected(){return !authKey.isEmpty();}
    public void connect(String key) throws Exception {
        JSONObject user=api.user(key);String name=text(user,"fullname");if(name.isEmpty())name=text(user,"email");if(name.contains("@"))name=name.substring(0,name.indexOf('@'));if(name.isEmpty())name="Your Stremio";
        List<Addon> list=api.addons(key);List<JSONObject> items=api.library(key);
        vault.put("session",new JSONObject().put("key",key).put("name",name).toString());
        authKey=key;accountName=name;guest=false;sessionUnreadable=false;addons=list;
        synchronized(libraryLock){library.clear();pending.clear();for(JSONObject item:items)library.put(text(item,"_id"),item);saveLibrary();}
        saveAddons(list);
    }
    public void refresh() throws Exception {
        String key=authKey;
        if(key.isEmpty()){List<Addon> list=api.publicAddons();if(authKey.isEmpty()){addons=list;guest=true;}return;}
        flush();List<Addon> list=api.addons(key);List<JSONObject> items=api.library(key);
        if(!key.equals(authKey))return;
        addons=list;saveAddons(list);
        synchronized(libraryLock){library.clear();for(JSONObject item:items)library.put(text(item,"_id"),item);library.putAll(pending);saveLibrary();}
    }
    /** Entering Live refreshes provider configuration without flushing library writes. */
    public void refreshAddons()throws Exception{
        String key=authKey;if(key.isEmpty()){if(addons.isEmpty())refresh();return;}
        List<Addon> list=api.addons(key);if(key.equals(authKey)){addons=list;saveAddons(list);}
    }
    private void saveAddons(List<Addon> list)throws Exception{JSONArray a=new JSONArray();for(Addon addon:list)a.put(addon.descriptor);vault.put("addons",a.toString());}
    private void saveLibrary()throws Exception{vault.put("library",new JSONArray(library.values()).toString());vault.put("pending",new JSONArray(pending.values()).toString());}
    public void disconnect(){
        // Serialize against queued writes so another account can never receive this account's progress.
        synchronized(libraryLock){authKey="";accountName="";addons=Collections.emptyList();library.clear();pending.clear();guest=false;vault.clear();}
    }
    public List<Title> libraryTitles(boolean continueOnly){
        List<JSONObject> rows=new ArrayList<>();synchronized(libraryLock){rows.addAll(library.values());}
        rows.sort((a,b)->text(b.optJSONObject("state")==null?b:b.optJSONObject("state"),"lastWatched").compareTo(text(a.optJSONObject("state")==null?a:a.optJSONObject("state"),"lastWatched")));
        List<Title> out=new ArrayList<>();for(JSONObject j:rows){if(j.optBoolean("removed")||!continueOnly&&j.optBoolean("temp"))continue;JSONObject state=j.optJSONObject("state");if(continueOnly&&(state==null||state.optLong("timeOffset")<=0||state.optLong("duration")>0&&state.optLong("timeOffset")>=state.optLong("duration")*.95))continue;out.add(new Title(j));}return out;
    }
    public JSONObject libraryItem(String id){synchronized(libraryLock){JSONObject j=library.get(id);try{return j==null?null:new JSONObject(j.toString());}catch(Exception e){return null;}}}
    public boolean saved(String id){JSONObject j=libraryItem(id);return j!=null&&!j.optBoolean("removed")&&!j.optBoolean("temp");}
    public long resume(String id,String videoId){JSONObject j=libraryItem(id);JSONObject state=j==null?null:j.optJSONObject("state");if(state==null||!videoId.equals(text(state,"video_id")))return 0;long pos=state.optLong("timeOffset"),duration=state.optLong("duration");return duration>0&&pos>=duration*.95?0:pos;}
    public void toggleLibrary(Title title,java.util.function.Consumer<Boolean> callback){
        String key=authKey;
        writes.execute(()->{try{if(!connected()||!key.equals(authKey))return;JSONObject item=baseItem(title);boolean remove=saved(title.id);item.put("removed",remove).put("temp",false).put("_mtime",now());queue(item);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(()->callback.accept(!remove));flush();
        }catch(Exception ignored){new android.os.Handler(android.os.Looper.getMainLooper()).post(()->callback.accept(saved(title.id)));}});
    }
    public void progress(Title title,String videoId,long position,long duration,long watched,boolean ended){
        final String key=authKey;if(key.isEmpty()||duration<1||position<0)return;
        writes.execute(()->{try{if(!key.equals(authKey))return;JSONObject item=baseItem(title);JSONObject state=item.optJSONObject("state");if(state==null)state=new JSONObject();
            if(!videoId.equals(text(state,"video_id")))state.put("timeWatched",0).put("flaggedWatched",0);
            long total=state.optLong("timeWatched")+Math.max(0,watched);
            boolean complete=ended||position>=duration*.95;
            state.put("video_id",videoId).put("timeOffset",complete?0:position).put("duration",duration).put("lastWatched",now()).put("timeWatched",total).put("overallTimeWatched",state.optLong("overallTimeWatched")+Math.max(0,watched));
            if((complete||total>duration*.7)&&state.optInt("flaggedWatched")==0)state.put("flaggedWatched",1).put("timesWatched",state.optInt("timesWatched")+1);
            item.put("state",state).put("_mtime",now());queue(item);flush();
        }catch(Exception ignored){}});
    }
    private JSONObject baseItem(Title t)throws Exception{JSONObject j=libraryItem(t.id);if(j==null)j=new JSONObject().put("_id",t.id).put("_ctime",now()).put("state",new JSONObject()).put("temp",true);
        j.put("name",t.name).put("type",t.type).put("poster",t.poster).put("background",t.background).put("removed",false);return j;}
    private void queue(JSONObject item)throws Exception{synchronized(libraryLock){if(!connected())return;library.put(text(item,"_id"),item);pending.put(text(item,"_id"),item);saveLibrary();}}
    public void flush(){
        synchronized(flushLock){String key=authKey;if(key.isEmpty())return;Map<String,JSONObject> copy;
            synchronized(libraryLock){copy=new LinkedHashMap<>(pending);}
            for(Map.Entry<String,JSONObject> entry:copy.entrySet()){
                if(!key.equals(authKey))return;
                try{api.putLibrary(key,entry.getValue());}catch(Exception failure){break;}
                synchronized(libraryLock){if(!key.equals(authKey))return;if(pending.get(entry.getKey())==entry.getValue())pending.remove(entry.getKey());}
            }
            synchronized(libraryLock){if(key.equals(authKey))try{saveLibrary();}catch(Exception ignored){}}
        }
    }
    static String now(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
}
