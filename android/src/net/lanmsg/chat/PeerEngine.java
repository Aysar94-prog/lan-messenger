package net.lanmsg.chat;

import java.io.*;
import javax.net.ssl.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Shared wire protocol: protocol.md. No Android dependencies, so desktop tests exercise this exact engine. */
public final class PeerEngine implements Closeable {
  public static final int DISCOVERY_PORT=43871, MESSAGE_PORT=43872;
  public static final class Peer {
    public String id,name,host; public int port; public long seen; public String fingerprint="",verified="",publicKey="";
    Peer(String i,String n,String h,int p){id=i;name=n;host=h;port=p;}
    public boolean online(){return System.currentTimeMillis()-seen<12000;}
    public boolean trusted(){return !fingerprint.isEmpty()&&fingerprint.equals(verified);}
    public boolean keyChanged(){return !verified.isEmpty()&&!verified.equals(fingerprint);}
    public String security(){return keyChanged()?"KEY CHANGED":trusted()?"Verified":"Verify device";}
  }
  static final long GROUP_TTL_MS=168L*3600*1000;
  public static final class Message {
    public String id,from,to,text,status,groupId="",fileName="",fileHash="",signature=""; public long time; public int fileSize; public boolean ttlEligible;
    Message(String i,String f,String t,String x,long at,String s){id=i;from=f;to=t;text=x;time=at;status=s;}
    Message(String i,String f,String t,String x,long at,String s,String g,String n,int size,String hash){this(i,f,t,x,at,s);groupId=g;fileName=n;fileSize=size;fileHash=hash;}
    Message(String i,String f,String t,String x,long at,String s,String g,String n,int size,String hash,String sig,boolean eligible){this(i,f,t,x,at,s,g,n,size,hash);signature=sig;ttlEligible=eligible;}
    Message copy(){Message m=new Message(id,from,to,text,time,status,groupId,fileName,fileSize,fileHash,signature,ttlEligible);return m;}
  }
  final File file;
  final SecureIdentity identity;
  final SecureIdentity.Protector protector;
  static final byte[] MAGIC="LMSEC3\n".getBytes(StandardCharsets.US_ASCII);
  final LinkedHashMap<String,Peer> peers=new LinkedHashMap<>();
  final ArrayList<Message> messages=new ArrayList<>();
  final ExecutorService connections=new ThreadPoolExecutor(2,6,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(32),new ThreadPoolExecutor.AbortPolicy());
  final ScheduledExecutorService timer=Executors.newScheduledThreadPool(2);
  final Set<String> sending=ConcurrentHashMap.newKeySet();
  ServerSocket listener; DatagramSocket discovery;
  public String id,name; public volatile String error=""; public volatile boolean running;
  public volatile Runnable changed=()->{};
  public volatile java.util.function.Consumer<Message> received=m->{};
  int port=MESSAGE_PORT, discoveryPort=DISCOVERY_PORT; String bind="0.0.0.0";
  public PeerEngine(File directory,String defaultName,SecureIdentity.Protector protector)throws IOException {
    this.protector=protector;
    if(!directory.exists()&&!directory.mkdirs())throw new IOException("Cannot create message storage.");
    file=new File(directory,"state.txt");
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
  public synchronized List<Peer> peers(){ArrayList<Peer> result=new ArrayList<>();for(Peer p:peers.values()){Peer copy=new Peer(p.id,p.name,p.host,p.port);copy.seen=p.seen;copy.fingerprint=p.fingerprint;copy.verified=p.verified;copy.publicKey=p.publicKey;result.add(copy);}return result;}
  public synchronized List<Message> messages(String peer){LinkedHashMap<String,Message> result=new LinkedHashMap<>();HashMap<String,int[]> counts=new HashMap<>();for(Message m:messages)if(!m.groupId.isEmpty()?m.groupId.equals(peer):m.from.equals(peer)||m.to.equals(peer)){String key=m.from+"/"+m.id;if(!result.containsKey(key))result.put(key,m.copy());int[] c=counts.get(key);if(c==null){c=new int[3];counts.put(key,c);}c[0]++;if(m.status.equals("Seen"))c[2]++;if(m.status.equals("Seen")||m.status.equals("Delivered"))c[1]++;}for(Map.Entry<String,Message> entry:result.entrySet()){Message m=entry.getValue();int[] c=counts.get(entry.getKey());if(m.from.equals(id)&&!m.groupId.isEmpty())m.status=c[2]==c[0]?"Seen":c[1]==c[0]?"Delivered":"Queued ("+c[1]+"/"+c[0]+" delivered)";}return new ArrayList<>(result.values());}

  public synchronized int pending(){int n=0;for(Message m:messages)if(m.status.equals("Queued"))n++;return n;}
  public synchronized void rename(String value)throws IOException {String old=name;name=cleanName(value);try{save();}catch(IOException e){name=old;throw e;}notifyChanged();}
  File avatarPath(){return new File(file.getParentFile(),"avatar.sec");}
  public synchronized byte[] avatar(){File path=avatarPath();if(!path.exists())return null;try{return protector.unprotect(SecureIdentity.readFile(path));}catch(Exception e){return null;}}
  public synchronized void setAvatar(byte[] data)throws IOException {File path=avatarPath();if(data==null){if(path.exists()&&!path.delete())throw new IOException("Cannot remove profile picture");notifyChanged();return;}
    File tmp=new File(path+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(protector.protect(data));out.getFD().sync();}catch(Exception e){throw new IOException(e);}if(!tmp.renameTo(path))throw new IOException("Cannot save profile picture");notifyChanged();}
  public synchronized void queue(String peer,String text)throws IOException {
    queueContent(peer,text,"",null);
  }

  synchronized void load(File source)throws IOException {
    byte[] stored=SecureIdentity.readFile(source);if(stored.length>=MAGIC.length&&Arrays.equals(Arrays.copyOf(stored,MAGIC.length),MAGIC))try{stored=protector.unprotect(Arrays.copyOfRange(stored,MAGIC.length,stored.length));}catch(Exception e){throw new IOException("Could not decrypt saved data",e);}
    ArrayList<String> lines=new ArrayList<>();try(BufferedReader r=new BufferedReader(new InputStreamReader(new ByteArrayInputStream(stored),StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)lines.add(line);}
    if(lines.size()<2||!lines.get(lines.size()-1).equals("END"))throw new IOException("Incomplete storage");
    String[] h=lines.get(0).split("\t",-1);if(h.length!=3||(!h[0].equals("LMSTORE2")&&!h[0].equals("LMSTORE3")&&!h[0].equals("LMSTORE4"))||!uuid(h[1]))throw new IOException("Invalid storage");
    LinkedHashMap<String,Peer> loadedPeers=new LinkedHashMap<>();ArrayList<Message> loadedMessages=new ArrayList<>();LinkedHashMap<String,Group> loadedGroups=new LinkedHashMap<>();HashSet<String> loadedHidden=new HashSet<>();
    for(int i=1;i<lines.size()-1;i++){String[] a=lines.get(i).split("\t",-1);
      if(a[0].equals("P")&&(a.length==5||a.length==7||a.length==8)){Peer p=new Peer(a[1],dec(a[2]),a[3],Integer.parseInt(a[4]));if(a.length>=7){p.fingerprint=a[5];p.verified=a[6];}if(a.length==8)p.publicKey=a[7];loadedPeers.put(a[1],p);}
      else if(a[0].equals("M")&&(a.length==8||a.length==12||a.length==14))loadedMessages.add(new Message(a[1],a[2],a[3],dec(a[5]),Long.parseLong(a[4]),a[6],a.length>=12?a[8]:"",a.length>=12?dec(a[9]):"",a.length>=12?Integer.parseInt(a[10]):0,a.length>=12?a[11]:"",a.length==14?a[12]:"",a.length==14&&a[13].equals("1")));
      else if(a[0].equals("G")&&a.length==6)loadedGroups.put(a[1],new Group(a[1],a[2],dec(a[3]),a[4].split(","),a[5]));
      else if(a[0].equals("H")&&a.length==2)loadedHidden.add(a[1]);else throw new IOException("Invalid storage row");}
    groups.clear();groups.putAll(loadedGroups);hidden.clear();hidden.addAll(loadedHidden);
    id=h[1];name=dec(h[2]);peers.clear();peers.putAll(loadedPeers);messages.clear();messages.addAll(loadedMessages);
  }
  synchronized void save()throws IOException {
    StringBuilder text=new StringBuilder("LMSTORE4\t"+id+"\t"+enc(name)+"\n");
    for(Peer p:peers.values())text.append("P\t").append(p.id).append('\t').append(enc(p.name)).append('\t').append(p.host).append('\t').append(p.port).append('\t').append(p.fingerprint).append('\t').append(p.verified).append('\t').append(p.publicKey).append('\n');
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
    Peer p=new Peer(peerId,cleanName(peerName),host,peerPort);if(old!=null){p.fingerprint=old.fingerprint;p.verified=old.verified;p.publicKey=old.publicKey;}p.seen=System.currentTimeMillis();peers.put(peerId,p);
    if(modified)try{save();}catch(IOException e){if(old==null)peers.remove(peerId);else peers.put(peerId,old);throw e;}notifyChanged();
  }
  public void addAddress(String address)throws IOException {
    String[] a=address.trim().split(":",-1);if(a.length>2||!a[0].matches("[0-9.]+"))throw new IOException("Enter a local IPv4 address.");
    InetAddress target=InetAddress.getByName(a[0]);if(!(target.isSiteLocalAddress()||target.isLinkLocalAddress()||target.isLoopbackAddress()))throw new IOException("Use a local network address.");
    int targetPort=a.length==2?Integer.parseInt(a[1]):MESSAGE_PORT;
    try(Socket s=connect(a[0],targetPort)){write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||h[2].equals(id))throw new IOException("No other LAN Messenger device at this address.");remember(h[2],dec(h[3]),a[0],Integer.parseInt(h[4]));try{recordCertificate(h[2],SecureIdentity.remote((SSLSocket)s),SecureIdentity.remotePublicKey((SSLSocket)s));}catch(Exception e){throw new IOException(e);}}
  }
  Socket connect(String host,int targetPort)throws IOException {SSLSocket s=(SSLSocket)identity.context.getSocketFactory().createSocket();s.setEnabledProtocols(new String[]{"TLSv1.2"});s.setEnabledCipherSuites(new String[]{"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384","TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"});try{s.bind(new InetSocketAddress(bind,0));s.connect(new InetSocketAddress(host,targetPort),1800);s.setSoTimeout(6000);s.startHandshake();return s;}catch(IOException e){s.close();throw e;}}
  static String read(Socket s)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();int c;InputStream in=s.getInputStream();while((c=in.read())!=-1){if(c==10)return new String(b.toByteArray(),StandardCharsets.UTF_8);if(b.size()>=16384)throw new IOException("Frame too large");b.write(c);}throw new EOFException();}
  static void write(Socket s,String line)throws IOException {s.getOutputStream().write((line+"\n").getBytes(StandardCharsets.UTF_8));s.getOutputStream().flush();}
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
  void receive(Socket socket){try(SSLSocket s=(SSLSocket)socket){s.setSoTimeout(6000);s.startHandshake();String fingerprint=SecureIdentity.remote(s);String[] h=read(s).split("\t",-1);if(!validHello(h)||h[2].equals(id))return;remember(h[2],dec(h[3]),s.getInetAddress().getHostAddress(),Integer.parseInt(h[4]));recordCertificate(h[2],fingerprint,SecureIdentity.remotePublicKey(s));write(s,hello());
    if(!trusted(h[2],fingerprint)){write(s,"LM4\tPAIR");return;}write(s,"LM4\tREADY");
    String[] m=read(s).split("\t",-1);
    if(m.length==6&&m[0].equals("LM4")&&m[1].equals("GROUP")){acceptGroup(m,h[2],fingerprint);write(s,"LM4\tGROUPACK\t"+m[2]);notifyChanged();return;}
    if(m.length==4&&m[0].equals("LM4")&&m[1].equals("SEEN")&&uuid(m[2])&&m[3].equals(h[2])){markSeen(m[2],h[2]);write(s,"LM4\tSEENACK\t"+m[2]);notifyChanged();return;}
    if(m.length==4&&m[0].equals("LM4")&&m[1].equals("SYNCREQ")&&uuid(m[2])){handleSync(s,m[2],m[3],h[2]);notifyChanged();return;}
    if((m.length!=8&&m.length!=12&&m.length!=13)||!m[0].equals("LM4")||!m[1].equals("MSG")||!uuid(m[2])||!m[3].equals(h[2])||!m[4].equals(id))return;
    long at=Long.parseLong(m[6]);if(at<0||at>253402300799999L)return;
    String text=dec(m[7]),group=m.length>=12?m[8]:"",name=m.length>=12?dec(m[9]):"",hash=m.length>=12?m[11]:"";int size=m.length>=12?Integer.parseInt(m[10]):0;String signature=m.length==13?m[12]:"";
    if(m.length==13&&group.isEmpty())return;
    if(text.length()>2000||(name.isEmpty()&&text.trim().isEmpty()))return;
    if(!group.isEmpty()&&System.currentTimeMillis()>at+GROUP_TTL_MS)return;
    if(!signature.isEmpty()){
      String peerKey;synchronized(this){Peer sp=peers.get(h[2]);peerKey=sp!=null?sp.publicKey:"";}
      if(peerKey.isEmpty()||!SecureIdentity.verify(Base64.getDecoder().decode(peerKey),canonicalBytes(m[2],group,m[3],at,text,name,size,hash),Base64.getDecoder().decode(signature)))return;
    }
    validateFile(name,size,hash);synchronized(this){if(!allowedGroup(group,h[2]))return;}
    byte[] data=readBytes(s,size);if(!name.isEmpty()&&!SecureIdentity.hash(data).equals(hash))return;
    Message incoming=null;
    synchronized(this){if(!trusted(h[2],fingerprint)||!allowedGroup(group,h[2]))return;boolean found=hidden.contains(h[2]+"/"+m[2]);for(Message old:messages)if(old.id.equals(m[2])&&old.from.equals(m[3])){found=true;break;}if(!found){Message msg=new Message(m[2],m[3],id,text,at,"Received",group,name,size,hash,signature,!signature.isEmpty());if(!name.isEmpty())storeAttachment(msg,data);messages.add(msg);try{save();}catch(IOException e){messages.remove(msg);if(!name.isEmpty())attachmentPath(msg).delete();throw e;}incoming=msg;}}

    if(incoming!=null)try{received.accept(incoming);}catch(Exception ignored){}
    write(s,"LM4\tACK\t"+m[2]+"\t"+id);notifyChanged();
  }catch(Exception ignored){}}
  synchronized void markSeen(String messageId,String readerId)throws IOException{Message row=null;for(Message x:messages)if(x.id.equals(messageId)&&x.from.equals(id)&&x.to.equals(readerId)&&!x.status.equals("Seen")){row=x;break;}if(row==null)return;String old=row.status;row.status="Seen";try{save();}catch(IOException e){row.status=old;throw e;}}
  static byte[] canonicalBytes(String msgId,String group,String sender,long time,String text,String fileName,int fileSize,String fileHash){
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
        write(s,"LM4\tRELAY\t"+m.id+"\t"+group+"\t"+m.from+"\t"+enc(displayName(m.from))+"\t"+m.time+"\t"+enc(m.text)+"\t"+enc(m.fileName)+"\t"+m.fileSize+"\t"+m.fileHash+"\t"+m.signature);
        byte[] data=m.fileName.isEmpty()?new byte[0]:readAttachment(m);
        if(data.length>0){s.getOutputStream().write(data);s.getOutputStream().flush();}
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
    for(Message m:expired)if(!m.fileName.isEmpty())try{attachmentPath(m).delete();}catch(IOException ignored){}
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
  void flush(){if(!running)return;for(Peer p:peers())if(sending.add(p.id))try{connections.execute(()->{try{deliver(p);}finally{sending.remove(p.id);}});}catch(RejectedExecutionException e){sending.remove(p.id);}}
  void deliver(Peer p){
    try(Socket s=connect(p.host,p.port)){write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id))return;remember(h[2],dec(h[3]),p.host,Integer.parseInt(h[4]));recordCertificate(h[2],SecureIdentity.remote((SSLSocket)s),SecureIdentity.remotePublicKey((SSLSocket)s));}catch(Exception e){return;}
    for(Group g:groups())if(g.owner.equals(id)&&Arrays.asList(g.members).contains(p.id)&&!Arrays.asList(g.acknowledged.split(",")).contains(p.id))try(Socket s=connect(p.host,p.port)){
      String fingerprint=SecureIdentity.remote((SSLSocket)s);if(!trusted(p.id,fingerprint))return;write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id)||!read(s).equals("LM4\tREADY"))return;
      write(s,"LM4\tGROUP\t"+g.id+"\t"+g.owner+"\t"+enc(g.name)+"\t"+String.join(",",g.members));if(!read(s).equals("LM4\tGROUPACK\t"+g.id)||!trusted(p.id,fingerprint))return;
      synchronized(this){Group current=groups.get(g.id);String old=current.acknowledged;current.acknowledged=old.isEmpty()?p.id:old+","+p.id;try{save();}catch(IOException e){current.acknowledged=old;throw e;}}
    }catch(Exception e){return;}
    for(Group g:groups())if(Arrays.asList(g.members).contains(id)&&Arrays.asList(g.members).contains(p.id))try(Socket s=connect(p.host,p.port)){
      String fp=SecureIdentity.remote((SSLSocket)s);if(!trusted(p.id,fp))return;write(s,hello());String[] hello=read(s).split("\t",-1);if(!validHello(hello)||!hello[2].equals(p.id)||!read(s).equals("LM4\tREADY"))return;
      ArrayList<String> known=new ArrayList<>();LinkedHashSet<String> seen=new LinkedHashSet<>();synchronized(this){for(Message m:messages)if(m.groupId.equals(g.id)&&!m.signature.isEmpty()&&System.currentTimeMillis()<=m.time+GROUP_TTL_MS&&seen.add(m.id))known.add(m.id);}
      write(s,"LM4\tSYNCREQ\t"+g.id+"\t"+String.join(",",known));
      while(true){
        String[] reply=read(s).split("\t",-1);
        if(reply.length==2&&reply[0].equals("LM4")&&reply[1].equals("SYNCDONE"))break;
        if(reply.length!=12||!reply[0].equals("LM4")||!reply[1].equals("RELAY")||!uuid(reply[2])||!reply[3].equals(g.id))return;
        String rid=reply[2],origSender=reply[4];long at;int fileSize;
        try{at=Long.parseLong(reply[6]);fileSize=Integer.parseInt(reply[9]);}catch(NumberFormatException e){write(s,"LM4\tRELAYACK\t"+rid);continue;}
        if(at<0){write(s,"LM4\tRELAYACK\t"+rid);continue;}
        String rtext=dec(reply[7]),rfileName=dec(reply[8]),rfileHash=reply[10],rsignature=reply[11];
        if(System.currentTimeMillis()>at+GROUP_TTL_MS){write(s,"LM4\tRELAYACK\t"+rid);continue;}
        validateFile(rfileName,fileSize,rfileHash);
        byte[] rdata=fileSize>0?readBytes(s,fileSize):new byte[0];
        Message incoming=null;
        if(rfileName.isEmpty()||SecureIdentity.hash(rdata).equals(rfileHash)){
          String origKey;synchronized(this){Peer op=peers.get(origSender);origKey=op!=null&&op.trusted()?op.publicKey:"";}
          if(!origKey.isEmpty()&&SecureIdentity.verify(Base64.getDecoder().decode(origKey),canonicalBytes(rid,g.id,origSender,at,rtext,rfileName,fileSize,rfileHash),Base64.getDecoder().decode(rsignature))){
            synchronized(this){if(allowedGroup(g.id,origSender)&&!hidden.contains(origSender+"/"+rid)){boolean found=false;for(Message old:messages)if(old.id.equals(rid)&&old.from.equals(origSender)){found=true;break;}if(!found){Message msg=new Message(rid,origSender,id,rtext,at,"Received",g.id,rfileName,fileSize,rfileHash,rsignature,true);if(!rfileName.isEmpty())storeAttachment(msg,rdata);messages.add(msg);try{save();}catch(IOException e){messages.remove(msg);if(!rfileName.isEmpty())attachmentPath(msg).delete();throw e;}incoming=msg;}}}
          }
        }
        write(s,"LM4\tRELAYACK\t"+rid);
        if(incoming!=null){try{received.accept(incoming);}catch(Exception ignored){}notifyChanged();}
      }
    }catch(Exception e){return;}
    ArrayList<Message> queued=new ArrayList<>();synchronized(this){for(Message m:messages)if(m.to.equals(p.id)&&m.status.equals("Queued"))queued.add(m);}
    for(Message m:queued)try(Socket s=connect(p.host,p.port)){String fingerprint=SecureIdentity.remote((SSLSocket)s);if(!trusted(p.id,fingerprint))return;write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id))return;recordCertificate(h[2],fingerprint,SecureIdentity.remotePublicKey((SSLSocket)s));if(!read(s).equals("LM4\tREADY")||!trusted(p.id,fingerprint))return;
      byte[] data;synchronized(this){if(!messages.contains(m))continue;data=m.fileName.isEmpty()?new byte[0]:readAttachment(m);}
      write(s,"LM4\tMSG\t"+m.id+"\t"+id+"\t"+m.to+"\t"+enc(name)+"\t"+m.time+"\t"+enc(m.text)+"\t"+m.groupId+"\t"+enc(m.fileName)+"\t"+m.fileSize+"\t"+m.fileHash+(m.groupId.isEmpty()?"":"\t"+m.signature));
      if(data.length>0){s.getOutputStream().write(data);s.getOutputStream().flush();}
      String ack=read(s);if(!ack.equals("LM4\tACK\t"+m.id+"\t"+p.id)||!trusted(p.id,fingerprint))return;synchronized(this){if(!messages.contains(m))continue;m.status="Delivered";try{save();}catch(IOException e){m.status="Queued";throw e;}}notifyChanged();}catch(Exception e){return;}
    ArrayList<Message> toConfirm=new ArrayList<>();synchronized(this){for(Message m:messages)if(m.to.equals(id)&&m.from.equals(p.id)&&m.status.equals("Read"))toConfirm.add(m);}
    for(Message m:toConfirm)try(Socket s=connect(p.host,p.port)){String fingerprint=SecureIdentity.remote((SSLSocket)s);if(!trusted(p.id,fingerprint))return;write(s,hello());String[] h=read(s).split("\t",-1);if(!validHello(h)||!h[2].equals(p.id))return;recordCertificate(h[2],fingerprint,SecureIdentity.remotePublicKey((SSLSocket)s));if(!read(s).equals("LM4\tREADY")||!trusted(p.id,fingerprint))return;
      write(s,"LM4\tSEEN\t"+m.id+"\t"+id);
      if(!read(s).equals("LM4\tSEENACK\t"+m.id)||!trusted(p.id,fingerprint))return;
      synchronized(this){if(!messages.contains(m))continue;m.status="Seen";try{save();}catch(IOException e){m.status="Read";throw e;}}notifyChanged();
    }catch(Exception e){return;}
  }


  public static final int MAX_FILE_SIZE=200*1024*1024;
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
  public void queueFile(String conversation,String caption,String name,byte[] data)throws IOException {queueContent(conversation,caption,safeFileName(name),data);}
  synchronized void queueContent(String conversation,String text,String fileName,byte[] data)throws IOException {
    text=text.trim();if(text.length()>2000||(data==null&&text.isEmpty()))throw new IOException("Messages must contain 1–2000 characters.");
    if(data!=null&&data.length>MAX_FILE_SIZE)throw new IOException("Files must be "+(MAX_FILE_SIZE/1024/1024)+" MB or smaller.");
    ArrayList<String> recipients=new ArrayList<>();String groupId="";Group group=groups.get(conversation);
    if(group!=null){groupId=group.id;for(String member:group.members)if(!member.equals(id))recipients.add(member);}else if(peers.containsKey(conversation))recipients.add(conversation);else throw new IOException("Choose a conversation first.");
    String messageId=UUID.randomUUID().toString(),hash="";try{if(data!=null)hash=SecureIdentity.hash(data);}catch(Exception e){throw new IOException(e);}long at=System.currentTimeMillis();ArrayList<Message> batch=new ArrayList<>();
    String signature="";if(!groupId.isEmpty())try{signature=Base64.getEncoder().encodeToString(identity.sign(canonicalBytes(messageId,groupId,id,at,text,fileName,data==null?0:data.length,hash)));}catch(Exception e){throw new IOException(e);}
    for(String to:recipients)batch.add(new Message(messageId,id,to,text,at,"Queued",groupId,fileName,data==null?0:data.length,hash,signature,!groupId.isEmpty()));
    if(data!=null)storeAttachment(batch.get(0),data);messages.addAll(batch);try{save();}catch(IOException e){messages.removeAll(batch);if(data!=null)attachmentPath(batch.get(0)).delete();throw e;}notifyChanged();
  }
  public synchronized void clearConversation(String conversation)throws IOException {
    ArrayList<Message> removed=new ArrayList<>(),old=new ArrayList<>(messages);HashSet<String> oldHidden=new HashSet<>(hidden);
    for(Message m:messages)if(!m.groupId.isEmpty()?m.groupId.equals(conversation):m.from.equals(conversation)||m.to.equals(conversation)){removed.add(m);hidden.add(m.from+"/"+m.id);}
    messages.removeAll(removed);try{save();}catch(IOException e){messages.clear();messages.addAll(old);hidden.clear();hidden.addAll(oldHidden);throw e;}save();
    for(Message m:removed)if(!m.fileName.isEmpty())attachmentPath(m).delete();notifyChanged();
  }
  public static String safeFileName(String name){String[] parts=name.replace('\\','/').split("/",-1);name=parts[parts.length-1];StringBuilder b=new StringBuilder();for(char c:name.toCharArray())if(c>=32&&"<>:\"/\\|?*".indexOf(c)<0)b.append(c);name=b.toString().trim().replaceAll("^\\.+|\\.+$","");return name.isEmpty()?"attachment":name.substring(0,Math.min(120,name.length()));}
  static void validateFile(String name,int size,String hash)throws IOException {if(size<0||size>MAX_FILE_SIZE||(name.isEmpty()?(size!=0||!hash.isEmpty()):(!name.equals(safeFileName(name))||!hash.matches("[0-9a-f]{64}"))))throw new IOException("Invalid attachment metadata");}
  File attachmentPath(Message m)throws IOException {if(!uuid(m.from)||!uuid(m.id))throw new IOException("Invalid attachment ID");return new File(new File(file.getParentFile(),"attachments"),m.from+"-"+m.id+".sec");}
  void storeAttachment(Message m,byte[] data)throws IOException {
    File path=attachmentPath(m);if(!path.getParentFile().exists()&&!path.getParentFile().mkdirs())throw new IOException("Cannot create attachment storage");File tmp=new File(path+".tmp");
    try(FileOutputStream out=new FileOutputStream(tmp)){out.write(protector.protect(data));out.getFD().sync();}catch(Exception e){throw new IOException(e);}if(!tmp.renameTo(path))throw new IOException("Cannot save attachment");
  }
  public synchronized byte[] readAttachment(Message m)throws IOException {try{byte[] data=protector.unprotect(SecureIdentity.readFile(attachmentPath(m)));if(data.length!=m.fileSize||!SecureIdentity.hash(data).equals(m.fileHash))throw new IOException("Attachment integrity check failed");return data;}catch(Exception e){throw new IOException(e);}}
  static byte[] readBytes(Socket s,int size)throws IOException {byte[] data=new byte[size];InputStream in=s.getInputStream();int at=0;long end=System.nanoTime()+transferTimeoutNanos(size);while(at<size){if(System.nanoTime()>end)throw new IOException("Attachment timeout");int n=in.read(data,at,Math.min(65536,size-at));if(n<0)throw new EOFException();at+=n;}return data;}

  void notifyChanged(){try{changed.run();}catch(Exception ignored){}}
  public synchronized void close(){running=false;try{if(listener!=null)listener.close();}catch(IOException ignored){}if(discovery!=null)discovery.close();timer.shutdownNow();connections.shutdownNow();}
}
