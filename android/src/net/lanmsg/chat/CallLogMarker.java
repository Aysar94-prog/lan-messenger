package net.lanmsg.chat;

// A call-history entry is a local-only PeerEngine.Message whose fileName, not its text, carries
// the record -- mirroring VoiceMarker's own filename-convention trick exactly, since both need a
// cheap way to tag one message's kind without a new storage format or LMSTORE4 row type. Never
// sent to a peer (PeerEngine.appendCallLog sets status="Delivered" directly, so deliver() never
// queues it), never decrypted or validated like a voice marker's id -- just encode/parse.
final class CallLogMarker {
  private CallLogMarker() {}
  private static final String PREFIX = "call-", SUFFIX = ".lanlog";

  static final class Info { final boolean isCaller, connected; final long durationMs;
    Info(boolean c, boolean conn, long d) { isCaller = c; connected = conn; durationMs = d; } }

  static String encode(boolean isCaller, boolean connected, long durationMs) {
    return PREFIX + (isCaller ? "1" : "0") + "-" + (connected ? "1" : "0") + "-" + Math.max(0, durationMs) + SUFFIX;
  }

  static Info tryParse(String fileName) {
    if (!fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) return null;
    String body = fileName.substring(PREFIX.length(), fileName.length() - SUFFIX.length());
    String[] parts = body.split("-", -1);
    if (parts.length != 3) return null;
    try { return new Info("1".equals(parts[0]), "1".equals(parts[1]), Long.parseLong(parts[2])); }
    catch (NumberFormatException ex) { return null; }
  }
}
