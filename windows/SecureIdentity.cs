using System.Net.Security;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Runtime.InteropServices;

namespace LanMessenger;
public interface IStorageProtector { byte[] Protect(byte[] data); byte[] Unprotect(byte[] data); }
public sealed class WindowsProtector : IStorageProtector { public byte[] Protect(byte[] data)=>SecureIdentity.Protect(data);public byte[] Unprotect(byte[] data)=>SecureIdentity.Unprotect(data); }
public sealed class SecureIdentity : IDisposable
{
    public X509Certificate2 Certificate {get;}
    public string Fingerprint=>Hash(Certificate.RawData);
    public SecureIdentity(string directory,string id,IStorageProtector protector)
    {
        var path=Path.Combine(directory,"identity.sec");
        if(File.Exists(path))Certificate=X509CertificateLoader.LoadPkcs12(protector.Unprotect(File.ReadAllBytes(path)),null,X509KeyStorageFlags.EphemeralKeySet|X509KeyStorageFlags.Exportable);
        else{using var rsa=RSA.Create(3072);var req=new CertificateRequest("CN=LAN Messenger "+id,rsa,HashAlgorithmName.SHA256,RSASignaturePadding.Pkcs1);using var cert=req.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1),DateTimeOffset.UtcNow.AddYears(10));var data=cert.Export(X509ContentType.Pfx);using(var stream=new FileStream(path+".tmp",FileMode.Create,FileAccess.Write)){stream.Write(protector.Protect(data));stream.Flush(true);}File.Move(path+".tmp",path);Certificate=X509CertificateLoader.LoadPkcs12(data,null,X509KeyStorageFlags.EphemeralKeySet|X509KeyStorageFlags.Exportable);}
    }
    public static string Hash(byte[] bytes)=>Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    public byte[] Sign(byte[] data){using var rsa=Certificate.GetRSAPrivateKey()!;return rsa.SignData(data,HashAlgorithmName.SHA256,RSASignaturePadding.Pkcs1);}
    public static bool Verify(byte[] publicKey,byte[] data,byte[] signature){try{using var rsa=RSA.Create();rsa.ImportSubjectPublicKeyInfo(publicKey,out _);return rsa.VerifyData(data,signature,HashAlgorithmName.SHA256,RSASignaturePadding.Pkcs1);}catch{return false;}}
    public SecureChannel Wrap(Stream stream)=>new(stream,this);
    public async Task Authenticate(SecureChannel stream,bool server)
    {
        try{await Task.Run(()=>stream.Open(server)).WaitAsync(TimeSpan.FromSeconds(8));}
        catch{stream.Dispose();throw;}
    }
    public static string Remote(SecureChannel stream)=>stream.PeerFingerprint;
    public static byte[] RemotePublicKey(SecureChannel stream)=>stream.PeerPublicKey;
    [StructLayout(LayoutKind.Sequential)]struct Blob{public int Length;public IntPtr Data;}
    [DllImport("crypt32.dll",SetLastError=true,CharSet=CharSet.Unicode)]static extern bool CryptProtectData(ref Blob input,string? description,IntPtr entropy,IntPtr reserved,IntPtr prompt,int flags,out Blob output);
    [DllImport("crypt32.dll",SetLastError=true)]static extern bool CryptUnprotectData(ref Blob input,IntPtr description,IntPtr entropy,IntPtr reserved,IntPtr prompt,int flags,out Blob output);
    [DllImport("kernel32.dll")]static extern IntPtr LocalFree(IntPtr memory);
    static byte[] Transform(byte[] value,bool encrypt)
    {
        Blob input=new(){Length=value.Length,Data=Marshal.AllocHGlobal(value.Length)},output=default;
        try{Marshal.Copy(value,0,input.Data,value.Length);bool ok=encrypt?CryptProtectData(ref input,"LAN Messenger",IntPtr.Zero,IntPtr.Zero,IntPtr.Zero,1,out output):CryptUnprotectData(ref input,IntPtr.Zero,IntPtr.Zero,IntPtr.Zero,IntPtr.Zero,1,out output);if(!ok)throw new CryptographicException("Windows user protection unavailable: "+new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error()).Message);var result=new byte[output.Length];Marshal.Copy(output.Data,result,0,result.Length);return result;}
        finally{Marshal.FreeHGlobal(input.Data);if(output.Data!=IntPtr.Zero)LocalFree(output.Data);}
    }
    public static byte[] Protect(byte[] data)=>Transform(data,true);
    public static byte[] Unprotect(byte[] data)=>Transform(data,false);
    public void Dispose()=>Certificate.Dispose();
}
