using System.Text.RegularExpressions;

namespace LanMessenger;

// Confirmed A02b v2 syntax boundary. Authorization/roles/generation are enforced separately by
// CallController and CallFrameAdmission; this type only decides whether a frame's *shape* is legal.
// Never apply these rules to v1 -- a v1 frame is validated by nothing beyond the envelope in
// CallSignaling.Parse, because the archived Windows 2.2.42 and Android voice builds emit v1 frames
// that a v2 validator would (correctly) reject.
//
// This is a field-for-field port of android/src/net/lanmsg/chat/CallVideoProtocol.java. If the two
// ever disagree, one of them is a bug: tests/video-contract's shared fixture corpus is consumed by
// BOTH this file and the Java original and asserts identical accept/reject decisions, so a change
// here must be made there in the same commit.
public static class CallVideoProtocol
{
    public const string Calcaps = CallCapabilities.Request;
    public const string Capability = CallCapabilities.Response;
    public const long RequestTimeoutMs = 30_000;
    public const long VideoTimeoutMs = 15_000;
    public const int MaxCandidateBytes = 4096;
    public const int MaxRequests = 128;

    public static bool Capable(string? response, bool verified, bool legacy, long elapsedMs) =>
        CallCapabilities.Supports(response, verified, legacy, elapsedMs);

    public static string? CapabilityResponse(bool legacy) => legacy ? null : Capability;

    static bool Keys(Dictionary<string, object> b, params string[] keys)
    {
        if (b.Count != keys.Length) return false;
        foreach (var k in keys) if (!b.ContainsKey(k)) return false;
        return true;
    }

    // Java accepts Long/Integer/Short/Byte. System.Text.Json only ever produces long or double for
    // a JSON number, and CallSignaling.Parse deliberately keeps a non-integral literal (1.0, 1e3)
    // as double -- exactly like the Java parser -- so "is it an integer on the wire" is answered
    // here rather than being silently coerced away.
    static bool Integral(object? n) => n is long or int or short or byte;

    static long AsLong(object? n) => n switch
    {
        long l => l,
        int i => i,
        short s => s,
        byte b => b,
        _ => 0,
    };

    static bool Text(object? value, int max)
    {
        if (value is not string s || s.Length == 0) return false;
        if (s.IndexOf('\0') >= 0 || s.IndexOf('\r') >= 0 || s.IndexOf('\n') >= 0) return false;
        return System.Text.Encoding.UTF8.GetByteCount(s) <= max;
    }

    static bool Request(object? value) => value is string s && CallProtocol.ValidCallId(s);

    static bool Video(Dictionary<string, object> b) => b.TryGetValue("media", out var m) && m is "video";
    static bool Media(Dictionary<string, object> b) =>
        (b.TryGetValue("media", out var a) && a is "audio") || Video(b);

    static bool MediaKeys(Dictionary<string, object> b, params string[] fields)
    {
        if (!Media(b)) return false;
        var names = new List<string>(fields) { "media" };
        if (Video(b))
        {
            names.Add("request");
            // Read through TryGetValue: a video body with no "request" at all must be REFUSED, not
            // throw out of a validator. The Java original reads b.get("request"), which yields null.
            if (!Request(b.TryGetValue("request", out var bound) ? bound : null)) return false;
        }
        return Keys(b, names.ToArray());
    }

    public static bool Valid(CallProtocol.Frame? f)
    {
        if (f == null || f.V != 2 || !CallProtocol.ValidCallId(f.Cid)) return false;
        if (f.Seq <= 0 || f.Gen < 0 || f.B == null || f.T.Length == 0) return false;
        var b = f.B;
        long gen = f.Gen;
        switch (f.T)
        {
            case CallProtocol.INVITE:
                return gen == 0 && Keys(b, "caller", "callee", "media") && Media(b)
                    && Request(b["caller"]) && Request(b["callee"]);
            case CallProtocol.ACCEPT:
                return gen == 0 && Keys(b, "media") && Media(b);
            case CallProtocol.VIDEO_REQUEST:
            case CallProtocol.VIDEO_ACCEPT:
            case CallProtocol.VIDEO_DECLINE:
                return gen == 0 && Keys(b, "request") && Request(b["request"]);
            case CallProtocol.OFFER:
            case CallProtocol.ANSWER:
                return MediaKeys(b, "sdp") && Generation(b, gen) && ValidSdp(b["sdp"], Video(b));
            case CallProtocol.ICE:
                return MediaKeys(b, "candidate", "sdpMid", "sdpMLineIndex") && Generation(b, gen)
                    && Text(b["candidate"], MaxCandidateBytes)
                    && ((string)b["candidate"]).StartsWith("candidate:", StringComparison.Ordinal)
                    && Text(b["sdpMid"], 64) && Integral(b["sdpMLineIndex"])
                    && AsLong(b["sdpMLineIndex"]) == 0;
            case CallProtocol.VIDEO_STATE:
                return gen >= 2 && Keys(b, "request", "camera", "revision") && Request(b["request"])
                    && b["camera"] is bool && Integral(b["revision"]) && AsLong(b["revision"]) >= 0;
            case CallProtocol.MEDIA_READY:
                return MediaKeys(b) && Generation(b, gen);
            case CallProtocol.ERROR:
                return MediaKeys(b, "code") && Generation(b, gen)
                    && b["code"] is "failed" or "timeout" or "unsupported";
            case CallProtocol.RINGING:
            case CallProtocol.DECLINE:
            case CallProtocol.BUSY:
            case CallProtocol.CANCEL:
            case CallProtocol.HANGUP:
            case CallProtocol.PING:
            case CallProtocol.PONG:
                return gen == 0 && b.Count == 0;
            case CallProtocol.REMOTE_SPEAKER:
                return gen == 0 && Keys(b, "speaker") && b["speaker"] is bool;
            case CallProtocol.REMOTE_CAMERA:
                return gen >= 2 && Keys(b, "request", "camera", "facing") && Request(b["request"])
                    && b["camera"] is bool && b["facing"] is "front" or "rear" or "keep";
            default:
                return false;
        }
    }

    static bool Generation(Dictionary<string, object> b, long gen) => Video(b) ? gen >= 2 : gen == 1;

    // Ported line-for-line from CallVideoProtocol.validSdp. The checks that matter for interop:
    // exactly one m-line, of the right media kind, VP8/90000 for video and G722/8000 for audio, a
    // sha-256 fingerprint, and UDP/TLS/RTP/SAVPF. A native peer that fails any of these must be
    // refused rather than handed to a media stack that would negotiate something else.
    public static bool ValidSdp(object? value, bool video)
    {
        if (value is not string sdp) return false;
        if (!sdp.StartsWith("v=0\r\n", StringComparison.Ordinal)) return false;
        if (sdp.IndexOf('\0') >= 0) return false;
        if (System.Text.Encoding.UTF8.GetByteCount(sdp) > CallProtocol.MaxSdpBytes) return false;

        string[]? section = null;
        bool fingerprint = false, codec = false;
        int mLines = 0;
        foreach (var raw in sdp.Split("\r\n", StringSplitOptions.None))
        {
            // Split on CRLF can leave a lone CR or LF behind when the peer used a mixed terminator.
            if (raw.IndexOf('\r') >= 0 || raw.IndexOf('\n') >= 0) return false;
            if (raw.StartsWith("m=", StringComparison.Ordinal))
            {
                mLines++;
                section = raw.Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
                if (section.Length < 4) return false;
                if (section[0] != (video ? "m=video" : "m=audio")) return false;
                if (!RegexPort(section[1])) return false;
                if (int.Parse(section[1]) > 65535) return false;
                // Both SAVP and SAVPF are accepted, and only those two. `SAVPF` is the extended
                // feedback profile and `SAVP` is the plain secure profile; they differ by one flag
                // in the same ICE/DTLS transport, and real stacks disagree about which to emit --
                // SIPSorcery 10.0.17 (the Windows adapter) writes `SAVP`, libwebrtc writes `SAVPF`.
                // Refusing the other one here broke every real call with EndReason.MediaError, which
                // is exactly the failure this clause previously caused. TCP is still refused on
                // purpose: it is a different transport, not a different profile, and this codebase
                // does not implement DTLS over TCP.
                if (section[2] is not ("UDP/TLS/RTP/SAVPF" or "UDP/TLS/RTP/SAVP")) return false;
                for (int i = 3; i < section.Length; i++)
                    if (!RegexPayload(section[i]) || int.Parse(section[i]) > 127) return false;
            }
            if (raw.StartsWith("a=fingerprint:", StringComparison.Ordinal))
            {
                if (!Regex.IsMatch(raw, "^a=fingerprint:sha-256 [a-fA-F0-9]{2}(?::[a-fA-F0-9]{2}){31}$")) return false;
                fingerprint = true;
            }
            if (raw.StartsWith("a=rtpmap:", StringComparison.Ordinal))
            {
                var mapping = raw.Substring(9).Split(new[] { ' ' }, StringSplitOptions.RemoveEmptyEntries);
                if (mapping.Length != 2 || section == null) return false;
                bool offered = false;
                for (int i = 3; i < section.Length; i++) if (mapping[0] == section[i]) { offered = true; break; }
                if (!offered) return false;
                if (mapping[1] == (video ? "VP8/90000" : "G722/8000")) codec = true;
                if (video && mapping[1] is not ("VP8/90000" or "rtx/90000" or "red/90000" or "ulpfec/90000")) return false;
            }
        }
        return mLines == 1 && fingerprint && codec;
    }

    static bool RegexPort(string s) =>
        s.Length is >= 1 and <= 5 && s[0] is >= '1' and <= '9'
        && s.All(c => c is >= '0' and <= '9');

    static bool RegexPayload(string s) =>
        s.Length is >= 1 and <= 3 && s.All(c => c is >= '0' and <= '9');
}
