namespace LanMessenger;
sealed partial class ChatWindow
{
    record ContactItem(string Id,string Name,string Detail,bool Group=false,int Unread=0,long LastActivity=0,bool Online=false) { public override string ToString()=>Name; }

    void Render()
    {
        if(updatingView){renderPending=true;return;}
        updatingView=true;
        try{RenderCore();}
        finally{updatingView=false;if(renderPending&&IsHandleCreated&&!IsDisposed){renderPending=false;BeginInvoke(new Action(Render));}}
    }
    void RenderCore()
    {
        if(selected!=null&&Visible&&WindowState!=FormWindowState.Minimized)try{engine.MarkRead(selected);}catch{}
        var peers=engine.Peers;
        status.Text=$"{(engine.Running?"Available on your network":"Offline")}  ·  {peers.Count(p=>p.Online)} online  ·  {engine.Pending} queued  ·  Your ID: {engine.Id[..8]}";
        var groups=engine.Groups;
        // Most recently active conversation first, like a typical chat app — not name/online order.
        long LastActivity(string conversation){var items=engine.Messages(conversation);return items.Length>0?items.Max(m=>m.Time):0;}
        var entries=groups.Select(g=>new ContactItem(g.Id,g.Name,$"Group · {g.Members.Length} members",true,engine.Unread(g.Id),LastActivity(g.Id))).Concat(peers.Select(p=>new ContactItem(p.Id,p.Name,$"{(p.Online?"Online":"Offline")} · {p.Security}",false,engine.Unread(p.Id),LastActivity(p.Id),p.Online))).OrderByDescending(e=>e.LastActivity).ThenBy(e=>e.Name).ToArray();
        string signature=string.Join("|",entries.Select(p=>$"{p.Id}:{p.Name}:{p.Detail}:{p.Unread}:{p.LastActivity}"))+"|"+string.Join(",",peers.Select(p=>p.Id+":"+p.ReceivedAvatarHash));
        if(signature!=lastContacts){
            rendering=true;contacts.BeginUpdate();contacts.Items.Clear();contacts.Items.AddRange(entries);for(int i=0;i<contacts.Items.Count;i++)if(((ContactItem)contacts.Items[i]).Id==selected)contacts.SelectedIndex=i;contacts.EndUpdate();rendering=false;lastContacts=signature;
            // Decoded once here, not per paint — DrawItem below just reads this cache.
            foreach(var img in avatarCache.Values)img.Dispose();avatarCache.Clear();
            foreach(var p in peers)try{var raw=engine.PeerAvatar(p.Id);if(raw!=null){var img=TryImageThumbnail(raw,72,72);if(img!=null)avatarCache[p.Id]=img;}}catch{}
        }
        var peer=peers.FirstOrDefault(p=>p.Id==selected);var group=groups.FirstOrDefault(g=>g.Id==selected);
        verify.Enabled=peer!=null;members.Enabled=group!=null;clear.Enabled=selected!=null;send.Enabled=!sendBusy&&(group!=null||peer?.Trusted==true);fastTransfer.Enabled=attach.Enabled=send.Enabled;composer.Enabled=selected!=null&&!sendBusy;send.Text=sendBusy?"Preparing…":"Send";groupNotice.Visible=group!=null;groupNoticeRow.Height=group!=null?34:0;
        if(peer==null&&group==null){heading.Text="Your conversations, together";return;}
        heading.Text=group!=null?$"{group.Name} · {group.Members.Length} members":$"{peer!.Name} · {(peer.Online?"Online":"Offline")}";
        var items=engine.Messages(selected!);
        // Per-conversation view state: newest N rendered, scroll position, cached cards.
        if(!chatViews.TryGetValue(selected!,out var current)){current=new ChatViewState(selected!,Math.Min(InitialVisibleMessages,items.Length),0,feed.Width,TimestampMs(),true);chatViews[selected!]=current;}
        // A chat first opened with fewer than ten messages must grow up to ten
        // as new messages arrive; keep any older pages the user has loaded.
        var visibleCount=Math.Min(items.Length,Math.Max(InitialVisibleMessages,current.VisibleCount));
        if(current.VisibleCount<visibleCount){current=current with{VisibleCount=visibleCount};chatViews[selected!]=current;}
        int start=items.Length-visibleCount;
        bool moreOlder=start>0;
        var slice=items.Skip(start);
        var signatureFeed=selected+":"+feed.Width+":"+start+":"+string.Join("|",slice.Select(m=>m.From+":"+m.Id+":"+m.Status+":"+(m.FileName.Length>0?engine.HasAttachment(m)+":"+engine.Downloading(m):"")));
        if(lastFeed!=signatureFeed){lastFeed=signatureFeed;
            bool reset=feedConversation!=selected||feedWidth!=feed.Width;
            bool switching=feedConversation.Length>0&&feedConversation!=selected;
            bool bottom=reset||!feed.VerticalScroll.Visible||-feed.AutoScrollPosition.Y+feed.ClientSize.Height>=feed.DisplayRectangle.Height-60;
            var scroll=Math.Max(0,-feed.AutoScrollPosition.Y);
            var anchor=FindTopAnchor();
            feed.SuspendLayout();
            // Empty-chat hints are not message cards, so remove the previous one
            // before a rebuild or chat switch instead of accumulating labels.
            emptyHint?.Dispose();emptyHint=null;
            if(reset){
                if(switching)StashFeed(feedConversation);
                else if(feedConversation.Length>0)DiscardFeed();
                feed.AutoScrollPosition=Point.Empty;feed.Controls.Clear();cards.Clear();statusLabels.Clear();
                feedConversation=selected!;feedWidth=feed.Width;
                var cached=current.Cards;
                var cachedLabels=current.StatusLabels;
                bool cachedAlive=cached is {Count:>0}&&current.FeedWidth==feed.Width&&cachedLabels!=null&&cached.Values.All(v=>!v.card.IsDisposed);
                if(cachedAlive&&cached!=null&&cachedLabels!=null){
                    foreach(var pair in cached)cards[pair.Key]=pair.Value;
                    foreach(var pair in cachedLabels)statusLabels[pair.Key]=pair.Value;
                    foreach(var m in slice)if(cards.TryGetValue(m.From+"/"+m.Id,out var attached))feed.Controls.Add(attached.card);
                }else foreach(var pair in cached??[])if(!pair.Value.card.IsDisposed)pair.Value.card.Dispose();
                chatViews[selected!]=current with{VisibleCount=Math.Max(current.VisibleCount,visibleCount),FeedWidth=feed.Width,Cards=cachedAlive?current.Cards:null,StatusLabels=cachedAlive?current.StatusLabels:null};
            }
            var keys=slice.Select(m=>m.From+"/"+m.Id).ToHashSet();
            foreach(var key in cards.Keys.Where(k=>!keys.Contains(k)).ToArray()){cards[key].card.Dispose();cards.Remove(key);statusLabels.Remove(key);}
            foreach(var m in slice){var key=m.From+"/"+m.Id;var content=m.FileName.Length>0?engine.HasAttachment(m)+":"+engine.Downloading(m):"";
                if(cards.TryGetValue(key,out var old)&&old.content==content){cards[key]=(old.card,content,m);continue;}
                old.card?.Dispose();var card=MessageCard(m);cards[key]=(card,content,m);feed.Controls.Add(card);}
            if(moreOlder){olderHint??=MessageLabel("Older messages — scroll up to load",9,Color.SlateGray,Math.Max(300,feed.Width-30));if(!feed.Controls.Contains(olderHint))feed.Controls.Add(olderHint);}
            else if(olderHint!=null&&feed.Controls.Contains(olderHint))feed.Controls.Remove(olderHint);
            if(items.Length==0&&cards.Count==0){emptyHint=MessageLabel("A fresh start. Send a message or share a file.",11,Ink,Math.Max(300,feed.Width-30));feed.Controls.Add(emptyHint);}
            // Enforce chronological order: optional older-history hint first, then the rendered slice.
            var ordered=new List<Control>();if(moreOlder&&feed.Controls.Contains(olderHint!))ordered.Add(olderHint!);
            foreach(var m in slice)if(cards.TryGetValue(m.From+"/"+m.Id,out var placed))ordered.Add(placed.card);
            for(int i=ordered.Count-1;i>=0;i--)feed.Controls.SetChildIndex(ordered[i],i);
            feed.ResumeLayout(true);UpdateTransferLabels();
            if(reset){if(current.AtBottom)feed.AutoScrollPosition=new Point(0,feed.VerticalScroll.Maximum);else feed.AutoScrollPosition=new Point(0,Math.Min(current.ScrollY,feed.VerticalScroll.Maximum));}
            else if(bottom)feed.AutoScrollPosition=new Point(0,feed.VerticalScroll.Maximum);
            else RestoreAnchor(anchor);
            feed.Invalidate(true);var previous=(files.SelectedItem as FileItem)?.Message.Id;files.Items.Clear();foreach(var m in items.Where(m=>m.FileName.Length>0))files.Items.Add(new FileItem(m));if(files.Items.Count>0){files.SelectedIndex=0;for(int i=0;i<files.Items.Count;i++)if(((FileItem)files.Items[i]!).Message.Id==previous)files.SelectedIndex=i;}}
        files.Enabled=saveFile.Enabled=preview.Enabled=files.Items.Count>0;
    }
    // Detach the currently attached conversation's live cards into its view state, then bound
    // the retained chat set so switching stays cheap without unbounded control memory.
    void StashFeed(string conversation)
    {
        if(!chatViews.TryGetValue(conversation,out var view))return;
        var state=view with{
            Cards=new(cards),StatusLabels=new(statusLabels),
            ScrollY=Math.Max(0,-feed.AutoScrollPosition.Y),FeedWidth=feedWidth,LastUse=TimestampMs(),
            AtBottom=!feed.VerticalScroll.Visible||-feed.AutoScrollPosition.Y+feed.ClientSize.Height>=feed.DisplayRectangle.Height-60};
        chatViews[conversation]=state;
        EvictChatCache();
    }
    void DiscardFeed(){foreach(Control control in feed.Controls.Cast<Control>().ToArray())control.Dispose();}
    void EvictChatCache()
    {
        var cached=chatViews.Where(kv=>kv.Value.Cards is {Count:>0}).ToList();
        while((cached.Count>MaxCachedChats||cached.Sum(kv=>kv.Value.Cards!.Count)>MaxCachedCards)&&cached.Count>0){
            var lru=cached.OrderBy(kv=>kv.Value.LastUse).First();
            foreach(var pair in lru.Value.Cards!)pair.Value.card.Dispose();
            chatViews[lru.Key]=lru.Value with{Cards=null,StatusLabels=null};
            cached=chatViews.Where(kv=>kv.Value.Cards is {Count:>0}).ToList();
        }
    }
    // The wheel reaches the top: page in the next 20 older messages while keeping the current
    // first visible message pinned so the view cannot jump. One arrival loads exactly one page.
    void LoadOlderPage()
    {
        if(selected==null||loadingOlder)return;
        if(!chatViews.TryGetValue(selected,out var view)||view.VisibleCount>=engine.Messages(selected).Length)return;
        if(-feed.AutoScrollPosition.Y>24)return;
        loadingOlder=true;
        try{
            chatViews[selected]=view with{VisibleCount=Math.Min(engine.Messages(selected).Length,view.VisibleCount+OlderPageSize)};
            lastFeed="";Render();
        }finally{loadingOlder=false;}
    }
    (string? Key,int Offset) FindTopAnchor()
    {
        int s=Math.Max(0,-feed.AutoScrollPosition.Y);
        foreach(Control c in feed.Controls){
            if(c.Top+c.Height<=s)continue;
            string? key=c is FlowLayoutPanel?cards.FirstOrDefault(kv=>kv.Value.card==c).Key:null;
            if(key!=null)return(key,c.Top-s);
        }
        return(null,0);
    }
    void RestoreAnchor((string? Key,int Offset) anchor)
    {
        if(anchor.Key==null)return;
        if(!cards.TryGetValue(anchor.Key,out var entry)||entry.card.Parent==null)return;
        feed.AutoScrollPosition=new Point(0,Math.Max(0,entry.card.Top-anchor.Offset));
    }
    static long TimestampMs()=>DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
    public long ThumbnailCacheBytes=>thumbnails.Bytes;
    public long ThumbnailCacheMisses=>thumbnails.Misses;
    public long ThumbnailCacheHits=>thumbnails.Hits;
    public long ThumbnailCacheEvictions=>thumbnails.Evictions;
    public int CachedChatCount=>chatViews.Count(kv=>kv.Value.Cards is {Count:>0});
    public int CachedCardCount=>chatViews.Sum(kv=>kv.Value.Cards?.Count??0);
}
