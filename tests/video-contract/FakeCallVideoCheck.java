package net.lanmsg.chat;

public final class FakeCallVideoCheck {
  static int pass;
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);++pass;}
  static void rejects(Runnable r,String message){try{r.run();throw new AssertionError(message);}catch(IllegalStateException expected){++pass;}}
  public static void main(String[] args)throws Exception {
    FakeCallMedia media=new FakeCallMedia();media.initialize();media.startMedia();
    FakeCallMedia.FakeVideo video=(FakeCallMedia.FakeVideo)media.video();
    final boolean[] permission={true};final int[] local={0},remote={0},errors={0},ready={0};
    video.setListener(new ICallMedia.VideoListener(){
      public void onIce(long gen,String candidate,String mid,int index){}
      public void onReady(long gen){++ready[0];}
      public void onError(long gen,String reason){++errors[0];}
    });
    video.initialize(2,()->permission[0]);
    check(!video.cameraActive(),"initialization is camera-free");
    rejects(()->video.startCamera(2),"capture waits for media readiness");
    ICallMedia.FrameSink ls=frame -> ++local[0],rs=frame -> ++remote[0];
    video.attachLocal(ls);video.attachLocal(ls);video.attachRemote(rs);video.attachRemote(rs);
    ICallMedia.RendererLease surface=video.acquireRendererLease();
    check(surface.sharedContext()!=null,"fake renderer has leased context");
    String offer=video.createOffer(2);check(CallVideoProtocol.validSdp(offer,true),"fake selected VP8 syntax");
    video.setRemoteAnswer(2,offer);check(ready[0]==1&&!video.cameraActive(),"ready does not auto-capture");
    video.startCamera(2);video.emitLocal(new Object());video.emitRemote(new Object());
    check(local[0]==1&&remote[0]==1,"duplicate attach delivers exactly one frame");
    video.detachLocal(ls);video.emitLocal(new Object());video.emitRemote(new Object());
    check(local[0]==1&&remote[0]==2&&video.cameraActive(),"hide local sink keeps transmission and remote video");
    permission[0]=false;rejects(()->video.switchCamera(2),"switch rechecks permission");
    video.stopCamera(2);check(!video.cameraActive(),"camera stops independently");
    rejects(()->video.startCamera(2),"revoked permission blocks restart");
    permission[0]=true;video.startCamera(2);video.fail();
    check(errors[0]==1&&!video.cameraActive(),"recoverable video failure releases capture");
    check(media.getStats().valid,"video failure leaves fake audio stats available");
    video.emitRemote(new Object());check(remote[0]==2,"no frames after failure");
    check(surface.sharedContext()!=null,"root remains until old renderer releases");surface.close();surface.close();
    rejects(()->video.initialize(2,()->true),"failed generation not reused");
    video.initialize(3,()->true);video.setRemoteAnswer(3,video.createOffer(3));
    video.startCamera(3);video.dispose(2);check(video.cameraActive(),"stale disposal cannot kill new video");
    video.dispose(3);check(!video.cameraActive(),"matching dispose camera-free");
    media.dispose();media.dispose();rejects(()->video.initialize(4,()->true),"parent teardown prevents resurrection");
    System.out.println("FakeCallVideoCheck PASS="+pass+" FAIL=0 (A05 fake media; no native camera)");
  }
}
