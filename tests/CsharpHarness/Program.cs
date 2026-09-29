using LanMessenger;
using System.Text;
// WT01: `CsharpHarness --voice-check <manifestDir>` runs the real Voice Messages algorithms
// against the shared fixture manifest without needing a live PeerEngine/network setup at all.
// Uses Environment.ExitCode (not `return <int>`) to match this file's existing top-level-
// program inference, established below by mixing `await` with `Environment.ExitCode=1`.
if(args.Length==2&&args[0]=="--voice-check"){Environment.ExitCode=VoiceMessagesCheck.Run(args[1]);return;}
if(args.Length==1&&args[0]=="--voice-device-check"){Environment.ExitCode=VoiceDeviceLifecycleCheck.Run();return;}
var voiceWriters=new Dictionary<string,VoiceDraftWriter>();
try {
using var engine=new PeerEngine(args[0],args[1],new TestProtector(args[0]));int notifications=0;engine.Received+=m=>Interlocked.Increment(ref notifications);engine.Start(args[2],int.Parse(args[3]),int.Parse(args[4]));Console.WriteLine("READY\t"+engine.Id);
string? line;while((line=Console.ReadLine())!=null){var a=line.Split('\t');try{
  if(a[0]=="STOP")break;
  if(a[0]=="OFFLINE")engine.GoOffline();
  if(a[0]=="ONLINE")engine.Start(args[2],int.Parse(args[3]),int.Parse(args[4]));
  if(a[0]=="RAPID"){
    await Task.WhenAll(Enumerable.Range(0,4).Select(_=>Task.Run(()=>{
      for(int i=0;i<4;i++){engine.GoOffline();engine.Start(args[2],int.Parse(args[3]),int.Parse(args[4]));}
    })));
  }
  if(a[0]=="NETWORK")Console.WriteLine("NETWORK\t"+engine.NetworkState);
  if(a[0]=="REFRESH")await engine.Announce();
  if(a[0]=="DISPOSE")engine.Dispose();
 if(a[0]=="ADD")await engine.AddAddress(a[1]);
 if(a[0]=="ERROR")Console.WriteLine("DIAGNOSTIC\t"+engine.LastConnectionError.Replace("\n"," ").Replace("\r"," "));
 if(a[0]=="CODE")Console.WriteLine("CODE\t"+engine.PairingCode(a[1]));
 if(a[0]=="VERIFY")engine.Verify(a[1],a[2]);
 if(a[0]=="REVOKE")engine.Revoke(a[1]);
 if(a[0]=="NOTIFYCOUNT")Console.WriteLine("COUNT\t"+notifications);
 if(a[0]=="GROUP")Console.WriteLine("GROUP\t"+engine.CreateGroup(Encoding.UTF8.GetString(Convert.FromBase64String(a[1])),a[2].Split(',')));
 if(a[0]=="CLEAR")engine.ClearConversation(a[1]);
 if(a[0]=="DELETECONV")engine.DeleteConversation(a[1]);
 if(a[0]=="DELETEALL")engine.DeleteAllData();
 if(a[0]=="VERIFIED")Console.WriteLine("VERIFIED\t"+engine.Peers.Any(p=>p.Id==a[1]&&p.Trusted).ToString().ToLowerInvariant());
 if(a[0]=="REINVITE")await engine.ReinviteMember(a[1],a[2]);
 if(a[0]=="LEFT")Console.WriteLine("LEFT\t"+engine.AllKnownMembers(a[1]).Any(m=>m.Id==a[2]&&!m.Active).ToString().ToLowerInvariant());
 if(a[0]=="ROSTER")foreach(var g in engine.Groups.Where(g=>g.Id==a[1]))Console.WriteLine($"ROSTER\t{g.MembersVersion}\t{string.Join(",",g.Members)}");
 if(a[0]=="OWNER")foreach(var g in engine.Groups.Where(g=>g.Id==a[1]))Console.WriteLine($"OWNER\t{g.Owner}");
 if(a[0]=="TRANSFEROWNER")await engine.TransferOwnership(a[1],a[2]);
 if(a[0]=="PENDINGHANDOFF")Console.WriteLine("PENDINGHANDOFF\t"+engine.PendingOwnershipHandoff(a[1]).ToString().ToLowerInvariant());
 if(a[0]=="LEGACY")engine.SimulateLegacyBuild=a[1]=="true";
 if(a[0]=="READ")engine.MarkRead(a[1]);
 if(a[0]=="UNREAD")Console.WriteLine("UNREAD\t"+engine.Unread(a[1]));
 if(a[0]=="SETAVATAR")engine.SetAvatar(Convert.FromBase64String(a[1]));
 if(a[0]=="CLEARAVATAR")engine.SetAvatar(null);
 if(a[0]=="PEERAVATAR"){var data=engine.PeerAvatar(a[1]);Console.WriteLine(data==null?"PEERAVATAR\tNONE":$"PEERAVATAR\t{SecureIdentity.Hash(data)}\t{data.Length}");}
 if(a[0]=="OWNAVATAR"){var data=engine.Avatar;Console.WriteLine(data==null?"OWNAVATAR\tNONE":$"OWNAVATAR\t{SecureIdentity.Hash(data)}\t{data.Length}");}
 if(a[0]=="NAME")Console.WriteLine("NAME\t"+Convert.ToBase64String(Encoding.UTF8.GetBytes(engine.Name)));
 if(a[0]=="FILE")engine.QueueFile(a[1],Encoding.UTF8.GetString(Convert.FromBase64String(a[2])),Convert.FromBase64String(a[3]));
 if(a[0]=="FASTFILE")await engine.QueueFastFileAsync(a[1],"",a[2]);
 if(a[0]=="DOWNLOADTOASYNC"){var m=engine.Messages(a[1]).First(m=>m.Id==a[2]);_=Task.Run(async()=>{try{await engine.DownloadToAsync(m,a[3]);}catch{}});}
 if(a[0]=="DOWNLOADTO")await engine.DownloadToAsync(engine.Messages(a[1]).First(m=>m.Id==a[2]),a[3]);
 if(a[0]=="DOWNLOAD")await engine.DownloadAttachmentAsync(engine.Messages(a[1]).First(m=>m.Id==a[2]));
 if(a[0]=="HASFILE")Console.WriteLine("HASFILE\t"+engine.HasAttachment(engine.Messages(a[1]).First(m=>m.Id==a[2])).ToString().ToLowerInvariant());
 if(a[0]=="DOWNLOADASYNC"){var m=engine.Messages(a[1]).First(m=>m.Id==a[2]);_=Task.Run(async()=>{try{await engine.DownloadAttachmentAsync(m);}catch{}});}
 if(a[0]=="CANCEL")engine.CancelDownload(engine.Messages(a[1]).First(m=>m.Id==a[2]));
 if(a[0]=="EXPORT")await engine.ExportAttachmentAsync(engine.Messages(a[1]).First(m=>m.Id==a[2]),a[3]);
 if(a[0]=="FILEHASH")foreach(var m in engine.Messages(a[1]).Where(m=>m.Id==a[2])){var data=engine.ReadAttachment(m);Console.WriteLine($"FILEHASH\t{SecureIdentity.Hash(data)}\t{data.Length}");}
 if(a[0]=="CONV")foreach(var m in engine.Messages(a[1]))Print(m);
 if(a[0]=="SEND")engine.Queue(a[1],Encoding.UTF8.GetString(Convert.FromBase64String(a[2])));
 if(a[0]=="STATE")foreach(var p in engine.Peers){Console.WriteLine($"P\t{p.Id}\t{p.Online}");foreach(var m in engine.Messages(p.Id))Console.WriteLine($"M\t{m.Id}\t{m.From}\t{m.To}\t{m.Status}\t{Convert.ToBase64String(Encoding.UTF8.GetBytes(m.Text))}");}
 if(a[0]=="STATE")foreach(var g in engine.Groups){Console.WriteLine($"G\t{g.Id}\t{Convert.ToBase64String(Encoding.UTF8.GetBytes(g.Name))}");foreach(var m in engine.Messages(g.Id))Print(m);}
 // WT02: harness commands for the Voice Messages durable draft registry (VoiceDrafts.cs, W02).
 // No audio hardware or PeerEngine networking is exercised here beyond the existing plumbing —
 // frames are supplied directly as base64 PCM bytes by the Python driver.
 if(a[0]=="CREATEDRAFT")Console.WriteLine("DRAFT\t"+engine.CreateVoiceDraft(a[1],a[2]=="true"));
 if(a[0]=="OPENWRITER")voiceWriters[a[1]]=engine.OpenVoiceDraftWriter(a[1]);
 if(a[0]=="WRITEFRAME")await voiceWriters[a[1]].WriteFrame(Convert.FromBase64String(a[2]));
 if(a[0]=="CLOSEWRITER"){await voiceWriters[a[1]].CloseAsync();voiceWriters.Remove(a[1]);}
 if(a[0]=="ABORTWRITER"){voiceWriters[a[1]].Abort();voiceWriters.Remove(a[1]);}
 if(a[0]=="FINALIZEDRAFT"){try{var v=engine.FinalizeVoiceDraft(a[1]);Console.WriteLine("FINALIZE\t"+v.Pass.ToString().ToLowerInvariant()+"\t"+(v.FailureReason??""));}catch(Exception e){Console.WriteLine("FINALIZE\tERROR\t"+e.Message);}}
 if(a[0]=="INVALIDATEDRAFT")engine.InvalidateVoiceDraft(a[1]);
 if(a[0]=="DELETEDRAFT")engine.DeleteVoiceDraft(a[1]);
 if(a[0]=="DRAFTSTATE"){var d=engine.GetVoiceDraft(a[1]);Console.WriteLine(d==null?"DRAFTSTATE\tNONE":$"DRAFTSTATE\t{d.State}\t{d.ByteSize}\t{d.DurationMs}\t{d.SendTransactionId}");}
 if(a[0]=="DRAFTSFOR")Console.WriteLine("DRAFTSFOR\t"+string.Join(",",engine.VoiceDraftsFor(a[1]).Select(d=>d.Id)));
 if(a[0]=="DRAFTSENDABLE")Console.WriteLine("DRAFTSENDABLE\t"+engine.VoiceDraftSendable(a[1]).ToString().ToLowerInvariant());
 if(a[0]=="SENDDRAFT")await engine.SendVoiceDraft(a[1],a.Length>2?Encoding.UTF8.GetString(Convert.FromBase64String(a[2])):"");
 if(a[0]=="READDRAFTWAV"){try{var wav=engine.ReadVoiceDraftWav(a[1]);Console.WriteLine($"DRAFTWAV\t{SecureIdentity.Hash(wav)}\t{wav.Length}");}catch(Exception e){Console.WriteLine("DRAFTWAV\tERROR\t"+e.Message);}}
 if(a[0]=="RECONCILEDRAFTS")engine.ReconcileVoiceDrafts();
 Console.WriteLine("END");
 }catch(Exception e){Console.WriteLine("ERROR\t"+e.Message);Console.WriteLine("END");}}

}catch(Exception error){Console.Error.WriteLine(error);Environment.ExitCode=1;}

static void Print(PeerEngine.Message m)=>Console.WriteLine($"M\t{m.Id}\t{m.From}\t{m.To}\t{m.Status}\t{Convert.ToBase64String(Encoding.UTF8.GetBytes(m.Text))}\t{m.GroupId}\t{Convert.ToBase64String(Encoding.UTF8.GetBytes(m.FileName))}\t{m.FileSize}\t{m.FileHash}");
