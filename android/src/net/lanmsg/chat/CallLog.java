package net.lanmsg.chat;

/** Call-path tracing, so a failed call can be diagnosed from logcat on a real device.
 *
 *  <p>The signaling path used to have no logging at all, which meant three separate silent
 *  failures were each diagnosed only by reasoning from source: an incoming INVITE discarded by a
 *  string-identity comparison, reply frames dropped by an admission table written from the wrong
 *  point of view, and an end banner that never expired.  None of them threw, and none of them were
 *  reachable from the unit tests.  Every one of them would have been obvious here.
 *
 *  <p>All lines use the tag {@link #TAG} so a capture is simply
 *  {@code adb logcat -s LANCALL}.  Nothing here records audio, identity material or message
 *  content: only call IDs, frame types, states and refusal reasons. */
final class CallLog {
  private CallLog() {}

  static final String TAG = "LANCALL";

  static void i(String message) {
    try { android.util.Log.i(TAG, message); } catch (Throwable ignored) {}
  }

  static void w(String message) {
    try { android.util.Log.w(TAG, message); } catch (Throwable ignored) {}
  }

  /** A short readable prefix of an encoded frame, so outbound traffic is legible in a capture.
   *  Frames are JSON, so the leading bytes already name the type.  Non-printable bytes become
   *  {@code .} rather than being dropped, which keeps the frame length readable. */
  static String preview(byte[] bytes) {
    if (bytes == null) return "(null)";
    StringBuilder sb = new StringBuilder(bytes.length < 72 ? bytes.length : 72);
    for (int i = 0; i < bytes.length && sb.length() < 72; i++) {
      int c = bytes[i] & 0xFF;
      sb.append(c >= 32 && c < 127 ? (char) c : '.');
    }
    return sb.toString();
  }
}