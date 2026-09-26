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
    // Members is the live, active roster — it shrinks when someone leaves and grows when the owner
    // adds someone, propagated to every active member via MEMBERSUPDATE (see PeerEngine.cs's
    // Deliver/Receive). MembersVersion is a plain monotonic counter, bumped by exactly 1 on every
    // such change; a receiving device only ever adopts a strictly greater version.
    public sealed record Group(string Id,string Owner,string Name,string[] Members,int MembersVersion=0);
    readonly Dictionary<string,Group> groups=[];
    readonly HashSet<string> hidden=[];
    // Peer ids we've deleted and still owe a FORGET notice; groups we've left, keyed by the
    // owner id we still owe a LEAVE notice to (captured before the local group record is dropped).
    readonly HashSet<string> forgotten=[];
    readonly Dictionary<string,string> pendingLeaves=[];
    // Owner-only bookkeeping, never sent over the wire: ids who used to be a member of a group,
    // kept purely so the Members dialog can show them and offer Re-invite. Disjoint from the live
    // Members above (a departed id is removed from Members, not merely flagged here).
    readonly Dictionary<string,HashSet<string>> departedHistory=[];
    // Owner-only: per group, the last MembersVersion each active member has acked. Drives Deliver's
    // broadcast/retry loop — a member whose acked version is behind the group's current version is
    // still owed either a first GROUP invite (never acked anything before) or a MEMBERSUPDATE.
    readonly Dictionary<string,Dictionary<string,int>> memberAcked=[];
    // Test-only: makes this engine behave as if it predates group-membership changes entirely —
    // refuses CAPS and MEMBERSUPDATE while HELLO and ordinary messaging behave normally. Used by
    // tests/CsharpHarness and tests/PeerHarness.java to simulate a not-yet-updated peer without a
    // real legacy binary.
    public bool SimulateLegacyBuild;
    // Owner-only: per group, requesters asking to join. Null value = currently Pending (shown in
    // the owner's queue); a timestamp = Ignored at that moment, kept only to enforce the 1-hour
    // cooldown before the same person can request again — never shown once ignored.
    readonly Dictionary<string,Dictionary<string,long?>> joinRequests=[];
    // Requester-only: ownerId -> group ids still awaiting a JOINREQUESTACK. Persisted so a restart
    // before the ack arrives doesn't silently drop the intent — mirrors forgotten/pendingLeaves.
    readonly Dictionary<string,HashSet<string>> pendingJoinRequests=[];
    const long JoinRequestCooldownMs=3600_000;
    public Group[] Groups {get{lock(gate)return groups.Values.Select(g=>g with{Members=g.Members.ToArray()}).ToArray();}}
    // Union of the live roster and the departed-history record, for the Members dialog — a departed
    // id no longer appears in Groups[].Members, so callers that want to show (and Re-invite) them
    // need this instead.
    public (string Id,bool Active)[] AllKnownMembers(string groupId)
    {
        lock(gate){
            if(!groups.TryGetValue(groupId,out var g))return [];
            var departed=departedHistory.TryGetValue(groupId,out var h)?h:[];
            return g.Members.Select(id=>(id,true)).Concat(departed.Select(id=>(id,false))).ToArray();
        }
    }
    public string DisplayName(string id){lock(gate)return id==Id?Name:groups.TryGetValue(id,out var g)?g.Name:peers.TryGetValue(id,out var p)?p.Name:"Device "+id[..Math.Min(8,id.Length)];}
    public string CreateGroup(string name,IEnumerable<string> members)
    {
        name=name.Trim();var ids=members.Append(Id).Distinct().OrderBy(x=>x,StringComparer.Ordinal).ToArray();
        lock(gate){if(name.Length is <1 or >50||ids.Length is <3 or >16||ids.Any(x=>x!=Id&&(!peers.TryGetValue(x,out var p)||!p.Trusted)))throw new IOException("Name the group and select 2–15 verified contacts.");
        var g=new Group(Guid.NewGuid().ToString(),Id,name,ids);groups.Add(g.Id,g);try{Save();}catch{groups.Remove(g.Id);throw;}Notify();return g.Id;}
    }
    bool AllowedGroup(string id,string sender)=>id.Length==0||(groups.TryGetValue(id,out var g)&&g.Members.Contains(Id)&&g.Members.Contains(sender));
    // Owner-side: a member has told us they left. This is never gated by anyone's capability — it's
    // a fact that already happened, not a request the owner can refuse. Removes them from the live
    // roster (shrinking it), records them in the separate departed history, and bumps the version so
    // Deliver's broadcast loop picks up every remaining active member automatically.
    void HandleLeave(string groupId,string memberId)
    {
        lock(gate){
            if(!groups.TryGetValue(groupId,out var g)||g.Owner!=Id||!g.Members.Contains(memberId))return;
            var hadHistory=departedHistory.TryGetValue(groupId,out var set)&&set.Contains(memberId);
            groups[groupId]=g with{Members=g.Members.Where(x=>x!=memberId).ToArray(),MembersVersion=g.MembersVersion+1};
            if(!departedHistory.TryGetValue(groupId,out set))departedHistory[groupId]=set=[];
            set.Add(memberId);
            try{Save();}catch{groups[groupId]=g;if(!hadHistory)set.Remove(memberId);throw;}
        }
    }
    // Requester side: sends a request to join a group, given its id and a verified contact who
    // owns it. Delivery is guaranteed the same way FORGET/LEAVE are — retried until acked — with
    // no user-visible "pending" state: if accepted, the group simply appears like any invite does.
    public void RequestJoin(string ownerId,string groupId)
    {
        lock(gate){
            if(!Uuid(groupId))throw new IOException("Enter a valid group ID.");
            if(!peers.TryGetValue(ownerId,out var p)||!p.Trusted)throw new IOException("Choose a verified contact.");
            if(groups.ContainsKey(groupId))throw new IOException("You're already part of this group.");
            if(!pendingJoinRequests.TryGetValue(ownerId,out var set))pendingJoinRequests[ownerId]=set=[];
            if(!set.Add(groupId))return; // already requested; no-op
            try{Save();}catch{set.Remove(groupId);throw;}
        }
        Notify();WakeDelivery();
    }
    // Owner side: records an incoming join request. Verified contact required to even record it;
    // a repeat request while already Pending is a no-op; a request from someone recently Ignored is
    // silently swallowed until the 1-hour cooldown passes, without refreshing that cooldown.
    void HandleJoinRequest(string groupId,string requesterId,string fingerprint)
    {
        lock(gate){
            if(!Trusted(requesterId,fingerprint))return;
            if(!groups.TryGetValue(groupId,out var g)||g.Owner!=Id)return;
            if(!joinRequests.TryGetValue(groupId,out var reqs))joinRequests[groupId]=reqs=[];
            if(reqs.TryGetValue(requesterId,out var existing)){
                if(existing is null)return; // already pending
                if(Now-existing.Value<JoinRequestCooldownMs)return; // ignored recently; cooldown still active
            }
            var had=reqs.TryGetValue(requesterId,out var oldValue);
            reqs[requesterId]=null;
            try{Save();}catch{if(had)reqs[requesterId]=oldValue;else reqs.Remove(requesterId);throw;}
        }
    }
    // Owner-only: currently pending (not ignored) requesters for a group, for the request-queue UI.
    public string[] PendingJoinRequests(string groupId)
    {
        lock(gate)return joinRequests.TryGetValue(groupId,out var reqs)?reqs.Where(kv=>kv.Value is null).Select(kv=>kv.Key).ToArray():[];
    }
    void ClearJoinRequestLocked(string groupId,string requesterId)
    {
        if(!joinRequests.TryGetValue(groupId,out var reqs))return;
        var had=reqs.TryGetValue(requesterId,out var old);
        reqs.Remove(requesterId);
        try{Save();}catch{if(had)reqs[requesterId]=old;throw;}
    }
    // Test-only: backdates an Ignored request's timestamp so tests can exercise "the 1-hour
    // cooldown has elapsed" without a real wall-clock wait.
    public void DebugBackdateIgnoredJoinRequest(string groupId,string requesterId,long epochMillis)
    {
        lock(gate){
            if(!joinRequests.TryGetValue(groupId,out var reqs)||!reqs.ContainsKey(requesterId))throw new IOException("No such request.");
            var old=reqs[requesterId];reqs[requesterId]=epochMillis;
            try{Save();}catch{reqs[requesterId]=old;throw;}
        }
    }
    // Owner-only: disappears from the queue, silently — no notice to the requester, no visible
    // "ignored" record anywhere. The timestamp kept internally only enforces the cooldown above.
    public void IgnoreJoinRequest(string groupId,string requesterId)
    {
        lock(gate){
            if(!groups.TryGetValue(groupId,out var g)||g.Owner!=Id)throw new IOException("Only the group owner can ignore a request.");
            if(!joinRequests.TryGetValue(groupId,out var reqs)||!reqs.ContainsKey(requesterId))return;
            var old=reqs[requesterId];
            reqs[requesterId]=Now;
            try{Save();}catch{reqs[requesterId]=old;throw;}
        }
        Notify();
    }
    // Owner-only: re-validated at the moment of the decision, not from when the request was first
    // sent. Not already a member, or no longer a verified contact — cleared, nothing recoverable
    // without a fresh request. Group full, or an existing member's live capability check fails —
    // AddMember itself throws and the request is deliberately left Pending (recoverable later).
    public async Task AcceptJoinRequest(string groupId,string requesterId)
    {
        lock(gate){
            if(!groups.TryGetValue(groupId,out var g)||g.Owner!=Id)throw new IOException("Only the group owner can accept a request.");
            if(!joinRequests.TryGetValue(groupId,out var reqs)||!reqs.TryGetValue(requesterId,out var v)||v is not null)throw new IOException("This request is no longer pending.");
            if(!g.Members.Contains(requesterId)&&(!peers.TryGetValue(requesterId,out var p)||!p.Trusted)){
                ClearJoinRequestLocked(groupId,requesterId);
                throw new IOException("This person is no longer a verified contact.");
            }
        }
        await AddMember(groupId,requesterId); // throws (Pending kept) for a full group or a failed live capability check
        lock(gate)ClearJoinRequestLocked(groupId,requesterId);
        Notify();
    }
    void AcceptGroup(string[] a,string sender,string fingerprint)
    {
        var members=a[5].Split(',');var name=Dec(a[4]);var version=a.Length==7?int.Parse(a[6]):0;
        lock(gate){
        if(!Trusted(sender,fingerprint)||!Uuid(a[2])||a[3]!=sender||name.Trim().Length==0||name.Length>50||members.Length>16||members.Distinct().Count()!=members.Length||members.Any(x=>!Uuid(x))||!members.Contains(Id)||!members.Contains(sender)||peers.ContainsKey(a[2])||a[2]==Id)throw new IOException("Invalid group invitation");
        if(groups.TryGetValue(a[2],out var old)){
            if(old.Owner!=sender||old.Name!=name)throw new IOException("Group membership cannot be replaced");
            if(version<=old.MembersVersion)return; // stale/duplicate retry; already at least this current
            groups[a[2]]=old with{Members=members,MembersVersion=version};try{Save();}catch{groups[a[2]]=old;throw;}
            return;
        }
        groups[a[2]]=new(a[2],sender,name,members,version);try{Save();}catch{groups.Remove(a[2]);throw;}}
    }
    // Update-only: applies only to a group the recipient already has a local record for. A stray or
    // late MEMBERSUPDATE for a group with no local record (departed, or never a member) is ignored,
    // same as an unknown group id is rejected elsewhere — only a GROUP frame ever creates a record.
    void HandleMembersUpdate(string[] a,string sender,string fingerprint)
    {
        if(!Uuid(a[2])||!int.TryParse(a[3],out var version))throw new IOException("Invalid membership update");
        var members=a[4].Split(',');
        lock(gate){
            if(!groups.TryGetValue(a[2],out var old))return;
            if(old.Owner!=sender||!Trusted(sender,fingerprint))return;
            if(version<=old.MembersVersion)return;
            if(members.Length>16||members.Distinct().Count()!=members.Length||members.Any(x=>!Uuid(x))||!members.Contains(Id)||!members.Contains(sender))return;
            groups[a[2]]=old with{Members=members,MembersVersion=version};try{Save();}catch{groups[a[2]]=old;throw;}
        }
    }
    // Owner-only view: the last MembersVersion a specific active member has acked, for the Members
    // dialog's sync-status display — -1 if never (they're not yet caught up to anything).
    public int MemberAckedVersion(string groupId,string peerId){lock(gate)return memberAcked.TryGetValue(groupId,out var m)&&m.TryGetValue(peerId,out var v)?v:-1;}
    // Queries a peer live, every time — a past success is never trusted as durable proof, since the
    // same device could have been downgraded, reinstalled, or restored from a backup since. Returns
    // 0 (unsupported) for any failure: unreachable, connection error, or an invalid/missing reply —
    // which is exactly how a build that's never heard of CAPS also looks from here.
    async Task<int> QueryCapability(Peer peer)
    {
        try{
            using var c=await Connect(peer.Host,peer.Port);using var tls=identity.Wrap(c.GetStream());await identity.Authenticate(tls,false);
            var fp=SecureIdentity.Remote(tls);if(!Trusted(peer.Id,fp))return 0;
            await Write(tls,Hello());var hello=(await Read(tls)).Split('\t');if(!ValidHello(hello)||hello[2]!=peer.Id)return 0;
            if(await Read(tls)!="LM4\tREADY")return 0;
            await Write(tls,"LM4\tCAPS");var reply=(await Read(tls)).Split('\t');
            if(reply.Length!=3||reply[0]!="LM4"||reply[1]!="CAPS"||!int.TryParse(reply[2],out var v))return 0;
            return v;
        }catch{return 0;}
    }
    // The one real membership mutation — Re-invite and Accept-on-a-join-request both call this and
    // nothing else. Refuses outright (nothing mutated) unless every id that would end up in Members —
    // every current active member, plus whoever's being added — answers a fresh CAPS query, at this
    // exact moment, confirming support. Never gates an incoming LEAVE; leaving is handled separately
    // in HandleLeave and can never be refused.
    public async Task AddMember(string groupId,string memberId)
    {
        string[] toCheck;
        lock(gate){
            var g=groups[groupId];if(g.Owner!=Id)throw new IOException("Only the group owner can add a member.");
            if(g.Members.Contains(memberId))return;
            if(g.Members.Length>=16)throw new IOException("This group already has 16 members.");
            toCheck=[..g.Members,memberId];
        }
        foreach(var id in toCheck.Where(x=>x!=Id)){
            Peer? p;lock(gate)p=peers.TryGetValue(id,out var found)?found:null;
            if(p is null)throw new IOException("Every member must already be a verified contact.");
            if(await QueryCapability(p)<1)throw new IOException($"Can't change this group's membership: {p.Name} hasn't updated to a version that supports it.");
        }
        lock(gate){
            var g=groups[groupId];if(g.Owner!=Id)throw new IOException("Only the group owner can add a member.");
            if(g.Members.Contains(memberId))return;
            if(g.Members.Length>=16)throw new IOException("This group already has 16 members.");
            groups[groupId]=g with{Members=[..g.Members,memberId],MembersVersion=g.MembersVersion+1};
            // Whether brand-new or returning, whoever's added needs a fresh first-time GROUP invite,
            // not a MEMBERSUPDATE — a returning member deleted their own local record when they left
            // (that's how leaving works), so any stale acked-version from before they left must not
            // make Deliver() think they already have a live copy to merely update.
            int? oldAck=memberAcked.TryGetValue(groupId,out var m)&&m.TryGetValue(memberId,out var v)?v:null;
            m?.Remove(memberId);
            // A direct invite/Re-invite landing while a join request from the same person is still
            // pending must collapse into one add with no leftover queue entry — regardless of which
            // path actually added them, not just AcceptJoinRequest's own.
            long? oldRequestValue=joinRequests.TryGetValue(groupId,out var reqs)&&reqs.TryGetValue(memberId,out var rv)?rv:null;
            bool hadRequest=reqs?.ContainsKey(memberId)??false;
            reqs?.Remove(memberId);
            try{Save();}catch{groups[groupId]=g;if(oldAck is int restore)m![memberId]=restore;if(hadRequest)reqs![memberId]=oldRequestValue;throw;}
        }
        Notify();WakeDelivery();
    }
    public void QueueFile(string conversation,string name,byte[] data)=>QueueFile(conversation,"",name,data);
    public void QueueFile(string conversation,string caption,string name,byte[] data)
        =>Task.Run(()=>QueueContentAsync(conversation,caption,SafeFileName(name),new MemoryStream(data),data.Length,null)).GetAwaiter().GetResult();
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
        Notify();WakeDelivery();
    }
    public void ClearConversation(string conversation)
    {
        lock(gate){var removed=messages.Where(m=>m.GroupId.Length>0?m.GroupId==conversation:m.From==conversation||m.To==conversation).ToArray();
        var old=messages.ToArray();var oldHidden=hidden.ToArray();foreach(var m in removed)hidden.Add(m.From+"/"+m.Id);messages.RemoveAll(m=>removed.Contains(m));
        try{Save();}catch{messages.Clear();messages.AddRange(old);hidden.Clear();hidden.UnionWith(oldHidden);throw;}
        Save(); // Replace the backup too; cleared content must not return on recovery.
        foreach(var m in removed.Where(m=>m.FileName.Length>0))DeleteTransfer(m);}
        Notify();
    }
    // Like ClearConversation, but also forgets the contact or leaves the group so it drops off the
    // list entirely. A forgotten peer that reappears on the LAN shows up as a brand-new, unverified
    // device — chatting again requires comparing safety codes from scratch. A forget/leave notice
    // is queued for delivery whenever the other side is next reachable (see Deliver).
    public void DeleteConversation(string conversation)
    {
        lock(gate){
            var removed=messages.Where(m=>m.GroupId.Length>0?m.GroupId==conversation:m.From==conversation||m.To==conversation).ToArray();
            var oldMessages=messages.ToArray();var oldHidden=hidden.ToArray();
            peers.TryGetValue(conversation,out var oldPeer);groups.TryGetValue(conversation,out var oldGroup);
            var wasForgotten=forgotten.Contains(conversation);var hadPendingLeave=pendingLeaves.ContainsKey(conversation);
            foreach(var m in removed)hidden.Add(m.From+"/"+m.Id);
            messages.RemoveAll(m=>removed.Contains(m));
            if(oldPeer!=null){peers.Remove(conversation);forgotten.Add(conversation);}
            if(oldGroup!=null){groups.Remove(conversation);pendingLeaves[conversation]=oldGroup.Owner;}
            try{Save();}
            catch{
                messages.Clear();messages.AddRange(oldMessages);hidden.Clear();hidden.UnionWith(oldHidden);
                if(oldPeer!=null){peers[conversation]=oldPeer;if(!wasForgotten)forgotten.Remove(conversation);}
                if(oldGroup!=null){groups[conversation]=oldGroup;if(!hadPendingLeave)pendingLeaves.Remove(conversation);}
                throw;
            }
            Save(); // Replace the backup too; deleted content must not return on recovery.
            foreach(var m in removed.Where(m=>m.FileName.Length>0))DeleteTransfer(m);
            if(oldPeer!=null)SetPeerAvatar(conversation,null);
        }
        Notify();WakeDelivery();
    }
    // Wipes every conversation, contact and group — everything except this device's own identity,
    // display name and profile picture. Every forgotten contact still gets a queued forget notice.
    public void DeleteAllData()
    {
        lock(gate){
            var oldMessages=messages.ToArray();var oldHidden=hidden.ToArray();
            var oldPeers=new Dictionary<string,Peer>(peers);var oldGroups=new Dictionary<string,Group>(groups);
            var oldForgotten=forgotten.ToArray();var oldPendingLeaves=new Dictionary<string,string>(pendingLeaves);
            var oldDeparted=departedHistory.ToDictionary(kv=>kv.Key,kv=>new HashSet<string>(kv.Value));
            var oldAcked=memberAcked.ToDictionary(kv=>kv.Key,kv=>new Dictionary<string,int>(kv.Value));
            var oldJoinRequests=joinRequests.ToDictionary(kv=>kv.Key,kv=>new Dictionary<string,long?>(kv.Value));
            var oldPendingJoinRequests=pendingJoinRequests.ToDictionary(kv=>kv.Key,kv=>new HashSet<string>(kv.Value));
            var withFiles=messages.Where(m=>m.FileName.Length>0).ToArray();
            messages.Clear();groups.Clear();hidden.Clear();departedHistory.Clear();memberAcked.Clear();joinRequests.Clear();pendingJoinRequests.Clear();
            foreach(var id in oldPeers.Keys)forgotten.Add(id);
            peers.Clear();
            try{Save();}
            catch{
                messages.AddRange(oldMessages);hidden.UnionWith(oldHidden);
                foreach(var kv in oldPeers)peers[kv.Key]=kv.Value;foreach(var kv in oldGroups)groups[kv.Key]=kv.Value;
                foreach(var kv in oldDeparted)departedHistory[kv.Key]=kv.Value;foreach(var kv in oldAcked)memberAcked[kv.Key]=kv.Value;
                foreach(var kv in oldJoinRequests)joinRequests[kv.Key]=kv.Value;foreach(var kv in oldPendingJoinRequests)pendingJoinRequests[kv.Key]=kv.Value;
                forgotten.Clear();forgotten.UnionWith(oldForgotten);pendingLeaves.Clear();foreach(var kv in oldPendingLeaves)pendingLeaves[kv.Key]=kv.Value;
                throw;
            }
            Save();
            foreach(var m in withFiles)DeleteTransfer(m);
            // Final sweep for anything orphaned (e.g. a crash before an earlier cleanup finished).
            // Own avatar.sec and identity.sec are siblings of these two folders, never touched.
            try{Directory.Delete(Path.Combine(Path.GetDirectoryName(file)!,"attachments"),true);}catch{}
            try{Directory.Delete(Path.Combine(Path.GetDirectoryName(file)!,"avatars"),true);}catch{}
        }
        Notify();WakeDelivery();
    }
    // Owner-only: brings a departed member back. Same underlying action as AddMember — a fresh
    // capability check, live, every time — just triggered from the Members dialog instead of an
    // incoming join request.
    public Task ReinviteMember(string groupId,string memberId)
    {
        lock(gate)if(groups[groupId].Owner!=Id)throw new IOException("Only the group owner can re-invite a member.");
        return AddMember(groupId,memberId);
    }
    public static string SafeFileName(string name)
    {
        name=name.Replace('\\','/').Split('/').Last();name=new string(name.Where(c=>c>=32&&!"<>:\"/\\|?*".Contains(c)).ToArray()).Trim().Trim('.');return name.Length==0?"attachment":name[..Math.Min(120,name.Length)];
    }
    static void ValidateFile(string name,long size,string hash)
    {
        if(size<0||size>MaxFastFileSize||(name.Length==0?(size!=0||hash.Length!=0):(name!=SafeFileName(name)||hash.Length!=64||hash.Any(c=>!"0123456789abcdef".Contains(c)))))throw new IOException("Invalid attachment metadata");
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
    async Task<string> StoreAttachmentStream(Message m,Stream source,long totalBytes,Action<long>? onProgress,string? destination=null)
    {
        var path=destination??AttachmentPath(m);Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var tmp=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        using var aes=Aes.Create();aes.KeySize=256;aes.GenerateKey();aes.GenerateIV();
        var header=protector.Protect([..aes.Key,..aes.IV]);
        using var sha=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);long total=0;
        try{
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
        }finally{try{File.Delete(tmp);}catch{}}
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
        if(headerLen<1||headerLen>65536)throw new IOException("Invalid attachment header");var header=new byte[headerLen];f.ReadExactly(header);
        var raw=protector.Unprotect(header);
        using var aes=Aes.Create();aes.KeySize=256;aes.Key=raw[..32];aes.IV=raw[32..48];
        return new CryptoStream(f,aes.CreateDecryptor(),CryptoStreamMode.Read);
    }
    // Small attachments and callers that need a byte[] (thumbnails, exports below a threshold,
    // tests). Not used for the actual network send/receive path — that streams, see below.
    public byte[] ReadAttachment(Message m)
    {
        using var plain=OpenContent(m);
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
        using(var plain=OpenContent(m))
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
