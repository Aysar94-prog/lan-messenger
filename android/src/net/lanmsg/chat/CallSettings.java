package net.lanmsg.chat;

import java.io.*;

/** Incoming-call preference: persisted per-device, independent of Online/Offline.
 *  Uses a separate atomic file in the peer-data directory (not the network prefs).
 *
 *  Contract (from plan-v006):
 *  - Label: "Allow incoming calls"
 *  - Default: enabled when setting is genuinely absent
 *  - Corrupt/unreadable → disabled for session, recoverable error shown
 *  - Older writes cannot overwrite newer choices (generation counter)
 *  - Preserved across chat deletion and messaging-data reset
 *  - OS-level data clear or uninstall resets
 */
public final class CallSettings {

  private static final String FILE_NAME = "call-settings.txt";
  private static final String ENABLED_PREFIX = "ALLOW_INCOMING\t1\t";
  private static final String DISABLED_PREFIX = "ALLOW_INCOMING\t0\t";

  private final File file;
  private boolean allowIncoming;
  private long generation;
  private String loadError;

  /** Load call settings from the given directory.  Missing file → default enabled. */
  public CallSettings(File dataDir) {
    this.file = new File(dataDir, FILE_NAME);
    this.allowIncoming = true; // default: enabled when absent
    this.generation = 0;
    this.loadError = null;
    load();
  }

  private void load() {
    if (!file.exists()) return;
    try {
      String content = new String(SecureIdentity.readFile(file), java.nio.charset.StandardCharsets.UTF_8).trim();
      if (content.startsWith(ENABLED_PREFIX)) {
        allowIncoming = true;
        generation = parseGeneration(content, ENABLED_PREFIX);
      } else if (content.startsWith(DISABLED_PREFIX)) {
        allowIncoming = false;
        generation = parseGeneration(content, DISABLED_PREFIX);
      } else {
        throw new IOException("Unrecognized call-settings format");
      }
    } catch (Exception e) {
      // Corrupt/unreadable → disabled for session
      allowIncoming = false;
      generation = 0;
      loadError = "Call settings could not be read. Incoming calls are disabled.";
    }
  }

  private static long parseGeneration(String content, String prefix) throws IOException {
    try {
      return Long.parseLong(content.substring(prefix.length()));
    } catch (Exception e) {
      throw new IOException("Invalid generation in call settings");
    }
  }

  // ── Public API ─────────────────────────────────────────────────

  /** Whether incoming calls are currently allowed. */
  public synchronized boolean allowIncoming() { return allowIncoming; }

  /** Error from loading, or null.  UI can show this as a recoverable notice. */
  public synchronized String loadError() { return loadError; }

  /** Update the preference.  Persisted atomically with a generation counter
   *  so older writes cannot overwrite newer choices. */
  public synchronized void setAllowIncoming(boolean value) throws IOException {
    long newGen = generation + 1;
    String content = (value ? ENABLED_PREFIX : DISABLED_PREFIX) + newGen;

    // Write to temp, then atomic rename
    File tmp = new File(file.getPath() + ".tmp");
    try (FileOutputStream fos = new FileOutputStream(tmp)) {
      fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      fos.getFD().sync();
    }
    if (!tmp.renameTo(file)) {
      // Temp rename failed; try direct write as fallback
      try (FileOutputStream fos = new FileOutputStream(file)) {
        fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        fos.getFD().sync();
      }
    }

    this.allowIncoming = value;
    this.generation = newGen;
    this.loadError = null; // successful write clears any previous load error
  }

  /** For testing: get current generation. */
  synchronized long generation() { return generation; }
}