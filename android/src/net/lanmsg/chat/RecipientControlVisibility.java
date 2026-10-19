package net.lanmsg.chat;

/** Call-scoped presentation status. Recipient authorization remains authoritative. */
final class RecipientControlVisibility {
  static final long REFRESH_MS=5000,FRESH_MS=10000;
  private String callId;
  private long sequence,lastAttempt=-REFRESH_MS,confirmedAt;
  private boolean pending,confirmed;
  private int mask;
  synchronized long begin(String activeCall,long now){
    if(activeCall==null||!activeCall.equals(callId)){
      callId=activeCall;sequence++;pending=false;confirmed=false;mask=0;lastAttempt=now-REFRESH_MS;
    }
    if(activeCall==null||pending||now-lastAttempt<REFRESH_MS)return -1;
    pending=true;lastAttempt=now;return ++sequence;
  }
  synchronized void finish(String queriedCall,long token,int scopes,boolean success,long now){
    if(token!=sequence||queriedCall==null||!queriedCall.equals(callId))return;
    pending=false;confirmed=success;mask=success?scopes&12:0;confirmedAt=now;
  }
  synchronized int visible(String activeCall,long now){
    return activeCall!=null&&activeCall.equals(callId)&&confirmed&&now>=confirmedAt&&now-confirmedAt<FRESH_MS?mask:0;
  }
}
