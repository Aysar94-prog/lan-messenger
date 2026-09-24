using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Collections.Concurrent;

namespace LanMessenger;
public sealed partial class PeerEngine
{
    const int DirectBlock=256*1024;
    sealed record DestinationState(string Path="",bool Complete=false,bool Fast=false);
    readonly ConcurrentDictionary<string,DestinationState> destinations=new();
    sealed class DestinationRequiredException:IOException {public DestinationRequiredException():base("Choose a download destination. Both devices need the latest version for Fast file."){} }
    sealed class DestinationStorageException(Exception cause):IOException("Cannot write the selected destination: "+cause.Message,cause);
    string DestinationRecord(Message m)=>AttachmentPath(m)+".destination";
    DestinationState Destination(Message m)=>destinations.GetOrAdd(m.From+"/"+m.Id,_=>{
        try{if(!File.Exists(DestinationRecord(m)))return new();var fields=Encoding.UTF8.GetString(protector.Unprotect(File.ReadAllBytes(DestinationRecord(m)))).Split('\t');
            if(fields.Length==4&&fields[0]=="LMDST1")return new(Dec(fields[1]),fields[2]=="1",fields[3]=="1");}catch{}
        return new();
    });
    void SetDestination(Message m,string path,bool complete,bool fast){
        var record=DestinationRecord(m);Directory.CreateDirectory(Path.GetDirectoryName(record)!);
        var bytes=protector.Protect(Encoding.UTF8.GetBytes($"LMDST1\t{Enc(path)}\t{(complete?1:0)}\t{(fast?1:0)}"));
        var temp=record+".tmp";using(var output=new FileStream(temp,FileMode.Create,FileAccess.Write)){output.Write(bytes);output.Flush(true);}File.Move(temp,record,true);
        destinations[m.From+"/"+m.Id]=new(path,complete,fast);
    }
    public string SavedDestination(Message m){var state=Destination(m);if(state.Complete&&File.Exists(state.Path))return state.Path;
        if(m.From==Id&&File.Exists(SourcePath(m)))try{var path=Encoding.UTF8.GetString(protector.Unprotect(File.ReadAllBytes(SourcePath(m))));if(File.Exists(path))return path;}catch{}
        return "";
    }
    public string PendingDestination(Message m){var state=Destination(m);return state.Complete?"":state.Path;}
    bool IsFastAttachment(Message m)=>File.Exists(SourcePath(m))||Destination(m).Fast;
    void ForgetDestination(Message m){File.Delete(DestinationRecord(m));destinations.TryRemove(m.From+"/"+m.Id,out _);}
    void CheckDirectActive(Message m,CancellationToken cancel){cancel.ThrowIfCancellationRequested();if(!Retained(m))throw new IOException("Attachment cleared or expired");}
    Peer[] DirectCandidates(Message m)=>Peers.Where(p=>p.Trusted&&(m.GroupId.Length==0?p.Id==m.From:AllowedGroup(m.GroupId,p.Id))).OrderByDescending(p=>p.Id==m.From).ToArray();

    async Task ServeDirect(SecureChannel tls,TcpClient control,string[] request,string peerId)
    {
        if(!Uuid(request[2])||!Uuid(request[3])||!long.TryParse(request[4],out var offset))return;
        Message? m;lock(gate)m=messages.FirstOrDefault(x=>x.From==request[2]&&x.Id==request[3]&&(x.GroupId.Length==0?x.From==Id&&x.To==peerId:AllowedGroup(x.GroupId,peerId)));
        if(m==null||!Retained(m)||!HasAttachment(m)||offset<0||offset>m.FileSize||offset%DirectBlock!=0){await Write(tls,"LM4\tUNAVAILABLE");return;}
        if(!await fileSlots.WaitAsync(0)){await Write(tls,"LM4\tBUSY");return;}
        try{
            using var source=OpenContent(m);
            if(source.CanSeek)source.Position=offset;
            else{var skip=new byte[DirectBlock];long at=0;while(at<offset){int n=await source.ReadAsync(skip.AsMemory(0,(int)Math.Min(skip.Length,offset-at)));if(n==0)throw new EndOfStreamException();at+=n;}}
            string header=$"LM4\tDIRECT\t{m.Id}\t{offset}\t{m.FileSize-offset}";
            if(!IsFastAttachment(m)){await Write(tls,header+"\tTLS");await SendDirectPayload(m,source,tls,offset,peerId,tls.PeerFingerprint);return;}
            string token=Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
            var listener=new TcpListener(bind,0);listener.Start(1);
            try{
                using var deadline=CancellationTokenSource.CreateLinkedTokenSource(stop.Token);deadline.CancelAfter(TimeSpan.FromSeconds(15));
                await Write(tls,header+$"\tRAW\t{((IPEndPoint)listener.LocalEndpoint).Port}\t{token}");
                for(int attempt=0;attempt<4;attempt++){
                    using var raw=await listener.AcceptTcpClientAsync(deadline.Token);raw.NoDelay=true;
                    if(!((IPEndPoint)raw.Client.RemoteEndPoint!).Address.Equals(((IPEndPoint)control.Client.RemoteEndPoint!).Address))continue;
                    string supplied;try{supplied=await Read(raw.GetStream());}catch(IOException){continue;}catch(OperationCanceledException){continue;}
                    if(!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(supplied),Encoding.ASCII.GetBytes("LM4\tTOKEN\t"+token)))continue;
                    if(!Retained(m)||!Trusted(peerId,tls.PeerFingerprint))return;
                    listener.Stop();await Write(raw.GetStream(),"LM4\tRAWREADY");
                    await SendDirectPayload(m,source,raw.GetStream(),offset,peerId,tls.PeerFingerprint);return;
                }
            }finally{listener.Stop();}
        }finally{fileSlots.Release();}
    }
    async Task SendDirectPayload(Message m,Stream input,Stream output,long offset,string peer,string fingerprint){
        var buffer=new byte[DirectBlock];var guarded=new NetworkTimeoutStream(output,ChunkTimeout);
        for(long at=offset;at<m.FileSize;){
            if(!Retained(m)||!Trusted(peer,fingerprint))throw new IOException("Transfer no longer authorized");
            int count=(int)Math.Min(buffer.Length,m.FileSize-at);await input.ReadExactlyAsync(buffer.AsMemory(0,count),stop.Token);
            await guarded.WriteAsync(buffer.AsMemory(0,count),stop.Token);at+=count;
        }
    }
    public async Task DownloadToAsync(Message m,string destination)
    {
        destination=Path.GetFullPath(destination);if(string.Equals(destination,SavedDestination(m),StringComparison.OrdinalIgnoreCase))return;
        using var cancel=CancellationTokenSource.CreateLinkedTokenSource(stop.Token);string key=m.From+"/"+m.Id;
        if(!downloads.TryAdd(key,cancel))return;
        try{
            CheckDirectActive(m,cancel.Token);var previous=Destination(m);bool fresh=!string.Equals(previous.Path,destination,StringComparison.OrdinalIgnoreCase);
            bool cached=HasAttachment(m)&&!previous.Complete;
            using var output=new FileStream(destination,FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.Read,DirectBlock,FileOptions.Asynchronous);
            if(fresh){output.SetLength(0);SetDestination(m,destination,false,IsFastAttachment(m));}
            if(cached){
                output.SetLength(0);output.Position=0;
                using var input=OpenContent(m);using var cachedHash=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);var data=new byte[DirectBlock];long at=0;
                while(at<m.FileSize){CheckDirectActive(m,cancel.Token);int count=(int)Math.Min(data.Length,m.FileSize-at);await input.ReadExactlyAsync(data.AsMemory(0,count),cancel.Token);await output.WriteAsync(data.AsMemory(0,count),cancel.Token);cachedHash.AppendData(data,0,count);at+=count;ReportProgress(m.Id,at,m.FileSize);}
                if(Convert.ToHexString(cachedHash.GetHashAndReset()).ToLowerInvariant()!=m.FileHash)throw new IOException("Stored file is damaged");
                FinishDirect(m,output,destination,IsFastAttachment(m),cancel.Token);return;
            }
            if(output.Length>m.FileSize)throw new IOException("Destination changed; choose a new file");
            long done=output.Length==m.FileSize?output.Length:output.Length/DirectBlock*DirectBlock;output.SetLength(done);output.Position=0;
            using var hash=IncrementalHash.CreateHash(HashAlgorithmName.SHA256);var buffer=new byte[DirectBlock];long scanned=0;
            while(scanned<done){int n=await output.ReadAsync(buffer.AsMemory(0,(int)Math.Min(buffer.Length,done-scanned)),cancel.Token);if(n==0)throw new EndOfStreamException();hash.AppendData(buffer,0,n);scanned+=n;}
            output.Position=done;bool fast=Destination(m).Fast,request=fresh||done<m.FileSize;ReportProgress(m.Id,done,m.FileSize);
            while(request){
                CheckDirectActive(m,cancel.Token);bool oldPeer=false;
                foreach(var peer in DirectCandidates(m))try{
                    using var client=await Connect(peer.Host,peer.Port);using var abort=cancel.Token.Register(()=>client.Dispose());
                    using var tls=identity.Wrap(client.GetStream());await identity.Authenticate(tls,false);
                    if(!Trusted(peer.Id,tls.PeerFingerprint))continue;
                    await Write(tls,Hello());var hello=(await Read(tls)).Split('\t');if(!ValidHello(hello)||hello[2]!=peer.Id||await Read(tls)!="LM4\tREADY")continue;
                    await Write(tls,$"LM4\tFETCHDIRECT\t{m.From}\t{m.Id}\t{done}");
                    string response;try{response=await Read(tls);}catch(EndOfStreamException){oldPeer=true;continue;}
                    if(response is "LM4\tBUSY" or "LM4\tUNAVAILABLE")continue;
                    var header=response.Split('\t');
                    if(header.Length<6||header[0]!="LM4"||header[1]!="DIRECT"||header[2]!=m.Id||header[3]!=done.ToString()||header[4]!=(m.FileSize-done).ToString())throw new IOException("Invalid file response");
                    fast=header[5]=="RAW";if(!(fast&&header.Length==8||header[5]=="TLS"&&header.Length==6))throw new IOException("Invalid transfer mode");
                    try{SetDestination(m,destination,false,fast);}catch(Exception error){throw new DestinationStorageException(error);}
                    using var raw=fast?new TcpClient(new IPEndPoint(bind,0)){NoDelay=true}:null;
                    using var abortRaw=cancel.Token.Register(()=>raw?.Dispose());Stream payload=tls;
                    if(fast){
                        if(!int.TryParse(header[6],out int port)||port<1||port>65535||!System.Text.RegularExpressions.Regex.IsMatch(header[7],"^[0-9a-f]{64}$"))throw new IOException("Invalid data authorization");
                        using var connectDeadline=CancellationTokenSource.CreateLinkedTokenSource(cancel.Token);connectDeadline.CancelAfter(3000);
                        await raw!.ConnectAsync(IPAddress.Parse(peer.Host),port,connectDeadline.Token);payload=raw.GetStream();
                        await Write(payload,"LM4\tTOKEN\t"+header[7]);if(await Read(payload)!="LM4\tRAWREADY")throw new IOException("Data authorization failed");
                    }
                    var guarded=new NetworkTimeoutStream(payload,ChunkTimeout);
                    while(done<m.FileSize){
                        CheckDirectActive(m,cancel.Token);if(!Trusted(peer.Id,tls.PeerFingerprint))throw new IOException("Device verification changed");
                        int count=(int)Math.Min(buffer.Length,m.FileSize-done);await guarded.ReadExactlyAsync(buffer.AsMemory(0,count),cancel.Token);CheckDirectActive(m,cancel.Token);
                        try{await output.WriteAsync(buffer.AsMemory(0,count),cancel.Token);}catch(IOException error){try{output.SetLength(done);output.Position=done;}catch{}throw new DestinationStorageException(error);}
                        hash.AppendData(buffer,0,count);done+=count;ReportProgress(m.Id,done,m.FileSize);
                    }
                    if(!Trusted(peer.Id,tls.PeerFingerprint))throw new IOException("Device verification changed");request=false;break;
                }catch(DestinationStorageException){throw;}
                catch(IOException){CheckDirectActive(m,cancel.Token);if(done==m.FileSize)throw;}
                catch(SocketException){CheckDirectActive(m,cancel.Token);}
                catch(OperationCanceledException) when(!cancel.IsCancellationRequested){CheckDirectActive(m,cancel.Token);}
                if(request){if(oldPeer)throw new IOException("Update the sender to download directly to the chosen folder");await Task.Delay(500,cancel.Token);}
            }
            if(Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant()!=m.FileHash){ForgetDestination(m);throw new IOException("File changed or is damaged. Choose a new destination and ask the sender to send again.");}
            FinishDirect(m,output,destination,fast,cancel.Token);
        }finally{downloads.TryRemove(key,out _);Notify();}
    }
    void FinishDirect(Message m,FileStream output,string destination,bool fast,CancellationToken cancel){
        output.Flush(true);lock(gate){CheckDirectActive(m,cancel);SetDestination(m,destination,true,fast);}
        File.Delete(AttachmentPath(m));if(Directory.Exists(PartsPath(m)))Directory.Delete(PartsPath(m),true);Notify();
    }
}
