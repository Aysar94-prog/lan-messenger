package net.lanmsg.chat;

/** Audio output route selection policy (plan-v006 A07).
 *
 *  Pure Java: decides WHICH route a call should use and whether a switch is allowed. The Android
 *  side (AudioManager/AudioDeviceModule) only applies the decision. Keeping selection here means
 *  the routing rules are testable on the desktop and identical on both platforms' semantics.
 *
 *  Rules:
 *  - Default route is Earpiece. Speaker is a deliberate user action, not an automatic fallback.
 *  - A wired/Bluetooth headset outranks earpiece; a user-selected route outranks discovery.
 *  - When the selected route disappears, recovery is retried for ROUTE_RECOVERY_MS before the call
 *    is failed -- a headset disconnecting mid-call is transient far more often than not.
 *  - Proximity is only relevant for the earpiece route and only while not on speaker.
 */
public class CallRoutePolicy {

  public enum Route {
    Earpiece,     // default for a private call
    Speaker,      // explicit user choice
    Wired,        // wired headset / USB audio
    Bluetooth     // Bluetooth headset
  }

  /** What the platform currently has available. */
  public static final class Availability {
    public final boolean earpiece, speaker, wired, bluetooth;

    public Availability(boolean earpiece, boolean speaker, boolean wired, boolean bluetooth) {
      this.earpiece = earpiece; this.speaker = speaker; this.wired = wired; this.bluetooth = bluetooth;
    }

    public boolean has(Route r) {
      switch (r) {
        case Earpiece:   return earpiece;
        case Speaker:    return speaker;
        case Wired:      return wired;
        case Bluetooth:  return bluetooth;
        default:         return false;
      }
    }
  }

  private Route selected = Route.Earpiece;
  private Route lastGood = Route.Earpiece;
  private long routeLostAtMs = -1;
  private long nowMs;

  /** Injectable clock so recovery-window behavior is deterministic in tests. */
  public void setNowMs(long nowMs) { this.nowMs = nowMs; }

  public Route selected() { return selected; }

  /** The user explicitly picked a route. Outranks automatic discovery, and is not overwritten
   *  by a headset appearing or disappearing. */
  public boolean userSelect(Route route) {
    if (route == null) return false;
    selected = route;
    routeLostAtMs = -1;
    lastGood = route;
    return true;
  }

  /** Automatic preference for the current availability, used when nothing is user-selected.
   *  A connected headset wins over earpiece because that is what the user plugged in. */
  public Route preferred(Availability a) {
    if (a == null) return Route.Earpiece;
    if (a.wired) return Route.Wired;
    if (a.bluetooth) return Route.Bluetooth;
    if (a.earpiece) return Route.Earpiece;
    if (a.speaker) return Route.Speaker;
    return Route.Earpiece;
  }

  /** Reconcile the selected route against current availability.
   *
   *  Returns true when the applied route CHANGED (the caller must re-apply routing). A lost route
   *  is retried within ROUTE_RECOVERY_MS; after that window the call falls back to the preferred
   *  available route, or fails if nothing is available.
   *
   *  returns false when nothing changed, and sets failed=true only if no route at all exists. */
  public boolean reconcile(Availability a, boolean userSelected) {
    if (a == null) return false;

    if (a.has(selected)) {
      // Still good. Re-anchor recovery state.
      if (routeLostAtMs != -1) { routeLostAtMs = -1; }
      lastGood = selected;
      return false;
    }

    // The selected route is gone. Start (or continue) the recovery window.
    if (routeLostAtMs == -1) routeLostAtMs = nowMs;

    // A user-selected route is never silently overridden during recovery -- the user is expected
    // to pick again once their headset is back. Speaker is the single exception: it is the
    // universal fallback when the chosen private route is gone for good.
    if (userSelected && selected != Route.Speaker) {
      return false;
    }

    Route fallback = preferred(a);
    if (fallback == selected) return false;

    if (selected != Route.Speaker && nowMs - routeLostAtMs < CallProtocol.ROUTE_RECOVERY_MS) {
      // Still inside the recovery window: hold the last good route rather than switching.
      return false;
    }

    selected = fallback;
    routeLostAtMs = -1;
    return true;
  }

  /** Whether proximity sensing should be active. True only for the private earpiece route. */
  public boolean proximityActive() {
    return selected == Route.Earpiece;
  }

  /** True when the call should fail because no audio route exists. */
  public boolean noRouteAvailable(Availability a) {
    return a == null || (!a.earpiece && !a.speaker && !a.wired && !a.bluetooth);
  }

  public void reset() {
    selected = Route.Earpiece;
    lastGood = Route.Earpiece;
    routeLostAtMs = -1;
  }
}
