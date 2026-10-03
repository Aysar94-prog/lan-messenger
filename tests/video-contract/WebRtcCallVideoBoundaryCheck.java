package net.lanmsg.chat;

import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.webrtc.*;

/** Executes production worker/lifetime/error boundaries on the JVM using the
 * actual pinned SDK classes, but no native factory, camera or device. */
public final class WebRtcCallVideoBoundaryCheck {
  static int pass;
  interface Action {void run() throws Exception;}
  static void check(boolean value,String label){if(!value)throw new AssertionError(label);++pass;}
  static void rejects(Action action,String label)throws Exception {
    try {action.run();throw new AssertionError(label);}catch(Exception expected){++pass;}
  }
  public static void main(String[] args)throws Exception {
    AtomicInteger opened=new AtomicInteger(),released=new AtomicInteger();
    CallVideoResources resources=new CallVideoResources(() -> {
      opened.incrementAndGet();return new CallVideoResources.Root(){
        public Object sharedContext(){return new Object();}
        public void close(){released.incrementAndGet();}
      };
    });
    WebRtcCallVideo video=new WebRtcCallVideo(null,null,resources,()->true);
    try {
      rejects(()->video.initialize(1,()->true),"audio generation cannot initialize video");
      rejects(()->video.initialize(2,null),"missing consent gate cannot initialize");
      check(opened.get()==0,"invalid initialization never acquires resources");
      ICallMedia.FrameSink a=frame->{},b=frame->{},c=frame->{};
      video.attachLocal(a);video.attachLocal(a);video.attachLocal(b);
      rejects(()->video.attachLocal(c),"local sink cap ignores duplicates");
      video.detachLocal(a);video.attachLocal(c);video.detachLocal(c);video.detachLocal(b);
      video.attachRemote(a);video.attachRemote(a);video.attachRemote(b);
      rejects(()->video.attachRemote(c),"remote sink cap ignores duplicates");
      video.detachRemote(a);video.attachRemote(c);
      check(opened.get()==0,"sink binding never acquires camera or EGL");
      rejects(()->video.acquireRendererLease(),"no renderer without active media");
      rejects(()->video.startCamera(2),"no camera before initialized secured media");
      rejects(()->video.switchCamera(2),"no switch before active camera");
      rejects(()->video.createOffer(2),"no SDP before initialization");
      rejects(()->video.addIce(2,"candidate:x","0",0),"no ICE before initialization");
      video.stopCamera(2);video.dispose(2);check(opened.get()==0,"stale stop/dispose no-op");
      for(int i=2;i<22;i++)rejects(()->video.initialize(next++,()->true),"native factory failure is contained");
      check(opened.get()==20&&released.get()==20,"20 failed native constructions release every lease");
      rejects(()->video.initialize(2,()->true),"failed generation cannot be reused");
      check(video.closeAll(),"worker shuts down after cleanup");
      check(video.closeAll(),"shutdown is idempotent");
      rejects(()->video.initialize(22,()->true),"shutdown prevents resurrection");
      check(released.get()==opened.get(),"no retained root after orderly failure cleanup");
    } finally {video.closeAll();resources.close();}
    Class<?> type=Class.forName("net.lanmsg.chat.WebRtcCallVideo$AwaitSdp");
    Constructor<?> constructor=type.getDeclaredConstructor();constructor.setAccessible(true);
    Method await=type.getDeclaredMethod("await");await.setAccessible(true);
    Object success=constructor.newInstance();((SdpObserver)success).onSetSuccess();await.invoke(success);++pass;
    Object created=constructor.newInstance();((SdpObserver)created).onCreateSuccess(new SessionDescription(SessionDescription.Type.OFFER,"v=0\r\n"));await.invoke(created);++pass;
    Object failure=constructor.newInstance();((SdpObserver)failure).onSetFailure("secret SDP address 192.0.2.1");
    try {await.invoke(failure);throw new AssertionError("native apply failure accepted");}
    catch(InvocationTargetException expected){check(!expected.getCause().getMessage().contains("192.0.2.1"),"native SDP failure reason not leaked");}
    System.out.println("WebRtcCallVideoBoundaryCheck PASS="+pass+" FAIL=0 (JVM boundaries only; no native camera)");
  }
  static int next=2;
}
