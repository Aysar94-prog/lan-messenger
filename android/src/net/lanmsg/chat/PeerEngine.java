package net.lanmsg.chat;

import java.io.*;
import javax.net.ssl.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/** Shared wire protocol: protocol.md. No Android dependencies, so desktop tests exercise this exact engine. */
public final class PeerEngine implements Closeable {
  public static final int DISCOVERY_PORT=43871, MESSAGE_PORT=43872;
  public static final class Peer {
    public String id,name,host; public int port; public long seen; public String fingerprint="",verified="",publicKey="",sentAvatarHash="",receivedAvatarHash="";
    Peer(String i,String n,String h,int p){id=i;name=n;host=h;port=p;}
    public boolean online(){return System.currentTimeMillis()-seen<12000;}
    public boolean trusted(){return !fingerprint.isEmpty()&&fingerprint.equals(verified);}
    public boolean keyChanged(){return !verified.isEmpty()&&!verified.equals(fingerprint);}
    public String security(){return keyChanged()?"KEY CHANGED":trusted()?"Verified":"Verify device";}
  }
  static final long GROUP_TTL_MS=168L*3600*1000;
  public static final class Message {
    public String id,from,to,text,status,groupId="",fileName="",fileHash="",signature=""; public long time; public long fileSize; public boolean ttlEligible;
    Message(String i,String f,String t,String x,long at,String s){id=i;from=f;to=t;text=x;time=at;status=s;}
    Message(String i,String f,String t,String x,long at,String s,String g,String n,long size,String hash){this(i,f,t,x,at,s);groupId=g;fileName=n;fileSize=size;fileHash=hash;}
    Message(String i,String f,String t,String x,long at,String s,String g,String n,long size,String hash,String sig,boolean eligible){this(i,f,t,x,at,s,g,n,size,hash);signature=sig;ttlEligible=eligible;}
    Message copy(){Message m=new Message(id,from,to,text,time,status,groupId,fileName,fileSize,fileHash,signature,ttlEligible);return m;}
  }
  final File file;
  public final DailyUploadPolicy uploadPolicy;
  final SecureIdentity identity;
  final SecureIdentity.Protector protector;
  static final byte[] MAGIC="LMSEC3\n".getBytes(StandardCharsets.US_ASCII);
  final LinkedHashMap<String,Peer> peers=new LinkedHashMap<>();
  final ArrayList<Message> messages=new ArrayList<>();
  final ExecutorService connections=new ThreadPoolExecutor(8,16,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(32),new ThreadPoolExecutor.AbortPolicy());
  final ExecutorService outgoing=Executors.newFixedThreadPool(4);
  final ScheduledExecutorService timer=Executors.newScheduledThreadPool(2);
  final Set<String> sending=ConcurrentHashMap.newKeySet();
  ServerSocket listener; DatagramSocket discovery;
  public String id,name; public volatile String error=""; public volatile boolean running;
  public volatile Runnable changed=()->{};
  public volatile java.util.function.Consumer<Message> received=m->{};
  public interface TransferProgressListener{void onProgress(String messageId,long done,long total);}
  // Fired while streaming an attachment over the network, in either direction. Purely
  // informational/UI, never persisted. Throttled internally to about one event per percent.
  public volatile TransferProgressListener transferProgress=(id,done,total)->{};
  final ConcurrentHashMap<String,Integer> lastReportedPercent=new ConcurrentHashMap<>();
  void reportProgress(String messageId,long done,long total){
    if(total<=0)return;int pct=(int)(done*100/total);
    Integer last=lastReportedPercent.get(messageId);
    if(last!=null&&last==pct&&done<total)return;
    lastReportedPercent.put(messageId,pct);
    try{transferProgress.onProgress(messageId,done,total);}catch(Exception ignored){}
    if(done>=total)lastReportedPercent.remove(messageId);
  }
  int port=MESSAGE_PORT, discoveryPort=DISCOVERY_PORT; String bind="0.0.0.0";
  public PeerEngine(File directory,String defaultName,SecureIdentity.Protector protector)throws IOException {
    this.protector=protector;
    if(!directory.exists()&&!directory.mkdirs())throw new IOException("Cannot create message storage.");
    file=new File(directory,"state.txt");
    uploadPolicy=new DailyUploadPolicy(directory,protector);
    if(file.exists()||new File(file+".bak").exists()) {
      try{load(file);}catch(Exception e){try{load(new File(file+".bak"));}catch(Exception other){throw new IOException("Saved data could not be read. Keep the data folder for recovery.",other);}}
    } else {id=UUID.randomUUID().toString();name=cleanName(defaultName);}
    try{identity=new SecureIdentity(directory,id,protector);}catch(Exception e){throw new IOException("Could not open protected device identity",e);}
    purgeExpired();
    save();save();
  }
  static String enc(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
  static String dec(String s){return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8);}
  static boolean uuid(String s){try{return UUID.fromString(s).toString().equals(s);}catch(Exception e){return false;}}
  static String cleanName(String s){s=s.trim();return s.isEmpty()?"My device":s.substring(0,Math.min(s.length(),30));}
  public synchronized List<Peer> peers(){ArrayList<Peer> result=new ArrayList<>();for(Peer p:peers.values()){Peer copy=new Peer(p.id,p.name,p.host,p.port);copy.seen=p.seen;copy.fingerprint=p.fingerprint;copy.verified=p.verified;copy.publicKey=p.publicKey;copy.sentAvatarHash=p.sentAvatarHash;copy.receivedAvatarHash=p.receivedAvatarHash;result.add(copy);}return result;}
  public synchronized List<Message> messages(String peer){LinkedHashMap<String,Message> result=new LinkedHashMap<>();HashMap<String,int[]> counts=new HashMap<>();for(Message m:messages)if(!m.groupId.isEmpty()?m.groupId.equals(peer):m.from.equals(peer)||m.to.equals(peer)){String key=m.from+"/"+m.id;if(!result.containsKey(key))result.put(key,m.copy());int[] c=counts.get(key);if(c==null){c=new int[3];counts.put(key,c);}c[0]++;if(m.status.equals("Seen"))c[2]++;if(m.status.equals("Seen")||m.status.equals("Delivered"))c[1]++;}for(Map.Entry<String,Message> entry:result.entrySet()){Message m=entry.getValue();int[] c=counts.get(entry.getKey());if(m.from.equals(id)&&!m.groupId.isEmpty())m.status=c[2]==c[0]?"Seen":c[1]==c[0]?"Delivered":"Queued ("+c[1]+"/"+c[0]+" delivered)";}return new ArrayList<>(result.values());}

  public synchronized int pending(){int n=0;for(Message m:messages)if(m.status.equals("Queued"))n++;return n;}
  public synchronized void rename(String value)throws IOException {String old=name;name=cleanName(value);try{save();}catch(IOException e){name=old;throw e;}notifyChanged();}
  File avatarPath(){return new File(file.getParentFile(),"avatar.sec");}
  public synchronized byte[] avatar(){File path=avatarPath();if(!path.exists())return null;try{return protector.unprotect(SecureIdentity.readFile(path));}catch(Exception e){return null;}}
  // File.renameTo() silently fails (returns false) if the destination already exists — true on
  // Windows in particular, unlike a plain POSIX rename(). Files.move with REPLACE_EXISTING is the
  // correct cross-platform equivalent of "atomically replace whatever's already there".
  static void atomicReplace(File tmp,File dest)throws IOException{java.nio.file.Files.move(tmp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
  public synchronized void setAvatar(byte[] data)throws IOException {File path=avatarPath();if(data==null){if(path.exists()&&!path.delete())throw new IOException("Cannot remove profile picture");notifyChanged();return;}
    File tmp=new File(path+"."+UUID.randomUUID()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(protector.protect(data));out.getFD().sync();}catch(Exception e){throw new IOException(e);}atomicReplace(tmp,path);notifyChanged();}
  // A peer's photo, once they've sent it over a verified connection — never fetched or guessed, only what they pushed us.
  File peerAvatarPath(String peerId){return new File(new File(file.getParentFile(),"avatars"),peerId+".sec");}
  public synchronized byte[] peerAvatar(String peerId){File path=peerAvatarPath(peerId);if(!path.exists())return null;try{return protector.unprotect(SecureIdentity.readFile(path));}catch(Exception e){return null;}}
  void setPeerAvatar(String peerId,byte[] data)throws IOException {
    File path=peerAvatarPath(peerId);if(!path.getParentFile().exists()&&!path.getParentFile().mkdirs())throw new IOException("Cannot create avatar storage");
    if(data==null||data.length==0){if(path.exists()&&!path.delete())throw new IOException("Cannot remove contact picture");return;}
    File tmp=new File(path+"."+UUID.randomUUID()+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(protector.protect(data));out.getFD().sync();}catch(Exception e){throw new IOException(e);}atomicReplace(tmp,path);
  }
  public void queue(String peer,String text)throws IOException {
    queueContentStream(peer,text,"",null,0,null);
  }
  // Persist a text message before attempting network delivery, so the UI can show it immediately.
  public void queueLocal(String peer,String text)throws IOException {
    queueContentStream(peer,text,"",null,0,null,false);
  }

  synchronized void load(File source)throws IOException {
    byte[] stored=SecureIdentity.readFile(source);if(stored.length>=MAGIC.length&&Arrays.equals(Arrays.copyOf(stored,MAGIC.length),MAGIC))try{stored=protector.unprotect(Arrays.copyOfRange(stored,MAGIC.length,stored.length));}catch(Exception e){throw new IOException("Could not decrypt saved data",e);}
    ArrayList<String> lines=new ArrayList<>();try(BufferedReader r=new BufferedReader(new InputStreamReader(new ByteArrayInputStream(stored),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)lines.add(line);}
    if(lines.size()<2||!lines.get(lines.size()-1).equals("END"))throw new IOException("Incomplete storage");
    String[] h=lines.get(0).split("\t",-1);if(h.length!=3||(!h[0].equals("LMSTORE2")&&!h[0].equals("LMSTORE3")&&!h[0].equals("LMSTORE4"))||!uuid(h[1]))throw new IOException("Invalid storage");
    LinkedHashMap<String,Peer> loadedPeers=new LinkedHashMap<>();ArrayList<Message> loadedMessages=new ArrayList<>();LinkedHashMap<String,Group> loadedGroups=new LinkedHashMap<>();HashSet<String> loadedHidden=new HashSet<>();
    for(int i=1;i<lines.size()-1;i++){String[] a=lines.get(i).split("\t",-1);
      if(a[0].equals("P")&&(a.length==5||a.length==7||a.length==8||a.length==10)){Peer p=new Peer(a[1],dec(a[2]),a[3],Integer.parseInt(a[4]));if(a.length>=7){p.fingerprint=a[5];p.verified=a[6];}if(a.length>=8)p.publicKey=a[7];if(a.length==10){p.sentAvatarHash=a[8];p.receivedAvatarHash=a[9];}loadedPeers.put(a[1],p);}
      else if(a[0].equals("M")&&(a.length==8||a.length==12||a.length==14))loadedMessages.add(new Message(a[1],a[2],a[3],dec(a[5]),Long.parseLong(a[4]),a[6],a.length>=12?a[8]:"",a.length>=12?dec(a[9]):"",a.length>=12?Long.parseLong(a[10]):0,a.length>=12?a[11]:"",a.length==14?a[12]:"",a.length==14&&a[13].equals("1")));
      else if(a[0].equals("G")&&a.length==6)loadedGroups.put(a[1],new Group(a[1],a[2],dec(a[3]),a[4].split(","),a[5]));
      else if(a[0].equals("H")&&a.length==2)loadedHidden.add(a[1]);else throw new IOException("Invalid storage row");}
    groups.clear();groups.putAll(loadedGroups);hidden.clear();hidden.addAll(loadedHidden);
    id=h[1];name=dec(h[2]);peers.clear();peers.putAll(loadedPeers);messages.clear();messages.addAll(loadedMessages);
  }
  synchronized void save()throws IOException {
    StringBuilder text=new StringBuilder("LMSTORE4\t"+id+"\t"+enc(name)+"\n");
    for(Peer p:peers.values())text.append("P\t").append(p.id).append('\t').append(enc(p.name)).append('\t').append(p.host).append('\t').append(p.port).append('\t').append(p.fingerprint).append('\t').append(p.verified).append('\t').append(p.publicKey).append('\t').append(p.sentAvatarHash).append('\t').append(p.receivedAvatarHash).append('\n');
    for(Message m:messages)text.append("M\t").append(m.id).append('\t').append(m.from).append('\t').append(m.to).append('\t').append(m.time).append('\t').append(enc(m.text)).append('\t').append(m.status).append("\t1\t").append(m.groupId).append('\t').append(enc(m.fileName)).append('\t').append(m.fileSize).append('\t').append(m.fileHash).append('\t').append(m.signature).append('\t').append(m.ttlEligible?"1":"0").append('\n');
    for(Group g:groups.values())text.append("G\t").append(g.id).append('\t').append(g.owner).append('\t').append(enc(g.name)).append('\t').append(String.join(",",g.members)).append('\t').append(g.acknowledged).append('\n');for(String key:hidden)text.append("H\t").append(key).append('\n');
    text.append("END\n");File tmp=new File(file+".tmp"),bak=new File(file+".bak");
    try(FileOutputStream out=new FileOutputStream(tmp)){out.write(MAGIC);out.write(protector.protect(text.toString().getBytes(StandardCharsets.UTF_8)));out.getFD().sync();}catch(Exception e){throw new IOException("Could not encrypt local data",e);}
    if(file.exists()){if(bak.exists()&&!bak.delete())throw new IOException("Cannot update storage backup.");if(!file.renameTo(bak))throw new IOException("Cannot back up storage.");}
    if(!tmp.renameTo(file)){if(bak.exists())bak.renameTo(file);throw new IOException("Cannot save messages.");}
  }
  public void start()throws IOException {start("0.0.0.0",MESSAGE_PORT,DISCOVERY_PORT);}
  public synchronized void start(String bindAddress,int tcpPort,int udpPort)throws IOException {
    bind=bindAddress;discoveryPort=udpPort;
    try {
      listener=identity.context.getServerSocketFactory().createServerSocket();((SSLServerSocket)listener).setNeedClientAuth(true);((SSLServerSocket)listener).setEnabledProtocols(new String[]{"TLSv1.2"});((SSLServerSocket)listener).setEnabledCipherSuites(new String[]{"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384","TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"});listener.setReuseAddress(true);listener.bind(new InetSocketAddress(bind,tcpPort));port=listener.getLocalPort();
      discovery=new DatagramSocket(null);discovery.setReuseAddress(true);discovery.setBroadcast(true);discovery.bind(new InetSocketAddress(bind,udpPort));running=true;
    }catch(IOException e){if(listener!=null)listener.close();if(discovery!=null)discovery.close();throw e;}
    Thread accept=new Thread(()->{while(running)try{Socket s=listener.accept();try{connections.execute(()->receive(s));}catch(RejectedExecutionException e){s.close();}}catch(IOException e){if(running)error="Incoming connections unavailable.";}},"lan-incoming");accept.setDaemon(true);accept.start();
    Thread discover=new Thread(()->{while(running)try{byte[] b=new byte[1024];DatagramPacket p=new DatagramPacket(b,b.length);discovery.receive(p);String line=new String(p.getData(),0,p.getLength(),StandardCharsets.UTF_8).trim();String[] a=line.split("\t",-1);if(validHello(a)&&!a[2].equals(id)){boolean fresh; synchronized(this){Peer old=peers.get(a[2]);fresh=old==null||!old.online();}remember(a[2],dec(a[3]),p.getAddress().getHostAddress(),Integer.parseInt(a[4]));if(fresh)announceTo(p.getAddress(),p.getPort());}}catch(Exception e){if(running&&!(e instanceof SocketException))error="Discovery packet ignored.";}},"lan-discovery");discover.setDaemon(true);discover.start();
    timer.scheduleWithFixedDelay(()->{try{purgeExpired();}catch(Exception ignored){}},0,3,TimeUnit.SECONDS);
    timer.scheduleWithFixedDelay(()->{try{announce();}catch(Exception ignored){}},0,3,TimeUnit.SECONDS);
    timer.scheduleWithFixedDelay(()->{try{flush();}catch(Exception ignored){}},1,2,TimeUnit.SECONDS);
    notifyChanged();
  }
  synchronized String hello(){return "LM4\tHELLO\t"+id+"\t"+enc(name)+"\t"+port;}
  boolean validHello(String[] a){try{return a.length==5&&a[0].equals("LM4")&&a[1].equals("HELLO")&&uuid(a[2])&&!dec(a[3]).trim().isEmpty()&&dec(a[3]).length()<=30&&Integer.parseInt(a[4])>0&&Integer.parseInt(a[4])<=65535;}catch(Exception e){return false;}}
  void announceTo(InetAddress address,int targetPort)throws IOException {byte[] b=hello().getBytes(StandardCharsets.UTF_8);discovery.send(new DatagramPacket(b,b.length,address,targetPort));}
  public void announce()throws IOException {
    if(!running)return;
    if(bind.equals("0.0.0.0")){
      try{announceTo(InetAddress.getByName("255.255.255.255"),discoveryPort);}catch(IOException ignored){}
      Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
      while(interfaces.hasMoreElements())for(InterfaceAddress a:interfaces.nextElement().getInterfaceAddresses())if(a.getBroadcast()!=null)try{announceTo(a.getBroadcast(),discoveryPort);}catch(IOException ignored){}
    }
    for(Peer p:peers())try{announceTo(InetAddress.getByName(p.host),discoveryPort);}catch(IOException ignored){}
  }
  public synchronized void remember(String peerId,String peerName,String host,int peerPort)throws IOException {
    if(peerId.equals(id))return;
    Peer old=peers.get(peerId);boolean modified=old==null||!old.host.equals(host)||old.port!=peerPort||!old.name.equals(peerName);
    Peer p=new Peer(peerId,cleanName(peerName),host,peerPort);if(old!=null){p.fingerprint=old.fingerprint;p.verified=old.verified;p.publicKey=old.publicKey;p.sentAvatarHash=old.sentAvatarHash;p.receivedAvatarHash=old.receivedAvatarHash;}p.seen=System.currentTimeMillis();peers.put(peerId,p);
    if(modified)try{save();}catch(IOException e){if(old==null)peers.remove(peerId);else peers.put(peerId,old);throw e;}notifyChanged();
  }
  public void addAddress(String address)throws IOException {
    String[] a=address.trim().split(":",-1);if(a.length>2||!a[0].matches("[0-9.]+"))throw new IOException("Enter a local IPv4 address.");
    InetAddress target=InetAddress.getByName(a[0]);if(!(target.isSiteLocalAddress()||target.isLinkLocalAddress()||target.isLoopbackAddress()))throw new IOException("Use a local network address.");
    int targetPort=a.length==2?Integer.parseInt(a[1]):MESSAGE_PORT;
    try(Socket s=connect(a[0],targetPort)){write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||h[2].equals(id))throw new IOException("No other LAN Messenger device at this address.");remember(h[2],dec(h[3]),a[0],Integer.parseInt(h[4]));try{recordCertificate(h[2],SecureIdentity.remote((SSLSocket)s),SecureIdentity.remotePublicKey((SSLSocket)s));}catch(Exception e){throw new IOException(e);}}
  }
  Socket connect(String host,int targetPort)throws IOException {SSLSocket s=(SSLSocket)identity.context.getSocketFactory().createSocket();s.setEnabledProtocols(new String[]{"TLSv1.2"});s.setEnabledCipherSuites(new String[]{"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384","TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"});try{s.bind(new InetSocketAddress(bind,0));s.connect(new InetSocketAddress(host,targetPort),1800);s.setSoTimeout(6000);s.setTcpNoDelay(true);s.startHandshake();return s;}catch(IOException e){s.close();throw e;}}
  static String read(Socket s)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();int c;InputStream in=s.getInputStream();while((c=in.read())!=-1){if(c==10)return new String(b.toByteArray(),StandardCharsets.UTF_8);if(b.size()>=16384)throw new IOException("Frame too large");b.write(c);}throw new EOFException();}
  static void write(Socket s,String line)throws IOException {OutputStream out=new NetworkTimeoutOutputStream(s);out.write((line+"\n").getBytes(StandardCharsets.UTF_8));out.flush();}
  public synchronized String pairingCode(String peerId)throws IOException {
    Peer p=peers.get(peerId);if(p==null||p.fingerprint.isEmpty())throw new IOException("Waiting for the device's secure connection. Keep both apps open.");
    try{String[] parts={id+":"+identity.fingerprint,p.id+":"+p.fingerprint};Arrays.sort(parts);return SecureIdentity.hash(("LAN Messenger pairing v3\n"+parts[0]+"\n"+parts[1]).getBytes(StandardCharsets.UTF_8)).toUpperCase(Locale.ROOT);}catch(Exception e){throw new IOException(e);}
  }
  public synchronized void verify(String peerId,String expectedCode)throws IOException {
    Peer p=peers.get(peerId);if(p==null)throw new IOException("Choose a device");if(p.keyChanged())throw new IOException("Device key changed. Revoke old verification before pairing again.");if(!pairingCode(peerId).equals(expectedCode))throw new IOException("Device key changed while this dialog was open. Try again.");String old=p.verified;p.verified=p.fingerprint;try{save();}catch(IOException e){p.verified=old;throw e;}notifyChanged();
  }
  public synchronized void revoke(String peerId)throws IOException{Peer p=peers.get(peerId);String old=p.verified;p.verified="";try{save();}catch(IOException e){p.verified=old;throw e;}notifyChanged();}
  synchronized void recordCertificate(String peerId,String fingerprint,byte[] publicKey)throws IOException{Peer p=peers.get(peerId);String encodedKey=publicKey!=null&&publicKey.length>0?Base64.getEncoder().encodeToString(publicKey):p.publicKey;if(p.fingerprint.equals(fingerprint)&&p.publicKey.equals(encodedKey))return;String old=p.fingerprint,oldKey=p.publicKey;p.fingerprint=fingerprint;p.publicKey=encodedKey;try{save();}catch(IOException e){p.fingerprint=old;p.publicKey=oldKey;throw e;}notifyChanged();}
  synchronized boolean trusted(String peerId,String fingerprint){Peer p=peers.get(peerId);return p!=null&&!p.verified.isEmpty()&&p.verified.equals(fingerprint);}
  void receive(Socket socket){try(SSLSocket s=(SSLSocket)socket){s.setSoTimeout(6000);s.setTcpNoDelay(true);s.startHandshake();String fingerprint=SecureIdentity.remote(s);String[] h=read(s).split("\t",-1);if(!validHello(h)||h[2].equals(id))return;remember(h[2],dec(h[3]),s.getInetAddress().getHostAddress(),Integer.parseInt(h[4]));recordCertificate(h[2],fingerprint,SecureIdentity.remotePublicKey(s));write(s,hello());
    if(!trusted(h[2],fingerprint)){write(s,"LM4\tPAIR");return;}write(s,"LM4\tREADY");
    String[] m=read(s).split("\t",-1);
    if(m.length==5&&m[0].equals("LM4")&&m[1].equals("FETCHDIRECT")){DirectFileTransfer.serve(this,s,m,h[2],fingerprint);return;}
    if(m.length==2&&m[0].equals("LM4")&&m[1].equals("FILECAPS")){write(s,"LM4\tFILECAPS\tSTREAM1");return;}
    if(m.length==5&&m[0].equals("LM4")&&m[1].equals("FETCHSTREAM")){ResumableTransfer.serve(this,s,m,h[2],fingerprint);return;}
    if(m.length==6&&m[0].equals("LM4")&&m[1].equals("GROUP")){acceptGroup(m,h[2],fingerprint);write(s,"LM4\tGROUPACK\t"+m[2]);notifyChanged();return;}
    if(m.length==4&&m[0].equals("LM4")&&m[1].equals("SEEN")&&uuid(m[2])&&m[3].equals(h[2])){markSeen(m[2],h[2]);write(s,"LM4\tSEENACK\t"+m[2]);notifyChanged();return;}
    if(m.length==4&&m[0].equals("LM4")&&m[1].equals("SYNCREQ2")&&uuid(m[2])){handleSync(s,m[2],m[3],h[2]);notifyChanged();return;}
    if(m.length==5&&m[0].equals("LM4")&&m[1].equals("AVATAR")&&m[2].equals(h[2])){handleAvatar(s,m[2],m[3],m[4]);notifyChanged();return;}
    if(m.length==6&&m[0].equals("LM4")&&m[1].equals("FETCH")){serveDownload(s,m,h[2]);return;}
    if((m.length!=8&&m.length!=12&&m.length!=13)||!m[0].equals("LM4")||(!m[1].equals("MSG")&&!m[1].equals("OFFER"))||!uuid(m[2])||!m[3].equals(h[2])||!m[4].equals(id))return;
    long at=Long.parseLong(m[6]);if(at<0||at>253402300799999L)return;
    String text=dec(m[7]),group=m.length>=12?m[8]:"",name=m.length>=12?dec(m[9]):"",hash=m.length>=12?m[11]:"";long size=m.length>=12?Long.parseLong(m[10]):0;String signature=m.length==13?m[12]:"";
    if(m.length==13&&group.isEmpty())return;
    if(text.length()>2000||(name.isEmpty()&&text.trim().isEmpty()))return;
    if(!group.isEmpty()&&System.currentTimeMillis()>at+GROUP_TTL_MS)return;
    if(!group.isEmpty()&&signature.isEmpty())return;
    if(!signature.isEmpty()){
      String peerKey;synchronized(this){Peer sp=peers.get(h[2]);peerKey=sp!=null?sp.publicKey:"";}
      if(peerKey.isEmpty()||!SecureIdentity.verify(Base64.getDecoder().decode(peerKey),canonicalBytes(m[2],group,m[3],at,text,name,size,hash),Base64.getDecoder().decode(signature)))return;
    }
    validateFile(name,size,hash);synchronized(this){if(!allowedGroup(group,h[2]))return;}
    Message msg=new Message(m[2],m[3],id,text,at,"Received",group,name,size,hash,signature,!signature.isEmpty());
    if(!name.isEmpty()&&!m[1].equals("OFFER"))return; // Refuse unsolicited payloads, even from old clients.
    Message incoming=null;
    // A retransmitted duplicate of a message we already have must never delete the attachment we
    // already legitimately stored for it — only a cleared/hidden conversation (never resurrect)
    // or an outright-rejected message should ever remove a file here.
    synchronized(this){
      boolean stillPresent=false;for(Message old:messages)if(old.id.equals(m[2])&&old.from.equals(m[3])){stillPresent=true;break;}
      if(!trusted(h[2],fingerprint)||!allowedGroup(group,h[2])){if(!name.isEmpty()&&!stillPresent)try{attachmentPath(msg).delete();}catch(IOException ignored){}return;}
      boolean cleared=hidden.contains(h[2]+"/"+m[2]);
      if(!cleared&&!stillPresent){messages.add(msg);try{save();}catch(IOException e){messages.remove(msg);if(!name.isEmpty())try{attachmentPath(msg).delete();}catch(IOException ignored){}throw e;}incoming=msg;}
      else if(!name.isEmpty()&&!stillPresent)try{attachmentPath(msg).delete();}catch(IOException ignored){}
    }

    if(incoming!=null)try{received.accept(incoming);}catch(Exception ignored){}
    write(s,"LM4\tACK\t"+m[2]+"\t"+id);notifyChanged();
  }catch(Exception ignored){}}
  static final int MAX_AVATAR_SIZE=2_000_000;
  void handleAvatar(Socket s,String senderId,String hash,String lengthText)throws IOException {
    int length;try{length=Integer.parseInt(lengthText);}catch(NumberFormatException e){return;}
    if(length<0||length>MAX_AVATAR_SIZE)return;
    byte[] data=length>0?readBytes(s,length):new byte[0];
    try{if(length>0&&!SecureIdentity.hash(data).equals(hash))return;}catch(Exception e){throw new IOException(e);}
    synchronized(this){Peer p=peers.get(senderId);if(p==null||!p.trusted())return;}
    setPeerAvatar(senderId,length>0?data:null);
    synchronized(this){Peer p=peers.get(senderId);if(p==null)return;String old=p.receivedAvatarHash;p.receivedAvatarHash=hash;try{save();}catch(IOException e){p.receivedAvatarHash=old;throw e;}}
    write(s,"LM4\tAVATARACK\t"+hash);
  }
  synchronized void markSeen(String messageId,String readerId)throws IOException{Message row=null;for(Message x:messages)if(x.id.equals(messageId)&&x.from.equals(id)&&x.to.equals(readerId)&&!x.status.equals("Seen")){row=x;break;}if(row==null)return;String old=row.status;row.status="Seen";try{save();}catch(IOException e){row.status=old;throw e;}}
  static byte[] canonicalBytes(String msgId,String group,String sender,long time,String text,String fileName,long fileSize,String fileHash){
    return (msgId+"\t"+group+"\t"+sender+"\t"+time+"\t"+enc(text)+"\t"+enc(fileName)+"\t"+fileSize+"\t"+fileHash).getBytes(StandardCharsets.UTF_8);
  }
  void handleSync(SSLSocket s,String group,String knownIdsCsv,String peerId){
    HashSet<String> known=new HashSet<>();if(!knownIdsCsv.isEmpty())known.addAll(Arrays.asList(knownIdsCsv.split(",",-1)));
    ArrayList<Message> offer=new ArrayList<>();
    synchronized(this){
      if(allowedGroup(group,peerId)){LinkedHashSet<String> seen=new LinkedHashSet<>();for(Message m:messages)if(m.groupId.equals(group)&&!m.signature.isEmpty()&&System.currentTimeMillis()<=m.time+GROUP_TTL_MS&&!known.contains(m.id)&&seen.add(m.id))offer.add(m);}
    }
    try{
      for(Message m:offer){
        write(s,"LM4\tMETA\t"+m.id+"\t"+group+"\t"+m.from+"\t"+enc(displayName(m.from))+"\t"+m.time+"\t"+enc(m.text)+"\t"+enc(m.fileName)+"\t"+m.fileSize+"\t"+m.fileHash+"\t"+m.signature);
        // Data moves only after the receiver requests FETCH.
        if(!read(s).equals("LM4\tRELAYACK\t"+m.id))return;
      }
      write(s,"LM4\tSYNCDONE");
    }catch(Exception ignored){}
  }
  void purgeExpired()throws IOException{
    ArrayList<Message> expired=new ArrayList<>();
    synchronized(this){for(Message m:messages)if(m.ttlEligible&&!m.groupId.isEmpty()&&System.currentTimeMillis()>m.time+GROUP_TTL_MS)expired.add(m);
      if(expired.isEmpty())return;
      ArrayList<Message> old=new ArrayList<>(messages);messages.removeAll(expired);
      try{save();}catch(IOException e){messages.clear();messages.addAll(old);throw e;}
    }
    for(Message m:expired)if(!m.fileName.isEmpty())deleteTransfer(m);
    notifyChanged();
  }
  public synchronized void markRead(String conversation)throws IOException {
    ArrayList<Message> changed=new ArrayList<>();for(Message m:messages)if(m.to.equals(id)&&m.status.equals("Received")&&(!m.groupId.isEmpty()?m.groupId.equals(conversation):m.from.equals(conversation)))changed.add(m);
    if(changed.isEmpty())return;
    for(Message m:changed)m.status="Read";
    try{save();}catch(IOException e){for(Message m:changed)m.status="Received";throw e;}
    notifyChanged();
  }
  public synchronized int unread(String conversation){int n=0;for(Message m:messages)if(m.to.equals(id)&&m.status.equals("Received")&&(!m.groupId.isEmpty()?m.groupId.equals(conversation):m.from.equals(conversation)))n++;return n;}
  final java.util.concurrent.atomic.AtomicLong queueEpoch=new java.util.concurrent.atomic.AtomicLong();
  void flush(){if(!running)return;queueImageDownloads();for(Peer p:peers())startDelivery(p);}
  void startDelivery(Peer p){if(!running||!sending.add(p.id))return;try{outgoing.execute(()->{long observed=-1;try{do{observed=queueEpoch.get();deliver(p);}while(running&&observed!=queueEpoch.get());}finally{sending.remove(p.id);if(running&&observed!=queueEpoch.get())startDelivery(p);}});}catch(RejectedExecutionException e){sending.remove(p.id);}}
  void deliver(Peer p){
    try(Socket s=connect(p.host,p.port)){write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id))return;remember(h[2],dec(h[3]),p.host,Integer.parseInt(h[4]));recordCertificate(h[2],SecureIdentity.remote((SSLSocket)s),SecureIdentity.remotePublicKey((SSLSocket)s));}catch(Exception e){return;}
    for(Group g:groups())if(g.owner.equals(id)&&Arrays.asList(g.members).contains(p.id)&&!Arrays.asList(g.acknowledged.split(",")).contains(p.id))try(Socket s=connect(p.host,p.port)){
      String fingerprint=SecureIdentity.remote((SSLSocket)s);if(!trusted(p.id,fingerprint))return;write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id)||!read(s).equals("LM4\tREADY"))return;
      write(s,"LM4\tGROUP\t"+g.id+"\t"+g.owner+"\t"+enc(g.name)+"\t"+String.join(",",g.members));if(!read(s).equals("LM4\tGROUPACK\t"+g.id)||!trusted(p.id,fingerprint))return;
      synchronized(this){Group current=groups.get(g.id);String old=current.acknowledged;current.acknowledged=old.isEmpty()?p.id:old+","+p.id;try{save();}catch(IOException e){current.acknowledged=old;throw e;}}
    }catch(Exception e){return;}
    ArrayList<Message> queued=new ArrayList<>();synchronized(this){for(Message m:messages)if(m.to.equals(p.id)&&m.status.equals("Queued"))queued.add(m);}
    for(Message m:queued)try(Socket s=connect(p.host,p.port)){String fingerprint=SecureIdentity.remote((SSLSocket)s);if(!trusted(p.id,fingerprint))return;write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id))return;recordCertificate(h[2],fingerprint,SecureIdentity.remotePublicKey((SSLSocket)s));if(!read(s).equals("LM4\tREADY")||!trusted(p.id,fingerprint))return;
      boolean exists;synchronized(this){exists=messages.contains(m);}if(!exists)continue;
      write(s,"LM4\t"+(m.fileName.isEmpty()?"MSG":"OFFER")+"\t"+m.id+"\t"+id+"\t"+m.to+"\t"+enc(name)+"\t"+m.time+"\t"+enc(m.text)+"\t"+m.groupId+"\t"+enc(m.fileName)+"\t"+m.fileSize+"\t"+m.fileHash+(m.groupId.isEmpty()?"":"\t"+m.signature));
      // Data moves only after the receiver requests FETCH.
      String ack=read(s);if(!ack.equals("LM4\tACK\t"+m.id+"\t"+p.id)||!trusted(p.id,fingerprint))return;synchronized(this){if(!messages.contains(m))continue;m.status="Delivered";try{save();}catch(IOException e){m.status="Queued";throw e;}}notifyChanged();}catch(Exception e){return;}
    ArrayList<Message> toConfirm=new ArrayList<>();synchronized(this){for(Message m:messages)if(m.to.equals(id)&&m.from.equals(p.id)&&m.status.equals("Read"))toConfirm.add(m);}
    for(Message m:toConfirm)try(Socket s=connect(p.host,p.port)){String fingerprint=SecureIdentity.remote((SSLSocket)s);if(!trusted(p.id,fingerprint))return;write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id))return;recordCertificate(h[2],fingerprint,SecureIdentity.remotePublicKey((SSLSocket)s));if(!read(s).equals("LM4\tREADY")||!trusted(p.id,fingerprint))return;
      write(s,"LM4\tSEEN\t"+m.id+"\t"+id);
      if(!read(s).equals("LM4\tSEENACK\t"+m.id)||!trusted(p.id,fingerprint))return;
      synchronized(this){if(!messages.contains(m))continue;m.status="Seen";try{save();}catch(IOException e){m.status="Read";throw e;}}notifyChanged();
    }catch(Exception e){return;}
    for(Group g:groups())if(Arrays.asList(g.members).contains(id)&&Arrays.asList(g.members).contains(p.id))try(Socket s=connect(p.host,p.port)){
      String fp=SecureIdentity.remote((SSLSocket)s);if(!trusted(p.id,fp))return;write(s,hello());String[] hello=read(s).split("\t",-1);if(!validHello(hello)||!hello[2].equals(p.id)||!read(s).equals("LM4\tREADY"))return;
      ArrayList<String> known=new ArrayList<>();LinkedHashSet<String> seen=new LinkedHashSet<>();synchronized(this){for(Message m:messages)if(m.groupId.equals(g.id)&&!m.signature.isEmpty()&&System.currentTimeMillis()<=m.time+GROUP_TTL_MS&&seen.add(m.id))known.add(m.id);}
      write(s,"LM4\tSYNCREQ2\t"+g.id+"\t"+String.join(",",known));
      while(true){
        String[] reply=read(s).split("\t",-1);
        if(reply.length==2&&reply[0].equals("LM4")&&reply[1].equals("SYNCDONE"))break;
        if(reply.length!=12||!reply[0].equals("LM4")||!reply[1].equals("META")||!uuid(reply[2])||!reply[3].equals(g.id))return;
        String rid=reply[2],origSender=reply[4];long at;long fileSize;
        try{at=Long.parseLong(reply[6]);fileSize=Long.parseLong(reply[9]);}catch(NumberFormatException e){write(s,"LM4\tRELAYACK\t"+rid);continue;}
        if(at<0||at>System.currentTimeMillis()+300000||!uuid(origSender)){write(s,"LM4\tRELAYACK\t"+rid);continue;}
        String rtext=dec(reply[7]),rfileName=dec(reply[8]),rfileHash=reply[10],rsignature=reply[11];
        if(System.currentTimeMillis()>at+GROUP_TTL_MS){write(s,"LM4\tRELAYACK\t"+rid);continue;}
        if(rtext.length()>2000||(rfileName.isEmpty()&&rtext.trim().isEmpty()))return;
        validateFile(rfileName,fileSize,rfileHash);
        Message msg=new Message(rid,origSender,id,rtext,at,"Received",g.id,rfileName,fileSize,rfileHash,rsignature,true);
        boolean fileOk=true;

        Message incoming=null;
        // As in receive(): a re-sync of a message we already have must never delete the
        // attachment we already legitimately stored for it.
        if(fileOk){
          String origKey;synchronized(this){Peer op=peers.get(origSender);origKey=op!=null&&op.trusted()?op.publicKey:"";}
          boolean verified=!origKey.isEmpty()&&SecureIdentity.verify(Base64.getDecoder().decode(origKey),canonicalBytes(rid,g.id,origSender,at,rtext,rfileName,fileSize,rfileHash),Base64.getDecoder().decode(rsignature));
          synchronized(this){
            boolean stillPresent=false;for(Message old:messages)if(old.id.equals(rid)&&old.from.equals(origSender)){stillPresent=true;break;}
            if(verified&&allowedGroup(g.id,origSender)&&!hidden.contains(origSender+"/"+rid)&&!stillPresent){
              messages.add(msg);try{save();}catch(IOException e){messages.remove(msg);if(!rfileName.isEmpty())try{attachmentPath(msg).delete();}catch(IOException ignored){}throw e;}incoming=msg;
            }else if(!rfileName.isEmpty()&&!stillPresent)try{attachmentPath(msg).delete();}catch(IOException ignored){}
          }
        }
        write(s,"LM4\tRELAYACK\t"+rid);
        if(incoming!=null){try{received.accept(incoming);}catch(Exception ignored){}notifyChanged();}
      }
    }catch(Exception e){return;}
    if(p.trusted()){
      byte[] avatarData=avatar();String hash;try{hash=avatarData!=null?SecureIdentity.hash(avatarData):"";}catch(Exception e){return;}
      if(!p.sentAvatarHash.equals(hash))try(Socket s=connect(p.host,p.port)){
        String fingerprint=SecureIdentity.remote((SSLSocket)s);if(!trusted(p.id,fingerprint))return;write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id))return;recordCertificate(h[2],fingerprint,SecureIdentity.remotePublicKey((SSLSocket)s));if(!read(s).equals("LM4\tREADY")||!trusted(p.id,fingerprint))return;
        write(s,"LM4\tAVATAR\t"+id+"\t"+hash+"\t"+(avatarData!=null?avatarData.length:0));
        if(avatarData!=null&&avatarData.length>0){OutputStream out=new NetworkTimeoutOutputStream(s);out.write(avatarData);out.flush();}
        if(!read(s).equals("LM4\tAVATARACK\t"+hash)||!trusted(p.id,fingerprint))return;
        synchronized(this){Peer current=peers.get(p.id);if(current==null)return;String old=current.sentAvatarHash;current.sentAvatarHash=hash;try{save();}catch(IOException e){current.sentAvatarHash=old;throw e;}}notifyChanged();
      }catch(Exception e){return;}
    }
  }



  public static final long MAX_FAST_FILE_SIZE=1024L*1024*1024*1024;
  static final long SEGMENT_SIZE=100L*1024*1024;
  static final int TRANSFER_READ_TIMEOUT_MS=45_000;
  final Semaphore fileSlots=new Semaphore(2);
  final ConcurrentHashMap<String,Boolean> downloads=new ConcurrentHashMap<>();
  final ConcurrentHashMap<String,Socket> downloadSockets=new ConcurrentHashMap<>();
  final ConcurrentHashMap<String,String> downloadNotes=new ConcurrentHashMap<>();
  final ConcurrentHashMap<String,DownloadDestination> destinations=new ConcurrentHashMap<>();
  public interface DestinationOpener {DownloadDestination.FileHandle open(String reference)throws IOException;}
  public volatile DestinationOpener destinationOpener=DownloadDestination::local;
  public String savedDestination(Message m){DownloadDestination value=DownloadDestination.get(this,m);if(value.complete)return value.reference;try{if(m.from.equals(id)&&sourcePath(m).exists())return new String(protector.unprotect(SecureIdentity.readFile(sourcePath(m))),StandardCharsets.UTF_8);}catch(Exception ignored){}return "";}
  public String pendingDestination(Message m){DownloadDestination value=DownloadDestination.get(this,m);return value.complete?"":value.reference;}
  public void downloadTo(Message m,String reference)throws IOException {if(!reference.equals(savedDestination(m)))DirectFileTransfer.download(this,m,reference);}
  boolean isFastAttachment(Message m)throws IOException{return sourcePath(m).exists()||DownloadDestination.get(this,m).fast;}
  public String downloadNote(Message m){String value=downloadNotes.get(m.from+"/"+m.id);return value==null?"":value;}
  void downloadNote(Message m,String value){String old=downloadNotes.put(m.from+"/"+m.id,value);if(!value.equals(old))notifyChanged();}
  public interface SourceOpener{InputStream open(String reference)throws IOException;}
  public volatile SourceOpener sourceOpener=reference->new FileInputStream(reference);
  File sourcePath(Message m)throws IOException{return new File(attachmentPath(m)+".source");}
  File partsPath(Message m)throws IOException{return new File(attachmentPath(m)+".parts");}
  File segmentPath(Message m,int part)throws IOException{return new File(partsPath(m),part+".sec");}
  public boolean hasAttachment(Message m){try{return !m.fileName.isEmpty()&&(DownloadDestination.get(this,m).complete||attachmentPath(m).exists()||sourcePath(m).exists()||new File(partsPath(m),"complete").exists());}catch(Exception e){return false;}}
  synchronized boolean retained(Message m){if(hidden.contains(m.from+"/"+m.id)||(m.ttlEligible&&System.currentTimeMillis()>m.time+GROUP_TTL_MS))return false;for(Message row:messages)if(row.from.equals(m.from)&&row.id.equals(m.id))return true;return false;}
  final Semaphore imageSlots=new Semaphore(2);
  final Set<String> imageAttempts=ConcurrentHashMap.newKeySet();
  public static boolean isImageAttachment(Message m){String name=m.fileName.toLowerCase(Locale.ROOT);int dot=name.lastIndexOf('.');return dot>=0&&Arrays.asList(".jpg",".jpeg",".png",".gif",".bmp",".webp",".tif",".tiff",".heic",".heif",".avif").contains(name.substring(dot));}
  void queueImageDownloads(){
    if(!running)return;ArrayList<Message> images=new ArrayList<>();
    synchronized(this){for(Message m:messages)if(!m.from.equals(id)&&isImageAttachment(m))images.add(m.copy());}
    for(Message m:images){
      String key=m.from+"/"+m.id;
      if(hasAttachment(m)||downloading(m)||imageAttempts.contains(key)||!retained(m))continue;
      boolean reachable=false;for(Peer p:peers())if(p.trusted()&&p.online()&&(p.id.equals(m.from)||(!m.groupId.isEmpty()&&allowedGroup(m.groupId,p.id)))){reachable=true;break;}
      if(!reachable)continue;if(!imageSlots.tryAcquire())break;
      if(!imageAttempts.add(key)){imageSlots.release();continue;}
      Thread worker=new Thread(()->{try{downloadAttachment(m);}catch(Exception ignored){}finally{imageSlots.release();notifyChanged();}},"lan-image-download");worker.setDaemon(true);worker.start();
    }
  }
  public boolean downloading(Message m){return downloads.containsKey(m.from+"/"+m.id);}
  public void cancelDownload(Message m){String key=m.from+"/"+m.id;downloads.computeIfPresent(key,(k,value)->false);Socket socket=downloadSockets.get(key);if(socket!=null)try{socket.close();}catch(IOException ignored){}}
  void deleteTransfer(Message m){cancelDownload(m);downloadNotes.remove(m.from+"/"+m.id);try{DownloadDestination.forget(this,m);attachmentPath(m).delete();new File(attachmentPath(m)+".resume").delete();sourcePath(m).delete();deleteParts(m);}catch(Exception ignored){}}
  void deleteParts(Message m)throws IOException{File dir=partsPath(m);File[] children=dir.listFiles();if(children!=null)for(File child:children)child.delete();dir.delete();}
  String hashStream(InputStream in)throws IOException{try{MessageDigest hash=MessageDigest.getInstance("SHA-256");byte[] b=new byte[256*1024];int n;while((n=in.read(b))!=-1)hash.update(b,0,n);return hex(hash.digest());}catch(GeneralSecurityException e){throw new IOException(e);}}
  static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
  public void queueFastFile(String conversation,String caption,String reference,long size,String name)throws IOException{
    if(size<0||size>MAX_FAST_FILE_SIZE)throw new IOException("Fast transfer limit is 1 TiB.");
    caption=caption.trim();if(caption.length()>2000)throw new IOException("Caption too long.");
    ArrayList<String> recipients=new ArrayList<>();String group="";
    synchronized(this){Group g=groups.get(conversation);if(g!=null){group=g.id;for(String member:g.members)if(!member.equals(id))recipients.add(member);}else if(peers.containsKey(conversation))recipients.add(conversation);else throw new IOException("Choose a conversation");}
    String hash;try(InputStream in=sourceOpener.open(reference)){if(in==null)throw new IOException("Source unavailable");try{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[262144];long total=0;int n;while((n=in.read(buffer))!=-1){total+=n;if(total>size)throw new IOException("Source changed");digest.update(buffer,0,n);}if(total!=size)throw new IOException("Source changed");hash=hex(digest.digest());}catch(GeneralSecurityException error){throw new IOException(error);}}
    String mid=UUID.randomUUID().toString();long at=System.currentTimeMillis();name=safeFileName(name);String signature="";
    try{if(!group.isEmpty())signature=Base64.getEncoder().encodeToString(identity.sign(canonicalBytes(mid,group,id,at,caption,name,size,hash)));}catch(Exception e){throw new IOException(e);}
    ArrayList<Message> batch=new ArrayList<>();for(String to:recipients)batch.add(new Message(mid,id,to,caption,at,"Queued",group,name,size,hash,signature,!group.isEmpty()));
    File path=sourcePath(batch.get(0));path.getParentFile().mkdirs();
    try(FileOutputStream out=new FileOutputStream(path)){out.write(protector.protect(reference.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IOException(e);}
    synchronized(this){messages.addAll(batch);try{save();}catch(IOException e){messages.removeAll(batch);path.delete();throw e;}}
    notifyChanged();queueEpoch.incrementAndGet();flush();
  }
  InputStream openContent(Message m)throws IOException{
    DownloadDestination destination=DownloadDestination.get(this,m);if(destination.complete)return sourceOpener.open(destination.reference);
    File source=sourcePath(m);
    if(source.exists())try{return sourceOpener.open(new String(protector.unprotect(SecureIdentity.readFile(source)),StandardCharsets.UTF_8));}catch(Exception e){throw new IOException(e);}
    if(attachmentPath(m).exists())return openAttachmentPlaintext(attachmentPath(m));
    if(!new File(partsPath(m),"complete").exists())throw new IOException("Download this attachment first.");
    return openParts(m);
  }
  InputStream openParts(Message m)throws IOException{return openParts(m,0);}
  InputStream openParts(Message m,int start)throws IOException{
    final int count=(int)Math.max(1,(m.fileSize+SEGMENT_SIZE-1)/SEGMENT_SIZE);
    return new InputStream(){int index=start;InputStream current;
      public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
      public int read(byte[] b,int offset,int length)throws IOException{
        if(length==0)return 0;
        while(true){if(current==null){if(index>=count)return -1;current=openAttachmentPlaintext(segmentPath(m,index++));}int n=current.read(b,offset,length);if(n>=0)return n;current.close();current=null;}
      }
      public void close()throws IOException{if(current!=null)current.close();}
    };
  }
  void serveDownload(Socket s,String[] request,String peerId)throws Exception{
    if(!uuid(request[2])||!uuid(request[3]))return;
    long offset,count;try{offset=Long.parseLong(request[4]);count=Long.parseLong(request[5]);}catch(Exception e){return;}
    Message m=null;synchronized(this){for(Message row:messages)if(row.from.equals(request[2])&&row.id.equals(request[3])&&(!row.groupId.isEmpty()?allowedGroup(row.groupId,peerId):row.from.equals(id)&&row.to.equals(peerId))){m=row;break;}}
    if(m==null||!retained(m)||!hasAttachment(m)||offset<0||count<0||count>SEGMENT_SIZE||offset>m.fileSize||count>m.fileSize-offset||offset%SEGMENT_SIZE!=0||count!=Math.min(SEGMENT_SIZE,m.fileSize-offset)){write(s,"LM4\tUNAVAILABLE");return;}
    if(isFastAttachment(m)){write(s,"LM4\tFASTONLY");return;}
    if(!fileSlots.tryAcquire()){write(s,"LM4\tBUSY");return;}
    boolean sourceReference=sourcePath(m).exists()||DownloadDestination.get(this,m).complete;
    File stored=attachmentPath(m);
    boolean storedSnapshot=!sourceReference&&stored.exists();
    boolean segmented=!sourceReference&&!storedSnapshot;
    try(InputStream source=segmented?openParts(m,(int)(offset/SEGMENT_SIZE)):storedSnapshot?openAttachmentPlaintext(stored,offset):openContent(m)){
      long skip=segmented||storedSnapshot?0:offset;while(skip>0){long n=source.skip(skip);if(n==0){if(source.read()<0)throw new EOFException();n=1;}skip-=n;}
      s.setSoTimeout(TRANSFER_READ_TIMEOUT_MS);
      write(s,"LM4\tDATA\t"+m.id+"\t"+offset+"\t"+count);
      MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IOException(e);}
      OutputStream out=new NetworkTimeoutOutputStream(s);byte[] buffer=new byte[256*1024];long done=0;
      // The certificate is fixed for this TLS session. Recheck its pin, but do not hash and
      // format the certificate again for every small CipherInputStream read.
      String transferFingerprint=SecureIdentity.remote((SSLSocket)s);
      while(done<count){
        if(!retained(m)||!trusted(peerId,transferFingerprint))throw new IOException("Transfer no longer authorized");
        int wanted=(int)Math.min(buffer.length,count-done),n=0;
        // CipherInputStream may return just one small internal buffer. Fill a network block
        // before scheduling a watchdog, checking the quota and writing a TLS record batch.
        while(n<wanted){int got=source.read(buffer,n,wanted-n);if(got<0)throw new EOFException();if(got>0)n+=got;}
        digest.update(buffer,0,n);uploadPolicy.write(out,buffer,0,n);done+=n;
      }
      write(s,"LM4\tPART\t"+hex(digest.digest()));
    }finally{try{uploadPolicy.flush();}finally{fileSlots.release();}}
  }
  public void downloadAttachment(Message m)throws IOException{
    if(m.from.equals(id)||hasAttachment(m))return;
    String key=m.from+"/"+m.id;if(downloads.putIfAbsent(key,true)!=null)return;
    try{
      downloadNote(m,"");
      if(ResumableTransfer.download(this,m,key))return;
      downloadNote(m,"Older sender: update both phones for continuous resume");
      partsPath(m).mkdirs();int parts=(int)Math.max(1,(m.fileSize+SEGMENT_SIZE-1)/SEGMENT_SIZE);
      for(int i=0;i<parts;i++){
        long offset=i*SEGMENT_SIZE,count=Math.min(SEGMENT_SIZE,m.fileSize-offset);File part=segmentPath(m,i),pending=new File(part+".pending");
        if(part.exists()){reportProgress(m.id,offset+count,m.fileSize);continue;}
        while(true){
          if(!running||!Boolean.TRUE.equals(downloads.get(key)))throw new IOException("Download paused");
          if(!retained(m))throw new IOException("Attachment cleared or expired");
          boolean fetched=false;
          for(Peer p:peers()){
            if(!p.trusted()||(!m.groupId.isEmpty()?!allowedGroup(m.groupId,p.id):!p.id.equals(m.from)))continue;
            try(Socket s=connect(p.host,p.port)){
              String fp=SecureIdentity.remote((SSLSocket)s);if(!trusted(p.id,fp))continue;
              write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id)||!read(s).equals("LM4\tREADY"))continue;
              write(s,"LM4\tFETCH\t"+m.from+"\t"+m.id+"\t"+offset+"\t"+count);
              s.setSoTimeout(TRANSFER_READ_TIMEOUT_MS);
              String response=read(s);if(response.equals("LM4\tFASTONLY"))throw new DirectFileTransfer.DestinationRequired();
              if(!response.equals("LM4\tDATA\t"+m.id+"\t"+offset+"\t"+count))continue;
              final long base=offset;
              String hash=storeAttachmentStreamAt(m,s.getInputStream(),count,(done,total)->{
                if(!Boolean.TRUE.equals(downloads.get(key)))try{s.close();}catch(IOException ignored){}
                reportProgress(m.id,base+done,m.fileSize);
              },pending);
              if(!read(s).equals("LM4\tPART\t"+hash))throw new IOException("Segment integrity check failed");
              if(!retained(m)||!trusted(p.id,fp))throw new IOException("Attachment no longer authorized");
              if(!pending.renameTo(part))throw new IOException("Cannot commit segment");
              fetched=true;break;
            }catch(DirectFileTransfer.DestinationRequired required){throw required;}
            catch(Exception e){pending.delete();System.err.println("LAN Messenger download retry: segment "+i+" from "+p.id+": "+e);}
          }
          if(fetched)break;
          try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Download paused",e);}
        }
      }
      String hash;try(InputStream in=openParts(m)){hash=hashStream(in);}
      if(!hash.equals(m.fileHash)){deleteParts(m);throw new IOException("Source changed or attachment is corrupt. Retry download.");}
      if(!retained(m))throw new IOException("Attachment cleared or expired");
      if(!Boolean.TRUE.equals(downloads.get(key)))throw new IOException("Download paused");
      try(FileOutputStream out=new FileOutputStream(new File(partsPath(m),"complete"))){out.write(hash.getBytes(StandardCharsets.US_ASCII));}
      notifyChanged();
    }catch(IOException failure){downloadNote(m,String.valueOf(failure.getMessage()));throw failure;}
    finally{downloads.remove(key);downloadSockets.remove(key);lastReportedPercent.remove(m.id);notifyChanged();}
  }

  public static final int MAX_FILE_SIZE=1024*1024*1024;
  // Chunk size for every streamed read/write (local store, export, network) — attachments up to
  // MAX_FILE_SIZE are never buffered whole in memory at any step; peak memory stays near this size.
  static final int CHUNK_SIZE=3*1024*1024;
  static final byte[] ATTACHMENT_MAGIC="LMATCS1".getBytes(StandardCharsets.US_ASCII);
  static final long CHUNK_WRITE_TIMEOUT_MS=45_000;
  static final ScheduledThreadPoolExecutor WRITE_WATCHDOGS=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"lan-write-watchdog");t.setDaemon(true);return t;});
  static { WRITE_WATCHDOGS.setRemoveOnCancelPolicy(true); }
  // Socket has no native write timeout (unlike the read timeout set once at connect() time via
  // setSoTimeout) — a peer that stops reading could otherwise block a sender's thread forever,
  // and with it, one of this process's few shared connection-handling threads. A watchdog closes
  // the socket if a single chunk write doesn't complete in time.
  static final class NetworkTimeoutOutputStream extends OutputStream {
    final Socket socket;final OutputStream inner;
    NetworkTimeoutOutputStream(Socket socket)throws IOException{this.socket=socket;this.inner=socket.getOutputStream();}
    @Override public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}
    @Override public void write(byte[] b,int off,int len)throws IOException{
      ScheduledFuture<?> guard=WRITE_WATCHDOGS.schedule(()->{try{socket.close();}catch(IOException ignored){}},CHUNK_WRITE_TIMEOUT_MS,TimeUnit.MILLISECONDS);
      try{inner.write(b,off,len);}finally{guard.cancel(false);}
    }
    @Override public void flush()throws IOException{inner.flush();}
  }
  // A generous floor plus ~1s/MB tolerates slow Wi-Fi without making small transfers wait needlessly.
  static long transferTimeoutNanos(int size){return TimeUnit.SECONDS.toNanos(Math.max(60,30+size/1_000_000));}
  public static final class Group {
    public final String id,owner,name;public final String[] members;String acknowledged;
    Group(String i,String o,String n,String[] m,String ack){id=i;owner=o;name=n;members=m.clone();acknowledged=ack;}
  }
  final LinkedHashMap<String,Group> groups=new LinkedHashMap<>();
  final HashSet<String> hidden=new HashSet<>();
  public synchronized List<Group> groups(){ArrayList<Group> result=new ArrayList<>();for(Group g:groups.values())result.add(new Group(g.id,g.owner,g.name,g.members,g.acknowledged));return result;}
  public synchronized String displayName(String target){return target.equals(id)?name:groups.containsKey(target)?groups.get(target).name:peers.containsKey(target)?peers.get(target).name:"Device "+target.substring(0,Math.min(8,target.length()));}
  public synchronized String createGroup(String name,List<String> members)throws IOException {
    name=name.trim();TreeSet<String> ids=new TreeSet<>(members);ids.add(id);
    if(name.isEmpty()||name.length()>50||ids.size()<3||ids.size()>16)throw new IOException("Name the group and select 2–15 verified contacts.");
    for(String target:ids)if(!target.equals(id)&&(!peers.containsKey(target)||!peers.get(target).trusted()))throw new IOException("Select verified contacts.");
    Group g=new Group(UUID.randomUUID().toString(),id,name,ids.toArray(new String[0]),"");groups.put(g.id,g);try{save();}catch(IOException e){groups.remove(g.id);throw e;}notifyChanged();return g.id;
  }
  synchronized boolean allowedGroup(String group,String sender){Group g=groups.get(group);return group.isEmpty()||(g!=null&&Arrays.asList(g.members).contains(id)&&Arrays.asList(g.members).contains(sender));}
  synchronized void acceptGroup(String[] a,String sender,String fingerprint)throws IOException {
    String[] members=a[5].split(",",-1);String name=dec(a[4]);List<String> ids=Arrays.asList(members);
    if(!trusted(sender,fingerprint)||!uuid(a[2])||!a[3].equals(sender)||name.trim().isEmpty()||name.length()>50||ids.size()<3||ids.size()>16||new HashSet<>(ids).size()!=ids.size()||!ids.contains(id)||!ids.contains(sender)||peers.containsKey(a[2])||a[2].equals(id))throw new IOException("Invalid group invitation");
    for(String member:members)if(!uuid(member))throw new IOException("Invalid member");
    Group old=groups.get(a[2]);if(old!=null){if(!old.owner.equals(sender)||!old.name.equals(name)||!Arrays.equals(old.members,members))throw new IOException("Group membership cannot be replaced");return;}
    groups.put(a[2],new Group(a[2],sender,name,members,""));try{save();}catch(IOException e){groups.remove(a[2]);throw e;}
  }
  public void queueFile(String conversation,String name,byte[] data)throws IOException {queueFile(conversation,"",name,data);}
  public void queueFile(String conversation,String caption,String name,byte[] data)throws IOException {
    queueContentStream(conversation,caption,safeFileName(name),new ByteArrayInputStream(data),data.length,null);
  }
  // Streams from an already-open source (e.g. a content:// picker or camera-capture InputStream)
  // straight into the encrypted attachment store — the file is never held whole in memory, which
  // is what lets an attachment be as large as MAX_FILE_SIZE (1 GB) without risking an OOM on a phone.
  // Must be called off the UI thread: this does blocking file/crypto I/O.
  public void queueFileStream(String conversation,String caption,InputStream source,long size,String name,BiConsumer<Long,Long> onProgress)throws IOException {
    queueContentStream(conversation,caption,safeFileName(name),source,size,onProgress);
  }
  void queueContentStream(String conversation,String text,String fileName,InputStream data,long declaredSize,BiConsumer<Long,Long> onProgress)throws IOException {
    queueContentStream(conversation,text,fileName,data,declaredSize,onProgress,true);
  }
  void queueContentStream(String conversation,String text,String fileName,InputStream data,long declaredSize,BiConsumer<Long,Long> onProgress,boolean dispatch)throws IOException {
    text=text.trim();if(text.length()>2000||(data==null&&text.isEmpty()))throw new IOException("Messages must contain 1–2000 characters.");
    if(data!=null&&declaredSize>MAX_FILE_SIZE)throw new IOException("Files must be "+(MAX_FILE_SIZE/1024/1024)+" MB or smaller.");
    ArrayList<String> recipients=new ArrayList<>();String groupId="";
    synchronized(this){
      Group group=groups.get(conversation);
      if(group!=null){groupId=group.id;for(String member:group.members)if(!member.equals(id))recipients.add(member);}
      else if(peers.containsKey(conversation))recipients.add(conversation);
      else throw new IOException("Choose a conversation first.");
    }
    String messageId=UUID.randomUUID().toString();long at=System.currentTimeMillis();String hash="";
    if(data!=null){
      Message placeholder=new Message(messageId,id,recipients.get(0),text,at,"Queued",groupId,fileName,(int)declaredSize,"");
      hash=storeAttachmentStream(placeholder,data,declaredSize,onProgress);
    }
    String signature="";if(!groupId.isEmpty())try{signature=Base64.getEncoder().encodeToString(identity.sign(canonicalBytes(messageId,groupId,id,at,text,fileName,(int)declaredSize,hash)));}catch(Exception e){throw new IOException(e);}
    ArrayList<Message> batch=new ArrayList<>();for(String to:recipients)batch.add(new Message(messageId,id,to,text,at,"Queued",groupId,fileName,(int)declaredSize,hash,signature,!groupId.isEmpty()));
    synchronized(this){
      messages.addAll(batch);try{save();}catch(IOException e){messages.removeAll(batch);if(fileName!=null&&!fileName.isEmpty())try{attachmentPath(batch.get(0)).delete();}catch(IOException ignored){}throw e;}
    }
    notifyChanged();queueEpoch.incrementAndGet();if(dispatch)flush();
  }
  public synchronized void clearConversation(String conversation)throws IOException {
    ArrayList<Message> removed=new ArrayList<>(),old=new ArrayList<>(messages);HashSet<String> oldHidden=new HashSet<>(hidden);
    for(Message m:messages)if(!m.groupId.isEmpty()?m.groupId.equals(conversation):m.from.equals(conversation)||m.to.equals(conversation)){removed.add(m);hidden.add(m.from+"/"+m.id);}
    messages.removeAll(removed);try{save();}catch(IOException e){messages.clear();messages.addAll(old);hidden.clear();hidden.addAll(oldHidden);throw e;}save();
    for(Message m:removed)if(!m.fileName.isEmpty())deleteTransfer(m);notifyChanged();
  }
  public static String safeFileName(String name){String[] parts=name.replace('\\','/').split("/",-1);name=parts[parts.length-1];StringBuilder b=new StringBuilder();for(char c:name.toCharArray())if(c>=32&&"<>:\"/\\|?*".indexOf(c)<0)b.append(c);name=b.toString().trim().replaceAll("^\\.+|\\.+$","");return name.isEmpty()?"attachment":name.substring(0,Math.min(120,name.length()));}
  static void validateFile(String name,long size,String hash)throws IOException {if(size<0||size>MAX_FAST_FILE_SIZE||(name.isEmpty()?(size!=0||!hash.isEmpty()):(!name.equals(safeFileName(name))||!hash.matches("[0-9a-f]{64}"))))throw new IOException("Invalid attachment metadata");}
  File attachmentPath(Message m)throws IOException {if(!uuid(m.from)||!uuid(m.id))throw new IOException("Invalid attachment ID");return new File(new File(file.getParentFile(),"attachments"),m.from+"-"+m.id+".sec");}
  // `totalBytes` is read as an exact count, not "until EOF" — required for a network source, which
  // has no natural end-of-stream mid-connection (more protocol frames follow after it). A
  // ByteArrayInputStream/file source works the same way since its length is already known too.
  // Encrypts a stream of plaintext into the attachment file without ever buffering the whole
  // content in memory — required now that attachments can be up to MAX_FILE_SIZE. A random
  // per-attachment AES-256-CBC key/IV (itself wrapped by the small, one-shot OS storage protector)
  // replaces protecting the whole blob at once. Confidentiality-only at rest (no per-chunk
  // authentication); the existing end-to-end SHA-256 hash still catches corruption/tampering,
  // same as before, and the TLS channel content travels over is itself authenticated.
  String storeAttachmentStream(Message m,InputStream source,long totalBytes,BiConsumer<Long,Long> onProgress)throws IOException {return storeAttachmentStreamAt(m,source,totalBytes,onProgress,attachmentPath(m));}
  String storeAttachmentStreamAt(Message m,InputStream source,long totalBytes,BiConsumer<Long,Long> onProgress,File destination)throws IOException {
    File path=destination;if(!path.getParentFile().exists()&&!path.getParentFile().mkdirs())throw new IOException("Cannot create attachment storage");
    File tmp=new File(path+"."+UUID.randomUUID()+".tmp");
    try{
      byte[] key=new byte[32],iv=new byte[16];SecureRandom random=new SecureRandom();random.nextBytes(key);random.nextBytes(iv);
      byte[] combined=new byte[48];System.arraycopy(key,0,combined,0,32);System.arraycopy(iv,0,combined,32,16);
      byte[] header;try{header=protector.protect(combined);}catch(Exception e){throw new IOException(e);}
      MessageDigest digest=MessageDigest.getInstance("SHA-256");
      // Cipher driven manually (update()/doFinal()), not via CipherOutputStream: that class's
      // close() cascades into closing the underlying FileOutputStream, which would leave nothing
      // open for the fsync below to act on (surfaces as a SyncFailedException on a closed fd).
      try(FileOutputStream fileOut=new FileOutputStream(tmp)){
        fileOut.write(ATTACHMENT_MAGIC);fileOut.write(ByteBuffer.allocate(4).putInt(header.length).array());fileOut.write(header);
        Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
        byte[] buffer=new byte[CHUNK_SIZE];long total=0;
        while(total<totalBytes){
          int want=(int)Math.min(CHUNK_SIZE,totalBytes-total);
          int n=source.read(buffer,0,want);
          if(n<=0)throw new EOFException("Attachment transfer ended early");
          digest.update(buffer,0,n);
          byte[] encrypted=cipher.update(buffer,0,n);
          if(encrypted!=null&&encrypted.length>0)fileOut.write(encrypted);
          total+=n;if(onProgress!=null)onProgress.accept(total,totalBytes);
        }
        byte[] finalBlock=cipher.doFinal();
        if(finalBlock!=null&&finalBlock.length>0)fileOut.write(finalBlock);
        fileOut.getFD().sync();
      }
      atomicReplace(tmp,path);
      StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
      return hex.toString();
    }catch(GeneralSecurityException e){tmp.delete();throw new IOException(e);}
    catch(IOException e){tmp.delete();throw e;}
  }
  void storeAttachment(Message m,byte[] data)throws IOException {storeAttachmentStream(m,new ByteArrayInputStream(data),data.length,null);}
  // Opens the attachment's decrypted plaintext as a stream, transparently handling both the
  // current streaming format and attachments stored by versions before it (a single whole-file
  // Unprotect — the only way to read those, since they predate the per-attachment key/IV header).
  InputStream openAttachmentPlaintext(File path)throws IOException {return openAttachmentPlaintext(path,0);}
  // CBC can begin at a 16-byte boundary using the preceding ciphertext block as its IV.
  // The protocol's 100 MiB offsets are aligned, so later segments need no prefix decrypt.
  InputStream openAttachmentPlaintext(File path,long offset)throws IOException {
    if(offset<0||offset%16!=0)throw new IOException("Invalid attachment offset");
    if(path.length()>=ATTACHMENT_MAGIC.length){
      FileInputStream probe=new FileInputStream(path);
      try{
        byte[] head=new byte[ATTACHMENT_MAGIC.length];readFully(probe,head);
        if(Arrays.equals(head,ATTACHMENT_MAGIC)){
          byte[] lenBuf=new byte[4];readFully(probe,lenBuf);int headerLen=ByteBuffer.wrap(lenBuf).getInt();
          if(headerLen<1||headerLen>65536)throw new IOException("Invalid attachment header");byte[] header=new byte[headerLen];readFully(probe,header);
          byte[] raw;try{raw=protector.unprotect(header);}catch(Exception e){throw new IOException(e);}
          if(raw.length!=48)throw new IOException("Invalid attachment key");
          byte[] key=Arrays.copyOfRange(raw,0,32),iv=Arrays.copyOfRange(raw,32,48);
          long encryptedAt=probe.getChannel().position();
          if(offset>0){
            if(offset>path.length()-encryptedAt-16)throw new EOFException();
            probe.getChannel().position(encryptedAt+offset-16);readFully(probe,iv);
            probe.getChannel().position(encryptedAt+offset);
          }
          Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(iv));
          return new CipherInputStream(new BufferedInputStream(probe,256*1024),cipher);
        }
      }catch(Exception e){probe.close();if(e instanceof IOException)throw (IOException)e;throw new IOException(e);}
      probe.close();
    }
    try{ByteArrayInputStream plain=new ByteArrayInputStream(protector.unprotect(SecureIdentity.readFile(path)));if(plain.skip(offset)!=offset)throw new EOFException();return plain;}catch(IOException e){throw e;}catch(Exception e){throw new IOException(e);}
  }
  static void readFully(InputStream in,byte[] buffer)throws IOException{int at=0;while(at<buffer.length){int n=in.read(buffer,at,buffer.length-at);if(n<0)throw new EOFException();at+=n;}}
  static void readTransferBlock(InputStream in,byte[] buffer,int count)throws IOException{int at=0;while(at<count){int n=in.read(buffer,at,count-at);if(n<0)throw new EOFException("Transfer interrupted");if(n>0)at+=n;}}
  // Small attachments and callers that need a byte[] (thumbnails, exports below a threshold,
  // tests). Not used for the actual network send/receive path — that streams, see below.
  public byte[] readAttachment(Message m)throws IOException {
    try(InputStream plain=openContent(m);ByteArrayOutputStream buffer=new ByteArrayOutputStream()){
      byte[] chunk=new byte[CHUNK_SIZE];int n;while((n=plain.read(chunk))!=-1)buffer.write(chunk,0,n);
      byte[] data=buffer.toByteArray();
      if(data.length!=m.fileSize||!SecureIdentity.hash(data).equals(m.fileHash))throw new IOException("Attachment integrity check failed");
      return data;
    }catch(Exception e){if(e instanceof IOException)throw (IOException)e;throw new IOException(e);}
  }
  // Decrypts straight to `destination` (e.g. a SAF export OutputStream, or the network) without
  // ever buffering the whole attachment in memory. Verifies size+hash only once fully streamed,
  // matching readAttachment's guarantee. Caller owns/closes `destination`.
  public void readAttachmentStream(Message m,OutputStream destination,BiConsumer<Long,Long> onProgress)throws IOException {
    MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IOException(e);}
    long total=0;
    try(InputStream plain=openContent(m)){
      byte[] buffer=new byte[CHUNK_SIZE];int n;
      while((n=plain.read(buffer))!=-1){
        digest.update(buffer,0,n);destination.write(buffer,0,n);
        total+=n;if(onProgress!=null)onProgress.accept(total,(long)m.fileSize);
      }
    }
    StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
    if(total!=m.fileSize||!hex.toString().equals(m.fileHash))throw new IOException("Attachment integrity check failed");
  }
  static byte[] readBytes(Socket s,int size)throws IOException {byte[] data=new byte[size];InputStream in=s.getInputStream();int at=0;long end=System.nanoTime()+transferTimeoutNanos(size);while(at<size){if(System.nanoTime()>end)throw new IOException("Attachment timeout");int n=in.read(data,at,Math.min(65536,size-at));if(n<0)throw new EOFException();at+=n;}return data;}
  // Streams exactly `size` bytes from the socket into the attachment store, reporting progress —
  // the network-receive counterpart to storeAttachmentStream's file/byte[] sources. The socket's
  // own SO_TIMEOUT (set at connect time) already bounds each individual read, so a stalled
  // connection is caught without needing a separate per-chunk timeout mechanism here.
  String storeAttachmentFromSocket(Message m,Socket s,long size,String messageId)throws IOException {
    return storeAttachmentStream(m,s.getInputStream(),size,(done,total)->reportProgress(messageId,done,total));
  }
  void sendAttachmentToSocket(Message m,Socket s,String messageId)throws IOException {
    OutputStream guarded=new NetworkTimeoutOutputStream(s);
    readAttachmentStream(m,guarded,(done,total)->reportProgress(messageId,done,total));
    guarded.flush();
  }

  void notifyChanged(){try{changed.run();}catch(Exception ignored){}}
  public synchronized void close(){running=false;for(Socket socket:downloadSockets.values())try{socket.close();}catch(IOException ignored){}try{uploadPolicy.flush();}catch(IOException e){error=e.getMessage();}try{if(listener!=null)listener.close();}catch(IOException ignored){}if(discovery!=null)discovery.close();timer.shutdownNow();connections.shutdownNow();outgoing.shutdownNow();}
}
