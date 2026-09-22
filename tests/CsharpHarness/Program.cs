using LanMessenger;
using System.Text;
try {
using var engine=new PeerEngine(args[0],args[1],new TestProtector(args[0]));int notifications=0;engine.Received+=m=>Interlocked.Increment(ref notifications);engine.Start(args[2],int.Parse(args[3]),int.Parse(args[4]));Console.WriteLine("READY\t"+engine.Id);
string? line;while((line=Console.ReadLine())!=null){var a=line.Split('\t');try{
 if(a[0]=="STOP")break;
 if(a[0]=="ADD")await engine.AddAddress(a[1]);
 if(a[0]=="ERROR")Console.WriteLine("DIAGNOSTIC\t"+engine.LastConnectionError.Replace("\n"," ").Replace("\r"," "));
 if(a[0]=="CODE")Console.WriteLine("CODE\t"+engine.PairingCode(a[1]));
 if(a[0]=="VERIFY")engine.Verify(a[1],a[2]);
 if(a[0]=="REVOKE")engine.Revoke(a[1]);
 if(a[0]=="NOTIFYCOUNT")Console.WriteLine("COUNT\t"+notifications);
 if(a[0]=="GROUP")Console.WriteLine("GROUP\t"+engine.CreateGroup(Encoding.UTF8.GetString(Convert.FromBase64String(a[1])),a[2].Split(',')));
 if(a[0]=="CLEAR")engine.ClearConversation(a[1]);
 if(a[0]=="READ")engine.MarkRead(a[1]);
 if(a[0]=="UNREAD")Console.WriteLine("UNREAD\t"+engine.Unread(a[1]));
 if(a[0]=="FILE")engine.QueueFile(a[1],Encoding.UTF8.GetString(Convert.FromBase64String(a[2])),Convert.FromBase64String(a[3]));
 if(a[0]=="FILEHASH")foreach(var m in engine.Messages(a[1]).Where(m=>m.Id==a[2])){var data=engine.ReadAttachment(m);Console.WriteLine($"FILEHASH\t{SecureIdentity.Hash(data)}\t{data.Length}");}
 if(a[0]=="CONV")foreach(var m in engine.Messages(a[1]))Print(m);
 if(a[0]=="SEND")engine.Queue(a[1],Encoding.UTF8.GetString(Convert.FromBase64String(a[2])));
 if(a[0]=="STATE")foreach(var p in engine.Peers){Console.WriteLine($"P\t{p.Id}\t{p.Online}");foreach(var m in engine.Messages(p.Id))Console.WriteLine($"M\t{m.Id}\t{m.From}\t{m.To}\t{m.Status}\t{Convert.ToBase64String(Encoding.UTF8.GetBytes(m.Text))}");}
 if(a[0]=="STATE")foreach(var g in engine.Groups){Console.WriteLine($"G\t{g.Id}\t{Convert.ToBase64String(Encoding.UTF8.GetBytes(g.Name))}");foreach(var m in engine.Messages(g.Id))Print(m);}
 Console.WriteLine("END");
}catch(Exception e){Console.WriteLine("ERROR\t"+e.Message);Console.WriteLine("END");}}

}catch(Exception error){Console.Error.WriteLine(error);Environment.ExitCode=1;}

static void Print(PeerEngine.Message m)=>Console.WriteLine($"M\t{m.Id}\t{m.From}\t{m.To}\t{m.Status}\t{Convert.ToBase64String(Encoding.UTF8.GetBytes(m.Text))}\t{m.GroupId}\t{Convert.ToBase64String(Encoding.UTF8.GetBytes(m.FileName))}\t{m.FileSize}\t{m.FileHash}");
