package net.lanmsg.chat;

/** A03: call-bound permission request identity. Permission is not video consent.
 * Pure Java; owns no Activity, callback, camera or media object. */
public final class CallCameraPermission {
  public static final int REQUEST_FIRST = 12000, REQUEST_LAST = 16000;
  private static final java.util.concurrent.atomic.AtomicInteger REQUEST_CODES =
    new java.util.concurrent.atomic.AtomicInteger(REQUEST_FIRST);
  /** Process-wide allocation also prevents a recreated Activity reusing an old OS request code. */
  public static int nextRequestCode() {
    int code = REQUEST_CODES.getAndUpdate(value -> value <= REQUEST_LAST ? value + 1 : value);
    return code <= REQUEST_LAST ? code : -1;
  }
  public enum Decision { Ready, Request, Denied, Unavailable, Stale, Busy }
  private String pendingCallId;
  private long nextToken, pendingToken;

  public synchronized Decision begin(String expectedCallId, String liveCallId,
      boolean online, boolean cameraAvailable, boolean currentlyGranted, boolean permanentlyDenied) {
    if (expectedCallId == null || !expectedCallId.equals(liveCallId) || !online) return Decision.Stale;
    if (!cameraAvailable) return Decision.Unavailable;
    if (pendingCallId != null) return Decision.Busy;
    if (!currentlyGranted && permanentlyDenied) return Decision.Denied;
    if (nextToken == Long.MAX_VALUE) return Decision.Stale;
    pendingCallId = expectedCallId; pendingToken = ++nextToken;
    return currentlyGranted ? Decision.Ready : Decision.Request;
  }

  public synchronized long token() { return pendingToken; }
  public synchronized boolean hasPending() { return pendingCallId != null; }
  public synchronized String callId() { return pendingCallId; }

  public synchronized Decision complete(long token, String liveCallId, boolean online,
      boolean cameraAvailable, boolean currentlyGranted) {
    if (pendingCallId == null || token != pendingToken) return Decision.Stale;
    boolean current = online && pendingCallId.equals(liveCallId);
    cancel();
    if (!current) return Decision.Stale;
    if (!cameraAvailable) return Decision.Unavailable;
    return currentlyGranted ? Decision.Ready : Decision.Denied;
  }

  public synchronized void reconcile(String liveCallId, boolean online) {
    if (!online || (pendingCallId != null && !pendingCallId.equals(liveCallId))) cancel();
  }
  public synchronized void cancel() { pendingCallId = null; pendingToken = 0; }
}
