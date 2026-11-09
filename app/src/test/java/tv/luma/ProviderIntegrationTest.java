package tv.luma;

import org.json.*;
import org.junit.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static tv.luma.Models.*;

/** Exercise real HTTP/JSON boundaries without an account or third-party providers. */
public class ProviderIntegrationTest {
    private ServerSocket server;private StremioClient client;private String base;
    private final Map<String,String> replies=new ConcurrentHashMap<>();
    private final ExecutorService sockets=Executors.newCachedThreadPool();
    @Before public void start()throws Exception{
        server=new ServerSocket(0,20,InetAddress.getByName("127.0.0.1"));base="http://127.0.0.1:"+server.getLocalPort();client=new StremioClient();
        sockets.execute(()->{while(!server.isClosed())try{Socket socket=server.accept();sockets.execute(()->{try(Socket s=socket){
            BufferedReader reader=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.UTF_8));String first=reader.readLine();String path=URLDecoder.decode(first.split(" ")[1],"UTF-8");
            String line;while((line=reader.readLine())!=null&&!line.isEmpty()){}
            String response=replies.getOrDefault(path,"HTTP/1.1 404 Not Found\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}");s.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
        }catch(Exception ignored){}});}catch(IOException ignored){}});
    }
    @After public void close()throws Exception{client.close();server.close();sockets.shutdownNow();}
    private void reply(String path,int status,String json){replies.put(path,"HTTP/1.1 "+status+" Response\r\nContent-Type: application/json\r\nContent-Length: "+json.getBytes(StandardCharsets.UTF_8).length+"\r\nConnection: close\r\n\r\n"+json);}
    private Addon addon(String prefix,String resources,String catalogs)throws Exception{return new Addon(new JSONObject().put("transportUrl",base+prefix+"/manifest.json").put("manifest",new JSONObject().put("name",prefix).put("types",new JSONArray("[\"movie\",\"series\"]")).put("resources",new JSONArray(resources)).put("catalogs",new JSONArray(catalogs))));}
    @Test public void mixedProvidersPreservePlayableHeadersAndReportFailures()throws Exception{
        reply("/good/stream/series/tt1:1:2.json",200,"{\"streams\":[{\"url\":\"https://media.example/video.mkv\",\"name\":\"1080p\",\"behaviorHints\":{\"proxyHeaders\":{\"request\":{\"Referer\":\"https://example.org\"}}}},{\"infoHash\":\"abc\"},{\"externalUrl\":\"https://example.org/watch\"}]}");
        reply("/bad/stream/series/tt1:1:2.json",503,"{}");
        StreamResult result=client.streams(Arrays.asList(addon("/good","[\"stream\"]","[]"),addon("/bad","[\"stream\"]","[]")),new Title(new JSONObject().put("id","tt1").put("type","series")),"tt1:1:2",null);
        assertEquals(1,result.streams.size());assertEquals("https://example.org",result.streams.get(0).headers.get("Referer"));assertEquals(1,result.torrents);assertEquals(1,result.external);assertEquals(1,result.failedProviders);
    }
    @Test public void embeddedStreamsDoNotInvokeAddonStreamRoutes()throws Exception{
        StreamResult result=client.streams(Collections.singletonList(addon("/missing","[\"stream\"]","[]")),new Title(new JSONObject().put("id","tt1")),"tt1",new JSONArray("[{\"url\":\"https://media.example/included.mp4\"}]"));assertEquals(1,result.streams.size());assertEquals(0,result.failedProviders);
    }
    @Test public void parallelSearchDeduplicatesByTypeAndId()throws Exception{
        reply("/one/catalog/movie/search/search=space opera.json",200,"{\"metas\":[{\"id\":\"tt1\",\"name\":\"Space\",\"type\":\"movie\"}]}");
        reply("/two/catalog/movie/search/search=space opera.json",200,"{\"metas\":[{\"id\":\"tt1\",\"name\":\"Space\",\"type\":\"movie\"},{\"id\":\"tt2\",\"name\":\"Orbit\",\"type\":\"movie\"}]}");
        String c="[{\"id\":\"search\",\"type\":\"movie\",\"extra\":[{\"name\":\"search\"}]}]";
        assertEquals(2,client.search(Arrays.asList(addon("/one","[\"catalog\"]",c),addon("/two","[\"catalog\"]",c)),"space opera").size());
    }
    @Test public void metadataFallbackSkipsUnavailableProvider()throws Exception{
        reply("/one/meta/movie/tt1.json",500,"{}");reply("/two/meta/movie/tt1.json",200,"{\"meta\":{\"name\":\"Recovered title\",\"description\":\"Metadata from the second provider\"}}");
        Title meta=client.meta(Arrays.asList(addon("/one","[\"meta\"]","[]"),addon("/two","[\"meta\"]","[]")),new Title(new JSONObject().put("id","tt1").put("type","movie")));assertEquals("tt1",meta.id);assertEquals("Recovered title",meta.name);assertEquals("movie",meta.type);
    }
}
