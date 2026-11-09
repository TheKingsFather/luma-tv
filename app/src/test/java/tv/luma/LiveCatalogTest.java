package tv.luma;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static tv.luma.Models.*;

public class LiveCatalogTest {
    private Addon addon()throws Exception{return new Addon(new JSONObject().put("transportUrl","https://fixture.invalid/config/manifest.json").put("manifest",new JSONObject()));}
    private Catalog catalog(String type,String id,String name)throws Exception{return new Catalog(addon(),new JSONObject().put("type",type).put("id",id).put("name",name));}
    @Test public void everySupportedLiveTypeIsIncluded()throws Exception{for(String type:LIVE_TYPES)assertTrue(type,catalog(type,"all","Watch now").matches("Live"));}
    @Test public void animeAndGenericLibrariesAreExcluded()throws Exception{for(String type:Arrays.asList("anime","other","movie","series","downloads"))assertFalse(type,catalog(type,"all","Live anime").matches("Live"));}
    @Test public void sportsReplaysAndHighlightsAreExcluded()throws Exception{for(String label:Arrays.asList("Replays","Highlights","Catchup","VOD","Archive","Archives","Replay"))assertFalse(label,catalog("sports",label,"Football").isLive());}
    @Test public void liveFilterUsesWholeWordsAndIgnoresCase()throws Exception{assertTrue(catalog("TV","all","Live channels").isLive());assertTrue(catalog("sports","highlighted","Live football").isLive());}
    @Test public void requiredSearchIsNotBrowseableEvenWhenLive()throws Exception{Catalog c=new Catalog(addon(),new JSONObject("{\"type\":\"tv\",\"id\":\"search\",\"extraRequired\":[\"search\"]}"));assertFalse(c.matches("Live"));}
    @Test public void liveCatalogInventoryHasNoTwelveRowCutoff()throws Exception{JSONArray catalogs=new JSONArray();for(int i=0;i<24;i++)catalogs.put(new JSONObject().put("type","tv").put("id","live"+i));Addon a=new Addon(new JSONObject().put("transportUrl","https://fixture.invalid/manifest.json").put("manifest",new JSONObject().put("catalogs",catalogs)));StremioClient client=new StremioClient();try{assertEquals(24,client.catalogs(Collections.singletonList(a)).stream().filter(c->c.matches("Live")).count());}finally{client.close();}}
    @Test public void liveAndSquareCardsPreserveTheirDeclaredProportions()throws Exception{Title live=new Title(new JSONObject().put("type","tv").put("posterShape","poster"));assertEquals("landscape",live.posterShape());assertEquals(232,live.cardWidth());assertEquals(130,live.artworkHeight());assertEquals(189,live.cardHeight());Title square=new Title(new JSONObject().put("type","movie").put("posterShape","square"));assertEquals(157,square.cardWidth());assertEquals(157,square.artworkHeight());}
    @Test public void ordinaryPostersRetainPortraitFrames()throws Exception{Title t=new Title(new JSONObject().put("type","movie"));assertEquals("poster",t.posterShape());assertEquals(125,t.cardWidth());assertEquals(157,t.artworkHeight());assertEquals(216,t.cardHeight());}
    @Test public void missingTypesInheritCatalogWithoutMutatingProviderJson()throws Exception{JSONObject row=new JSONObject().put("id","channel-1");List<Title> titles=StremioClient.titles(new JSONArray().put(row),"tv");assertEquals("tv",titles.get(0).type);assertFalse(row.has("type"));}
    @Test public void explicitItemTypeWinsOverCatalogDefault()throws Exception{Title t=StremioClient.titles(new JSONArray("[{\"id\":\"tt1\",\"type\":\"series\"}]"),"tv").get(0);assertEquals("series",t.type);}
}
