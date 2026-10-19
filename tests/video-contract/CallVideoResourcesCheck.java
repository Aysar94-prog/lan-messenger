package net.lanmsg.chat;

/** A05 root/renderer lease ownership tests using a fake EGL backend. */
public final class CallVideoResourcesCheck {
  static int pass,opens,closes;
  static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);++pass;}
  static CallVideoResources owner() {
    return new CallVideoResources(() -> {
      ++opens;final Object context=new Object();
      return new CallVideoResources.Root(){public Object sharedContext(){return context;}public void close(){++closes;}};
    });
  }
  static void rejects(Runnable work,String why){try{work.run();throw new AssertionError(why);}catch(IllegalStateException expected){++pass;}}
  public static void main(String[] args) {
    CallVideoResources owner=owner();
    check(opens==0,"service creation does not initialize EGL or camera");
    rejects(() -> owner.acquireRenderer(),"renderer requires live media");
    CallVideoResources.Lease media=owner.acquireMedia();
    Object firstContext=media.sharedContext();
    CallVideoResources.Lease local=owner.acquireRenderer(),remote=owner.acquireRenderer();
    check(opens==1&&local.sharedContext()==remote.sharedContext()&&media.sharedContext()==local.sharedContext(),"shared context borrowed not recreated");
    media.close();
    check(closes==0&&owner.mediaUsers()==0&&owner.rendererUsers()==2,"media release cannot destroy root under surfaces");
    rejects(() -> owner.acquireRenderer(),"no new renderer after media teardown");
    local.close();local.close();
    check(closes==0&&owner.rendererUsers()==1,"idempotent renderer release");
    rejects(() -> local.sharedContext(),"released context not reusable");
    remote.close();check(closes==1,"last surface release destroys root");
    CallVideoResources.Lease fresh=owner.acquireMedia();
    check(opens==2&&fresh.sharedContext()!=firstContext,"next lifetime uses new context");
    CallVideoResources.Lease surface=owner.acquireRenderer();
    owner.close();owner.close();
    rejects(() -> owner.acquireMedia(),"closing service refuses new media");
    rejects(() -> owner.acquireRenderer(),"closing service refuses new surfaces");
    fresh.close();check(closes==1,"service close waits for its renderer");
    surface.close();check(closes==2,"service close drains retained context");
    CallVideoResources bounded=owner();
    CallVideoResources.Lease[] users=new CallVideoResources.Lease[4];
    for(int i=0;i<4;i++)users[i]=bounded.acquireMedia();
    rejects(() -> bounded.acquireMedia(),"media references bounded");
    CallVideoResources.Lease[] views=new CallVideoResources.Lease[4];
    for(int i=0;i<4;i++)views[i]=bounded.acquireRenderer();
    rejects(() -> bounded.acquireRenderer(),"view rebuild references bounded");
    for(CallVideoResources.Lease lease:users)lease.close();
    for(CallVideoResources.Lease lease:views)lease.close();
    check(bounded.mediaUsers()==0&&bounded.rendererUsers()==0,"counts drained");
    bounded.close();
    CallVideoResources repeated=owner();int startingCloses=closes;
    for(int i=0;i<50;i++) {
      CallVideoResources.Lease m=repeated.acquireMedia(),r=repeated.acquireRenderer();
      r.close();m.close();m.close();
    }
    check(closes-startingCloses==50,"50 repeated root lifetimes released exactly once");
    repeated.close();
    CallVideoResources bad=new CallVideoResources(() -> new CallVideoResources.Root(){public Object sharedContext(){return null;}public void close(){++closes;}});
    int before=closes;rejects(() -> bad.acquireMedia(),"invalid root rejected");
    check(closes==before+1&&bad.mediaUsers()==0,"invalid root released without reference leak");
    System.out.println("CallVideoResourcesCheck PASS="+pass+" FAIL=0 (A05 fake EGL; native device acceptance separate)");
  }
}
