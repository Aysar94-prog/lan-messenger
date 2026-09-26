package net.lanmsg.chat;

import java.io.IOException;
import java.util.*;
import javax.net.ssl.SSLSocket;

/** Group membership, invitations and the SYNCREQ2/META relay that lets members catch up on history. */
final class GroupSync {
  private GroupSync(){}
  static List<PeerEngine.Group> groups(PeerEngine e){synchronized(e){ArrayList<PeerEngine.Group> result=new ArrayList<>();for(PeerEngine.Group g:e.groups.values())result.add(new PeerEngine.Group(g.id,g.owner,g.name,g.members,g.acknowledged,g.left));return result;}}
  static String displayName(PeerEngine e,String target){synchronized(e){return target.equals(e.id)?e.name:e.groups.containsKey(target)?e.groups.get(target).name:e.peers.containsKey(target)?e.peers.get(target).name:"Device "+target.substring(0,Math.min(8,target.length()));}}
  static String createGroup(PeerEngine e,String name,List<String> members)throws IOException {
    synchronized(e){
      name=name.trim();TreeSet<String> ids=new TreeSet<>(members);ids.add(e.id);
      if(name.isEmpty()||name.length()>50||ids.size()<3||ids.size()>16)throw new IOException("Name the group and select 2–15 verified contacts.");
      for(String target:ids)if(!target.equals(e.id)&&(!e.peers.containsKey(target)||!e.peers.get(target).trusted()))throw new IOException("Select verified contacts.");
      PeerEngine.Group g=new PeerEngine.Group(UUID.randomUUID().toString(),e.id,name,ids.toArray(new String[0]),"");e.groups.put(g.id,g);try{e.save();}catch(IOException ex){e.groups.remove(g.id);throw ex;}e.notifyChanged();return g.id;
    }
  }
  // Owner-side: a member has told us they left. Recorded separately from acknowledged so the
  // invite-resend loop stops for them until reinviteMember explicitly clears it.
  static void handleLeave(PeerEngine e,String groupId,String memberId)throws IOException{
    synchronized(e){
      PeerEngine.Group g=e.groups.get(groupId);if(g==null||!g.owner.equals(e.id))return;
      ArrayList<String> left=new ArrayList<>();for(String x:g.left.split(",",-1))if(!x.isEmpty())left.add(x);
      if(left.contains(memberId))return;
      String old=g.left;left.add(memberId);g.left=String.join(",",left);
      try{e.save();}catch(IOException ex){g.left=old;throw ex;}
    }
  }
  static boolean allowedGroup(PeerEngine e,String group,String sender){synchronized(e){PeerEngine.Group g=e.groups.get(group);return group.isEmpty()||(g!=null&&Arrays.asList(g.members).contains(e.id)&&Arrays.asList(g.members).contains(sender));}}
  static void acceptGroup(PeerEngine e,String[] a,String sender,String fingerprint)throws IOException {
    synchronized(e){
      String[] members=a[5].split(",",-1);String name=PeerEngine.dec(a[4]);List<String> ids=Arrays.asList(members);
      if(!e.trusted(sender,fingerprint)||!PeerEngine.uuid(a[2])||!a[3].equals(sender)||name.trim().isEmpty()||name.length()>50||ids.size()<3||ids.size()>16||new HashSet<>(ids).size()!=ids.size()||!ids.contains(e.id)||!ids.contains(sender)||e.peers.containsKey(a[2])||a[2].equals(e.id))throw new IOException("Invalid group invitation");
      for(String member:members)if(!PeerEngine.uuid(member))throw new IOException("Invalid member");
      PeerEngine.Group old=e.groups.get(a[2]);if(old!=null){if(!old.owner.equals(sender)||!old.name.equals(name)||!Arrays.equals(old.members,members))throw new IOException("Group membership cannot be replaced");return;}
      e.groups.put(a[2],new PeerEngine.Group(a[2],sender,name,members,""));try{e.save();}catch(IOException ex){e.groups.remove(a[2]);throw ex;}
    }
  }
  static void handleSync(PeerEngine e,SSLSocket s,String group,String knownIdsCsv,String peerId){
    HashSet<String> known=new HashSet<>();if(!knownIdsCsv.isEmpty())known.addAll(Arrays.asList(knownIdsCsv.split(",",-1)));
    ArrayList<PeerEngine.Message> offer=new ArrayList<>();
    synchronized(e){
      if(allowedGroup(e,group,peerId)){LinkedHashSet<String> seen=new LinkedHashSet<>();for(PeerEngine.Message m:e.messages)if(m.groupId.equals(group)&&!m.signature.isEmpty()&&System.currentTimeMillis()<=m.time+PeerEngine.GROUP_TTL_MS&&!known.contains(m.id)&&seen.add(m.id))offer.add(m);}
    }
    try{
      for(PeerEngine.Message m:offer){
        PeerEngine.write(s,"LM4\tMETA\t"+m.id+"\t"+group+"\t"+m.from+"\t"+PeerEngine.enc(displayName(e,m.from))+"\t"+m.time+"\t"+PeerEngine.enc(m.text)+"\t"+PeerEngine.enc(m.fileName)+"\t"+m.fileSize+"\t"+m.fileHash+"\t"+m.signature);
        // Data moves only after the receiver requests FETCH.
        if(!PeerEngine.read(s).equals("LM4\tRELAYACK\t"+m.id))return;
      }
      PeerEngine.write(s,"LM4\tSYNCDONE");
    }catch(Exception ignored){}
  }
}
