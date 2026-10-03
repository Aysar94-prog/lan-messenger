package net.lanmsg.chat;

/** A05 service-owned EGL lifetime, with a pure-Java backend for ownership tests.
 * Media factory releases before its lease; renderer detaches/releases surfaces
 * before its lease. Closing the service prevents new users, but never destroys
 * the root under a renderer that has not released its surface yet. */
public final class CallVideoResources implements AutoCloseable {
  public interface Root extends AutoCloseable {
    Object sharedContext();
    @Override void close();
  }
  public interface Provider { Root open(); }
  private final Provider provider;
  private Root root;
  private int mediaUsers, rendererUsers;
  private boolean closing;
  public CallVideoResources(Provider provider) {
    if(provider==null)throw new IllegalArgumentException("Missing context provider");
    this.provider=provider;
  }
  public final class Lease implements ICallMedia.RendererLease {
    private final Root heldRoot;
    private final boolean renderer;
    private boolean released;
    private Lease(Root heldRoot,boolean renderer) {this.heldRoot=heldRoot;this.renderer=renderer;}
    @Override public Object sharedContext() {
      synchronized(CallVideoResources.this) {
        if(released)throw new IllegalStateException("Context lease released");
        return heldRoot.sharedContext();
      }
    }
    @Override public void close() {
      synchronized(CallVideoResources.this) {
        if(released)return;
        released=true;
        if(renderer)--rendererUsers;else --mediaUsers;
        releaseUnusedRoot();
      }
    }
  }
  public synchronized Lease acquireMedia() {
    if(closing)throw new IllegalStateException("Video service is closing");
    if(mediaUsers>=4)throw new IllegalStateException("Media lease limit");
    if(root==null) {
      Root created=provider.open();
      if(created==null)throw new IllegalStateException("EGL root unavailable");
      try {
        if(created.sharedContext()==null)throw new IllegalStateException("EGL shared context unavailable");
      } catch(RuntimeException error) {created.close();throw error;}
      root=created;
    }
    ++mediaUsers;return new Lease(root,false);
  }
  public synchronized Lease acquireRenderer() {
    if(closing || root==null || mediaUsers==0)throw new IllegalStateException("No active media context");
    if(rendererUsers>=4)throw new IllegalStateException("Renderer lease limit");
    ++rendererUsers;return new Lease(root,true);
  }
  private void releaseUnusedRoot() {
    if(root!=null && mediaUsers==0 && rendererUsers==0) {
      Root previous=root;root=null;previous.close();
    }
  }
  public synchronized int mediaUsers() {return mediaUsers;}
  public synchronized int rendererUsers() {return rendererUsers;}
  @Override public synchronized void close() {closing=true;releaseUnusedRoot();}
}
