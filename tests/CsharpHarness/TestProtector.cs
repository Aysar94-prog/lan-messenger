using System.Security.Cryptography;
using LanMessenger;
// Test-only portable protector. The Windows application always uses WindowsProtector (DPAPI).
public sealed class TestProtector : IStorageProtector
{
    readonly byte[] key;
    public TestProtector(string directory){Directory.CreateDirectory(directory);var path=Path.Combine(directory,"TEST-ONLY.key");if(File.Exists(path))key=File.ReadAllBytes(path);else{key=RandomNumberGenerator.GetBytes(32);File.WriteAllBytes(path,key);}}
    public byte[] Protect(byte[] data){var nonce=RandomNumberGenerator.GetBytes(12);var ciphertext=new byte[data.Length];var tag=new byte[16];using var aes=new AesGcm(key,16);aes.Encrypt(nonce,data,ciphertext,tag);return nonce.Concat(ciphertext).Concat(tag).ToArray();}
    public byte[] Unprotect(byte[] data){var plain=new byte[data.Length-28];using var aes=new AesGcm(key,16);aes.Decrypt(data.AsSpan(0,12),data.AsSpan(12,data.Length-28),data.AsSpan(data.Length-16),plain);return plain;}
}
