package tv.luma;

import org.junit.*;
import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public class PrivateReportsTest {
    private static KeyPair key;
    @BeforeClass public static void key()throws Exception{KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(3072);key=g.generateKeyPair();}
    @Test public void countsWallTimeAcrossPauseAndBufferTransitions(){
        WatchClock c=new WatchClock();c.update(0,true);c.update(1000,true);c.update(1400,false);c.update(2400,false);c.update(3100,true);c.update(4100,true);
        assertEquals(2400,c.total());assertEquals(2400,c.drain());assertEquals(0,c.drain());
    }
    @Test public void seekingAndSpeedDoNotSupplyViewingTimeAndSuspensionIsExcluded(){
        WatchClock c=new WatchClock();c.update(0,true);c.update(1000,true);c.update(2000,true);assertEquals(2000,c.total());
        c.update(60000,true);assertEquals(2000,c.total());c.update(61000,false);assertEquals(3000,c.total());
        c.update(60000,true);assertEquals(3000,c.total());c.reset();assertEquals(0,c.drain());
    }
    @Test public void encryptionIsRandomizedDecryptableAndRejectsTampering()throws Exception{
        byte[] plain="{\"title\":\"Private fixture\",\"watched_ms\":1234}".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted=ReportCrypto.seal(key.getPublic(),plain);
        assertArrayEquals(plain,decrypt(encrypted));assertFalse(Arrays.equals(encrypted,ReportCrypto.seal(key.getPublic(),plain)));
        assertFalse(new String(encrypted,StandardCharsets.ISO_8859_1).contains("Private fixture"));
        encrypted[encrypted.length-1]^=1;try{decrypt(encrypted);fail("Tampered ciphertext accepted");}catch(AEADBadTagException expected){}
    }
    @Test public void refusesSmallOwnerKeys()throws Exception{
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);
        try{ReportCrypto.publicKey(g.generateKeyPair().getPublic().getEncoded());fail("Weak key accepted");}catch(InvalidKeyException expected){}
    }
    @Test public void snapshotsReplacePerSessionAndOwnerCannotBeSilentlyChanged()throws Exception{
        Path root=Files.createTempDirectory("luma-reports-");
        try{
            ReportStore store=new ReportStore(root.toFile());assertNull(store.owner());store.pair(key.getPublic().getEncoded());
            String id=UUID.randomUUID().toString();store.save(id,"first".getBytes());store.save(id,"second".getBytes());
            assertEquals(1,store.snapshots().size());assertArrayEquals("second".getBytes(),decrypt(store.snapshots().get(0)));
            ReportStore reopened=new ReportStore(root.toFile());assertEquals(ReportCrypto.fingerprint(key.getPublic()),ReportCrypto.fingerprint(reopened.owner()));
            KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(3072);
            try{store.pair(g.generateKeyPair().getPublic().getEncoded());fail("Owner replaced");}catch(SecurityException expected){}
            store.revoke();assertNull(store.owner());assertTrue(store.snapshots().isEmpty());assertNull(new ReportStore(root.toFile()).owner());
        }finally{delete(root);}
    }
    @Test public void expiresOldRecordsAndBoundsStorage()throws Exception{
        Path root=Files.createTempDirectory("luma-retention-");
        try{
            File folder=root.resolve("watch-reports").toFile();assertTrue(folder.mkdir());
            for(int n=0;n<2002;n++){File file=new File(folder,n+".rpt");Files.write(file.toPath(),new byte[]{1});}
            File expired=new File(folder,"expired.rpt");Files.write(expired.toPath(),new byte[]{1});assertTrue(expired.setLastModified(System.currentTimeMillis()-ReportStore.RETENTION_MS-10000));
            ReportStore store=new ReportStore(root.toFile());assertFalse(expired.exists());assertTrue(store.snapshots().size()<=2000);
        }finally{delete(root);}
    }
    @Test public void serverRequiresOwnerSignatureAndRejectsReplayAndOrigins()throws Exception{
        try(ReportServer server=new ReportServer(0,()->key.getPublic(),()->"{\"records\":[]}".getBytes())){
            assertTrue(request(server.port(),"/reports","").startsWith("HTTP/1.1 401"));
            String response=request(server.port(),"/challenge","");String body=response.substring(response.indexOf("\r\n\r\n")+4);
            String nonce=body.substring(14,body.length()-2);assertEquals(43,nonce.length());
            Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(key.getPrivate());signature.update((ReportCrypto.AUTH_CONTEXT+nonce).getBytes(StandardCharsets.US_ASCII));
            String headers="X-Luma-Challenge: "+nonce+"\r\nAuthorization: Luma "+Base64.getEncoder().encodeToString(signature.sign())+"\r\n";
            assertTrue(request(server.port(),"/reports",headers).startsWith("HTTP/1.1 200"));
            assertTrue(request(server.port(),"/reports",headers).startsWith("HTTP/1.1 401"));
            assertTrue(request(server.port(),"/challenge","Origin: https://untrusted.invalid\r\n").startsWith("HTTP/1.1 403"));
        }
    }
    @Test public void serverRejectsWrongSignatureAndUnpairedRequests()throws Exception{
        try(ReportServer server=new ReportServer(0,()->key.getPublic(),()->new byte[0])){
            String response=request(server.port(),"/challenge","");String body=response.substring(response.indexOf("\r\n\r\n")+4);String nonce=body.substring(14,body.length()-2);
            assertTrue(request(server.port(),"/reports","X-Luma-Challenge: "+nonce+"\r\nAuthorization: Luma aW52YWxpZA==\r\n").startsWith("HTTP/1.1 401"));
        }
        try(ReportServer server=new ReportServer(0,()->null,()->new byte[0])){assertTrue(request(server.port(),"/challenge","").startsWith("HTTP/1.1 403"));}
    }
    private static String request(int port,String path,String headers)throws IOException{
        try(Socket socket=new Socket("127.0.0.1",port)){socket.setSoTimeout(3000);socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: localhost\r\n"+headers+"\r\n").getBytes(StandardCharsets.US_ASCII));return new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);}
    }
    private static byte[] decrypt(byte[] envelope)throws Exception{
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(envelope));byte[] magic=in.readNBytes(8);assertEquals("LUMARPT1",new String(magic,StandardCharsets.US_ASCII));int length=in.readUnsignedShort();byte[] wrapped=in.readNBytes(length),nonce=in.readNBytes(12);
        Cipher wrap=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");wrap.init(Cipher.DECRYPT_MODE,key.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(wrap.doFinal(wrapped),"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(Arrays.copyOf(envelope,10+length+12));return cipher.doFinal(in.readNBytes(in.readInt()));
    }
    private static void delete(Path root)throws IOException{try(java.util.stream.Stream<Path> paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
}
