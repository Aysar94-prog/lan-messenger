package net.lanmsg.chat;

/** Per-call authenticated envelope/replay boundary. Caller must check role,
 * state, request and media generation before passing permitted=true. Rejected
 * traffic must not refresh heartbeat/deadlines or consume the accepted sequence. */
public final class CallFrameAdmission {
  private final String callId, peerId;
  private final int version;
  private long lastAccepted;
  public CallFrameAdmission(String callId,String peerId,int version,long openingSequence) {
    if(!CallProtocol.validCallId(callId)||!CallProtocol.validCallId(peerId)
        ||(version!=1&&version!=2)||openingSequence<0)
      throw new IllegalArgumentException("Invalid authenticated call binding");
    this.callId=callId;this.peerId=peerId;this.version=version;lastAccepted=openingSequence;
  }
  public synchronized boolean admit(CallProtocol.Frame frame,String authenticatedPeer,boolean permitted) {
    if(!permitted||frame==null||!peerId.equals(authenticatedPeer)||!callId.equals(frame.callId)
        ||frame.protocolVersion!=version||frame.type==null||frame.senderSequence<=lastAccepted
        ||frame.senderSequence<=0)return false;
    if(version==2&&!CallVideoProtocol.valid(frame))return false;
    lastAccepted=frame.senderSequence;return true;
  }
  public synchronized long lastAcceptedSequence(){return lastAccepted;}
}
