package net.lanmsg.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;

/** A10: call notifications and the service command surface behind them.
 *
 *  The notification lives with the service, not the Activity, so a call keeps its notification
 *  while the app is backgrounded or the Activity has been destroyed.  Actions are plain service
 *  intents carrying the call ID; the controller revalidates the call ID and the current
 *  preference before acting, so a stale action can never accept a call that has since been
 *  withdrawn, or a different call than the one the action was raised for.
 *
 *  Two channels, deliberately:
 *   - "calls_ringing_v2" is default importance, with a separate ringtone: incoming
 *     calls are actionable without an unsolicited heads-up/full-screen popup.
 *   - "calls_active" is low importance and silent: an established call should be visible and
 *     actionable without repeatedly interrupting.
 */
final class CallNotifier {

  private CallNotifier() {}

  // New channel: existing high-importance channels cannot be lowered programmatically.
  static final String CHANNEL_RINGING = "calls_ringing_v2";
  static final String CHANNEL_ACTIVE  = "calls_active";
  static final int NOTIFICATION_RINGING = 40;
  static final int NOTIFICATION_ACTIVE  = 41;

  // Service action constants, shared by the notification builders and MessengerService's
  // onStartCommand.  Each carries the call ID it applies to.
  static final String ACTION_ACCEPT   = "CALL_ACCEPT";
  static final String ACTION_DECLINE  = "CALL_DECLINE";
  static final String ACTION_CANCEL   = "CALL_CANCEL";
  static final String ACTION_HANGUP   = "CALL_HANGUP";
  static final String ACTION_MUTE     = "CALL_MUTE";
  static final String ACTION_RETURN   = "CALL_RETURN";
  static final String EXTRA_CALL_ID   = "call_id";

  /** Create both channels.  Idempotent. */
  static void createChannels(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return;
    manager.createNotificationChannel(new NotificationChannel(CHANNEL_RINGING,
      "Incoming calls", NotificationManager.IMPORTANCE_DEFAULT));
    manager.createNotificationChannel(new NotificationChannel(CHANNEL_ACTIVE,
      "Ongoing calls", NotificationManager.IMPORTANCE_LOW));
  }

  private static PendingIntent serviceAction(Context context, String action, String callId, int id) {
    Intent intent = new Intent(context, MessengerService.class)
      .setAction(action)
      .setData(android.net.Uri.parse("lanmsg-call://action/" + callId + "/" + action))
      .putExtra(EXTRA_CALL_ID, callId);
    return PendingIntent.getService(context, id, intent,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  private static PendingIntent openApp(Context context, String callId) {
    Intent open = new Intent(context, MainActivity.class)
      .setAction("OPEN_CALL")
      .putExtra(EXTRA_CALL_ID, callId)
      .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    return PendingIntent.getActivity(context, 0, open,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  /** Show the incoming-call notification with Accept and Decline.
   *
   *  Reached only after the controller has admitted the invitation, so a call that is refused by
   *  policy or by the admission limiter never rings, never notifies, and never allocates media. */
  static void showRinging(Context context, CallSession call, String peerName) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return;
    PendingIntent decline = serviceAction(context, ACTION_DECLINE, call.callId, 1);
    PendingIntent accept = serviceAction(context, ACTION_ACCEPT, call.callId, 2);
    String text = call.invitedVideo ? "Incoming video call" : "Incoming voice call";
    Notification.Builder builder = new Notification.Builder(context, CHANNEL_RINGING)
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setContentTitle(peerName)
      .setContentText(text)
      .setCategory(Notification.CATEGORY_CALL)
      .setPriority(Notification.PRIORITY_DEFAULT)
      .setVisibility(Notification.VISIBILITY_PRIVATE)
      .setContentIntent(openApp(context, call.callId))
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setAutoCancel(false);
    if (android.os.Build.VERSION.SDK_INT >= 31) {
      builder.setStyle(Notification.CallStyle.forIncomingCall(
        new android.app.Person.Builder().setName(peerName).build(), decline, accept)
        .setIsVideo(call.invitedVideo));
    } else {
      builder.addAction(new Notification.Action.Builder(null, "Decline", decline).build())
        .addAction(new Notification.Action.Builder(null, "Accept", accept).build());
    }
    // Retain lock-screen actions even when private caller details are redacted.
    Notification.Builder publicBuilder=new Notification.Builder(context,CHANNEL_RINGING)
      .setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("LAN Messenger call")
      .setContentText(text).setCategory(Notification.CATEGORY_CALL).setOngoing(true)
      .setContentIntent(openApp(context,call.callId));
    if(android.os.Build.VERSION.SDK_INT>=31){
      publicBuilder.setStyle(Notification.CallStyle.forIncomingCall(
        new android.app.Person.Builder().setName("LAN Messenger").build(),decline,accept)
        .setIsVideo(call.invitedVideo));
    }else{
      publicBuilder.addAction(new Notification.Action.Builder(null,"Decline",decline).build())
        .addAction(new Notification.Action.Builder(null,"Accept",accept).build());
    }
    builder.setPublicVersion(publicBuilder.build());
    Notification n = builder.build();
    ((MessengerService)context).postCallForeground(NOTIFICATION_RINGING,n);
    playRingtone(context);
  }

  /** Show or update the ongoing-call notification.  Silently replaces the ringing one. */
  static void showActive(Context context, CallSession call, String peerName) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    if (manager == null) return;
    stopRingtone(context);
    manager.cancel(NOTIFICATION_RINGING);

    String title = call.isCaller ? "Calling " + peerName : "Call with " + peerName;
    Notification.Builder b = new Notification.Builder(context, CHANNEL_ACTIVE)
      .setSmallIcon(android.R.drawable.stat_notify_chat)
      .setContentTitle(title)
      .setContentText(CallUi.stateLabel(call))
      .setCategory(Notification.CATEGORY_CALL)
      .setVisibility(Notification.VISIBILITY_PRIVATE)
      .setContentIntent(openApp(context, call.callId))
      .setOnlyAlertOnce(true)
      .setOngoing(call.state == CallProtocol.State.Connected)
      .addAction(new Notification.Action.Builder(null,
        call.state == CallProtocol.State.OutgoingRinging ? "Cancel" : "Hang up",
        serviceAction(context,
          call.state == CallProtocol.State.OutgoingRinging ? ACTION_CANCEL : ACTION_HANGUP,
          call.callId, 3)).build());
    if (call.state == CallProtocol.State.Connected) {
      b.addAction(new Notification.Action.Builder(null, call.muted ? "Unmute" : "Mute",
        serviceAction(context, ACTION_MUTE, call.callId, 4)).build());
    }
    ((MessengerService)context).postCallForeground(NOTIFICATION_ACTIVE,b.build());
  }

  /** Withdraw every call notification.  Called the moment a call reaches a terminal state, so a
   *  policy-driven decline leaves nothing behind. */
  static void clear(Context context) {
    NotificationManager manager = context.getSystemService(NotificationManager.class);
    stopRingtone(context);
    if (manager == null) return;
    manager.cancel(NOTIFICATION_RINGING);
    manager.cancel(NOTIFICATION_ACTIVE);
    MessengerService host=(MessengerService)context;
    host.clearCallForeground();
  }

  private static Ringtone ringtone;

  private static void playRingtone(Context context) {
    if (ringtone != null) return;
    try {
      Ringtone tone = RingtoneManager.getRingtone(context,
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
      if (tone == null) return;
      if (android.os.Build.VERSION.SDK_INT >= 21) {
        tone.setAudioAttributes(new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
      }
      tone.play();
      // Only stored once playback actually started, so a failure cannot leave a non-null field
      // that suppresses every later ring.
      ringtone = tone;
    } catch (Exception ignored) {
      // A missing or in-use default ringtone must not break the call itself.
      ringtone = null;
    }
  }

  private static void stopRingtone(Context context) {
    Ringtone current = ringtone;
    ringtone = null;
    if (current == null) return;
    try { current.stop(); } catch (Exception ignored) {}
  }
}
