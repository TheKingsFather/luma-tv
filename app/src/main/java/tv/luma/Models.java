package tv.luma;

import org.json.*;
import java.util.*;

/** Protocol records are independent of Android so the wire contracts can be tested. */
public final class Models {
    private Models() {}
    public static final Set<String> LIVE_TYPES=Collections.unmodifiableSet(new HashSet<>(Arrays.asList("tv","channel","channels","live","iptv","sport","sports","event","events")));
    private static final Set<String> REPLAYS=new HashSet<>(Arrays.asList("replay","replays","highlights","highlight","catchup","vod","archive","archives"));
    public static boolean liveType(String type){return LIVE_TYPES.contains(type.toLowerCase(Locale.ROOT));}
    public static String text(JSONObject o, String key) {
        String value = o.optString(key, "");
        return "null".equals(value) ? "" : value;
    }
    public static List<JSONObject> objects(JSONArray a) {
        List<JSONObject> out = new ArrayList<>();
        if (a != null) for (int i=0;i<a.length();i++) if (a.optJSONObject(i)!=null) out.add(a.optJSONObject(i));
        return out;
    }
    public static final class Addon {
        public final String name, base;
        public final JSONObject descriptor, manifest;
        public Addon(JSONObject descriptor) {
            this.descriptor=descriptor;
            manifest=descriptor.optJSONObject("manifest") == null ? new JSONObject() : descriptor.optJSONObject("manifest");
            name=text(manifest,"name");
            String transport=text(descriptor,"transportUrl");
            base=transport.endsWith("/manifest.json") ? transport.substring(0,transport.length()-14) : transport.replaceAll("/$", "");
        }
        public boolean supports(String resource, String type, String id) {
            JSONArray resources=manifest.optJSONArray("resources");
            if(resources==null)return false;
            for(int i=0;i<resources.length();i++) {
                Object value=resources.opt(i);
                JSONObject r=value instanceof JSONObject ? (JSONObject)value : null;
                String name=r==null?String.valueOf(value):text(r,"name");
                if(!resource.equals(name))continue;
                JSONArray types=r==null?null:r.optJSONArray("types");
                if(types==null)types=manifest.optJSONArray("types");
                if(types!=null && !contains(types,type))continue;
                JSONArray prefixes=r==null?null:r.optJSONArray("idPrefixes");
                if(prefixes==null)prefixes=manifest.optJSONArray("idPrefixes");
                if(prefixes==null)return true;
                for(int j=0;j<prefixes.length();j++)if(id.startsWith(prefixes.optString(j)))return true;
            }
            return false;
        }
    }
    static boolean contains(JSONArray a,String s){for(int i=0;i<a.length();i++)if(s.equals(a.optString(i)))return true;return false;}
    public static final class Catalog {
        public final Addon addon;
        public final String type,id,name;
        public final JSONObject json;
        public Catalog(Addon addon,JSONObject json){this.addon=addon;this.json=json;type=text(json,"type");id=text(json,"id");name=text(json,"name").isEmpty()?id:text(json,"name");}
        public boolean supportsExtra(String extra){
            if(contains(json.optJSONArray("extraSupported")==null?new JSONArray():json.optJSONArray("extraSupported"),extra))return true;
            for(JSONObject e:objects(json.optJSONArray("extra")))if(extra.equals(text(e,"name")))return true;
            return contains(json.optJSONArray("extraRequired")==null?new JSONArray():json.optJSONArray("extraRequired"),extra);
        }
        public boolean canBrowse(){
            JSONArray required=json.optJSONArray("extraRequired");
            if(required!=null && required.length()>0)return false;
            for(JSONObject e:objects(json.optJSONArray("extra")))if(e.optBoolean("isRequired"))return false;
            return true;
        }
        public boolean isLive(){
            for(String word:(id+" "+name).toLowerCase(Locale.ROOT).split("[^a-z]+"))if(REPLAYS.contains(word))return false;
            return liveType(type);
        }
        public boolean matches(String section){return canBrowse()&&("Home".equals(section)||"Movies".equals(section)&&"movie".equals(type)||"Series".equals(section)&&"series".equals(type)||"Live".equals(section)&&isLive());}
    }
    public static final class Title {
        public final JSONObject json;
        public final String id,type,name,poster,background,description,year;
        public Title(JSONObject json){this.json=json;String raw=text(json,"id");id=raw.isEmpty()?text(json,"_id"):raw;type=text(json,"type").isEmpty()?"movie":text(json,"type");name=text(json,"name");poster=text(json,"poster");background=text(json,"background");description=text(json,"description");year=text(json,"releaseInfo");}
        public String posterShape(){String shape=text(json,"posterShape").toLowerCase(Locale.ROOT);return liveType(type)?"landscape":Arrays.asList("square","landscape").contains(shape)?shape:"poster";}
        public int cardWidth(){return "landscape".equals(posterShape())?232:"square".equals(posterShape())?157:125;}
        public int artworkHeight(){return "landscape".equals(posterShape())?130:157;}
        public int cardHeight(){return artworkHeight()+59;}
        public List<Video> videos(){List<Video> out=new ArrayList<>();for(JSONObject v:objects(json.optJSONArray("videos")))out.add(new Video(v));out.sort(Comparator.comparingInt((Video v)->v.season).thenComparingInt(v->v.episode));return out;}
    }
    public static final class Video {
        public final String id,name,released;
        public final int season,episode;
        public final JSONObject json;
        public Video(JSONObject j){json=j;id=text(j,"id");name=text(j,"title").isEmpty()?text(j,"name"):text(j,"title");season=j.optInt("season");episode=j.optInt("episode");released=text(j,"released");}
        public boolean isFuture(){try{java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return !released.isEmpty() && f.parse(released).getTime()>System.currentTimeMillis();}catch(Exception e){return false;}}
    }
    public static final class Stream {
        public final JSONObject json;
        public final String name,description,url,provider;
        public final Map<String,String> headers=new LinkedHashMap<>();
        public Stream(JSONObject j,String provider){json=j;this.provider=provider;name=text(j,"name").isEmpty()?provider:text(j,"name");description=text(j,"description").isEmpty()?text(j,"title"):text(j,"description");url=text(j,"url");JSONObject hints=j.optJSONObject("behaviorHints");JSONObject proxy=hints==null?null:hints.optJSONObject("proxyHeaders");JSONObject request=proxy==null?null:proxy.optJSONObject("request");if(request!=null)for(Iterator<String> it=request.keys();it.hasNext();){String key=it.next();headers.put(key,text(request,key));}}
        public boolean playable(){return url.startsWith("https://") || url.startsWith("http://");}
    }
    public static final class StreamResult {
        public final List<Stream> streams=new ArrayList<>();
        public int torrents,external,failedProviders;
    }
}
