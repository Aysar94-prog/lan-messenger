namespace LanMessenger;

// A04/A05 resource ownership, ported from android/src/net/lanmsg/chat/CallVideoResources.java.
//
// The Java original is about an EGL root; the ownership rule underneath it is platform-neutral and is
// the part that matters, so that is what is kept here behind an opaque Root. Closing the service
// prevents NEW users, but never destroys the shared root while a renderer is still holding a lease --
// otherwise a resize or a call-window teardown would pull the surface out from under a renderer that
// is mid-paint.
public sealed class CallVideoResources : IDisposable
{
    public interface IRoot : IDisposable
    {
        object? SharedContext { get; }
    }

    public interface IProvider
    {
        IRoot? Open();
    }

    readonly IProvider provider;
    IRoot? root;
    int mediaUsers, rendererUsers;
    bool closing;

    public CallVideoResources(IProvider provider)
    {
        this.provider = provider ?? throw new ArgumentNullException(nameof(provider));
    }

    public sealed class Lease : ICallVideoRendererLease
    {
        readonly CallVideoResources owner;
        readonly IRoot heldRoot;
        readonly bool renderer;
        bool released;

        internal Lease(CallVideoResources owner, IRoot heldRoot, bool renderer)
        {
            this.owner = owner; this.heldRoot = heldRoot; this.renderer = renderer;
        }

        public object? SharedContext
        {
            get
            {
                lock (owner)
                {
                    if (released) throw new InvalidOperationException("Context lease released");
                    return heldRoot.SharedContext;
                }
            }
        }

        public void Dispose()
        {
            lock (owner)
            {
                if (released) return;
                released = true;
                if (renderer) owner.rendererUsers--; else owner.mediaUsers--;
                owner.ReleaseUnusedRoot();
            }
        }
    }

    public Lease AcquireMedia()
    {
        lock (this)
        {
            if (closing) throw new InvalidOperationException("Video service is closing");
            if (mediaUsers >= 4) throw new InvalidOperationException("Media lease limit");
            if (root == null)
            {
                var created = provider.Open() ?? throw new InvalidOperationException("Video root unavailable");
                try
                {
                    if (created.SharedContext == null)
                        throw new InvalidOperationException("Video shared context unavailable");
                }
                catch
                {
                    created.Dispose();
                    throw;
                }
                root = created;
            }
            ++mediaUsers;
            return new Lease(this, root, false);
        }
    }

    public Lease AcquireRenderer()
    {
        lock (this)
        {
            if (closing || root == null || mediaUsers == 0)
                throw new InvalidOperationException("No active media context");
            if (rendererUsers >= 4) throw new InvalidOperationException("Renderer lease limit");
            ++rendererUsers;
            return new Lease(this, root, true);
        }
    }

    void ReleaseUnusedRoot()
    {
        if (root != null && mediaUsers == 0 && rendererUsers == 0)
        {
            var previous = root; root = null; previous.Dispose();
        }
    }

    public int MediaUsers { get { lock (this) return mediaUsers; } }
    public int RendererUsers { get { lock (this) return rendererUsers; } }

    public void Dispose() { lock (this) { closing = true; ReleaseUnusedRoot(); } }
}
