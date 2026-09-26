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
        var loadedForgotten=new HashSet<string>();var loadedPendingLeaves=new Dictionary<string,string>();
        var loadedDeparted=new Dictionary<string,HashSet<string>>();var loadedAcked=new Dictionary<string,Dictionary<string,int>>();
        foreach(var line in lines.Skip(1).SkipLast(1)){var a=line.Split('\t');
            if((a.Length==5||a.Length==7||a.Length==8||a.Length==10)&&a[0]=="P")loadedPeers[a[1]]=new(a[1],Dec(a[2]),a[3],int.Parse(a[4]),0,a.Length>=7?a[5]:"",a.Length>=7?a[6]:"",a.Length>=8?a[7]:"",a.Length==10?a[8]:"",a.Length==10?a[9]:"");
            else if((a.Length==8||a.Length==12||a.Length==14)&&a[0]=="M")loadedMessages.Add(new(a[1],a[2],a[3],Dec(a[5]),long.Parse(a[4]),a[6],a.Length>=12?a[8]:"",a.Length>=12?Dec(a[9]):"",a.Length>=12?long.Parse(a[10]):0,a.Length>=12?a[11]:"",a.Length==14?a[12]:"",a.Length==14&&a[13]=="1"));
            else if(a[0]=="G"&&a.Length==7){
                // Pre-M1 shape (this session's now-superseded forget-notice release): G,Id,Owner,Name,Members,Acknowledged,Left.
                // A departed member sat in both Members and Left at once; migrate once to the new
                // disjoint model. If Left was non-empty this is a real membership change other active
                // members don't know about yet (Left was owner-only, never propagated before this
                // feature), so it gets version 1 and a MEMBERSUPDATE broadcast; an empty Left is a
                // pure no-op and stays at version 0.
                var oldMembers=a[4].Split(',');var oldLeft=a[6].Split(',',StringSplitOptions.RemoveEmptyEntries);
                var newMembers=oldMembers.Where(x=>!oldLeft.Contains(x)).ToArray();
                loadedGroups[a[1]]=new(a[1],a[2],Dec(a[3]),newMembers,oldLeft.Length>0?1:0);
                if(oldLeft.Length>0){if(!loadedDeparted.TryGetValue(a[1],out var set))loadedDeparted[a[1]]=set=[];set.UnionWith(oldLeft);}
            }
            else if(a[0]=="G"&&a.Length==6&&int.TryParse(a[5],out var v))loadedGroups[a[1]]=new(a[1],a[2],Dec(a[3]),a[4].Split(','),v);
            else if(a[0]=="G"&&a.Length==6)loadedGroups[a[1]]=new(a[1],a[2],Dec(a[3]),a[4].Split(','),0); // ancient pre-Left shape; Acknowledged (a[5]) discarded
            else if(a.Length==2&&a[0]=="H")loadedHidden.Add(a[1]);
            else if(a.Length==2&&a[0]=="F")loadedForgotten.Add(a[1]);
            else if(a.Length==3&&a[0]=="L")loadedPendingLeaves[a[1]]=a[2];
            else if(a.Length==3&&a[0]=="D"){if(!loadedDeparted.TryGetValue(a[1],out var set))loadedDeparted[a[1]]=set=[];set.Add(a[2]);}
            else if(a.Length==4&&a[0]=="V"){if(!loadedAcked.TryGetValue(a[1],out var m))loadedAcked[a[1]]=m=[];m[a[2]]=int.Parse(a[3]);}
            else if(a[0]=="J"||a[0]=="Q"){} // Removed join-request feature; tolerate old rows already on disk instead of failing to load.
            else throw new IOException("Invalid storage row");}
        groups.Clear();foreach(var g in loadedGroups)groups[g.Key]=g.Value;hidden.Clear();hidden.UnionWith(loadedHidden);
        forgotten.Clear();forgotten.UnionWith(loadedForgotten);pendingLeaves.Clear();foreach(var kv in loadedPendingLeaves)pendingLeaves[kv.Key]=kv.Value;
        departedHistory.Clear();foreach(var kv in loadedDeparted)departedHistory[kv.Key]=kv.Value;
        memberAcked.Clear();foreach(var kv in loadedAcked)memberAcked[kv.Key]=kv.Value;
        Id=h[1];Name=Dec(h[2]);peers.Clear();foreach(var pair in loadedPeers)peers[pair.Key]=pair.Value;messages.Clear();messages.AddRange(loadedMessages);
    }
    void Save()
    {
        lock(gate){var text=new StringBuilder($"LMSTORE4\t{Id}\t{Enc(Name)}\n");
        foreach(var p in peers.Values)text.Append($"P\t{p.Id}\t{Enc(p.Name)}\t{p.Host}\t{p.Port}\t{p.Fingerprint}\t{p.Verified}\t{p.PublicKey}\t{p.SentAvatarHash}\t{p.ReceivedAvatarHash}\n");
        foreach(var m in messages)text.Append($"M\t{m.Id}\t{m.From}\t{m.To}\t{m.Time}\t{Enc(m.Text)}\t{m.Status}\t1\t{m.GroupId}\t{Enc(m.FileName)}\t{m.FileSize}\t{m.FileHash}\t{m.Signature}\t{(m.TtlEligible?"1":"0")}\n");
        foreach(var g in groups.Values)text.Append($"G\t{g.Id}\t{g.Owner}\t{Enc(g.Name)}\t{string.Join(",",g.Members)}\t{g.MembersVersion}\n");
        foreach(var key in hidden)text.Append($"H\t{key}\n");foreach(var id in forgotten)text.Append($"F\t{id}\n");foreach(var kv in pendingLeaves)text.Append($"L\t{kv.Key}\t{kv.Value}\n");
        foreach(var kv in departedHistory)foreach(var id in kv.Value)text.Append($"D\t{kv.Key}\t{id}\n");
        foreach(var kv in memberAcked)foreach(var mv in kv.Value)text.Append($"V\t{kv.Key}\t{mv.Key}\t{mv.Value}\n");
        text.Append("END\n");
        using(var stream=new FileStream(file+".tmp",FileMode.Create,FileAccess.Write,FileShare.None)){stream.Write(StorageMagic);stream.Write(protector.Protect(Encoding.UTF8.GetBytes(text.ToString())));stream.Flush(true);}
        if(File.Exists(file))File.Move(file,file+".bak",true);
        try{File.Move(file+".tmp",file,true);}catch{if(File.Exists(file+".bak"))File.Move(file+".bak",file,true);throw;}}
    }
}
