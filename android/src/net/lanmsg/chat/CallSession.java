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
  }

  /** Current elapsed duration (0 if not connected). */
  public long elapsedMs() {
    return state == CallProtocol.State.Connected ? durationMs +
           Math.max(0, System.currentTimeMillis() - createdAtMs) : 0;
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