package net.lanmsg.chat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;

/** Test fixture: turn one saved owner group into the pre-M1 overlapping Members/Left row. */
public final class LegacyGroupState {
  public static void main(String[] args) throws Exception {
    File directory = new File(args[0]);
    File state = new File(directory, "state.txt");
    byte[] stored = Files.readAllBytes(state.toPath());
    byte[] magic = "LMSEC3\n".getBytes(StandardCharsets.US_ASCII);
    if (!Arrays.equals(Arrays.copyOf(stored, magic.length), magic)) throw new IllegalStateException("Expected encrypted fixture state");
    String text = new String(new TestProtector(directory).unprotect(Arrays.copyOfRange(stored, magic.length, stored.length)), StandardCharsets.UTF_8);
    ArrayList<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
    boolean found = false;
    for (int i = 0; i < lines.size(); i++) {
      String[] row = lines.get(i).split("\t", -1);
      if (row.length == 6 && row[0].equals("G") && row[1].equals(args[1])) {
        if (!Arrays.asList(row[4].split(",")).contains(args[2])) throw new IllegalStateException("Departed id absent from old roster");
        lines.set(i, String.join("\t", "G", row[1], row[2], row[3], row[4], "", args[2]));
        found = true;
      }
    }
    if (!found) throw new IllegalStateException("Group row not found");
    Files.writeString(state.toPath(), String.join("\n", lines), StandardCharsets.UTF_8);
  }
}
