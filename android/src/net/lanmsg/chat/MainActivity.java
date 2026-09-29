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
  MessengerService host;
  boolean bound;
  final ServiceConnection serviceConnection=new ServiceConnection(){
    public void onServiceConnected(ComponentName name,IBinder binder){host=((MessengerService.LocalBinder)binder).host();bound=true;render();}
    public void onServiceDisconnected(ComponentName name){host=null;bound=false;render();}
  };
  PeerEngine engine(){return host==null?null:host.engine;}
  ViewTreeObserver.OnGlobalLayoutListener keyboardListener;
  final Runnable tick=new Runnable(){public void run(){VoiceUi.tickVoiceRecording(MainActivity.this);if(activePlayerUiRefresh!=null)activePlayerUiRefresh.run();render();if(active)ui.postDelayed(this,1000);}};
  int dp(int x){return (int)(x*getResources().getDisplayMetrics().density);}
  TextView label(String text,int size){TextView t=new TextView(this);t.setText(text);t.setTextColor(ink);t.setTextSize(size);t.setPadding(0,dp(6),0,dp(6));return t;}
  LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
  Button button(String text){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(accent);return b;}
  GradientDrawable circleBg(int c,int diameterDp){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(diameterDp)/2f);return d;}
  TextView circle(String letter,int color,int diameterDp,int textSize){TextView t=new TextView(this);t.setText(letter);t.setTextColor(Color.WHITE);t.setTypeface(null,Typeface.BOLD);t.setTextSize(textSize);t.setGravity(android.view.Gravity.CENTER);t.setBackground(circleBg(color,diameterDp));return t;}
  EditText input(String hint,int max){EditText e=new EditText(this);e.setHint(hint);e.setTextSize(17);e.setSingleLine(true);e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});return e;}
  GradientDrawable bg(int c){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(12));return d;}
  @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(headerDark);getWindow().setNavigationBarColor(Color.WHITE);pendingOpen=getIntent().getStringExtra("conversation");
    // The people rows are filtered by a persisted preference, so the first render has to wait for
    // the read instead of flashing unfiltered rows and then hiding them. Reading preferences
    // touches disk, so it stays off the UI thread. The saved Online request starts the service
    // after the first render; an Offline request binds locally without starting networking.
    new Thread(()->{final boolean offlineValue=PeopleListView.readShowOffline(this);final boolean hideGroupsValue=PeopleListView.readHideGroups(this);final boolean online=getSharedPreferences("lan_messenger_connection",MODE_PRIVATE).getBoolean("default_online",true);ui.post(()->{if(isDestroyed())return;showOffline=offlineValue;hideGroups=hideGroupsValue;showPeople();bindService(new Intent(this,MessengerService.class),serviceConnection,BIND_AUTO_CREATE);if(online)startConnection();});},"lan-ui-preference").start();
    if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1);}
  void startConnection(){try{startForegroundService(new Intent(this,MessengerService.class).setAction("ONLINE"));}catch(Exception e){if(host!=null)host.problem="Could not start. Open the app and try again.";}}
  void setConnection(boolean online){if(online)startConnection();else if(host!=null)host.transition(false);lastSignature="";render();}
  // The header is built by each screen (showPeople/showChat) and inserted at chrome index 0, so a
  // space-constrained chat can use a single compact bar instead of always paying for the full app banner.
  // The content view is a single full-screen stage so the people side menu can overlay everything,
  // header bar included, without changing the chrome column that each screen already builds.
  void frame(){if(root!=null)releaseImages(root);PeopleListView.closeMenu(this);if(keyboardListener!=null){getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(keyboardListener);keyboardListener=null;}
    stage=new FrameLayout(this);chrome=column();chrome.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);chrome.setBackgroundColor(panelBg);stage.addView(chrome,new FrameLayout.LayoutParams(-1,-1));setContentView(stage);
    LinearLayout content=column();content.setPadding(dp(18),dp(10),dp(18),dp(8));chrome.addView(content,new LinearLayout.LayoutParams(-1,0,1));root=content;
    status=label("Finding people on your network…",14);}
  void saveDraft(){if(selected!=null&&composer!=null)drafts.put(selected,composer.getText().toString());}
  void showPeople(){saveDraft();if(recordingConversation!=null)VoiceUi.stopVoiceRecording(this);VoicePlayback.stopActivePlayer(this);AttachmentFlow.clearPendingAttachment(this);selected=null;composer=null;lastSignature="";frame();
    LinearLayout headerBar=new LinearLayout(this);headerBar.setOrientation(LinearLayout.HORIZONTAL);headerBar.setGravity(android.view.Gravity.CENTER_VERTICAL);headerBar.setBackgroundColor(headerDark);headerBar.setPadding(dp(18),dp(14),dp(18),dp(14));
    TextView title=label("LAN Messenger",20);title.setTypeface(null,Typeface.BOLD);title.setTextColor(Color.WHITE);title.setPadding(0,0,0,0);headerBar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
    Button menuButton=PeopleListView.barButton(this,"☰",20);menuButton.setContentDescription("Menu");menuButton.setOnClickListener(v->PeopleListView.openMenu(this));headerBar.addView(menuButton);
    chrome.addView(headerBar,0);
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
  void showChat(String id){saveDraft();if(recordingConversation!=null&&!recordingConversation.equals(id))VoiceUi.stopVoiceRecording(this);VoicePlayback.stopActivePlayer(this);if(pendingAttachmentTarget!=null&&!pendingAttachmentTarget.equals(id))AttachmentFlow.clearPendingAttachment(this);selected=id;lastSignature="";visibleMessageCounts.putIfAbsent(id,10);frame();
    PeerEngine chatEngine=engine();boolean isGroup=false;String chatInitial="?";int chatColor=accent;
    if(chatEngine!=null){for(PeerEngine.Group g:chatEngine.groups())if(g.id.equals(id)){isGroup=true;chatColor=Color.rgb(156,124,224);chatInitial="G";}
      if(!isGroup)for(PeerEngine.Peer p:chatEngine.peers())if(p.id.equals(id)){chatColor=nameColor(p.id);chatInitial=p.name.isEmpty()?"?":p.name.substring(0,1).toUpperCase(Locale.ROOT);}}
    LinearLayout chatHeader=new LinearLayout(this);chatHeader.setOrientation(LinearLayout.HORIZONTAL);chatHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);chatHeader.setBackgroundColor(headerDark);chatHeader.setPadding(dp(2),dp(6),dp(10),dp(6));
    Button back=new Button(this);back.setText("‹");back.setAllCaps(false);back.setTextSize(24);back.setTextColor(Color.WHITE);back.setBackgroundColor(Color.TRANSPARENT);back.setMinWidth(dp(46));back.setOnClickListener(v->showPeople());chatHeader.addView(back);
    FrameLayout chatAvatar=new FrameLayout(this);chatAvatar.setBackground(circleBg(chatColor,36));TextView chatAvatarText=new TextView(this);chatAvatarText.setText(chatInitial);chatAvatarText.setTextColor(Color.WHITE);chatAvatarText.setTypeface(null,Typeface.BOLD);chatAvatarText.setGravity(android.view.Gravity.CENTER);chatAvatar.addView(chatAvatarText,new FrameLayout.LayoutParams(-1,-1));LinearLayout.LayoutParams chatAvatarParams=new LinearLayout.LayoutParams(dp(36),dp(36));chatAvatarParams.setMargins(0,0,dp(10),0);chatHeader.addView(chatAvatar,chatAvatarParams);
    heading=new TextView(this);heading.setTextColor(Color.WHITE);heading.setTypeface(null,Typeface.BOLD);heading.setTextSize(15);heading.setSingleLine(true);heading.setEllipsize(TextUtils.TruncateAt.END);chatHeader.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
    Button more=new Button(this);more.setText("⋮");more.setAllCaps(false);more.setTextSize(20);more.setTextColor(Color.WHITE);more.setBackgroundColor(Color.TRANSPARENT);more.setOnClickListener(v->chatMenu(more));chatHeader.addView(more);
    chrome.addView(chatHeader,0);
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
    attachmentDraft=column();root.addView(attachmentDraft);composer=input("Write a message or caption…",2000);composer.setSingleLine(false);composer.setMaxLines(4);composer.setText(drafts.containsKey(id)?drafts.get(id):"");root.addView(composer);LinearLayout composeActions=new LinearLayout(this);Button camera=button("Camera"),photo=button("Photo"),file=button("File");composeActions.addView(camera);composeActions.addView(photo);composeActions.addView(file);camera.setOnClickListener(v->AttachmentFlow.capturePhoto(this));photo.setOnClickListener(v->AttachmentFlow.pickFile(this,true));file.setOnClickListener(v->AttachmentFlow.pickFile(this,false));send=button("Send");send.setTextColor(Color.WHITE);send.setBackgroundTintList(android.content.res.ColorStateList.valueOf(accent));Button fast=button("Fast file");composeActions.addView(fast);fast.setOnClickListener(v->AttachmentFlow.pickFastFile(this));recordVoice=button("🎤");recordVoice.setContentDescription("Record a voice message");composeActions.addView(recordVoice);recordVoice.setOnClickListener(v->VoiceUi.startVoiceRecording(this));HorizontalScrollView actionScroll=new HorizontalScrollView(this);actionScroll.setHorizontalScrollBarEnabled(false);actionScroll.addView(composeActions);LinearLayout actionRow=new LinearLayout(this);actionRow.addView(actionScroll,new LinearLayout.LayoutParams(0,dp(48),1));actionRow.addView(send,new LinearLayout.LayoutParams(dp(80),dp(48)));root.addView(actionRow);
    send.setOnClickListener(v->{
      PeerEngine e=engine();if(e==null){Toast.makeText(this,"Local messages are still loading.",Toast.LENGTH_SHORT).show();return;}
      if(pendingAttachmentUri==null&&composer.getText().toString().trim().isEmpty())return;
      String target=selected;String caption=composer.getText().toString();Uri uri=pendingAttachmentUri;long size=pendingAttachmentSize;String name=pendingAttachmentName;File cameraFile2=pendingCameraFile;boolean fastMode=pendingFast;
      sendBusy=true;send.setEnabled(false);composer.setEnabled(false);send.setText("Preparing…");
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
          ui.post(()->{if(target.equals(selected)){composer.setText("");drafts.remove(target);}if(uri==pendingAttachmentUri)AttachmentFlow.clearPendingAttachment(this);sendBusy=false;send.setEnabled(true);send.setText("Send");if(composer!=null)composer.setEnabled(true);lastSignature="";render();});
          if(uri==null)try{e.flush();}catch(Exception ignored){}
        }catch(Exception error){ui.post(()->{sendBusy=false;send.setEnabled(true);send.setText("Send");if(composer!=null)composer.setEnabled(true);problem(error);});}
      },"lan-send").start();
    });AttachmentFlow.renderPendingAttachment(this);VoiceUi.renderVoicePanel(this);render();
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
  void render(){if(root==null||status==null)return;boolean networkAvailable=host!=null&&"Online".equals(host.state);if(refreshButton!=null)refreshButton.setEnabled(networkAvailable);if(addAddressButton!=null)addAddressButton.setEnabled(networkAvailable);PeerEngine e=engine();if(e==null){status.setText(host==null?"Loading local messages…":host.problem.isEmpty()?host.state:host.problem);return;}
    if(e!=transferProgressWiredFor){
      // Attachment transfers run on background connection threads, not the UI thread — marshal
      // back to update the in-flight "Sending NN%" status shown on the message's own bubble.
      e.transferProgress=(id,done,total)->ui.post(()->{if(done>=total)transferProgress.remove(id);else transferProgress.put(id,new long[]{done,total});updateProgressLabels(e);});
      transferProgressWiredFor=e;
    }
    updateProgressLabels(e);
    if(pendingOpen!=null){String target=pendingOpen;pendingOpen=null;showChat(target);return;}
    if(selected!=null)try{e.markRead(selected);}catch(Exception ignored){}
    List<PeerEngine.Peer> people=e.peers();Collections.sort(people,(a,b)->a.online()==b.online()?a.name.compareToIgnoreCase(b.name):(a.online()?-1:1));int online=0;if("Online".equals(host.state))for(PeerEngine.Peer p:people)if(p.online())online++;status.setText((host.problem.isEmpty()?host.state:host.state+" · "+host.problem)+" · "+online+" online · "+e.pending()+" queued · "+e.name);
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
    heading.setText(group!=null?group.name+" · "+group.members.length+" members"+(leavingPending?" · Leaving — waiting for members to catch up":""):peer.name+" · "+("Online".equals(host.state)&&peer.online()?"Online":"Offline")+" · "+peer.security());send.setEnabled(!sendBusy&&!leavingPending&&(group!=null||peer.trusted()));send.setText(sendBusy?"Preparing…":"Send");composer.setEnabled(!sendBusy&&!leavingPending);if(recordVoice!=null)recordVoice.setEnabled(send.isEnabled()&&recordingDraftId==null);List<PeerEngine.Message> messages=e.messages(selected);
    int visibleCount=visibleMessageCounts.getOrDefault(selected,10);int start=Math.max(0,messages.size()-visibleCount);
    StringBuilder signature=new StringBuilder(selected).append(start);for(int i=start;i<messages.size();i++){PeerEngine.Message m=messages.get(i);signature.append(m.id).append(m.status).append(e.hasAttachment(m)).append(e.downloading(m));}if(signature.toString().equals(lastSignature))return;lastSignature=signature.toString();boolean bottom=feed.getHeight()-scroll.getScrollY()-scroll.getHeight()<dp(120);releaseImages(feed);feed.removeAllViews();progressLabels.clear();
    if(messages.isEmpty())feed.addView(label("Verify this device before chatting.\n\nQueued messages send after both verified devices reconnect. Delivered means saved on the other device.",17));
    int maxBubble=Math.min(dp(320),(int)(getResources().getDisplayMetrics().widthPixels*0.78));
    byte[] ownAvatarRaw=e.avatar();Bitmap ownAvatarBmp=ownAvatarRaw==null?null:inlineBitmap(ownAvatarRaw);
    for(int messageIndex=start;messageIndex<messages.size();messageIndex++){PeerEngine.Message m=messages.get(messageIndex);boolean mine=m.from.equals(e.id);LinearLayout card=column();card.setPadding(dp(12),dp(6),dp(12),dp(8));card.setBackground(bg(mine?bubbleMine:Color.WHITE));
      LinearLayout whoRow=new LinearLayout(this);whoRow.setOrientation(LinearLayout.HORIZONTAL);whoRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
      String whoName=mine?"You":e.displayName(m.from);int whoColor=mine?accent:nameColor(m.from);
      LinearLayout.LayoutParams miniAvatarParams=new LinearLayout.LayoutParams(dp(18),dp(18));miniAvatarParams.setMargins(0,0,dp(6),0);
      byte[] peerAvatarRaw=mine?null:e.peerAvatar(m.from);Bitmap peerAvatarBmp=peerAvatarRaw==null?null:inlineBitmap(peerAvatarRaw);
      if(mine&&ownAvatarBmp!=null){ImageView miniAvatar=new ImageView(this);miniAvatar.setImageBitmap(ownAvatarBmp);miniAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);miniAvatar.setClipToOutline(true);whoRow.addView(miniAvatar,miniAvatarParams);}
      else if(peerAvatarBmp!=null){ImageView miniAvatar=new ImageView(this);miniAvatar.setImageBitmap(peerAvatarBmp);miniAvatar.setScaleType(ImageView.ScaleType.CENTER_CROP);miniAvatar.setClipToOutline(true);whoRow.addView(miniAvatar,miniAvatarParams);}
      else whoRow.addView(circle(whoName.isEmpty()?"?":whoName.substring(0,1).toUpperCase(Locale.ROOT),whoColor,18,9),miniAvatarParams);
      TextView who=label(whoName,14);who.setPadding(0,0,0,0);who.setTypeface(null,Typeface.BOLD);who.setTextColor(whoColor);whoRow.addView(who);
      card.addView(whoRow);if(m.fileName.isEmpty()){TextView text=label(m.text,17);text.setMaxWidth(maxBubble);text.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);text.setTextIsSelectable(true);card.addView(text);}else if(VoiceMarker.classify(m.fileName,"Normal",m.id).equals(VoiceMarker.CANDIDATE)){VoiceCard.addVoiceCard(this,card,m);if(!m.text.isEmpty()){TextView caption=label(m.text,17);caption.setMaxWidth(maxBubble);caption.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);caption.setTextIsSelectable(true);card.addView(caption);}}else{Bitmap thumbnail=inlineBitmap(e,m);if(thumbnail!=null){ImageView image=new ImageView(this);image.setImageBitmap(thumbnail);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setAdjustViewBounds(false);int height=Math.max(dp(150),Math.min(dp(320),(int)((long)maxBubble*thumbnail.getHeight()/Math.max(1,thumbnail.getWidth()))));image.setLayoutParams(new LinearLayout.LayoutParams(maxBubble,height));image.setBackground(bg(Color.rgb(232,236,243)));image.setClipToOutline(true);image.setContentDescription("Open "+m.fileName);image.setOnClickListener(v->AttachmentFlow.previewImage(this,m));card.addView(image);}TextView name=label(m.fileName+"  ·  "+formatSize(m.fileSize),15);name.setMaxWidth(maxBubble);name.setTextIsSelectable(true);name.setOnClickListener(v->{if(e.hasAttachment(m))AttachmentFlow.fileAction(this,m);});card.addView(name);LinearLayout fileActions=new LinearLayout(this);boolean available=e.hasAttachment(m);Button save=button(available?"Open":e.downloading(m)?"Pause":e.pendingDestination(m).isEmpty()?"Download":"Resume");fileActions.addView(save);save.setOnClickListener(v->AttachmentFlow.fileAction(this,m));if(thumbnail!=null){Button open=button("Open");fileActions.addView(open);open.setOnClickListener(v->AttachmentFlow.previewImage(this,m));}card.addView(fileActions);TextView progressLine=label("",13);card.addView(progressLine);progressLabels.put(m.id,progressLine);if(!m.text.isEmpty()){TextView caption=label(m.text,17);caption.setMaxWidth(maxBubble);caption.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);caption.setTextIsSelectable(true);card.addView(caption);}}String when=android.text.format.DateFormat.format("MMM d, HH:mm",m.time).toString();if(mine){boolean seen=m.status.startsWith("Seen");String ticks=seen||m.status.startsWith("Delivered")?"✓✓":"✓";String statusText=m.status;long[] progress=m.status.equals("Queued")&&!m.fileName.isEmpty()?transferProgress.get(m.id):null;if(progress!=null&&progress[1]>0)statusText="Sending "+(progress[0]*100/progress[1])+"%";TextView statusLine=label(when+"  ·  "+ticks+" "+statusText,13);statusLine.setTextColor(seen?seenBlue:Color.rgb(112,128,144));card.addView(statusLine);}else{TextView statusLine=label(when,13);statusLine.setTextColor(Color.rgb(112,128,144));card.addView(statusLine);}
      LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);View spacer=new View(this);
      if(mine){row.addView(spacer,new LinearLayout.LayoutParams(0,0,1));row.addView(card,new LinearLayout.LayoutParams(-2,-2));}else{row.addView(card,new LinearLayout.LayoutParams(-2,-2));row.addView(spacer,new LinearLayout.LayoutParams(0,0,1));}
      LinearLayout.LayoutParams rowParams=new LinearLayout.LayoutParams(-1,-2);rowParams.setMargins(0,dp(5),0,dp(5));feed.addView(row,rowParams);}
    updateProgressLabels(e);
    if(bottom&&!loadingEarlier)scroll.post(()->scroll.fullScroll(View.FOCUS_DOWN));
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

  void chatMenu(View anchor){if(selected==null)return;PeerEngine e=engine();boolean isPeer=false;PeerEngine.Group group=null;
    if(e!=null){for(PeerEngine.Peer p:e.peers())if(p.id.equals(selected))isPeer=true;for(PeerEngine.Group g:e.groups())if(g.id.equals(selected))group=g;}
    final PeerEngine.Group selectedGroup=group;
    PopupMenu menu=new PopupMenu(this,anchor);if(isPeer)menu.getMenu().add("Verify device");menu.getMenu().add("Group members");menu.getMenu().add("Clear conversation");if(selectedGroup!=null)menu.getMenu().add("Leave group");
    menu.setOnMenuItemClickListener(item->{String title=item.getTitle().toString();if(title.equals("Clear conversation"))clearChat();else if(title.equals("Verify device"))verifyDevice();else if(title.equals("Leave group"))confirmDeleteConversation(selectedGroup.id,selectedGroup.name,true);else showMembers();return true;});menu.show();}
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
   @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK){if(request==44&&cameraFile!=null)cameraFile.delete();return;}final Uri uri=data==null?null:data.getData();final PeerEngine e=engine();if(e==null){Toast.makeText(this,"Local messages are still loading.",Toast.LENGTH_LONG).show();return;}
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
      else if(request==44&&target!=null&&captured!=null){
        String name="Photo-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+".jpg";
        AttachmentFlow.prepareAttachment(this,target,name,Uri.fromFile(captured),captured.length(),captured);
      }
      else if(request==45&&uri!=null){byte[] raw;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Cannot open image");raw=AttachmentFlow.readLimited(in);}Bitmap bitmap=inlineBitmap(raw);if(bitmap==null)throw new IOException("This file is not a supported image.");ByteArrayOutputStream png=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,90,png);e.setAvatar(png.toByteArray());bitmap.recycle();ui.post(()->{Toast.makeText(this,"Profile picture updated",Toast.LENGTH_SHORT).show();if(selected==null)showPeople();});}
    }catch(Exception error){if(captured!=null&&request==44)captured.delete();ui.post(()->problem(error));}},"lan-attachment").start();
  }
  @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);pendingOpen=intent.getStringExtra("conversation");render();}

  static final String APP_VERSION="2.2.5";
  void showAbout(){new AlertDialog.Builder(this).setTitle("About LAN Messenger").setMessage("LAN Messenger\nVersion "+APP_VERSION+"\n\nPrivate Windows and Android messaging on a local network. No central server, host laptop, account or Internet relay.").setPositiveButton("Close",null).show();}
   void changeAvatar(){PeerEngine e=engine();if(e==null){Toast.makeText(this,"Local messages are still loading.",Toast.LENGTH_SHORT).show();return;}try{startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"),45);}catch(Exception error){problem(error);}}
   void profile(){PeerEngine e=engine();if(e==null)return;EditText name=input("Display name",30);name.setText(e.name);new AlertDialog.Builder(this).setTitle("Your profile").setMessage("Device ID: "+e.id.substring(0,8)+"\nYour contacts recognize this device even if its IP changes.\n\n"+e.uploadPolicy.summary()).setView(name).setPositiveButton("Save",(d,w)->{try{e.rename(name.getText().toString());render();}catch(Exception error){Toast.makeText(this,"Could not save your name.",Toast.LENGTH_LONG).show();}}).setNegativeButton("Close",null).show();}
   void addAddress(){PeerEngine e=engine();if(e==null||host==null||!"Online".equals(host.state)){Toast.makeText(this,"Go online to find a device.",Toast.LENGTH_SHORT).show();return;}EditText address=input("192.168.1.20",60);address.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);StringBuilder ips=new StringBuilder();try{Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();while(all.hasMoreElements()){Enumeration<InetAddress> addresses=all.nextElement().getInetAddresses();while(addresses.hasMoreElements()){InetAddress a=addresses.nextElement();if(a instanceof Inet4Address&&!a.isLoopbackAddress())ips.append(a.getHostAddress()).append("  ");}}}catch(Exception ignored){}
    new AlertDialog.Builder(this).setTitle("Add a device").setMessage("Your IP: "+ips+"\nEnter the other device's IP. It must be running LAN Messenger.").setView(address).setPositiveButton("Find device",(d,w)->{String value=address.getText().toString();new Thread(()->{try{e.addAddress(value);ui.post(()->{lastSignature="";render();});}catch(Exception error){ui.post(()->Toast.makeText(this,"Device not reachable. Check Wi-Fi, IP and firewall.",Toast.LENGTH_LONG).show());}}).start();}).setNegativeButton("Cancel",null).show();
  }
  // Back closes an open side menu first; only then does the existing navigation run.
  @Override public void onBackPressed(){if(menuOpen){PeopleListView.closeMenu(this);return;}if(selected!=null)showPeople();else super.onBackPressed();}
  @Override protected void onResume(){super.onResume();active=true;ui.removeCallbacks(tick);ui.post(tick);}
  // Backgrounding forces a safe stop, same as Windows' hide-to-tray/conversation-switch triggers:
  // recording is a foreground-UI activity here (no foreground-service microphone type declared),
  // so it cannot continue meaningfully once the Activity leaves the foreground.
  @Override protected void onPause(){super.onPause();active=false;ui.removeCallbacks(tick);if(recordingDraftId!=null)VoiceUi.stopVoiceRecording(this);VoicePlayback.stopActivePlayer(this);saveDraft();}
   // stopVoiceRecording's real work runs on its own background thread and is not awaited here --
   // unlike Windows' FormClosed (which really does end the whole process), destroying this
   // Activity does not by itself kill the process (MessengerService keeps it alive as a
   // foreground service), so that thread realistically gets to finish naturally in the common
   // case. If it doesn't, ReconcileVoiceDrafts (A02) safely diagnoses the leftover
   // Recording-state entry as Invalid the next time the registry is reconciled -- acceptable,
   // matching Windows' own documented limitation here.
   @Override protected void onDestroy(){if(recordingDraftId!=null)VoiceUi.stopVoiceRecording(this);VoicePlayback.stopActivePlayer(this);if(bound)unbindService(serviceConnection);super.onDestroy();ui.removeCallbacksAndMessages(null);}
  @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
    super.onRequestPermissionsResult(requestCode,permissions,results);
    if(requestCode==VoiceUi.RECORD_AUDIO_REQUEST){
      if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)VoiceUi.startVoiceRecording(this);
      else VoiceUi.permissionDenied(this);
    }
  }
}
