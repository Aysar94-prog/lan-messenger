package net.lanmsg.chat;

import java.util.*;

/** Tracks the state of one call session.  Snapshots are immutable copies
 *  safe for UI threads; the controller owns mutable state under its lock. */
public class CallSession {

  public final String callId;
  public final String peerId;
  public final boolean isCaller;
  public final long createdAtMs;

  // Immutable snapshot fields
  public final CallProtocol.State state;
  public final CallProtocol.EndReason endReason;
  public final boolean muted;
  public final String audioRoute;        // "System", "Phone", "Speaker", null=unknown
  public final CallProtocol.Quality quality;
  public final long durationMs;
  /** When the call actually connected, or 0 while it has not.  The call clock runs from here, not
   *  from {@link #createdAtMs}: a caller watching "0:35" after twenty seconds of ringing has been
   *  told the call has been up for thirty-five seconds, which is not true and is exactly what a
   *  phone caller's own handset would never do. */
  public final long connectedAtMs;

  CallSession(Builder b) {
    this.callId = b.callId;
    this.peerId = b.peerId;
    this.isCaller = b.isCaller;
    this.createdAtMs = b.createdAtMs;
    this.state = b.state;
    this.endReason = b.endReason;
    this.muted = b.muted;
    this.audioRoute = b.audioRoute;
    this.quality = b.quality;
    this.durationMs = b.durationMs;
    this.connectedAtMs = b.connectedAtMs;
  }

  /** How long the call has been connected (0 unless Connected).
   *
   *  <p>This used to add the time since the call was <em>created</em> to the last refreshed
   *  duration, so a call answered four seconds after it rang was already showing its fourth
   *  second and a call that rang out counted toward the conversation.  The clock is now measured
   *  from the moment the call connected, and the stored duration is only used as a floor so a
   *  snapshot taken before {@code connectedAtMs} was set still reports the time it had. */
  public long elapsedMs() {
    if (state != CallProtocol.State.Connected) return 0;
    long sinceConnect = connectedAtMs > 0
      ? Math.max(0, System.currentTimeMillis() - connectedAtMs) : 0;
    return Math.max(durationMs, sinceConnect);
  }

  // ── Builder for mutable state held by controller ───────────────

  static class Builder {
    String callId, peerId;
    boolean isCaller;
    long createdAtMs;
    CallProtocol.State state = CallProtocol.State.Idle;
    CallProtocol.EndReason endReason;
    boolean muted;
    String audioRoute;
    CallProtocol.Quality quality = CallProtocol.Quality.Unknown;
    long durationMs;
    long connectedAtMs;

    Builder(String callId, String peerId, boolean isCaller, long nowMs) {
      this.callId = callId;
      this.peerId = peerId;
      this.isCaller = isCaller;
      this.createdAtMs = nowMs;
    }

    CallSession snapshot() {
      return new CallSession(this);
    }

    CallSession snapshotWithDuration() {
      if (state == CallProtocol.State.Connected && connectedAtMs > 0) {
        durationMs = System.currentTimeMillis() - connectedAtMs;
      }
      return new CallSession(this);
    }
  }

  // ── Serialization for snapshots (tests/harness) ────────────────

  @Override public String toString() {
    return String.format(Locale.ROOT,
      "CallSession{callId=%s peer=%s state=%s caller=%s muted=%s route=%s quality=%s dur=%d}",
      callId, peerId, state, isCaller, muted, audioRoute, quality,
      state == CallProtocol.State.Connected ? elapsedMs() : 0);
  }
}