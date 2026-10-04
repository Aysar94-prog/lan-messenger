using System.Text;

namespace LanMessenger;

// Runs the shared call-frame corpus against the WINDOWS parser/validator and writes one verdict line
// per record. tests/video-contract/SharedFrameFixtureCheck.java runs the same corpus against the
// Android implementation; tests/run.ps1 diffs the two outputs, so any place the two platforms accept
// or refuse different frames is a build failure rather than an interop surprise.
//
// Output: `name<TAB>parse<TAB>valid` where parse is 1/0 and valid is 1/0/-.
//
// The expectations in the corpus file are asserted here as well, which makes it a three-way check:
// Android, Windows, and what a human wrote down. A change that both implementations make "the same
// wrong way" still fails, because the authored expectation does not move with it.
static class CallFrameFixtureCheck
{
    public static int Run(string fixturesDirectory, string? outputPath)
    {
        var samples = new Dictionary<string, string>(StringComparer.Ordinal);
        var records = new List<string>();
        int failures = 0;

        foreach (var raw in File.ReadAllLines(Path.Combine(fixturesDirectory, "call-frames.txt")))
        {
            var line = raw;
            if (line.EndsWith('\r')) line = line[..^1];
            if (line.Length == 0) continue;
            if (line.StartsWith("#SAMPLE ", StringComparison.Ordinal))
            {
                int tab = line.IndexOf('\t', 8);
                if (tab < 0) { Console.Error.WriteLine("Malformed sample line: " + line); return 1; }
                samples[line.Substring(8, tab - 8)] = line[(tab + 1)..];
                continue;
            }
            if (line.StartsWith('#')) continue;
            records.Add(line);
        }

        // Samples may reference other samples; resolve until stable.
        for (int pass = 0; pass < 8; pass++)
        {
            bool changed = false;
            var next = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var (name, text) in samples)
            {
                string expanded = Expand(text, samples, ref failures);
                changed |= expanded != text;
                next[name] = expanded;
            }
            samples = next;
            if (!changed) break;
        }

        var results = new StringBuilder();
        foreach (var line in records)
        {
            // Split at most 4 fields so the payload keeps any '|' it might contain.
            var parts = line.Split('|', 4);
            if (parts.Length != 4) { Console.Error.WriteLine("Malformed fixture record: " + line); return 1; }
            var name = parts[0];
            int expectParse = int.Parse(parts[1]);
            var expectValid = parts[2];

            byte[] bytes;
            if (parts[3].StartsWith("b64:", StringComparison.Ordinal))
            {
                try { bytes = Convert.FromBase64String(parts[3][4..]); }
                catch { Console.Error.WriteLine(name + ": bad base64"); return 1; }
            }
            else bytes = Wire(Expand(parts[3], samples, ref failures));

            var frame = CallSignaling.Parse(bytes);
            int parsed = frame != null ? 1 : 0;
            // validity is a v2 concept; for a v1 frame both sides report '-' rather than inventing one
            var valid = "-";
            if (frame != null && frame.V == 2) valid = CallVideoProtocol.Valid(frame) ? "1" : "0";

            if (parsed != expectParse || (expectValid != "-" && valid != expectValid))
            {
                // The body keys and value types are printed because a validator that returns false
                // for a good frame is otherwise impossible to diagnose from "0" alone.
                Console.Error.WriteLine($"{name}: expected parse={expectParse} valid={expectValid} "
                    + $"but got parse={parsed} valid={valid}{Describe(frame)}");
                failures++;
            }
            results.Append(name).Append('\t').Append(parsed).Append('\t').Append(valid).Append('\n');
        }

        if (records.Count < 80)
        {
            Console.Error.WriteLine($"Fixture corpus shrank: only {records.Count} records");
            failures++;
        }

        if (outputPath != null) File.WriteAllText(outputPath, results.ToString());
        else Console.Write(results.ToString());
        Console.WriteLine($"Shared frame fixtures: {records.Count} records, {failures} failures");
        return failures == 0 ? 0 : 1;
    }

    static string Describe(CallProtocol.Frame? frame)
    {
        if (frame == null) return "";
        var body = frame.B == null ? "" : " body={" + string.Join(",", frame.B
            .Select(p => $"{p.Key}:{(p.Value?.GetType().Name ?? "null")}")) + "}";
        return $" [v={frame.V} t={frame.T} cid={frame.Cid} seq={frame.Seq} gen={frame.Gen}{body}]";
    }

    static byte[] Wire(string payload)
    {
        var utf8 = Encoding.UTF8.GetBytes(payload);
        var wire = new byte[4 + utf8.Length];
        wire[0] = (byte)(utf8.Length >> 24); wire[1] = (byte)(utf8.Length >> 16);
        wire[2] = (byte)(utf8.Length >> 8); wire[3] = (byte)utf8.Length;
        Buffer.BlockCopy(utf8, 0, wire, 4, utf8.Length);
        return wire;
    }

    internal static string Unescape(string raw)
    {
        var sb = new StringBuilder(raw.Length);
        for (int i = 0; i < raw.Length; i++)
        {
            if (raw[i] != '\\') { sb.Append(raw[i]); continue; }
            if (++i >= raw.Length) throw new ArgumentException("Trailing escape");
            sb.Append(raw[i] switch { 't' => '\t', 'r' => '\r', 'n' => '\n', _ => raw[i] });
        }
        return sb.ToString();
    }

    static string Expand(string text, Dictionary<string, string> samples, ref int failures)
    {
        if (!text.Contains('@')) return text;
        var sb = new StringBuilder(text.Length);
        for (int i = 0; i < text.Length; i++)
        {
            if (text[i] != '@') { sb.Append(text[i]); continue; }
            int end = text.IndexOf('@', i + 1);
            if (end < 0) { sb.Append(text[i]); continue; }
            var name = text.Substring(i + 1, end - i - 1);
            if (!samples.TryGetValue(name, out var value))
            {
                Console.Error.WriteLine("Unknown sample @" + name + "@");
                failures++;
                sb.Append(text[i]);
                continue;
            }
            sb.Append(value);
            i = end;
        }
        return sb.ToString();
    }
}
