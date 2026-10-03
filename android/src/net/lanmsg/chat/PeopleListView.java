package net.lanmsg.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** People-screen side menu (Profile/About/Show-offline-users) and conversation-row building. */
final class PeopleListView {
  private PeopleListView(){}
  static final String PREFS_UI="lan_messenger_ui",KEY_SHOW_OFFLINE="show_offline_users",KEY_HIDE_GROUPS="hide_groups";
  // Side menu for the people screen: a scrim plus a start-aligned panel rebuilt on every open, so
  // the toggle always shows the current value. Removed from the stage when closed or on frame().
  static void openMenu(MainActivity activity){if(activity.selected!=null||activity.stage==null||activity.menuOpen)return;
    FrameLayout overlay=new FrameLayout(activity);overlay.setBackgroundColor(Color.argb(110,0,0,0));overlay.setOnClickListener(v->closeMenu(activity));
    LinearLayout panel=activity.column();panel.setPadding(activity.dp(20),activity.dp(14),activity.dp(20),activity.dp(16));panel.setBackgroundColor(Color.WHITE);
    android.widget.ScrollView menuScroll=new android.widget.ScrollView(activity);menuScroll.setBackgroundColor(Color.WHITE);menuScroll.addView(panel);
    overlay.addView(menuScroll,new FrameLayout.LayoutParams(Math.min(activity.dp(300),(int)(activity.getResources().getDisplayMetrics().widthPixels*0.82f)),-1,android.view.Gravity.START));
    LinearLayout headRow=new LinearLayout(activity);headRow.setGravity(android.view.Gravity.CENTER_VERTICAL);TextView head=activity.label("Menu",19);head.setTypeface(null,Typeface.BOLD);head.setPadding(0,0,0,0);headRow.addView(head,new LinearLayout.LayoutParams(0,-2,1));Button closeMenuItem=activity.button("Close");closeMenuItem.setOnClickListener(v->closeMenu(activity));headRow.addView(closeMenuItem);panel.addView(headRow);
    Button profileItem=menuItem(activity,"Profile");profileItem.setOnClickListener(v->{closeMenu(activity);activity.profile();});panel.addView(profileItem);
    Button aboutItem=menuItem(activity,"About");aboutItem.setOnClickListener(v->{closeMenu(activity);activity.showAbout();});panel.addView(aboutItem);
    Button masters=menuItem(activity,"Masters");masters.setOnClickListener(v->{closeMenu(activity);activity.showPermissionDevices(true);});panel.addView(masters);
    Button slave=menuItem(activity,"Slave");slave.setOnClickListener(v->{closeMenu(activity);activity.showPermissionDevices(false);});panel.addView(slave);
    boolean retry=activity.host!=null&&activity.host.requestedOnline&&"Offline".equals(activity.host.state);
    Button connection=menuItem(activity,retry?"Retry online":activity.host!=null&&activity.host.requestedOnline?"Go offline":"Go online");connection.setOnClickListener(v->{closeMenu(activity);activity.setConnection(retry||activity.host==null||!activity.host.requestedOnline);});panel.addView(connection);
    // setChecked runs before the listener is attached, so building the menu never reports a change.
    Switch offlineToggle=new Switch(activity);offlineToggle.setText("Show offline users");offlineToggle.setTextSize(16);offlineToggle.setPadding(0,activity.dp(12),0,0);offlineToggle.setChecked(activity.showOffline);offlineToggle.setOnCheckedChangeListener((view,checked)->setShowOffline(activity,checked));panel.addView(offlineToggle,new LinearLayout.LayoutParams(-1,-2));
    Switch groupsToggle=new Switch(activity);groupsToggle.setText("Hide groups");groupsToggle.setTextSize(16);groupsToggle.setPadding(0,activity.dp(12),0,0);groupsToggle.setChecked(activity.hideGroups);groupsToggle.setOnCheckedChangeListener((view,checked)->setHideGroups(activity,checked));panel.addView(groupsToggle,new LinearLayout.LayoutParams(-1,-2));
    panel.addView(activity.label("Calls",19));
    // A08: the incoming-call preference. It lives in the service (persisted in the app's files
    // directory), not in this menu's SharedPreferences, because the service must be able to read it
    // with no Activity running. The menu is rebuilt on every open, so the switch always shows the
    // value the service actually holds, including a value that survived a restart or was loaded
    // from a corrupt file.
    MessengerService service=activity.host;
    if(service==null){
      TextView callsUnavailable=activity.label("Calls are still starting. Open the app again in a moment to change this.",14);
      callsUnavailable.setTextColor(Color.rgb(112,128,144));panel.addView(callsUnavailable);
    } else {
      Switch incomingCalls=new Switch(activity);incomingCalls.setText("Allow incoming calls");incomingCalls.setTextSize(16);incomingCalls.setPadding(0,activity.dp(12),0,0);
      incomingCalls.setChecked(service.allowIncomingCalls());
      incomingCalls.setContentDescription("Allow incoming calls");
      // setChecked runs before the listener is attached, so building the menu never reports a change.
      incomingCalls.setOnCheckedChangeListener((view,checked)->activity.setAllowIncomingCalls(checked));
      panel.addView(incomingCalls,new LinearLayout.LayoutParams(-1,-2));
      // If this device may not post notifications, an incoming call cannot be announced at all:
      // the platform silently discards the ringing notification, so the caller's phone rings out
      // and reports "No answer" with no clue why. This is the one place the user can act on it.
      // The switch is left enabled, because this device can still place outgoing calls.
      if(!activity.canPostNotifications()){
        TextView noNotifications=activity.label("This device is not allowed to show notifications, so incoming calls cannot be announced here. Enable notifications for LAN Messenger in Android settings, otherwise an incoming call rings out on the other phone with no explanation.",13);
        noNotifications.setTextColor(Color.rgb(211,47,47));panel.addView(noNotifications);
      }
      // A corrupt or unreadable settings file disables admission and says so. The message names
      // the recoverable action, because the user cannot tell a refused call from an off switch.
      String settingsError=service.callSettingsError();
      if(settingsError!=null&&!settingsError.isEmpty()){
        TextView errorLine=activity.label(settingsError+"\nIncoming calls stay off until you turn this back on.",13);
        errorLine.setTextColor(Color.rgb(211,47,47));panel.addView(errorLine);
      }
      panel.addView(activity.label("Turning this off does not stop you calling others. A call that is already ringing is declined and its notification is withdrawn.",13));
    }
    Button deleteDataItem=menuItem(activity,"Delete app data");deleteDataItem.setTextColor(Color.rgb(211,47,47));deleteDataItem.setOnClickListener(v->{closeMenu(activity);activity.confirmDeleteAllData();});panel.addView(deleteDataItem);
    panel.addView(activity.label("Hiding a row only removes it from this list. The contact or group, its chats, its unread count and any queued message stay on this device.",13));
    activity.stage.addView(overlay,new FrameLayout.LayoutParams(-1,-1));activity.menuOverlay=overlay;activity.menuOpen=true;}
  static void closeMenu(MainActivity activity){if(activity.menuOverlay!=null){if(activity.stage!=null)activity.stage.removeView(activity.menuOverlay);activity.menuOverlay=null;}activity.menuOpen=false;}
  static Button barButton(MainActivity activity,String glyph,int size){Button b=new Button(activity);b.setText(glyph);b.setAllCaps(false);b.setTextSize(size);b.setTextColor(Color.WHITE);b.setBackgroundColor(Color.TRANSPARENT);b.setMinWidth(activity.dp(46));return b;}
  static Button menuItem(MainActivity activity,String text){Button b=activity.button(text);b.setTextSize(17);b.setTextColor(activity.ink);b.setGravity(android.view.Gravity.LEFT|android.view.Gravity.CENTER_VERTICAL);b.setPadding(activity.dp(4),activity.dp(12),activity.dp(4),activity.dp(12));b.setBackgroundColor(Color.TRANSPARENT);return b;}
  // Android-local, defaults to false. A failed read must not crash startup, and a failed write only
  // costs persistence across restarts, so both ends swallow the error and keep the in-memory value.
  static boolean readShowOffline(MainActivity activity){try{return activity.getSharedPreferences(PREFS_UI,Context.MODE_PRIVATE).getBoolean(KEY_SHOW_OFFLINE,false);}catch(Exception ignored){return false;}}
  static void setShowOffline(MainActivity activity,boolean value){if(activity.showOffline==value)return;activity.showOffline=value;try{activity.getSharedPreferences(PREFS_UI,Context.MODE_PRIVATE).edit().putBoolean(KEY_SHOW_OFFLINE,value).apply();}catch(Exception ignored){}
    // Force a rebuild rather than relying on the row signature: the filter also decides the
    // empty-state hint, and the signature only describes rows that are currently visible.
    closeMenu(activity);activity.lastSignature="";activity.render();}
  static boolean readHideGroups(MainActivity activity){try{return activity.getSharedPreferences(PREFS_UI,Context.MODE_PRIVATE).getBoolean(KEY_HIDE_GROUPS,false);}catch(Exception ignored){return false;}}
  static void setHideGroups(MainActivity activity,boolean value){if(activity.hideGroups==value)return;activity.hideGroups=value;try{activity.getSharedPreferences(PREFS_UI,Context.MODE_PRIVATE).edit().putBoolean(KEY_HIDE_GROUPS,value).apply();}catch(Exception ignored){}
    closeMenu(activity);activity.lastSignature="";activity.render();}
  static TextView badge(MainActivity activity,int count){TextView t=new TextView(activity);t.setText(count>99?"99+":String.valueOf(count));t.setTextColor(Color.WHITE);t.setTextSize(11);t.setTypeface(null,Typeface.BOLD);t.setGravity(android.view.Gravity.CENTER);t.setBackground(activity.circleBg(activity.accent,26));return t;}
  static void addContactRow(MainActivity activity,Button contact,int unread,String initial,int avatarColor){addContactRow(activity,contact,unread,activity.circle(initial,avatarColor,40,15));}
  // A contact's real photo, once received (see peerAvatarView) — otherwise the colored-initial circle.
  static void addContactRow(MainActivity activity,Button contact,int unread,View avatarView){LinearLayout row=new LinearLayout(activity);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(android.view.Gravity.CENTER_VERTICAL);row.setPadding(0,activity.dp(2),0,activity.dp(2));
    LinearLayout.LayoutParams avatarParams=new LinearLayout.LayoutParams(activity.dp(40),activity.dp(40));avatarParams.setMargins(activity.dp(6),0,activity.dp(8),0);row.addView(avatarView,avatarParams);
    row.addView(contact,new LinearLayout.LayoutParams(0,-2,1));
    if(unread>0){LinearLayout.LayoutParams badgeParams=new LinearLayout.LayoutParams(activity.dp(26),activity.dp(26));badgeParams.setMargins(activity.dp(8),0,activity.dp(6),0);row.addView(badge(activity,unread),badgeParams);}
    activity.body.addView(row,new LinearLayout.LayoutParams(-1,-2));}
  static View peerAvatarView(MainActivity activity,PeerEngine e,PeerEngine.Peer p,String initial){
    byte[] raw=e.peerAvatar(p.id);Bitmap bmp=raw!=null?activity.inlineBitmap(raw):null;
    FrameLayout wrap=new FrameLayout(activity);View avatar;
    if(bmp==null)avatar=activity.circle(initial,activity.nameColor(p.id),40,15);
    else{ImageView iv=new ImageView(activity);iv.setImageBitmap(bmp);iv.setScaleType(ImageView.ScaleType.CENTER_CROP);iv.setBackground(activity.circleBg(activity.nameColor(p.id),40));iv.setClipToOutline(true);avatar=iv;}
    wrap.addView(avatar,new FrameLayout.LayoutParams(-1,-1));View dot=new View(activity);boolean online=activity.host!=null&&"Online".equals(activity.host.state)&&p.online();GradientDrawable d=activity.circleBg(online?Color.rgb(33,150,243):Color.GRAY,12);d.setStroke(activity.dp(2),Color.WHITE);dot.setBackground(d);dot.setContentDescription(online?"Online":"Offline");wrap.addView(dot,new FrameLayout.LayoutParams(activity.dp(12),activity.dp(12),android.view.Gravity.BOTTOM|android.view.Gravity.RIGHT));return wrap;
  }
}
