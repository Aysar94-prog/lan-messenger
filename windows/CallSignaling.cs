using System.Text.Json;
using System.Text.Json.Nodes;

namespace LanMessenger;

// Serializes/parses call-signaling frames: 4-byte big-endian length + UTF-8 JSON.
// Root keys v/t/cid/seq/gen/b and body field names (caller/callee/sdp/candidate/sdpMid/
// sdpMLineIndex) match android/src/net/lanmsg/chat/CallSignaling.java exactly -- this is the
// actual interop contract, not just a similar shape.
public static class CallSignaling
{
    public static byte[] Serialize(CallProtocol.Frame frame)
    {
        var root = new JsonObject
        {
            ["v"] = frame.V,
            ["t"] = frame.T,
            ["cid"] = frame.Cid,
            ["seq"] = frame.Seq,
            ["gen"] = frame.Gen,
        };
        if (frame.B is { Count: > 0 })
        {
            var b = new JsonObject();
            foreach (var (k, v) in frame.B) b[k] = v switch
            {
                string s => JsonValue.Create(s),
                int i => JsonValue.Create(i),
                long l => JsonValue.Create(l),
                bool bo => JsonValue.Create(bo),
                null => null,
                _ => JsonValue.Create(v.ToString()),
            };
            root["b"] = b;
        }
        var json = root.ToJsonString();
        var utf8 = System.Text.Encoding.UTF8.GetBytes(json);
        if (utf8.Length > CallProtocol.MaxFrameBytes)
            throw new InvalidOperationException($"Frame exceeds {CallProtocol.MaxFrameBytes} bytes");
        var wire = new byte[4 + utf8.Length];
        wire[0] = (byte)(utf8.Length >> 24); wire[1] = (byte)(utf8.Length >> 16);
        wire[2] = (byte)(utf8.Length >> 8); wire[3] = (byte)utf8.Length;
        Buffer.BlockCopy(utf8, 0, wire, 4, utf8.Length);
        return wire;
    }

    public static CallProtocol.Frame? Parse(byte[] wire)
    {
        if (wire.Length < 4) return null;
        int length = (wire[0] << 24) | (wire[1] << 16) | (wire[2] << 8) | wire[3];
        if (length <= 0 || length > CallProtocol.MaxFrameBytes || 4 + length != wire.Length) return null;
        try
        {
            var json = System.Text.Encoding.UTF8.GetString(wire, 4, length);
            var root = JsonNode.Parse(json)?.AsObject();
            if (root == null) return null;
            var f = new CallProtocol.Frame
            {
                V = root["v"]?.GetValue<int>() ?? 1,
                T = root["t"]?.GetValue<string>() ?? "",
                Cid = root["cid"]?.GetValue<string>() ?? "",
                Seq = root["seq"]?.GetValue<long>() ?? 0,
                Gen = root["gen"]?.GetValue<long>() ?? 0,
            };
            if (root["b"]?.AsObject() is { } b)
            {
                f.B = new Dictionary<string, object>();
                foreach (var (k, v) in b)
                {
                    if (v == null) continue;
                    f.B[k] = v.GetValueKind() switch
                    {
                        JsonValueKind.Number => v.AsValue().TryGetValue<long>(out var l) ? l : v.GetValue<double>(),
                        JsonValueKind.True or JsonValueKind.False => v.GetValue<bool>(),
                        _ => v.ToString(),
                    };
                }
            }
            if (f.T.Length == 0 || !CallProtocol.ValidCallId(f.Cid)) return null;
            return f;
        }
        catch { return null; }
    }

    static CallProtocol.Frame Make(string type, string callId, long seq, long gen = 0) =>
        new() { T = type, Cid = callId, Seq = seq, Gen = gen };

    public static CallProtocol.Frame Invite(string callId, long seq, string callerId, string calleeId)
    {
        var f = Make(CallProtocol.INVITE, callId, seq);
        f.B = new() { ["caller"] = callerId, ["callee"] = calleeId };
        return f;
    }
    public static CallProtocol.Frame Ringing(string callId, long seq) => Make(CallProtocol.RINGING, callId, seq);
    public static CallProtocol.Frame Accept(string callId, long seq) => Make(CallProtocol.ACCEPT, callId, seq);
    public static CallProtocol.Frame Decline(string callId, long seq) => Make(CallProtocol.DECLINE, callId, seq);
    public static CallProtocol.Frame Busy(string callId, long seq) => Make(CallProtocol.BUSY, callId, seq);
    public static CallProtocol.Frame Cancel(string callId, long seq) => Make(CallProtocol.CANCEL, callId, seq);

    public static CallProtocol.Frame Offer(string callId, long seq, long gen, string sdp)
    {
        var f = Make(CallProtocol.OFFER, callId, seq, gen);
        f.B = new() { ["sdp"] = sdp };
        return f;
    }
    public static CallProtocol.Frame Answer(string callId, long seq, long gen, string sdp)
    {
        var f = Make(CallProtocol.ANSWER, callId, seq, gen);
        f.B = new() { ["sdp"] = sdp };
        return f;
    }
    public static CallProtocol.Frame Ice(string callId, long seq, long gen, string candidate, string sdpMid, int sdpMLineIndex)
    {
        var f = Make(CallProtocol.ICE, callId, seq, gen);
        f.B = new() { ["candidate"] = candidate, ["sdpMid"] = sdpMid, ["sdpMLineIndex"] = sdpMLineIndex };
        return f;
    }
    public static CallProtocol.Frame MediaReady(string callId, long seq, long gen) => Make(CallProtocol.MEDIA_READY, callId, seq, gen);
    public static CallProtocol.Frame Hangup(string callId, long seq) => Make(CallProtocol.HANGUP, callId, seq);
    public static CallProtocol.Frame Ping(string callId, long seq) => Make(CallProtocol.PING, callId, seq);
    public static CallProtocol.Frame Pong(string callId, long seq) => Make(CallProtocol.PONG, callId, seq);

    public static string? GetSdp(CallProtocol.Frame f) => f.B != null && f.B.TryGetValue("sdp", out var v) ? v as string : null;
    public static string? GetCandidate(CallProtocol.Frame f) => f.B != null && f.B.TryGetValue("candidate", out var v) ? v as string : null;
    public static string? GetSdpMid(CallProtocol.Frame f) => f.B != null && f.B.TryGetValue("sdpMid", out var v) ? v as string : null;
    public static int GetSdpMLineIndex(CallProtocol.Frame f) => f.B != null && f.B.TryGetValue("sdpMLineIndex", out var v) ? Convert.ToInt32(v) : 0;
}
