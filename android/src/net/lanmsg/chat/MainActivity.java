package net.lanmsg.chat;

import android.app.*;
import android.content.*;
import android.os.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import android.text.InputFilter;
import java.util.*;
import java.net.*;
import java.io.*;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.view.ViewTreeObserver;
import android.text.TextUtils;

public class MainActivity extends Activity {
  final Handler ui=new Handler(Looper.getMainLooper());
  final int ink=Color.rgb(17,27,33),accent=Color.rgb(37,211,102),headerDark=Color.rgb(7,94,84),chatBg=Color.rgb(236,229,221),bubbleMine=Color.rgb(220,248,198),seenBlue=Color.rgb(83,169,239),panelBg=Color.rgb(240,242,245);
  // ── Voice calls (A08-A10) ──
  // The Activity holds no call state of its own: it binds to the service-owned CallUi, reads
  // immutable snapshots, and issues commands. The call overlay lives on the stage, so it covers
  // the people list and the chat alike.
  CallUi callUi; String pendingCallPeerId,pendingCallAcceptId;
  String pendingVideoPeerId,pendingVideoCallId;
  Runnable pendingVideoMicAction;
  boolean pendingVideoMicGranted;
  static final int VIDEO_MIC_REQUEST=92;
  static final int ATTACHMENT_CAMERA_REQUEST=93;
  String pendingAttachmentCameraTarget;
  boolean pendingAttachmentCameraVideo,pendingAttachmentCameraGranted;
  boolean ensureAttachmentCamera(boolean video){
    if(checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED)return true;
    if(!active||selected==null||pendingAttachmentCameraTarget!=null)return false;
    pendingAttachmentCameraTarget=selected;pendingAttachmentCameraVideo=video;pendingAttachmentCameraGranted=false;
    requestPermissions(new String[]{android.Manifest.permission.CAMERA},ATTACHMENT_CAMERA_REQUEST);return false;
  }
  void finishAttachmentCamera(){
    if(!active||!pendingAttachmentCameraGranted)return;
    String target=pendingAttachmentCameraTarget;boolean video=pendingAttachmentCameraVideo;
    pendingAttachmentCameraTarget=null;pendingAttachmentCameraGranted=false;
    if(target!=null&&target.equals(selected)){if(video)AttachmentFlow.captureVideo(this);else AttachmentFlow.capturePhoto(this);}
  }
  final CallCameraPermission callCameraPermission=new CallCameraPermission();
  static final int CALL_CAMERA_REQUEST_FIRST=CallCameraPermission.REQUEST_FIRST,CALL_CAMERA_REQUEST_LAST=CallCameraPermission.REQUEST_LAST;
  int pendingCallCameraRequest=-1;
  long pendingCallCameraToken;
  Runnable pendingCallCameraReady;
  boolean callCameraResultReturned,callCameraResultGranted;
  final int[] namePalette={Color.rgb(233,30,99),Color.rgb(156,39,176),Color.rgb(63,81,181),Color.rgb(230,126,0),Color.rgb(0,137,123),Color.rgb(121,85,72),Color.rgb(216,67,21)};
  int nameColor(String id){int h=0;for(int i=0;i<id.length();i++)h=h*31+id.charAt(i);return namePalette[Math.abs(h)%namePalette.length];}
  LinearLayout root,chrome,body,feed,attachmentDraft; TextView status,heading; ScrollView scroll; EditText composer; Button send;
  String selected=null,lastSignature="",attachmentTarget=null,pendingOpen=null,pendingAttachmentName="",pendingAttachmentTarget=null; Uri cameraUri; File cameraFile; PeerEngine.Message exportMessage; boolean active; final Map<String,String> drafts=new HashMap<>();
  // The picked attachment's Uri and size, not its bytes — a 1 GB attachment is never fully read
  // into memory just to sit in the compose draft; it's streamed only once actually queued to send.
  Uri pendingAttachmentUri; long pendingAttachmentSize; File pendingCameraFile; boolean pendingFast,sendBusy;
  // Voice Messages (Phase 1 / Android), A04: one recorder app-wide, matching the shared
  // one-recorder rule. See VoiceUi.java for the actual logic; these fields are just state.
  String recordingDraftId,recordingConversation; VoiceRecorder activeRecorder; VoiceDraftWriter activeWriter;
  long recordingStartedAtMs; boolean recordingStopping; String lastVoicePanel="";
  // Only one persistent button lives in the compose action row (Record); Stop lives inside the
  // voice panel itself (VoiceUi.renderVoicePanel), next to the recording clock -- avoids adding a
  // second permanently-visible button to an already-crowded action row for a state that's only
  // ever active while that panel is showing anyway.
  Button recordVoice;
  // Voice Messages (Phase 1 / Android), A08: exactly one active player app-wide (contract.md
  // Decision 6). See VoicePlayback.java for the actual logic.
  VoicePlayer activePlayer; String activePlayerKey; Runnable activePlayerUiRefresh;
  final Map<String,TextView> progressLabels=new HashMap<>();
  static final long THUMBNAIL_PREVIEW_CAP=20*1024*1024;
  // (bytesDone, bytesTotal) per in-flight message id — transient, never persisted.
  final Map<String,long[]> transferProgress=new HashMap<>();
  final Map<String,Integer> visibleMessageCounts=new HashMap<>();
  final android.util.LruCache<String,Bitmap> thumbnailCache=new android.util.LruCache<String,Bitmap>(16*1024*1024){
    @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getByteCount();}
  };
  boolean loadingEarlier;
  // People-screen side menu: Profile, About, and the persisted offline-row/group-row filters. stage
  // is the single full-screen FrameLayout the overlay is added to; menuOverlay is null while closed.
  FrameLayout stage; View menuOverlay; boolean menuOpen; boolean showOffline; boolean hideGroups;
  Button refreshButton, addAddressButton;
  /** What the call bar was last built for, so it can be rebuilt when the words change rather than
   *  only when it appears or disappears.  Null while no call is live. */
  String callBarTag;
  MessengerService host;
  boolean bound;
  final ServiceConnection serviceConnection=new ServiceConnection(){
    public void onServiceConnected(ComponentName name,IBinder binder){host=((MessengerService.LocalBinder)binder).host();bound=true;bindCalls();render();}
    public void onServiceDisconnected(ComponentName name){unbindCalls();host=null;bound=false;render();}
  };
  PeerEngine engine(){return host==null?null:host.engine;}
  final RecipientControlVisibility recipientControls=new RecipientControlVisibility();
  void refreshRecipientControls(CallSession call){
    PeerEngine e=engine();
    String active=e!=null&&e.running&&call!=null&&call.isCaller&&call.videoCapable&&call.state==CallProtocol.State.Connected?call.callId:null;
    long token=recipientControls.begin(active,android.os.SystemClock.elapsedRealtime());
    if(token<0)return;String peerId=call.peerId;
    new Thread(()->{
      boolean success=e.refreshRemoteCallGrant(peerId);int mask=success?e.remoteControlDisplayMask(peerId):0;
      recipientControls.finish(active,token,mask,success,android.os.SystemClock.elapsedRealtime());
      ui.post(()->{if(!isDestroyed())render();});
    },"lan-recipient-permissions").start();
  }
  int recipientControlMask(CallSession call){
    PeerEngine e=engine();if(e==null||host==null||!"Online".equals(host.state)||call==null||!call.isCaller||call.state!=CallProtocol.State.Connected)return 0;
    return recipientControls.visible(call.callId,android.os.SystemClock.elapsedRealtime())&e.remoteControlDisplayMask(call.peerId);
  }
  void runRecipientControl(CallSession expected,int scope,CallAction action,String failure){
    runCallAction(()->{
      CallUi calls=host==null?null:host.calls();CallSession current=calls==null?null:calls.getCurrent();
      if(current==null||!expected.callId.equals(current.callId)||(recipientControlMask(current)&scope)==0)
        throw new java.io.IOException("The recipient has not confirmed this control permission.");
      action.run();
    },failure);
  }
  ViewTreeObserver.OnGlobalLayoutListener keyboardListener;
  final Runnable tick=new Runnable(){public void run(){VoiceUi.tickVoiceRecording(MainActivity.this);if(activePlayerUiRefresh!=null)activePlayerUiRefresh.run();render();if(active)ui.postDelayed(this,1000);}};
  int dp(int x){return (int)(x*getResources().getDisplayMetrics().density);}
  TextView label(String text,int size){TextView t=new TextView(this);t.setText(text);t.setTextColor(ink);t.setTextSize(size);t.setPadding(0,dp(6),0,dp(6));return t;}
  LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
  Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(accent);return b;}
  GradientDrawable circleBg(int c,int diameterDp){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(diameterDp)/2f);return d;}
  /** A round control carrying one glyph, used for the in-call toggles and for the list's call
   *  control.
   *
   *  <p>Where the glyph is a toggle, the on/off state is carried three ways at once -- the glyph
   *  changes, the fill changes from translucent to solid, and the accessibility label spells out
   *  which is in force -- because a single toggle whose state only lives in its icon is
   *  indistinguishable from one that does nothing when pressed. */
  Button dotButton(String glyph,String description,boolean on){return dotButton(glyph,description,on,56);}
  // A neutral light-gray circular icon button for the compose row (dotButton's translucent-white
  // "off" look is tuned for the call screen's dark background, invisible against this screen's
  // light one).
  Button composeIcon(String glyph,String description){Button b=new Button(this);b.setText(glyph);b.setAllCaps(false);b.setTextSize(18);b.setPadding(0,0,0,0);b.setGravity(android.view.Gravity.CENTER);b.setContentDescription(description);b.setBackground(circleBg(Color.rgb(241,242,245),44));b.setTextColor(ink);return b;}
  Button dotButton(String glyph,String description,boolean on,int diameterDp){Button b=new Button(this);b.setText(glyph);b.setAllCaps(false);b.setTextSize(diameterDp>=56?24:20);b.setPadding(0,0,0,0);b.setGravity(android.view.Gravity.CENTER);b.setContentDescription(description);b.setBackground(circleBg(on?accent:Color.argb(70,255,255,255),diameterDp));b.setTextColor(on?Color.WHITE:Color.rgb(214,224,232));return b;}
  TextView circle(String letter,int color,int diameterDp,int textSize){TextView t=new TextView(this);t.setText(letter);t.setTextColor(Color.WHITE);t.setTypeface(null,Typeface.BOLD);t.setTextSize(textSize);t.setGravity(android.view.Gravity.CENTER);t.setBackground(circleBg(color,diameterDp));return t;}
  /** The peer's name on the call screen's header bar: bold, uppercase, white, flush left.
   *
   *  <p>Uppercase is not decoration.  A call screen shows one name and nothing else to tell the
   *  two participants apart, and the reference call layout reads as a headline rather than as a
   *  form field -- which is precisely what was missing when this screen was a centred stack of
   *  labels and wide buttons. */
  TextView callTitle(String text){TextView t=new TextView(this);t.setText(text==null?"":text.toUpperCase(Locale.ROOT));t.setTextColor(Color.WHITE);t.setTypeface(null,Typeface.BOLD);t.setTextSize(24);t.setGravity(android.view.Gravity.LEFT|android.view.Gravity.CENTER_VERTICAL);t.setSingleLine(true);return t;}
  /** The end-call control: one large red disc carrying a hanging-up phone.
   *
   *  <p>The disc is rotated a quarter turn inside the disc, which is the hang-up symbol every
   *  phone uses.  There is no emoji for it: 📴 means "mobile phone off" and reads as a phone that is
   *  switched off rather than a call being ended, and it is a symbol rather than a picture, so it
   *  was unrecognisable as an action.  Everything else on the call screen is pale on dark, so red
   *  on its own disc reads as the single destructive action from across the desk; the
   *  accessibility label names it, so the glyph is never the only cue.
   *
   *  <p>A FrameLayout rather than a Button, because the rotation belongs to the glyph and a
   *  rotated Button would rotate its own bounds and its touch target with it. */
  View hangupCircle(String description,int diameterDp){FrameLayout wrap=new FrameLayout(this);
    wrap.setBackground(circleBg(Color.rgb(211,47,47),diameterDp));
    wrap.setClickable(true);wrap.setContentDescription(description);
    TextView glyph=new TextView(this);glyph.setText("📞");glyph.setAllCaps(false);glyph.setTextSize(30);
    glyph.setTextColor(Color.WHITE);glyph.setGravity(android.view.Gravity.CENTER);
    glyph.setRotation(135f);glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    FrameLayout.LayoutParams g=new FrameLayout.LayoutParams(-1,-1);g.setMargins(dp(6),dp(6),dp(6),dp(6));
    wrap.addView(glyph,g);return wrap;}
  EditText input(String hint,int max){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(17);e.setSingleLine(true);e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});return e;}
  GradientDrawable bg(int c){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(12));return d;}
  @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(headerDark);getWindow().setNavigationBarColor(Color.WHITE);pendingOpen=getIntent().getStringExtra("conversation");
    // The people rows are filtered by a persisted preference, so the first render has to wait for
    // the read instead of flashing unfiltered rows and then hiding them. Reading preferences
    // touches disk, so it stays off the UI thread. The saved Online request starts the service
    // after the first render; an Offline request binds locally without starting networking.
    new Thread(()->{final boolean offlineValue=PeopleListView.readShowOffline(this);final boolean hideGroupsValue=PeopleListView.readHideGroups(this);final boolean online=getSharedPreferences("lan_messenger_connection",MODE_PRIVATE).getBoolean("default_online",true);ui.post(()->{if(isDestroyed())return;showOffline=offlineValue;hideGroups=hideGroupsValue;showPeople();bindService(new Intent(this,MessengerService.class),serviceConnection,BIND_AUTO_CREATE);if(online)startConnection();});},"lan-ui-preference").start();
    requestNotificationPermissionOnce();}

  /** Whether this device may post notifications.  False on Android 13+ when POST_NOTIFICATIONS is
   *  not granted.  Used to refuse calls rather than start one that could never be announced. */
  boolean canPostNotifications(){
    if(Build.VERSION.SDK_INT<33)return true;
    return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED;
  }

  /** Ask for notification permission at most once per install.
   *
   *  This used to be requested on every launch, and its result was never handled.  That is worse
   *  than it sounds: once the user has declined, Android rate-limits and then silently auto-denies
   *  further requests, so the app asked forever, believed the permission was still pending, and
   *  notifications posted through NotificationManager were discarded with no error.  An incoming
   *  call therefore arrived, was admitted, and produced complete silence on the receiving device.
   *
   *  Now the request is made once.  A denial is remembered and never re-asked, so the app stops
   *  requesting something the system will not grant; {@link #canPostNotifications} reports the
   *  real state at the moment it matters. */
  void requestNotificationPermissionOnce(){
    if(Build.VERSION.SDK_INT<33||canPostNotifications())return;
    if(getPreferences(MODE_PRIVATE).getBoolean("notifications_asked",false))return;
    getPreferences(MODE_PRIVATE).edit().putBoolean("notifications_asked",true).apply();
    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_REQUEST);
  }

  void startConnection(){try{startForegroundService(new Intent(this,MessengerService.class).setAction("ONLINE"));}catch(Exception e){if(host!=null)host.problem="Could not start. Open the app and try again.";}}
  void setConnection(boolean online){if(online)startConnection();else if(host!=null)host.transition(false);lastSignature="";render();}
  // The header is built by each screen (showPeople/showChat) and inserted at chrome index 0, so a
  // space-constrained chat can use a single compact bar instead of always paying for the full app banner.
  // The content view is a single full-screen stage so the people side menu can overlay everything,
  // header bar included, without changing the chrome column that each screen already builds.
  void frame(){if(root!=null)releaseImages(root);PeopleListView.closeMenu(this);if(keyboardListener!=null){getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(keyboardListener);keyboardListener=null;}
    // The call overlay lives on the stage, which frame() replaces wholesale, so its reference must
    // be dropped with the old tree. The retained terminal snapshot survives on the view-model, so
    // the end reason is still shown by the next render.
    CallView.forgetOverlay();callBar=null;callBarTag=null;callBarSummary=null;
    stage=new FrameLayout(this);chrome=column();chrome.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);chrome.setBackgroundColor(panelBg);stage.addView(chrome,new FrameLayout.LayoutParams(-1,-1));setContentView(stage);
    LinearLayout content=column();content.setPadding(dp(18),dp(10),dp(18),dp(8));chrome.addView(content,new LinearLayout.LayoutParams(-1,0,1));root=content;
    status=label("Finding people on your network…",14);}
  void saveDraft(){if(selected!=null&&composer!=null)drafts.put(selected,composer.getText().toString());}
  void showPeople(){saveDraft();if(recordingConversation!=null)VoiceUi.stopVoiceRecording(this);VoicePlayback.stopActivePlayer(this);AttachmentFlow.clearPendingAttachment(this);selected=null;composer=null;lastSignature="";frame();
    LinearLayout headerBar=new LinearLayout(this);headerBar.setOrientation(LinearLayout.HORIZONTAL);headerBar.setGravity(android.view.Gravity.CENTER_VERTICAL);headerBar.setBackgroundColor(headerDark);headerBar.setPadding(dp(18),dp(14),dp(18),dp(14));
    TextView title=label("LAN Messenger",20);title.setTypeface(null,Typeface.BOLD);title.setTextColor(Color.WHITE);title.setPadding(0,0,0,0);headerBar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    Button menuButton=PeopleListView.barButton(this,"☰",20);menuButton.setContentDescription("Menu");menuButton.setOnClickListener(v->PeopleListView.openMenu(this));headerBar.addView(menuButton);
    chrome.addView(headerBar,0);
    // Return-to-call bar sits directly under the header, so a live call keeps its controls and a way
    // back even when the user is looking at the conversation list.
    buildCallBar();
    root.addView(status);
    LinearLayout tools=new LinearLayout(this);tools.setGravity(android.view.Gravity.CENTER_VERTICAL);
    FrameLayout avatarWrap=new FrameLayout(this);LinearLayout.LayoutParams avatarWrapParams=new LinearLayout.LayoutParams(dp(40),dp(40));avatarWrapParams.setMargins(0,0,dp(8),0);tools.addView(avatarWrap,avatarWrapParams);
    ImageView avatarView=new ImageView(this);avatarView.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);avatarView.setClipToOutline(true);
    PeerEngine ownEngine=engine();byte[] ownAvatar=ownEngine==null?null:ownEngine.avatar();
    Bitmap ownBitmap=ownAvatar==null?null:inlineBitmap(ownAvatar);
    if(ownBitmap!=null){avatarView.setImageBitmap(ownBitmap);avatarWrap.addView(avatarView);}
    else{avatarWrap.setBackground(circleBg(accent,40));String initial=ownEngine!=null&&!ownEngine.name.isEmpty()?ownEngine.name.substring(0,1).toUpperCase(Locale.ROOT):"?";TextView t=new TextView(this);t.setText(initial);t.setTextColor(Color.WHITE);t.setTypeface(null,Typeface.BOLD);t.setGravity(android.view.Gravity.CENTER);avatarWrap.addView(t,new FrameLayout.LayoutParams(-1,-1));}
    avatarWrap.setOnClickListener(v->changeAvatar());
    // Profile and About moved into the side menu to keep this row to the per-conversation actions.
     Button refresh=button("Refresh"),add=button("Add by IP");refreshButton=refresh;addAddressButton=add;tools.addView(refresh);tools.addView(add);Button group=button("New group");tools.addView(group);group.setOnClickListener(v->createGroup());HorizontalScrollView toolScroll=new HorizontalScrollView(this);toolScroll.setHorizontalScrollBarEnabled(false);toolScroll.addView(tools);root.addView(toolScroll);
    refresh.setOnClickListener(v->{PeerEngine e=engine();if(e!=null&&host!=null&&"Online".equals(host.state))new Thread(()->{try{e.announce();}catch(Exception ignored){}}).start();lastSignature="";render();});add.setOnClickListener(v->addAddress());
    root.addView(label("Conversations",20));scroll=new ScrollView(this);body=column();scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));root.addView(label("Saved contacts stay saved when hidden.\nOnly devices using LAN Messenger appear.",14));render();
  }
  // One compact bar (back + avatar + name/status + overflow) replaces the old stack of app-title
  // bar + a separate button row + a separate heading row, to leave more vertical room for the chat.
  /** The return-to-call strip, placed directly under whichever header is on screen.
   *
   *  <p>It used to be built into the people screen only, and the comment there claimed it "stays
   *  across the top of every screen" -- it did not. So a live call had no way back into the call
   *  screen once the user opened the conversation from it, which is precisely what the chat control
   *  on the call screen is for: the tap did what it said and then left the call with no controls at
   *  all, which is indistinguishable from the call having ended. Both screens carry it now, and
   *  renderCallBar() fills whichever one is present and keeps it hidden when there is no call. */
  void buildCallBar(){
    callBar=new LinearLayout(this);callBar.setOrientation(LinearLayout.HORIZONTAL);
    callBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
    callBar.setBackgroundColor(headerDark);callBar.setPadding(dp(18),dp(8),dp(12),dp(8));
    callBar.setVisibility(View.GONE);
    chrome.addView(callBar,1);
  }

  void showChat(String id){saveDraft();if(recordingConversation!=null&&!recordingConversation.equals(id))VoiceUi.stopVoiceRecording(this);VoicePlayback.stopActivePlayer(this);if(pendingAttachmentTarget!=null&&!pendingAttachmentTarget.equals(id))AttachmentFlow.clearPendingAttachment(this);selected=id;lastSignature="";visibleMessageCounts.putIfAbsent(id,10);frame();
    PeerEngine chatEngine=engine();boolean isGroup=false;String chatInitial="?";int chatColor=accent;String chatName="this device";
    if(chatEngine!=null){for(PeerEngine.Group g:chatEngine.groups())if(g.id.equals(id)){isGroup=true;chatColor=Color.rgb(156,124,224);chatInitial="G";chatName=g.name;}
      if(!isGroup)for(PeerEngine.Peer p:chatEngine.peers())if(p.id.equals(id)){chatColor=nameColor(p.id);chatInitial=p.name.isEmpty()?"?":p.name.substring(0,1).toUpperCase(Locale.ROOT);chatName=p.name;}}
    LinearLayout chatHeader=new LinearLayout(this);chatHeader.setOrientation(LinearLayout.HORIZONTAL);chatHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);chatHeader.setBackgroundColor(headerDark);chatHeader.setPadding(dp(2),dp(6),dp(10),dp(6));
    Button back=new Button(this);back.setText("‹");back.setAllCaps(false);back.setTextSize(24);back.setTextColor(Color.WHITE);back.setBackgroundColor(Color.TRANSPARENT);back.setMinWidth(dp(46));back.setOnClickListener(v->showPeople());chatHeader.addView(back);
    FrameLayout chatAvatar=new FrameLayout(this);chatAvatar.setBackground(circleBg(chatColor,36));TextView chatAvatarText=new TextView(this);chatAvatarText.setText(chatInitial);chatAvatarText.setTextColor(Color.WHITE);chatAvatarText.setTypeface(null,Typeface.BOLD);chatAvatarText.setGravity(android.view.Gravity.CENTER);chatAvatar.addView(chatAvatarText,new FrameLayout.LayoutParams(-1,-1));LinearLayout.LayoutParams chatAvatarParams=new LinearLayout.LayoutParams(dp(36),dp(36));chatAvatarParams.setMargins(0,0,dp(10),0);chatHeader.addView(chatAvatar,chatAvatarParams);
    heading=new TextView(this);heading.setTextColor(Color.WHITE);heading.setTypeface(null,Typeface.BOLD);heading.setTextSize(15);heading.setSingleLine(true);heading.setEllipsize(TextUtils.TruncateAt.END);chatHeader.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
    // The call control sits beside the name it calls, the way a messenger chat header carries it.
    // It used to be reachable only through the overflow menu, two taps in and easy to forget, which
    // is why the app looked like it had no way to call at all. It stays enabled for a device that
    // is offline, because then it says why rather than being silently dead. A group has no
    // one-to-one voice call, so it does not get one.
    if(!isGroup){
      final String callTarget=id;
      Button callNow=dotButton("📞","Call "+chatName,true,40);
      callNow.setOnClickListener(v->startCallTo(callTarget));
      Button videoNow=dotButton("📹","Video call "+chatName,true,40);
      videoNow.setOnClickListener(v->startVideoCallTo(callTarget));
      LinearLayout.LayoutParams callParams=new LinearLayout.LayoutParams(dp(40),dp(40));callParams.setMargins(0,0,dp(8),0);
      chatHeader.addView(callNow,callParams);
      chatHeader.addView(videoNow,new LinearLayout.LayoutParams(dp(40),dp(40)));
    }
    Button more=new Button(this);more.setText("⋮");more.setAllCaps(false);more.setTextSize(20);more.setTextColor(Color.WHITE);more.setBackgroundColor(Color.TRANSPARENT);more.setOnClickListener(v->chatMenu(more));chatHeader.addView(more);
    chrome.addView(chatHeader,0);
    // Same reason as the people screen: a live call must keep a way back into the call screen from
    // wherever the user is, and the chat control on the call screen is how they get here.
    buildCallBar();
    final boolean isGroupFinal=isGroup;
    final TextView[] noticeHolder={null};if(isGroupFinal){noticeHolder[0]=label("Group messages and their attachments are automatically deleted after 7 days of being sent.",12);noticeHolder[0].setTextColor(Color.rgb(112,128,144));root.addView(noticeHolder[0]);}
    scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(chatBg);feed=column();feed.setPadding(dp(6),dp(6),dp(6),dp(6));scroll.addView(feed);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
    scroll.setOnScrollChangeListener((View.OnScrollChangeListener)(view,x,y,oldX,oldY)->{
      if(loadingEarlier||y>dp(24)||oldY<=y||selected==null)return;
      PeerEngine engine=engine();if(engine==null)return;
      int current=visibleMessageCounts.getOrDefault(selected,10);
      if(engine.messages(selected).size()<=current)return;
      loadingEarlier=true;int previousHeight=feed.getHeight();visibleMessageCounts.put(selected,current+20);lastSignature="";render();
      scroll.post(()->{scroll.scrollTo(0,Math.max(0,feed.getHeight()-previousHeight));loadingEarlier=false;});
    });
    attachmentDraft=column();root.addView(attachmentDraft);composer=input("Write a message or caption…",2000);composer.setSingleLine(false);composer.setMaxLines(4);composer.setText(drafts.containsKey(id)?drafts.get(id):"");root.addView(composer);
    composer.addTextChangedListener(new android.text.TextWatcher(){
      @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
      @Override public void onTextChanged(CharSequence s,int start,int before,int count){}
      @Override public void afterTextChanged(android.text.Editable s){refreshSendIcon();}
    });
    // Messenger-style icon row: a "+" folding in the two less-common attachment actions (File,
    // Fast file), a camera icon offering a Photo/Video choice before handing off to the system
    // camera, and a gallery icon whose picker now accepts photos and videos alike (see
    // AttachmentFlow.pickFile's `photo` parameter, which really means "media" now). Voice
    // recording keeps its own always-visible icon rather than displacing Send the way Messenger's
    // own mic-in-the-send-slot does, since recording and sending are two independent actions here.
    LinearLayout composeActions=new LinearLayout(this);composeActions.setGravity(android.view.Gravity.CENTER_VERTICAL);
    Button plus=composeIcon("+","More attachment options");
    plus.setOnClickListener(v->{
      android.widget.PopupMenu menu=new android.widget.PopupMenu(this,plus);
      menu.getMenu().add(0,1,0,"File");menu.getMenu().add(0,2,0,"Fast file");
      menu.setOnMenuItemClickListener(item->{if(item.getItemId()==1)AttachmentFlow.pickFile(this,false);else AttachmentFlow.pickFastFile(this);return true;});
      menu.show();
    });
    Button cameraIcon=composeIcon("📷","Camera: take a photo or record a video");
    cameraIcon.setOnClickListener(v->AttachmentFlow.chooseCameraMode(this));
    Button galleryIcon=composeIcon("🖼","Choose a photo or video to send");
    galleryIcon.setOnClickListener(v->AttachmentFlow.pickFile(this,true));
    LinearLayout.LayoutParams plusParams=new LinearLayout.LayoutParams(dp(44),dp(44));plusParams.setMargins(0,0,dp(8),0);
    LinearLayout.LayoutParams cameraParams=new LinearLayout.LayoutParams(dp(44),dp(44));cameraParams.setMargins(0,0,dp(8),0);
    LinearLayout.LayoutParams galleryParams=new LinearLayout.LayoutParams(dp(44),dp(44));galleryParams.setMargins(0,0,dp(8),0);
    composeActions.addView(plus,plusParams);composeActions.addView(cameraIcon,cameraParams);composeActions.addView(galleryIcon,galleryParams);
    recordVoice=composeIcon("🎤","Record a voice message");
    LinearLayout.LayoutParams micParams=new LinearLayout.LayoutParams(dp(44),dp(44));composeActions.addView(recordVoice,micParams);
    recordVoice.setOnClickListener(v->VoiceUi.startVoiceRecording(this));
    // Paper-plane when there's something to send, thumbs-up otherwise -- Messenger's own quiet
    // nudge that an empty composer still has a one-tap action, though unlike Messenger's actual
    // like-message this app has no sticker/like message type, so tapping it while empty is a
    // harmless no-op via the same guard the click listener below already had.
    send=dotButton("➤","Send message",true,48);
    HorizontalScrollView actionScroll=new HorizontalScrollView(this);actionScroll.setHorizontalScrollBarEnabled(false);actionScroll.addView(composeActions);
    LinearLayout actionRow=new LinearLayout(this);actionRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
    actionRow.addView(actionScroll,new LinearLayout.LayoutParams(0,dp(52),1));
    LinearLayout.LayoutParams sendParams=new LinearLayout.LayoutParams(dp(48),dp(48));sendParams.setMargins(dp(8),0,0,0);
    actionRow.addView(send,sendParams);root.addView(actionRow);
    send.setOnClickListener(v->{
      PeerEngine e=engine();if(e==null){Toast.makeText(this,"Local messages are still loading.",Toast.LENGTH_SHORT).show();return;}
      if(pendingAttachmentUri==null&&composer.getText().toString().trim().isEmpty())return;
      String target=selected;String caption=composer.getText().toString();Uri uri=pendingAttachmentUri;long size=pendingAttachmentSize;String name=pendingAttachmentName;File cameraFile2=pendingCameraFile;boolean fastMode=pendingFast;
      sendBusy=true;send.setEnabled(false);composer.setEnabled(false);
      // Runs the actual encrypt+store (and, for a large file, real work) off the UI thread — this
      // used to happen synchronously in this click listener, which could freeze the app or trigger
      // an ANR on a large attachment.
      new Thread(()->{
        try{
          if(uri!=null){
            if(fastMode)e.queueFastFile(target,caption,uri.toString(),size,name);else try(InputStream in=getContentResolver().openInputStream(uri)){
              if(in==null)throw new IOException("Cannot open attachment");
              e.queueFileStream(target,caption,in,size,name,(done,tot)->ui.post(()->{if(status!=null&&tot>0)status.setText("Preparing to send… "+(done*100/tot)+"%");}));
            }
            if(cameraFile2!=null)cameraFile2.delete();
          }else e.queueLocal(target,caption);
          ui.post(()->{if(target.equals(selected)){composer.setText("");drafts.remove(target);}if(uri==pendingAttachmentUri)AttachmentFlow.clearPendingAttachment(this);sendBusy=false;send.setEnabled(true);refreshSendIcon();if(composer!=null)composer.setEnabled(true);lastSignature="";render();});
          if(uri==null)try{e.flush();}catch(Exception ignored){}
        }catch(Exception error){ui.post(()->{sendBusy=false;send.setEnabled(true);refreshSendIcon();if(composer!=null)composer.setEnabled(true);problem(error);});}
      },"lan-send").start();
    });AttachmentFlow.renderPendingAttachment(this);VoiceUi.renderVoicePanel(this);refreshSendIcon();render();
    // The header is now a single always-compact bar, so keyboard-open only needs to hide the group
    // notice and snap to the latest message — there is no separate button row left to collapse.
    View decor=getWindow().getDecorView();final boolean[] keyboardWasVisible={false};
    keyboardListener=()->{
      Rect visible=new Rect();decor.getWindowVisibleDisplayFrame(visible);int screenHeight=decor.getRootView().getHeight();int keypadHeight=screenHeight-visible.bottom;boolean keyboardVisible=keypadHeight>screenHeight*0.15;
      if(keyboardVisible==keyboardWasVisible[0])return;keyboardWasVisible[0]=keyboardVisible;
      if(noticeHolder[0]!=null)noticeHolder[0].setVisibility(keyboardVisible?View.GONE:View.VISIBLE);
      if(keyboardVisible)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
    };
    decor.getViewTreeObserver().addOnGlobalLayoutListener(keyboardListener);
  }
  PeerEngine transferProgressWiredFor;
  void render(){reconcileCallCameraPermission();if(root==null||status==null)return;boolean networkAvailable=host!=null&&"Online".equals(host.state);if(refreshButton!=null)refreshButton.setEnabled(networkAvailable);if(addAddressButton!=null)addAddressButton.setEnabled(networkAvailable);
    // The service only creates its CallUi once it builds the LAN stack, which is several seconds
    // after the Activity has already resumed and been connected to. bindCalls() therefore finds
    // null on both of its only two callers and gives up -- and nothing ever asked again, so for the
    // rest of the process this Activity had no call view-model at all. The incoming-call screen
    // still appeared, because CallView.render reads the service's instance directly, so the defect
    // presented as one dead button: Accept did nothing on every incoming call until the user left
    // and came back, and the notification's own Accept action was dead for as long as they did not.
    // Retried here, on the pass that already runs once a second, so the view-model is picked up as
    // soon as the service has one. bindCalls() returns immediately when there is nothing to do.
    if(callUi==null)bindCalls();
    // A09: the call overlay is refreshed on every pass, including the one-second tick, so the
    // duration clock advances and a snapshot change appears without any Activity-side state.
    renderCallBar();CallView.render(this);
    PeerEngine e=engine();if(e==null){status.setText(host==null?"Loading local messages…":host.problem.isEmpty()?host.state:host.problem);return;}
    if(e!=transferProgressWiredFor){
      // Attachment transfers run on background connection threads, not the UI thread — marshal
      // back to update the in-flight "Sending NN%" status shown on the message's own bubble.
      e.transferProgress=(id,done,total)->ui.post(()->{if(done>=total)transferProgress.remove(id);else transferProgress.put(id,new long[]{done,total});updateProgressLabels(e);});
      transferProgressWiredFor=e;
    }
    updateProgressLabels(e);
    if(pendingOpen!=null){String target=pendingOpen;pendingOpen=null;showChat(target);return;}
    if(selected!=null)try{e.markRead(selected);}catch(Exception ignored){}
    List<PeerEngine.Peer> people=e.peers();Collections.sort(people,(a,b)->a.online()==b.online()?a.name.compareToIgnoreCase(b.name):(a.online()?-1:1));int online=0;if("Online".equals(host.state))for(PeerEngine.Peer p:people)if(p.online())online++;status.setText((host.problem.isEmpty()?(e.directOnly()&&"Online".equals(host.state)?"Direct connections":host.state):host.state+" · "+host.problem)+" · "+online+" online · "+e.pending()+" queued · "+e.name);
    if(selected==null){
      // Most recently active conversation first, like a typical chat app — groups and contacts mixed
      // together by last message time, not name/online order. {lastActivity, isGroup, id, group-or-peer}
      List<Object[]> convos=new ArrayList<>();
      // Presentation-time filter only, same shape as the offline-peer filter below: skipped while
      // building the visible list, but the engine keeps the group, its messages, unread count and
      // queued sends untouched, and an incoming deep link or an already-open chat still reaches it.
      if(!hideGroups)for(PeerEngine.Group g:e.groups()){long last=0;for(PeerEngine.Message m:e.messages(g.id))if(m.time>last)last=m.time;convos.add(new Object[]{last,Boolean.TRUE,g.id,g});}
      // Presentation-time filter only. An offline direct peer is skipped while building the visible
      // list, but the engine keeps the peer, its messages, unread count and queued sends untouched,
      // and an incoming deep link or an already-open chat still reaches it. Group rows are never
      // filtered by this one, so a group whose members are all offline stays reachable.
       for(PeerEngine.Peer p:people){if(!showOffline&&"Online".equals(host.state)&&!p.online())continue;long last=0;for(PeerEngine.Message m:e.messages(p.id))if(m.time>last)last=m.time;convos.add(new Object[]{last,Boolean.FALSE,p.id,p});}
      convos.sort((x,y)->Long.compare((Long)y[0],(Long)x[0]));
      // The preferences are part of the signature because they also decide the empty-state hint,
      // which is a rendered row of its own and would otherwise survive a toggle with the wrong wording.
       StringBuilder signature=new StringBuilder(showOffline?"offline-shown:":"offline-hidden:").append(host.state).append(hideGroups?"groups-hidden:":"groups-shown:");for(Object[] c:convos){signature.append(c[2]).append(c[0]).append(e.unread((String)c[2]));if((Boolean)c[1]){PeerEngine.Group g=(PeerEngine.Group)c[3];signature.append(g.name).append(g.members.length).append(e.pendingOwnershipHandoff(g.id));}else{PeerEngine.Peer p=(PeerEngine.Peer)c[3];signature.append(p.name).append(p.online()).append(p.security());}}
      if(signature.toString().equals(lastSignature)&&body.getChildCount()>0)return;lastSignature=signature.toString();body.removeAllViews();
      if(convos.isEmpty()){
        if(people.isEmpty()&&e.groups().isEmpty())body.addView(label("No contacts yet.\n\nOpen LAN Messenger on another phone or computer connected to the same Wi-Fi. No host computer is needed.\n\nIf your router blocks discovery, use Add by IP.",17));
        else if(hideGroups&&!e.groups().isEmpty()&&(showOffline||online>0))body.addView(label("No conversations to show.\n\nGroups are hidden from this list. Open the menu and turn off Hide groups to see them again.",17));
        else body.addView(label(showOffline?"No conversations yet.\n\nOpen LAN Messenger on another phone or computer connected to the same Wi-Fi, or use Add by IP.":"No conversations to show.\n\nEvery contact is offline right now and hidden from this list, and groups may be hidden too.\n\nOpen the menu to turn on Show offline users or turn off Hide groups.",17));
      }
      for(Object[] c:convos){
        if((Boolean)c[1]){PeerEngine.Group g=(PeerEngine.Group)c[3];Button contact=button(g.name+"\nGroup · "+g.members.length+" members"+(e.pendingOwnershipHandoff(g.id)?" · Leaving…":""));contact.setGravity(android.view.Gravity.LEFT|android.view.Gravity.CENTER_VERTICAL);contact.setPadding(dp(14),dp(10),dp(14),dp(10));contact.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(245,243,250)));PeopleListView.addContactRow(this,contact,e.unread(g.id),"G",Color.rgb(156,124,224));contact.setOnClickListener(v->showChat(g.id));contact.setOnLongClickListener(v->{confirmDeleteConversation(g.id,g.name,true);return true;});}
         else{PeerEngine.Peer p=(PeerEngine.Peer)c[3];Button contact=button(p.name+"  ·  "+("Online".equals(host.state)&&p.online()?"Online":"Offline")+"\n"+p.id.substring(0,8)+" · "+p.security());contact.setGravity(android.view.Gravity.LEFT|android.view.Gravity.CENTER_VERTICAL);contact.setPadding(dp(14),dp(10),dp(14),dp(10));String initial=p.name.isEmpty()?"?":p.name.substring(0,1).toUpperCase(Locale.ROOT);PeopleListView.addContactRow(this,contact,e.unread(p.id),PeopleListView.peerAvatarView(this,e,p,initial));contact.setOnClickListener(v->showChat(p.id));contact.setOnLongClickListener(v->{confirmDeleteConversation(p.id,p.name,false);return true;});}
      }
      return;
    }
    PeerEngine.Peer peer=null;for(PeerEngine.Peer p:people)if(p.id.equals(selected))peer=p;PeerEngine.Group group=null;for(PeerEngine.Group g:e.groups())if(g.id.equals(selected))group=g;if(peer==null&&group==null)return;
    boolean leavingPending=group!=null&&e.pendingOwnershipHandoff(group.id);
    heading.setText(group!=null?group.name+" · "+group.members.length+" members"+(leavingPending?" · Leaving — waiting for members to catch up":""):peer.name+" · "+("Online".equals(host.state)&&peer.online()?"Online":"Offline")+" · "+peer.security());send.setEnabled(!sendBusy&&!leavingPending&&(group!=null||peer.trusted()));if(!sendBusy)refreshSendIcon();composer.setEnabled(!sendBusy&&!leavingPending);if(recordVoice!=null)recordVoice.setEnabled(send.isEnabled()&&recordingDraftId==null);List<PeerEngine.Message> messages=e.messages(selected);
    int visibleCount=visibleMessageCounts.getOrDefault(selected,10);int start=Math.max(0,messages.size()-visibleCount);
    StringBuilder signature=new StringBuilder(selected).append(start);for(int i=start;i<messages.size();i++){PeerEngine.Message m=messages.get(i);signature.append(m.id).append(m.status).append(e.hasAttachment(m)).append(e.downloading(m));}if(signature.toString().equals(lastSignature))return;lastSignature=signature.toString();boolean bottom=feed.getHeight()-scroll.getScrollY()-scroll.getHeight()<dp(120);releaseImages(feed);feed.removeAllViews();progressLabels.clear();
    if(messages.isEmpty())feed.addView(label("Verify this device before chatting.\n\nQueued messages send after both verified devices reconnect. Delivered means saved on the other device.",17));
    int maxBubble=Math.min(dp(320),(int)(getResources().getDisplayMetrics().widthPixels*0.78));
    byte[] ownAvatarRaw=e.avatar();Bitmap ownAvatarBmp=ownAvatarRaw==null?null:inlineBitmap(ownAvatarRaw);
    for(int messageIndex=start;messageIndex<messages.size();messageIndex++){PeerEngine.Message m=messages.get(messageIndex);
      // A call-history entry (PeerEngine.appendCallLog) renders as a centered system-style pill,
      // Messenger-style -- not a chat bubble: no sender name/avatar row, no delivery ticks, added
      // directly to the feed rather than through the mine/theirs bubble wrapper below.
      CallLogMarker.Info callInfo=CallLogMarker.tryParse(m.fileName);
      if(callInfo!=null){
        String peerName=peer!=null?peer.name:(group!=null?group.name:"them");
        String label=callInfo.isCaller
          ?("You called "+peerName+(callInfo.connected?" · "+formatCallDuration(callInfo.durationMs):""))
          :(callInfo.connected?(peerName+" called you · "+formatCallDuration(callInfo.durationMs)):"Missed call");
        boolean missed=!callInfo.isCaller&&!callInfo.connected;
        LinearLayout pillColumn=new LinearLayout(this);pillColumn.setOrientation(LinearLayout.VERTICAL);pillColumn.setGravity(android.view.Gravity.CENTER);
        TextView pill=label("📞  "+label,13);pill.setTextColor(missed?Color.rgb(211,47,47):Color.rgb(112,128,144));pill.setBackground(bg(Color.rgb(236,236,236)));pill.setPadding(dp(12),dp(6),dp(12),dp(6));
        pillColumn.addView(pill);
        TextView pillTime=label(android.text.format.DateFormat.format("MMM d, HH:mm",m.time).toString(),11);pillTime.setTextColor(Color.rgb(112,128,144));pillTime.setPadding(0,dp(2),0,0);pillTime.setGravity(android.view.Gravity.CENTER);
        pillColumn.addView(pillTime);
        LinearLayout pillRow=new LinearLayout(this);pillRow.setOrientation(LinearLayout.HORIZONTAL);pillRow.setGravity(android.view.Gravity.CENTER);pillRow.addView(pillColumn);
        LinearLayout.LayoutParams pillWrapParams=new LinearLayout.LayoutParams(-1,-2);pillWrapParams.setMargins(0,dp(5),0,dp(5));
        feed.addView(pillRow,pillWrapParams);
        continue;
      }
      boolean mine=m.from.equals(e.id);LinearLayout card=column();card.setPadding(dp(12),dp(6),dp(12),dp(8));card.setBackground(bg(mine?bubbleMine:Color.WHITE));
      LinearLayout whoRow=new LinearLayout(this);whoRow.setOrientation(LinearLayout.HORIZONTAL);whoRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
      String whoName=mine?"You":e.displayName(m.from);int whoColor=mine?accent:nameColor(m.from);
      LinearLayout.LayoutParams miniAvatarParams=new LinearLayout.LayoutParams(dp(18),dp(18));miniAvatarParams.setMargins(0,0,dp(6),0);
      byte[] peerAvatarRaw=mine?null:e.peerAvatar(m.from);Bitmap peerAvatarBmp=peerAvatarRaw==null?null:inlineBitmap(peerAvatarRaw);
      if(mine&&ownAvatarBmp!=null){ImageView miniAvatar=new ImageView(this);miniAvatar.setImageBitmap(ownAvatarBmp);miniAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);miniAvatar.setClipToOutline(true);whoRow.addView(miniAvatar,miniAvatarParams);}
      else if(peerAvatarBmp!=null){ImageView miniAvatar=new ImageView(this);miniAvatar.setImageBitmap(peerAvatarBmp);miniAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);miniAvatar.setClipToOutline(true);whoRow.addView(miniAvatar,miniAvatarParams);}
      else whoRow.addView(circle(whoName.isEmpty()?"?":whoName.substring(0,1).toUpperCase(Locale.ROOT),whoColor,18,9),miniAvatarParams);
      TextView who=label(whoName,14);who.setPadding(0,0,0,0);who.setTypeface(null,Typeface.BOLD);who.setTextColor(whoColor);whoRow.addView(who);
      card.addView(whoRow);if(m.fileName.isEmpty()){TextView text=label(m.text,17);text.setMaxWidth(maxBubble);text.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);text.setTextIsSelectable(true);card.addView(text);}else if(VoiceMarker.classify(m.fileName,"Normal",m.id).equals(VoiceMarker.CANDIDATE)){VoiceCard.addVoiceCard(this,card,m);if(!m.text.isEmpty()){TextView caption=label(m.text,17);caption.setMaxWidth(maxBubble);caption.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);caption.setTextIsSelectable(true);card.addView(caption);}}else{Bitmap thumbnail=inlineBitmap(e,m);boolean videoPreview=thumbnail==null&&PeerEngine.isVideoAttachment(m)&&e.hasAttachment(m);
      // Messenger-style: a usable preview is the whole card -- no filename/size line, no
      // button row underneath it. Only an attachment with no local preview (not yet
      // downloaded, over the image size cap, or an unsupported type) falls back to the
      // ordinary generic file card below.
      if(thumbnail!=null)MediaCard.addImageCard(this,card,m,thumbnail,maxBubble);
      else if(videoPreview)MediaCard.addVideoCard(this,card,m,maxBubble);
      else{TextView name=label(m.fileName+"  ·  "+formatSize(m.fileSize),15);name.setMaxWidth(maxBubble);name.setTextIsSelectable(true);name.setOnClickListener(v->{if(e.hasAttachment(m))AttachmentFlow.fileAction(this,m);});card.addView(name);LinearLayout fileActions=new LinearLayout(this);boolean available=e.hasAttachment(m);Button save=button(available?"Open":e.downloading(m)?"Pause":e.pendingDestination(m).isEmpty()?"Download":"Resume");fileActions.addView(save);save.setOnClickListener(v->AttachmentFlow.fileAction(this,m));card.addView(fileActions);}
      TextView progressLine=label("",13);card.addView(progressLine);progressLabels.put(m.id,progressLine);if(!m.text.isEmpty()){TextView caption=label(m.text,17);caption.setMaxWidth(maxBubble);caption.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);caption.setTextIsSelectable(true);card.addView(caption);}}String when=android.text.format.DateFormat.format("MMM d, HH:mm",m.time).toString();if(mine){boolean seen=m.status.startsWith("Seen");String ticks=seen||m.status.startsWith("Delivered")?"✓✓":"✓";String statusText=m.status;long[] progress=m.status.equals("Queued")&&!m.fileName.isEmpty()?transferProgress.get(m.id):null;if(progress!=null&&progress[1]>0)statusText="Sending "+(progress[0]*100/progress[1])+"%";TextView statusLine=label(when+"  ·  "+ticks+" "+statusText,13);statusLine.setTextColor(seen?seenBlue:Color.rgb(112,128,144));card.addView(statusLine);}else{TextView statusLine=label(when,13);statusLine.setTextColor(Color.rgb(112,128,144));card.addView(statusLine);}
      LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);View spacer=new View(this);
      if(mine){row.addView(spacer,new LinearLayout.LayoutParams(0,0,1));row.addView(card,new LinearLayout.LayoutParams(-2,-2));}else{row.addView(card,new LinearLayout.LayoutParams(-2,-2));row.addView(spacer,new LinearLayout.LayoutParams(0,0,1));}
      LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);rowParams.setMargins(0,dp(5),0,dp(5));feed.addView(row,rowParams);}
    updateProgressLabels(e);
    if(bottom&&!loadingEarlier)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
  }
  // Paper-plane once there's a message or attachment ready to go, thumbs-up otherwise -- called
  // on every composer keystroke and whenever the pending attachment is set or cleared, since
  // either one alone can make the difference between "nothing to send" and "something to send".
  void refreshSendIcon(){
    if(send==null)return;
    boolean hasContent=pendingAttachmentUri!=null||(composer!=null&&!composer.getText().toString().trim().isEmpty());
    send.setText(hasContent?"➤":"👍");
    send.setContentDescription(hasContent?"Send message":"Nothing to send yet");
  }
  void updateProgressLabels(PeerEngine e){
    if(selected==null)return;
    for(PeerEngine.Message m:e.messages(selected)){
      TextView view=progressLabels.get(m.id);if(view==null)continue;
      long[] progress=transferProgress.get(m.id);String note=e.downloadNote(m);
      if(e.downloading(m)){
        String text=progress!=null&&progress[1]>0?"Downloading "+(progress[0]*100/progress[1])+"%":"Downloading…";
        view.setText(note.isEmpty()?text:text+" · "+note);
      }else view.setText(e.hasAttachment(m)?"":note.isEmpty()?"Tap Download to receive this file":note);
    }
  }
  // Guarded by size: decoding an inline thumbnail means fully decrypting the attachment into
  // memory (readAttachment), which must stay off the table for anything near the 1 GB cap —
  // rendering a whole conversation's history would otherwise decrypt every large file in it.
  Bitmap inlineBitmap(PeerEngine engine,PeerEngine.Message message){if(!PeerEngine.isImageAttachment(message)||message.fileSize>THUMBNAIL_PREVIEW_CAP||!engine.hasAttachment(message))return null;String key=message.from+"/"+message.id+"/"+message.fileHash;Bitmap cached=thumbnailCache.get(key);if(cached!=null&&!cached.isRecycled())return cached;try{Bitmap bitmap=inlineBitmap(engine.readAttachment(message));if(bitmap!=null)thumbnailCache.put(key,bitmap);return bitmap;}catch(Exception ignored){return null;}}
  static String formatSize(long bytes){return bytes>=1024*1024?String.format(Locale.ROOT,"%.1f MB",bytes/1024.0/1024.0):String.format(Locale.ROOT,"%.1f KB",bytes/1024.0);}
  static String formatCallDuration(long ms){long s=Math.max(0,ms/1000);return s>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,(s%3600)/60,s%60):String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
  Bitmap inlineBitmap(byte[] bytes){try{BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth<=0||bounds.outHeight<=0||(long)bounds.outWidth*bounds.outHeight>32000000)return null;bounds.inSampleSize=1;while(bounds.outWidth/bounds.inSampleSize>1000||bounds.outHeight/bounds.inSampleSize>800)bounds.inSampleSize*=2;bounds.inJustDecodeBounds=false;return BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);}catch(Exception ignored){return null;}}
  void releaseImages(View view){if(view instanceof ImageView){((ImageView)view).setImageDrawable(null);}else if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++)releaseImages(group.getChildAt(i));}}
  void verifyDevice(){PeerEngine e=engine();if(selected==null)return;if(host==null||!"Online".equals(host.state)){Toast.makeText(this,"Go online to verify a device.",Toast.LENGTH_LONG).show();return;}if(e==null)return;PeerEngine.Peer peer=null;for(PeerEngine.Peer p:e.peers())if(p.id.equals(selected))peer=p;if(peer==null)return;final PeerEngine.Peer target=peer;
    try{final String code=e.pairingCode(target.id);StringBuilder formatted=new StringBuilder();for(int i=0;i<8;i++)formatted.append(code.substring(i*8,i*8+8)).append(i%2==0?" ":"\n");
      TextView text=label((target.keyChanged()?"KEY CHANGED. Check with this person before trusting their device again.\n\n":"Compare this entire safety code on BOTH devices in person or through a trusted channel.\n\n")+formatted+"\nOpen Verify device on the other device too. Confirm on each device only if every group matches.",16);text.setPadding(dp(20),dp(10),dp(20),dp(10));text.setTextIsSelectable(true);
      AlertDialog.Builder builder=new AlertDialog.Builder(this).setTitle("Verify device").setView(text).setNegativeButton("Cancel",null);
      if(!target.keyChanged())builder.setPositiveButton("Codes match — verify",(d,w)->{try{e.verify(target.id,code);render();}catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}});
      if(!target.verified.isEmpty())builder.setNeutralButton("Revoke",(d,w)->new AlertDialog.Builder(this).setTitle("Revoke verification?").setMessage("Messages stay queued until you compare and verify this device again.").setPositiveButton("Revoke",(d2,w2)->{try{e.revoke(target.id);render();}catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}}).setNegativeButton("Cancel",null).show());builder.show();
    }catch(Exception error){Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();}
  }

  void showPermissionDevices(boolean masters){
    LinearLayout panel=column();panel.setPadding(dp(16),dp(8),dp(16),dp(8));
    ScrollView scroll=new ScrollView(this);scroll.addView(panel);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle(masters?"Masters":"Slave").setView(scroll)
      .setPositiveButton("Refresh",null).setNegativeButton("Close",null).create();
    java.util.concurrent.atomic.AtomicBoolean querying=new java.util.concurrent.atomic.AtomicBoolean();
    Runnable draw=()->{
      panel.removeAllViews();panel.addView(label(masters?"Devices you granted permissions to.":"Devices that granted permissions to you. Only that device can change its grant.",15));
      PeerEngine e=engine();if(e==null){panel.addView(label("Devices are still loading.",14));return;}
      List<PeerEngine.Peer> people=e.peers();Collections.sort(people,(a,b)->a.name.compareToIgnoreCase(b.name));
      int shown=0,unknown=0;
      for(PeerEngine.Peer p:people){if(!p.trusted())continue;
        PeerEngine.RemoteCallGrant remote=masters?null:e.remoteCallGrant(p.id);
        int mask=masters?e.trustedCallMask(p.id):remote==null?0:remote.mask;
        if(!masters&&remote==null){unknown++;continue;}if(mask==0)continue;shown++;
        boolean online=e.running&&p.online();
        String text=p.name+" · "+(online?"Online":"Offline")+"\n"+permissionScopeLabel(mask);
        if(remote!=null)text+="\nLast confirmed: "+new java.text.SimpleDateFormat("MMM d, HH:mm:ss",Locale.getDefault()).format(new Date(remote.checkedAt))
          +(!online||System.currentTimeMillis()-remote.checkedAt>30000?" (last-known; may have changed)":"");
        Button row=button(text);row.setOnClickListener(v->{
          if(masters)trustedCallAccess(p.id);
          else new AlertDialog.Builder(this).setTitle(p.name).setMessage("Permissions this device granted you:\n"+permissionScopeLabel(mask)+"\n\nIts local settings remain authoritative.")
            .setPositiveButton("Open conversation",(d,w)->{dialog.dismiss();showChat(p.id);}).setNegativeButton("Close",null).show();
        });panel.addView(row,new LinearLayout.LayoutParams(-1,-2));
      }
      if(shown==0)panel.addView(label(masters?"No devices have permissions from you.":"No devices have confirmed permissions for you.",15));
      if(!masters&&unknown>0)panel.addView(label(unknown+" verified device(s): permission status unknown. Offline or older devices may not support this query.",14));
      if(!masters)panel.addView(label("Last-known results are kept only for this app session. Refresh to check for changes. This list never activates a camera or starts a call.",13));
    };
    Runnable refresh=()->{
      draw.run();PeerEngine e=engine();if(e==null||!e.running||!querying.compareAndSet(false,true))return;
      new Thread(()->{try{for(PeerEngine.Peer p:e.peers()){if(!dialog.isShowing())break;if(p.trusted()&&p.online())e.refreshRemoteCallGrant(p.id);}}
        finally{querying.set(false);ui.post(()->{if(dialog.isShowing())draw.run();});}},"lan-permission-status").start();
    };
    Runnable tick=new Runnable(){public void run(){if(!dialog.isShowing()||isFinishing())return;refresh.run();ui.postDelayed(this,5000);}};
    dialog.setOnDismissListener(d->ui.removeCallbacks(tick));dialog.setOnShowListener(d->{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->refresh.run());tick.run();});dialog.show();
  }
  static String permissionScopeLabel(int mask){
    List<String> scopes=new ArrayList<>();if((mask&1)!=0)scopes.add("Voice auto-answer");if((mask&2)!=0)scopes.add("Video auto-answer");
    if((mask&4)!=0)scopes.add("Camera control (front/rear)");if((mask&8)!=0)scopes.add("Speaker control");return String.join(" · ",scopes);
  }
  void trustedCallAccess(){trustedCallAccess(selected);}
  void trustedCallAccess(String peerId){PeerEngine e=engine();if(e==null||peerId==null)return;PeerEngine.Peer peer=null;for(PeerEngine.Peer p:e.peers())if(p.id.equals(peerId))peer=p;if(peer==null)return;if(!peer.trusted()){Toast.makeText(this,"Verify this device first.",Toast.LENGTH_LONG).show();return;}final PeerEngine.Peer target=peer;int mask=e.trustedCallMask(target.id);
    LinearLayout panel=column();panel.setPadding(dp(20),dp(8),dp(20),0);panel.addView(label("Trusted calls from this verified device can connect immediately. Your microphone may activate; the ongoing call notification, mute and hang-up controls remain available.",15));
    CheckBox voice=new CheckBox(this);voice.setText("Automatically answer voice calls");voice.setChecked((mask&PeerEngine.TRUSTED_AUTO_ANSWER_VOICE)!=0);panel.addView(voice);
    CheckBox video=new CheckBox(this);video.setText("Automatically accept video and open my camera");video.setChecked((mask&PeerEngine.TRUSTED_AUTO_ANSWER_VIDEO)!=0);panel.addView(video);
    CheckBox speaker=new CheckBox(this);speaker.setText("Allow this device to control my speaker");speaker.setChecked((mask&PeerEngine.TRUSTED_REMOTE_SPEAKER)!=0);panel.addView(speaker);
    CheckBox camera=new CheckBox(this);camera.setText("Allow this device to control my camera and switch front/rear");camera.setChecked((mask&PeerEngine.TRUSTED_REMOTE_CAMERA)!=0);panel.addView(camera);
    TextView note=label("Trusted calls can answer in the background or while locked. Starting a new camera session requires this app visible and unlocked; an already prepared camera can continue with the ongoing call notification. Android permission restrictions still apply.",13);note.setTextColor(Color.DKGRAY);panel.addView(note);
    new AlertDialog.Builder(this).setTitle("Trusted call access").setView(panel).setNegativeButton("Cancel",null).setPositiveButton("Save",(d,w)->{int next=(voice.isChecked()?PeerEngine.TRUSTED_AUTO_ANSWER_VOICE:0)|(video.isChecked()?PeerEngine.TRUSTED_AUTO_ANSWER_VIDEO:0)|(speaker.isChecked()?PeerEngine.TRUSTED_REMOTE_SPEAKER:0)|(camera.isChecked()?PeerEngine.TRUSTED_REMOTE_CAMERA:0);try{e.setTrustedCallMask(target.id,next);render();Toast.makeText(this,next==0?"Trusted call access is off.":"Trusted call access saved.",Toast.LENGTH_LONG).show();}catch(Exception error){problem(error);}}).show();
  }

  // A return-to-call bar, shown on the people screen while a call is live. A call is not tied to a
  // conversation, so leaving the chat must not hide the only controls for ending it. Only the
  // people screen gets it: inside a chat the call overlay is already on top.
  LinearLayout callBar;

  /** The bar's summary line, kept across renders so the 1 Hz clock updates text in place.
   *
   *  <p>Null means "the bar has no children": set after a build, and cleared when the bar goes away
   *  so the next call builds its own buttons rather than reusing the previous peer's, whose Return
   *  and End would then be labelling the wrong person. */
  TextView callBarSummary;

  void renderCallBar(){
    if(callBar==null||root==null)return;
    CallUi ui=callUi;
    CallSession call=ui==null?null:ui.getCurrent();
    boolean live=call!=null&&!call.state.terminal();
    // The bar used to stop as soon as it was the right visibility, so the text was frozen at
    // whatever the state was when it first appeared: a call that had been connected for a minute
    // still offered "Incoming call" beside a running timer, which is the one thing the bar exists
    // to report.  Rebuild when the words change OR when the bar appears or disappears -- checking
    // only one of the two leaves the other one stale, which is how it stayed stale to begin with.
    // Keyed on the words actually rendered, not on call.state.  The state is an enum, so it stopped
    // changing the moment the call connected, while the summary beside it is the duration and
    // changes every second: the bar was built once at Connecting->Connected and then frozen at the
    // first "0:00" it ever showed, for as long as the call ran.
    String want=live?CallUi.callBarWords(call):null;
    boolean visibilityChanged=callBar.getVisibility()!=(live?View.VISIBLE:View.GONE);
    boolean wordsChanged=callBarTag==null?want!=null:!callBarTag.equals(want);
    if(!visibilityChanged&&!wordsChanged)return;
    callBarTag=want;
    if(live){
      MessengerService service=host;
      String name=service==null?call.peerId:service.callPeerName(call.peerId);
      if(callBarSummary==null){
        callBar.removeAllViews();
        callBarSummary=label("",15);callBarSummary.setTextColor(Color.WHITE);
        callBar.addView(callBarSummary,new LinearLayout.LayoutParams(0,-2,1));
        Button returnToCall=button("Return");returnToCall.setTextColor(Color.WHITE);
        returnToCall.setContentDescription("Return to the call with "+name);
        returnToCall.setOnClickListener(v->showCallOverlay());
        callBar.addView(returnToCall);
        Button endCall=button("End");endCall.setTextColor(Color.WHITE);
        endCall.setContentDescription("End the call with "+name);
        endCall.setOnClickListener(v->runCallAction(ui::hangup,"Could not end the call."));
        callBar.addView(endCall);
      }
      // Retargeted in place rather than rebuilt. The words change every second while connected --
      // that is the whole point of the bar -- and rebuilding meant removeAllViews plus three new
      // Buttons, four LayoutParams and a fresh content description allocated on the UI thread once
      // a second for as long as the call lasted. Only the first render after the bar appears
      // allocates; every one after it writes text.
      callBarSummary.setText(CallUi.stateLabel(call));
      callBarSummary.setContentDescription("Call with "+name+" in progress. "+CallUi.stateLabel(call));
    }
    if(callBarSummary!=null&&!live)callBarSummary=null;
    callBar.setVisibility(live?View.VISIBLE:View.GONE);
  }

  // The words themselves are computed by CallUi.callBarWords, which is pure and so is reachable
    // from the Java test harness; keeping the string here would put it out of reach of the one
    // check that can prove it changes with the clock.
  /** Put the call overlay on top of whatever screen is showing. */
  void showCallOverlay(){
    // Lifts the dismissal recorded when the user left the call screen for the conversation.
    // Return-to-call *is* the act of un-dismissing, so it has to be the thing that lifts it:
    // render() holds the overlay down for as long as the dismissal stands.
    CallView.returnToCall();
    lastSignature="";
    CallView.render(this);
  }

  void chatMenu(View anchor){if(selected==null)return;PeerEngine e=engine();boolean isPeer=false;PeerEngine.Group group=null;
    if(e!=null){for(PeerEngine.Peer p:e.peers())if(p.id.equals(selected))isPeer=true;for(PeerEngine.Group g:e.groups())if(g.id.equals(selected))group=g;}
    final PeerEngine.Group selectedGroup=group;
    PopupMenu menu=new PopupMenu(this,anchor);
    // A08: the Call action exists only for a direct contact. Group calls are out of scope, and a
    // self-call is not a thing, so neither can offer it.
    if(isPeer)menu.getMenu().add("Call");
    if(isPeer)menu.getMenu().add("Verify device");
    if(isPeer)menu.getMenu().add("Trusted call access");
    // Group-only. It used to be added unconditionally, so a direct conversation offered "Group
    // members", and tapping it opened nothing at all: showMembers() looks for a group whose id
    // matches `selected`, finds none, and falls through to a toast telling the user the obvious.
    // There is nothing about a two-person conversation to list, and offering it invited the user to
    // tap a control that cannot work. Guarded on selectedGroup, not isGroup, so it also disappears
    // if the group is removed underneath us.
    if(selectedGroup!=null)menu.getMenu().add("Group members");
    menu.getMenu().add("Clear conversation");
    if(selectedGroup!=null)menu.getMenu().add("Leave group");
    // Each title is dispatched explicitly. This used to end in `else showMembers()`, so every
    // title that was not matched above silently ran the members dialog -- adding one item meant
    // forgetting to route it, and the wrong dialog opened instead of nothing.
    menu.setOnMenuItemClickListener(item->{String title=item.getTitle().toString();
      if(title.equals("Call"))startCallTo(selected);
      else if(title.equals("Clear conversation"))clearChat();
      else if(title.equals("Verify device"))verifyDevice();
      else if(title.equals("Trusted call access"))trustedCallAccess();
      else if(title.equals("Group members"))showMembers();
      else if(title.equals("Leave group"))confirmDeleteConversation(selectedGroup.id,selectedGroup.name,true);
      return true;});menu.show();}

  // ── Voice calls (A08-A10) ──────────────────────────────────────

  // Request code for the call microphone. Separate from VoiceUi's own so a grant cannot be mistaken
  // for a call permission or vice versa; each is checked for its own pending action.
  static final int CALL_MIC_REQUEST=7;

  // Request code for the one-time POST_NOTIFICATIONS request. It was previously hardcoded to 1,
  // which collided with nothing by accident but was never handled at all.
  static final int NOTIFICATION_REQUEST=91;

  /** Attach the Activity to the service-owned call view-model.  The service already bound it, so
   *  the Activity only adds an observer; rebinding is safe and cannot displace the notification,
   *  because observers live in the controller's listener list rather than its single callback slot. */
  void bindCalls(){
    if(host!=null)host.callActivityVisible(active);
    CallUi serviceUi=host==null?null:host.calls();
    if(serviceUi==null){unbindCalls();return;}
    if(callUi==serviceUi)return;
    unbindCalls();
    callUi=serviceUi;
    callUi.addObserver(this::onCallChanged);
    onCallChanged(callUi.getCurrent());
  }

  void unbindCalls(){
    clearCallCameraPermission();
    if(callUi!=null)callUi.removeObserver(this::onCallChanged);
    callUi=null;
  }

  /** Invalidate stale camera permission actions and the render signature; tick repaints. */
  void onCallChanged(CallSession snapshot){
    if(snapshot!=null&&pendingVideoCallId!=null&&!pendingVideoCallId.equals(snapshot.callId)){
      pendingVideoPeerId=null;pendingVideoCallId=null;pendingVideoMicAction=null;
    }
    callCameraPermission.reconcile(CallUi.cameraPermissionCallId(snapshot),host!=null&&"Online".equals(host.state));
    ui.post(this::reconcileCallCameraPermission);
    lastSignature="";
  }

  String currentCallCameraId(){
    CallUi live=callUi!=null?callUi:(host==null?null:host.calls());
    String current=CallUi.cameraPermissionCallId(live==null?null:live.getCurrent());
    return current==null&&pendingVideoPeerId!=null?pendingVideoCallId:current;
  }
  boolean hasCallCameraHardware(){
    return getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY);
  }
  /** Rechecked by A04/A05 at every eventual capture attempt, separately from microphone access. */
  boolean hasCurrentCallCameraPermission(String expectedCallId){
    return expectedCallId!=null&&expectedCallId.equals(currentCallCameraId())
      &&host!=null&&"Online".equals(host.state)&&active&&hasCallCameraHardware()
      &&checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED;
  }
  void clearCallCameraPermission(){
    callCameraPermission.cancel();pendingCallCameraReady=null;pendingCallCameraRequest=-1;
    pendingCallCameraToken=0;callCameraResultReturned=false;callCameraResultGranted=false;
  }
  void reconcileCallCameraPermission(){
    if(host==null||!"Online".equals(host.state)){pendingVideoPeerId=null;pendingVideoCallId=null;}
    callCameraPermission.reconcile(currentCallCameraId(),host!=null&&"Online".equals(host.state));
    if(!callCameraPermission.hasPending())clearCallCameraPermission();
  }
  /** A03 permission plumbing. A04 will supply explicit video actions after A02b.
   * A grant only resumes that action; its controller/media must still check bilateral consent. */
  void requestCallCameraPermission(String expectedCallId,Runnable permissionReady){
    if(permissionReady==null||!active||isDestroyed())return;
    reconcileCallCameraPermission();
    boolean granted=checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED;
    boolean asked=getSharedPreferences("call_camera_permission",MODE_PRIVATE).getBoolean("asked",false);
    CallCameraPermission.Decision decision=callCameraPermission.begin(expectedCallId,currentCallCameraId(),
      host!=null&&"Online".equals(host.state),hasCallCameraHardware(),granted,
      asked&&!shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA));
    if(decision==CallCameraPermission.Decision.Busy||decision==CallCameraPermission.Decision.Stale)return;
    if(decision==CallCameraPermission.Decision.Unavailable||decision==CallCameraPermission.Decision.Denied){
      showCallCameraPermissionFallback(decision);return;
    }
    pendingCallCameraReady=permissionReady;pendingCallCameraToken=callCameraPermission.token();
    if(decision==CallCameraPermission.Decision.Ready){
      callCameraResultReturned=true;callCameraResultGranted=true;finishCallCameraPermission();return;
    }
    // Never reuse a process request code, including across Activity recreation.
    pendingCallCameraRequest=CallCameraPermission.nextRequestCode();
    if(pendingCallCameraRequest<0){clearCallCameraPermission();showCallCameraPermissionFallback(CallCameraPermission.Decision.Denied);return;}
    try{requestPermissions(new String[]{android.Manifest.permission.CAMERA},pendingCallCameraRequest);}
    catch(RuntimeException error){clearCallCameraPermission();showCallCameraPermissionFallback(CallCameraPermission.Decision.Denied);}
  }
  void showCallCameraPermissionFallback(CallCameraPermission.Decision decision){
    pendingVideoPeerId=null;pendingVideoCallId=null;
    String message=decision==CallCameraPermission.Decision.Unavailable?
      "No camera is available. You can keep talking.":
      "Camera access is off. You can keep talking, or enable camera access in Android settings.";
    Toast.makeText(this,message,Toast.LENGTH_LONG).show();
  }
  void finishCallCameraPermission(){
    if(!active||!callCameraResultReturned)return;
    Runnable ready=pendingCallCameraReady;String expected=callCameraPermission.callId();
    CallCameraPermission.Decision decision=callCameraPermission.complete(pendingCallCameraToken,currentCallCameraId(),
      host!=null&&"Online".equals(host.state),hasCallCameraHardware(),
      callCameraResultGranted&&checkSelfPermission(android.Manifest.permission.CAMERA)==android.content.pm.PackageManager.PERMISSION_GRANTED);
    clearCallCameraPermission();
    if(decision==CallCameraPermission.Decision.Ready&&ready!=null&&hasCurrentCallCameraPermission(expected))ready.run();
    else if(decision==CallCameraPermission.Decision.Denied||decision==CallCameraPermission.Decision.Unavailable)
      showCallCameraPermissionFallback(decision);
  }

  /** Run a call command on a background thread and report failures in plain language.  Call actions
   *  touch the socket and the media stack, so none of them may run on the UI thread. */
  void runCallAction(CallAction action,String failureMessage){
    new Thread(()->{try{action.run();ui.post(()->{lastSignature="";render();});}
      catch(final Exception error){ui.post(()->Toast.makeText(this,error.getMessage()==null?failureMessage:error.getMessage(),Toast.LENGTH_LONG).show());}},"lan-call-action").start();
  }

  interface CallAction{void run() throws Exception;}
  void requireCallMicrophone(Runnable action){
    if(!active||action==null)return;
    if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED){action.run();return;}
    if(pendingVideoMicAction!=null)return;
    pendingVideoMicAction=action;pendingVideoMicGranted=false;
    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},VIDEO_MIC_REQUEST);
  }
  void finishVideoMicrophone(){
    if(!active||!pendingVideoMicGranted)return;
    Runnable action=pendingVideoMicAction;pendingVideoMicAction=null;pendingVideoMicGranted=false;
    if(host!=null)host.startForegroundSafely();if(action!=null)action.run();
  }
  void startVideoCallTo(String peerId){
    if(host==null||!active||!"Online".equals(host.state))return;
    MessengerService service=host;
    runCallAction(()->{
      if(!service.canInviteVideo(peerId))throw new java.io.IOException("This contact supports voice calls only.");
      ui.post(()->{
        if(!active||host!=service||callUi!=null&&callUi.getCurrent()!=null&&!callUi.getCurrent().state.terminal())return;
        if(callCameraPermission.hasPending()||pendingVideoMicAction!=null)return;
        pendingVideoPeerId=peerId;pendingVideoCallId=java.util.UUID.randomUUID().toString();
        String id=pendingVideoCallId;
        requireCallMicrophone(()->requestCallCameraPermission(id,()->{
          String target=pendingVideoPeerId;
          if(!id.equals(pendingVideoCallId)||target==null)return;
          if(!service.prepareCallCamera(id))return;
          pendingVideoPeerId=null;pendingVideoCallId=null;
          runCallAction(()->service.startVideoCall(target,id),"Could not place video call.");
        }));
      });
    },"Could not check video support.");
  }

  /** Place a call to a peer.  The microphone permission is obtained before inviting, and no
   *  capture starts until the peer accepts. */
  void startCallTo(String peerId){
    if(peerId==null)return;
    if(host==null||"Online".equals(host.state)==false){Toast.makeText(this,"Go online to call.",Toast.LENGTH_SHORT).show();return;}
    PeerEngine e=engine();
    if(e==null)return;
    PeerEngine.Peer target=null;for(PeerEngine.Peer p:e.peers())if(p.id.equals(peerId))target=p;
    if(target==null)return;
    // A verified peer is the admission rule; say why rather than failing inside the controller.
    if(!target.trusted()){Toast.makeText(this,"Verify this device before calling.",Toast.LENGTH_LONG).show();return;}
    if(!target.online()){Toast.makeText(this,target.name+" is offline.",Toast.LENGTH_SHORT).show();return;}
    if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
      pendingCallPeerId=peerId;
      requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},CALL_MIC_REQUEST);
      return;
    }
    placeCall(peerId);
  }

  void placeCall(final String peerId){
    if(host==null)return;
    // Blocking: opens an authenticated TLS channel and writes the first signaling frame.
    new Thread(()->{try{host.startCall(peerId);ui.post(()->{lastSignature="";render();});}
      catch(final Exception error){ui.post(()->Toast.makeText(this,error.getMessage()==null?"Could not place the call.":error.getMessage(),Toast.LENGTH_LONG).show());}},"lan-call-start").start();
  }

  /** Accept an incoming call.  Revalidated against the live preference and call ID by the
   *  controller, so a notification action raised for an earlier call cannot accept this one. */
  void acceptCall(final String callId){ acceptCall(null, callId); }

  /** Accept, acting on a named view-model.
   *
   *  <p>source is the instance that built the Accept button, and it is preferred over the field on
   *  purpose.  The button is on screen because *that* instance reported a ringing call, so it is the
   *  one that can act on it.  The field is a second, independently-bound reference to the same model
   *  which was routinely null on a cold start, and reading it instead made this button silently
   *  inert -- the single control on the incoming-call screen that could not be pressed -- with no
   *  error and no visible change, while Decline beside it worked because it captured the instance it
   *  was built with.  The call ID is still passed on, so the controller's revalidation is intact and
   *  a rebuilt view cannot accept a newer call.
   *
   *  <p>Failing to reach any view-model now says so.  A button that silently does nothing is
   *  indistinguishable from one that is broken. */
  void acceptCall(CallUi source, final String callId){
    CallUi target=CallUi.resolveForAccept(source,callUi,host==null?null:host.calls());
    if(target==null){Toast.makeText(this,"Calls are not available yet.",Toast.LENGTH_SHORT).show();return;}
    requireCallMicrophone(()->runCallAction(()->target.answerWithVoice(callId),"Could not accept the call."));
  }

  /** Change the incoming-call preference from the people menu.
   *
   *  Applied through the controller so a still-ringing invitation is declined immediately and its
   *  notification is withdrawn, rather than left to ring out its timeout.  A save failure keeps the
   *  in-memory choice and reports the problem; it does not silently revert the switch, which would
   *  then disagree with what the service is actually enforcing. */
  void setAllowIncomingCalls(final boolean value){
    MessengerService service=host;
    if(service==null){Toast.makeText(this,"Calls are still starting.",Toast.LENGTH_SHORT).show();return;}
    new Thread(()->{try{service.setAllowIncomingCalls(value);ui.post(()->{lastSignature="";render();});}
      catch(final Exception error){ui.post(()->{Toast.makeText(this,error.getMessage()==null?"Setting was not saved.":error.getMessage(),Toast.LENGTH_LONG).show();
        // The in-memory choice stands, so the switch stays where the user put it. Reopening the
        // menu re-reads it from the service, so the displayed and enforced values stay in step.
        closeMenuForPreference();});}},"lan-call-pref").start();
  }

  /** Rebuild the menu so the switch and any settings error reflect the service's live value. */
  void closeMenuForPreference(){
    PeopleListView.closeMenu(this);
    lastSignature="";
    render();
  }

  /** Toggle the speakerphone route for the live call.  The route is applied through AudioManager
   *  and recorded on the session, so what is displayed is what is actually selected. */
  void toggleSpeakerphone(CallSession call){
    if(host==null)return;
    final boolean toSpeaker=!"Speaker".equals(call.audioRoute);
    runCallAction(()->{host.setCallSpeaker(toSpeaker);},"Could not change the audio route.");
  }

  void clearChat(){final PeerEngine e=engine();final String target=selected;if(e==null||target==null)return;new AlertDialog.Builder(this).setTitle("Clear conversation?").setMessage("Remove messages and attachments from this device and cancel pending sends. Other devices keep their copies.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{try{e.clearConversation(target);drafts.remove(target);if(composer!=null)composer.setText("");AttachmentFlow.clearPendingAttachment(this);lastSignature="";render();}catch(Exception error){problem(error);}}).show();}
  void confirmDeleteConversation(String id,String name,boolean isGroup){
     PeerEngine e=engine();
    if(isGroup&&e!=null){
      PeerEngine.Group g=null;for(PeerEngine.Group candidate:e.groups())if(candidate.id.equals(id))g=candidate;
      if(g!=null&&g.owner.equals(e.id)){
        ArrayList<String> others=new ArrayList<>();for(String m:g.members)if(!m.equals(e.id))others.add(m);
        if(!others.isEmpty()){if(host==null||!"Online".equals(host.state)){Toast.makeText(this,"Go online before handing off this group.",Toast.LENGTH_LONG).show();return;}showTransferOwnershipPicker(g,others);return;}
      }
    }
    new AlertDialog.Builder(this).setTitle(isGroup?"Leave group?":"Delete conversation?")
      .setMessage(isGroup
        ?"Leave \""+name+"\"? You'll need a new invitation to rejoin. Other members keep the group and their own copies."
        :"Delete \""+name+"\" entirely? This removes the conversation and its attachments, and revokes verification. Seeing this device again on the network starts from an unverified state. Other devices keep their own copies.")
      .setNegativeButton("Cancel",null)
       .setPositiveButton(isGroup?"Leave":"Delete",(d,w)->{PeerEngine engine=engine();if(engine==null)return;try{engine.deleteConversation(id);drafts.remove(id);if(id.equals(selected)){selected=null;showPeople();}else{lastSignature="";render();}}catch(Exception error){problem(error);}})
      .show();
  }
  // Shown instead of the normal leave confirmation when the local user is this group's owner and
  // other members remain -- leaving requires handing off first. Confirming starts the handoff; the
  // actual departure completes automatically, in the background, once every other member has caught
  // up (see PLAN-GROUP-OWNERSHIP-TRANSFER.md), not immediately when this dialog closes.
  void showTransferOwnershipPicker(PeerEngine.Group g,ArrayList<String> others){
     PeerEngine e=engine();if(e==null)return;
    String[] names=new String[others.size()];for(int i=0;i<names.length;i++)names[i]=e.displayName(others.get(i));
    new AlertDialog.Builder(this).setTitle("Choose a new admin")
      .setMessage("You're the admin of \""+g.name+"\". Choose who takes over before you leave. You'll leave automatically, in the background, once they and everyone else have caught up.")
      .setItems(names,(d,which)->{
        String newOwnerId=others.get(which);
        new Thread(()->{try{e.transferOwnership(g.id,newOwnerId);ui.post(this::render);}catch(Exception error){ui.post(()->problem(error));}}).start();
      })
      .setNegativeButton("Cancel",null)
      .show();
  }
  void confirmDeleteAllData(){
    new AlertDialog.Builder(this).setTitle("Delete app data?")
      .setMessage("Permanently remove every conversation, contact, group and downloaded file on this device. Your profile name and picture are kept. This cannot be undone.")
      .setNegativeButton("Cancel",null)
       .setPositiveButton("Delete",(d,w)->{PeerEngine e=engine();if(e==null)return;try{e.deleteAllData();thumbnailCache.evictAll();drafts.clear();selected=null;showPeople();}catch(Exception error){problem(error);}})
      .show();
  }
  void showMembers(){
     PeerEngine e=engine();if(e==null)return;
    for(PeerEngine.Group g:e.groups())if(g.id.equals(selected)){
      boolean isOwner=g.owner.equals(e.id);
      LinearLayout list=column();list.setPadding(dp(4),dp(4),dp(4),dp(4));
      list.addView(label("Every pair must verify each other to exchange group messages.",13));
      for(PeerEngine.KnownMember known:e.allKnownMembers(g.id)){
        String id=known.id;
        boolean verified=false;for(PeerEngine.Peer person:e.peers())if(person.id.equals(id))verified=person.trusted();
        String status=id.equals(e.id)?" (you)":id.equals(g.owner)?" · Admin":verified?" · Verified":" · Verify in People";
        // Owner-only, and only meaningful for a currently-active member other than yourself -- never
        // implies the group as a whole is consistent, just whether this one member has caught up.
        String sync=isOwner&&known.active&&!id.equals(e.id)?(e.memberAckedVersion(g.id,id)>=g.membersVersion?" · Synced":" · Catching up"):"";
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(label(e.displayName(id)+status+sync+(known.active?"":" · Left"),15),new LinearLayout.LayoutParams(0,-2,1));
        if(isOwner&&!known.active){
          Button reinvite=button("Re-invite");final String memberId=id;final PeerEngine.Group group=g;
          reinvite.setOnClickListener(v->{if(host==null||!"Online".equals(host.state)){Toast.makeText(this,"Go online to re-invite.",Toast.LENGTH_SHORT).show();return;}reinvite.setEnabled(false);new Thread(()->{try{e.reinviteMember(group.id,memberId);ui.post(()->Toast.makeText(this,"Invite sent",Toast.LENGTH_SHORT).show());}catch(Exception error){ui.post(()->{problem(error);reinvite.setEnabled(true);});}}).start();});
          row.addView(reinvite);
        }
        list.addView(row);
      }
      ScrollView scroller=new ScrollView(this);scroller.addView(list);
      new AlertDialog.Builder(this).setTitle(g.name+" · Members").setView(scroller).setPositiveButton("Close",null).show();
      return;
    }
    Toast.makeText(this,"This is a direct conversation.",Toast.LENGTH_SHORT).show();
  }
   void createGroup(){final PeerEngine e=engine();if(e==null)return;final ArrayList<PeerEngine.Peer> peers=new ArrayList<>();for(PeerEngine.Peer p:e.peers())if(p.trusted())peers.add(p);if(peers.size()<2){Toast.makeText(this,"Verify at least two contacts first.",Toast.LENGTH_LONG).show();return;}
    final EditText name=input("Group name",50);final boolean[] checked=new boolean[peers.size()];String[] names=new String[peers.size()];for(int i=0;i<names.length;i++)names[i]=peers.get(i).name+" · "+peers.get(i).id.substring(0,6);
    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("New group · select 2–15 contacts").setView(name).setMultiChoiceItems(names,checked,(d,which,on)->checked[which]=on).setNegativeButton("Cancel",null).setPositiveButton("Create",null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{try{ArrayList<String> ids=new ArrayList<>();for(int i=0;i<checked.length;i++)if(checked[i])ids.add(peers.get(i).id);String id=e.createGroup(name.getText().toString(),ids);dialog.dismiss();showChat(id);}catch(Exception error){problem(error);}}));dialog.show();
  }
  void problem(Exception error){Toast.makeText(this,error.getMessage()==null?"Operation failed":error.getMessage(),Toast.LENGTH_LONG).show();}
   @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK){if((request==44||request==48)&&cameraFile!=null)cameraFile.delete();return;}final Uri uri=data==null?null:data.getData();final PeerEngine e=engine();if(e==null){Toast.makeText(this,"Local messages are still loading.",Toast.LENGTH_LONG).show();return;}
    final String target=attachmentTarget;final PeerEngine.Message exporting=exportMessage;final File captured=cameraFile;
    new Thread(()->{try{
      if(request==47&&exporting!=null&&uri!=null){
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        getContentResolver().takePersistableUriPermission(uri,flags);
        ui.postDelayed(()->render(),100);e.downloadTo(exporting,uri.toString());
        ui.post(()->{lastSignature="";render();});
      }
      else if(request==43&&exporting!=null&&uri!=null){
        try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){
          if(out==null)throw new IOException("Cannot open destination");
          e.readAttachmentStream(exporting,out,(done,tot)->ui.post(()->{if(status!=null&&tot>0)status.setText("Saving… "+(done*100/tot)+"%");}));
        }
        ui.post(()->{Toast.makeText(this,"File saved",Toast.LENGTH_SHORT).show();lastSignature="";render();});
      }
      else if((request==41||request==42||request==46)&&target!=null&&uri!=null){
        String name="attachment";long size=-1;
        try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
          if(cursor!=null&&cursor.moveToFirst()){name=cursor.getString(0);int sizeIndex=cursor.getColumnIndex(OpenableColumns.SIZE);if(sizeIndex>=0&&!cursor.isNull(sizeIndex))size=cursor.getLong(sizeIndex);}
        }
        if(name==null)name="attachment";
        boolean fastMode=request==46;long limit=fastMode?PeerEngine.MAX_FAST_FILE_SIZE:PeerEngine.MAX_FILE_SIZE;
        if(size>limit)throw new IOException(fastMode?"Fast transfer limit is 1 TiB.":"Files must be 1 GiB or smaller.");
        if(fastMode){getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);if(size<0){size=0;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Cannot open attachment");byte[] buffer=new byte[262144];int n;while((n=in.read(buffer))!=-1){size+=n;if(size>limit)throw new IOException("File too large");}}}AttachmentFlow.prepareAttachment(this,target,name,uri,size,null,true);}
        else if(size>=0)AttachmentFlow.prepareAttachment(this,target,name,uri,size,null);
        else{File tmp=File.createTempFile("attachment-",".tmp",getCacheDir());long count=0;try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(tmp)){if(in==null)throw new IOException("Cannot open attachment");byte[] buffer=new byte[262144];int n;while((n=in.read(buffer))!=-1){count+=n;if(count>limit)throw new IOException("File too large");out.write(buffer,0,n);}}catch(Exception error){tmp.delete();throw error;}AttachmentFlow.prepareAttachment(this,target,name,Uri.fromFile(tmp),count,tmp);}

      }
      else if((request==44||request==48)&&target!=null&&captured!=null){
        boolean video=request==48;
        String name=(video?"Video-":"Photo-")+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+(video?".mp4":".jpg");
        AttachmentFlow.prepareAttachment(this,target,name,Uri.fromFile(captured),captured.length(),captured);
      }
      else if(request==45&&uri!=null){byte[] raw;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Cannot open image");raw=AttachmentFlow.readLimited(in);}Bitmap bitmap=inlineBitmap(raw);if(bitmap==null)throw new IOException("This file is not a supported image.");ByteArrayOutputStream png=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,90,png);e.setAvatar(png.toByteArray());bitmap.recycle();ui.post(()->{Toast.makeText(this,"Profile picture updated",Toast.LENGTH_SHORT).show();if(selected==null)showPeople();});}
    }catch(Exception error){if(captured!=null&&(request==44||request==48))captured.delete();ui.post(()->problem(error));}},"lan-attachment").start();
  }
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);pendingOpen=intent.getStringExtra("conversation");
    // Tapping the ongoing-call notification brings the user back to the call. The call ID is
    // carried so a stale notification cannot switch the view to a call that has already ended.
    if("OPEN_CALL".equals(intent.getAction())){pendingCallAcceptId=intent.getStringExtra(CallNotifier.EXTRA_CALL_ID);lastSignature="";render();return;}
    render();}

  /** The installed version, read from the package rather than hardcoded.  This used to be a
   *  literal, which silently went stale — the About screen reported 2.2.6 in a 2.2.8 build.  The
   *  manifest is now the only place a version is declared, so it cannot drift from the APK. */
  String appVersion(){
    try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}
    catch(Exception unavailable){return "unknown";}
  }
  void showAbout(){new AlertDialog.Builder(this).setTitle("About LAN Messenger").setMessage("LAN Messenger\nVersion "+appVersion()+"\n\nPrivate Windows and Android messaging on a local network. No central server, host laptop, account or Internet relay.").setPositiveButton("Close",null).show();}
   void changeAvatar(){PeerEngine e=engine();if(e==null){Toast.makeText(this,"Local messages are still loading.",Toast.LENGTH_SHORT).show();return;}try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),45);}catch(Exception error){problem(error);}}
   void profile(){PeerEngine e=engine();if(e==null)return;EditText name=input("Display name",30);name.setText(e.name);new AlertDialog.Builder(this).setTitle("Your profile").setMessage("Device ID: "+e.id.substring(0,8)+"\nYour contacts recognize this device even if its IP changes.\n\n"+e.uploadPolicy.summary()).setView(name).setPositiveButton("Save",(d,w)->{try{e.rename(name.getText().toString());render();}catch(Exception error){Toast.makeText(this,"Could not save your name.",Toast.LENGTH_LONG).show();}}).setNegativeButton("Close",null).show();}
   void addAddress(){PeerEngine e=engine();if(e==null||host==null||!"Online".equals(host.state)){Toast.makeText(this,"Go online to find a device.",Toast.LENGTH_SHORT).show();return;}EditText address=input("192.168.1.20",60);address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);StringBuilder ips=new StringBuilder();try{Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all.hasMoreElements()){Enumeration<InetAddress> addresses=all.nextElement().getInetAddresses();while(addresses.hasMoreElements()){InetAddress a=addresses.nextElement();if(a instanceof Inet4Address&&!a.isLoopbackAddress())ips.append(a.getHostAddress()).append("  ");}}}catch(Exception ignored){}
    new AlertDialog.Builder(this).setTitle("Add a device").setMessage("Your IP: "+ips+"\nEnter the other device's IP. It must be running LAN Messenger.").setView(address).setPositiveButton("Find device",(d,w)->{String value=address.getText().toString();new Thread(()->{try{e.addAddress(value);ui.post(()->{lastSignature="";render();});}catch(Exception error){ui.post(()->Toast.makeText(this,"Device not reachable. Check Wi-Fi, IP and firewall.",Toast.LENGTH_LONG).show());}}).start();}).setNegativeButton("Cancel",null).show();
  }
  // Back closes an open side menu first; then toggles a live call screen between full and minimised
  // rather than taking it away, so a running call is always visible somewhere and Back can never be
  // mistaken for hanging up; then dismisses an end-reason banner; only then existing navigation.
  @Override public void onBackPressed(){if(menuOpen){PeopleListView.closeMenu(this);return;}
    CallUi live=callUi!=null?callUi:(host==null?null:host.calls());
    if(CallView.isShowing()&&live!=null){
      if(live.hasActive()){CallView.backWhileLive(this);}
      else{CallView.dismiss(this,live);}
      renderCallBar();lastSignature="";render();return;}
    if(selected!=null)showPeople();else super.onBackPressed();}
  @Override protected void onResume(){super.onResume();active=true;if(host!=null)host.callActivityVisible(true);bindCalls();finishVideoMicrophone();finishAttachmentCamera();finishCallCameraPermission();handlePendingCallAccept();ui.removeCallbacks(tick);ui.post(tick);}

  /** Act on a notification tap that asked to return to a call.  The call ID is revalidated, so a
   *  notification left over from a call that has already ended simply does nothing. */
  void handlePendingCallAccept(){
    String expected=pendingCallAcceptId;
    if(expected==null)return;
    // Falls back to the service's instance for the same reason the Accept button does: the field
    // was unbound on a cold start, and that made the notification's Accept action a silent no-op --
    // so a call announced by a notification could not be answered from the notification at all.
    CallUi current=callUi!=null?callUi:(host==null?null:host.calls());
    if(current==null)return;
    CallSession call=current.getCurrent();
    if(call==null||!expected.equals(call.callId)){pendingCallAcceptId=null;lastSignature="";render();return;}
    pendingCallAcceptId=null;
    lastSignature="";CallView.render(this);
  }
  // Backgrounding forces a safe stop, same as Windows' hide-to-tray/conversation-switch triggers:
  // recording is a foreground-UI activity here (no foreground-service microphone type declared),
  // so it cannot continue meaningfully once the Activity leaves the foreground.
  @Override protected void onPause(){super.onPause();active=false;if(host!=null)host.callActivityVisible(false);CallView.pauseVideo();ui.removeCallbacks(tick);if(recordingDraftId!=null)VoiceUi.stopVoiceRecording(this);VoicePlayback.stopActivePlayer(this);saveDraft();}
   // stopVoiceRecording's real work runs on its own background thread and is not awaited here --
   // unlike Windows' FormClosed (which really does end the whole process), destroying this
   // Activity does not by itself kill the process (MessengerService keeps it alive as a
   // foreground service), so that thread realistically gets to finish naturally in the common
   // case. If it doesn't, ReconcileVoiceDrafts (A02) safely diagnoses the leftover
   // Recording-state entry as Invalid the next time the registry is reconciled -- acceptable,
   // matching Windows' own documented limitation here.
   @Override protected void onDestroy(){if(recordingDraftId!=null)VoiceUi.stopVoiceRecording(this);VoicePlayback.stopActivePlayer(this);unbindCalls();CallView.forgetOverlay();if(bound)unbindService(serviceConnection);super.onDestroy();ui.removeCallbacksAndMessages(null);}
  @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
    super.onRequestPermissionsResult(requestCode,permissions,results);
    if(requestCode==ATTACHMENT_CAMERA_REQUEST){
      if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED){pendingAttachmentCameraGranted=true;finishAttachmentCamera();}
      else {pendingAttachmentCameraTarget=null;Toast.makeText(this,"Camera access is off. You can still choose a photo or video from the gallery.",Toast.LENGTH_LONG).show();}
      return;
    }
    if(requestCode==VIDEO_MIC_REQUEST){
      if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED){pendingVideoMicGranted=true;finishVideoMicrophone();}
      else {pendingVideoMicAction=null;pendingVideoPeerId=null;pendingVideoCallId=null;Toast.makeText(this,"Microphone access is needed to answer or start a call.",Toast.LENGTH_LONG).show();}
      return;
    }
    if(requestCode>=CALL_CAMERA_REQUEST_FIRST&&requestCode<=CALL_CAMERA_REQUEST_LAST){
      if(requestCode!=pendingCallCameraRequest||!callCameraPermission.hasPending())return;
      callCameraResultGranted=false;
      for(int i=0;i<permissions.length&&i<results.length;i++)
        if(android.Manifest.permission.CAMERA.equals(permissions[i]))
          callCameraResultGranted=results[i]==android.content.pm.PackageManager.PERMISSION_GRANTED;
      getSharedPreferences("call_camera_permission",MODE_PRIVATE).edit().putBoolean("asked",true).apply();
      callCameraResultReturned=true;finishCallCameraPermission();return;
    }
    // Result of the one-time notification request. Nothing is retried: the platform would
    // auto-deny a repeat, and the refusal is surfaced at call time instead, where it can actually
    // explain what is wrong and where to fix it.
    if(requestCode==NOTIFICATION_REQUEST){
      if(results.length==0||results[0]!=android.content.pm.PackageManager.PERMISSION_GRANTED)
        ui.post(()->Toast.makeText(this,"Without notification permission this device cannot show an incoming call. You can still call other people.",Toast.LENGTH_LONG).show());
      return;
    }
    if(requestCode==VoiceUi.RECORD_AUDIO_REQUEST){
      // The service may already be foreground without the microphone FGS type (it never requests
      // that type unless RECORD_AUDIO is granted at the moment it goes online -- see
      // MessengerService.startForegroundSafely). Granting it here, mid-session, must upgrade the
      // already-running foreground service to include it, since nothing else will.
      if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED){if(host!=null)host.startForegroundSafely();VoiceUi.startVoiceRecording(this);}
      else VoiceUi.permissionDenied(this);
      return;
    }
    // A08: the call's microphone permission. Granted means place the call that asked for it;
    // denied means no call is started and no automatic retry is attempted, because a call without
    // a microphone would connect and transmit silence.
    if(requestCode==CALL_MIC_REQUEST){
      String peerId=pendingCallPeerId;pendingCallPeerId=null;
      if(peerId==null)return;
      if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED){if(host!=null)host.startForegroundSafely();placeCall(peerId);}
      else Toast.makeText(this,"Calling needs the microphone. Enable it in Android settings to call.",Toast.LENGTH_LONG).show();
    }
  }
}
