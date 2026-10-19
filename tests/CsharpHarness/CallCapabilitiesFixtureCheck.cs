using System.Text;

namespace LanMessenger;

// Runs the shared capability/grant corpus against the WINDOWS boundary and writes one verdict line
// per record. tests/video-contract/CallCapabilityFixtureCheck.java runs the same corpus against the
// Android boundary; tests/video-contract/run.ps1 diffs the two outputs.
//
// The interesting records here are the near-misses: "LM4\tCALLCAPS\t2\tH264" is a peer that
// understands the query perfectly well and is simply not going to do video, and it must be treated
// as voice-only rather than as "capable". Conversely a legacy-simulating build must never answer at
// all, so that an old Windows client cannot be mistaken for a new one just because it was asked.
static class CallCapabilitiesFixtureCheck
{
    public static int Run(string fixturesDirectory, string? outputPath)
    {
        var results = new StringBuilder();
        int failures = 0, records = 0;

        foreach (var raw in File.ReadAllLines(Path.Combine(fixturesDirectory, "capabilities.txt")))
        {
            var line = raw;
            if (line.EndsWith('\r')) line = line[..^1];
            if (line.Length == 0 || line.StartsWith('#')) continue;
            var parts = line.Split('|');
            var name = parts[0];

            if (parts.Length == 3)
            {
                int expected = int.Parse(parts[1]);
                int mask = CallCapabilities.ParseGrants(Reply(parts[2]));
                if (mask != expected)
                {
                    Console.Error.WriteLine($"{name}: expected grant mask {expected} but got {mask}");
                    failures++;
                }
                results.Append(name).Append('\t').Append(mask).Append('\n');
                records++;
                continue;
            }

            if (parts.Length != 6)
            {
                Console.Error.WriteLine($"Malformed capability record: {line}");
                failures++;
                continue;
            }
            int wantCapable = int.Parse(parts[1]);
            bool verified = parts[2] == "1";
            bool legacy = parts[3] == "1";
            long elapsed = long.Parse(parts[4]);
            bool capable = CallCapabilities.Supports(Reply(parts[5]), verified, legacy, elapsed);
            if (capable != (wantCapable == 1))
            {
                Console.Error.WriteLine($"{name}: expected capable={wantCapable} but got {capable}");
                failures++;
            }
            results.Append(name).Append('\t').Append(capable ? 1 : 0).Append('\n');
            records++;
        }

        if (records < 30)
        {
            Console.Error.WriteLine($"Capability corpus shrank: only {records} records");
            failures++;
        }

        if (outputPath != null) File.WriteAllText(outputPath, results.ToString());
        else Console.Write(results.ToString());
        Console.WriteLine($"Capability fixtures: {records} records, {failures} failures");
        return failures == 0 ? 0 : 1;
    }

    // '-' means the peer closed the connection without answering at all, which is the common case for
    // an old build. It is a null reply, not an empty one.
    static string? Reply(string raw) =>
        raw == "-" ? null : CallFrameFixtureCheck.Unescape(raw);
}
