package tv.luma;

import org.json.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static tv.luma.Models.*;

public class ProtocolTest {
    private Addon addon(String fields)throws Exception{return new Addon(new JSONObject("{\"transportUrl\":\"https://example.org/configured/manifest.json\",\"manifest\":{"+fields+"}}"));}
    @Test public void stripsOnlyManifestSuffix()throws Exception{assertEquals("https://example.org/configured",addon("\"name\":\"Example\"").base);}
    @Test public void resourceObjectsOverrideTypesAndPrefixes()throws Exception{Addon a=addon("\"types\":[\"movie\",\"series\"],\"resources\":[{\"name\":\"stream\",\"types\":[\"series\"],\"idPrefixes\":[\"tt\"]}]");assertTrue(a.supports("stream","series","tt123:1:2"));assertFalse(a.supports("stream","movie","tt123"));assertFalse(a.supports("stream","series","other123"));assertFalse(a.supports("meta","series","tt123"));}
    @Test public void stringResourceInheritsManifestConstraints()throws Exception{Addon a=addon("\"resources\":[\"stream\"],\"types\":[\"movie\"],\"idPrefixes\":[\"tt\"]");assertTrue(a.supports("stream","movie","tt123"));assertFalse(a.supports("stream","series","tt123"));}
    @Test public void emptyPrefixListSupportsNoIds()throws Exception{Addon a=addon("\"resources\":[\"stream\"],\"types\":[\"movie\"],\"idPrefixes\":[]");assertFalse(a.supports("stream","movie","tt123"));}
    @Test public void unspecifiedPrefixesAcceptCustomIds()throws Exception{assertTrue(addon("\"resources\":[\"meta\"],\"types\":[\"series\"]").supports("meta","series","custom:episode"));}
    @Test public void searchCatalogIsNotBrowseableWhenSearchRequired()throws Exception{Catalog c=new Catalog(addon(""),new JSONObject("{\"id\":\"find\",\"type\":\"movie\",\"extra\":[{\"name\":\"search\",\"isRequired\":true}]}"));assertTrue(c.supportsExtra("search"));assertFalse(c.canBrowse());}
    @Test public void optionalGenreAndSkipCatalogIsBrowseable()throws Exception{Catalog c=new Catalog(addon(""),new JSONObject("{\"id\":\"top\",\"type\":\"movie\",\"extra\":[{\"name\":\"genre\"},{\"name\":\"skip\"}]}"));assertTrue(c.canBrowse());assertTrue(c.supportsExtra("skip"));}
    @Test public void encodesSpecialCharactersWithoutLosingEpisodeColon()throws Exception{assertEquals("https://example.org/configured/stream/series/tt123:1:2.json",StremioClient.resourceUrl(addon(""),"stream","series","tt123:1:2",""));assertEquals("a%2Fb%20%26%3F",StremioClient.encode("a/b &?"));}
    @Test public void queryExtrasUsePathSegmentBeforeJson()throws Exception{assertEquals("https://example.org/configured/catalog/movie/top/search=star%20wars.json",StremioClient.resourceUrl(addon(""),"catalog","movie","top","search="+StremioClient.encode("star wars")));}
    @Test public void proxyRequestHeadersAreKept()throws Exception{Stream s=new Stream(new JSONObject("{\"url\":\"https://media.example/v.mkv\",\"behaviorHints\":{\"proxyHeaders\":{\"request\":{\"User-Agent\":\"Provider\",\"Referer\":\"https://example.org\"}}}}"),"Example");assertTrue(s.playable());assertEquals("Provider",s.headers.get("User-Agent"));assertEquals(2,s.headers.size());}
    @Test public void torrentOnlyIsNotPassedToHttpPlayer()throws Exception{assertFalse(new Stream(new JSONObject("{\"infoHash\":\"abc\"}"),"Example").playable());assertFalse(new Stream(new JSONObject("{\"url\":\"javascript:alert(1)\"}"),"Example").playable());}
    @Test public void pendingIsDistinctFromOtherErrors()throws Exception{try{StremioClient.unwrap(new JSONObject("{\"error\":{\"code\":101,\"message\":\"Not ready\"}}"));fail();}catch(StremioClient.ApiException e){assertTrue(StremioClient.pending(e));}assertFalse(StremioClient.pending(new StremioClient.ApiException(102,"Expired")));}
    @Test public void unwrapSupportsLibraryArrayAndAccountObject()throws Exception{assertTrue(StremioClient.unwrap(new JSONObject("{\"result\":[]}")) instanceof JSONArray);assertEquals("abcd",((JSONObject)StremioClient.unwrap(new JSONObject("{\"result\":{\"authKey\":\"abcd\"}}"))).getString("authKey"));}
    @Test public void libraryTitlesResolveUnderscoreId()throws Exception{Title t=new Title(new JSONObject("{\"_id\":\"tt123\",\"name\":\"Title\"}"));assertEquals("tt123",t.id);assertEquals("movie",t.type);}
    @Test public void episodeSortingUsesSeasonThenEpisode()throws Exception{Title t=new Title(new JSONObject("{\"id\":\"tt1\",\"videos\":[{\"id\":\"c\",\"season\":2,\"episode\":1},{\"id\":\"b\",\"season\":1,\"episode\":3},{\"id\":\"a\",\"season\":1,\"episode\":1}]}"));assertEquals("a",t.videos().get(0).id);assertEquals("c",t.videos().get(2).id);}
    @Test public void futureEpisodeCannotPlayEarly()throws Exception{assertTrue(new Video(new JSONObject("{\"released\":\"2099-01-01T00:00:00.000Z\"}")).isFuture());assertFalse(new Video(new JSONObject("{\"released\":\"2000-01-01T00:00:00.000Z\"}")).isFuture());}
    @Test public void nullableProtocolFieldsDoNotDisplayLiteralNull()throws Exception{assertEquals("",text(new JSONObject("{\"name\":null}"),"name"));}
}
