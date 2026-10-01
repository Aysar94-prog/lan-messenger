package net.lanmsg.chat;

import java.util.*;

/** Shared call protocol: message types, state machine, frame format, and admission rules.
 *  Pure Java — no Android/JNI dependency.  Matches plan-v006 contracts exactly. */
public final class CallProtocol {

  private CallProtocol() {} // namespace

  // ── Message types ──────────────────────────────────────────────
  public static final String
    INVITE      = "INVITE",
    RINGING     = "RINGING",
    ACCEPT      = "ACCEPT",
    DECLINE     = "DECLINE",
    BUSY        = "BUSY",
    CANCEL      = "CANCEL",
    OFFER       = "OFFER",
    ANSWER      = "ANSWER",
    ICE         = "ICE",
    MEDIA_READY = "MEDIA_READY",
    HANGUP      = "HANGUP",
    ERROR       = "ERROR",
    PING        = "PING",
    PONG        = "PONG";

  // ── Call states ────────────────────────────────────────────────
  public enum State {
    Idle,
    OutgoingRinging,
    IncomingRinging,
    Connecting,
    Connected,
    Ending;

    public boolean terminal() { return this == Idle || this == Ending; }
    public boolean active()   { return this == Connecting || this == Connected; }
  }

  // ── Terminal reasons ───────────────────────────────────────────
  public enum EndReason {
    LOCAL_HANGUP,
    REMOTE_HANGUP,
    DECLINED,          // "Declined" — same wording for manual and policy
    BUSY_REMOTE,
    CANCELED,
    TIMEOUT_RINGING,
    TIMEOUT_MEDIA,
    SIGNALING_LOST,
    NETWORK_FAILURE,
    OFFLINE,
    MEDIA_ERROR,
    LOCAL_DECLINE,
    GLARE_RESOLVED,
    ENGINE_SHUTDOWN
  }

  // ── Admission & throttling constants ───────────────────────────
  /** Max distinct valid invitations per peer in a rolling window. */
  public static final int INVITATION_LIMIT_COUNT = 5;
  /** Rolling window for invitation throttling, milliseconds. */
  public static final long INVITATION_LIMIT_WINDOW_MS = 60_000;

  // ── Timing constants (plan defaults) ───────────────────────────
  public static final long TIMEOUT_CAPABILITY_MS   = 10_000;
  public static final long TIMEOUT_RINGING_MS       = 30_000;
  public static final long TIMEOUT_MEDIA_SETUP_MS   = 15_000;
  public static final long HEARTBEAT_INTERVAL_MS    =  5_000;
  public static final long HEARTBEAT_FAIL_MS        = 15_000;
  public static final long ROUTE_RECOVERY_MS        =  3_000;
  public static final long LOCAL_TEARDOWN_TARGET_MS =  2_000;

  // ── Frame limits ───────────────────────────────────────────────
  public static final int MAX_FRAME_BYTES    = 64 * 1024;   // 64 KiB
  public static final int MAX_SDP_BYTES      = 48 * 1024;   // 48 KiB
  public static final int MAX_ICE_CANDIDATES = 128;

  // ── Quality indicator constants ────────────────────────────────
  public enum Quality { Unknown, Normal, Reduced }

  public static final long QUALITY_SAMPLE_INTERVAL_MS = 1000;
  public static final long QUALITY_WARMUP_MS          = 5000;
  public static final int  QUALITY_WINDOW_SECONDS     = 5;
  public static final int  QUALITY_MIN_PACKETS        = 50;

  public static final double LOSS_THRESHOLD_ENTER   = 5.0;
  public static final double LOSS_THRESHOLD_RECOVER  = 2.0;
  public static final double JITTER_THRESHOLD_ENTER  = 30.0;
  public static final double JITTER_THRESHOLD_RECOVER = 20.0;
  public static final double RTT_THRESHOLD_ENTER     = 300.0;
  public static final double RTT_THRESHOLD_RECOVER   = 200.0;
  public static final int    DEGRADED_CONSECUTIVE     = 3;
  public static final long   RECOVERY_DURATION_MS     = 10_000;
  public static final long   STALE_METRIC_MS          = 3_000;

  // ── Frame envelope ─────────────────────────────────────────────
  /** Envelope for a call-signaling frame, serialized as:
   *  4-byte unsigned big-endian length, then UTF-8 JSON body. */
  public static class Frame {
    public int    protocolVersion = 1;
    public String type;
    public String callId;
    public long   senderSequence;
    public long   negotiationGeneration;
    public Map<String,Object> body;

    public Frame() {}

    public Frame(String type, String callId, long seq, long negGen) {
      this.type = type;
      this.callId = callId;
      this.senderSequence = seq;
      this.negotiationGeneration = negGen;
      this.body = new LinkedHashMap<>();
    }

    public Frame copy() {
      Frame f = new Frame(type, callId, senderSequence, negotiationGeneration);
      if (body != null) f.body = new LinkedHashMap<>(body);
      return f;
    }
  }

  // ── Validation helpers ─────────────────────────────────────────
  public static boolean validCallId(String id) {
    try { return id != null && UUID.fromString(id).toString().equals(id); }
    catch (Exception e) { return false; }
  }

  /** Returns the role permitted to have sent an incoming frame of {@code type}, or null if that
   *  frame is not admissible in this state.  Null means "ignore the frame".
   *
   *  <p>This is evaluated on the machine <em>receiving</em> the frame, so {@code state} is the
   *  local state and {@code isCaller} is the <em>local</em> role; the sender therefore holds the
   *  opposite role.
   *
   *  <p>This table used to be written from the sender's point of view, which silently discarded
   *  RINGING, ACCEPT, BUSY and ANSWER on the caller and OFFER on the callee: each of those checks
   *  asked whether the <em>local</em> machine was in the state the <em>remote</em> sender would
   *  have been in.  No call could ever get past ringing, and because the caller simply stops
   *  hearing anything it reported "No answer" after the timeout rather than failing loudly. */
  public static String allowedSender(String type, State state, boolean isCaller) {
    // Role the peer must hold for this frame: the opposite of the local role.
    String remote = isCaller ? "callee" : "caller";
    switch (type) {
      // Caller -> callee: only an idle device can be invited.
      case INVITE:      return state == State.Idle && !isCaller ? "caller" : null;
      // Callee -> caller: the caller's local state while the callee rings, answers or is busy.
      case RINGING:     return state == State.OutgoingRinging && isCaller ? "callee" : null;
      case ACCEPT:      return state == State.OutgoingRinging && isCaller ? "callee" : null;
      case BUSY:        return state == State.OutgoingRinging && isCaller ? "callee" : null;
      // Either side may decline while ringing, including during glare when both are ringing.
      case DECLINE:     return (state == State.IncomingRinging || state == State.OutgoingRinging)
                               ? "both" : null;
      // Caller -> callee: the callee rings before the caller abandons the attempt.
      case CANCEL:      return state == State.IncomingRinging && !isCaller ? "caller" : null;
      // SDP offer travels caller -> callee; answer travels callee -> caller.
      case OFFER:       return state == State.Connecting && !isCaller ? "caller" : null;
      case ANSWER:      return state == State.Connecting && isCaller ? "callee" : null;
      case ICE:         return state.active() ? "both" : null;
      case MEDIA_READY: return state.active() ? "both" : null;
      case HANGUP:      return !state.terminal() ? "both" : null;
      case ERROR:       return state.active() ? "both" : null;
      case PING:        return state.active() ? "both" : null;
      case PONG:        return state.active() ? "both" : null;
      default:          return null;
    }
  }

  // ── State transitions ──────────────────────────────────────────
  /** Returns the next state after processing a message, or null if invalid. */
  public static State transition(State current, String type, boolean isCaller) {
    switch (type) {
      case INVITE:
        return current == State.Idle ? State.IncomingRinging : null;
      case RINGING:
        return current == State.OutgoingRinging ? State.OutgoingRinging : null;
      case ACCEPT:
        return (current == State.OutgoingRinging || current == State.IncomingRinging)
               ? State.Connecting : null;
      case DECLINE:
      case BUSY:
        return (current == State.IncomingRinging || current == State.OutgoingRinging)
               ? State.Ending : null;
      case CANCEL:
        return (current == State.IncomingRinging || current == State.OutgoingRinging)
               ? State.Ending : null;
      case MEDIA_READY:
        return current == State.Connecting ? State.Connected : current;
      case HANGUP:
        return !current.terminal() ? State.Ending : null;
      case ERROR:
        return current.active() ? State.Ending : null;
      default:
        return current; // non-state-changing messages
    }
  }

  // ── Glare resolution ───────────────────────────────────────────
  /** Resolve simultaneous dialing: caller with lexicographically lower
   *  (localId + remoteId) wins. Returns true if local wins. */
  public static boolean glareWinner(String localId, String remoteId,
                                     long localSeq, long remoteSeq) {
    int cmp = (localId + remoteId).compareTo(remoteId + localId);
    return cmp <= 0;
  }

  // ── Invitation limiter ─────────────────────────────────────────
  public static class InvitationLimiter {
    private final Map<String, Deque<Long>> attempts = new HashMap<>();

    /** Record an invitation attempt. Returns true if within limits. */
    public synchronized boolean record(String peerId, long nowMs) {
      Deque<Long> dq = attempts.get(peerId);
      if (dq == null) {
        dq = new ArrayDeque<>();
        attempts.put(peerId, dq);
      }
      // Prune old entries outside the window
      long cutoff = nowMs - INVITATION_LIMIT_WINDOW_MS;
      while (!dq.isEmpty() && dq.peekFirst() < cutoff)
        dq.pollFirst();
      if (dq.size() >= INVITATION_LIMIT_COUNT)
        return false; // exceeded limit
      dq.addLast(nowMs);
      return true;
    }

    /** Clean up stale peer entries. */
    public synchronized void prune(long nowMs) {
      long cutoff = nowMs - INVITATION_LIMIT_WINDOW_MS;
      attempts.entrySet().removeIf(e -> {
        Deque<Long> dq = e.getValue();
        while (!dq.isEmpty() && dq.peekFirst() < cutoff)
          dq.pollFirst();
        return dq.isEmpty();
      });
    }

    public synchronized void clear() { attempts.clear(); }
  }
}