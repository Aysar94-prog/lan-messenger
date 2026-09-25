using System.Text;
namespace LanMessenger;
public sealed partial class PeerEngine
{
    void Load(string source)
    {
        var stored=File.ReadAllBytes(source);if(stored.AsSpan().StartsWith(StorageMagic))stored=protector.Unprotect(stored[StorageMagic.Length..]);
        var lines=Encoding.UTF8.GetString(stored).TrimEnd('\n','\r').Replace("\r","").Split('\n');if(lines.Length<2||lines[^1]!="END")throw new IOException("Incomplete storage");
        var h=lines[0].Split('\t');if(h.Length!=3||(h[0]!="LMSTORE2"&&h[0]!="LMSTORE3"&&h[0]!="LMSTORE4")||!Uuid(h[1]))throw new IOException("Invalid storage");
        var loadedPeers=new Dictionary<string,Peer>();var loadedMessages=new List<Message>();var loadedGroups=new Dictionary<string,Group>();var loadedHidden=new HashSet<string>();
        foreach(var line in lines.Skip(1).SkipLast(1)){var a=line.Split('\t');
            if((a.Length==5||a.Length==7||a.Length==8||a.Length==10)&&a[0]=="P")loadedPeers[a[1]]=new(a[1],Dec(a[2]),a[3],int.Parse(a[4]),0,a.Length>=7?a[5]:"",a.Length>=7?a[6]:"",a.Length>=8?a[7]:"",a.Length==10?a[8]:"",a.Length==10?a[9]:"");
            else if((a.Length==8||a.Length==12||a.Length==14)&&a[0]=="M")loadedMessages.Add(new(a[1],a[2],a[3],Dec(a[5]),long.Parse(a[4]),a[6],a.Length>=12?a[8]:"",a.Length>=12?Dec(a[9]):"",a.Length>=12?long.Parse(a[10]):0,a.Length>=12?a[11]:"",a.Length==14?a[12]:"",a.Length==14&&a[13]=="1"));
            else if(a.Length==6&&a[0]=="G")loadedGroups[a[1]]=new(a[1],a[2],Dec(a[3]),a[4].Split(','),a[5]);
            else if(a.Length==2&&a[0]=="H")loadedHidden.Add(a[1]);
            else throw new IOException("Invalid storage row");}
        groups.Clear();foreach(var g in loadedGroups)groups[g.Key]=g.Value;hidden.Clear();hidden.UnionWith(loadedHidden);
        Id=h[1];Name=Dec(h[2]);peers.Clear();foreach(var pair in loadedPeers)peers[pair.Key]=pair.Value;messages.Clear();messages.AddRange(loadedMessages);
    }
    void Save()
    {
        lock(gate){var text=new StringBuilder($"LMSTORE4\t{Id}\t{Enc(Name)}\n");
        foreach(var p in peers.Values)text.Append($"P\t{p.Id}\t{Enc(p.Name)}\t{p.Host}\t{p.Port}\t{p.Fingerprint}\t{p.Verified}\t{p.PublicKey}\t{p.SentAvatarHash}\t{p.ReceivedAvatarHash}\n");
        foreach(var m in messages)text.Append($"M\t{m.Id}\t{m.From}\t{m.To}\t{m.Time}\t{Enc(m.Text)}\t{m.Status}\t1\t{m.GroupId}\t{Enc(m.FileName)}\t{m.FileSize}\t{m.FileHash}\t{m.Signature}\t{(m.TtlEligible?"1":"0")}\n");foreach(var g in groups.Values)text.Append($"G\t{g.Id}\t{g.Owner}\t{Enc(g.Name)}\t{string.Join(",",g.Members)}\t{g.Acknowledged}\n");foreach(var key in hidden)text.Append($"H\t{key}\n");text.Append("END\n");
        using(var stream=new FileStream(file+".tmp",FileMode.Create,FileAccess.Write,FileShare.None)){stream.Write(StorageMagic);stream.Write(protector.Protect(Encoding.UTF8.GetBytes(text.ToString())));stream.Flush(true);}
        if(File.Exists(file))File.Move(file,file+".bak",true);
        try{File.Move(file+".tmp",file,true);}catch{if(File.Exists(file+".bak"))File.Move(file+".bak",file,true);throw;}}
    }
}
