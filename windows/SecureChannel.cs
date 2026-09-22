using Org.BouncyCastle.Tls;
using Org.BouncyCastle.Tls.Crypto;
using Org.BouncyCastle.Tls.Crypto.Impl.BC;
using Org.BouncyCastle.Security;
using Org.BouncyCastle.Asn1.X509;
using System.Security.Cryptography.X509Certificates;
using System.Security.Cryptography;
using BcCertificate=Org.BouncyCastle.Tls.Certificate;
using CertificateRequest=Org.BouncyCastle.Tls.CertificateRequest;

namespace LanMessenger;
/// <summary>Standard mutually authenticated TLS 1.2, ECDHE-RSA + AES-GCM. Pins are enforced by PeerEngine.</summary>
public sealed class SecureChannel : Stream
{
    readonly Stream network;
    readonly SecureIdentity identity;
    TlsProtocol? protocol;
    Stream Application=>protocol?.Stream??throw new IOException("TLS handshake not complete");
    public string PeerFingerprint {get;private set;}="";
    public byte[] PeerPublicKey {get;private set;}=[];
    public SecureChannel(Stream network,SecureIdentity identity){this.network=network;this.identity=identity;if(network.CanTimeout){network.ReadTimeout=6000;network.WriteTimeout=6000;}}
    public void Open(bool server)
    {
        if(server){var p=new TlsServerProtocol(network);protocol=p;p.Accept(new Server(this));}
        else{var p=new TlsClientProtocol(network);protocol=p;p.Connect(new Client(this));}
        if(PeerFingerprint.Length!=64)throw new IOException("Peer certificate required");
    }
    void Inspect(BcCertificate chain)
    {
        if(chain==null||chain.IsEmpty)throw new TlsFatalAlert(AlertDescription.bad_certificate);
        byte[] encoded=chain.GetCertificateAt(0).GetEncoded();using var cert=X509CertificateLoader.LoadCertificate(encoded);
        if(DateTime.UtcNow<cert.NotBefore.ToUniversalTime()||DateTime.UtcNow>cert.NotAfter.ToUniversalTime())throw new TlsFatalAlert(AlertDescription.certificate_expired);
        PeerFingerprint=SecureIdentity.Hash(encoded);PeerPublicKey=cert.GetRSAPublicKey()!.ExportSubjectPublicKeyInfo();
    }
    TlsCredentialedSigner Signer(TlsContext context,BcTlsCrypto crypto)
    {
        using var rsa=identity.Certificate.GetRSAPrivateKey()!;
        var key=PrivateKeyFactory.CreateKey(rsa.ExportPkcs8PrivateKey());
        var chain=new BcCertificate(new[]{crypto.CreateCertificate(identity.Certificate.RawData)});
        return new BcDefaultTlsCredentialedSigner(new TlsCryptoParameters(context),crypto,key,chain,new SignatureAndHashAlgorithm(Org.BouncyCastle.Tls.HashAlgorithm.sha256,SignatureAlgorithm.rsa));
    }
    static int[] Suites=>new[]{CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256};
    sealed class Server : DefaultTlsServer
    {
        readonly SecureChannel owner;
        public Server(SecureChannel owner):base(new BcTlsCrypto()){this.owner=owner;}
        protected override ProtocolVersion[] GetSupportedVersions()=>new[]{ProtocolVersion.TLSv12};
        protected override int[] GetSupportedCipherSuites()=>Suites;
        protected override TlsCredentialedSigner GetRsaSignerCredentials()=>owner.Signer(m_context,(BcTlsCrypto)Crypto);
        public override CertificateRequest GetCertificateRequest()=>new(new short[]{ClientCertificateType.rsa_sign},new List<SignatureAndHashAlgorithm>{new(Org.BouncyCastle.Tls.HashAlgorithm.sha256,SignatureAlgorithm.rsa)},new List<X509Name>());
        public override void NotifyClientCertificate(BcCertificate chain){owner.Inspect(chain);TlsUtilities.CheckPeerSigAlgs(m_context,chain.GetCertificateList());}
    }
    sealed class Client : DefaultTlsClient
    {
        readonly SecureChannel owner;
        public Client(SecureChannel owner):base(new BcTlsCrypto()){this.owner=owner;}
        protected override ProtocolVersion[] GetSupportedVersions()=>new[]{ProtocolVersion.TLSv12};
        protected override int[] GetSupportedCipherSuites()=>Suites;
        public override TlsAuthentication GetAuthentication()=>new Authentication(owner,m_context,(BcTlsCrypto)Crypto);
    }
    sealed class Authentication : TlsAuthentication
    {
        readonly SecureChannel owner;readonly TlsContext context;readonly BcTlsCrypto crypto;
        public Authentication(SecureChannel owner,TlsContext context,BcTlsCrypto crypto){this.owner=owner;this.context=context;this.crypto=crypto;}
        public void NotifyServerCertificate(TlsServerCertificate certificate){owner.Inspect(certificate.Certificate);TlsUtilities.CheckPeerSigAlgs(context,certificate.Certificate.GetCertificateList());}
        public TlsCredentials GetClientCredentials(CertificateRequest request)=>owner.Signer(context,crypto);
    }
    public override bool CanRead=>true;public override bool CanWrite=>true;public override bool CanSeek=>false;
    public override long Length=>throw new NotSupportedException();public override long Position{get=>throw new NotSupportedException();set=>throw new NotSupportedException();}
    public override int Read(byte[] buffer,int offset,int count)=>Application.Read(buffer,offset,count);
    public override void Write(byte[] buffer,int offset,int count)=>Application.Write(buffer,offset,count);
    public override void Flush()=>Application.Flush();
    public override long Seek(long offset,SeekOrigin origin)=>throw new NotSupportedException();public override void SetLength(long value)=>throw new NotSupportedException();
    protected override void Dispose(bool disposing){if(disposing){try{protocol?.Close();}catch{}network.Dispose();}base.Dispose(disposing);}
}
