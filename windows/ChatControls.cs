namespace LanMessenger;
sealed class BufferedFeed : FlowLayoutPanel
{
    public BufferedFeed(){DoubleBuffered=true;}
    // Fired when the user scrolls upward while already at (or above) the top edge — the signal
    // to page in older messages. Guarded by the owning form so it cannot auto-repeat without
    // a fresh gesture.
    public event Action? TopReached;
    // Buffer the entire child-window subtree: buffering the panel alone leaves
    // native labels/buttons/pictures painted independently during ScrollWindowEx.
    protected override CreateParams CreateParams {get{var cp=base.CreateParams;cp.ExStyle|=0x02000000;return cp;}}
    protected override void OnScroll(ScrollEventArgs e){base.OnScroll(e);Invalidate(true);if(e.NewValue<e.OldValue&&-AutoScrollPosition.Y<=24)TopReached?.Invoke();}
    protected override void OnMouseWheel(MouseEventArgs e){base.OnMouseWheel(e);Invalidate(true);if(e.Delta>0&&-AutoScrollPosition.Y<=24)TopReached?.Invoke();}
    protected override void OnLayout(LayoutEventArgs e){base.OnLayout(e);Invalidate(true);}
}
sealed class MessageBubble : FlowLayoutPanel
{
    public MessageBubble(){DoubleBuffered=true;}
    protected override void OnPaintBackground(PaintEventArgs e){
        // Paint rounded corners, without applying an HWND region. Window regions
        // on scrolling containers can leave stale clipped child-window pixels.
        e.Graphics.Clear(Parent?.BackColor??BackColor);
        const int d=20;if(Width<d||Height<d){base.OnPaintBackground(e);return;}
        using var path=new System.Drawing.Drawing2D.GraphicsPath();
        path.AddArc(0,0,d,d,180,90);path.AddArc(Width-d,0,d,d,270,90);
        path.AddArc(Width-d,Height-d,d,d,0,90);path.AddArc(0,Height-d,d,d,90,90);path.CloseFigure();
        using var brush=new SolidBrush(BackColor);e.Graphics.FillPath(brush,path);
    }
}
// Per-conversation pagination and cache state. VisibleCount is the number of newest messages
// rendered (10 on first open, +20 per upward top-arrival); ScrollY/AtBottom preserve the view
// while the user is away. Cards/StatusLabels are the detachable live controls for one chat.
sealed record ChatViewState(string Conversation,int VisibleCount,int ScrollY,int FeedWidth,long LastUse,bool AtBottom=true,Dictionary<string,(Control card,string content,PeerEngine.Message message)>? Cards=null,Dictionary<string,Label>? StatusLabels=null);
// Bounded LRU of decoded attachment thumbnails. Bitmaps are reference counted: eviction only
// disposes images no card is still displaying; images still shown stay alive until the card
// releases them. Keys are owner message/hash/preview dimensions per conversation.
sealed class ThumbnailCache
{
    public const long DefaultLimit=16L*1024*1024;
    sealed class Entry{public required Image Image;public required string Conversation;public long Size;public int Refs;public bool Retired;public LinkedListNode<string> Node=null!;}
    readonly Dictionary<string,Entry> byKey=[];
    readonly Dictionary<string,Entry> retired=[];
    readonly Dictionary<string,HashSet<string>> members=[];
    readonly LinkedList<string> order=[];
    long bytes;
    public long Limit{get;}
    public long Bytes=>bytes;
    public long Entries=>byKey.Count;
    public long Hits,Misses,Evictions,Retirements;
    public ThumbnailCache(long limit=DefaultLimit){Limit=limit;}
    static long Estimate(Image image){try{return (long)image.Width*image.Height*4;}catch{return 262144;}}
    // Returns a cached image when available, otherwise decodes once. The caller owns one
    // reference until Release(key); eviction honors that reference.
    public Image? Acquire(string key,string conversation,Func<Image?> decode)
    {
        if(byKey.TryGetValue(key,out var existing)){Hits++;existing.Refs++;Touch(key);return existing.Image;}
        Misses++;var image=decode();if(image==null)return null;
        var size=Estimate(image);var entry=new Entry{Image=image,Conversation=conversation,Size=size,Refs=1};
        byKey[key]=entry;AddMember(conversation,key);entry.Node=order.AddLast(key);bytes+=size;
        EvictOverBudget();
        return image;
    }
    public void Release(string key)
    {
        if(retired.TryGetValue(key,out var re)){re.Refs=Math.Max(0,re.Refs-1);if(re.Refs==0){retired.Remove(key);bytes-=re.Size;DisposeImage(re);}return;}
        if(byKey.TryGetValue(key,out var entry))entry.Refs=Math.Max(0,entry.Refs-1);
    }
    void Touch(string key){if(order.Count<=1||!byKey.TryGetValue(key,out var e)||e.Retired)return;order.Remove(e.Node);e.Node=order.AddLast(key);}
    void EvictOverBudget()
    {
        while(bytes>Limit&&order.Count>0)
        {
            var victimKey=order.First!.Value;var victim=byKey[victimKey];
            if(victim.Refs>0){victim.Retired=true;Retirements++;retired[victimKey]=victim;byKey.Remove(victimKey);order.RemoveFirst();RemoveMember(victim.Conversation,victimKey);continue;}
            RemoveKey(victimKey,victim);
        }
    }
    void RemoveKey(string key,Entry entry)
    {
        byKey.Remove(key);order.Remove(entry.Node);bytes-=entry.Size;RemoveMember(entry.Conversation,key);Evictions++;
        DisposeImage(entry);
    }
    static void DisposeImage(Entry entry){try{entry.Image.Dispose();}catch{}}
    void AddMember(string conversation,string key){if(!members.TryGetValue(conversation,out var set))members[conversation]=set=[];set.Add(key);}
    void RemoveMember(string conversation,string key){if(members.TryGetValue(conversation,out var set)){set.Remove(key);if(set.Count==0)members.Remove(conversation);}}
    // Clearing a conversation must drop its cached thumbnails; ones still shown by a live card
    // are retired and disposed when that card is released.
    public void RemoveConversation(string conversation)
    {
        if(!members.TryGetValue(conversation,out var set))return;
        foreach(var key in set.ToArray())
            if(byKey.TryGetValue(key,out var entry))
            {
                byKey.Remove(key);order.Remove(entry.Node);RemoveMember(entry.Conversation,key);Evictions++;
                if(entry.Refs==0){bytes-=entry.Size;DisposeImage(entry);}else{entry.Retired=true;retired[key]=entry;}
            }
        members.Remove(conversation);
    }
}
