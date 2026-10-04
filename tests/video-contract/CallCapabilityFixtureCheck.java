package net.lanmsg.chat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Runs the shared capability/grant corpus against the ANDROID boundary and prints one verdict line
 *  per record. tests/CsharpHarness/CallCapabilitiesFixtureCheck.cs runs the same corpus against the
 *  Windows implementation and tests/run.ps1 diffs the two outputs.
 *
 *  Output: {@code name<TAB>capable} for CALLCAPS records and {@code name<TAB>mask} for CALLGRANTS
 *  records, where mask is 0..15 or -1 when no usable answer was given.
 */
public final class CallCapabilityFixtureCheck {

  private static int pass;

  static void check(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
    ++pass;
  }

  /** Byte-for-byte the expression PeerEngine.parseCallGrant uses. PeerEngine itself cannot be
   *  compiled in this pure-Java harness (it pulls in android.util.Log and the whole transfer stack),
   *  so the grammar is asserted here and cross-checked against the Windows implementation through
   *  the shared corpus. A change to PeerEngine's pattern must be made here in the same commit. */
  static final String GRANTS = "LM4\\tCALLGRANTS\\t1\\t(?:[0-9]|1[0-5])";

  static int parseGrant(String reply) {
    if (reply == null || !reply.matches(GRANTS)) return -1;
    return Integer.parseInt(reply.substring(reply.lastIndexOf('\t') + 1));
  }

  static String unescape(String raw) {
    StringBuilder sb = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (c != '\\') { sb.append(c); continue; }
      if (++i >= raw.length()) throw new IllegalArgumentException("Trailing escape");
      char e = raw.charAt(i);
      switch (e) {
        case 't': sb.append('\t'); break;
        case 'r': sb.append('\r'); break;
        case 'n': sb.append('\n'); break;
        case '\\': sb.append('\\'); break;
        default: sb.append('\\').append(e);
      }
    }
    return sb.toString();
  }

  static String reply(String raw) { return "-".equals(raw) ? null : unescape(raw); }

  public static void main(String[] args) throws IOException {
    if (args.length < 1) throw new IllegalArgumentException("Usage: CallCapabilityFixtureCheck <fixturesDir>");
    Path dir = Paths.get(args[0]);
    List<String> lines = Files.readAllLines(dir.resolve("capabilities.txt"), StandardCharsets.UTF_8);

    StringBuilder results = new StringBuilder();
    int records = 0;
    // Counted, not thrown, so a full verdict file is still written for the cross-platform diff.
    int failures = 0;

    for (String rawLine : lines) {
      String line = rawLine;
      if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
      if (line.isEmpty() || line.startsWith("#")) continue;
      String[] parts = line.split("\\|", -1);
      String name = parts[0];

      // Three fields means a CALLGRANTS record, six a CALLCAPS one. The record is identified by its
      // shape rather than by searching the text for "CALLGRANTS": the reply keeps its tabs escaped,
      // so the literal "|CALLGRANTS|" never appears.
      if (parts.length == 3) {
        int expected = Integer.parseInt(parts[1]);
        int mask = parseGrant(reply(parts[2]));
        if (mask != expected) {
          ++failures;
          System.err.println(name + ": expected grant mask " + expected + " but got " + mask);
        }
        results.append(name).append('\t').append(mask).append('\n');
        ++records;
        continue;
      }

      check(parts.length == 6, "Malformed capability record: " + line);
      int expectedCapable = Integer.parseInt(parts[1]);
      boolean verified = "1".equals(parts[2]);
      boolean legacy = "1".equals(parts[3]);
      long elapsed = Long.parseLong(parts[4]);
      boolean capable = CallCapabilities.supports(reply(parts[5]), verified, legacy, elapsed);
      if (capable != (expectedCapable == 1)) {
        ++failures;
        System.err.println(name + ": expected capable=" + expectedCapable + " but got " + capable);
      }
      results.append(name).append('\t').append(capable ? 1 : 0).append('\n');
      ++records;
    }

    check(records >= 30, "Capability corpus shrank: only " + records + " records");
    if (args.length >= 2) Files.write(Paths.get(args[1]), results.toString().getBytes(StandardCharsets.UTF_8));
    else System.out.print(results);
    System.out.println("Capability fixtures: " + records + " records, " + failures + " failures");
    if (failures != 0) System.exit(1);
  }

  private CallCapabilityFixtureCheck() {}
}
