package net.lanmsg.chat;

import android.app.*;
import android.content.*;
import android.net.wifi.WifiManager;
import android.os.*;

public class MessengerService extends Service {
  public static volatile PeerEngine engine;
  public static volatile String problem="";
  WifiManager.MulticastLock multicast;
  volatile boolean stopping;
  final Handler handler=new Handler(Looper.getMainLooper());
  final Runnable update=new Runnable(){public void run(){if(engine!=null){getSystemService(NotificationManager.class).notify(1,notification());handler.postDelayed(this,5000);}}};
  Notification notification(){
    Intent open=new Intent(this,MainActivity.class);
    PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MessengerService.class).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    String text=engine==null?"Starting local messaging…":"Available on your network · "+engine.pending()+" queued";
    return new Notification.Builder(this,"connection").setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("LAN Messenger").setContentText(text).setContentIntent(content).setOngoing(true).addAction(new Notification.Action.Builder(null,"Go offline",stop).build()).build();
  }
  @Override public void onCreate(){super.onCreate();
    NotificationManager manager=getSystemService(NotificationManager.class);
    manager.createNotificationChannel(new NotificationChannel("connection","Local connection",NotificationManager.IMPORTANCE_LOW));
    manager.createNotificationChannel(new NotificationChannel("messages","Messages",NotificationManager.IMPORTANCE_DEFAULT));
    startForeground(1,notification());problem="";
    new Thread(()->{try{
      WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
      if(wifi!=null){multicast=wifi.createMulticastLock("lan-messenger-discovery");multicast.setReferenceCounted(false);multicast.acquire();}
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
      synchronized(this){if(stopping){peer.close();return;}peer.start();engine=peer;}handler.post(update);
    }catch(Exception e){problem="Could not start local messaging: "+e.getMessage();stopSelf();}},"lan-security-start").start();
  }
  @Override public int onStartCommand(Intent intent,int flags,int startId){if(intent!=null&&"STOP".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}return START_STICKY;}
  @Override public IBinder onBind(Intent intent){return null;}
  @Override public synchronized void onDestroy(){stopping=true;handler.removeCallbacksAndMessages(null);PeerEngine peer=engine;engine=null;if(peer!=null)peer.close();if(multicast!=null&&multicast.isHeld())multicast.release();stopForeground(true);super.onDestroy();}
}
