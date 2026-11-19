package tv.luma;

import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class AppUpdaterTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final byte[] APK="a signed APK fixture".getBytes(StandardCharsets.UTF_8);
    private static String sha(byte[] bytes)throws Exception{return AppUpdater.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static JSONObject release(String version,byte[] bytes)throws Exception{
        return new JSONObject().put("tag_name","v"+version).put("assets",new JSONArray().put(new JSONObject().put("name","Luma-TV-"+version+".apk").put("id",41).put("size",bytes.length).put("digest","sha256:"+sha(bytes))));
    }
    private static AppUpdater updater(byte[] body){AppUpdater client=new AppUpdater();client.transport=(path,token,binary)->new AppUpdater.Response(new ByteArrayInputStream(body),()->{});return client;}
    private static final class Connection extends HttpURLConnection {
        final int status;final String location;boolean disconnected;
        Connection(URL url,int status,String location){super(url);this.status=status;this.location=location;}
        public int getResponseCode(){return status;}
        public String getHeaderField(String name){return "Location".equals(name)?location:null;}
        public InputStream getInputStream(){return new ByteArrayInputStream(APK);}
        public void disconnect(){disconnected=true;}public boolean usingProxy(){return false;}public void connect(){}
    }
    private static AppUpdater.HttpTransport transport(List<Connection> opened,int[] statuses,String[] locations){
        return new AppUpdater.HttpTransport(url->{int n=opened.size();Connection c=new Connection(url,statuses[n],locations[n]);opened.add(c);return c;});
    }
    @Test public void versionsCompareNumerically()throws Exception{assertTrue(AppUpdater.compare("v0.10.0","0.9.9")>0);assertTrue(AppUpdater.compare("1.0.0","0.99.99")>0);assertEquals(0,AppUpdater.compare("v0.7.0","0.7.0"));}
    @Test public void malformedVersionsAreRejected(){for(String version:new String[]{"","0.7","1.2.3-rc1","01.2.3","1.2.3/../../x","999999999999999999999.0.0"})assertThrows(AppUpdater.UpdateException.class,()->AppUpdater.version(version));}
    @Test public void currentOlderDraftAndPrereleasesAreNotUpdates()throws Exception{
        assertNull(AppUpdater.parse(release("0.7.0",APK),"0.7.0"));assertNull(AppUpdater.parse(release("0.6.1",APK),"0.7.0"));
        assertNull(AppUpdater.parse(release("0.8.0",APK).put("draft",true),"0.7.0"));assertNull(AppUpdater.parse(release("0.8.0",APK).put("prerelease",true),"0.7.0"));
    }
    @Test public void exactApkAndVerifiedDigestAreRequired()throws Exception{
        JSONObject metadata=release("0.8.0",APK);JSONObject asset=metadata.getJSONArray("assets").getJSONObject(0);
        asset.put("name","Luma-TV-0.8.0.exe");assertThrows(AppUpdater.UpdateException.class,()->AppUpdater.parse(metadata,"0.7.0"));
        asset.put("name","Luma-TV-0.8.0.apk").remove("digest");assertThrows(AppUpdater.UpdateException.class,()->AppUpdater.parse(metadata,"0.7.0"));
    }
    @Test public void invalidSizesAndAssetIdsAreRejected()throws Exception{
        for(long size:new long[]{-1,0,AppUpdater.MAX_APK+1}){JSONObject metadata=release("0.8.0",APK);metadata.getJSONArray("assets").getJSONObject(0).put("size",size);assertThrows(AppUpdater.UpdateException.class,()->AppUpdater.parse(metadata,"0.7.0"));}
        JSONObject metadata=release("0.8.0",APK);metadata.getJSONArray("assets").getJSONObject(0).put("id",0);assertThrows(AppUpdater.UpdateException.class,()->AppUpdater.parse(metadata,"0.7.0"));
    }
    @Test public void metadataIsReadWithoutTrustingDownloadUrls()throws Exception{
        JSONObject metadata=release("0.8.0",APK);metadata.getJSONArray("assets").getJSONObject(0).put("url","https://attacker.invalid/apk").put("browser_download_url","https://attacker.invalid/apk");
        AppUpdater client=updater(metadata.toString().getBytes(StandardCharsets.UTF_8));AppUpdater.Release update=client.check("0.7.0");assertEquals(41,update.id);assertEquals("0.8.0",update.version);
    }
    @Test public void unreadableOrUnboundedMetadataIsRejected(){assertThrows(AppUpdater.UpdateException.class,()->updater("not JSON".getBytes(StandardCharsets.UTF_8)).check("0.7.0"));assertThrows(AppUpdater.UpdateException.class,()->updater(new byte[1024*1024+1]).check("0.7.0"));}
    @Test public void verifiedDownloadUsesFixedAssetEndpointAndReportsCompletion()throws Exception{
        AppUpdater client=updater(APK);AtomicInteger progress=new AtomicInteger();List<String> paths=new ArrayList<>();
        client.transport=(path,token,binary)->{paths.add(path);assertTrue(binary);return new AppUpdater.Response(new ByteArrayInputStream(APK),()->{});};
        File folder=temp.newFolder();File file=client.download(AppUpdater.parse(release("0.8.0",APK),"0.7.0"),folder,()->false,progress::set);
        assertArrayEquals(APK,Files.readAllBytes(file.toPath()));assertEquals(100,progress.get());assertEquals(Collections.singletonList("/releases/assets/41"),paths);assertEquals(1,folder.list().length);assertTrue(file.getName().endsWith(".apk"));
    }
    @Test public void checksumFailureDeletesPartialAndCompletedFiles()throws Exception{
        File folder=temp.newFolder();JSONObject metadata=release("0.8.0",APK);metadata.getJSONArray("assets").getJSONObject(0).put("digest","sha256:"+String.join("",Collections.nCopies(64,"0")));
        assertThrows(AppUpdater.UpdateException.class,()->updater(APK).download(AppUpdater.parse(metadata,"0.7.0"),folder,()->false,p->{}));assertEquals(0,folder.list().length);
    }
    @Test public void truncatedAndOversizedDownloadsAreRejectedAndCleaned()throws Exception{
        for(byte[] body:new byte[][]{new byte[APK.length-1],new byte[APK.length+1]}){File folder=temp.newFolder();assertThrows(AppUpdater.UpdateException.class,()->updater(body).download(AppUpdater.parse(release("0.8.0",APK),"0.7.0"),folder,()->false,p->{}));assertEquals(0,folder.list().length);}
    }
    @Test public void cancellationDeletesPartialAndClosesResponse()throws Exception{
        AppUpdater client=new AppUpdater();AtomicInteger closed=new AtomicInteger();client.transport=(p,t,b)->new AppUpdater.Response(new ByteArrayInputStream(APK),closed::incrementAndGet);File folder=temp.newFolder();
        assertThrows(InterruptedIOException.class,()->client.download(AppUpdater.parse(release("0.8.0",APK),"0.7.0"),folder,()->true,p->{}));assertEquals(0,folder.list().length);assertEquals(1,closed.get());
    }
    @Test public void cancellationClosesAnActiveDownloadResponse()throws Exception{
        AppUpdater.Cancellation cancellation=new AppUpdater.Cancellation();AtomicInteger closed=new AtomicInteger();AppUpdater.Response response=new AppUpdater.Response(new ByteArrayInputStream(APK),closed::incrementAndGet);
        cancellation.attach(response);cancellation.cancel();response.close();assertTrue(cancellation.getAsBoolean());assertEquals(1,closed.get());
    }
    @Test public void expiredOrMissingAccessRequestsAuthentication()throws Exception{
        for(int status:new int[]{401,403,404}){List<Connection> opened=new ArrayList<>();AppUpdater.HttpTransport transport=transport(opened,new int[]{status},new String[]{null});assertThrows(AppUpdater.AuthenticationRequired.class,()->transport.open("/releases/latest","session-token",false));assertTrue(opened.get(0).disconnected);}
    }
    @Test public void credentialsAreNeverForwardedToAssetHost()throws Exception{
        List<Connection> opened=new ArrayList<>();AppUpdater.HttpTransport transport=transport(opened,new int[]{302,200},new String[]{"https://release-assets.githubusercontent.com/signed-asset",null});
        try(AppUpdater.Response response=transport.open("/releases/assets/41","session-token",true)){assertEquals("Bearer session-token",opened.get(0).getRequestProperty("Authorization"));assertNull(opened.get(1).getRequestProperty("Authorization"));assertFalse(opened.get(0).getInstanceFollowRedirects());}assertTrue(opened.get(1).disconnected);
    }
    @Test public void bouncedRedirectDoesNotRegainCredentials()throws Exception{
        List<Connection> opened=new ArrayList<>();AppUpdater.HttpTransport transport=transport(opened,new int[]{302,302,200},new String[]{"https://release-assets.githubusercontent.com/asset",AppUpdater.API+"/releases/assets/41",null});
        try(AppUpdater.Response response=transport.open("/releases/assets/41","session-token",true)){assertNull(opened.get(1).getRequestProperty("Authorization"));assertNull(opened.get(2).getRequestProperty("Authorization"));}
    }
    @Test public void relativeRepositoryRedirectKeepsApiAuthentication()throws Exception{
        List<Connection> opened=new ArrayList<>();AppUpdater.HttpTransport transport=transport(opened,new int[]{302,200},new String[]{"/repos/TheKingsFather/luma-tv/releases/assets/42",null});
        try(AppUpdater.Response response=transport.open("/releases/assets/41","session-token",true)){assertEquals("Bearer session-token",opened.get(1).getRequestProperty("Authorization"));}
    }
    @Test public void unsafeRedirectsAreRejectedBeforeConnecting()throws Exception{
        for(String location:new String[]{"http://release-assets.githubusercontent.com/asset","https://attacker.invalid/asset","https://session-token@github.com/asset","https://github.com:8443/asset"}){
            List<Connection> opened=new ArrayList<>();AppUpdater.HttpTransport transport=transport(opened,new int[]{302},new String[]{location});assertThrows(AppUpdater.UpdateException.class,()->transport.open("/releases/assets/41","session-token",true));assertEquals(1,opened.size());assertTrue(opened.get(0).disconnected);
        }
    }
    @Test public void redirectLoopsAreBounded()throws Exception{
        List<Connection> opened=new ArrayList<>();AppUpdater.HttpTransport transport=transport(opened,new int[]{302,302,302,302,302},new String[]{"/loop","/loop","/loop","/loop","/loop"});assertThrows(AppUpdater.UpdateException.class,()->transport.open("/releases/latest","session-token",false));assertEquals(5,opened.size());
    }
    @Test public void arbitraryEndpointsAndHeaderInjectionAreRejectedBeforeNetwork(){
        AppUpdater.HttpTransport transport=new AppUpdater.HttpTransport(url->{throw new AssertionError("Must not connect");});
        assertThrows(AppUpdater.UpdateException.class,()->transport.open("/user","",false));assertThrows(AppUpdater.AuthenticationRequired.class,()->transport.open("/releases/latest","token\r\nInjected: yes",false));
    }
}
