package tv.luma;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Bounded ciphertext snapshots in Android's private, backup-excluded directory. */
public final class ReportStore {
    private final File directory,keyFile;
    private PublicKey owner;
    static final int MAX_RECORDS=2000;
    static final long RETENTION_MS=90L*24*60*60*1000;
    public ReportStore(File root)throws Exception{
        directory=new File(root,"watch-reports");keyFile=new File(root,"report-owner.der");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Report storage unavailable");
        if(keyFile.exists())owner=ReportCrypto.publicKey(Files.readAllBytes(keyFile.toPath()));
        prune(System.currentTimeMillis());
    }
    public synchronized PublicKey owner(){return owner;}
    public synchronized void pair(byte[] encoded)throws Exception{
        PublicKey candidate=ReportCrypto.publicKey(encoded);
        if(owner!=null&&!Arrays.equals(owner.getEncoded(),candidate.getEncoded()))throw new SecurityException("Revoke the existing owner before pairing another");
        atomicWrite(keyFile,candidate.getEncoded());owner=candidate;
    }
    public synchronized void revoke()throws IOException{
        owner=null;Files.deleteIfExists(keyFile.toPath());
        for(File f:files())Files.deleteIfExists(f.toPath());
    }
    public synchronized void save(String session,byte[] plaintext)throws Exception{
        if(owner==null)return;
        UUID.fromString(session); // Never use title/provider text as a path.
        atomicWrite(new File(directory,session+".rpt"),ReportCrypto.seal(owner,plaintext));
        prune(System.currentTimeMillis());
    }
    public synchronized List<byte[]> snapshots()throws IOException{
        prune(System.currentTimeMillis());List<byte[]> out=new ArrayList<>();
        for(File f:files())if(f.getName().endsWith(".rpt")&&f.length()<=18000)out.add(Files.readAllBytes(f.toPath()));
        return out;
    }
    private File[] files(){File[] value=directory.listFiles();return value==null?new File[0]:value;}
    private void prune(long now)throws IOException{
        File[] files=files();Arrays.sort(files,Comparator.comparingLong(File::lastModified));int retained=files.length;
        for(File f:files)if(f.lastModified()<now-RETENTION_MS||retained>MAX_RECORDS||f.getName().endsWith(".tmp")){Files.deleteIfExists(f.toPath());retained--;}
    }
    private static void atomicWrite(File destination,byte[] bytes)throws IOException{
        File temporary=new File(destination.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}
        Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
