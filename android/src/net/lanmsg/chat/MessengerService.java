package net.lanmsg.chat;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;

public class MessengerService extends Service {
  public volatile PeerEngine engine;
  public volatile String problem="";
  WifiManager.MulticastLock multicast;
  volatile boolean stopping;
  volatile String state="Offline";
  boolean requestedOnline, foreground;
  final IBinder binder=new LocalBinder();
  public class LocalBinder extends Binder {MessengerService host(){return MessengerService.this;}}

  // ── Call infrastructure (A03c, A03d) ──────────────────────────

  volatile CallController callController;
  volatile CallSettings callSettings;
  // A08-A10: the service-owned call view-model. The Activity binds to this for display; it never
  // creates one, and never owns the controller.
  volatile CallUi callUi;
  final AudioOwnership audioOwner = new AudioOwnership();
  final CallVideoResources callVideoResources = WebRtcCallMedia.newVideoResources();
  private volatile boolean callActivityVisible,cameraForeground;
  private volatile String cameraIntentCall;
  void callActivityVisible(boolean visible){
    callActivityVisible=visible;
    if(!visible){
      cameraForeground=false;cameraIntentCall=null;
      CallUi model=callUi;CallSession call=model==null?null:model.getCurrent();
      CallController cc=callController;
      if(cc!=null&&call!=null){CallVideoCoordinator video=cc.video(call.callId);if(video!=null)video.revokeCapture();}
      if(foreground)startForegroundSafely();
    }
  }
  private boolean cameraEligible(String expected){
    if(!callActivityVisible||!cameraForeground||!"Online".equals(state)||!callMediaReal)return false;
    if(checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED
        ||!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))return false;
    PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
    KeyguardManager guard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
    if(power==null||!power.isInteractive()||guard!=null&&guard.isKeyguardLocked())return false;
    if(Build.VERSION.SDK_INT>=29&&power.getCurrentThermalStatus()>=PowerManager.THERMAL_STATUS_SEVERE)return false;
    CallSession call=callUi==null?null:callUi.getCurrent();
    return expected==null||expected.equals(cameraIntentCall)||call!=null&&!call.state.terminal()&&expected.equals(call.callId);
  }
  /** Called from an explicit foreground camera action after permission. */
  boolean prepareCallCamera(String expected){
    if(!callActivityVisible||!"Online".equals(state)||checkSelfPermission(android.Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return false;
    cameraIntentCall=expected;cameraForeground=true;
    try{startForegroundSafely();}catch(RuntimeException denied){cameraForeground=false;cameraIntentCall=null;return false;}
    return cameraEligible(expected);
  }
  // Set by the A04 probe: true once the WebRTC native stack is confirmed usable on this device.
  volatile boolean callMediaReal;
  // Message shown once for the user when the native stack is unavailable, so a device that cannot
  // run WebRTC explains itself instead of appearing to place calls that never connect.
  volatile boolean callMediaWarningShown;

  // ── Call commands (A08-A10) ───────────────────────────────────

  /** Place a call.  Called from the Activity; the network work happens on the caller's thread,
   *  which is already a background thread in the Activity's call flow. */
  public void startCall(String peerId) throws java.io.IOException {
    PeerEngine peer = engine;
    CallController cc = callController;
    if (peer == null) throw new java.io.IOException("Local messages are still loading.");
    if (cc == null) throw new java.io.IOException("Calls are not available on this device.");
    if (!"Online".equals(state)) throw new java.io.IOException("Go online to call.");
    // A05: a call and a voice recording cannot both hold the microphone. Checked here so the
    // user gets a plain explanation rather than an exception from inside the controller lock.
    if (audioOwner != null && !audioOwner.canStartCall())
      throw new java.io.IOException("Cannot start a call while recording a voice message");
    // The controller mints the call ID and opens the channel for it inside its own lock, so the
    // INVITE can never be written before the connection that carries it exists. The factory is
    // named explicitly because a lambda matches both overloads.
    cc.startCall(peerId, (CallController.TransportFactory)
      callId -> CallChannel.openOutgoing(peer, peerId, callId, cc));
  }

  /** The call view-model, or null before the background load finishes. */
  public CallUi calls() { return callUi; }
  boolean canInviteVideo(String peerId){
    PeerEngine peer=engine;return peer!=null&&callMediaReal&&"Online".equals(state)&&peer.probeCallVideo(peerId);
  }
  void startVideoCall(String peerId,String id)throws java.io.IOException {
    PeerEngine peer=engine;CallController cc=callController;
    if(peer==null||cc==null||!cameraEligible(id))throw new java.io.IOException("Camera access is no longer available");
    try{cc.startCall(peerId,(CallController.TransportFactory)cid->CallChannel.openOutgoing(peer,peerId,cid,cc),true,id);}
    catch(java.io.IOException error){handler.post(()->{cameraForeground=false;cameraIntentCall=null;if(foreground)startForegroundSafely();});throw error;}
  }

  /** Notification actions and UI actions funnel through here.  Each is revalidated against the
   *  live session, so a stale action is refused rather than acting on whatever call came after. */
  void handleCallAction(String action, String callId) {
    CallController cc = callController;
    if (cc == null) return;
    try {
      switch (action) {
        case CallNotifier.ACTION_ACCEPT: {
          CallSession call = cc.snapshot();
          // A stale Accept cannot bypass policy: the controller re-checks the preference, and
          // the call ID must still match the invitation the action was raised for.
          if (call != null && callId != null && !callId.equals(call.callId)) {
            if (call.state == CallProtocol.State.IncomingRinging) cc.decline();
            return;
          }
          cc.accept(callId);
          break;
        }
        case CallNotifier.ACTION_DECLINE:
          requireSameCall(cc, callId);
          cc.decline();
          break;
        case CallNotifier.ACTION_CANCEL:
          requireSameCall(cc, callId);
          cc.cancel();
          break;
        case CallNotifier.ACTION_HANGUP:
          requireSameCall(cc, callId);
          cc.hangup();
          break;
        case CallNotifier.ACTION_MUTE: {
          requireSameCall(cc, callId);
          cc.setMute(!cc.isMuted());
          break;
        }
        default:
          return;
      }
    } catch (Exception ignored) {
      // The call ended between the notification being raised and the action arriving. The
      // terminal snapshot has already withdrawn the notification.
    }
  }

  /** Refuse an action that names a call other than the live one. */
  private void requireSameCall(CallController cc, String callId) throws java.io.IOException {
    CallSession call = cc.snapshot();
    if (call == null) throw new java.io.IOException("No call to act on");
    if (callId != null && !callId.equals(call.callId))
      throw new java.io.IOException("That call has ended");
  }

  /** Change the incoming-call preference.  A ringing invitation is withdrawn by the controller. */
  public void setAllowIncomingCalls(boolean value) throws java.io.IOException {
    CallController cc = callController;
    CallSettings settings = callSettings;
    if (cc != null) { cc.setAllowIncoming(value); return; }
    if (settings != null) settings.setAllowIncoming(value);
  }

  public boolean allowIncomingCalls() {
    CallSettings settings = callSettings;
    return settings == null || settings.allowIncoming();
  }

  public String callSettingsError() {
    CallSettings settings = callSettings;
    return settings == null ? null : settings.loadError();
  }

  /** Mute/unmute the live call.  Used by the in-call UI. */
  public void setCallMuted(boolean muted) throws java.io.IOException {
    CallController cc = callController;
    if (cc != null) cc.setMute(muted);
  }

  public boolean isCallMuted() {
    CallController cc = callController;
    return cc != null && cc.isMuted();
  }

  /** Apply an audio output route for the live call.
   *
   *  The choice is applied to the platform immediately and recorded on the session, so the route
   *  the call view shows is the route that is actually in use rather than a UI-side guess. Default
   *  is the earpiece: the speaker is a deliberate user action, never an automatic fallback. */
  public void setCallSpeaker(boolean speaker) throws java.io.IOException {
    CallController cc = callController;
    if (cc == null) throw new java.io.IOException("There is no call to route");
    CallSession call = cc.snapshot();
    if (call == null || !call.state.active())
      throw new java.io.IOException("There is no call to route");
    CallRoute.apply(this, speaker);
    cc.setAudioRoute(speaker ? "Speaker" : "Earpiece");
  }

  /** Display name for a call peer, falling back to a short ID when the engine is gone. */
  String callPeerName(String peerId) {
    PeerEngine peer = engine;
    if (peer == null) return peerId;
    try {
      String name = peer.displayName(peerId);
      return name == null || name.isEmpty() ? peerId : name;
    } catch (Exception ignored) {
      return peerId;
    }
  }

  /** React to a call state change: keep the notification in step with the session.
   *
   *  The ringing notification exists only for an admitted incoming invitation, so a policy-driven
   *  decline never produced one, and an invitation withdrawn by turning the preference off
   *  reaches a terminal snapshot and is cleared immediately. */
  void onCallSnapshot(CallSession call) {
    if(call==null||call.state.terminal()||call.video!=null&&(call.video.phase==CallVideoConsent.Phase.Voice
        ||call.video.phase==CallVideoConsent.Phase.Ended||call.video.phase==CallVideoConsent.Phase.Video&&!call.video.localCamera)){
      if(cameraForeground){cameraForeground=false;cameraIntentCall=null;if(foreground)startForegroundSafely();}
    }
    if (call == null) { CallNotifier.clear(this); CallRoute.exitCallMode(this); return; }
    String name = callPeerName(call.peerId);
    switch (call.state) {
      case IncomingRinging:
        CallNotifier.showRinging(this, call, name);
        break;
      case OutgoingRinging:
      case Connecting:
        CallNotifier.showActive(this, call, name);
        break;
      case Connected:
        // The route is applied when media actually starts carrying audio, not while ringing: the
        // earpiece default applies to the call itself, and the speaker remains an explicit choice.
        CallRoute.apply(this, "Speaker".equals(call.audioRoute));
        CallNotifier.showActive(this, call, name);
        break;
      case Ending:
        CallNotifier.clear(this);
        CallRoute.exitCallMode(this);
        // A chat-visible call-history entry, Messenger-style -- purely local (see
        // PeerEngine.appendCallLog's comment), so both ends of the same call each log their own
        // side independently and nothing needs to be sent for the other device to see its own.
        if(engine!=null)try{engine.appendCallLog(call.peerId,call.isCaller,call.connectedAtMs>0,call.durationMs);}catch(Exception ignored){}
        break;
      default:
        break;
    }
  }

  final Handler handler=new Handler(Looper.getMainLooper());
  final Runnable update=new Runnable(){public void run(){if(foreground){getSystemService(NotificationManager.class).notify(1,notification());handler.postDelayed(this,5000);}}};
  Notification notification(){
    Intent open=new Intent(this,MainActivity.class);
    PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MessengerService.class).setAction("OFFLINE"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    String text=state+" · "+(engine==null?0:engine.pending())+" queued";
    // Append call state if active
    CallController cc = callController;
    if (cc != null) {
      CallSession call = cc.snapshot();
      if (call != null && !call.state.terminal()) {
        text = "📞 " + (call.isCaller ? "Calling " : "Incoming: ") +
               (engine != null ? engine.displayName(call.peerId) : call.peerId) +
               " · " + text;
      }
    }
    return new Notification.Builder(this,"connection").setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("LAN Messenger").setContentText(text).setContentIntent(content).setOngoing(true).addAction(new Notification.Action.Builder(null,"Go offline",stop).build()).build();
  }
  @Override public void onCreate(){super.onCreate();
    NotificationManager manager=getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("connection","Local connection",NotificationManager.IMPORTANCE_LOW));
    manager.createNotificationChannel(new NotificationChannel("messages","Messages",NotificationManager.IMPORTANCE_DEFAULT));
    // A10: call channels. Only the ringing channel may make sound.
    CallNotifier.createChannels(this);
    // ── Call settings (A03d): service-owned, loaded before call admission ──
    callSettings = new CallSettings(new java.io.File(getFilesDir(),"peer-data"));
    // A04's probe runs on the same background thread as the controller creation below, so
    // callMediaReal is settled before the factory is chosen. Loading the native library is
    // slow enough that it must not happen on the main thread.
    new Thread(()->{try{
      PeerEngine peer=new PeerEngine(new java.io.File(getFilesDir(),"peer-data"),Build.MODEL,new AndroidProtector());
      peer.sourceOpener=reference->{java.io.InputStream input=getContentResolver().openInputStream(android.net.Uri.parse(reference));if(input==null)throw new java.io.IOException("Source is unavailable");return input;};
      peer.destinationOpener=reference->{
        final ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(android.net.Uri.parse(reference),"rw");
        if(descriptor==null)throw new java.io.IOException("Cannot open download destination");
        final java.io.FileDescriptor fd=descriptor.getFileDescriptor();
        try{android.system.Os.lseek(fd,0,android.system.OsConstants.SEEK_SET);}catch(Exception failure){descriptor.close();throw new java.io.IOException("Choose a local folder that supports resumable downloads",failure);}
        return new DownloadDestination.FileHandle(){
          public long size()throws java.io.IOException{try{return android.system.Os.fstat(fd).st_size;}catch(Exception failure){throw new java.io.IOException(failure);}}
          public void position(long offset)throws java.io.IOException{try{android.system.Os.lseek(fd,offset,android.system.OsConstants.SEEK_SET);}catch(Exception failure){throw new java.io.IOException(failure);}}
          public void truncate(long size)throws java.io.IOException{try{android.system.Os.ftruncate(fd,size);}catch(Exception failure){throw new java.io.IOException(failure);}}
          public int read(byte[] buffer,int count)throws java.io.IOException{try{int n=android.system.Os.read(fd,buffer,0,count);return n==0?-1:n;}catch(Exception failure){throw new java.io.IOException(failure);}}
          public void write(byte[] buffer,int count)throws java.io.IOException{try{int at=0;while(at<count){int n=android.system.Os.write(fd,buffer,at,count-at);if(n<=0)throw new java.io.IOException("Destination stopped accepting data");at+=n;}}catch(Exception failure){throw new java.io.IOException(failure);}}
          public void sync()throws java.io.IOException{try{android.system.Os.fsync(fd);}catch(Exception failure){throw new java.io.IOException(failure);}}
          public void close()throws java.io.IOException{descriptor.close();}
        };
      };
      peer.received=m->{
        String conversation=m.groupId.isEmpty()?m.from:m.groupId;String sender=peer.displayName(conversation);
        PendingIntent open=PendingIntent.getActivity(this,conversation.hashCode(),new Intent(this,MainActivity.class).setAction(conversation).putExtra("conversation",conversation).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,"messages").setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(sender).setContentText("New encrypted message").setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build();
        manager.notify((m.id.hashCode()&0x7ffffffc)+2,n);
      };
      peer.forgottenCallback=peerId->{
        String name=peer.displayName(peerId);
        PendingIntent open=PendingIntent.getActivity(this,peerId.hashCode(),new Intent(this,MainActivity.class).setAction(peerId).putExtra("conversation",peerId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,"messages").setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle(name).setContentText("Removed you as a contact. Verify again to keep chatting.").setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true).build();
        manager.notify((peerId.hashCode()&0x7ffffffc)+3,n);
      };
      synchronized(this){if(stopping){peer.close();return;}engine=peer;}
      // ── Call controller (A03c): exactly one, service-owned, no Activity reference ──
      // A04: real WebRTC media, installed once into the permanent service host. The probe loads
      // libjingle_peerconnection_so and exercises a real audio track, so a device that cannot
      // run it degrades to fake media instead of failing every call.
      WebRtcCallMedia.install(this,callVideoResources);
      callMediaReal = WebRtcCallMedia.probe(this);
      ICallMedia.Factory mediaFactory = new FakeCallMedia.Factory();
      if (callMediaReal) mediaFactory = new WebRtcCallMedia.Factory(this);
      CallController cc = new CallController(peer, callSettings);
      cc.setMediaFactory(mediaFactory);
      cc.setAudioOwner(audioOwner); // A05: shared with voice messages
      cc.configureVideo(callMediaReal,this::cameraEligible);
      peer.callVideoSupport=()->callMediaReal&&callController==cc&&!stopping;
      cc.start();
      callController = cc;
      // The service-owned UI view-model. It is the controller's primary callback, so the terminal
      // snapshot is retained even with no Activity bound; the Activity binds as an extra observer.
      callUi = new CallUi();
      callUi.bind(cc, callSettings);
      callUi.adoptAsPrimary();

      // A10: call notifications. Registered as an extra listener so it can never be displaced by
      // Activity binding. Snapshots arrive on the thread that produced the state change, so the
      // notification is always posted to the main thread.
      cc.addListener(snapshot -> handler.post(() -> onCallSnapshot(snapshot)));

      // ── Wire call handoff (A02) + revoke hook (A03) ──
      // A08-A10: the channel is owned by CallChannel, which pairs the socket with its single
      // reader thread and serialized writer, and tears both down together.
      peer.callHandler = (peerId, callId, socket) -> {
        CallChannel channel = CallChannel.adoptIncoming(socket, peerId, callId, cc);
        // The channel closes itself when the socket dies; the controller is told through
        // onSignalingChannelClosed() so an established call cannot outlive its signaling.
      };

      // End active call when verification is revoked
      peer.onRevoke = peerId -> {
        CallController ctrl = callController;
        if (ctrl != null) ctrl.onPeerRevoked(peerId);
      };
      handler.post(()->{if(requestedOnline){state="Offline";transition(true);}});
    }catch(Exception e){problem="Could not load local messaging: "+e.getMessage();state="Offline";handler.post(this::stopStartedOffline);}},"lan-security-start").start();
  }
  // An OS kill does not automatically restart networking. Activity startup reads the saved
  // preference and issues an explicit Online request; an Offline Activity only binds locally.
  // A10: notification actions arrive here too, and must not be read as an Online/Offline request.
  @Override public int onStartCommand(Intent intent,int flags,int startId){
    String action=intent==null?null:intent.getAction();
    if(CallNotifier.ACTION_ACCEPT.equals(action)||CallNotifier.ACTION_DECLINE.equals(action)
      ||CallNotifier.ACTION_CANCEL.equals(action)||CallNotifier.ACTION_HANGUP.equals(action)
      ||CallNotifier.ACTION_MUTE.equals(action)){
      handleCallAction(action,intent.getStringExtra(CallNotifier.EXTRA_CALL_ID));
      return START_NOT_STICKY;
    }
    transition(intent==null?getSharedPreferences("lan_messenger_connection",MODE_PRIVATE).getBoolean("default_online",true):!"OFFLINE".equals(action));
    return START_NOT_STICKY;
  }
  @Override public IBinder onBind(Intent intent){return binder;}
  @Override public boolean onUnbind(Intent intent){if(!"Online".equals(state))stopSelf();return true;}
  // The manifest declares both connectedDevice and microphone foreground-service types (the
  // latter for voice calls/messages), but Android 14+ throws a SecurityException and kills the
  // whole process if a microphone-typed FGS is started while RECORD_AUDIO is not currently
  // granted -- not merely declared in the manifest, actually granted at this moment. That
  // permission can go missing for reasons unrelated to anything the user did in this app: the OS
  // auto-revokes runtime permissions for an app that hasn't been opened in a while, and a fresh
  // install/reinstall always starts with nothing granted until the user records a voice message
  // or makes a call for the first time. Since going online must never depend on that, the
  // microphone type is only ever requested when RECORD_AUDIO is actually granted right now;
  // connectedDevice alone (this service's whole reason for being foreground at all) is always a
  // safe subset of what the manifest declares.
  void startForegroundSafely(){
    if(Build.VERSION.SDK_INT>=29){
      int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
      if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
      if(cameraForeground&&callActivityVisible&&checkSelfPermission(android.Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)
        type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
      try{startForeground(1,notification(),type);}
      catch(SecurityException denied){
        cameraForeground=false;cameraIntentCall=null;
        startForeground(1,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
      }
    }else startForeground(1,notification());
    foreground=true;
  }
  // All requests (notification and Activity) meet here; engine creation remains in onCreate only.
  synchronized void transition(boolean online){
    requestedOnline=online;
    getSharedPreferences("lan_messenger_connection",MODE_PRIVATE).edit().putBoolean("default_online",online).apply();
    if(online){
      // Socket teardown can take time while a transfer is active. If an Online request arrives
      // during that teardown, remember it and let the Offline worker restart cleanly afterwards.
      if("Stopping".equals(state)){
        if(!foreground){startForegroundSafely();handler.post(update);}
        return;
      }
      if(engine==null)state="Starting";
      if(!foreground){startForegroundSafely();handler.post(update);}
      if(engine==null)return;
      if("Online".equals(state)||"Starting".equals(state))return;
      state="Starting";problem="";
      new Thread(()->{try{
        WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        synchronized(this){if(!requestedOnline||stopping)return;if(wifi!=null){multicast=wifi.createMulticastLock("lan-messenger-discovery");multicast.setReferenceCounted(false);multicast.acquire();}engine.start();state="Online";}
      }catch(Exception error){synchronized(this){problem="Could not bind: "+error.getMessage();state="Offline";releaseMulticast();stopStartedOffline();}}},"lan-network-start").start();
    }else{
      if("Stopping".equals(state))return;
      state="Stopping";
      // Notify call controller of Offline transition
      CallController cc = callController;
      if (cc != null) cc.onOffline();
      // Never tear down live transfers on Android's main thread. Closing sockets wakes the
      // transfer workers and may briefly wait for their synchronized cleanup.
      new Thread(()->{
        PeerEngine peer;
        synchronized(this){peer=engine;}
        try{if(peer!=null)peer.goOffline();}
        catch(Exception error){synchronized(this){problem="Could not stop networking cleanly: "+error.getMessage();}}
        synchronized(this){
          releaseMulticast();state="Offline";
          if(requestedOnline&&!stopping){transition(true);return;}
          stopStartedOffline(); // A bound Activity keeps local data available while Offline.
        }
      },"lan-network-stop").start();
    }
  }
  // Clear the started-service lifetime after any actual-Offline failure. A bound Activity keeps
  // this instance available for local data and Retry online; after unbind Android may destroy it.
  void stopStartedOffline(){
    if(foreground){foreground=false;handler.removeCallbacks(update);stopForeground(true);getSystemService(NotificationManager.class).cancel(1);}
    stopSelf();
  }
  void releaseMulticast(){if(multicast!=null&&multicast.isHeld())multicast.release();multicast=null;}
  @Override public synchronized void onDestroy(){stopping=true;handler.removeCallbacksAndMessages(null);CallNotifier.clear(this);PeerEngine peer=engine;engine=null;CallController cc=callController;callController=null;callUi=null;if(cc!=null)cc.shutdown();callVideoResources.close();if(peer!=null)peer.close();releaseMulticast();if(foreground)stopForeground(true);super.onDestroy();}
}
