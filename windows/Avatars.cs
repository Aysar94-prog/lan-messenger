namespace LanMessenger;
public sealed partial class PeerEngine
{
    string AvatarPath=>Path.Combine(Path.GetDirectoryName(file)!,"avatar.sec");
    public byte[]? Avatar{get{lock(gate){if(!File.Exists(AvatarPath))return null;try{return protector.Unprotect(File.ReadAllBytes(AvatarPath));}catch{return null;}}}}
    public void SetAvatar(byte[]? data)
    {
        lock(gate){var path=AvatarPath;if(data==null){if(File.Exists(path))File.Delete(path);Notify();return;}
        var tmp=path+".tmp";File.WriteAllBytes(tmp,protector.Protect(data));File.Move(tmp,path,true);}Notify();
    }
    // A peer's photo, once they've sent it over a verified connection — never fetched or guessed, only what they pushed us.
    string PeerAvatarPath(string peerId)=>Path.Combine(Path.GetDirectoryName(file)!,"avatars",peerId+".sec");
    public byte[]? PeerAvatar(string peerId){lock(gate){var path=PeerAvatarPath(peerId);if(!File.Exists(path))return null;try{return protector.Unprotect(File.ReadAllBytes(path));}catch{return null;}}}
    void SetPeerAvatar(string peerId,byte[]? data)
    {
        var path=PeerAvatarPath(peerId);Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        if(data==null||data.Length==0){if(File.Exists(path))File.Delete(path);return;}
        var tmp=path+".tmp";File.WriteAllBytes(tmp,protector.Protect(data));File.Move(tmp,path,true);
    }
    const int MaxAvatarSize=2_000_000;
    async Task HandleAvatar(SecureChannel tls,string senderId,string hash,string lengthText)
    {
        if(!int.TryParse(lengthText,out var length)||length<0||length>MaxAvatarSize)return;
        var data=length>0?await ReadBytes(tls,length):Array.Empty<byte>();
        if(length>0&&SecureIdentity.Hash(data)!=hash)return;
        lock(gate)if(!peers.TryGetValue(senderId,out var p)||!p.Trusted)return;
        SetPeerAvatar(senderId,length>0?data:null);
        lock(gate){if(!peers.TryGetValue(senderId,out var p))return;var old=p;peers[senderId]=p with{ReceivedAvatarHash=hash};try{Save();}catch{peers[senderId]=old;throw;}}
        await Write(tls,$"LM4\tAVATARACK\t{hash}");
    }
    // Pushes our profile picture to a trusted peer only when it differs from what they last
    // acknowledged; called as the final step of Deliver for that peer.
    async Task PushAvatarIfChanged(Peer peer)
    {
        if(!peer.Trusted)return;
        var avatar=Avatar;var hash=avatar!=null?SecureIdentity.Hash(avatar):"";
        if(peer.SentAvatarHash==hash)return;
        try{
            using var c=await Connect(peer.Host,peer.Port);using var tls=identity.Wrap(c.GetStream());await identity.Authenticate(tls,false);var fingerprint=SecureIdentity.Remote(tls);if(!Trusted(peer.Id,fingerprint))return;
            await Write(tls,Hello());var h=(await Read(tls)).Split('\t');if(!ValidHello(h)||h[2]!=peer.Id)return;RecordCertificate(h[2],fingerprint,SecureIdentity.RemotePublicKey(tls));if(await Read(tls)!="LM4\tREADY"||!Trusted(peer.Id,fingerprint))return;
            await Write(tls,$"LM4\tAVATAR\t{Id}\t{hash}\t{avatar?.Length??0}");
            if(avatar!=null&&avatar.Length>0){using var timeout=new CancellationTokenSource(TransferTimeout(avatar.Length));await tls.WriteAsync(avatar,timeout.Token);}
            if(await Read(tls)!=$"LM4\tAVATARACK\t{hash}"||!Trusted(peer.Id,fingerprint))return;
            lock(gate){if(!peers.TryGetValue(peer.Id,out var p))return;var old=p;peers[peer.Id]=p with{SentAvatarHash=hash};try{Save();}catch{peers[peer.Id]=old;throw;}}Notify();
        }catch{return;}
    }
}
