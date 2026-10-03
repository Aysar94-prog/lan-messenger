package net.lanmsg.chat;

/** A04: call-bound video consent, independent of microphone mute/voice state.
 * Pure Java. Owns no Activity, camera, socket or media object. All mutations are
 * serialized; the controller must separately authenticate/validate wire input. */
public final class CallVideoConsent {
  public enum Result { Ready, Voice, Prompt, Declined, Ignored, Denied, Busy, Unsupported }
  public enum Phase { Voice, Waiting, Negotiating, Video, Ended }
  private final String callId;
  private final boolean capable, caller, invitedVideo;
  private boolean initialAnswered, connected, ended, localConsent, peerConsent;
  private boolean cameraWanted, mediaReady, remoteRequest, remoteCamera;
  private String request;
  private long generation, lastGeneration, remoteRevision = -1;
  private final java.util.Set<String> usedRequests = new java.util.HashSet<String>();
  private static final int MAX_REQUESTS = 128;

  public CallVideoConsent(String callId, boolean capable, boolean caller, boolean invitedVideo) {
    if (!CallProtocol.validCallId(callId)) throw new IllegalArgumentException("Invalid call ID");
    if (invitedVideo && !capable) throw new IllegalArgumentException("Video needs capability");
    this.callId = callId; this.capable = capable; this.caller = caller;
    this.invitedVideo = invitedVideo;
    // Outgoing video invitation is an explicit local action; incoming invitation
    // expresses only the peer's consent, never local capture authorization.
    localConsent = invitedVideo && caller;
    peerConsent = invitedVideo && !caller;
    cameraWanted = localConsent;
  }

  private boolean live(String expected) { return !ended && callId.equals(expected); }
  public synchronized boolean isLive(String expected) { return live(expected); }

  public synchronized Result acceptInitial(String expected, boolean video, boolean eligible) {
    if (!live(expected) || caller || initialAnswered) return Result.Ignored;
    if (video && (!capable || !invitedVideo)) return Result.Unsupported;
    if (video && !eligible) return Result.Denied;
    initialAnswered = true;
    if (!video) { clearVideo(); return Result.Voice; }
    localConsent = peerConsent = cameraWanted = true;
    request = callId;
    usedRequests.add(request);
    return Result.Ready;
  }

  public synchronized Result peerAnswered(String expected, boolean video) {
    if (!live(expected) || !caller || initialAnswered) return Result.Ignored;
    if (video && (!capable || !invitedVideo)) return Result.Unsupported;
    initialAnswered = true;
    if (!video) { clearVideo(); return Result.Voice; }
    peerConsent = true; request = callId;
    usedRequests.add(request);
    return Result.Ready;
  }

  /** Sending acceptance failed before audio connected: permit an explicit retry/voice answer. */
  public synchronized void rollbackInitialAnswer(String expected) {
    if (!live(expected) || connected || caller) return;
    initialAnswered = false; clearVideo(); peerConsent = invitedVideo;
  }

  public synchronized void setConnected(String expected, boolean value) {
    if (!live(expected)) return;
    connected = value;
    if (!value) clearVideo();
  }

  /** eligible is a fresh permission/hardware/foreground check, not cached consent. */
  public synchronized Result requestUpgrade(String expected, String id, boolean eligible) {
    if (!live(expected) || !connected || !CallProtocol.validCallId(id)) return Result.Ignored;
    if (!capable) return Result.Unsupported;
    if (!eligible) return Result.Denied;
    if (request != null) return Result.Busy;
    if (usedRequests.contains(id)) return Result.Ignored;
    if (usedRequests.size() >= MAX_REQUESTS) return Result.Unsupported;
    usedRequests.add(id);
    request = id; remoteRequest = false;
    localConsent = cameraWanted = true; peerConsent = false;
    return Result.Ready;
  }

  /** A peer invitation is only a prompt. Lower UUID wins simultaneous requests. */
  public synchronized Result receiveRequest(String expected, String id) {
    if (!live(expected) || !connected || !CallProtocol.validCallId(id)) return Result.Ignored;
    if (!capable) return Result.Unsupported;
    if (generation != 0) return Result.Busy;
    if (usedRequests.contains(id) && !id.equals(request)) return Result.Ignored;
    if (usedRequests.size() >= MAX_REQUESTS && !usedRequests.contains(id)) return Result.Unsupported;
    if (request != null) {
      if (request.equals(id)) return Result.Ignored;
      if (request.compareTo(id) < 0) return Result.Declined;
      // A local action for the losing UUID cannot silently accept the winner.
      clearVideo();
    }
    request = id; remoteRequest = true; peerConsent = true;
    usedRequests.add(id);
    localConsent = cameraWanted = false;
    return Result.Prompt;
  }

  public synchronized Result acceptUpgrade(String expected, String id, boolean eligible) {
    if (!live(expected) || !connected || request == null || !request.equals(id)
        || !remoteRequest || localConsent) return Result.Ignored;
    if (!eligible) return Result.Denied;
    localConsent = cameraWanted = true;
    return Result.Ready;
  }

  public synchronized Result peerAccepted(String expected, String id) {
    if (!live(expected) || request == null || !request.equals(id) || remoteRequest
        || !localConsent || peerConsent) return Result.Ignored;
    peerConsent = true;
    return Result.Ready;
  }

  public synchronized Result declineUpgrade(String expected, String id) {
    if (!live(expected) || request == null || !request.equals(id)) return Result.Ignored;
    clearVideo(); return Result.Declined;
  }

  /** Caller allocates; callee admits only an authenticated caller's new generation. */
  public synchronized boolean authorizeGeneration(String expected, String id, long value) {
    if (!canAuthorizeGeneration(expected,id,value)) return false;
    generation = lastGeneration = value;
    mediaReady = false; remoteRevision = -1; remoteCamera = false;
    return true;
  }
  public synchronized boolean canAuthorizeGeneration(String expected,String id,long value) {
    return live(expected)&&connected&&localConsent&&peerConsent&&request!=null
      &&request.equals(id)&&generation==0&&value>=2&&value>lastGeneration;
  }
  public synchronized boolean canPeerAccept(String expected,String id) {
    return live(expected)&&request!=null&&request.equals(id)&&!remoteRequest
      &&localConsent&&!peerConsent&&generation==0;
  }

  public synchronized boolean markMediaReady(String expected, long value) {
    if (!live(expected) || generation == 0 || generation != value) return false;
    mediaReady = true; return true;
  }

  /** Used again at the actual camera acquisition, not only at button/permission time. */
  public synchronized boolean canCapture(String expected, boolean permission,
      boolean cameraAvailable, boolean foreground) {
    return live(expected) && connected && capable && localConsent && peerConsent
      && request != null && generation >= 2 && mediaReady && cameraWanted
      && permission && cameraAvailable && foreground;
  }

  public synchronized void cameraOff(String expected) {
    if (live(expected)) cameraWanted = false;
  }

  public synchronized Result cameraOn(String expected, boolean eligible) {
    if (!live(expected) || !connected || !mediaReady || generation < 2
        || !localConsent || !peerConsent) return Result.Ignored;
    if (!eligible) return Result.Denied;
    if (cameraWanted) return Result.Busy;
    cameraWanted = true; return Result.Ready;
  }

  public synchronized boolean remoteCameraState(String expected, long value,
      long revision, boolean on) {
    if (!canRemoteCameraState(expected,value,revision)) return false;
    remoteRevision = revision; remoteCamera = on;
    return true; // Does not change local consent or cameraWanted.
  }
  public synchronized boolean canRemoteCameraState(String expected,long value,long revision){
    return live(expected)&&generation!=0&&value==generation&&revision>=0&&revision>remoteRevision;
  }

  /** Background/revocation stops the camera. Return requires another local action. */
  public synchronized void revokeCapture(String expected) { cameraOff(expected); }

  /** Video failure/timeout never modifies the call's audio state. */
  public synchronized boolean failVideo(String expected, long value) {
    if (!live(expected) || generation == 0 || generation != value) return false;
    clearVideo(); return true;
  }

  public synchronized void end() { ended = true; connected = false; clearVideo(); }
  public synchronized String requestId() { return request; }
  public synchronized long generation() { return generation; }
  public synchronized boolean remoteCameraOn() { return remoteCamera; }
  public synchronized boolean remoteRequestPending(){return remoteRequest&&request!=null&&generation==0;}
  public synchronized Phase phase() {
    if (ended) return Phase.Ended;
    if (generation > 0) return mediaReady ? Phase.Video : Phase.Negotiating;
    return request == null ? Phase.Voice : Phase.Waiting;
  }
  private void clearVideo() {
    request = null; generation = 0; remoteRevision = -1;
    localConsent = peerConsent = cameraWanted = mediaReady = remoteRequest = remoteCamera = false;
  }
}
