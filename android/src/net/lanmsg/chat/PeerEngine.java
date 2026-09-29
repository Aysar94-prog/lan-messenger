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
  ExecutorService connections, outgoing;
  ScheduledExecutorService timer;
  final ConcurrentHashMap<String,Long> sending=new ConcurrentHashMap<>();
  final Set<Socket> activeSockets=ConcurrentHashMap.newKeySet();
  final Set<ServerSocket> temporaryListeners=ConcurrentHashMap.newKeySet();
  volatile long generation;
  final ThreadLocal<Long> workerSession=new ThreadLocal<>();
  volatile boolean closed;
  public volatile String networkState="Offline";
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
    VoiceDrafts.reconcile(this);
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
  // File.renameTo() silently fails (returns false) if the destination already exists — true on
  // Windows in particular, unlike a plain POSIX rename(). Files.move with REPLACE_EXISTING is the
  // correct cross-platform equivalent of "atomically replace whatever's already there".
  static void atomicReplace(File tmp,File dest)throws IOException{java.nio.file.Files.move(tmp.toPath(),dest.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
  public byte[] avatar(){return AvatarSync.avatar(this);}
  public void setAvatar(byte[] data)throws IOException {AvatarSync.setAvatar(this,data);}
  public byte[] peerAvatar(String peerId){return AvatarSync.peerAvatar(this,peerId);}
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
    HashSet<String> loadedForgotten=new HashSet<>();HashMap<String,String> loadedPendingLeaves=new HashMap<>();
    LinkedHashMap<String,HashSet<String>> loadedDeparted=new LinkedHashMap<>();LinkedHashMap<String,HashMap<String,Integer>> loadedAcked=new LinkedHashMap<>();
    HashSet<String> loadedEverTransferred=new HashSet<>();HashSet<String> loadedPendingHandoff=new HashSet<>();
    LinkedHashMap<String,VoiceDraft> loadedVoiceDrafts=new LinkedHashMap<>();
    for(int i=1;i<lines.size()-1;i++){String[] a=lines.get(i).split("\t",-1);
      if(a[0].equals("P")&&(a.length==5||a.length==7||a.length==8||a.length==10)){Peer p=new Peer(a[1],dec(a[2]),a[3],Integer.parseInt(a[4]));if(a.length>=7){p.fingerprint=a[5];p.verified=a[6];}if(a.length>=8)p.publicKey=a[7];if(a.length==10){p.sentAvatarHash=a[8];p.receivedAvatarHash=a[9];}loadedPeers.put(a[1],p);}
      else if(a[0].equals("M")&&(a.length==8||a.length==12||a.length==14))loadedMessages.add(new Message(a[1],a[2],a[3],dec(a[5]),Long.parseLong(a[4]),a[6],a.length>=12?a[8]:"",a.length>=12?dec(a[9]):"",a.length>=12?Long.parseLong(a[10]):0,a.length>=12?a[11]:"",a.length==14?a[12]:"",a.length==14&&a[13].equals("1")));
      else if(a[0].equals("G")&&a.length==7){
        // Pre-M1 shape (this session's now-superseded forget-notice release): G,Id,Owner,Name,Members,Acknowledged,Left.
        // A departed member sat in both Members and Left at once; migrate once to the new disjoint
        // model. If Left was non-empty this is a real membership change other active members don't
        // know about yet (Left was owner-only, never propagated before this feature), so it gets
        // version 1 and a MEMBERSUPDATE broadcast; an empty Left is a pure no-op and stays at 0.
        ArrayList<String> oldLeft=new ArrayList<>();for(String x:a[6].split(",",-1))if(!x.isEmpty())oldLeft.add(x);
        ArrayList<String> newMembers=new ArrayList<>();for(String x:a[4].split(","))if(!oldLeft.contains(x))newMembers.add(x);
        loadedGroups.put(a[1],new Group(a[1],a[2],dec(a[3]),newMembers.toArray(new String[0]),oldLeft.isEmpty()?0:1));
        if(!oldLeft.isEmpty()){HashSet<String> set=loadedDeparted.get(a[1]);if(set==null){set=new HashSet<>();loadedDeparted.put(a[1],set);}set.addAll(oldLeft);}
      }
      else if(a[0].equals("G")&&a.length==6){
        int v=-1;try{v=Integer.parseInt(a[5]);}catch(NumberFormatException ignored){}
        loadedGroups.put(a[1],new Group(a[1],a[2],dec(a[3]),a[4].split(","),Math.max(v,0))); // v<0: ancient pre-Left shape; Acknowledged (a[5]) discarded
      }
      else if(a[0].equals("H")&&a.length==2)loadedHidden.add(a[1]);
      else if(a[0].equals("F")&&a.length==2)loadedForgotten.add(a[1]);
      else if(a[0].equals("L")&&a.length==3)loadedPendingLeaves.put(a[1],a[2]);
      else if(a[0].equals("D")&&a.length==3){HashSet<String> set=loadedDeparted.get(a[1]);if(set==null){set=new HashSet<>();loadedDeparted.put(a[1],set);}set.add(a[2]);}
      else if(a[0].equals("V")&&a.length==4){HashMap<String,Integer> m=loadedAcked.get(a[1]);if(m==null){m=new HashMap<>();loadedAcked.put(a[1],m);}m.put(a[2],Integer.parseInt(a[3]));}
      else if(a[0].equals("T")&&a.length==2)loadedEverTransferred.add(a[1]);
      else if(a[0].equals("O")&&a.length==2)loadedPendingHandoff.add(a[1]);
      else if(a[0].equals("J")||a[0].equals("Q")){} // Removed join-request feature; tolerate old rows already on disk instead of failing to load.
      else if(a[0].equals("R")&&a.length==10){VoiceDraft d=new VoiceDraft(a[1],a[2],a[3].equals("1"),Long.parseLong(a[4]),Long.parseLong(a[5]),a[6],Long.parseLong(a[7]),Long.parseLong(a[8]));d.sendTransactionId=a[9];loadedVoiceDrafts.put(a[1],d);}
      else throw new IOException("Invalid storage row");}
    groups.clear();for(Map.Entry<String,Group> kv:loadedGroups.entrySet()){if(loadedEverTransferred.contains(kv.getKey()))kv.getValue().everTransferredOwnership=true;groups.put(kv.getKey(),kv.getValue());}
    hidden.clear();hidden.addAll(loadedHidden);
    forgotten.clear();forgotten.addAll(loadedForgotten);pendingLeaves.clear();pendingLeaves.putAll(loadedPendingLeaves);
    departedHistory.clear();departedHistory.putAll(loadedDeparted);memberAcked.clear();memberAcked.putAll(loadedAcked);
    pendingOwnershipHandoff.clear();pendingOwnershipHandoff.addAll(loadedPendingHandoff);
    id=h[1];name=dec(h[2]);peers.clear();peers.putAll(loadedPeers);messages.clear();messages.addAll(loadedMessages);
    voiceDrafts.clear();voiceDrafts.putAll(loadedVoiceDrafts);
  }
  synchronized void save()throws IOException {
    StringBuilder text=new StringBuilder("LMSTORE4\t"+id+"\t"+enc(name)+"\n");
    for(Peer p:peers.values())text.append("P\t").append(p.id).append('\t').append(enc(p.name)).append('\t').append(p.host).append('\t').append(p.port).append('\t').append(p.fingerprint).append('\t').append(p.verified).append('\t').append(p.publicKey).append('\t').append(p.sentAvatarHash).append('\t').append(p.receivedAvatarHash).append('\n');
    for(Message m:messages)text.append("M\t").append(m.id).append('\t').append(m.from).append('\t').append(m.to).append('\t').append(m.time).append('\t').append(enc(m.text)).append('\t').append(m.status).append("\t1\t").append(m.groupId).append('\t').append(enc(m.fileName)).append('\t').append(m.fileSize).append('\t').append(m.fileHash).append('\t').append(m.signature).append('\t').append(m.ttlEligible?"1":"0").append('\n');
    for(Group g:groups.values())text.append("G\t").append(g.id).append('\t').append(g.owner).append('\t').append(enc(g.name)).append('\t').append(String.join(",",g.members)).append('\t').append(g.membersVersion).append('\n');
    for(String key:hidden)text.append("H\t").append(key).append('\n');
    for(String pid:forgotten)text.append("F\t").append(pid).append('\n');for(Map.Entry<String,String> kv:pendingLeaves.entrySet())text.append("L\t").append(kv.getKey()).append('\t').append(kv.getValue()).append('\n');
    for(Map.Entry<String,HashSet<String>> kv:departedHistory.entrySet())for(String mid:kv.getValue())text.append("D\t").append(kv.getKey()).append('\t').append(mid).append('\n');
    for(Map.Entry<String,HashMap<String,Integer>> kv:memberAcked.entrySet())for(Map.Entry<String,Integer> mv:kv.getValue().entrySet())text.append("V\t").append(kv.getKey()).append('\t').append(mv.getKey()).append('\t').append(mv.getValue()).append('\n');
    for(Group g:groups.values())if(g.everTransferredOwnership)text.append("T\t").append(g.id).append('\n');
    for(String groupId:pendingOwnershipHandoff)text.append("O\t").append(groupId).append('\n');
    for(VoiceDraft d:voiceDrafts.values())text.append("R\t").append(d.id).append('\t').append(d.conversationId).append('\t').append(d.isGroup?"1":"0").append('\t').append(d.createdAt).append('\t').append(d.updatedAt).append('\t').append(d.state).append('\t').append(d.byteSize).append('\t').append(d.durationMs).append('\t').append(d.sendTransactionId).append('\n');
    text.append("END\n");File tmp=new File(file+".tmp"),bak=new File(file+".bak");
    try(FileOutputStream out=new FileOutputStream(tmp)){out.write(MAGIC);out.write(protector.protect(text.toString().getBytes(StandardCharsets.UTF_8)));out.getFD().sync();}catch(Exception e){throw new IOException("Could not encrypt local data",e);}
    if(file.exists()){if(bak.exists()&&!bak.delete())throw new IOException("Cannot update storage backup.");if(!file.renameTo(bak))throw new IOException("Cannot back up storage.");}
    if(!tmp.renameTo(file)){if(bak.exists())bak.renameTo(file);throw new IOException("Cannot save messages.");}
  }
  public void start()throws IOException {start("0.0.0.0",MESSAGE_PORT,DISCOVERY_PORT);}
  public synchronized void start(String bindAddress,int tcpPort,int udpPort)throws IOException {
    if(closed)throw new IOException("Engine is closed.");
    if(running)return;
    networkState="Starting";
    bind=bindAddress;discoveryPort=udpPort;
    try {
      listener=identity.context.getServerSocketFactory().createServerSocket();((SSLServerSocket)listener).setNeedClientAuth(true);((SSLServerSocket)listener).setEnabledProtocols(new String[]{"TLSv1.2"});((SSLServerSocket)listener).setEnabledCipherSuites(new String[]{"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384","TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"});listener.setReuseAddress(true);listener.bind(new InetSocketAddress(bind,tcpPort));port=listener.getLocalPort();
      discovery=new DatagramSocket(null);discovery.setReuseAddress(true);discovery.setBroadcast(true);discovery.bind(new InetSocketAddress(bind,udpPort));running=true;
    }catch(IOException e){if(listener!=null)listener.close();if(discovery!=null)discovery.close();listener=null;discovery=null;networkState="Offline";throw e;}
    generation++;
    connections=new ThreadPoolExecutor(8,16,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(32),new ThreadPoolExecutor.AbortPolicy());
    outgoing=Executors.newFixedThreadPool(4);timer=Executors.newScheduledThreadPool(2);
    networkState="Online";
    final ServerSocket server=listener;final DatagramSocket udp=discovery;final long session=generation;
    Thread accept=new Thread(()->{workerSession.set(session);while(running&&session==generation)try{Socket s=server.accept();try{track(s);connections.execute(()->{workerSession.set(session);receive(s);});}catch(RejectedExecutionException e){activeSockets.remove(s);s.close();}}catch(IOException e){if(running&&session==generation)error="Incoming connections unavailable.";}},"lan-incoming");accept.setDaemon(true);accept.start();
    Thread discover=new Thread(()->{workerSession.set(session);while(running&&session==generation)try{byte[] b=new byte[1024];DatagramPacket p=new DatagramPacket(b,b.length);udp.receive(p);if(!running||session!=generation)break;String line=new String(p.getData(),0,p.getLength(),StandardCharsets.UTF_8).trim();String[] a=line.split("\t",-1);if(validHello(a)&&!a[2].equals(id)){boolean fresh; synchronized(this){Peer old=peers.get(a[2]);fresh=old==null||!old.online();}remember(a[2],dec(a[3]),p.getAddress().getHostAddress(),Integer.parseInt(a[4]));if(fresh)announceTo(p.getAddress(),p.getPort());}}catch(Exception e){if(running&&session==generation&&!(e instanceof SocketException))error="Discovery packet ignored.";}},"lan-discovery");discover.setDaemon(true);discover.start();
    timer.scheduleWithFixedDelay(()->{workerSession.set(session);if(session==generation)try{purgeExpired();}catch(Exception ignored){}},0,3,TimeUnit.SECONDS);
    timer.scheduleWithFixedDelay(()->{workerSession.set(session);if(session==generation)try{announce();}catch(Exception ignored){}},0,3,TimeUnit.SECONDS);
    timer.scheduleWithFixedDelay(()->{workerSession.set(session);if(session==generation)try{flush();}catch(Exception ignored){}},1,2,TimeUnit.SECONDS);
    notifyChanged();
  }
  synchronized String hello(){return "LM4\tHELLO\t"+id+"\t"+enc(name)+"\t"+port;}
  boolean validHello(String[] a){try{return a.length==5&&a[0].equals("LM4")&&a[1].equals("HELLO")&&uuid(a[2])&&!dec(a[3]).trim().isEmpty()&&dec(a[3]).length()<=30&&Integer.parseInt(a[4])>0&&Integer.parseInt(a[4])<=65535;}catch(Exception e){return false;}}
  void announceTo(InetAddress address,int targetPort)throws IOException {if(!running||workerSession.get()!=null&&workerSession.get()!=generation)throw new IOException("Network is offline.");byte[] b=hello().getBytes(StandardCharsets.UTF_8);discovery.send(new DatagramPacket(b,b.length,address,targetPort));}
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
  Socket connect(String host,int targetPort)throws IOException {SSLSocket s=(SSLSocket)identity.context.getSocketFactory().createSocket();s.setEnabledProtocols(new String[]{"TLSv1.2"});s.setEnabledCipherSuites(new String[]{"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384","TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"});try{track(s);s.bind(new InetSocketAddress(bind,0));s.connect(new InetSocketAddress(host,targetPort),1800);s.setSoTimeout(6000);s.setTcpNoDelay(true);s.startHandshake();if(!running)throw new IOException("Network is offline.");return s;}catch(IOException e){activeSockets.remove(s);s.close();throw e;}}
  synchronized void track(Socket s)throws IOException {if(!running||workerSession.get()!=null&&workerSession.get()!=generation){s.close();throw new IOException("Network is offline.");}activeSockets.removeIf(Socket::isClosed);activeSockets.add(s);}
  synchronized void track(ServerSocket s)throws IOException {if(!running){s.close();throw new IOException("Network is offline.");}temporaryListeners.add(s);}
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
    if(!simulateLegacyBuild&&m.length==2&&m[0].equals("LM4")&&m[1].equals("CAPS")){write(s,"LM4\tCAPS\t2");return;}
    if((m.length==6||m.length==7)&&m[0].equals("LM4")&&m[1].equals("GROUP")){GroupSync.acceptGroup(this,m,h[2],fingerprint);write(s,"LM4\tGROUPACK\t"+m[2]);notifyChanged();return;}
    if(!simulateLegacyBuild&&(m.length==5||m.length==6)&&m[0].equals("LM4")&&m[1].equals("MEMBERSUPDATE")){GroupSync.handleMembersUpdate(this,m,h[2],fingerprint);write(s,"LM4\tMEMBERSUPDATEACK\t"+m[2]+"\t"+m[3]);notifyChanged();return;}
    if(m.length==4&&m[0].equals("LM4")&&m[1].equals("SEEN")&&uuid(m[2])&&m[3].equals(h[2])){markSeen(m[2],h[2]);write(s,"LM4\tSEENACK\t"+m[2]);notifyChanged();return;}
    if(m.length==4&&m[0].equals("LM4")&&m[1].equals("SYNCREQ2")&&uuid(m[2])){GroupSync.handleSync(this,s,m[2],m[3],h[2]);notifyChanged();return;}
    if(m.length==5&&m[0].equals("LM4")&&m[1].equals("AVATAR")&&m[2].equals(h[2])){AvatarSync.handleAvatar(this,s,m[2],m[3],m[4]);notifyChanged();return;}
    if(m.length==3&&m[0].equals("LM4")&&m[1].equals("FORGET")&&m[2].equals(h[2])){revoke(h[2]);try{forgottenCallback.accept(h[2]);}catch(Exception ignored){}write(s,"LM4\tFORGETACK");notifyChanged();return;}
    if(m.length==4&&m[0].equals("LM4")&&m[1].equals("LEAVE")&&uuid(m[2])&&m[3].equals(h[2])){GroupSync.handleLeave(this,m[2],h[2]);write(s,"LM4\tLEAVEACK\t"+m[2]);notifyChanged();return;}
    if(m.length==6&&m[0].equals("LM4")&&m[1].equals("FETCH")){TransferManager.serveDownload(this,s,m,h[2]);return;}
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
    AttachmentStore.validateFile(name,size,hash);synchronized(this){if(!GroupSync.allowedGroup(this,group,h[2]))return;}
    Message msg=new Message(m[2],m[3],id,text,at,"Received",group,name,size,hash,signature,!signature.isEmpty());
    if(!name.isEmpty()&&!m[1].equals("OFFER"))return; // Refuse unsolicited payloads, even from old clients.
    Message incoming=null;
    // A retransmitted duplicate of a message we already have must never delete the attachment we
    // already legitimately stored for it — only a cleared/hidden conversation (never resurrect)
    // or an outright-rejected message should ever remove a file here.
    synchronized(this){
      boolean stillPresent=false;for(Message old:messages)if(old.id.equals(m[2])&&old.from.equals(m[3])){stillPresent=true;break;}
      if(!trusted(h[2],fingerprint)||!GroupSync.allowedGroup(this,group,h[2])){if(!name.isEmpty()&&!stillPresent)try{AttachmentStore.attachmentPath(this,msg).delete();}catch(IOException ignored){}return;}
      boolean cleared=hidden.contains(h[2]+"/"+m[2]);
      if(!cleared&&!stillPresent){messages.add(msg);try{save();}catch(IOException e){messages.remove(msg);if(!name.isEmpty())try{AttachmentStore.attachmentPath(this,msg).delete();}catch(IOException ignored){}throw e;}incoming=msg;}
      else if(!name.isEmpty()&&!stillPresent)try{AttachmentStore.attachmentPath(this,msg).delete();}catch(IOException ignored){}
    }

    if(incoming!=null)try{received.accept(incoming);}catch(Exception ignored){}
    write(s,"LM4\tACK\t"+m[2]+"\t"+id);notifyChanged();
  }catch(Exception ignored){}finally{activeSockets.remove(socket);}}
  synchronized void markSeen(String messageId,String readerId)throws IOException{Message row=null;for(Message x:messages)if(x.id.equals(messageId)&&x.from.equals(id)&&x.to.equals(readerId)&&!x.status.equals("Seen")){row=x;break;}if(row==null)return;String old=row.status;row.status="Seen";try{save();}catch(IOException e){row.status=old;throw e;}}
  static byte[] canonicalBytes(String msgId,String group,String sender,long time,String text,String fileName,long fileSize,String fileHash){
    return (msgId+"\t"+group+"\t"+sender+"\t"+time+"\t"+enc(text)+"\t"+enc(fileName)+"\t"+fileSize+"\t"+fileHash).getBytes(StandardCharsets.UTF_8);
  }
  void purgeExpired()throws IOException{
    ArrayList<Message> expired=new ArrayList<>();
    synchronized(this){for(Message m:messages)if(m.ttlEligible&&!m.groupId.isEmpty()&&System.currentTimeMillis()>m.time+GROUP_TTL_MS)expired.add(m);
      if(expired.isEmpty())return;
      ArrayList<Message> old=new ArrayList<>(messages);messages.removeAll(expired);
      try{save();}catch(IOException e){messages.clear();messages.addAll(old);throw e;}
    }
    for(Message m:expired)if(!m.fileName.isEmpty())TransferManager.deleteTransfer(this,m);
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
  void flush(){if(!running)return;TransferManager.queueAutomaticMedia(this);for(Peer p:peers())startDelivery(p);}
  void startDelivery(Peer p){long session=generation;if(!running||workerSession.get()!=null&&workerSession.get()!=session||sending.putIfAbsent(p.id,session)!=null)return;try{outgoing.execute(()->{workerSession.set(session);long observed=-1;try{do{observed=queueEpoch.get();deliver(p);}while(running&&session==generation&&observed!=queueEpoch.get());}finally{sending.remove(p.id,session);if(running&&session==generation&&observed!=queueEpoch.get())startDelivery(p);}});}catch(RejectedExecutionException e){sending.remove(p.id,session);}}
  void deliver(Peer p){
    try(Socket s=connect(p.host,p.port)){write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id))return;remember(h[2],dec(h[3]),p.host,Integer.parseInt(h[4]));recordCertificate(h[2],SecureIdentity.remote((SSLSocket)s),SecureIdentity.remotePublicKey((SSLSocket)s));}catch(Exception e){return;}
    for(Group g:GroupSync.groups(this)){
      int acked;synchronized(this){HashMap<String,Integer> m=memberAcked.get(g.id);acked=m!=null&&m.containsKey(p.id)?m.get(p.id):-1;}
      // Normally only the current owner broadcasts; while a handoff I started is still pending, I
      // also keep pushing this group's (already-decided) snapshot even though g.owner now correctly
      // says someone else -- see PLAN-GROUP-OWNERSHIP-TRANSFER.md for why the new owner can't take
      // over delivery to a still-lagging member instead.
      if(!((g.owner.equals(id)||pendingOwnershipHandoff(g.id))&&Arrays.asList(g.members).contains(p.id)&&acked<g.membersVersion))continue;
      try(Socket s=connect(p.host,p.port)){
        String fingerprint=SecureIdentity.remote((SSLSocket)s);if(!trusted(p.id,fingerprint))return;write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id)||!read(s).equals("LM4\tREADY"))return;
        int sentVersion=g.membersVersion;boolean neverAcked=acked<0;
        if(neverAcked){
          String wire="LM4\tGROUP\t"+g.id+"\t"+g.owner+"\t"+enc(g.name)+"\t"+String.join(",",g.members)+(sentVersion>0?"\t"+sentVersion:"");
          write(s,wire);if(!read(s).equals("LM4\tGROUPACK\t"+g.id)||!trusted(p.id,fingerprint))return;
        }else{
          String ownerField=g.everTransferredOwnership?"\t"+g.owner:"";
          write(s,"LM4\tMEMBERSUPDATE\t"+g.id+"\t"+sentVersion+"\t"+String.join(",",g.members)+ownerField);
          if(!read(s).equals("LM4\tMEMBERSUPDATEACK\t"+g.id+"\t"+sentVersion)||!trusted(p.id,fingerprint))return;
        }
        synchronized(this){HashMap<String,Integer> m=memberAcked.get(g.id);if(m==null){m=new HashMap<>();memberAcked.put(g.id,m);}Integer old=m.get(p.id);m.put(p.id,sentVersion);try{save();}catch(IOException e){if(old!=null)m.put(p.id,old);else m.remove(p.id);throw e;}}
        GroupSync.checkOwnershipHandoffConvergence(this,g.id);
      }catch(Exception e){return;}
    }
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
    for(Group g:GroupSync.groups(this))if(Arrays.asList(g.members).contains(id)&&Arrays.asList(g.members).contains(p.id))try(Socket s=connect(p.host,p.port)){
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
        AttachmentStore.validateFile(rfileName,fileSize,rfileHash);
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
            if(verified&&GroupSync.allowedGroup(this,g.id,origSender)&&!hidden.contains(origSender+"/"+rid)&&!stillPresent){
              messages.add(msg);try{save();}catch(IOException e){messages.remove(msg);if(!rfileName.isEmpty())try{AttachmentStore.attachmentPath(this,msg).delete();}catch(IOException ignored){}throw e;}incoming=msg;
            }else if(!rfileName.isEmpty()&&!stillPresent)try{AttachmentStore.attachmentPath(this,msg).delete();}catch(IOException ignored){}
          }
        }
        write(s,"LM4\tRELAYACK\t"+rid);
        if(incoming!=null){try{received.accept(incoming);}catch(Exception ignored){}notifyChanged();}
      }
    }catch(Exception e){return;}
    AvatarSync.pushAvatarIfChanged(this,p);
    // A deleted contact's forget notice, and a left group's notice to its owner — both queued and
    // retried here exactly like everything else, until acknowledged.
    if(forgotten.contains(p.id))
    try(Socket s=connect(p.host,p.port)){
      write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id))return;
      if(read(s).equals("LM4\tREADY")){
        write(s,"LM4\tFORGET\t"+id);
        if(read(s).equals("LM4\tFORGETACK")){synchronized(this){forgotten.remove(p.id);try{save();}catch(IOException e){forgotten.add(p.id);throw e;}}}
      }
    }catch(Exception e){return;}
    ArrayList<String> leavingGroupIds=new ArrayList<>();synchronized(this){for(Map.Entry<String,String> kv:pendingLeaves.entrySet())if(kv.getValue().equals(p.id))leavingGroupIds.add(kv.getKey());}
    for(String groupId:leavingGroupIds)
    try(Socket s=connect(p.host,p.port)){
      write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id))return;
      if(read(s).equals("LM4\tREADY")){
        write(s,"LM4\tLEAVE\t"+groupId+"\t"+id);
        if(read(s).equals("LM4\tLEAVEACK\t"+groupId)){synchronized(this){pendingLeaves.remove(groupId);try{save();}catch(IOException e){pendingLeaves.put(groupId,p.id);throw e;}}}
      }
    }catch(Exception e){return;}
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
  public String savedDestination(Message m){return TransferManager.savedDestination(this,m);}
  public String pendingDestination(Message m){return TransferManager.pendingDestination(this,m);}
  public void downloadTo(Message m,String reference)throws IOException {TransferManager.downloadTo(this,m,reference);}
  boolean isFastAttachment(Message m)throws IOException{return TransferManager.isFastAttachment(this,m);}
  public String downloadNote(Message m){return TransferManager.downloadNote(this,m);}
  void downloadNote(Message m,String value){TransferManager.downloadNote(this,m,value);}
  public interface SourceOpener{InputStream open(String reference)throws IOException;}
  public volatile SourceOpener sourceOpener=reference->new FileInputStream(reference);
  File sourcePath(Message m)throws IOException{return TransferManager.sourcePath(this,m);}
  File segmentPath(Message m,int part)throws IOException{return TransferManager.segmentPath(this,m,part);}
  public boolean hasAttachment(Message m){return TransferManager.hasAttachment(this,m);}
  boolean retained(Message m){return TransferManager.retained(this,m);}
  final Semaphore imageSlots=new Semaphore(2);
  final Set<String> imageAttempts=ConcurrentHashMap.newKeySet();
  // Voice Messages (Phase 1 / Android), A06: the same imageSlots capacity pool now also admits
  // automatic Voice Message retrieval, per tests/voice_messages/validation-contract.md's fixed
  // scheduler admission rules -- mirrors windows/Transfers.cs (W06) exactly. voiceAttempts
  // mirrors imageAttempts (in-memory only, reset on restart -- rule 9's "reconstructs eligible
  // automatic work... without duplicating" is satisfied structurally by re-scanning current
  // message/retention state on every call, same as imageAttempts already did before this).
  final Set<String> voiceAttempts=ConcurrentHashMap.newKeySet();
  final Object schedulerGate=new Object();
  int consecutiveAutoVoiceAdmissions;
  public static boolean isImageAttachment(Message m){String name=m.fileName.toLowerCase(Locale.ROOT);int dot=name.lastIndexOf('.');return dot>=0&&Arrays.asList(".jpg",".jpeg",".png",".gif",".bmp",".webp",".tif",".tiff",".heic",".heif",".avif").contains(name.substring(dot));}
  public boolean downloading(Message m){return TransferManager.downloading(this,m);}
  public void cancelDownload(Message m){TransferManager.cancelDownload(this,m);}
  void deleteParts(Message m)throws IOException{TransferManager.deleteParts(this,m);}
  static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte b:bytes)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
  public void queueFastFile(String conversation,String caption,String reference,long size,String name)throws IOException{TransferManager.queueFastFile(this,conversation,caption,reference,size,name);}
  InputStream openContent(Message m)throws IOException{return TransferManager.openContent(this,m);}
  InputStream openParts(Message m,int start)throws IOException{return TransferManager.openParts(this,m,start);}
  public void downloadAttachment(Message m)throws IOException{TransferManager.downloadAttachment(this,m);}

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
  // Members is the live, active roster — it shrinks when someone leaves and grows when the owner
  // adds someone, propagated to every active member via MEMBERSUPDATE. membersVersion is a plain
  // monotonic counter, bumped by exactly 1 on every such change; a receiving device only ever
  // adopts a strictly greater version.
  public static final class Group {
    public final String id,name;public String owner;public String[] members;public int membersVersion;
    // Stays false for a group whose owner has never changed since creation — exactly like
    // membersVersion==0, it keeps the wire shape (5-field MEMBERSUPDATE) unchanged for such a group so
    // an unrelated, not-yet-updated device is genuinely unaffected. Once true, permanently: every
    // future update for this group sends the 6-field (owner-carrying) form.
    public boolean everTransferredOwnership;
    Group(String i,String o,String n,String[] m,int v){this(i,o,n,m,v,false);}
    Group(String i,String o,String n,String[] m,int v,boolean t){id=i;owner=o;name=n;members=m.clone();membersVersion=v;everTransferredOwnership=t;}
  }
  // A member id paired with whether they're currently active (in a group's live members) or only
  // in its departed history — see PeerEngine.allKnownMembers.
  public static final class KnownMember {
    public final String id;public final boolean active;
    KnownMember(String i,boolean a){id=i;active=a;}
  }
  // Voice Messages (Phase 1 / Android), A02: the durable application-private draft registry.
  // Recording/playback device access (A03) and UI (A04) are separate, later tasks; this class
  // only tracks draft metadata -- see VoiceDrafts.java for the encrypted PCM/WAV read/write,
  // matching AttachmentStore.java's existing encryption construction (per-file random AES-256-
  // CBC key/IV wrapped by the small, one-shot protector).
  public static final class VoiceDraft {
    public static final String RECORDING="Recording",FINALIZED="Finalized",INVALID="Invalid";
    public final String id,conversationId;public final boolean isGroup;public final long createdAt;
    public long updatedAt;public String state;public long byteSize,durationMs;public String sendTransactionId="";
    VoiceDraft(String id,String conversationId,boolean isGroup,long createdAt,long updatedAt,String state,long byteSize,long durationMs){
      this.id=id;this.conversationId=conversationId;this.isGroup=isGroup;this.createdAt=createdAt;this.updatedAt=updatedAt;this.state=state;this.byteSize=byteSize;this.durationMs=durationMs;
    }
    VoiceDraft copy(){VoiceDraft d=new VoiceDraft(id,conversationId,isGroup,createdAt,updatedAt,state,byteSize,durationMs);d.sendTransactionId=sendTransactionId;return d;}
    // Age alone never silently deletes a valid finalized draft (Registry rule 5) -- this is
    // purely an advisory read, never a trigger for automatic removal.
    static final long STALE_REVIEW_AGE_MS=30L*24*60*60*1000;
    public boolean isStale(long nowMs){return state.equals(FINALIZED)&&nowMs-updatedAt>=STALE_REVIEW_AGE_MS;}
  }
  public static final int VOICE_DRAFT_CAP=10;
  static final byte[] VOICE_DRAFT_MAGIC="LMVOICE1".getBytes(StandardCharsets.US_ASCII);
  final LinkedHashMap<String,VoiceDraft> voiceDrafts=new LinkedHashMap<>();
  final HashSet<String> openVoiceDraftWriters=new HashSet<>();

  final LinkedHashMap<String,Group> groups=new LinkedHashMap<>();
  final HashSet<String> hidden=new HashSet<>();
  // Peer ids we've deleted and still owe a FORGET notice; groups we've left, keyed by the owner
  // id we still owe a LEAVE notice to (captured before the local group record is dropped).
  final HashSet<String> forgotten=new HashSet<>();
  final HashMap<String,String> pendingLeaves=new HashMap<>();
  // Owner-only bookkeeping, never sent over the wire: ids who used to be a member of a group,
  // kept purely so the Members dialog can show them and offer Re-invite. Disjoint from the live
  // members above (a departed id is removed from members, not merely flagged here).
  final LinkedHashMap<String,HashSet<String>> departedHistory=new LinkedHashMap<>();
  // Owner-only: per group, the last membersVersion each active member has acked. Drives deliver's
  // broadcast/retry loop.
  final LinkedHashMap<String,HashMap<String,Integer>> memberAcked=new LinkedHashMap<>();
  // Groups where I've handed off ownership and am waiting for every other active member to catch up
  // before my own departure completes — I stay the delivery/leave-acceptance authority for exactly
  // this one group until then, even though Group.owner already (correctly) says someone else. See
  // PLAN-GROUP-OWNERSHIP-TRANSFER.md for why this can't be handed off to the new owner instead.
  final HashSet<String> pendingOwnershipHandoff=new HashSet<>();
  // True while my own departure from this group is deferred, waiting for every other active member
  // to catch up to a completed ownership handoff — the UI should show this instead of a normal chat.
  public synchronized boolean pendingOwnershipHandoff(String groupId){return pendingOwnershipHandoff.contains(groupId);}
  // Test-only: makes this engine behave as if it predates group-membership changes entirely —
  // refuses CAPS and MEMBERSUPDATE while HELLO and ordinary messaging behave normally.
  public volatile boolean simulateLegacyBuild=false;
  // Fired when a contact remotely revokes our verification of them, because we previously
  // deleted them and they've told us so (the FORGET notice). Carries their peer id.
  public volatile java.util.function.Consumer<String> forgottenCallback=id->{};
  public List<Group> groups(){return GroupSync.groups(this);}
  public String displayName(String target){return GroupSync.displayName(this,target);}
  public String createGroup(String name,List<String> members)throws IOException {return GroupSync.createGroup(this,name,members);}
  public List<KnownMember> allKnownMembers(String groupId){return GroupSync.allKnownMembers(this,groupId);}
  public void addMember(String groupId,String memberId)throws IOException {GroupSync.addMember(this,groupId,memberId);}
  public void transferOwnership(String groupId,String newOwnerId)throws IOException {GroupSync.transferOwnership(this,groupId,newOwnerId);}
  // Voice Messages (Phase 1 / Android), A02: thin delegating wrappers, matching the existing
  // GroupSync/AttachmentStore/TransferManager pattern -- see VoiceDrafts.java for the real logic.
  public List<VoiceDraft> voiceDraftsFor(String conversation){return VoiceDrafts.voiceDraftsFor(this,conversation);}
  public VoiceDraft getVoiceDraft(String draftId){return VoiceDrafts.getVoiceDraft(this,draftId);}
  public boolean voiceDraftSendable(String draftId){return VoiceDrafts.voiceDraftSendable(this,draftId);}
  public String createVoiceDraft(String conversation,boolean isGroup)throws IOException {return VoiceDrafts.createVoiceDraft(this,conversation,isGroup);}
  public VoiceDraftWriter openVoiceDraftWriter(String draftId)throws IOException {return VoiceDrafts.openVoiceDraftWriter(this,draftId);}
  public VoiceWavValidation finalizeVoiceDraft(String draftId)throws IOException {return VoiceDrafts.finalizeVoiceDraft(this,draftId);}
  public void invalidateVoiceDraft(String draftId){VoiceDrafts.invalidateVoiceDraft(this,draftId);}
  public void deleteVoiceDraft(String draftId)throws IOException {VoiceDrafts.deleteVoiceDraft(this,draftId);}
  public void sendVoiceDraft(String draftId,String caption)throws IOException {VoiceDrafts.sendVoiceDraft(this,draftId,caption);}
  public byte[] readVoiceDraftWav(String draftId)throws IOException {return VoiceDrafts.readVoiceDraftWav(this,draftId);}
  // Owner-only view: the last MembersVersion a specific active member has acked, for the Members
  // screen's sync-status display -- -1 if never (they're not yet caught up to anything).
  public synchronized int memberAckedVersion(String groupId,String peerId){HashMap<String,Integer> m=memberAcked.get(groupId);return m!=null&&m.containsKey(peerId)?m.get(peerId):-1;}
  boolean allowedGroup(String group,String sender){return GroupSync.allowedGroup(this,group,sender);}
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
    queueContentStream(conversation,text,fileName,data,declaredSize,onProgress,dispatch,null);
  }
  // explicitId lets a caller (Voice Messages' SendVoiceDraft, A05) allocate the message id itself
  // before importing content, so a marker filename embedding that same id can be computed first.
  // null preserves every existing caller's random-UUID behavior unchanged.
  void queueContentStream(String conversation,String text,String fileName,InputStream data,long declaredSize,BiConsumer<Long,Long> onProgress,boolean dispatch,String explicitId)throws IOException {
    text=text.trim();if(text.length()>2000||(data==null&&text.isEmpty()))throw new IOException("Messages must contain 1–2000 characters.");
    if(data!=null&&declaredSize>MAX_FILE_SIZE)throw new IOException("Files must be "+(MAX_FILE_SIZE/1024/1024)+" MB or smaller.");
    ArrayList<String> recipients=new ArrayList<>();String groupId="";
    synchronized(this){
      Group group=groups.get(conversation);
      if(group!=null){groupId=group.id;for(String member:group.members)if(!member.equals(id))recipients.add(member);}
      else if(peers.containsKey(conversation))recipients.add(conversation);
      else throw new IOException("Choose a conversation first.");
    }
    String messageId=explicitId!=null?explicitId:UUID.randomUUID().toString();long at=System.currentTimeMillis();String hash="";
    if(data!=null){
      Message placeholder=new Message(messageId,id,recipients.get(0),text,at,"Queued",groupId,fileName,(int)declaredSize,"");
      hash=AttachmentStore.storeAttachmentStream(this,placeholder,data,declaredSize,onProgress);
    }
    String signature="";if(!groupId.isEmpty())try{signature=Base64.getEncoder().encodeToString(identity.sign(canonicalBytes(messageId,groupId,id,at,text,fileName,(int)declaredSize,hash)));}catch(Exception e){throw new IOException(e);}
    ArrayList<Message> batch=new ArrayList<>();for(String to:recipients)batch.add(new Message(messageId,id,to,text,at,"Queued",groupId,fileName,(int)declaredSize,hash,signature,!groupId.isEmpty()));
    synchronized(this){
      messages.addAll(batch);try{save();}catch(IOException e){messages.removeAll(batch);if(fileName!=null&&!fileName.isEmpty())try{AttachmentStore.attachmentPath(this,batch.get(0)).delete();}catch(IOException ignored){}throw e;}
    }
    notifyChanged();queueEpoch.incrementAndGet();if(dispatch)flush();
  }
  public synchronized void clearConversation(String conversation)throws IOException {
    ArrayList<Message> removed=new ArrayList<>(),old=new ArrayList<>(messages);HashSet<String> oldHidden=new HashSet<>(hidden);
    for(Message m:messages)if(!m.groupId.isEmpty()?m.groupId.equals(conversation):m.from.equals(conversation)||m.to.equals(conversation)){removed.add(m);hidden.add(m.from+"/"+m.id);}
    messages.removeAll(removed);try{save();}catch(IOException e){messages.clear();messages.addAll(old);hidden.clear();hidden.addAll(oldHidden);throw e;}save();
    for(Message m:removed)if(!m.fileName.isEmpty())TransferManager.deleteTransfer(this,m);notifyChanged();
  }
  // Like clearConversation, but also forgets the contact or leaves the group so it drops off the
  // list entirely. A forgotten peer that reappears on the LAN shows up as a brand-new, unverified
  // device — chatting again requires comparing safety codes from scratch.
  public synchronized void deleteConversation(String conversation)throws IOException {
    ArrayList<Message> removed=new ArrayList<>(),old=new ArrayList<>(messages);HashSet<String> oldHidden=new HashSet<>(hidden);
    Peer oldPeer=peers.get(conversation);Group oldGroup=groups.get(conversation);
    boolean wasForgotten=forgotten.contains(conversation);boolean hadPendingLeave=pendingLeaves.containsKey(conversation);
    for(Message m:messages)if(!m.groupId.isEmpty()?m.groupId.equals(conversation):m.from.equals(conversation)||m.to.equals(conversation)){removed.add(m);hidden.add(m.from+"/"+m.id);}
    messages.removeAll(removed);
    if(oldPeer!=null){peers.remove(conversation);forgotten.add(conversation);}
    if(oldGroup!=null){groups.remove(conversation);pendingLeaves.put(conversation,oldGroup.owner);}
    try{save();}catch(IOException e){
      messages.clear();messages.addAll(old);hidden.clear();hidden.addAll(oldHidden);
      if(oldPeer!=null){peers.put(conversation,oldPeer);if(!wasForgotten)forgotten.remove(conversation);}
      if(oldGroup!=null){groups.put(conversation,oldGroup);if(!hadPendingLeave)pendingLeaves.remove(conversation);}
      throw e;
    }
    save();
    for(Message m:removed)if(!m.fileName.isEmpty())TransferManager.deleteTransfer(this,m);
    if(oldPeer!=null)try{AvatarSync.setPeerAvatar(this,conversation,null);}catch(IOException ignored){}
    notifyChanged();queueEpoch.incrementAndGet();flush();
  }
  // Wipes every conversation, contact and group — everything except this device's own identity,
  // display name and profile picture. Every forgotten contact still gets a queued forget notice.
  public synchronized void deleteAllData()throws IOException {
    ArrayList<Message> old=new ArrayList<>(messages);HashSet<String> oldHidden=new HashSet<>(hidden);
    LinkedHashMap<String,Peer> oldPeers=new LinkedHashMap<>(peers);LinkedHashMap<String,Group> oldGroups=new LinkedHashMap<>(groups);
    HashSet<String> oldForgotten=new HashSet<>(forgotten);HashMap<String,String> oldPendingLeaves=new HashMap<>(pendingLeaves);
    LinkedHashMap<String,HashSet<String>> oldDeparted=new LinkedHashMap<>();for(Map.Entry<String,HashSet<String>> kv:departedHistory.entrySet())oldDeparted.put(kv.getKey(),new HashSet<>(kv.getValue()));
    LinkedHashMap<String,HashMap<String,Integer>> oldAcked=new LinkedHashMap<>();for(Map.Entry<String,HashMap<String,Integer>> kv:memberAcked.entrySet())oldAcked.put(kv.getKey(),new HashMap<>(kv.getValue()));
    HashSet<String> oldPendingHandoff=new HashSet<>(pendingOwnershipHandoff);
    ArrayList<Message> withFiles=new ArrayList<>();for(Message m:messages)if(!m.fileName.isEmpty())withFiles.add(m);
    messages.clear();groups.clear();hidden.clear();departedHistory.clear();memberAcked.clear();pendingOwnershipHandoff.clear();
    forgotten.addAll(oldPeers.keySet());
    peers.clear();
    try{save();}catch(IOException e){
      messages.addAll(old);hidden.addAll(oldHidden);peers.putAll(oldPeers);groups.putAll(oldGroups);
      departedHistory.putAll(oldDeparted);memberAcked.putAll(oldAcked);pendingOwnershipHandoff.addAll(oldPendingHandoff);
      forgotten.clear();forgotten.addAll(oldForgotten);pendingLeaves.clear();pendingLeaves.putAll(oldPendingLeaves);
      throw e;
    }
    save();
    for(Message m:withFiles)TransferManager.deleteTransfer(this,m);
    deleteRecursively(new File(file.getParentFile(),"attachments"));
    deleteRecursively(new File(file.getParentFile(),"avatars"));
    try{uploadPolicy.reset();}catch(IOException ignored){}
    notifyChanged();queueEpoch.incrementAndGet();flush();
  }
  static void deleteRecursively(File dir){File[] children=dir.listFiles();if(children!=null)for(File child:children){if(child.isDirectory())deleteRecursively(child);else child.delete();}dir.delete();}
  // Owner-only: brings a departed member back. Same underlying action as addMember — a fresh
  // capability check, live, every time — just triggered from the Members dialog instead of an
  // incoming join request.
  public void reinviteMember(String groupId,String memberId)throws IOException {
    synchronized(this){Group g=groups.get(groupId);if(g==null||!g.owner.equals(id))throw new IOException("Only the group owner can re-invite a member.");}
    addMember(groupId,memberId);
  }
  public static String safeFileName(String name){String[] parts=name.replace('\\','/').split("/",-1);name=parts[parts.length-1];StringBuilder b=new StringBuilder();for(char c:name.toCharArray())if(c>=32&&"<>:\"/\\|?*".indexOf(c)<0)b.append(c);name=b.toString().trim().replaceAll("^\\.+|\\.+$","");return name.isEmpty()?"attachment":name.substring(0,Math.min(120,name.length()));}
  File attachmentPath(Message m)throws IOException {return AttachmentStore.attachmentPath(this,m);}
  static void readTransferBlock(InputStream in,byte[] buffer,int count)throws IOException{int at=0;while(at<count){int n=in.read(buffer,at,count-at);if(n<0)throw new EOFException("Transfer interrupted");if(n>0)at+=n;}}
  InputStream openAttachmentPlaintext(File path)throws IOException {return AttachmentStore.openAttachmentPlaintext(this,path);}
  InputStream openAttachmentPlaintext(File path,long offset)throws IOException {return AttachmentStore.openAttachmentPlaintext(this,path,offset);}
  // Small attachments and callers that need a byte[] (thumbnails, exports below a threshold,
  // tests). Not used for the actual network send/receive path — that streams, see below.
  public byte[] readAttachment(Message m)throws IOException {return AttachmentStore.readAttachment(this,m);}
  // Decrypts straight to `destination` (e.g. a SAF export OutputStream, or the network) without
  // ever buffering the whole attachment in memory. Verifies size+hash only once fully streamed,
  // matching readAttachment's guarantee. Caller owns/closes `destination`.
  public void readAttachmentStream(Message m,OutputStream destination,BiConsumer<Long,Long> onProgress)throws IOException {AttachmentStore.readAttachmentStream(this,m,destination,onProgress);}
  String storeAttachmentStreamAt(Message m,InputStream source,long totalBytes,BiConsumer<Long,Long> onProgress,File destination)throws IOException {return AttachmentStore.storeAttachmentStreamAt(this,m,source,totalBytes,onProgress,destination);}

  void notifyChanged(){try{changed.run();}catch(Exception ignored){}}
  public synchronized void goOffline(){
    if(!running)return;
    networkState="Stopping";running=false;generation++;
    try{if(listener!=null)listener.close();}catch(IOException ignored){}
    if(discovery!=null)discovery.close();listener=null;discovery=null;
    for(ServerSocket server:temporaryListeners)try{server.close();}catch(IOException ignored){}
    temporaryListeners.clear();
    for(Socket socket:activeSockets)try{socket.close();}catch(IOException ignored){}
    activeSockets.clear();
    for(Socket socket:downloadSockets.values())try{socket.close();}catch(IOException ignored){}
    if(timer!=null)timer.shutdownNow();
    if(connections!=null)connections.shutdownNow();
    if(outgoing!=null)outgoing.shutdownNow();
    sending.clear();
    networkState="Offline";notifyChanged();
  }
  public synchronized void close(){goOffline();closed=true;try{uploadPolicy.flush();}catch(IOException e){error=e.getMessage();}}
}
