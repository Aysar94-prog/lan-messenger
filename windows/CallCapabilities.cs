using System.Text;

namespace LanMessenger;

// Separate call capability transaction. No group/attachment capability changes, no persistence, no
// cache, and -- critically -- no capability-driven permission request or camera acquisition: a peer
// answering this proves it can parse v2, nothing more. Ported from
// android/src/net/lanmsg/chat/CallCapabilities.java, including the exact response bytes and the
// strict UTF-8 reply decode, because the shared fixture corpus asserts both parsers agree on
// malformed and oversized replies.
public static class CallCapabilities
{
    public const string Request = "LM4\tCALLCAPS";
    public const string Response = "LM4\tCALLCAPS\t2\tVP8";
    public const int MaxReplyBytes = 128;
    public const int TimeoutMs = 10_000;

    // A peer only counts as video-capable when it is verified, is not a simulated legacy build,
    // answered inside the deadline, and answered with the exact expected line. Anything else --
    // missing, malformed, oversized, slow, or a different protocol/codec claim -- means plain v1
    // voice, and must never be cached across calls (A02b: "no success cached across calls").
    public static bool Supports(string? response, bool verified, bool legacy, long elapsedMs) =>
        verified && !legacy && elapsedMs >= 0 && elapsedMs < TimeoutMs && response == Response;

    public static string? ResponseFor(bool enabled, bool legacy) => enabled && !legacy ? Response : null;

    // Bounded, deadline-aware line read. Mirrors the Java readLine: at most `limit` bytes before the
    // terminator, strict UTF-8 (a malformed sequence is an error, never silently replaced), and a
    // per-read timeout recomputed from the caller's clock so a slow trickle cannot extend the
    // deadline indefinitely.
    public static async Task<string> ReadReplyAsync(Stream input, CancellationToken token)
    {
        var started = System.Diagnostics.Stopwatch.StartNew();
        var bytes = new MemoryStream();
        var one = new byte[1];
        var strict = new UTF8Encoding(false, throwOnInvalidBytes: true);
        while (true)
        {
            int remaining = Remaining(started, token);
            using var slice = CancellationTokenSource.CreateLinkedTokenSource(token);
            slice.CancelAfter(remaining);
            int value = await input.ReadAsync(one, slice.Token).ConfigureAwait(false);
            if (value <= 0) throw new EndOfStreamException("No call capability reply");
            if (one[0] == 10)
            {
                try { return strict.GetString(bytes.ToArray()); }
                catch (DecoderFallbackException) { throw new IOException("Invalid capability encoding"); }
            }
            if (bytes.Length >= MaxReplyBytes) throw new IOException("Capability reply too large");
            bytes.WriteByte(one[0]);
        }
    }

    static int Remaining(System.Diagnostics.Stopwatch since, CancellationToken token)
    {
        if (token.IsCancellationRequested) throw new OperationCanceledException(token);
        long elapsed = since.ElapsedMilliseconds;
        if (elapsed >= TimeoutMs) throw new IOException("Call capability timeout");
        return (int)Math.Max(1, TimeoutMs - elapsed);
    }

    // The exact request line, written and flushed. Split out so the caller controls the connection
    // lifetime: the probe runs on its own authenticated TLS connection, exactly as the Android
    // implementation does, and closes it immediately after the reply.
    public static async Task WriteRequestAsync(Stream output, CancellationToken token)
    {
        var data = Encoding.UTF8.GetBytes(Request + "\n");
        using var slice = CancellationTokenSource.CreateLinkedTokenSource(token);
        slice.CancelAfter(TimeoutMs);
        await output.WriteAsync(data, slice.Token).ConfigureAwait(false);
        await output.FlushAsync(slice.Token).ConfigureAwait(false);
    }

    // Grants are a separate, optional query so a device that never answers it still gets calls; the
    // answer is a 0..15 mask bound to the verified certificate, never a capability.
    //
    // The accepted shape is matched with the SAME full-match pattern the Android peer engine uses
    // ("(?:[0-9]|1[0-5])"), so "04" and "007" are refused on both ends rather than one side reading
    // them as 4 and 7. Two platforms disagreeing about which bits a peer granted is exactly the kind
    // of divergence that shows up as a camera that never opens.
    public const string GrantsRequest = "LM4\tCALLGRANTS\t1";

    static readonly System.Text.RegularExpressions.Regex GrantsPattern =
        new("^LM4\tCALLGRANTS\t1\t(?:[0-9]|1[0-5])$", System.Text.RegularExpressions.RegexOptions.CultureInvariant);

    public static string GrantsResponse(int mask) => $"LM4\tCALLGRANTS\t1\t{mask & 15}";

    public static int ParseGrants(string? response)
    {
        if (response == null || !GrantsPattern.IsMatch(response)) return -1;
        // Take everything after the last tab, so mask 15 does not come back as 5.
        int tab = response.LastIndexOf('\t');
        return int.TryParse(response.AsSpan(tab + 1), out var mask) ? mask : -1;
    }
}
