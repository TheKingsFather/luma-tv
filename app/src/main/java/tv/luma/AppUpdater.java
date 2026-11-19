package tv.luma;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.BooleanSupplier;

/** Release-only updates. Credentials go only to this repository's GitHub API. */
public final class AppUpdater {
    static final String API="https://api.github.com/repos/TheKingsFather/luma-tv";
    static final long MAX_APK=128L*1024*1024;
    public volatile String token=""; // Session-only; never written into preferences or the APK.
    Transport transport=new HttpTransport();
    interface Transport { Response open(String path,String token,boolean binary)throws IOException; }
    interface Connections { HttpURLConnection open(URL url)throws IOException; }
    static final class Response implements AutoCloseable {
        final InputStream body;private final Runnable close;
        private final AtomicBoolean closed=new AtomicBoolean();
        Response(InputStream body,Runnable close){this.body=body;this.close=close;}
        public void close()throws IOException{if(closed.compareAndSet(false,true))try{close.run();}finally{body.close();}}
    }
    static final class Cancellation implements BooleanSupplier {
        private volatile boolean canceled;private Response response;
        public boolean getAsBoolean(){return canceled;}
        void attach(Response value)throws IOException{boolean close;synchronized(this){close=canceled;if(!close)response=value;}if(close)value.close();}
        synchronized void finish(){response=null;}
        void cancel(){Response current;synchronized(this){canceled=true;current=response;response=null;}if(current!=null)try{current.close();}catch(IOException|RuntimeException ignored){}}
    }
    public static class UpdateException extends IOException { UpdateException(String message){super(message);} }
    public static final class AuthenticationRequired extends UpdateException {
        AuthenticationRequired(){super("GitHub could not read releases. This private repository needs a token with Contents: Read access.");}
    }
    public static final class Release {
        public final String version,digest;public final long id,size;
        Release(String version,long id,long size,String digest){this.version=version;this.id=id;this.size=size;this.digest=digest;}
    }
    static long[] version(String text)throws UpdateException{
        if(text==null||!text.matches("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"))throw new UpdateException("The release has an unsupported version number.");
        String[] parts=text.replaceFirst("^v","").split("\\.");long[] result=new long[3];
        try{for(int n=0;n<3;n++)result[n]=Long.parseLong(parts[n]);}catch(NumberFormatException e){throw new UpdateException("The release has an unsupported version number.");}return result;
    }
    static int compare(String a,String b)throws UpdateException{
        long[] first=version(a),second=version(b);for(int n=0;n<3;n++){int order=Long.compare(first[n],second[n]);if(order!=0)return order;}return 0;
    }
    static Release parse(JSONObject json,String current)throws UpdateException{
        if(json.optBoolean("draft")||json.optBoolean("prerelease"))return null;
        String tag=json.optString("tag_name");if(compare(tag,current)<=0)return null;
        String number=tag.replaceFirst("^v","");JSONArray assets=json.optJSONArray("assets");String name="Luma-TV-"+number+".apk";
        if(assets!=null)for(int n=0;n<assets.length();n++){
            JSONObject asset=assets.optJSONObject(n);if(asset==null||!name.equals(asset.optString("name")))continue;
            String digest=asset.optString("digest");long id=asset.optLong("id"),size=asset.optLong("size");
            if(id<=0||size<=0||size>MAX_APK)throw new UpdateException("The release has invalid APK download information.");
            if(!digest.matches("sha256:[0-9a-fA-F]{64}"))throw new UpdateException("The release is missing its SHA-256 checksum.");
            return new Release(number,id,size,digest.substring(7).toLowerCase(Locale.ROOT));
        }
        throw new UpdateException("This release has no Android TV APK.");
    }
    public Release check(String current)throws IOException{return check(current,new Cancellation());}
    Release check(String current,Cancellation cancel)throws IOException{
        if(cancel.getAsBoolean())throw new InterruptedIOException("Update canceled");
        String access=token;try(Response response=transport.open("/releases/latest",access,false)){
            cancel.attach(response);
            ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=response.body.read(buffer))!=-1){if(cancel.getAsBoolean())throw new InterruptedIOException("Update canceled");if(data.size()+count>1024*1024)throw new UpdateException("GitHub returned an unreadable release.");data.write(buffer,0,count);}
            try{return parse(new JSONObject(new String(data.toByteArray(),StandardCharsets.UTF_8)),current);}catch(JSONException e){throw new UpdateException("GitHub returned an unreadable release.");}
        }finally{cancel.finish();}
    }
    public File download(Release release,File folder,BooleanSupplier canceled,IntConsumer progress)throws IOException{
        if(!folder.isDirectory()&&!folder.mkdirs())throw new UpdateException("There is not enough space to save the update.");
        File partial=File.createTempFile("luma-",".part",folder),complete=null;boolean saved=false;
        try(Response response=transport.open("/releases/assets/"+release.id,token,true)){
            if(canceled instanceof Cancellation)((Cancellation)canceled).attach(response);
            MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
            long size=0;int previous=-1;byte[] buffer=new byte[65536];int count;
            try(OutputStream output=new FileOutputStream(partial)){
                while((count=response.body.read(buffer))!=-1){
                    if(canceled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Update canceled");
                    size+=count;if(size>release.size)throw new UpdateException("The download exceeds its published size.");
                    digest.update(buffer,0,count);output.write(buffer,0,count);int percent=(int)(size*100/release.size);
                    if(percent!=previous){progress.accept(percent);previous=percent;}
                }
            }
            if(canceled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Update canceled");
            if(size!=release.size||!hex(digest.digest()).equals(release.digest))throw new UpdateException("The update failed its integrity check. Please download it again.");
            complete=File.createTempFile("Luma-TV-"+release.version+"-",".apk",folder);
            Files.move(partial.toPath(),complete.toPath(),StandardCopyOption.REPLACE_EXISTING);saved=true;return complete;
        }finally{if(canceled instanceof Cancellation)((Cancellation)canceled).finish();partial.delete();if(!saved&&complete!=null)complete.delete();}
    }
    static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
    static final class HttpTransport implements Transport {
        private final Connections connections;
        HttpTransport(){this(url->(HttpURLConnection)url.openConnection());}
        HttpTransport(Connections connections){this.connections=connections;}
        static boolean allowed(URL url){
            if(!"https".equals(url.getProtocol())||url.getUserInfo()!=null||url.getPort()!=-1&&url.getPort()!=443)return false;
            return Arrays.asList("api.github.com","release-assets.githubusercontent.com","objects.githubusercontent.com","github.com").contains(url.getHost().toLowerCase(Locale.ROOT));
        }
        public Response open(String path,String token,boolean binary)throws IOException{
            if(!path.equals("/releases/latest")&&!path.matches("/releases/assets/[1-9][0-9]*"))throw new UpdateException("Invalid update request.");
            if(token.length()>2048||token.matches("(?s).*\\s.*"))throw new AuthenticationRequired();
            URL url=new URL(API+path);boolean credentials=true;
            for(int attempt=0;attempt<5;attempt++){
                if(!allowed(url))throw new UpdateException("GitHub returned an unsupported download location.");
                HttpURLConnection connection=connections.open(url);boolean handedOff=false;
                try{
                    connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(30000);
                    connection.setRequestProperty("User-Agent","Luma-TV-Updater");connection.setRequestProperty("Accept",binary?"application/octet-stream":"application/vnd.github+json");
                    if(credentials){connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28");if(!token.isEmpty())connection.setRequestProperty("Authorization","Bearer "+token);}
                    int status=connection.getResponseCode();
                    if(status==401||status==403||status==404)throw new AuthenticationRequired();
                    if(status==301||status==302||status==303||status==307||status==308){
                        String location=connection.getHeaderField("Location");if(location==null)throw new UpdateException("GitHub could not start the download.");
                        URL next=new URL(url,location);credentials=credentials&&"api.github.com".equals(next.getHost())&&next.getPath().startsWith("/repos/TheKingsFather/luma-tv/");url=next;continue;
                    }
                    if(status!=200)throw new UpdateException("GitHub could not complete the update request. Try again later.");
                    InputStream input=connection.getInputStream();handedOff=true;return new Response(input,connection::disconnect);
                }finally{if(!handedOff)connection.disconnect();}
            }
            throw new UpdateException("GitHub redirected the update too many times.");
        }
    }
}
