package net.lanmsg.chat;

import javax.net.ssl.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.*;

/** TLS uses the platform implementation; application messages never use a custom cipher protocol. */
public final class SecureIdentity {
  public interface Protector { byte[] protect(byte[] plain)throws Exception; byte[] unprotect(byte[] encrypted)throws Exception; }
  public final SSLContext context; public final String fingerprint;
  final PrivateKey privateKey;
  public SecureIdentity(File directory,String id,Protector protector)throws Exception {
    File identity=new File(directory,"identity.sec");X509Certificate cert;
    if(identity.exists()){
      byte[] data=protector.unprotect(readFile(identity));DataInputStream in=new DataInputStream(new ByteArrayInputStream(data));
      int length=in.readInt();if(length<1||length>16000)throw new IOException("Invalid identity");byte[] key=new byte[length];in.readFully(key);
      length=in.readInt();if(length<1||length>16000)throw new IOException("Invalid certificate");byte[] encoded=new byte[length];in.readFully(encoded);
      privateKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(key));cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));
    }else{
      KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(3072);KeyPair pair=generator.generateKeyPair();privateKey=pair.getPrivate();cert=selfSigned(pair,id);
      ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);byte[] key=privateKey.getEncoded(),encoded=cert.getEncoded();out.writeInt(key.length);out.write(key);out.writeInt(encoded.length);out.write(encoded);
      File tmp=new File(identity+".tmp");try(FileOutputStream f=new FileOutputStream(tmp)){f.write(protector.protect(bytes.toByteArray()));f.getFD().sync();}if(!tmp.renameTo(identity))throw new IOException("Could not save TLS identity");
    }
    fingerprint=hash(cert.getEncoded());KeyStore store=KeyStore.getInstance("PKCS12");store.load(null,null);char[] password=UUID.randomUUID().toString().toCharArray();store.setKeyEntry("identity",privateKey,password,new java.security.cert.Certificate[]{cert});
    KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());km.init(store,password);
    // Unknown self-signed certificates are allowed ONLY to exchange pairing metadata.
    // PeerEngine gates every message and ACK on the explicitly verified certificate pin.
    X509TrustManager pairingTrust=new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{check(c);}public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{check(c);}void check(X509Certificate[] c)throws CertificateException{if(c==null||c.length==0)throw new CertificateException("Missing certificate");c[0].checkValidity();}};
    context=SSLContext.getInstance("TLS");context.init(km.getKeyManagers(),new TrustManager[]{pairingTrust},new SecureRandom());
  }
  public static String hash(byte[] data)throws Exception{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
  public static String remote(SSLSocket socket)throws Exception{return hash(socket.getSession().getPeerCertificates()[0].getEncoded());}
  public static byte[] remotePublicKey(SSLSocket socket)throws Exception{return socket.getSession().getPeerCertificates()[0].getPublicKey().getEncoded();}
  public byte[] sign(byte[] data)throws Exception{Signature s=Signature.getInstance("SHA256withRSA");s.initSign(privateKey);s.update(data);return s.sign();}
  public static boolean verify(byte[] publicKeyEncoded,byte[] data,byte[] signature){try{PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(publicKeyEncoded));Signature s=Signature.getInstance("SHA256withRSA");s.initVerify(key);s.update(data);return s.verify(signature);}catch(Exception e){return false;}}
  public static byte[] readFile(File file)throws IOException{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
  // Minimal X.509 certificate serialization, not a cryptographic primitive. Signing is JCA SHA256withRSA.
  static byte[] join(byte[]... chunks)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[] b:chunks)out.write(b);return out.toByteArray();}
  static byte[] der(int tag,byte[] value)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(tag);int n=value.length;if(n<128)out.write(n);else{int bytes=0;for(int x=n;x>0;x>>=8)bytes++;out.write(128|bytes);for(int i=bytes-1;i>=0;i--)out.write(n>>(i*8));}out.write(value);return out.toByteArray();}
  static byte[] seq(byte[]... v)throws IOException{return der(48,join(v));}
  static byte[] integer(BigInteger n)throws IOException{return der(2,n.toByteArray());}
  static X509Certificate selfSigned(KeyPair pair,String id)throws Exception{
    byte[] algorithm=seq(der(6,new byte[]{42,(byte)134,72,(byte)134,(byte)247,13,1,1,11}),der(5,new byte[0]));
    byte[] name=seq(der(49,seq(der(6,new byte[]{85,4,3}),der(12,("LAN Messenger "+id).getBytes(StandardCharsets.UTF_8)))));
    SimpleDateFormat fmt=new SimpleDateFormat("yyyyMMddHHmmss'Z'",Locale.ROOT);fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
    byte[] validity=seq(der(24,fmt.format(new Date(System.currentTimeMillis()-86400000L)).getBytes(StandardCharsets.US_ASCII)),der(24,fmt.format(new Date(System.currentTimeMillis()+315360000000L)).getBytes(StandardCharsets.US_ASCII)));
    byte[] tbs=seq(der(160,integer(BigInteger.valueOf(2))),integer(new BigInteger(128,new SecureRandom()).add(BigInteger.ONE)),algorithm,name,validity,name,pair.getPublic().getEncoded());
    Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(pair.getPrivate());signer.update(tbs);byte[] encoded=seq(tbs,algorithm,der(3,join(new byte[]{0},signer.sign())));
    X509Certificate cert=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(encoded));cert.verify(pair.getPublic());return cert;
  }
}
