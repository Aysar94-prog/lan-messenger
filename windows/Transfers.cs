using System.Security.Cryptography;
using System.Text;
using System.Collections.Concurrent;
namespace LanMessenger;
public sealed partial class PeerEngine
{
    // Logical resume segments are independent of the small streaming I/O buffer.
    public const long MaxFastFileSize=1024L*1024*1024*1024;
    const long SegmentSize=100L*1024*1024;
    readonly SemaphoreSlim fileSlots=new(2);
    readonly ConcurrentDictionary<string,CancellationTokenSource> downloads=new();
    readonly SemaphoreSlim imageSlots=new(2);
    readonly ConcurrentDictionary<string,byte> imageAttempts=new();
    public static bool IsImageAttachment(Message m)=>new[]{".jpg",".jpeg",".png",".gif",".bmp",".webp",".tif",".tiff",".heic",".heif",".avif"}.Contains(Path.GetExtension(m.FileName).ToLowerInvariant());
    void QueueImageDownloads()
    {
        if(!Running)return;
        Message[] images;lock(gate)images=messages.Where(m=>m.From!=Id&&IsImageAttachment(m)).ToArray();
        foreach(var m in images){
            var key=m.From+"/"+m.Id;
            if(HasAttachment(m)||Downloading(m)||imageAttempts.ContainsKey(key)||!Retained(m))continue;
            if(!Peers.Any(p=>p.Trusted&&p.Online&&(p.Id==m.From||(m.GroupId.Length>0&&Groups.Any(g=>g.Id==m.GroupId&&g.Members.Contains(p.Id))))))continue;
            if(!imageSlots.Wait(0))break;
            if(!imageAttempts.TryAdd(key,0)){imageSlots.Release();continue;}
            _=Task.Run(async()=>{try{await DownloadAttachmentAsync(m);}catch{}finally{imageSlots.Release();Notify();}});
        }
    }
    public bool Downloading(Message m)=>downloads.ContainsKey(m.From+"/"+m.Id);
    string SourcePath(Message m)=>AttachmentPath(m)+".source";
    string PartsPath(Message m)=>AttachmentPath(m)+".parts";
    string SegmentPath(Message m,int index)=>Path.Combine(PartsPath(m),index+".sec");
    public bool HasAttachment(Message m)=>m.FileName.Length>0&&(File.Exists(AttachmentPath(m))||File.Exists(SourcePath(m))||File.Exists(Path.Combine(PartsPath(m),"complete")));
    bool Retained(Message m){lock(gate)return !hidden.Contains(m.From+"/"+m.Id)&&messages.Any(x=>x.From==m.From&&x.Id==m.Id)&&(!m.TtlEligible||Now<=m.Time+GroupTtlMs);}
    public void CancelDownload(Message m){if(downloads.TryGetValue(m.From+"/"+m.Id,out var cancellation))cancellation.Cancel();}
    void DeleteTransfer(Message m)
    {
        CancelDownload(m);
        // Only generated, UUID-validated app-storage paths; never delete an external source.
        try{File.Delete(AttachmentPath(m));File.Delete(SourcePath(m));}catch{}
        try{if(Directory.Exists(PartsPath(m)))Directory.Delete(PartsPath(m),true);}catch{}
    }
    public async Task QueueFastFileAsync(string conversation,string caption,string path,string? displayName=null)
    {
        var info=new FileInfo(path);long size=info.Length;
        if(size>MaxFastFileSize)throw new IOException("Fast transfer limit is 1 TiB.");
        caption=caption.Trim();if(caption.Length>2000)throw new IOException("Caption too long.");
        string[] recipients;string group="";
        lock(gate){if(groups.TryGetValue(conversation,out var g)){group=g.Id;recipients=g.Members.Where(x=>x!=Id).ToArray();}else if(peers.ContainsKey(conversation))recipients=[conversation];else throw new IOException("Choose a conversation.");}
        // One read for a stable integrity identity, without an encrypted staging copy.
        string hash;using(var input=File.OpenRead(path))hash=Convert.ToHexString(await SHA256.HashDataAsync(input)).ToLowerInvariant();
        info.Refresh();if(info.Length!=size)throw new IOException("Source changed while preparing it.");
        string id=Guid.NewGuid().ToString(),name=SafeFileName(displayName??info.Name);long at=Now;
        string signature=group.Length>0?Convert.ToBase64String(identity.Sign(CanonicalBytes(id,group,Id,at,caption,name,size,hash))):"";
        var rows=recipients.Select(to=>new Message(id,Id,to,caption,at,"Queued",group,name,size,hash,signature,group.Length>0)).ToArray();
        Directory.CreateDirectory(Path.GetDirectoryName(SourcePath(rows[0]))!);
        File.WriteAllBytes(SourcePath(rows[0]),protector.Protect(Encoding.UTF8.GetBytes(Path.GetFullPath(path))));
        lock(gate){messages.AddRange(rows);try{Save();}catch{messages.RemoveAll(m=>m.Id==id);File.Delete(SourcePath(rows[0]));throw;}}
        Notify();WakeDelivery();
    }
    Stream OpenContent(Message m)
    {
        if(File.Exists(SourcePath(m)))return File.OpenRead(Encoding.UTF8.GetString(protector.Unprotect(File.ReadAllBytes(SourcePath(m)))));
        if(File.Exists(AttachmentPath(m)))return OpenAttachmentPlaintext(AttachmentPath(m));
        if(!File.Exists(Path.Combine(PartsPath(m),"complete")))throw new IOException("Download this attachment first.");
        return OpenParts(m);
    }
    Stream OpenParts(Message m,int start=0)=>new PartStream(Enumerable.Range(start,(int)Math.Max(1,(m.FileSize+SegmentSize-1)/SegmentSize)-start).Select(i=>(Func<Stream>)(()=>OpenAttachmentPlaintext(SegmentPath(m,i)))));
    sealed class PartStream(IEnumerable<Func<Stream>> parts):Stream
    {
        readonly IEnumerator<Func<Stream>> sources=parts.GetEnumerator();Stream? current;
        public override int Read(byte[] b,int o,int n){if(n==0)return 0;while(true){if(current==null){if(!sources.MoveNext())return 0;current=sources.Current();}int got=current.Read(b,o,n);if(got>0)return got;current.Dispose();current=null;}}
        protected override void Dispose(bool disposing){current?.Dispose();sources.Dispose();base.Dispose(disposing);}
        public override bool CanRead=>true;public override bool CanWrite=>false;public override bool CanSeek=>false;
        public override long Length=>throw new NotSupportedException();public override long Position{get=>throw new NotSupportedException();set=>throw new NotSupportedException();}
        public override void Flush(){} public override long Seek(long o,SeekOrigin s)=>throw new NotSupportedException();public override void SetLength(long n)=>throw new NotSupportedException();public override void Write(byte[] b,int o,int n)=>throw new NotSupportedException();
    }
    async Task ServeDownload(SecureChannel tls,string[] request,string peerId)
    {
        if(!Uuid(request[2])||!Uuid(request[3])||!long.TryParse(request[4],out var offset)||!long.TryParse(request[5],out var count))return;
        Message? m;lock(gate)m=messages.FirstOrDefault(x=>x.From==request[2]&&x.Id==request[3]&&(x.GroupId.Length>0?AllowedGroup(x.GroupId,peerId):x.From==Id&&x.To==peerId));
        if(m==null||!Retained(m)||!HasAttachment(m)||offset<0||count<0||count>SegmentSize||offset>m.FileSize||count>m.FileSize-offset||offset%SegmentSize!=0||count!=Math.Min(SegmentSize,m.FileSize-offset)){await Write(tls,"LM4\tUNAVAILABLE");return;}
        if(!await fileSlots.WaitAsync(0)){await Write(tls,"LM4\tBUSY");return;}
        try{
            bool segmented=!File.Exists(SourcePath(m))&&!File.Exists(AttachmentPath(m));
            using var source=segmented?OpenParts(m,(int)(offset/SegmentSize)):OpenContent(m);
            if(source.CanSeek){if(source.Length!=m.FileSize)throw new IOException("Source changed");source.Position=offset;}
            else{var discard=new byte[256*1024];long skipped=segmented?offset:0;while(skipped<offset){int n=await source.ReadAsync(discard.AsMemory(0,(int)Math.Min(discard.Length,offset-skipped)));if(n==0)throw new EndOfStreamException();skipped+=n;}}
            await Write(tls,$"LM4\tDATA\t{m.Id}\t{offset}\t{count}");
            var buffer=new byte[256*1024];long done=0;
            using var digest=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
            while(done<count){if(!Retained(m)||!Trusted(peerId,tls.PeerFingerprint))throw new IOException("Transfer no longer authorized");int n=await source.ReadAsync(buffer.AsMemory(0,(int)Math.Min(buffer.Length,count-done)));if(n==0)throw new EndOfStreamException();digest.AppendData(buffer,0,n);await new NetworkTimeoutStream(tls,ChunkTimeout).WriteAsync(buffer.AsMemory(0,n));done+=n;}
            await Write(tls,"LM4\tPART\t"+Convert.ToHexString(digest.GetHashAndReset()).ToLowerInvariant());
        }finally{fileSlots.Release();}
    }
    public async Task DownloadAttachmentAsync(Message m)
    {
        if(m.From==Id||HasAttachment(m))return;
        var key=m.From+"/"+m.Id;using var cancel=CancellationTokenSource.CreateLinkedTokenSource(stop.Token);
        if(!downloads.TryAdd(key,cancel))return;
        try{
            Directory.CreateDirectory(PartsPath(m));int parts=(int)Math.Max(1,(m.FileSize+SegmentSize-1)/SegmentSize);
            for(int i=0;i<parts;i++){
                long offset=i*SegmentSize,count=Math.Min(SegmentSize,m.FileSize-offset);string part=SegmentPath(m,i),pending=part+".pending";
                if(File.Exists(part)){ReportProgress(m.Id,offset+count,m.FileSize);continue;}
                while(true){
                    cancel.Token.ThrowIfCancellationRequested();if(!Retained(m))throw new IOException("Attachment cleared or expired");
                    var candidates=Peers.Where(p=>p.Trusted&&(m.GroupId.Length==0?p.Id==m.From:Groups.Any(g=>g.Id==m.GroupId&&g.Members.Contains(p.Id)))).OrderByDescending(p=>p.Id==m.From).ToArray();bool fetched=false;
                    foreach(var peer in candidates)try{
                        using var client=await Connect(peer.Host,peer.Port);using var abort=cancel.Token.Register(()=>client.Dispose());using var tls=identity.Wrap(client.GetStream());await identity.Authenticate(tls,false);
                        if(!Trusted(peer.Id,tls.PeerFingerprint))continue;
                        await Write(tls,Hello());var hello=(await Read(tls)).Split('\t');if(!ValidHello(hello)||hello[2]!=peer.Id||await Read(tls)!="LM4\tREADY")continue;
                        await Write(tls,$"LM4\tFETCH\t{m.From}\t{m.Id}\t{offset}\t{count}");
                        if(await Read(tls)!=$"LM4\tDATA\t{m.Id}\t{offset}\t{count}")continue;
                        string hash=await StoreAttachmentStream(m,new NetworkTimeoutStream(tls,ChunkTimeout),count,p=>ReportProgress(m.Id,offset+p,m.FileSize),pending);
                        if(await Read(tls)!="LM4\tPART\t"+hash){File.Delete(pending);throw new IOException("Segment integrity check failed");}
                        if(!Retained(m)||!Trusted(peer.Id,tls.PeerFingerprint)){File.Delete(pending);throw new IOException("Attachment no longer authorized");}
                        File.Move(pending,part,true);fetched=true;break;
                    }catch{try{File.Delete(pending);}catch{} cancel.Token.ThrowIfCancellationRequested();}
                    if(fetched)break;
                    await Task.Delay(2000,cancel.Token); // Consent persists until Pause/Cancel, even while peers are offline.
                }
            }
            using(var plain=OpenParts(m)){
                var hash=Convert.ToHexString(await SHA256.HashDataAsync(plain,cancel.Token)).ToLowerInvariant();
                if(hash!=m.FileHash){Directory.Delete(PartsPath(m),true);throw new IOException("Source changed or attachment is corrupt. Retry download.");}
            }
            if(!Retained(m))throw new IOException("Attachment cleared or expired");
            cancel.Token.ThrowIfCancellationRequested();File.WriteAllText(Path.Combine(PartsPath(m),"complete"),m.FileHash);Notify();
        }finally{downloads.TryRemove(key,out _);Notify();}
    }
}
