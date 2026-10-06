using System.Text;
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
                double d => JsonValue.Create(d),
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

    // The Java parser uses a strict UTF-8 decoder that REPORTS malformed input. The default
    // .NET decoder silently substitutes U+FFFD instead, which would let a peer smuggle a byte
    // sequence through that the other end cannot even represent -- the two ends would then disagree
    // about the text of, say, a call id. Malformed UTF-8 must be a parse failure.
    static readonly System.Text.Encoding StrictUtf8 = new UTF8Encoding(false, throwOnInvalidBytes: true);

    // Android's parser keeps a JSON number as a Java Long when the literal has no '.'/'e'/'E' and as a
    // Double otherwise, and the v2 grammar then asks "was this an integer on the wire?". That
    // distinction has to survive parsing, so it is decided from the raw literal text rather than by
    // trying to read the number back as an integer.
    static bool IsIntegralLiteral(string raw) => raw.IndexOfAny(['.', 'e', 'E']) < 0;

    static JsonElement? Member(JsonElement root, string name)
    {
        foreach (var p in root.EnumerateObject()) if (p.Name == name) return p.Value;
        return null;
    }

    static bool IntegralNumber(JsonElement? e) =>
        e is { ValueKind: JsonValueKind.Number } && IsIntegralLiteral(e.Value.GetRawText());

    static bool ExactKeys(List<string> names, params string[] expected)
    {
        if (names.Count != expected.Length) return false;
        for (int i = 0; i < expected.Length; i++) if (names[i] != expected[i]) return false;
        return true;
    }

    // System.Text.Json tolerates a repeated property and keeps the last one; CallSignaling.java
    // rejects the frame outright. A frame carrying two "cid" values is something no honest peer
    // emits, and resolving it silently would leave the two ends disagreeing about which value the
    // sender actually meant.
    static bool HasDuplicateKeys(JsonElement root)
    {
        var pending = new Stack<JsonElement>();
        pending.Push(root);
        while (pending.Count > 0)
        {
            var element = pending.Pop();
            if (element.ValueKind == JsonValueKind.Object)
            {
                var seen = new HashSet<string>(StringComparer.Ordinal);
                foreach (var property in element.EnumerateObject())
                {
                    if (!seen.Add(property.Name)) return true;
                    if (property.Value.ValueKind is JsonValueKind.Object or JsonValueKind.Array)
                        pending.Push(property.Value);
                }
            }
            else if (element.ValueKind == JsonValueKind.Array)
            {
                foreach (var item in element.EnumerateArray())
                    if (item.ValueKind is JsonValueKind.Object or JsonValueKind.Array) pending.Push(item);
            }
        }
        return false;
    }

    public static CallProtocol.Frame? Parse(byte[] wire)
    {
        if (wire.Length < 4) return null;
        int length = (wire[0] << 24) | (wire[1] << 16) | (wire[2] << 8) | wire[3];
        if (length < 0 || length > CallProtocol.MaxFrameBytes || 4 + length != wire.Length) return null;
        try
        {
            var json = StrictUtf8.GetString(wire, 4, length);
            // Depth cap matches the Java parser's own limit, so a nesting bomb is refused by both
            // ends rather than exhausting one stack and not the other.
            using var document = JsonDocument.Parse(json, new JsonDocumentOptions
            {
                MaxDepth = 16,
                AllowTrailingCommas = false,
                CommentHandling = JsonCommentHandling.Disallow,
            });
            var root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object) return null;
            if (HasDuplicateKeys(root)) return null;

            var names = new List<string>();
            foreach (var p in root.EnumerateObject()) names.Add(p.Name);

            // v2 is a closed envelope: exactly the five or six root keys, with seq/gen written as
            // true integers. Anything else is refused here rather than being handed to a validator
            // that would have to guess what an unrecognised field was for. v1 frames are deliberately
            // NOT subject to this: the archived Windows 2.2.42 and Android voice builds emit v1
            // frames, and the historical grammar must keep parsing exactly as it always has.
            var versionLiteral = Member(root, "v");
            bool versionIsTwo = IntegralNumber(versionLiteral) && versionLiteral!.Value.GetInt64() == 2;
            if (versionIsTwo)
            {
                if (!ExactKeys(names, "v", "t", "cid", "seq", "gen")
                    && !ExactKeys(names, "v", "t", "cid", "seq", "gen", "b")) return null;
                if (!IntegralNumber(Member(root, "seq")) || !IntegralNumber(Member(root, "gen"))) return null;
            }

            // A non-numeric "v" is not v2; it is not v2-shaped, so it falls back to the v1 reading
            // rather than being rejected -- exactly what the Java `instanceof Number` test does.
            double rawVersion = versionLiteral is { ValueKind: JsonValueKind.Number }
                ? versionLiteral.Value.GetDouble() : 1;
            int version = rawVersion >= int.MaxValue ? int.MaxValue
                : rawVersion <= int.MinValue ? int.MinValue : (int)rawVersion;
            // ...but a value that *reads* as 2 while not being the integer literal 2 (2.0, "2") is a
            // contradiction, not a v1 frame, and must be refused.
            if (version == 2 && !versionIsTwo) return null;

            var typeElement = Member(root, "t");
            var cidElement = Member(root, "cid");
            if (typeElement is not { ValueKind: JsonValueKind.String }) return null;
            if (cidElement is not { ValueKind: JsonValueKind.String }) return null;

            var frame = new CallProtocol.Frame
            {
                V = version,
                T = typeElement.Value.GetString() ?? "",
                Cid = cidElement.Value.GetString() ?? "",
                Seq = NumberOrZero(Member(root, "seq")),
                Gen = NumberOrZero(Member(root, "gen")),
                B = new Dictionary<string, object>(),
            };
            if (Member(root, "b") is JsonElement body)
            {
                // Java casts to Map here, so a non-object body throws and rejects the frame.
                if (body.ValueKind != JsonValueKind.Object) return null;
                foreach (var p in body.EnumerateObject())
                    frame.B[p.Name] = BodyValue(p.Value)!;
            }
            if (!CallProtocol.ValidCallId(frame.Cid)) return null;
            return frame;
        }
        catch { return null; }
    }

    static long NumberOrZero(JsonElement? e)
    {
        if (e is not { ValueKind: JsonValueKind.Number }) return 0;
        double d = e.Value.GetDouble();
        return d >= long.MaxValue ? long.MaxValue : d <= long.MinValue ? long.MinValue : (long)d;
    }

    static object? BodyValue(JsonElement e) => e.ValueKind switch
    {
        JsonValueKind.Null => null,
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        JsonValueKind.String => e.GetString() ?? "",
        JsonValueKind.Number => Number(e),
        // v2 admits no arrays or nested objects in a body; keep them as their raw text so the exact
        // key-set and type checks in CallVideoProtocol reject the frame instead of crashing here.
        _ => e.GetRawText(),
    };

    // The result is boxed as `object` on purpose. A conditional expression unifying `long` and
    // `double` is itself a double, which would silently turn every integral literal into a Double and
    // make CallVideoProtocol reject "seq"-style fields as non-integers -- the exact distinction the
    // v2 grammar is built on.
    static object Number(JsonElement e) =>
        IsIntegralLiteral(e.GetRawText()) && e.TryGetInt64(out var integral) ? (object)integral : e.GetDouble();

    static CallProtocol.Frame Make(string type, string callId, long seq, long gen = 0) =>
        new() { T = type, Cid = callId, Seq = seq, Gen = gen };

    public static CallProtocol.Frame Invite(string callId, long seq, string callerId, string calleeId)
    {
        var f = Make(CallProtocol.INVITE, callId, seq);
        f.B = new() { ["caller"] = callerId, ["callee"] = calleeId };
        return f;
    }
    public static CallProtocol.Frame Ringing(string callId, long seq) => Make(CallProtocol.RINGING, callId, seq);
    // The body is allocated HERE, not in Make. An ACCEPT is the one builder whose caller always
    // writes into the body: on a v2 call the controller adds "media" to it (CallController.cs:222
    // and :783). Make leaves B null for the bodyless builders on purpose, and an ACCEPT built that
    // way made accept.B!["media"] throw a NullReferenceException after the session had already
    // moved to Connecting -- which is why an incoming Android call could be neither answered nor
    // ended. Android is not exposed to this because its Frame constructor allocates body eagerly.
    // A v1 ACCEPT still serializes to exactly the same bytes: Serialize only emits "b" when
    // Count > 0 (see line 23), so an empty body never reaches the wire.
    public static CallProtocol.Frame Accept(string callId, long seq)
    {
        var f = Make(CallProtocol.ACCEPT, callId, seq);
        f.B = new();
        return f;
    }
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

    // ── A02b v2 builders ──────────────────────────────────────────
    // The v1 builders above are left untouched on purpose: an archived Windows 2.2.42 and a current
    // Android voice build must keep seeing byte-identical v1 frames. Every v2 builder therefore
    // stamps V=2 explicitly and adds exactly the keys CallVideoProtocol.Valid demands -- no more.

    public static CallProtocol.Frame VideoInvite(string callId, long seq, string callerId, string calleeId, string media)
    {
        var f = Make(CallProtocol.INVITE, callId, seq);
        f.V = 2;
        f.B = new() { ["caller"] = callerId, ["callee"] = calleeId, ["media"] = media };
        return f;
    }

    public static CallProtocol.Frame VideoAccept(string callId, long seq, string media)
    {
        var f = Make(CallProtocol.ACCEPT, callId, seq);
        f.V = 2;
        f.B = new() { ["media"] = media };
        return f;
    }

    // `gen` is a parameter even though it must be zero, so that a caller who passes the wrong value
    // gets a frame their own peer will refuse rather than a silently wrong one.
    public static CallProtocol.Frame VideoRequest(string callId, long seq, long gen, string requestId)
    {
        var f = Make(CallProtocol.VIDEO_REQUEST, callId, seq, gen);
        f.V = 2;
        f.B = new() { ["request"] = requestId };
        return f;
    }

    public static CallProtocol.Frame VideoAcceptUpgrade(string callId, long seq, long gen, string requestId)
    {
        var f = Make(CallProtocol.VIDEO_ACCEPT, callId, seq, gen);
        f.V = 2;
        f.B = new() { ["request"] = requestId };
        return f;
    }

    public static CallProtocol.Frame VideoDecline(string callId, long seq, long gen, string requestId)
    {
        var f = Make(CallProtocol.VIDEO_DECLINE, callId, seq, gen);
        f.V = 2;
        f.B = new() { ["request"] = requestId };
        return f;
    }

    public static CallProtocol.Frame VideoState(string callId, long seq, long gen, string requestId, bool camera, long revision)
    {
        var f = Make(CallProtocol.VIDEO_STATE, callId, seq, gen);
        f.V = 2;
        f.B = new() { ["request"] = requestId, ["camera"] = camera, ["revision"] = revision };
        return f;
    }

    public static CallProtocol.Frame RemoteSpeaker(string callId, long seq, bool speaker)
    {
        var f = Make(CallProtocol.REMOTE_SPEAKER, callId, seq);
        f.V = 2;
        f.B = new() { ["speaker"] = speaker };
        return f;
    }

    public static CallProtocol.Frame RemoteCamera(string callId, long seq, long gen, string requestId, bool camera, string facing)
    {
        var f = Make(CallProtocol.REMOTE_CAMERA, callId, seq, gen);
        f.V = 2;
        f.B = new() { ["request"] = requestId, ["camera"] = camera, ["facing"] = facing };
        return f;
    }

    /// Video-negotiation OFFER/ANSWER/ICE/MEDIA_READY/ERROR. These are the frames that carry a
    /// `media` key and, for video, the `request` they belong to plus a generation of at least 2.
    public static CallProtocol.Frame MediaOffer(string callId, long seq, long gen, string media, string requestId, string sdp)
    {
        var f = Make(CallProtocol.OFFER, callId, seq, gen);
        f.V = 2;
        f.B = Media(media, requestId);
        f.B["sdp"] = sdp;
        return f;
    }

    public static CallProtocol.Frame MediaAnswer(string callId, long seq, long gen, string media, string requestId, string sdp)
    {
        var f = Make(CallProtocol.ANSWER, callId, seq, gen);
        f.V = 2;
        f.B = Media(media, requestId);
        f.B["sdp"] = sdp;
        return f;
    }

    public static CallProtocol.Frame MediaIce(string callId, long seq, long gen, string media, string requestId,
        string candidate, string sdpMid, int sdpMLineIndex)
    {
        var f = Make(CallProtocol.ICE, callId, seq, gen);
        f.V = 2;
        f.B = Media(media, requestId);
        f.B["candidate"] = candidate;
        f.B["sdpMid"] = sdpMid;
        f.B["sdpMLineIndex"] = sdpMLineIndex;
        return f;
    }

    public static CallProtocol.Frame MediaReadyFor(string callId, long seq, long gen, string media, string requestId)
    {
        var f = Make(CallProtocol.MEDIA_READY, callId, seq, gen);
        f.V = 2;
        f.B = Media(media, requestId);
        return f;
    }

    public static CallProtocol.Frame MediaError(string callId, long seq, long gen, string media, string requestId, string code)
    {
        var f = Make(CallProtocol.ERROR, callId, seq, gen);
        f.V = 2;
        f.B = Media(media, requestId);
        f.B["code"] = code;
        return f;
    }

    // A video body always carries the request it belongs to; an audio body must NOT, because
    // CallVideoProtocol.MediaKeys rejects an audio frame that has one.
    static Dictionary<string, object> Media(string media, string requestId)
    {
        var b = new Dictionary<string, object> { ["media"] = media };
        if (media == "video") b["request"] = requestId;
        return b;
    }

    public static string? GetSdp(CallProtocol.Frame f) => f.B != null && f.B.TryGetValue("sdp", out var v) ? v as string : null;
    public static string? GetCandidate(CallProtocol.Frame f) => f.B != null && f.B.TryGetValue("candidate", out var v) ? v as string : null;
    public static string? GetSdpMid(CallProtocol.Frame f) => f.B != null && f.B.TryGetValue("sdpMid", out var v) ? v as string : null;
    public static int GetSdpMLineIndex(CallProtocol.Frame f) => f.B != null && f.B.TryGetValue("sdpMLineIndex", out var v) ? Convert.ToInt32(v) : 0;
}
