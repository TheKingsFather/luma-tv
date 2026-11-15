package tv.luma;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Versioned hybrid encryption. Only the owner holds a decryption/signing key. */
public final class ReportCrypto {
    private static final byte[] MAGIC="LUMARPT1".getBytes(StandardCharsets.US_ASCII);
    public static final String AUTH_CONTEXT="luma-reports-v1:";
    public static PublicKey publicKey(byte[] encoded)throws GeneralSecurityException{
        PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        int bits=((RSAPublicKey)key).getModulus().bitLength();
        if(bits<3072||bits>4096)throw new InvalidKeyException("Use a 3072 or 4096 bit owner key");
        return key;
    }
    public static String fingerprint(PublicKey key)throws GeneralSecurityException{
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
        StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    public static byte[] seal(PublicKey owner,byte[] plaintext)throws GeneralSecurityException,IOException{
        if(plaintext.length>16384)throw new IOException("Report too large");
        KeyGenerator generator=KeyGenerator.getInstance("AES");generator.init(256);SecretKey aes=generator.generateKey();
        Cipher wrap=Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        wrap.init(Cipher.ENCRYPT_MODE,owner,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
        byte[] wrapped=wrap.doFinal(aes.getEncoded()),nonce=new byte[12];new SecureRandom().nextBytes(nonce);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(buffer);
        out.write(MAGIC);out.writeShort(wrapped.length);out.write(wrapped);out.write(nonce);
        byte[] header=buffer.toByteArray();
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,aes,new GCMParameterSpec(128,nonce));cipher.updateAAD(header);
        byte[] encrypted=cipher.doFinal(plaintext);out.writeInt(encrypted.length);out.write(encrypted);return buffer.toByteArray();
    }
    public static boolean verify(PublicKey key,String nonce,String encodedSignature){
        try{Signature signature=Signature.getInstance("SHA256withRSA");signature.initVerify(key);
            signature.update((AUTH_CONTEXT+nonce).getBytes(StandardCharsets.US_ASCII));
            return signature.verify(Base64.getDecoder().decode(encodedSignature));
        }catch(GeneralSecurityException|IllegalArgumentException e){return false;}
    }
    private ReportCrypto(){}
}
