package net.lanmsg.chat;
import android.security.keystore.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.KeyStore;
import java.util.Arrays;
public final class AndroidProtector implements SecureIdentity.Protector {
  private final javax.crypto.SecretKey key;
  public AndroidProtector()throws Exception{
    KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);String alias="lan-messenger-storage-v3";
    if(!store.containsAlias(alias)){KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());g.generateKey();}
    key=(javax.crypto.SecretKey)store.getKey(alias,null);
  }
  public byte[] protect(byte[] plain)throws Exception{Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);byte[] cipher=c.doFinal(plain),iv=c.getIV();byte[] result=new byte[iv.length+cipher.length];System.arraycopy(iv,0,result,0,iv.length);System.arraycopy(cipher,0,result,iv.length,cipher.length);return result;}
  public byte[] unprotect(byte[] encrypted)throws Exception{if(encrypted.length<28)throw new java.io.IOException("Invalid encrypted storage");Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(encrypted,0,12)));return c.doFinal(Arrays.copyOfRange(encrypted,12,encrypted.length));}
}
