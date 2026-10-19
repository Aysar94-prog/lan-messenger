namespace LanMessenger;

// A call-history entry is a local-only Message whose FileName (not its Text) carries the record --
// mirroring android/src/net/lanmsg/chat/CallLogMarker.java's own filename-convention trick exactly,
// so both platforms encode the same four-variant call log the same structural way, and so neither
// needs a new storage row type for it.
public static class CallLogMarker
{
    const string Prefix = "call-", Suffix = ".lanlog";

    public sealed record Info(bool IsCaller, bool Connected, long DurationMs);

    public static string Encode(bool isCaller, bool connected, long durationMs) =>
        $"{Prefix}{(isCaller ? "1" : "0")}-{(connected ? "1" : "0")}-{Math.Max(0, durationMs)}{Suffix}";

    public static Info? TryParse(string fileName)
    {
        if (!fileName.StartsWith(Prefix) || !fileName.EndsWith(Suffix)) return null;
        var body = fileName[Prefix.Length..^Suffix.Length];
        var parts = body.Split('-');
        if (parts.Length != 3) return null;
        if (!long.TryParse(parts[2], out var duration)) return null;
        return new Info(parts[0] == "1", parts[1] == "1", duration);
    }
}
