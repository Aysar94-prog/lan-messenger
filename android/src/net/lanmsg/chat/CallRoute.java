package net.lanmsg.chat;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;

import java.util.List;

/** Applies an audio output route to the platform.
 *
 *  Selection lives in {@link CallRoutePolicy} so the rules are testable off-device; this class only
 *  carries the decision out. Default is the earpiece, because a private call heard out loud is a
 *  privacy failure rather than a convenience. The speaker is reached only by an explicit user
 *  action, never as an automatic fallback.
 *
 *  Deliberately limited to the two routes this release supports. Bluetooth device selection and
 *  proximity-sensor screen blanking are out of scope, so neither is claimed here: on API 31+ the
 *  platform is only asked to switch when the user actually chose the speaker, and it is never
 *  forced back to the earpiece while a headset the platform itself selected is still connected.
 */
final class CallRoute {

  private CallRoute() {}

  private static AudioManager audio(Context context) {
    return (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
  }

  /** Apply the requested route for a live call. */
  static void apply(Context context, boolean speaker) {
    AudioManager manager = context == null ? null : audio(context);
    if (manager == null) return;
    try {
      // Communication mode is what makes the platform apply voice-call processing (echo
      // cancellation, noise suppression) rather than playback defaults.
      manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (speaker) {
          AudioDeviceInfo builtIn = findDevice(manager, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER);
          if (builtIn != null) manager.setCommunicationDevice(builtIn);
        } else if (hasSelectedCommunicationDevice(manager)) {
          // Only clear a device when one is actually selected. If nothing is selected, the
          // platform is already routing to the earpiece and clearing would be a no-op that risks
          // dropping a call whose audio the user has already routed elsewhere.
          manager.clearCommunicationDevice();
        }
      } else {
        manager.setSpeakerphoneOn(speaker);
      }
    } catch (Exception ignored) {
      // Best effort. The snapshot still records the user's choice, and the platform keeps its
      // current route rather than dropping the call over a cosmetic setting.
    }
  }

  private static boolean hasSelectedCommunicationDevice(AudioManager manager) {
    try { return manager.getCommunicationDevice() != null; }
    catch (Exception ignored) { return false; }
  }

  /** The first available device of the given type, or null when the device has none. */
  private static AudioDeviceInfo findDevice(AudioManager manager, int type) {
    try {
      List<AudioDeviceInfo> devices = manager.getAvailableCommunicationDevices();
      if (devices == null) return null;
      for (AudioDeviceInfo device : devices) {
        if (device.getType() == type) return device;
      }
    } catch (Exception ignored) {}
    return null;
  }

  /** Leave communication mode.  Called when a call ends and the device is released, so the app
   *  does not leave the platform in a call-optimized mode with no call running. */
  static void exitCallMode(Context context) {
    AudioManager manager = context == null ? null : audio(context);
    if (manager == null) return;
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.clearCommunicationDevice();
      else manager.setSpeakerphoneOn(false);
      manager.setMode(AudioManager.MODE_NORMAL);
    } catch (Exception ignored) {}
  }
}
