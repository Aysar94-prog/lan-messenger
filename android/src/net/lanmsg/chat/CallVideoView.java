package net.lanmsg.chat;

import android.view.*;
import android.widget.*;
import android.graphics.Color;
import org.webrtc.*;
import java.util.concurrent.*;

/** Activity-owned surfaces only. Detach/release on replacement; capture stays
 * service-owned. Frame callbacks have no socket/controller work. */
final class CallVideoView implements AutoCloseable {
  private final MainActivity activity;
  private final FrameLayout stage,preview;
  private final SurfaceViewRenderer local,remote;
  private final TextView placeholder,localPlaceholder;
  private final ICallMedia.Video media;
  private final ICallMedia.RendererLease lease;
  private final CallVideoPlacement placement;
  private static final ThreadPoolExecutor bindings=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue<Runnable>(64),r->{Thread t=new Thread(r,"call-video-bindings");t.setDaemon(true);return t;});
  private final Object renderLock=new Object();
  private volatile boolean closed,wantedHidden;
  private boolean localBound;
  private final java.util.concurrent.atomic.AtomicBoolean previewQueued=new java.util.concurrent.atomic.AtomicBoolean();
  private final ICallMedia.FrameSink localSink,remoteSink;
  private float dragX,dragY;
  CallVideoView(MainActivity activity,FrameLayout stage,ICallMedia.Video media,CallVideoPlacement placement){
    this.activity=activity;this.stage=stage;this.media=media;this.placement=placement;
    lease=media.acquireRendererLease();
    local=new SurfaceViewRenderer(activity);remote=new SurfaceViewRenderer(activity);
    try{
      remote.init((EglBase.Context)lease.sharedContext(),null);
      local.init((EglBase.Context)lease.sharedContext(),null);
      remote.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT);
      local.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
      remote.setEnableHardwareScaler(true);local.setEnableHardwareScaler(true);
      local.setZOrderMediaOverlay(true);
    }catch(RuntimeException e){local.release();remote.release();lease.close();throw e;}
    stage.addView(remote,new FrameLayout.LayoutParams(-1,-1));
    placeholder=activity.label("Other camera is off",16);placeholder.setTextColor(Color.WHITE);placeholder.setGravity(Gravity.CENTER);
    stage.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
    preview=new FrameLayout(activity);preview.setContentDescription("Your camera preview. Drag to move.");
    preview.addView(local,new FrameLayout.LayoutParams(-1,-1));
    localPlaceholder=activity.label("Your camera is off",12);localPlaceholder.setGravity(Gravity.CENTER);localPlaceholder.setTextColor(Color.WHITE);
    preview.addView(localPlaceholder,new FrameLayout.LayoutParams(-1,-1));
    stage.addView(preview,new FrameLayout.LayoutParams(activity.dp(112),activity.dp(150)));
    localSink=frame->{synchronized(renderLock){if(!closed&&frame instanceof VideoFrame)local.onFrame((VideoFrame)frame);}};
    remoteSink=frame->{synchronized(renderLock){if(!closed&&frame instanceof VideoFrame)remote.onFrame((VideoFrame)frame);}};
    wantedHidden=placement.hidden();
    bindings.execute(()->{if(closed)return;media.attachRemote(remoteSink);if(!wantedHidden){media.attachLocal(localSink);localBound=true;}});
    preview.setOnTouchListener((view,event)->{
      if(event.getActionMasked()==MotionEvent.ACTION_DOWN){dragX=event.getRawX()-view.getX();dragY=event.getRawY()-view.getY();return true;}
      if(event.getActionMasked()==MotionEvent.ACTION_MOVE){placement.move(event.getRawX()-dragX,event.getRawY()-dragY);clamp();return true;}
      return event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL;
    });
    stage.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->clamp());
    clamp();
  }
  private void clamp(){
    if(closed)return;
    int width=Math.min(activity.dp(112),Math.max(1,stage.getWidth()));
    int height=Math.min(activity.dp(150),Math.max(1,stage.getHeight()));
    if(stage.getWidth()==0||stage.getHeight()==0)return;
    FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)preview.getLayoutParams();
    if(params.width!=width||params.height!=height){params.width=width;params.height=height;preview.setLayoutParams(params);}
    float[] p=placement.position(stage.getWidth(),stage.getHeight(),width,height,activity.dp(12));
    preview.setX(p[0]);preview.setY(p[1]);
  }
  void update(CallVideoCoordinator.Snapshot s){
    if(closed||s==null)return;
    remote.setVisibility(s.remoteCamera?View.VISIBLE:View.INVISIBLE);
    placeholder.setText(s.phase==CallVideoConsent.Phase.Negotiating?"Connecting video…":"Other camera is off");
    placeholder.setVisibility(s.remoteCamera?View.GONE:View.VISIBLE);
    local.setMirror(media.localMirror());
    local.setVisibility(s.localCamera?View.VISIBLE:View.INVISIBLE);
    localPlaceholder.setVisibility(s.localCamera?View.GONE:View.VISIBLE);
    preview.setVisibility(!placement.hidden()?View.VISIBLE:View.GONE);
  }
  void hidePreview(boolean hidden){
    placement.hide(hidden);preview.setVisibility(hidden?View.GONE:View.VISIBLE);
    wantedHidden=hidden;queuePreview();
  }
  private void queuePreview(){
    if(closed||!previewQueued.compareAndSet(false,true))return;
    bindings.execute(()->{
      boolean applied=wantedHidden;
      try{
        if(closed)return;
        if(applied&&localBound){media.detachLocal(localSink);localBound=false;}
        else if(!applied&&!localBound){media.attachLocal(localSink);localBound=true;}
      }finally{previewQueued.set(false);if(!closed&&applied!=wantedHidden)queuePreview();}
    });
  }
  void resetPreview(){placement.reset();hidePreview(false);clamp();}
  @Override public void close(){
    synchronized(renderLock){if(closed)return;closed=true;}
    bindings.execute(()->{
      try{try{media.detachLocal(localSink);}finally{media.detachRemote(remoteSink);}}
      finally{new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{try{local.release();remote.release();}finally{lease.close();}});}
    });
  }
}
