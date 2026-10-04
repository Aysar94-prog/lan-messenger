package net.lanmsg.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs the shared call-frame corpus against the ANDROID parser/validator and prints one verdict
 *  line per record. tests/CsharpHarness/CallFrameFixtureCheck.cs runs the same corpus against the
 *  Windows implementation; tests/run.ps1 diffs the two outputs, so any place the two platforms
 *  accept or refuse different frames is a build failure rather than an interop surprise.
 *
 *  Output: {@code name<TAB>parse<TAB>valid} where parse is 1/0 and valid is 1/0/-.
 */
public final class SharedFrameFixtureCheck {

  private static int pass;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    ++pass;
  }

  /** Payloads and samples are JSON *text*, so they are used exactly as written. An SDP sample keeps
   *  its CRLF as the two-character escape "\\r\\n" because that is what has to appear inside a JSON
   *  string; unescaping it here would put a raw control character in the JSON and both parsers would
   *  then refuse the frame for the wrong reason. */
  /** @sample@ references are expanded here, so both readers see byte-identical payloads. */
  static String expand(String text, Map<String, String> samples) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) != '@') { out.append(text.charAt(i)); continue; }
      int end = text.indexOf('@', i + 1);
      if (end < 0) { out.append(text.charAt(i)); continue; }
      String name = text.substring(i + 1, end);
      String value = samples.get(name);
      if (value == null) throw new IllegalArgumentException("Unknown sample @" + name + "@");
      out.append(value);
      i = end;
    }
    return out.toString();
  }

  static byte[] wire(String payload) {
    byte[] utf8 = payload.getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[4 + utf8.length];
    out[0] = (byte) (utf8.length >> 24);
    out[1] = (byte) (utf8.length >> 16);
    out[2] = (byte) (utf8.length >> 8);
    out[3] = (byte) utf8.length;
    System.arraycopy(utf8, 0, out, 4, utf8.length);
    return out;
  }

  public static void main(String[] args) throws IOException {
    if (args.length < 1) throw new IllegalArgumentException("Usage: SharedFrameFixtureCheck <fixturesDir>");
    Path dir = Paths.get(args[0]);
    Map<String, String> samples = new LinkedHashMap<String, String>();
    List<String> records = new ArrayList<String>();

    for (String raw : Files.readAllLines(dir.resolve("call-frames.txt"), StandardCharsets.UTF_8)) {
      String line = raw;
      if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
      if (line.isEmpty()) continue;
      if (line.startsWith("#SAMPLE ")) {
        int tab = line.indexOf('\t', 8);
        check(tab > 8, "Malformed sample line");
        String name = line.substring(8, tab);
        samples.put(name, line.substring(tab + 1));
        continue;
      }
      if (line.startsWith("#")) continue;
      records.add(line);
    }

    // Samples may reference other samples, so resolve until stable (bounded, to catch a cycle).
    for (int pass = 0; pass < 8; pass++) {
      boolean changed = false;
      Map<String, String> next = new LinkedHashMap<String, String>();
      for (Map.Entry<String, String> e : samples.entrySet()) {
        String expanded = expand(e.getValue(), samples);
        changed |= !expanded.equals(e.getValue());
        next.put(e.getKey(), expanded);
      }
      samples = next;
      if (!changed) break;
    }

    StringBuilder results = new StringBuilder();
    int seen = 0;
    // Verdict mismatches are counted rather than thrown, so the whole corpus is still walked and a
    // verdict file is written. That file is what gets diffed against the Windows one, and a diff of
    // a complete run says far more than "record 43 disagreed".
    int failures = 0;
    for (String line : records) {
      // Split at most 4 fields so the payload keeps any '|' it might contain.
      String[] parts = line.split("\\|", 4);
      check(parts.length == 4, "Malformed fixture record: " + line);
      String name = parts[0];
      int expectParse = Integer.parseInt(parts[1]);
      String expectValid = parts[2];
      String payload = parts[3];

      byte[] bytes;
      if (payload.startsWith("b64:")) bytes = Base64.getDecoder().decode(payload.substring(4));
      else bytes = wire(expand(payload, samples));

      CallProtocol.Frame frame = CallSignaling.parse(bytes);
      int parsed = frame != null ? 1 : 0;
      // validity is a v2 concept; for a v1 frame both sides report '-' rather than inventing an answer
      String valid = "-";
      if (frame != null && frame.protocolVersion == 2) valid = CallVideoProtocol.valid(frame) ? "1" : "0";

      boolean ok = parsed == expectParse && (expectValid.equals("-") || valid.equals(expectValid));
      if (!ok) {
        ++failures;
        System.err.println(name + ": expected parse=" + expectParse + " valid=" + expectValid
            + " but got parse=" + parsed + " valid=" + valid + describe(frame));
      }
      results.append(name).append('\t').append(parsed).append('\t').append(valid).append('\n');
      ++seen;
    }

    check(seen >= 80, "Fixture corpus shrank: only " + seen + " records");
    if (args.length >= 2) Files.write(Paths.get(args[1]), results.toString().getBytes(StandardCharsets.UTF_8));
    else System.out.print(results);
    System.out.println("Shared frame fixtures: " + seen + " records, " + failures + " failures");
    if (failures != 0) System.exit(1);
  }

  /** The parsed frame's shape, because a validator returning false for a good frame is otherwise
   *  impossible to tell apart from a parser that dropped the frame on the way in. */
  static String describe(CallProtocol.Frame frame) {
    if (frame == null) return "";
    StringBuilder sb = new StringBuilder();
    sb.append(" [v=").append(frame.protocolVersion).append(" t=").append(frame.type)
        .append(" cid=").append(frame.callId).append(" seq=").append(frame.senderSequence)
        .append(" gen=").append(frame.negotiationGeneration);
    if (frame.body != null) {
      sb.append(" body={");
      boolean first = true;
      for (Map.Entry<String, Object> e : frame.body.entrySet()) {
        if (!first) sb.append(",");
        first = false;
        sb.append(e.getKey()).append(":")
            .append(e.getValue() == null ? "null" : e.getValue().getClass().getSimpleName());
      }
      sb.append("}");
    }
    return sb.append("]").toString();
  }

  private SharedFrameFixtureCheck() {}
}
