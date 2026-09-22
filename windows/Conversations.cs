using System.Text;
namespace LanMessenger;
public sealed partial class PeerEngine
{
    public const int MaxFileSize=200*1024*1024;
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
    public void QueueFile(string conversation,string caption,string name,byte[] data)=>QueueContent(conversation,caption,SafeFileName(name),data);
    void QueueContent(string conversation,string text,string fileName,byte[]? data)
    {
        text=text.Trim();if(text.Length>2000||(data==null&&text.Length==0))throw new IOException("Messages must contain 1–2000 characters.");
        if(data!=null&&data.Length>MaxFileSize)throw new IOException($"Files must be {MaxFileSize/1024/1024} MB or smaller.");
        lock(gate){string[] recipients;string groupId="";
        if(groups.TryGetValue(conversation,out var group)){recipients=group.Members.Where(x=>x!=Id).ToArray();groupId=group.Id;}
        else if(peers.ContainsKey(conversation))recipients=[conversation];else throw new IOException("Choose a conversation first.");
        var id=Guid.NewGuid().ToString();var at=Now;var hash=data==null?"":SecureIdentity.Hash(data);
        var signature=groupId.Length>0?Convert.ToBase64String(identity.Sign(CanonicalBytes(id,groupId,Id,at,text,fileName,data?.Length??0,hash))):"";
        var batch=recipients.Select(to=>new Message(id,Id,to,text,at,"Queued",groupId,fileName,data?.Length??0,hash,signature,groupId.Length>0)).ToArray();
        if(data!=null)StoreAttachment(batch[0],data);messages.AddRange(batch);try{Save();}catch{messages.RemoveAll(m=>m.Id==id&&m.From==Id);if(data!=null)File.Delete(AttachmentPath(batch[0]));throw;}}Notify();
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
    void StoreAttachment(Message m,byte[] data)
    {
        var path=AttachmentPath(m);Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        using(var f=new FileStream(path+".tmp",FileMode.Create,FileAccess.Write)){f.Write(protector.Protect(data));f.Flush(true);}File.Move(path+".tmp",path,true);
    }
    public byte[] ReadAttachment(Message m){lock(gate){var data=protector.Unprotect(File.ReadAllBytes(AttachmentPath(m)));if(data.Length!=m.FileSize||SecureIdentity.Hash(data)!=m.FileHash)throw new IOException("Attachment integrity check failed");return data;}}
    static async Task<byte[]> ReadBytes(Stream stream,int count){var data=new byte[count];using var timeout=new CancellationTokenSource(TransferTimeout(count));await stream.ReadExactlyAsync(data,timeout.Token);return data;}
}
