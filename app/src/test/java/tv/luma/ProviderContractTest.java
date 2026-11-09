package tv.luma;

import org.json.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static tv.luma.Models.*;

/** Wire-contract fixtures require no network or owner credentials. */
public class ProviderContractTest {
    private Addon addon(String type)throws Exception{return new Addon(new JSONObject("{\"transportUrl\":\"https://fixture.invalid/user-config/manifest.json\",\"manifest\":{\"id\":\"fixture\",\"name\":\"Fixture\",\"types\":[\""+type+"\"],\"resources\":[\"catalog\",\"meta\"],\"catalogs\":[]},\"flags\":{\"protected\":true}}"));}
    @Test public void refreshUsesExactConfiguredUrlAndPreservesDescriptorFlags()throws Exception{
        List<String> paths=new CopyOnWriteArrayList<>();StremioClient c=new StremioClient((url,body)->{paths.add(url);return new JSONObject("{\"id\":\"fixture\",\"catalogs\":[{\"type\":\"tv\",\"id\":\"fresh\"}]}");});
        try{Addon fresh=c.currentManifests(Collections.singletonList(addon("tv"))).get(0);assertEquals(Collections.singletonList("https://fixture.invalid/user-config/manifest.json"),paths);assertEquals("Fixture",fresh.name);assertTrue(fresh.descriptor.getJSONObject("flags").getBoolean("protected"));assertEquals("fresh",fresh.manifest.getJSONArray("catalogs").getJSONObject(0).getString("id"));}finally{c.close();}
    }
    @Test public void unavailableProviderRetainsItsLastKnownManifest()throws Exception{StremioClient c=new StremioClient((url,body)->{throw new java.io.IOException("offline");});try{Addon old=addon("tv");assertSame(old,c.currentManifests(Collections.singletonList(old)).get(0));}finally{c.close();}}
    @Test public void vodProvidersAreNotRefetchedAsLive()throws Exception{StremioClient c=new StremioClient((url,body)->{throw new AssertionError("VOD manifest must not be fetched");});try{Addon a=addon("anime");assertSame(a,c.currentManifests(Collections.singletonList(a)).get(0));}finally{c.close();}}
    @Test public void catalogAndSearchNormalizeMissingChannelTypes()throws Exception{StremioClient c=new StremioClient((url,body)->new JSONObject("{\"metas\":[{\"id\":\"channel:1\",\"name\":\"News\"}]}"));try{Addon a=addon("tv");a.manifest.put("catalogs",new JSONArray("[{\"id\":\"all\",\"type\":\"tv\",\"extraSupported\":[\"search\"]}]"));assertEquals("tv",c.catalog(c.catalogs(Collections.singletonList(a)).get(0),"",0).get(0).type);assertEquals("tv",c.search(Collections.singletonList(a),"news").get(0).type);}finally{c.close();}}
    @Test public void partialMetadataRetainsEmbeddedLiveStreamsAndArtwork()throws Exception{StremioClient c=new StremioClient((url,body)->new JSONObject("{\"meta\":{\"name\":\"Updated channel\",\"description\":\"Fresh metadata\"}}"));try{Title t=new Title(new JSONObject("{\"id\":\"channel:1\",\"type\":\"tv\",\"poster\":\"https://fixture.invalid/logo.png\",\"streams\":[{\"url\":\"https://fixture.invalid/live.m3u8\"}]}"));Title meta=c.meta(Collections.singletonList(addon("tv")),t);assertEquals("tv",meta.type);assertEquals(t.poster,meta.poster);assertEquals("Updated channel",meta.name);assertEquals(1,meta.json.getJSONArray("streams").length());}finally{c.close();}}
    @Test public void invalidFreshManifestDoesNotReplaceUsableConfiguration()throws Exception{StremioClient c=new StremioClient((url,body)->new JSONObject("{\"id\":\"fixture\",\"catalogs\":null}"));try{Addon a=addon("sports");assertSame(a,c.currentManifests(Collections.singletonList(a)).get(0));}finally{c.close();}}
}
