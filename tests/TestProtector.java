package net.lanmsg.chat;
import java.io.*;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
/** Test fixture only. Android production uses a non-exportable Android Keystore AES key. */
public final class TestProtector implements SecureIdentity.Protector {
 private final byte[] key;
 public TestProtector(File directory)throws Exception{directory.mkdirs();File f=new File(directory,"TEST-ONLY.key");if(f.exists())key=SecureIdentity.readFile(f);else{key=new byte[32];new SecureRandom().nextBytes(key);try(FileOutputStream out=new FileOutputStream(f)){out.write(key);}}}
 public byte[] protect(byte[] plain)throws Exception{byte[] iv=new byte[12];new SecureRandom().nextBytes(iv);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));return SecureIdentity.join(iv,c.doFinal(plain));}
 public byte[] unprotect(byte[] data)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(data,0,12)));return c.doFinal(Arrays.copyOfRange(data,12,data.length));}
}
