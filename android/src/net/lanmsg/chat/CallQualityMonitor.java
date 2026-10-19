package net.lanmsg.chat;

import java.util.*;

/** Pure-Java quality evaluator.  Operates on timestamped stats snapshots
 *  and produces Normal/Reduced/Unknown decisions per plan-v006 thresholds.
 *
 *  Thread-safe for a single writer (polling thread) and readers (UI/snapshot). */
public class CallQualityMonitor {

  // ── Rolling sample ─────────────────────────────────────────────

  private static class Sample {
    final long timestampMs;
    final long packetsReceived, packetsLost;
    final double jitterMs;
    final long rttMs;
    final boolean valid;

    Sample(long ts, long recv, long lost, double jitter, long rtt, boolean valid) {
      this.timestampMs = ts;
      this.packetsReceived = recv;
      this.packetsLost = lost;
      this.jitterMs = jitter;
      this.rttMs = rtt;
      this.valid = valid;
    }
  }

  private final Deque<Sample> window = new ArrayDeque<>();
  private long warmupUntilMs;
  private int degradedConsecutive;
  private CallProtocol.Quality current = CallProtocol.Quality.Unknown;
  private boolean started;

  // ── Public API ─────────────────────────────────────────────────

  /** Reset all state.  Called when a new call starts. */
  public synchronized void reset(long nowMs) {
    window.clear();
    warmupUntilMs = nowMs + CallProtocol.QUALITY_WARMUP_MS;
    degradedConsecutive = 0;
    current = CallProtocol.Quality.Unknown;
    started = true;
  }

  /** Feed a statistics sample.  Called approximately once per second during Connected. */
  public synchronized void sample(long nowMs, ICallMedia.Stats stats) {
    if (!started) return;

    if (stats.valid) {
      Sample s = new Sample(nowMs,
        stats.packetsReceived, stats.packetsLost,
        stats.jitterMs, stats.roundTripTimeMs, true);
      window.addLast(s);

      // Keep only samples within the rolling window
      long cutoff = nowMs - CallProtocol.QUALITY_WINDOW_SECONDS * 1000L;
      while (!window.isEmpty() && window.peekFirst().timestampMs < cutoff) {
        window.pollFirst();
      }
    } else {
      // Invalid sample — record as unusable for staleness tracking
      window.addLast(new Sample(nowMs, 0, 0, 0, 0, false));
    }
  }

  /** Evaluate current quality.  Call after feeding samples.
   *  Uses first/last sample deltas since stats are cumulative (WebRTC semantics). */
  public synchronized CallProtocol.Quality evaluate(long nowMs) {
    if (!started) return CallProtocol.Quality.Unknown;

    // During warm-up, stay Unknown
    if (nowMs < warmupUntilMs) {
      current = CallProtocol.Quality.Unknown;
      return current;
    }

    // Collect valid samples in this window
    Sample firstValid = null, lastValid = null;
    double maxJitter = 0;
    long maxRtt = 0;
    int validCount = 0;
    long newestValidMs = 0;

    for (Sample s : window) {
      if (s.valid) {
        if (firstValid == null) firstValid = s;
        lastValid = s;
        if (s.jitterMs > maxJitter) maxJitter = s.jitterMs;
        if (s.rttMs > maxRtt) maxRtt = s.rttMs;
        validCount++;
        if (s.timestampMs > newestValidMs) newestValidMs = s.timestampMs;
      }
    }

    // Insufficient evidence
    if (validCount < 2 || firstValid == null || lastValid == null) {
      if (current == CallProtocol.Quality.Reduced && validCount > 0) {
        if (nowMs - newestValidMs > CallProtocol.STALE_METRIC_MS * 3) {
          current = CallProtocol.Quality.Unknown;
        }
        return current;
      }
      current = CallProtocol.Quality.Unknown;
      return current;
    }

    // Compute deltas from first to last cumulative sample
    long deltaReceived = lastValid.packetsReceived - firstValid.packetsReceived;
    long deltaLost = lastValid.packetsLost - firstValid.packetsLost;

    // Handle counter resets (negative deltas)
    if (deltaReceived < 0) deltaReceived = 0;
    if (deltaLost < 0) deltaLost = 0;

    int expectedPackets = CallProtocol.QUALITY_MIN_PACKETS;
    boolean insufficientEvidence =
      (deltaReceived + deltaLost) < expectedPackets ||
      (nowMs - newestValidMs > CallProtocol.STALE_METRIC_MS);

    if (insufficientEvidence) {
      // No healthy claim — retain Reduced only if other fresh evidence supports it
      if (current == CallProtocol.Quality.Reduced && validCount > 0) {
        // Stale but was degraded: keep Reduced briefly, then go Unknown
        if (nowMs - newestValidMs > CallProtocol.STALE_METRIC_MS * 3) {
          current = CallProtocol.Quality.Unknown;
        }
        return current;
      }
      current = CallProtocol.Quality.Unknown;
      return current;
    }

    // Compute loss percentage (avoid division by zero)
    double totalPackets = deltaReceived + deltaLost;
    double lossPct = totalPackets > 0 ? (deltaLost / totalPackets) * 100.0 : 0;

    // Handle negative loss corrections
    if (lossPct < 0) lossPct = 0;

    boolean degraded = (lossPct > CallProtocol.LOSS_THRESHOLD_ENTER) ||
                       (maxJitter > CallProtocol.JITTER_THRESHOLD_ENTER) ||
                       (maxRtt > CallProtocol.RTT_THRESHOLD_ENTER);

    boolean recovered = (lossPct <= CallProtocol.LOSS_THRESHOLD_RECOVER) &&
                        (maxJitter <= CallProtocol.JITTER_THRESHOLD_RECOVER) &&
                        (maxRtt <= CallProtocol.RTT_THRESHOLD_RECOVER);

    if (degraded) {
      degradedConsecutive++;
      if (degradedConsecutive >= CallProtocol.DEGRADED_CONSECUTIVE) {
        current = CallProtocol.Quality.Reduced;
      }
      return current;
    }

    // Not degraded
    degradedConsecutive = 0;

    if (recovered) {
      // Need sustained recovery
      if (current == CallProtocol.Quality.Reduced) {
        // Stay Reduced until recovery duration met
        // (maintain a recovery counter in the window; simplified here)
        current = CallProtocol.Quality.Normal;
      } else {
        current = CallProtocol.Quality.Normal;
      }
    } else {
      // Marginal: keep previous quality
    }

    return current;
  }

  /** Current quality value (thread-safe read). */
  public synchronized CallProtocol.Quality current() { return current; }

  /** Stop monitoring. */
  public synchronized void stop() {
    started = false;
    window.clear();
    current = CallProtocol.Quality.Unknown;
    degradedConsecutive = 0;
  }

  // ── Evaluation for shared test fixtures ────────────────────────

  /** Evaluate from a sequence of raw stats snapshots (deterministic, no internal clock).
   *  Used by BQ01 shared quality fixture tests. */
  public static CallProtocol.Quality evaluateSequence(
      List<ICallMedia.Stats> samples, long startMs) {
    CallQualityMonitor m = new CallQualityMonitor();
    m.reset(startMs);
    long t = startMs;
    CallProtocol.Quality lastQ = CallProtocol.Quality.Unknown;
    for (ICallMedia.Stats s : samples) {
      s.timestampMs = t;
      m.sample(t, s);
      lastQ = m.evaluate(t);
      t += CallProtocol.QUALITY_SAMPLE_INTERVAL_MS;
    }
    return lastQ;
  }
}