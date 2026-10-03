package net.lanmsg.chat;

import android.content.Context;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.webrtc.*;
import org.json.JSONObject;

/** AT03: executes the real production adapter in the separate test APK. One
 * physical phone camera sends to a second production PC; generated reverse
 * frames avoid trying to acquire the same physical camera twice. No app data,
 * production signing key or production test hook. */
public final class ProductionVideoAdapterCheck {
  private ProductionVideoAdapterCheck(){}
  public static JSONObject run(Context context,BooleanSupplier visible)throws Exception {
    JSONObject result=null;
    for(int round=0;round<3;round++)result=runOnce(context,visible);
    result.put("completedMediaLifetimes",3);
    return result;
  }
  private static JSONObject runOnce(Context context,BooleanSupplier visible)throws Exception {
    CallVideoResources resources=WebRtcCallMedia.newVideoResources();
    WebRtcCallMedia.install(context,resources);
    ICallMedia a=null,b=null;
    ExecutorService ice=Executors.newSingleThreadExecutor();
    ScheduledExecutorService generated=Executors.newSingleThreadScheduledExecutor();
    AtomicBoolean allowed=new AtomicBoolean(false);
    AtomicInteger fromCamera=new AtomicInteger(),fromGenerated=new AtomicInteger(),localFrames=new AtomicInteger();
    AtomicReference<String> failure=new AtomicReference<String>();
    CountDownLatch ready=new CountDownLatch(2);
    try{
      a=new WebRtcCallMedia.Factory(context).create();b=new WebRtcCallMedia.Factory(context).create();
      final ICallMedia.Video av=a.video(),bv=b.video();
      av.setListener(listener(ready,failure,ice,bv));bv.setListener(listener(ready,failure,ice,av));
      av.initialize(2,()->allowed.get()&&visible.getAsBoolean());bv.initialize(2,()->false);
      av.attachRemote(frame->fromGenerated.incrementAndGet());bv.attachRemote(frame->fromCamera.incrementAndGet());
      av.attachLocal(frame->localFrames.incrementAndGet());
      String offer=av.createOffer(2);String answer=bv.createAnswer(2,offer);av.setRemoteAnswer(2,answer);
      if(!ready.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Production video handshake timeout");
      if(failure.get()!=null)throw new IllegalStateException(failure.get());
      // Test-only generated input. Production does not contain reflection or
      // synthetic sources; this code is compiled only in AP01.
      java.lang.reflect.Field active=bv.getClass().getDeclaredField("active");active.setAccessible(true);
      Object node=active.get(bv);
      java.lang.reflect.Field sourceField=node.getClass().getDeclaredField("source");sourceField.setAccessible(true);
      java.lang.reflect.Field trackField=node.getClass().getDeclaredField("track");trackField.setAccessible(true);
      VideoSource source=(VideoSource)sourceField.get(node);VideoTrack track=(VideoTrack)trackField.get(node);
      track.setEnabled(true);source.getCapturerObserver().onCapturerStarted(true);
      AtomicInteger tick=new AtomicInteger();
      generated.scheduleAtFixedRate(()->{
        if(!visible.getAsBoolean())return;
        JavaI420Buffer buffer=JavaI420Buffer.allocate(320,240);int value=tick.incrementAndGet();
        for(int y=0;y<240;y++)for(int x=0;x<320;x++)buffer.getDataY().put(y*buffer.getStrideY()+x,(byte)(32+(x+y+value*7)%180));
        for(int y=0;y<120;y++)for(int x=0;x<160;x++){
          buffer.getDataU().put(y*buffer.getStrideU()+x,(byte)128);buffer.getDataV().put(y*buffer.getStrideV()+x,(byte)128);
        }
        VideoFrame frame=new VideoFrame(buffer,0,System.nanoTime());
        try{source.getCapturerObserver().onFrameCaptured(frame);}finally{frame.release();}
      },0,50,TimeUnit.MILLISECONDS);
      allowed.set(true);av.startCamera(2);
      Thread.sleep(2200);av.switchCamera(2);Thread.sleep(2200);
      CallVideoDiagnostics.Snapshot first=av.diagnostics();Thread.sleep(1100);
      CallVideoDiagnostics.Snapshot measured=av.diagnostics();Thread.sleep(1100);measured=av.diagnostics();
      allowed.set(false);av.stopCamera(2);int stopped=localFrames.get();Thread.sleep(500);
      if(localFrames.get()>stopped+2)throw new IllegalStateException("Camera frames continued after stop");
      if(fromCamera.get()<10||fromGenerated.get()<10)throw new IllegalStateException("Missing decoded production video");
      if(failure.get()!=null)throw new IllegalStateException(failure.get());
      JSONObject result=new JSONObject();result.put("adapter","production WebRtcCallVideo");
      result.put("physicalCameraFramesDecoded",fromCamera.get());result.put("generatedReverseFramesDecoded",fromGenerated.get());
      result.put("localCameraFrames",localFrames.get());result.put("cameraSwitch",true);result.put("cameraStopped",true);
      result.put("diagnostics",CallVideoDiagnostics.display(measured));result.put("audioListening","not repeated; previously accepted");
      return result;
    }finally{
      allowed.set(false);generated.shutdownNow();generated.awaitTermination(3,TimeUnit.SECONDS);
      ice.shutdown();ice.awaitTermination(3,TimeUnit.SECONDS);
      boolean cleanA=closeVideo(a),cleanB=closeVideo(b);
      try{
        if(a!=null)a.dispose();if(b!=null)b.dispose();
        java.lang.reflect.Field poisoned=WebRtcCallMedia.class.getDeclaredField("factoryPoisoned");poisoned.setAccessible(true);
        if(!cleanA||!cleanB||poisoned.getBoolean(null))
          throw new IllegalStateException("Production cleanup failed: videoA="+cleanA+", videoB="+cleanB+", factoryBlocked="+poisoned.getBoolean(null));
        ICallMedia next=new WebRtcCallMedia.Factory(context).create();
        try{next.createOffer();}finally{next.dispose();}
        if(poisoned.getBoolean(null))throw new IllegalStateException("Following voice-only media cleanup failed");
      }finally{resources.close();}
    }
  }
  private static boolean closeVideo(ICallMedia media){
    if(media==null)return true;
    WebRtcCallVideo video=(WebRtcCallVideo)media.video();
    if(video==null)return true;
    boolean clean=video.closeAll();
    if(!clean)try{
      java.lang.reflect.Field active=WebRtcCallVideo.class.getDeclaredField("active");active.setAccessible(true);
      Object node=active.get(video);
      if(node!=null){
        java.lang.reflect.Field remote=node.getClass().getDeclaredField("remoteTrack");remote.setAccessible(true);
        VideoTrack track=(VideoTrack)remote.get(node);
        android.util.Log.e("ProductionCleanupCheck","cleanup retained remoteTrack="+(track!=null)+", remoteDisposed="+(track!=null&&track.isDisposed()));
      }
    }catch(Exception ignored){}
    return clean;
  }
  private static ICallMedia.VideoListener listener(CountDownLatch ready,AtomicReference<String> failure,
      ExecutorService ice,ICallMedia.Video other){
    AtomicBoolean counted=new AtomicBoolean();
    return new ICallMedia.VideoListener(){
      public void onReady(long gen){if(counted.compareAndSet(false,true))ready.countDown();}
      public void onError(long gen,String message){failure.compareAndSet(null,"Production video adapter failure");}
      public void onIce(long gen,String candidate,String mid,int index){
        try{ice.execute(()->{try{other.addIce(gen,candidate,mid,index);}catch(Exception error){failure.compareAndSet(null,"Production ICE failure");}});}
        catch(RejectedExecutionException ignored){}
      }
    };
  }
}
