package tv.luma;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static tv.luma.Models.*;

public final class StremioClient {
    interface Transport { JSONObject read(String url,JSONObject body)throws Exception; }
    Transport transport=StremioClient::request;
    public StremioClient(){}
    StremioClient(Transport transport){this.transport=transport;}
    public static final String API="https://api.strem.io/api/";
    public static final String LINK="https://link.stremio.com/api/v2/";
    public static final String CINEMETA="https://v3-cinemeta.strem.io";
    private final ExecutorService providers=Executors.newFixedThreadPool(8);
    public static final class ApiException extends IOException {
        public final int code;
        public ApiException(int code,String message){super(message);this.code=code;}
    }
    public static String encode(String value){try{return URLEncoder.encode(value,"UTF-8").replace("+","%20");}catch(UnsupportedEncodingException e){throw new AssertionError(e);}}
    public static String path(String value){return encode(value).replace("%3A",":");}
    public static String resourceUrl(Addon a,String resource,String type,String id,String extras){return a.base+"/"+resource+"/"+path(type)+"/"+path(id)+(extras.isEmpty()?"":"/"+extras)+".json";}
    public static Object unwrap(JSONObject envelope) throws ApiException {
        JSONObject error=envelope.optJSONObject("error");
        if(error!=null)throw new ApiException(error.optInt("code"),text(error,"message"));
        return envelope.has("result")?envelope.opt("result"):envelope;
    }
    public static boolean pending(Throwable e){return e instanceof ApiException && ((ApiException)e).code==101;}
    public static String readable(Throwable e){
        if(e instanceof SocketTimeoutException)return "The connection took too long. Please try again.";
        if(e instanceof UnknownHostException)return "Check your TV’s internet connection and try again.";
        if(e instanceof ApiException){int code=((ApiException)e).code;if(code==1||code==2)return "Your Stremio session has expired. Connect again in Settings.";}
        return "We couldn’t reach this service. Please try again.";
    }
    public static JSONObject request(String url,JSONObject body) throws Exception {
        URL target=new URL(url);
        if(!target.getProtocol().equals("https")&&!target.getProtocol().equals("http"))throw new IOException("Unsupported address");
        HttpURLConnection c=(HttpURLConnection)target.openConnection();
        c.setConnectTimeout(10000);c.setReadTimeout(15000);c.setRequestProperty("User-Agent","LumaTV/0.3 (Android TV)");c.setRequestProperty("Accept","application/json");
        try {
            if(body!=null){c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int status=c.getResponseCode();
            if(status<200||status>=300)throw new IOException("HTTP "+status);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1){if(out.size()+count>8*1024*1024)throw new IOException("Response too large");out.write(buffer,0,count);}
                return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            }
        }finally{c.disconnect();}
    }
    public JSONObject createLink() throws Exception {return (JSONObject)unwrap(transport.read(LINK+"create?type=Create",null));}
    public String readLink(String code) throws Exception {
        try {Object result=unwrap(transport.read(LINK+"read?type=Read&code="+encode(code),null));return result instanceof JSONObject?text((JSONObject)result,"authKey"):"";}
        catch(ApiException e){if(pending(e))return "";throw e;}
    }
    public JSONObject user(String key) throws Exception {return (JSONObject)unwrap(transport.read(API+"getUser",new JSONObject().put("type","GetUser").put("authKey",key)));}
    public List<Addon> addons(String key) throws Exception {
        JSONObject result=(JSONObject)unwrap(transport.read(API+"addonCollectionGet",new JSONObject().put("type","AddonCollectionGet").put("authKey",key).put("update",true)));
        List<Addon> out=new ArrayList<>();for(JSONObject d:objects(result.optJSONArray("addons")))out.add(new Addon(d));return currentManifests(out);
    }
    public List<Addon> currentManifests(List<Addon> addons)throws Exception{
        List<Callable<Addon>> calls=new ArrayList<>();
        for(Addon a:addons)calls.add(()->{
            boolean live=false;for(Object value:strings(a.manifest.optJSONArray("types")))if(liveType(String.valueOf(value)))live=true;
            for(JSONObject c:objects(a.manifest.optJSONArray("catalogs")))if(new Catalog(a,c).isLive())live=true;
            if(!live)return a;
            try{JSONObject fresh=transport.read(text(a.descriptor,"transportUrl"),null);if(!text(fresh,"id").isEmpty()&&fresh.optJSONArray("catalogs")!=null){JSONObject merged=new JSONObject(a.manifest.toString());for(Iterator<String> keys=fresh.keys();keys.hasNext();){String key=keys.next();merged.put(key,fresh.get(key));}return new Addon(new JSONObject(a.descriptor.toString()).put("manifest",merged));}}catch(Exception ignored){}
            return a;
        });
        List<Addon> out=new ArrayList<>();List<Future<Addon>> results=providers.invokeAll(calls,18,TimeUnit.SECONDS);for(int i=0;i<results.size();i++)try{out.add(results.get(i).get());}catch(Exception ignored){out.add(addons.get(i));}return out;
    }
    private static List<String> strings(JSONArray a){List<String> out=new ArrayList<>();if(a!=null)for(int i=0;i<a.length();i++)out.add(a.optString(i));return out;}
    public List<Addon> publicAddons() throws Exception {
        JSONObject manifest=transport.read(CINEMETA+"/manifest.json",null);
        return Collections.singletonList(new Addon(new JSONObject().put("transportUrl",CINEMETA+"/manifest.json").put("manifest",manifest)));
    }
    public List<JSONObject> library(String key) throws Exception {
        Object result=unwrap(transport.read(API+"datastoreGet",new JSONObject().put("authKey",key).put("collection","libraryItem").put("ids",new JSONArray()).put("all",true)));
        return objects(result instanceof JSONArray?(JSONArray)result:null);
    }
    public void putLibrary(String key,JSONObject item) throws Exception {
        unwrap(transport.read(API+"datastorePut",new JSONObject().put("authKey",key).put("collection","libraryItem").put("changes",new JSONArray().put(item))));
    }
    public List<Catalog> catalogs(List<Addon> addons){List<Catalog> out=new ArrayList<>();for(Addon a:addons)for(JSONObject c:objects(a.manifest.optJSONArray("catalogs")))out.add(new Catalog(a,c));return out;}
    public List<Title> catalog(Catalog catalog,String genre,int skip) throws Exception {
        List<String> extras=new ArrayList<>();if(!genre.isEmpty())extras.add("genre="+encode(genre));if(skip>0)extras.add("skip="+skip);
        return titles(transport.read(resourceUrl(catalog.addon,"catalog",catalog.type,catalog.id,String.join("&",extras)),null).optJSONArray("metas"),catalog.type);
    }
    public List<Title> search(List<Addon> addons,String query) throws Exception {
        List<Callable<List<Title>>> calls=new ArrayList<>();
        for(Catalog c:catalogs(addons))if(c.supportsExtra("search"))calls.add(()->titles(transport.read(resourceUrl(c.addon,"catalog",c.type,c.id,"search="+encode(query)),null).optJSONArray("metas"),c.type));
        LinkedHashMap<String,Title> out=new LinkedHashMap<>();int success=0;
        for(Future<List<Title>> future:providers.invokeAll(calls,18,TimeUnit.SECONDS))try{for(Title t:future.get())out.putIfAbsent(t.type+":"+t.id,t);success++;}catch(Exception ignored){}
        if(!calls.isEmpty()&&success==0)throw new IOException("Search providers unavailable");
        return new ArrayList<>(out.values());
    }
    public Title meta(List<Addon> addons,Title title) throws Exception {
        Exception failure=null;
        for(Addon a:addons)if(a.supports("meta",title.type,title.id))try{JSONObject meta=transport.read(resourceUrl(a,"meta",title.type,title.id,""),null).optJSONObject("meta");if(meta!=null){JSONObject merged=new JSONObject(title.json.toString());for(Iterator<String> keys=meta.keys();keys.hasNext();){String key=keys.next();merged.put(key,meta.get(key));}if(text(merged,"id").isEmpty())merged.put("id",title.id);if(text(merged,"type").isEmpty())merged.put("type",title.type);return new Title(merged);}}catch(Exception e){failure=e;}
        if(!title.description.isEmpty()||!title.videos().isEmpty())return title;
        if(failure!=null)throw failure;
        return title;
    }
    public StreamResult streams(List<Addon> addons,Title title,String videoId,JSONArray embedded) throws Exception {
        List<Callable<List<JSONObject>>> calls=new ArrayList<>();List<String> names=new ArrayList<>();
        if(embedded!=null&&embedded.length()>0){calls.add(()->objects(embedded));names.add("Included source");}
        else for(Addon a:addons)if(a.supports("stream",title.type,videoId)){calls.add(()->objects(transport.read(resourceUrl(a,"stream",title.type,videoId,""),null).optJSONArray("streams")));names.add(a.name);}
        StreamResult out=new StreamResult();List<Future<List<JSONObject>>> results=providers.invokeAll(calls,24,TimeUnit.SECONDS);Set<String> seen=new HashSet<>();
        for(int i=0;i<results.size();i++)try{for(JSONObject j:results.get(i).get()){
            Stream s=new Stream(j,names.get(i));if(s.playable()){if(seen.add(s.url+new JSONObject(s.headers)))out.streams.add(s);}
            else if(!text(j,"infoHash").isEmpty())out.torrents++;else out.external++;
        }}catch(Exception e){out.failedProviders++;}
        return out;
    }
    public List<JSONObject> subtitles(List<Addon> addons,String type,String id) throws Exception {
        List<Callable<List<JSONObject>>> calls=new ArrayList<>();for(Addon a:addons)if(a.supports("subtitles",type,id))calls.add(()->objects(transport.read(resourceUrl(a,"subtitles",type,id,""),null).optJSONArray("subtitles")));
        List<JSONObject> out=new ArrayList<>();for(Future<List<JSONObject>> f:providers.invokeAll(calls,12,TimeUnit.SECONDS))try{out.addAll(f.get());}catch(Exception ignored){}return out;
    }
    static List<Title> titles(JSONArray a){List<Title> out=new ArrayList<>();for(JSONObject j:objects(a)){Title t=new Title(j);if(!t.id.isEmpty())out.add(t);}return out;}
    static List<Title> titles(JSONArray a,String type)throws JSONException{JSONArray normalized=new JSONArray();for(JSONObject item:objects(a)){JSONObject copy=new JSONObject(item.toString());if(text(copy,"type").isEmpty())copy.put("type",type);normalized.put(copy);}return titles(normalized);}
    public void close(){providers.shutdownNow();}
}
