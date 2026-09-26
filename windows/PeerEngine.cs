using System.Net;
using System.Net.Security;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Collections.Concurrent;

namespace LanMessenger;

public sealed partial class PeerEngine : IDisposable
{
    public const int DiscoveryPort=43871, MessagePort=43872;
    public sealed record Peer(string Id,string Name,string Host,int Port,long Seen,string Fingerprint="",string Verified="",string PublicKey="",string SentAvatarHash="",string ReceivedAvatarHash="") { public bool Online=>Now-Seen<12000; public bool Trusted=>Fingerprint.Length>0&&Fingerprint==Verified; public bool KeyChanged=>Verified.Length>0&&Fingerprint!=Verified; public string Security=>KeyChanged?"KEY CHANGED":Trusted?"Verified":"Verify device"; }
    public sealed record Message(string Id,string From,string To,string Text,long Time,string Status,string GroupId="",string FileName="",long FileSize=0,string FileHash="",string Signature="",bool TtlEligible=false);
    const long GroupTtlMs=168L*3600*1000;
    readonly object gate=new();
    readonly string file;
    readonly SecureIdentity identity;
    readonly IStorageProtector protector;
    static readonly byte[] StorageMagic=Encoding.ASCII.GetBytes("LMSEC3\n");
    readonly Dictionary<string,Peer> peers=[];
    readonly List<Message> messages=[];
    readonly CancellationTokenSource stop=new();
    readonly ConcurrentDictionary<string,byte> sending=new();
    readonly SemaphoreSlim inbound=new(12);
    TcpListener? listener; UdpClient? udp;
    IPAddress bind=IPAddress.Any;int port=MessagePort,discoveryPort=DiscoveryPort;
    public string Id {get;private set;}="";
    public string Name {get;private set;}="";
    public bool Running {get;private set;}
    public string LastConnectionError {get;private set;}="";
    public event Action? Changed;
    public event Action<Message>? Received;
    // Fired when a contact remotely revokes our verification of them, because we previously
    // deleted them and they've told us so (the FORGET notice). Carries their peer id.
    public event Action<string>? Forgotten;
    // (messageId, bytesTransferred, totalBytes) — fired while streaming an attachment over the
    // network, in either direction. Purely informational/UI, never persisted.
    public event Action<string,long,long>? TransferProgress;
    readonly ConcurrentDictionary<string,int> lastReportedPercent=new();
    // Throttled to at most ~101 events per transfer regardless of chunk count/size.
    void ReportProgress(string messageId,long done,long total)
    {
        if(total<=0)return;var pct=(int)(done*100/total);
        if(lastReportedPercent.TryGetValue(messageId,out var last)&&pct==last&&done<total)return;
        lastReportedPercent[messageId]=pct;
        try{TransferProgress?.Invoke(messageId,done,total);}catch{}
        if(done>=total)lastReportedPercent.TryRemove(messageId,out _);
    }
    static readonly TimeSpan ChunkTimeout=TimeSpan.FromSeconds(45);
    public static long Now=>DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    public PeerEngine(string directory,string defaultName,IStorageProtector? storageProtector=null)
    {
        protector=storageProtector??new WindowsProtector();Directory.CreateDirectory(directory);file=Path.Combine(directory,"state.txt");
        if(File.Exists(file)||File.Exists(file+".bak")){try{Load(file);}catch{try{Load(file+".bak");}catch{throw new IOException("Saved data could not be read. Keep the data folder for recovery.");}}}
        else{Id=Guid.NewGuid().ToString();Name=CleanName(defaultName);}
        identity=new SecureIdentity(directory,Id,protector);
        PurgeExpired();
        // Two encrypted saves replace the legacy plaintext primary and backup without losing data.
        Save();Save();
    }
    static string Enc(string s)=>Convert.ToBase64String(Encoding.UTF8.GetBytes(s));
    static string Dec(string s)=>Encoding.UTF8.GetString(Convert.FromBase64String(s));
    static bool Uuid(string s)=>Guid.TryParseExact(s,"D",out _);
    static string CleanName(string s){s=s.Trim();return s.Length==0?"My device":s[..Math.Min(s.Length,30)];}
    public Peer[] Peers {get{lock(gate)return peers.Values.ToArray();}}
    public Message[] Messages(string peer){lock(gate){var list=messages.Where(m=>m.GroupId.Length>0?m.GroupId==peer:m.From==peer||m.To==peer);return list.GroupBy(m=>(m.From,m.Id)).Select(g=>{var m=g.First();if(m.From!=Id||m.GroupId.Length==0)return m;
        int total=g.Count(),seen=g.Count(x=>x.Status=="Seen"),delivered=g.Count(x=>x.Status=="Seen"||x.Status=="Delivered");
        return m with{Status=seen==total?"Seen":delivered==total?"Delivered":$"Queued ({delivered}/{total} delivered)"};}).ToArray();}}
    public int Pending {get{lock(gate)return messages.Count(m=>m.Status=="Queued");}}
    public void Rename(string name){lock(gate){var old=Name;Name=CleanName(name);try{Save();}catch{Name=old;throw;}}Notify();}
    public void Queue(string peer,string text)
    {
        QueueContentAsync(peer,text,"",null,0,null).GetAwaiter().GetResult();
    }

    public void Start(string bindAddress="0.0.0.0",int tcpPort=MessagePort,int udpPort=DiscoveryPort)
    {
        if(Running)return;bind=IPAddress.Parse(bindAddress);discoveryPort=udpPort;
        try{listener=new TcpListener(bind,tcpPort);listener.Start();port=((IPEndPoint)listener.LocalEndpoint).Port;udp=new UdpClient(AddressFamily.InterNetwork);udp.Client.SetSocketOption(SocketOptionLevel.Socket,SocketOptionName.ReuseAddress,true);udp.EnableBroadcast=true;udp.Client.Bind(new IPEndPoint(bind,udpPort));Running=true;}
        catch{listener?.Stop();udp?.Dispose();throw;}
        _=AcceptLoop();_=DiscoveryLoop();_=TimerLoop();Notify();
    }
    string Hello(){lock(gate)return $"LM4\tHELLO\t{Id}\t{Enc(Name)}\t{port}";}
    static bool ValidHello(string[] h){try{return h.Length==5&&h[0]=="LM4"&&h[1]=="HELLO"&&Uuid(h[2])&&Dec(h[3]).Trim().Length>0&&Dec(h[3]).Length<=30&&int.TryParse(h[4],out var p)&&p is >0 and <=65535;}catch{return false;}}
    public void Remember(string id,string name,string host,int peerPort)
    {
        if(id==Id)return;lock(gate){peers.TryGetValue(id,out var old);var p=new Peer(id,CleanName(name),host,peerPort,Now,old?.Fingerprint??"",old?.Verified??"",old?.PublicKey??"",old?.SentAvatarHash??"",old?.ReceivedAvatarHash??"");peers[id]=p;
        if(old is null||old.Name!=p.Name||old.Host!=p.Host||old.Port!=p.Port)try{Save();}catch{if(old is null)peers.Remove(id);else peers[id]=old;throw;}}Notify();
    }
    async Task AcceptLoop(){while(!stop.IsCancellationRequested)try{var client=await listener!.AcceptTcpClientAsync(stop.Token);client.NoDelay=true;if(!inbound.Wait(0)){client.Dispose();continue;}_=Task.Run(async()=>{try{await Receive(client);}finally{inbound.Release();}});}catch(Exception)when(stop.IsCancellationRequested){break;}catch{await Task.Delay(200);}}
    async Task DiscoveryLoop()
    {
        while(!stop.IsCancellationRequested)try{var packet=await udp!.ReceiveAsync(stop.Token);if(packet.Buffer.Length>1024)continue;var h=Encoding.UTF8.GetString(packet.Buffer).Trim().Split('\t');if(!ValidHello(h)||h[2]==Id)continue;
        bool fresh;lock(gate)fresh=!peers.TryGetValue(h[2],out var p)||!p.Online;
        Remember(h[2],Dec(h[3]),packet.RemoteEndPoint.Address.ToString(),int.Parse(h[4]));if(fresh)await AnnounceTo(packet.RemoteEndPoint);
        }catch(Exception)when(stop.IsCancellationRequested){break;}catch{}
    }
    async Task AnnounceTo(IPEndPoint target){var data=Encoding.UTF8.GetBytes(Hello());await udp!.SendAsync(data,target,stop.Token);}
    public async Task Announce()
    {
        if(!Running)return;var targets=new HashSet<string>(Peers.Select(p=>p.Host));
        if(bind.Equals(IPAddress.Any)){targets.Add("255.255.255.255");foreach(var n in NetworkInterface.GetAllNetworkInterfaces().Where(n=>n.OperationalStatus==OperationalStatus.Up))foreach(var a in n.GetIPProperties().UnicastAddresses.Where(a=>a.Address.AddressFamily==AddressFamily.InterNetwork)){var ip=a.Address.GetAddressBytes();var mask=a.IPv4Mask.GetAddressBytes();targets.Add(new IPAddress(ip.Zip(mask,(x,y)=>(byte)(x|~y)).ToArray()).ToString());}}
        foreach(var host in targets)try{await AnnounceTo(new IPEndPoint(IPAddress.Parse(host),discoveryPort));}catch{}
    }
    long queueEpoch;
    void WakeDelivery(){Interlocked.Increment(ref queueEpoch);foreach(var p in Peers)StartDelivery(p);}
    void StartDelivery(Peer p){if(Running&&sending.TryAdd(p.Id,0))_=Task.Run(async()=>{long observed=-1;try{do{observed=Interlocked.Read(ref queueEpoch);await Deliver(p);}while(Running&&observed!=Interlocked.Read(ref queueEpoch));}finally{sending.TryRemove(p.Id,out _);if(Running&&observed!=Interlocked.Read(ref queueEpoch))StartDelivery(p);}});}
    async Task TimerLoop(){while(!stop.IsCancellationRequested){try{PurgeExpired();QueueImageDownloads();await Announce();foreach(var p in Peers)StartDelivery(p);await Task.Delay(2000,stop.Token);}catch(OperationCanceledException){break;}catch{await Task.Delay(500);}}}
    async Task<TcpClient> Connect(string host,int peerPort){var client=new TcpClient(new IPEndPoint(bind,0)){NoDelay=true};try{using var timeout=CancellationTokenSource.CreateLinkedTokenSource(stop.Token);timeout.CancelAfter(1800);await client.ConnectAsync(IPAddress.Parse(host),peerPort,timeout.Token);return client;}catch{client.Dispose();throw;}}
    static async Task<string> Read(Stream stream)
    {
        using var timeout=new CancellationTokenSource(3500);using var bytes=new MemoryStream();var b=new byte[1];
        while(await stream.ReadAsync(b,timeout.Token)>0){if(b[0]==10)return Encoding.UTF8.GetString(bytes.ToArray());if(bytes.Length>=16384)throw new IOException("Frame too large");bytes.WriteByte(b[0]);}throw new EndOfStreamException();
    }
    static async Task Write(Stream stream,string line){using var timeout=new CancellationTokenSource(3500);await stream.WriteAsync(Encoding.UTF8.GetBytes(line+"\n"),timeout.Token);}
    public async Task AddAddress(string value)
    {
        var a=value.Trim().Split(':');if(a.Length>2||!IPAddress.TryParse(a[0],out var ip)||ip.AddressFamily!=AddressFamily.InterNetwork)throw new IOException("Enter a local IPv4 address.");var b=ip.GetAddressBytes();if(!(IPAddress.IsLoopback(ip)||b[0]==10||(b[0]==172&&b[1]>=16&&b[1]<=31)||(b[0]==192&&b[1]==168)||(b[0]==169&&b[1]==254)))throw new IOException("Use a local network address.");
        using var c=await Connect(a[0],a.Length==2?int.Parse(a[1]):MessagePort);using var tls=identity.Wrap(c.GetStream());await identity.Authenticate(tls,false);await Write(tls,Hello());var h=(await Read(tls)).Split('\t');if(!ValidHello(h)||h[2]==Id)throw new IOException("No other LAN Messenger device at this address.");Remember(h[2],Dec(h[3]),a[0],int.Parse(h[4]));RecordCertificate(h[2],SecureIdentity.Remote(tls),SecureIdentity.RemotePublicKey(tls));
    }
    public string PairingCode(string peerId)
    {
        lock(gate){var p=peers[peerId];if(p.Fingerprint.Length==0)throw new IOException("Waiting for the device's secure connection. Keep both apps open.");
        var parts=new[]{Id+":"+identity.Fingerprint,p.Id+":"+p.Fingerprint};Array.Sort(parts,StringComparer.Ordinal);return SecureIdentity.Hash(Encoding.UTF8.GetBytes("LAN Messenger pairing v3\n"+string.Join("\n",parts))).ToUpperInvariant();}
    }
    public void Verify(string peerId,string expectedCode)
    {
        lock(gate){var p=peers[peerId];if(p.KeyChanged)throw new IOException("Device key changed. Revoke the old verification explicitly before pairing again.");if(PairingCode(peerId)!=expectedCode)throw new IOException("Device key changed while this dialog was open. Try again.");peers[peerId]=p with{Verified=p.Fingerprint};try{Save();}catch{peers[peerId]=p;throw;}}Notify();
    }
    public void Revoke(string peerId){lock(gate){var p=peers[peerId];peers[peerId]=p with{Verified=""};try{Save();}catch{peers[peerId]=p;throw;}}Notify();}
    void RecordCertificate(string peerId,string fingerprint,byte[] publicKey){lock(gate){var p=peers[peerId];var encodedKey=publicKey.Length>0?Convert.ToBase64String(publicKey):p.PublicKey;if(p.Fingerprint==fingerprint&&p.PublicKey==encodedKey)return;peers[peerId]=p with{Fingerprint=fingerprint,PublicKey=encodedKey};try{Save();}catch{peers[peerId]=p;throw;}}Notify();}
    bool Trusted(string peerId,string fingerprint){lock(gate)return peers.TryGetValue(peerId,out var p)&&p.Verified.Length>0&&p.Verified==fingerprint;}
    async Task Receive(TcpClient client)
    {
        using(client)try{
        using var tls=identity.Wrap(client.GetStream());await identity.Authenticate(tls,true);var fingerprint=SecureIdentity.Remote(tls);
        var h=(await Read(tls)).Split('\t');if(!ValidHello(h)||h[2]==Id)return;Remember(h[2],Dec(h[3]),((IPEndPoint)client.Client.RemoteEndPoint!).Address.ToString(),int.Parse(h[4]));RecordCertificate(h[2],fingerprint,SecureIdentity.RemotePublicKey(tls));await Write(tls,Hello());
        if(!Trusted(h[2],fingerprint)){await Write(tls,"LM4\tPAIR");return;}await Write(tls,"LM4\tREADY");
        var a=(await Read(tls)).Split('\t');
        if(a.Length==6&&a[0]=="LM4"&&a[1]=="GROUP"){AcceptGroup(a,h[2],fingerprint);await Write(tls,"LM4\tGROUPACK\t"+a[2]);Notify();return;}
        if(a.Length==4&&a[0]=="LM4"&&a[1]=="SEEN"&&Uuid(a[2])&&a[3]==h[2]){MarkSeen(a[2],h[2]);await Write(tls,"LM4\tSEENACK\t"+a[2]);Notify();return;}
        if(a.Length==4&&a[0]=="LM4"&&a[1]=="SYNCREQ2"&&Uuid(a[2])){await HandleSync(tls,a[2],a[3],h[2]);Notify();return;}
        if(a.Length==5&&a[0]=="LM4"&&a[1]=="AVATAR"&&a[2]==h[2]){await HandleAvatar(tls,a[2],a[3],a[4]);Notify();return;}
        if(a.Length==3&&a[0]=="LM4"&&a[1]=="FORGET"&&a[2]==h[2]){Revoke(h[2]);try{Forgotten?.Invoke(h[2]);}catch{}await Write(tls,"LM4\tFORGETACK");Notify();return;}
        if(a.Length==4&&a[0]=="LM4"&&a[1]=="LEAVE"&&Uuid(a[2])&&a[3]==h[2]){HandleLeave(a[2],h[2]);await Write(tls,"LM4\tLEAVEACK\t"+a[2]);Notify();return;}
        if(a.Length==5&&a[0]=="LM4"&&a[1]=="FETCHDIRECT"){await ServeDirect(tls,client,a,h[2]);return;}
        if(a.Length==6&&a[0]=="LM4"&&a[1]=="FETCH"){await ServeDownload(tls,a,h[2]);return;}
        if((a.Length!=8&&a.Length!=12&&a.Length!=13)||a[0]!="LM4"||(a[1]!="MSG"&&a[1]!="OFFER")||!Uuid(a[2])||a[3]!=h[2]||a[4]!=Id)return;
        if(!long.TryParse(a[6],out var at)||at<0||at>253402300799999999L/1000)return;
        var text=Dec(a[7]);var group=a.Length>=12?a[8]:"";var name=a.Length>=12?Dec(a[9]):"";long size=a.Length>=12?long.Parse(a[10]):0;var hash=a.Length>=12?a[11]:"";var signature=a.Length==13?a[12]:"";
        if(a.Length==13&&group.Length==0)return;
        if(text.Length>2000||(name.Length==0&&text.Trim().Length==0))return;
        if(group.Length>0&&Now>at+GroupTtlMs)return;
        if(group.Length>0&&signature.Length==0)return;
        if(signature.Length>0){
            string peerKey;lock(gate)peerKey=peers.TryGetValue(h[2],out var sp)?sp.PublicKey:"";
            if(peerKey.Length==0||!SecureIdentity.Verify(Convert.FromBase64String(peerKey),CanonicalBytes(a[2],group,a[3],at,text,name,size,hash),Convert.FromBase64String(signature)))return;
        }
        ValidateFile(name,size,hash);lock(gate){if(!AllowedGroup(group,h[2]))return;}
        var m=new Message(a[2],a[3],Id,text,at,"Received",group,name,size,hash,signature,signature.Length>0);
        if(name.Length>0&&a[1]!="OFFER")return; // No unsolicited attachment bodies.

        Message? incoming=null;
        // A retransmitted duplicate of a message we already have must never delete the attachment
        // we already legitimately stored for it — only a cleared/hidden conversation (never
        // resurrect) or an outright-rejected message should ever remove a file here.
        lock(gate){bool stillPresent=messages.Any(x=>x.Id==a[2]&&x.From==a[3]);
            if(!Trusted(h[2],fingerprint)||!AllowedGroup(group,h[2])){if(name.Length>0&&!stillPresent)try{File.Delete(AttachmentPath(m));}catch{}return;}
            if(!hidden.Contains(h[2]+"/"+a[2])&&!stillPresent){messages.Add(m);try{Save();}catch{messages.Remove(m);if(name.Length>0)try{File.Delete(AttachmentPath(m));}catch{}throw;}incoming=m;}
            else if(name.Length>0&&!stillPresent)try{File.Delete(AttachmentPath(m));}catch{}}

        if(incoming is not null)try{Received?.Invoke(incoming);}catch{}
        await Write(tls,$"LM4\tACK\t{a[2]}\t{Id}");QueueImageDownloads();Notify();}catch(Exception e){LastConnectionError=e.ToString();}
    }
    // A generous floor plus ~1s/MB tolerates slow Wi-Fi without making small transfers wait needlessly.
    static TimeSpan TransferTimeout(int size)=>TimeSpan.FromSeconds(Math.Max(60,30+size/1_000_000));
    static byte[] CanonicalBytes(string id,string group,string sender,long time,string text,string fileName,long fileSize,string fileHash)
        =>Encoding.UTF8.GetBytes($"{id}\t{group}\t{sender}\t{time}\t{Enc(text)}\t{Enc(fileName)}\t{fileSize}\t{fileHash}");
    async Task HandleSync(SecureChannel tls,string group,string knownIdsCsv,string peerId)
    {
        var known=new HashSet<string>(knownIdsCsv.Length>0?knownIdsCsv.Split(','):Array.Empty<string>());
        Message[] offer;
        lock(gate)offer=AllowedGroup(group,peerId)?messages.Where(m=>m.GroupId==group&&m.Signature.Length>0&&Now<=m.Time+GroupTtlMs&&!known.Contains(m.Id)).GroupBy(m=>m.Id).Select(g=>g.First()).ToArray():Array.Empty<Message>();
        foreach(var m in offer)try{
            await Write(tls,$"LM4\tMETA\t{m.Id}\t{group}\t{m.From}\t{Enc(DisplayName(m.From))}\t{m.Time}\t{Enc(m.Text)}\t{Enc(m.FileName)}\t{m.FileSize}\t{m.FileHash}\t{m.Signature}");
            // Bytes are sent only in response to FETCH.
            if(await Read(tls)!="LM4\tRELAYACK\t"+m.Id)return;
        }catch{return;}
        try{await Write(tls,"LM4\tSYNCDONE");}catch{}
    }
    void MarkSeen(string messageId,string readerId){lock(gate){int i=messages.FindIndex(x=>x.Id==messageId&&x.From==Id&&x.To==readerId&&x.Status!="Seen");if(i<0)return;var old=messages[i];messages[i]=old with{Status="Seen"};try{Save();}catch{messages[i]=old;throw;}}}
    public void MarkRead(string conversation)
    {
        lock(gate){var idx=new List<int>();for(int i=0;i<messages.Count;i++){var m=messages[i];if(m.To==Id&&m.Status=="Received"&&(m.GroupId.Length>0?m.GroupId==conversation:m.From==conversation))idx.Add(i);}
        if(idx.Count==0)return;var old=idx.Select(i=>messages[i]).ToArray();foreach(var i in idx)messages[i]=messages[i] with{Status="Read"};
        try{Save();}catch{for(int k=0;k<idx.Count;k++)messages[idx[k]]=old[k];throw;}}Notify();
    }
    public int Unread(string conversation){lock(gate)return messages.Count(m=>m.To==Id&&m.Status=="Received"&&(m.GroupId.Length>0?m.GroupId==conversation:m.From==conversation));}
    void PurgeExpired()
    {
        Message[] expired;lock(gate)expired=messages.Where(m=>m.TtlEligible&&m.GroupId.Length>0&&Now>m.Time+GroupTtlMs).ToArray();
        if(expired.Length==0)return;
        lock(gate){var old=messages.ToArray();messages.RemoveAll(m=>expired.Contains(m));try{Save();}catch{messages.Clear();messages.AddRange(old);throw;}}
        foreach(var m in expired.Where(m=>m.FileName.Length>0))DeleteTransfer(m);
        Notify();
    }
    async Task Deliver(Peer peer)
    {
        // Probe every known endpoint for its TLS certificate, even when there are no messages.
        try{using var probe=await Connect(peer.Host,peer.Port);using var tls=identity.Wrap(probe.GetStream());await identity.Authenticate(tls,false);await Write(tls,Hello());var h=(await Read(tls)).Split('\t');if(!ValidHello(h)||h[2]!=peer.Id)return;Remember(h[2],Dec(h[3]),peer.Host,int.Parse(h[4]));RecordCertificate(h[2],SecureIdentity.Remote(tls),SecureIdentity.RemotePublicKey(tls));}catch{return;}
        foreach(var group in Groups.Where(g=>g.Owner==Id&&g.Members.Contains(peer.Id)&&!g.Acknowledged.Split(',').Contains(peer.Id)&&!g.Left.Split(',',StringSplitOptions.RemoveEmptyEntries).Contains(peer.Id)))
        try{using var c=await Connect(peer.Host,peer.Port);using var tls=identity.Wrap(c.GetStream());await identity.Authenticate(tls,false);var fp=SecureIdentity.Remote(tls);if(!Trusted(peer.Id,fp))return;await Write(tls,Hello());var hello=(await Read(tls)).Split('\t');if(!ValidHello(hello)||hello[2]!=peer.Id||await Read(tls)!="LM4\tREADY")return;
            await Write(tls,$"LM4\tGROUP\t{group.Id}\t{group.Owner}\t{Enc(group.Name)}\t{string.Join(",",group.Members)}");
            if(await Read(tls)!="LM4\tGROUPACK\t"+group.Id||!Trusted(peer.Id,fp))return;
            lock(gate){var old=groups[group.Id];groups[group.Id]=old with{Acknowledged=string.Join(",",old.Acknowledged.Split(',',StringSplitOptions.RemoveEmptyEntries).Append(peer.Id).Distinct())};try{Save();}catch{groups[group.Id]=old;throw;}}
        }catch{return;}
        Message[] queued;lock(gate)queued=messages.Where(m=>m.To==peer.Id&&m.Status=="Queued").ToArray();
        foreach(var m in queued)try{using var c=await Connect(peer.Host,peer.Port);using var tls=identity.Wrap(c.GetStream());await identity.Authenticate(tls,false);var fingerprint=SecureIdentity.Remote(tls);if(!Trusted(peer.Id,fingerprint))return;
        await Write(tls,Hello());var h=(await Read(tls)).Split('\t');if(!ValidHello(h)||h[2]!=peer.Id)return;RecordCertificate(h[2],fingerprint,SecureIdentity.RemotePublicKey(tls));if(await Read(tls)!="LM4\tREADY"||!Trusted(peer.Id,fingerprint))return;
        bool exists;lock(gate)exists=messages.Any(x=>x.Id==m.Id&&x.To==m.To);if(!exists)continue;
        var wire=$"LM4\t{(m.FileName.Length>0?"OFFER":"MSG")}\t{m.Id}\t{Id}\t{m.To}\t{Enc(Name)}\t{m.Time}\t{Enc(m.Text)}\t{m.GroupId}\t{Enc(m.FileName)}\t{m.FileSize}\t{m.FileHash}"+(m.GroupId.Length>0?$"\t{m.Signature}":"");
        await Write(tls,wire);
        // Bytes are sent only in response to FETCH.
        if(await Read(tls)!=$"LM4\tACK\t{m.Id}\t{peer.Id}"||!Trusted(peer.Id,fingerprint))return;
        lock(gate){int i=messages.FindIndex(x=>x.Id==m.Id&&x.To==m.To);if(i<0)continue;var old=messages[i];messages[i]=old with{Status="Delivered"};try{Save();}catch{messages[i]=old;throw;}}Notify();}catch{return;}
        Message[] toConfirm;lock(gate)toConfirm=messages.Where(m=>m.To==Id&&m.From==peer.Id&&m.Status=="Read").ToArray();
        foreach(var m in toConfirm)try{
        using var c=await Connect(peer.Host,peer.Port);using var tls=identity.Wrap(c.GetStream());await identity.Authenticate(tls,false);var fingerprint=SecureIdentity.Remote(tls);if(!Trusted(peer.Id,fingerprint))return;
        await Write(tls,Hello());var h=(await Read(tls)).Split('\t');if(!ValidHello(h)||h[2]!=peer.Id)return;RecordCertificate(h[2],fingerprint,SecureIdentity.RemotePublicKey(tls));if(await Read(tls)!="LM4\tREADY"||!Trusted(peer.Id,fingerprint))return;
        await Write(tls,$"LM4\tSEEN\t{m.Id}\t{Id}");
        if(await Read(tls)!=$"LM4\tSEENACK\t{m.Id}"||!Trusted(peer.Id,fingerprint))return;
        lock(gate){int i=messages.FindIndex(x=>x.Id==m.Id&&x.From==m.From&&x.To==m.To);if(i<0)continue;var old=messages[i];messages[i]=old with{Status="Seen"};try{Save();}catch{messages[i]=old;throw;}}Notify();}catch{return;}
        foreach(var group in Groups.Where(g=>g.Members.Contains(Id)&&g.Members.Contains(peer.Id)))
        try{
            using var c=await Connect(peer.Host,peer.Port);using var tls=identity.Wrap(c.GetStream());await identity.Authenticate(tls,false);var fp=SecureIdentity.Remote(tls);if(!Trusted(peer.Id,fp))return;
            await Write(tls,Hello());var hello=(await Read(tls)).Split('\t');if(!ValidHello(hello)||hello[2]!=peer.Id||await Read(tls)!="LM4\tREADY")return;
            string[] known;lock(gate)known=messages.Where(m=>m.GroupId==group.Id&&m.Signature.Length>0&&Now<=m.Time+GroupTtlMs).Select(m=>m.Id).Distinct().ToArray();
            await Write(tls,$"LM4\tSYNCREQ2\t{group.Id}\t{string.Join(",",known)}");
            while(true){
                var reply=(await Read(tls)).Split('\t');
                if(reply.Length==2&&reply[0]=="LM4"&&reply[1]=="SYNCDONE")break;
                if(reply.Length!=12||reply[0]!="LM4"||reply[1]!="META"||!Uuid(reply[2])||reply[3]!=group.Id)return;
                var id=reply[2];var origSender=reply[4];
                if(!long.TryParse(reply[6],out var at)||at<0||at>Now+300000||!Uuid(origSender)||!long.TryParse(reply[9],out var fileSize)){await Write(tls,$"LM4\tRELAYACK\t{id}");continue;}
                var text=Dec(reply[7]);var fileName=Dec(reply[8]);var fileHash=reply[10];var signature=reply[11];
                if(Now>at+GroupTtlMs){await Write(tls,$"LM4\tRELAYACK\t{id}");continue;}
                if(text.Length>2000||(fileName.Length==0&&text.Trim().Length==0))return;
                ValidateFile(fileName,fileSize,fileHash);
                var m=new Message(id,origSender,Id,text,at,"Received",group.Id,fileName,fileSize,fileHash,signature,true);
                bool fileOk=true;

                Message? incoming=null;
                // As in Receive(): a re-sync of a message we already have must never delete the
                // attachment we already legitimately stored for it.
                if(fileOk){
                    string origKey;lock(gate)origKey=peers.TryGetValue(origSender,out var op)&&op.Trusted?op.PublicKey:"";
                    bool verified=origKey.Length>0&&SecureIdentity.Verify(Convert.FromBase64String(origKey),CanonicalBytes(id,group.Id,origSender,at,text,fileName,fileSize,fileHash),Convert.FromBase64String(signature));
                    lock(gate){
                        bool stillPresent=messages.Any(x=>x.Id==id&&x.From==origSender);
                        if(verified&&AllowedGroup(group.Id,origSender)&&!hidden.Contains(origSender+"/"+id)&&!stillPresent){
                            messages.Add(m);try{Save();}catch{messages.Remove(m);if(fileName.Length>0)try{File.Delete(AttachmentPath(m));}catch{}throw;}incoming=m;
                        }else if(fileName.Length>0&&!stillPresent)try{File.Delete(AttachmentPath(m));}catch{}
                    }
                }
                await Write(tls,$"LM4\tRELAYACK\t{id}");
                if(incoming is not null){try{Received?.Invoke(incoming);}catch{}Notify();}
            }
        }catch{return;}
        await PushAvatarIfChanged(peer);
        // A deleted contact's forget notice, and a left group's notice to its owner — both queued
        // and retried here exactly like everything else, until acknowledged.
        if(forgotten.Contains(peer.Id))
        try{
            using var c=await Connect(peer.Host,peer.Port);using var tls=identity.Wrap(c.GetStream());await identity.Authenticate(tls,false);
            await Write(tls,Hello());var h=(await Read(tls)).Split('\t');if(!ValidHello(h)||h[2]!=peer.Id)return;
            if(await Read(tls)=="LM4\tREADY"){
                await Write(tls,$"LM4\tFORGET\t{Id}");
                if(await Read(tls)=="LM4\tFORGETACK"){lock(gate){forgotten.Remove(peer.Id);try{Save();}catch{forgotten.Add(peer.Id);throw;}}}
            }
        }catch{return;}
        string[] leavingGroupIds;lock(gate)leavingGroupIds=pendingLeaves.Where(kv=>kv.Value==peer.Id).Select(kv=>kv.Key).ToArray();
        foreach(var groupId in leavingGroupIds)
        try{
            using var c=await Connect(peer.Host,peer.Port);using var tls=identity.Wrap(c.GetStream());await identity.Authenticate(tls,false);
            await Write(tls,Hello());var h=(await Read(tls)).Split('\t');if(!ValidHello(h)||h[2]!=peer.Id)return;
            if(await Read(tls)=="LM4\tREADY"){
                await Write(tls,$"LM4\tLEAVE\t{groupId}\t{Id}");
                if(await Read(tls)==$"LM4\tLEAVEACK\t{groupId}"){lock(gate){pendingLeaves.Remove(groupId);try{Save();}catch{pendingLeaves[groupId]=peer.Id;throw;}}}
            }
        }catch{return;}
    }

    void Notify(){try{Changed?.Invoke();}catch{}}
    public void Dispose(){Running=false;stop.Cancel();listener?.Stop();udp?.Dispose();}
}
