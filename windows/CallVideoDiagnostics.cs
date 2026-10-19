using System.Globalization;

namespace LanMessenger;

// Allowlisted video measurements. There is deliberately no field here for a signaling message, a
// peer id, a frame, an SDP fragment or a log line -- this snapshot is what can be shown on screen and
// pasted into a support report, and nothing else is allowed to reach either.
//
// Ported from android/src/net/lanmsg/chat/CallVideoDiagnostics.java. Every metric is range-checked on
// construction, so an adapter that reports nonsense yields "unavailable" rather than a plausible
// looking lie.
public static class CallVideoDiagnostics
{
    public sealed class Snapshot
    {
        public string? Codec { get; }
        public double? SentFps { get; }
        public double? ReceivedFps { get; }
        public double? SentKbps { get; }
        public double? ReceivedKbps { get; }
        public double? LossPercent { get; }
        public long MeasuredAtNanos { get; }

        public Snapshot(string? codec, double? sentFps, double? receivedFps, double? sentKbps,
            double? receivedKbps, double? lossPercent, long measuredAtNanos)
        {
            Codec = codec == "VP8" ? codec : null;
            SentFps = Metric(sentFps, 1000);
            ReceivedFps = Metric(receivedFps, 1000);
            SentKbps = Metric(sentKbps, 1_000_000);
            ReceivedKbps = Metric(receivedKbps, 1_000_000);
            LossPercent = Metric(lossPercent, 100);
            MeasuredAtNanos = measuredAtNanos;
        }

        static double? Metric(double? value, double maximum) =>
            value is { } v && !double.IsNaN(v) && !double.IsInfinity(v) && v >= 0 && v <= maximum ? v : null;

        public static Snapshot Unavailable() => new(null, null, null, null, null, null, 0);

        // Anything older than three seconds is presented as unavailable: a stale number on a video
        // panel is worse than no number, because it looks current.
        public Snapshot Fresh(long nowNanos) =>
            MeasuredAtNanos != 0 && nowNanos >= MeasuredAtNanos && nowNanos - MeasuredAtNanos <= 3_000_000_000L
                ? this : Unavailable();
    }

    /// The adapter normalizes exactly one inbound/outbound video RTP stream into this.
    public sealed class Counters
    {
        public long TimestampUs;
        public string? Codec;
        public long? SentFrames, ReceivedFrames, SentBytes, ReceivedBytes, ReceivedPackets, LostPackets;
    }

    public sealed class Sampler
    {
        Counters? previous;

        static double? Rate(long? now, long? old, double seconds, double scale) =>
            now is { } n && old is { } o && n >= 0 && o >= 0 && n >= o ? (n - o) / seconds * scale : null;

        public Snapshot Sample(Counters? value, long nowNanos)
        {
            lock (this)
            {
                if (value == null) { previous = null; return Snapshot.Unavailable(); }
                var old = previous; previous = Copy(value);
                // The first sample has no interval to compute a rate over. That is exactly the
                // `seconds < 0.25` branch below, hoisted so `old` is non-null from here on.
                if (old == null) return new Snapshot(value.Codec, null, null, null, null, null, nowNanos);
                double seconds = (value.TimestampUs - old.TimestampUs) / 1_000_000.0;
                // Below a quarter of a second the delta is dominated by sampling jitter; above five
                // seconds the adapter has clearly been paused. Either way: report the codec alone.
                if (seconds < 0.25 || seconds > 5)
                    return new Snapshot(value.Codec, null, null, null, null, null, nowNanos);

                double? loss = null;
                if (value.ReceivedPackets is { } rp && old.ReceivedPackets is { } orp
                    && value.LostPackets is { } lp && old.LostPackets is { } olp
                    && orp >= 0 && olp >= 0 && rp >= orp && lp >= olp)
                {
                    double received = rp - orp, lost = lp - olp;
                    if (received + lost > 0) loss = 100 * lost / (received + lost);
                }
                return new Snapshot(value.Codec,
                    Rate(value.SentFrames, old.SentFrames, seconds, 1),
                    Rate(value.ReceivedFrames, old.ReceivedFrames, seconds, 1),
                    Rate(value.SentBytes, old.SentBytes, seconds, 0.008),
                    Rate(value.ReceivedBytes, old.ReceivedBytes, seconds, 0.008),
                    loss, nowNanos);
            }
        }

        static Counters Copy(Counters input) => new()
        {
            TimestampUs = input.TimestampUs,
            Codec = input.Codec,
            SentFrames = input.SentFrames,
            ReceivedFrames = input.ReceivedFrames,
            SentBytes = input.SentBytes,
            ReceivedBytes = input.ReceivedBytes,
            ReceivedPackets = input.ReceivedPackets,
            LostPackets = input.LostPackets,
        };

        public void Reset() { lock (this) previous = null; }
    }

    static string Metric(double? value, string unit) =>
        value == null ? "Unavailable" : string.Format(CultureInfo.InvariantCulture, "{0:0.0} {1}", value, unit);

    public static string Display(Snapshot? s)
    {
        s ??= Snapshot.Unavailable();
        return "Codec: " + (s.Codec ?? "Unavailable")
            + "\nSend: " + Metric(s.SentFps, "fps") + " / " + Metric(s.SentKbps, "kbps")
            + "\nReceive: " + Metric(s.ReceivedFps, "fps") + " / " + Metric(s.ReceivedKbps, "kbps")
            + "\nRecent receive loss: " + Metric(s.LossPercent, "%");
    }

    /// The version is constrained to a dotted numeric pattern rather than interpolated from arbitrary
    /// build text, which on some build systems carries a machine name, a branch or a key fragment.
    public static string Report(Snapshot? s, string? version)
    {
        bool safe = version != null && System.Text.RegularExpressions.Regex.IsMatch(
            version, "^[0-9]{1,4}(?:\\.[0-9]{1,4}){1,3}$");
        return "LAN Messenger Windows " + (safe ? version : "Unavailable") + "\n" + Display(s)
            + "\nClipboard text remains until replaced or cleared.";
    }
}
