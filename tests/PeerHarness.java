package net.lanmsg.chat;
import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
public class PeerHarness {
 public static void main(String[] args)throws Exception {
  PeerEngine e=new PeerEngine(new File(args[0]),args[1],new TestProtector(new File(args[0])));e.start(args[2],Integer.parseInt(args[3]),Integer.parseInt(args[4]));
  java.util.concurrent.atomic.AtomicInteger slowMs=new java.util.concurrent.atomic.AtomicInteger();
  e.sourceOpener=reference->new FilterInputStream(new FileInputStream(reference)){public int read(byte[] bytes,int offset,int length)throws IOException{int delay=slowMs.get();if(delay>0)try{Thread.sleep(delay);}catch(InterruptedException ex){throw new IOException(ex);}return in.read(bytes,offset,length);}};
  java.util.concurrent.atomic.AtomicInteger notifications=new java.util.concurrent.atomic.AtomicInteger();e.received=m->notifications.incrementAndGet();
  System.out.println("READY\t"+e.id);
  BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));String line;
  while((line=input.readLine())!=null){String[] a=line.split("\t",-1);try{
   if(a[0].equals("STOP"))break;
   if(a[0].equals("SLOWMS"))slowMs.set(Integer.parseInt(a[1]));
   if(a[0].equals("ADD"))e.addAddress(a[1]);
   if(a[0].equals("CODE"))System.out.println("CODE\t"+e.pairingCode(a[1]));
   if(a[0].equals("VERIFY"))e.verify(a[1],a[2]);
   if(a[0].equals("REVOKE"))e.revoke(a[1]);
   if(a[0].equals("NOTIFYCOUNT"))System.out.println("COUNT\t"+notifications.get());
   if(a[0].equals("RAWFETCH")){try(java.net.Socket socket=e.connect(a[1],Integer.parseInt(a[2]))){PeerEngine.write(socket,e.hello());PeerEngine.read(socket);if(!PeerEngine.read(socket).equals("LM4\tREADY"))throw new IOException("Not trusted");PeerEngine.write(socket,"LM4\tFETCH\t"+a[3]+"\t"+a[4]+"\t"+a[5]+"\t"+a[6]);System.out.println("FETCHREPLY\t"+PeerEngine.enc(PeerEngine.read(socket)));}}
   if(a[0].equals("RAWGROUP")){try(java.net.Socket socket=e.connect(a[1],Integer.parseInt(a[2]))){PeerEngine.write(socket,e.hello());PeerEngine.read(socket);String ready=PeerEngine.read(socket);if(!ready.equals("LM4\tREADY"))throw new IOException("Not trusted");PeerEngine.write(socket,"LM4\tGROUP\t"+a[3]+"\t"+a[4]+"\t"+a[5]+"\t"+a[6]);String reply;try{reply=PeerEngine.read(socket);}catch(IOException error){reply="REJECTED";}System.out.println("WIRE\t"+PeerEngine.enc(reply));}}
   if(a[0].equals("RAW")){
     try(java.net.Socket socket=e.connect(a[1],Integer.parseInt(a[2]))){
       PeerEngine.write(socket,"LM4\tHELLO\t"+a[3]+"\t"+PeerEngine.enc(e.name)+"\t"+args[3]);PeerEngine.read(socket);String state=PeerEngine.read(socket);
       if(state.equals("LM4\tREADY")){PeerEngine.write(socket,"LM4\tMSG\t"+a[5]+"\t"+a[3]+"\t"+a[4]+"\t"+PeerEngine.enc(e.name)+"\t"+System.currentTimeMillis()+"\t"+a[6]+(a.length>7?"\t"+a[7]+"\t"+a[8]+"\t"+a[9]+"\t"+a[10]:""));if(a.length>11){socket.getOutputStream().write(Base64.getDecoder().decode(a[11]));socket.getOutputStream().flush();}try{state=PeerEngine.read(socket);}catch(java.io.EOFException ignored){state="REJECTED";}}
       System.out.println("WIRE\t"+PeerEngine.enc(state));
     }
   }
   if(a[0].equals("RAWTIME")){
     try(java.net.Socket socket=e.connect(a[1],Integer.parseInt(a[2]))){
       PeerEngine.write(socket,"LM4\tHELLO\t"+a[3]+"\t"+PeerEngine.enc(e.name)+"\t"+args[3]);PeerEngine.read(socket);String state=PeerEngine.read(socket);
       if(state.equals("LM4\tREADY")){PeerEngine.write(socket,"LM4\tMSG\t"+a[5]+"\t"+a[3]+"\t"+a[4]+"\t"+PeerEngine.enc(e.name)+"\t"+a[11]+"\t"+a[6]+"\t"+a[7]+"\t"+a[8]+"\t"+a[9]+"\t"+a[10]);try{state=PeerEngine.read(socket);}catch(java.io.EOFException ignored){state="REJECTED";}}
       System.out.println("WIRE\t"+PeerEngine.enc(state));
     }
   }
   if(a[0].equals("GROUP"))System.out.println("GROUP\t"+e.createGroup(PeerEngine.dec(a[1]),Arrays.asList(a[2].split(","))));
   if(a[0].equals("CLEAR"))e.clearConversation(a[1]);
   if(a[0].equals("READ"))e.markRead(a[1]);
   if(a[0].equals("UNREAD"))System.out.println("UNREAD\t"+e.unread(a[1]));
   if(a[0].equals("SETAVATAR"))e.setAvatar(Base64.getDecoder().decode(a[1]));
   if(a[0].equals("CLEARAVATAR"))e.setAvatar(null);
   if(a[0].equals("PEERAVATAR")){byte[] data=e.peerAvatar(a[1]);System.out.println("PEERAVATAR\t"+(data==null?"NONE":SecureIdentity.hash(data)+"\t"+data.length));}
   if(a[0].equals("FILE"))e.queueFile(a[1],PeerEngine.dec(a[2]),Base64.getDecoder().decode(a[3]));
   if(a[0].equals("FASTFILE"))e.queueFastFile(a[1],"",a[2],new File(a[2]).length(),new File(a[2]).getName());
   if(a[0].equals("DOWNLOAD")||a[0].equals("HASFILE")||a[0].equals("DOWNLOADASYNC")||a[0].equals("CANCEL")||a[0].equals("EXPORT")||a[0].equals("VERIFYFILE"))for(PeerEngine.Message m:e.messages(a[1]))if(m.id.equals(a[2])){
     if(a[0].equals("DOWNLOAD"))e.downloadAttachment(m);
     if(a[0].equals("HASFILE"))System.out.println("HASFILE\t"+e.hasAttachment(m));
     if(a[0].equals("CANCEL"))e.cancelDownload(m);
     if(a[0].equals("DOWNLOADASYNC"))new Thread(()->{try{e.downloadAttachment(m);}catch(Exception ignored){}}).start();
     if(a[0].equals("VERIFYFILE"))e.readAttachmentStream(m,OutputStream.nullOutputStream(),null);
     if(a[0].equals("EXPORT"))try(OutputStream out=new FileOutputStream(a[3])){e.readAttachmentStream(m,out,null);}
   }
   if(a[0].equals("FILEHASH")){for(PeerEngine.Message m:e.messages(a[1]))if(m.id.equals(a[2])){byte[] data=e.readAttachment(m);System.out.println("FILEHASH\t"+SecureIdentity.hash(data)+"\t"+data.length);}}
   if(a[0].equals("CONV")){for(PeerEngine.Message m:e.messages(a[1]))print(m);}
   if(a[0].equals("SEND"))e.queue(a[1],new String(Base64.getDecoder().decode(a[2]),StandardCharsets.UTF_8));
   if(a[0].equals("STATE")){for(PeerEngine.Peer p:e.peers()){System.out.println("P\t"+p.id+"\t"+p.online());for(PeerEngine.Message m:e.messages(p.id))System.out.println("M\t"+m.id+"\t"+m.from+"\t"+m.to+"\t"+m.status+"\t"+Base64.getEncoder().encodeToString(m.text.getBytes(StandardCharsets.UTF_8)));}}
   if(a[0].equals("STATE")){for(PeerEngine.Group g:e.groups()){System.out.println("G\t"+g.id+"\t"+PeerEngine.enc(g.name));for(PeerEngine.Message m:e.messages(g.id))print(m);}}
   System.out.println("END");
  }catch(Exception x){System.out.println("ERROR\t"+x);System.out.println("END");}}
  e.close();
 }
 static void print(PeerEngine.Message m){System.out.println("M\t"+m.id+"\t"+m.from+"\t"+m.to+"\t"+m.status+"\t"+PeerEngine.enc(m.text)+"\t"+m.groupId+"\t"+PeerEngine.enc(m.fileName)+"\t"+m.fileSize+"\t"+m.fileHash);}
}
