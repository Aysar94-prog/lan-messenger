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
  java.util.concurrent.atomic.AtomicLong dropAt=new java.util.concurrent.atomic.AtomicLong(),pauseAt=new java.util.concurrent.atomic.AtomicLong(),clearAt=new java.util.concurrent.atomic.AtomicLong(),haltAt=new java.util.concurrent.atomic.AtomicLong();
  java.util.concurrent.atomic.AtomicLong progress=new java.util.concurrent.atomic.AtomicLong(),firstProgress=new java.util.concurrent.atomic.AtomicLong(-1);
  java.util.concurrent.atomic.AtomicInteger regressions=new java.util.concurrent.atomic.AtomicInteger(),drops=new java.util.concurrent.atomic.AtomicInteger();
  java.util.concurrent.atomic.AtomicReference<String> downloadError=new java.util.concurrent.atomic.AtomicReference<>("");
  e.transferProgress=(mid,done,total)->{
    firstProgress.compareAndSet(-1,done);long before=progress.getAndSet(done);if(done<before)regressions.incrementAndGet();
    long crash=haltAt.get();if(crash>0&&done>=crash)Runtime.getRuntime().halt(0);
    long target=dropAt.get();if(target>0&&done>=target&&dropAt.compareAndSet(target,0)){drops.incrementAndGet();for(java.net.Socket socket:e.downloadSockets.values())try{socket.close();}catch(IOException ignored){}}
    for(PeerEngine.Peer peer:e.peers())for(PeerEngine.Message m:e.messages(peer.id))if(m.id.equals(mid)){
      target=pauseAt.get();if(target>0&&done>=target&&pauseAt.compareAndSet(target,0))e.cancelDownload(m);
      target=clearAt.get();if(target>0&&done>=target&&clearAt.compareAndSet(target,0))try{e.clearConversation(peer.id);}catch(IOException failure){downloadError.set(failure.toString());}
    }
  };
  System.out.println("READY\t"+e.id);
  BufferedReader input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));String line;
  while((line=input.readLine())!=null){String[] a=line.split("\t",-1);try{
   if(a[0].equals("STOP"))break;
   if(a[0].equals("TRACK")){progress.set(0);firstProgress.set(-1);regressions.set(0);drops.set(0);downloadError.set("");dropAt.set(0);pauseAt.set(0);clearAt.set(0);haltAt.set(0);}
   if(a[0].equals("DROPAT"))dropAt.set(Long.parseLong(a[1]));
   if(a[0].equals("PAUSEAT"))pauseAt.set(Long.parseLong(a[1]));
   if(a[0].equals("CLEARAT"))clearAt.set(Long.parseLong(a[1]));
   if(a[0].equals("HALTAT"))haltAt.set(Long.parseLong(a[1]));
   if(a[0].equals("TRANSFERSTATE"))System.out.println("TRANSFERSTATE\t"+progress.get()+"\t"+firstProgress.get()+"\t"+regressions.get()+"\t"+drops.get()+"\t"+e.downloads.size()+"\t"+PeerEngine.enc(downloadError.get()));
   if(a[0].equals("RAWDIRECTTEST")){
     try(java.net.Socket control=e.connect(a[1],Integer.parseInt(a[2]))){
       PeerEngine.write(control,e.hello());PeerEngine.read(control);if(!PeerEngine.read(control).equals("LM4\tREADY"))throw new IOException("Not trusted");
       PeerEngine.write(control,"LM4\tFETCHDIRECT\t"+a[3]+"\t"+a[4]+"\t0");String[] header=PeerEngine.read(control).split("\t");
       if(header.length!=8||!header[5].equals("RAW"))throw new IOException("Expected plaintext RAW mode");int port=Integer.parseInt(header[6]);
       try(java.net.Socket bad=new java.net.Socket()){bad.bind(new java.net.InetSocketAddress(e.bind,0));bad.connect(new java.net.InetSocketAddress(a[1],port));bad.setSoTimeout(3000);PeerEngine.write(bad,"LM4\tTOKEN\tbad");if(bad.getInputStream().read()!=-1)throw new IOException("Bad token received bytes");}
       java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
       try(java.net.Socket raw=new java.net.Socket()){raw.bind(new java.net.InetSocketAddress(e.bind,0));raw.connect(new java.net.InetSocketAddress(a[1],port));raw.setSoTimeout(5000);PeerEngine.write(raw,"LM4\tTOKEN\t"+header[7]);if(!PeerEngine.read(raw).equals("LM4\tRAWREADY"))throw new IOException("Raw authorization failed");byte[] block=new byte[262144];long remaining=Long.parseLong(header[4]);while(remaining>0){int n=raw.getInputStream().read(block,0,(int)Math.min(block.length,remaining));if(n<0)throw new EOFException();digest.update(block,0,n);remaining-=n;}}
       boolean reused=false;try(java.net.Socket retry=new java.net.Socket()){retry.bind(new java.net.InetSocketAddress(e.bind,0));retry.connect(new java.net.InetSocketAddress(a[1],port),1000);reused=true;}catch(IOException expected){}if(reused)throw new IOException("Consumed token listener remained open");
       System.out.println("RAWHASH\t"+PeerEngine.hex(digest.digest()));
     }
   }
   if(a[0].equals("RAWSTREAM")){try(java.net.Socket socket=e.connect(a[1],Integer.parseInt(a[2]))){PeerEngine.write(socket,e.hello());PeerEngine.read(socket);if(!PeerEngine.read(socket).equals("LM4\tREADY"))throw new IOException("Not trusted");PeerEngine.write(socket,"LM4\tFETCHSTREAM\t"+a[3]+"\t"+a[4]+"\t"+a[5]);System.out.println("FETCHREPLY\t"+PeerEngine.enc(PeerEngine.read(socket)));}}
   if(a[0].equals("USAGE"))System.out.println("USAGE\t"+e.uploadPolicy.sentToday()+"\t"+e.uploadPolicy.limitBytesPerSecond());
   if(a[0].equals("SEEDUSAGE"))synchronized(e.uploadPolicy){e.uploadPolicy.bytes=Long.parseLong(a[1]);e.uploadPolicy.dirty=true;e.uploadPolicy.flush();}
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
   if(a[0].equals("FILEPATH"))try(InputStream source=new FileInputStream(a[2])){File file=new File(a[2]);e.queueFileStream(a[1],"",source,file.length(),file.getName(),null);}
   if(a[0].equals("FASTFILE"))e.queueFastFile(a[1],"",a[2],new File(a[2]).length(),new File(a[2]).getName());
   if(a[0].equals("DOWNLOADTOASYNC")||a[0].equals("DOWNLOADTO")||a[0].equals("DOWNLOAD")||a[0].equals("HASFILE")||a[0].equals("DOWNLOADASYNC")||a[0].equals("CANCEL")||a[0].equals("EXPORT")||a[0].equals("VERIFYFILE"))for(PeerEngine.Message m:e.messages(a[1]))if(m.id.equals(a[2])){
     if(a[0].equals("DOWNLOADTO"))e.downloadTo(m,a[3]);
     if(a[0].equals("DOWNLOADTOASYNC"))new Thread(()->{try{e.downloadTo(m,a[3]);}catch(Exception failure){downloadError.set(failure.toString());}}).start();
     if(a[0].equals("DOWNLOAD"))e.downloadAttachment(m);
     if(a[0].equals("HASFILE"))System.out.println("HASFILE\t"+e.hasAttachment(m));
     if(a[0].equals("CANCEL"))e.cancelDownload(m);
     if(a[0].equals("DOWNLOADASYNC"))new Thread(()->{try{e.downloadAttachment(m);}catch(Exception failure){downloadError.set(failure.toString());}}).start();
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
