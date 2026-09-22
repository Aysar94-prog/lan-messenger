using System.Security.Cryptography;
using System.Text;
namespace LanMessenger;
public sealed partial class PeerEngine
{
    public const int MaxFileSize=1024*1024*1024;
    // Chunk size for every streamed read/write (local store, export, network) — attachments up to
    // MaxFileSize are never buffered whole in memory at any step; peak memory stays near this size.
    const int ChunkSize=3*1024*1024;
    static readonly byte[] AttachmentMagic=Encoding.ASCII.GetBytes("LMATCS1");
    public sealed record Group(string Id,string Owner,string Name,string[] Members,string Acknowledged="");
    readonly Dictionary<string,Group> groups=[];
    readonly HashSet<string> hidden=[];
    public Group[] Groups {get{lock(gate)return groups.Values.Select(g=>g with{Members=g.Members.ToArray()}).ToArray();}}
    public string DisplayName(string id){lock(gate)return id==Id?Name:groups.TryGetValue(id,out var g)?g.Name:peers.TryGetValue(id,out var p)?p.Name:"Device "+id[..Math.Min(8,id.Length)];}
    public string CreateGroup(string name,IEnumerable<string> members)
    {
        name=name.Trim();var ids=members.Append(Id).Distinct().OrderBy(x=>x,StringComparer.Ordinal).ToArray();
        lock(gate){if(name.Length is <1 or >50||ids.Length is <3 or >16||ids.Any(x=>x!=Id&&(!peers.TryGetValue(x,out var p)||!p.Trusted)))throw new IOException("Name the group and select 2–15 verified contacts.");
        var g=new Group(Guid.NewGuid().ToString(),Id,name,ids);groups.Add(g.Id,g);try{Save();}catch{groups.Remove(g.Id);throw;}Notify();return g.Id;}
    }
    bool AllowedGroup(string id,string sender)=>id.Length==0||(groups.TryGetValue(id,out var g)&&g.Members.Contains(Id)&&g.Members.Contains(sender));
    void AcceptGroup(string[] a,string sender,string fingerprint)
    {
        var members=a[5].Split(',');var name=Dec(a[4]);
        lock(gate){if(!Trusted(sender,fingerprint)||!Uuid(a[2])||a[3]!=sender||name.Trim().Length==0||name.Length>50||members.Length is <3 or >16||members.Distinct().Count()!=members.Length||members.Any(x=>!Uuid(x))||!members.Contains(Id)||!members.Contains(sender)||peers.ContainsKey(a[2])||a[2]==Id)throw new IOException("Invalid group invitation");
        if(groups.TryGetValue(a[2],out var old)){if(old.Owner!=sender||old.Name!=name||!old.Members.SequenceEqual(members))throw new IOException("Group membership cannot be replaced");return;}
        groups[a[2]]=new(a[2],sender,name,members);try{Save();}catch{groups.Remove(a[2]);throw;}}
    }
    public void QueueFile(string conversation,string name,byte[] data)=>QueueFile(conversation,"",name,data);
    public void QueueFile(string conversation,string caption,string name,byte[] data)
        =>QueueContentAsync(conversation,caption,SafeFileName(name),new MemoryStream(data),data.Length,null).GetAwaiter().GetResult();
    // Streams straight from disk into the encrypted attachment store — the file is never held
    // whole in memory, so this is what a large (up to 1 GB) attachment must go through.
    // `displayName` lets the caller keep whatever name it already showed the user in the draft
    // preview (e.g. a camera capture's generated name) instead of always re-deriving one from
    // the source path.
    public async Task QueueFileFromPathAsync(string conversation,string caption,string sourcePath,Action<long>? onProgress=null,string? displayName=null)
    {
        var info=new FileInfo(sourcePath);if(!info.Exists)throw new IOException("File not found.");
        var name=SafeFileName(displayName??Path.GetFileName(sourcePath));
        if(info.Length>MaxFileSize)throw new IOException($"Files must be {MaxFileSize/1024/1024} MB or smaller.");
        await QueueContentAsync(conversation,caption,name,File.OpenRead(sourcePath),(int)info.Length,onProgress);
    }
    async Task QueueContentAsync(string conversation,string text,string fileName,Stream? data,int declaredSize,Action<long>? onProgress)
    {
        text=text.Trim();if(text.Length>2000||(data==null&&text.Length==0)){data?.Dispose();throw new IOException("Messages must contain 1–2000 characters.");}
        if(data!=null&&declaredSize>MaxFileSize){data.Dispose();throw new IOException($"Files must be {MaxFileSize/1024/1024} MB or smaller.");}
        string[] recipients;string groupId="";
        lock(gate){
            if(groups.TryGetValue(conversation,out var group)){recipients=group.Members.Where(x=>x!=Id).ToArray();groupId=group.Id;}
            else if(peers.ContainsKey(conversation))recipients=[conversation];
            else{data?.Dispose();throw new IOException("Choose a conversation first.");}
        }
        var id=Guid.NewGuid().ToString();var at=Now;var hash="";
        if(data!=null)using(data){
            var placeholder=new Message(id,Id,recipients[0],text,at,"Queued",groupId,fileName,declaredSize,"");
            hash=await StoreAttachmentStream(placeholder,data,declaredSize,onProgress);
        }
        var signature=groupId.Length>0?Convert.ToBase64String(identity.Sign(CanonicalBytes(id,groupId,Id,at,text,fileName,declaredSize,hash))):"";
        var batch=recipients.Select(to=>new Message(id,Id,to,text,at,"Queued",groupId,fileName,declaredSize,hash,signature,groupId.Length>0)).ToArray();
        lock(gate){messages.AddRange(batch);try{Save();}catch{messages.RemoveAll(m=>m.Id==id&&m.From==Id);if(fileName.Length>0)try{File.Delete(AttachmentPath(batch[0]));}catch{}throw;}}
        Notify();
    }
    public void ClearConversation(string conversation)
    {
        lock(gate){var removed=messages.Where(m=>m.GroupId.Length>0?m.GroupId==conversation:m.From==conversation||m.To==conversation).ToArray();
        var old=messages.ToArray();var oldHidden=hidden.ToArray();foreach(var m in removed)hidden.Add(m.From+"/"+m.Id);messages.RemoveAll(m=>removed.Contains(m));
        try{Save();}catch{messages.Clear();messages.AddRange(old);hidden.Clear();hidden.UnionWith(oldHidden);throw;}
        Save(); // Replace the backup too; cleared content must not return on recovery.
        foreach(var m in removed.Where(m=>m.FileName.Length>0))try{File.Delete(AttachmentPath(m));}catch{}}
        Notify();
    }
    public static string SafeFileName(string name)
    {
        name=name.Replace('\\','/').Split('/').Last();name=new string(name.Where(c=>c>=32&&!"<>:\"/\\|?*".Contains(c)).ToArray()).Trim().Trim('.');return name.Length==0?"attachment":name[..Math.Min(120,name.Length)];
    }
    static void ValidateFile(string name,int size,string hash)
    {
        if(size<0||size>MaxFileSize||(name.Length==0?(size!=0||hash.Length!=0):(name!=SafeFileName(name)||hash.Length!=64||hash.Any(c=>!"0123456789abcdef".Contains(c)))))throw new IOException("Invalid attachment metadata");
    }
    string AttachmentPath(Message m){if(!Uuid(m.From)||!Uuid(m.Id))throw new IOException("Invalid attachment ID");return Path.Combine(Path.GetDirectoryName(file)!,"attachments",m.From+"-"+m.Id+".sec");}
    // Encrypts a stream of plaintext into the attachment file without ever buffering the whole
    // content in memory — required now that attachments can be up to 1 GB. A random per-attachment
    // AES-256-CBC key/IV (itself wrapped by the small, one-shot OS storage protector) replaces
    // protecting the whole blob at once. This is confidentiality-only at rest (no per-chunk
    // authentication); the existing end-to-end SHA-256 hash still catches corruption/tampering,
    // same as before, and the TLS channel content travels over is itself authenticated.
    // `totalBytes` is read as an exact count, not "until EOF" — required for a network source,
    // which has no natural end-of-stream mid-connection (more protocol frames follow after it).
    // A FileStream/MemoryStream source works the same way since its length is already known too.
    async Task<string> StoreAttachmentStream(Message m,Stream source,long totalBytes,Action<long>? onProgress)
    {
        var path=AttachmentPath(m);Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var tmp=path+".tmp";
        using var aes=Aes.Create();aes.KeySize=256;aes.GenerateKey();aes.GenerateIV();
        var header=protector.Protect([..aes.Key,..aes.IV]);
        using var sha=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);long total=0;
        using(var f=new FileStream(tmp,FileMode.Create,FileAccess.Write))
        {
            f.Write(AttachmentMagic);
            var lengthBytes=BitConverter.GetBytes(header.Length);if(BitConverter.IsLittleEndian)Array.Reverse(lengthBytes);
            f.Write(lengthBytes);f.Write(header);
            using(var crypto=new CryptoStream(f,aes.CreateEncryptor(),CryptoStreamMode.Write,leaveOpen:true))
            {
                var buffer=new byte[ChunkSize];
                while(total<totalBytes)
                {
                    int want=(int)Math.Min(ChunkSize,totalBytes-total);
                    int n=await source.ReadAsync(buffer.AsMemory(0,want));
                    if(n<=0)throw new EndOfStreamException("Attachment transfer ended early");
                    sha.AppendData(buffer,0,n);await crypto.WriteAsync(buffer.AsMemory(0,n));
                    total+=n;onProgress?.Invoke(total);
                }
                await crypto.FlushFinalBlockAsync();
            }
            f.Flush(true);
        }
        try{File.Move(tmp,path,true);}catch{try{File.Delete(tmp);}catch{}throw;}
        return Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant();
    }
    void StoreAttachment(Message m,byte[] data)=>StoreAttachmentStream(m,new MemoryStream(data),data.Length,null).GetAwaiter().GetResult();
    static bool IsStreamedFormat(FileStream file)
    {
        if(file.Length<AttachmentMagic.Length)return false;
        var head=new byte[AttachmentMagic.Length];int read=file.Read(head,0,head.Length);file.Position=0;
        return read==head.Length&&head.AsSpan().SequenceEqual(AttachmentMagic);
    }
    // Opens the attachment's decrypted plaintext as a stream, transparently handling both the
    // current streaming format and attachments stored by versions before it (a single whole-file
    // Unprotect — the only way to read those, since they predate the per-attachment key/IV header).
    Stream OpenAttachmentPlaintext(string path)
    {
        var f=new FileStream(path,FileMode.Open,FileAccess.Read);
        if(!IsStreamedFormat(f)){f.Dispose();return new MemoryStream(protector.Unprotect(File.ReadAllBytes(path)));}
        f.Position=AttachmentMagic.Length;
        var lengthBytes=new byte[4];f.ReadExactly(lengthBytes);if(BitConverter.IsLittleEndian)Array.Reverse(lengthBytes);int headerLen=BitConverter.ToInt32(lengthBytes);
        var header=new byte[headerLen];f.ReadExactly(header);
        var raw=protector.Unprotect(header);
        using var aes=Aes.Create();aes.KeySize=256;aes.Key=raw[..32];aes.IV=raw[32..48];
        return new CryptoStream(f,aes.CreateDecryptor(),CryptoStreamMode.Read);
    }
    // Small attachments and callers that need a byte[] (thumbnails, exports below a threshold,
    // tests). Not used for the actual network send/receive path — that streams, see below.
    public byte[] ReadAttachment(Message m)
    {
        using var plain=OpenAttachmentPlaintext(AttachmentPath(m));
        using var buffer=new MemoryStream();plain.CopyTo(buffer);var data=buffer.ToArray();
        if(data.Length!=m.FileSize||SecureIdentity.Hash(data)!=m.FileHash)throw new IOException("Attachment integrity check failed");
        return data;
    }
    // Decrypts straight to `destination` (e.g. an exported file on disk, or the network) without
    // ever buffering the whole attachment in memory. Verifies size+hash only once fully streamed,
    // matching ReadAttachment's guarantee.
    async Task ReadAttachmentStream(Message m,Stream destination,Action<long>? onProgress)
    {
        var path=AttachmentPath(m);
        using var sha=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);long total=0;
        using(var plain=OpenAttachmentPlaintext(path))
        {
            var buffer=new byte[ChunkSize];int n;
            while((n=await plain.ReadAsync(buffer))>0)
            {
                sha.AppendData(buffer,0,n);await destination.WriteAsync(buffer.AsMemory(0,n));
                total+=n;onProgress?.Invoke(total);
            }
        }
        if(total!=m.FileSize||Convert.ToHexString(sha.GetHashAndReset()).ToLowerInvariant()!=m.FileHash)throw new IOException("Attachment integrity check failed");
    }
    // Exports an attachment straight to a destination file on disk, streaming (no full in-memory
    // buffer) so a near-1 GB export doesn't require the whole thing in RAM either.
    public async Task ExportAttachmentAsync(Message m,string destinationPath,Action<long>? onProgress=null)
    {
        var tmp=destinationPath+".lmtmp";
        try{await using(var dest=new FileStream(tmp,FileMode.Create,FileAccess.Write))await ReadAttachmentStream(m,dest,onProgress);File.Move(tmp,destinationPath,true);}
        catch{try{File.Delete(tmp);}catch{}throw;}
    }
    static async Task<byte[]> ReadBytes(Stream stream,int count){var data=new byte[count];using var timeout=new CancellationTokenSource(TransferTimeout(count));await stream.ReadExactlyAsync(data,timeout.Token);return data;}
}
