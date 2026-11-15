package tv.luma;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.*;

/** Loopback only. Every report request needs an owner-signed, single-use challenge. */
public final class ReportServer implements AutoCloseable {
    private final ServerSocket server;
    private final Supplier<PublicKey> owner;
    private final CallableBytes snapshots;
    private final LinkedHashMap<String,Long> challenges=new LinkedHashMap<>();
    private volatile boolean closed;
    public interface CallableBytes {byte[] get()throws Exception;}
    public ReportServer(int port,Supplier<PublicKey> owner,CallableBytes snapshots)throws IOException{
        this.owner=owner;this.snapshots=snapshots;
        server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),8);
        Thread worker=new Thread(()->{while(!closed){try(Socket client=server.accept()){client.setSoTimeout(2000);serve(client);}catch(Exception ignored){/* No request or report values in logs. */}}},"luma-private-reports");
        worker.setDaemon(true);worker.start();
    }
    public int port(){return server.getLocalPort();}
    private void serve(Socket client)throws Exception{
        InputStream in=client.getInputStream();int[] budget={8192};String first=line(in,budget);
        Map<String,String> headers=new HashMap<>();String next;
        while(!(next=line(in,budget)).isEmpty()){
            int colon=next.indexOf(':');if(colon<1){reply(client,400,"{}");return;}
            String name=next.substring(0,colon).toLowerCase(Locale.ROOT);
            if(headers.put(name,next.substring(colon+1).trim())!=null){reply(client,400,"{}");return;}
        }
        if(headers.containsKey("origin")){reply(client,403,"{}");return;}
        PublicKey key=owner.get();if(key==null){reply(client,403,"{}");return;}
        long now=System.nanoTime()/1_000_000;
        challenges.entrySet().removeIf(e->now-e.getValue()>30000);
        if("GET /challenge HTTP/1.1".equals(first)){
            while(challenges.size()>=32)challenges.remove(challenges.keySet().iterator().next());
            byte[] random=new byte[32];new SecureRandom().nextBytes(random);
            String nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(random);challenges.put(nonce,now);
            reply(client,200,"{\"challenge\":\""+nonce+"\"}");return;
        }
        if(!"GET /reports HTTP/1.1".equals(first)){reply(client,404,"{}");return;}
        String nonce=headers.getOrDefault("x-luma-challenge","");Long created=challenges.remove(nonce);
        String auth=headers.getOrDefault("authorization","");
        if(created==null||now-created>30000||!auth.startsWith("Luma ")||!ReportCrypto.verify(key,nonce,auth.substring(5))){reply(client,401,"{}");return;}
        byte[] body=snapshots.get();reply(client,200,body);
    }
    private static String line(InputStream in,int[] budget)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();int b;
        while((b=in.read())!=-1){if(--budget[0]<0||out.size()>2048)throw new IOException("Request too large");if(b=='\n')return out.toString("US-ASCII").replace("\r","");out.write(b);}
        throw new EOFException();
    }
    private static void reply(Socket client,int code,String body)throws IOException{reply(client,code,body.getBytes(StandardCharsets.UTF_8));}
    private static void reply(Socket client,int code,byte[] body)throws IOException{
        OutputStream out=client.getOutputStream();out.write(("HTTP/1.1 "+code+" Response\r\nContent-Type: application/json\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    @Override public void close()throws IOException{closed=true;server.close();}
}
