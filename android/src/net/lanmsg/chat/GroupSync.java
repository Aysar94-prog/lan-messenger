package net.lanmsg.chat;

import java.io.IOException;
import java.net.Socket;
import java.util.*;
import javax.net.ssl.SSLSocket;

/** Group membership, invitations and the SYNCREQ2/META relay that lets members catch up on history. */
final class GroupSync {
  private GroupSync(){}
  static List<PeerEngine.Group> groups(PeerEngine e){synchronized(e){ArrayList<PeerEngine.Group> result=new ArrayList<>();for(PeerEngine.Group g:e.groups.values())result.add(new PeerEngine.Group(g.id,g.owner,g.name,g.members,g.membersVersion));return result;}}
  static String displayName(PeerEngine e,String target){synchronized(e){return target.equals(e.id)?e.name:e.groups.containsKey(target)?e.groups.get(target).name:e.peers.containsKey(target)?e.peers.get(target).name:"Device "+target.substring(0,Math.min(8,target.length()));}}
  // Union of the live roster and the departed-history record, for the Members dialog — a departed
  // id no longer appears in a Group's members, so callers that want to show (and Re-invite) them
  // need this instead.
  static List<PeerEngine.KnownMember> allKnownMembers(PeerEngine e,String groupId){
    synchronized(e){
      PeerEngine.Group g=e.groups.get(groupId);ArrayList<PeerEngine.KnownMember> result=new ArrayList<>();
      if(g==null)return result;
      for(String m:g.members)result.add(new PeerEngine.KnownMember(m,true));
      HashSet<String> departed=e.departedHistory.get(groupId);
      if(departed!=null)for(String m:departed)result.add(new PeerEngine.KnownMember(m,false));
      return result;
    }
  }
  static String createGroup(PeerEngine e,String name,List<String> members)throws IOException {
    synchronized(e){
      name=name.trim();TreeSet<String> ids=new TreeSet<>(members);ids.add(e.id);
      if(name.isEmpty()||name.length()>50||ids.size()<3||ids.size()>16)throw new IOException("Name the group and select 2–15 verified contacts.");
      for(String target:ids)if(!target.equals(e.id)&&(!e.peers.containsKey(target)||!e.peers.get(target).trusted()))throw new IOException("Select verified contacts.");
      PeerEngine.Group g=new PeerEngine.Group(UUID.randomUUID().toString(),e.id,name,ids.toArray(new String[0]),0);e.groups.put(g.id,g);try{e.save();}catch(IOException ex){e.groups.remove(g.id);throw ex;}e.notifyChanged();return g.id;
    }
  }
  // Owner-side: a member has told us they left. Never gated by anyone's capability — a fact that
  // already happened, not a request the owner can refuse. Removes them from the live roster
  // (shrinking it), records them in the separate departed history, and bumps the version so
  // deliver's broadcast loop picks up every remaining active member automatically.
  static void handleLeave(PeerEngine e,String groupId,String memberId)throws IOException{
    synchronized(e){
      PeerEngine.Group g=e.groups.get(groupId);if(g==null||!g.owner.equals(e.id))return;
      boolean isMember=false;for(String m:g.members)if(m.equals(memberId)){isMember=true;break;}
      if(!isMember)return;
      String[] oldMembers=g.members;int oldVersion=g.membersVersion;
      ArrayList<String> kept=new ArrayList<>();for(String m:g.members)if(!m.equals(memberId))kept.add(m);
      g.members=kept.toArray(new String[0]);g.membersVersion=oldVersion+1;
      HashSet<String> set=e.departedHistory.get(groupId);if(set==null){set=new HashSet<>();e.departedHistory.put(groupId,set);}
      boolean hadHistory=set.contains(memberId);set.add(memberId);
      try{e.save();}catch(IOException ex){g.members=oldMembers;g.membersVersion=oldVersion;if(!hadHistory)set.remove(memberId);throw ex;}
    }
  }
  static boolean allowedGroup(PeerEngine e,String group,String sender){synchronized(e){PeerEngine.Group g=e.groups.get(group);return group.isEmpty()||(g!=null&&Arrays.asList(g.members).contains(e.id)&&Arrays.asList(g.members).contains(sender));}}
  static void acceptGroup(PeerEngine e,String[] a,String sender,String fingerprint)throws IOException {
    synchronized(e){
      String[] members=a[5].split(",",-1);String name=PeerEngine.dec(a[4]);List<String> ids=Arrays.asList(members);
      int version=a.length==7?Integer.parseInt(a[6]):0;
      if(!e.trusted(sender,fingerprint)||!PeerEngine.uuid(a[2])||!a[3].equals(sender)||name.trim().isEmpty()||name.length()>50||ids.size()>16||new HashSet<>(ids).size()!=ids.size()||!ids.contains(e.id)||!ids.contains(sender)||e.peers.containsKey(a[2])||a[2].equals(e.id))throw new IOException("Invalid group invitation");
      for(String member:members)if(!PeerEngine.uuid(member))throw new IOException("Invalid member");
      PeerEngine.Group old=e.groups.get(a[2]);
      if(old!=null){
        if(!old.owner.equals(sender)||!old.name.equals(name))throw new IOException("Group membership cannot be replaced");
        if(version<=old.membersVersion)return; // stale/duplicate retry; already at least this current
        String[] oldMembers=old.members;int oldVersion=old.membersVersion;
        old.members=members.clone();old.membersVersion=version;
        try{e.save();}catch(IOException ex){old.members=oldMembers;old.membersVersion=oldVersion;throw ex;}
        return;
      }
      e.groups.put(a[2],new PeerEngine.Group(a[2],sender,name,members,version));try{e.save();}catch(IOException ex){e.groups.remove(a[2]);throw ex;}
    }
  }
  // Update-only: applies only to a group the recipient already has a local record for. A stray or
  // late MEMBERSUPDATE for a group with no local record (departed, or never a member) is ignored,
  // same as an unknown group id is rejected elsewhere — only a GROUP frame ever creates a record.
  static void handleMembersUpdate(PeerEngine e,String[] a,String sender,String fingerprint)throws IOException {
    if(!PeerEngine.uuid(a[2]))throw new IOException("Invalid membership update");
    int version;try{version=Integer.parseInt(a[3]);}catch(NumberFormatException ex){throw new IOException("Invalid membership update");}
    String[] members=a[4].split(",",-1);
    synchronized(e){
      PeerEngine.Group old=e.groups.get(a[2]);if(old==null)return;
      if(!old.owner.equals(sender)||!e.trusted(sender,fingerprint))return;
      if(version<=old.membersVersion)return;
      List<String> ids=Arrays.asList(members);
      if(ids.size()>16||new HashSet<>(ids).size()!=ids.size()||!ids.contains(e.id)||!ids.contains(sender))return;
      for(String m:members)if(!PeerEngine.uuid(m))return;
      String[] oldMembers=old.members;int oldVersion=old.membersVersion;
      old.members=members.clone();old.membersVersion=version;
      try{e.save();}catch(IOException ex){old.members=oldMembers;old.membersVersion=oldVersion;throw ex;}
    }
  }
  // Queries a peer live, every time — a past success is never trusted as durable proof, since the
  // same device could have been downgraded, reinstalled, or restored from a backup since. Returns
  // 0 (unsupported) for any failure: unreachable, connection error, or an invalid/missing reply —
  // which is exactly how a build that's never heard of CAPS also looks from here.
  static int queryCapability(PeerEngine e,PeerEngine.Peer peer){
    try(Socket s=e.connect(peer.host,peer.port)){
      String fp=SecureIdentity.remote((SSLSocket)s);if(!e.trusted(peer.id,fp))return 0;
      PeerEngine.write(s,e.hello());String[] hello=PeerEngine.read(s).split("\t",-1);if(!e.validHello(hello)||!hello[2].equals(peer.id))return 0;
      if(!PeerEngine.read(s).equals("LM4\tREADY"))return 0;
      PeerEngine.write(s,"LM4\tCAPS");String[] reply=PeerEngine.read(s).split("\t",-1);
      if(reply.length!=3||!reply[0].equals("LM4")||!reply[1].equals("CAPS"))return 0;
      try{return Integer.parseInt(reply[2]);}catch(NumberFormatException ex){return 0;}
    }catch(Exception ex){return 0;}
  }
  // The one real membership mutation — reinviteMember and (later) accept-on-a-join-request both
  // call this and nothing else. Refuses outright (nothing mutated) unless every id that would end
  // up in members — every current active member, plus whoever's being added — answers a fresh CAPS
  // query, at this exact moment, confirming support. Never gates an incoming LEAVE.
  static void addMember(PeerEngine e,String groupId,String memberId)throws IOException {
    ArrayList<String> toCheck=new ArrayList<>();
    synchronized(e){
      PeerEngine.Group g=e.groups.get(groupId);if(g==null||!g.owner.equals(e.id))throw new IOException("Only the group owner can add a member.");
      for(String m:g.members)if(m.equals(memberId))return;
      if(g.members.length>=16)throw new IOException("This group already has 16 members.");
      for(String m:g.members)toCheck.add(m);toCheck.add(memberId);
    }
    for(String candidate:toCheck){
      if(candidate.equals(e.id))continue;
      PeerEngine.Peer p;synchronized(e){p=e.peers.get(candidate);}
      if(p==null)throw new IOException("Every member must already be a verified contact.");
      if(queryCapability(e,p)<1)throw new IOException("Can't change this group's membership: "+p.name+" hasn't updated to a version that supports it.");
    }
    synchronized(e){
      PeerEngine.Group g=e.groups.get(groupId);if(g==null||!g.owner.equals(e.id))throw new IOException("Only the group owner can add a member.");
      for(String m:g.members)if(m.equals(memberId))return;
      if(g.members.length>=16)throw new IOException("This group already has 16 members.");
      String[] oldMembers=g.members;int oldVersion=g.membersVersion;
      String[] newMembers=Arrays.copyOf(g.members,g.members.length+1);newMembers[g.members.length]=memberId;
      // Whether brand-new or returning, whoever's added needs a fresh first-time GROUP invite, not
      // a MEMBERSUPDATE — a returning member deleted their own local record when they left, so any
      // stale acked-version from before they left must not make deliver() think they already have a
      // live copy to merely update.
      HashMap<String,Integer> m=e.memberAcked.get(groupId);Integer oldAck=m!=null?m.remove(memberId):null;
      // A direct invite/Re-invite landing while a join request from the same person is still
      // pending must collapse into one add with no leftover queue entry — regardless of which
      // path actually added them, not just acceptJoinRequest's own.
      HashMap<String,Long> reqs=e.joinRequests.get(groupId);boolean hadRequest=reqs!=null&&reqs.containsKey(memberId);Long oldRequestValue=reqs!=null?reqs.get(memberId):null;
      if(reqs!=null)reqs.remove(memberId);
      g.members=newMembers;g.membersVersion=oldVersion+1;
      try{e.save();}catch(IOException ex){g.members=oldMembers;g.membersVersion=oldVersion;if(oldAck!=null){if(m==null){m=new HashMap<>();e.memberAcked.put(groupId,m);}m.put(memberId,oldAck);}if(hadRequest)reqs.put(memberId,oldRequestValue);throw ex;}
    }
    e.notifyChanged();e.queueEpoch.incrementAndGet();e.flush();
  }
  static final long JOIN_REQUEST_COOLDOWN_MS=3600_000L;
  // Requester side: sends a request to join a group, given its id and a verified contact who owns
  // it. Delivery is guaranteed the same way FORGET/LEAVE are -- retried until acked -- with no
  // user-visible "pending" state: if accepted, the group simply appears like any invite does.
  static void requestJoin(PeerEngine e,String ownerId,String groupId)throws IOException {
    synchronized(e){
      if(!PeerEngine.uuid(groupId))throw new IOException("Enter a valid group ID.");
      PeerEngine.Peer p=e.peers.get(ownerId);if(p==null||!p.trusted())throw new IOException("Choose a verified contact.");
      if(e.groups.containsKey(groupId))throw new IOException("You're already part of this group.");
      HashSet<String> set=e.pendingJoinRequests.get(ownerId);if(set==null){set=new HashSet<>();e.pendingJoinRequests.put(ownerId,set);}
      if(!set.add(groupId))return; // already requested; no-op
      try{e.save();}catch(IOException ex){set.remove(groupId);throw ex;}
    }
    e.notifyChanged();e.queueEpoch.incrementAndGet();e.flush();
  }
  // Owner side: records an incoming join request. Verified contact required to even record it; a
  // repeat request while already Pending is a no-op; a request from someone recently Ignored is
  // silently swallowed until the 1-hour cooldown passes, without refreshing that cooldown.
  static void handleJoinRequest(PeerEngine e,String groupId,String requesterId,String fingerprint)throws IOException {
    synchronized(e){
      if(!e.trusted(requesterId,fingerprint))return;
      PeerEngine.Group g=e.groups.get(groupId);if(g==null||!g.owner.equals(e.id))return;
      HashMap<String,Long> reqs=e.joinRequests.get(groupId);if(reqs==null){reqs=new HashMap<>();e.joinRequests.put(groupId,reqs);}
      if(reqs.containsKey(requesterId)){
        Long existing=reqs.get(requesterId);
        if(existing==null)return; // already pending
        if(System.currentTimeMillis()-existing<JOIN_REQUEST_COOLDOWN_MS)return; // cooldown still active
      }
      boolean had=reqs.containsKey(requesterId);Long oldValue=reqs.get(requesterId);
      reqs.put(requesterId,null);
      try{e.save();}catch(IOException ex){if(had)reqs.put(requesterId,oldValue);else reqs.remove(requesterId);throw ex;}
    }
  }
  // Owner-only: currently pending (not ignored) requesters for a group, for the request-queue UI.
  static List<String> pendingJoinRequests(PeerEngine e,String groupId){
    synchronized(e){
      HashMap<String,Long> reqs=e.joinRequests.get(groupId);ArrayList<String> result=new ArrayList<>();
      if(reqs!=null)for(Map.Entry<String,Long> kv:reqs.entrySet())if(kv.getValue()==null)result.add(kv.getKey());
      return result;
    }
  }
  static void clearJoinRequestLocked(PeerEngine e,String groupId,String requesterId)throws IOException {
    HashMap<String,Long> reqs=e.joinRequests.get(groupId);if(reqs==null)return;
    boolean had=reqs.containsKey(requesterId);Long old=reqs.get(requesterId);
    reqs.remove(requesterId);
    try{e.save();}catch(IOException ex){if(had)reqs.put(requesterId,old);throw ex;}
  }
  // Test-only: backdates an Ignored request's timestamp so tests can exercise "the 1-hour cooldown
  // has elapsed" without a real wall-clock wait.
  static void debugBackdateIgnoredJoinRequest(PeerEngine e,String groupId,String requesterId,long epochMillis)throws IOException {
    synchronized(e){
      HashMap<String,Long> reqs=e.joinRequests.get(groupId);if(reqs==null||!reqs.containsKey(requesterId))throw new IOException("No such request.");
      Long old=reqs.get(requesterId);reqs.put(requesterId,epochMillis);
      try{e.save();}catch(IOException ex){reqs.put(requesterId,old);throw ex;}
    }
  }
  // Owner-only: disappears from the queue, silently -- no notice to the requester, no visible
  // "ignored" record anywhere. The timestamp kept internally only enforces the cooldown above.
  static void ignoreJoinRequest(PeerEngine e,String groupId,String requesterId)throws IOException {
    synchronized(e){
      PeerEngine.Group g=e.groups.get(groupId);if(g==null||!g.owner.equals(e.id))throw new IOException("Only the group owner can ignore a request.");
      HashMap<String,Long> reqs=e.joinRequests.get(groupId);if(reqs==null||!reqs.containsKey(requesterId))return;
      Long old=reqs.get(requesterId);
      reqs.put(requesterId,System.currentTimeMillis());
      try{e.save();}catch(IOException ex){reqs.put(requesterId,old);throw ex;}
    }
    e.notifyChanged();
  }
  // Owner-only: re-validated at the moment of the decision, not from when the request was first
  // sent. Not already a member, or no longer a verified contact -- cleared, nothing recoverable
  // without a fresh request. Group full, or an existing member's live capability check fails --
  // addMember itself throws and the request is deliberately left Pending (recoverable later).
  static void acceptJoinRequest(PeerEngine e,String groupId,String requesterId)throws IOException {
    synchronized(e){
      PeerEngine.Group g=e.groups.get(groupId);if(g==null||!g.owner.equals(e.id))throw new IOException("Only the group owner can accept a request.");
      HashMap<String,Long> reqs=e.joinRequests.get(groupId);
      if(reqs==null||!reqs.containsKey(requesterId)||reqs.get(requesterId)!=null)throw new IOException("This request is no longer pending.");
      boolean isMember=false;for(String m:g.members)if(m.equals(requesterId)){isMember=true;break;}
      if(!isMember){
        PeerEngine.Peer p=e.peers.get(requesterId);
        if(p==null||!p.trusted()){clearJoinRequestLocked(e,groupId,requesterId);throw new IOException("This person is no longer a verified contact.");}
      }
    }
    addMember(e,groupId,requesterId); // throws (Pending kept) for a full group or a failed live capability check
    synchronized(e){clearJoinRequestLocked(e,groupId,requesterId);}
    e.notifyChanged();
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
