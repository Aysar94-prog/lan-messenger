namespace LanMessenger;
sealed partial class ChatWindow
{
    static Label MessageLabel(string text,float size,Color color,int width,bool bold=false)=>new(){Text=text,AutoSize=true,MaximumSize=new Size(width,0),Font=new Font("Segoe UI",size,bold?FontStyle.Bold:FontStyle.Regular),ForeColor=color,Margin=new Padding(0,2,0,2)};
    static Bitmap DrawInitialCircle(string initial,Color color,int size)
    {
        var bmp=new Bitmap(size,size);using var g=Graphics.FromImage(bmp);g.SmoothingMode=System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        using var b=new SolidBrush(color);g.FillEllipse(b,0,0,size,size);
        TextRenderer.DrawText(g,initial,new Font("Segoe UI",size*0.42f,FontStyle.Bold),new Rectangle(0,0,size,size),Color.White,TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter);
        return bmp;
    }
    // A small avatar next to the sender's name in every bubble: your own real picture for your own
    // messages when set, the sender's real picture once they've sent it over a verified connection,
    // a colored initial otherwise.
    Control SenderRow(bool mine,string senderId,string name,Color color,int width)
    {
        var row=new FlowLayoutPanel{FlowDirection=FlowDirection.LeftToRight,WrapContents=false,AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,Margin=new Padding(0)};
        const int avatarSize=18;
        var avatar=new PictureBox{Width=avatarSize,Height=avatarSize,SizeMode=PictureBoxSizeMode.Zoom,Margin=new Padding(0,0,5,0)};
        Image? peerPhoto=null;if(!mine)try{var raw=engine.PeerAvatar(senderId);if(raw!=null)peerPhoto=TryImageThumbnail(raw,64,64);}catch{}
        if(mine&&avatarImage!=null){var clone=new Bitmap(avatarImage);avatar.Image=clone;avatar.Disposed+=(_,_)=>clone.Dispose();}
        else if(peerPhoto!=null){avatar.Image=peerPhoto;avatar.Disposed+=(_,_)=>peerPhoto.Dispose();}
        else{var drawn=DrawInitialCircle(name.Length>0?name[..1].ToUpperInvariant():"?",color,64);avatar.Image=drawn;avatar.Disposed+=(_,_)=>drawn.Dispose();}
        row.Controls.Add(avatar);
        row.Controls.Add(MessageLabel(name,10,color,width-avatarSize-8,true));
        return row;
    }
    Control MessageCard(PeerEngine.Message message)
    {
        bool mine=message.From==engine.Id;int width=Math.Max(260,Math.Min(460,feed.Width-65));
        var card=new MessageBubble{FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,MinimumSize=new Size(width,0),MaximumSize=new Size(width,10000),Padding=new Padding(12,8,12,8),Margin=new Padding(mine?Math.Max(8,feed.Width-width-60):4,6,4,6),BackColor=mine?BubbleMine:BubbleOther};
        card.Controls.Add(SenderRow(mine,message.From,mine?"You":engine.DisplayName(message.From),mine?Accent:NameColor(message.From),width-24));
        if(message.FileName.Length==0)card.Controls.Add(MessageLabel(message.Text,12,Ink,width-24));
        else{
            int thumbWidth=Math.Min(420,width-24);var thumbKey=ThumbKey(message,thumbWidth,320);
            var thumbnail=CachedThumbnail(message,thumbKey,thumbWidth,320);
            if(thumbnail!=null){var picture=new PictureBox{Image=thumbnail,SizeMode=PictureBoxSizeMode.Zoom,Width=width-24,Height=Math.Max(150,Math.Min(320,(int)Math.Round((double)(width-24)*thumbnail.Height/thumbnail.Width))),Cursor=Cursors.Hand,BackColor=Color.FromArgb(232,236,243),Margin=new Padding(0,4,0,4)};picture.Click+=(_,_)=>PreviewImage(message);picture.Disposed+=(_,_)=>thumbnails.Release(thumbKey);card.Controls.Add(picture);}
            var fileLabel=MessageLabel($"{message.FileName}  ·  {FormatSize(message.FileSize)}",10,Ink,width-24);fileLabel.Cursor=Cursors.Hand;fileLabel.Click+=async(_,_)=>await FileAction(message);card.Controls.Add(fileLabel);
            var actions=new FlowLayoutPanel{AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,WrapContents=false,Margin=new Padding(0)};
            var available=engine.HasAttachment(message);var save=new Button{Text=available?"Open":engine.Downloading(message)?"Pause":engine.PendingDestination(message).Length>0?"Resume":"Download",AutoSize=true};
            save.Click+=async(_,_)=>await FileAction(message);actions.Controls.Add(save);StyleButtons(actions);card.Controls.Add(actions);
            if(message.Text.Length>0)card.Controls.Add(MessageLabel(message.Text,12,Ink,width-24));
        }
        var statusLabel=MessageLabel("",9,Color.SlateGray,width-24);statusLabels[message.From+"/"+message.Id]=statusLabel;card.Controls.Add(statusLabel);
        SetStatus(message,statusLabel);
        return card;
    }
    void SetStatus(PeerEngine.Message message,Label label)
    {
        bool mine=message.From==engine.Id;
        var when=DateTimeOffset.FromUnixTimeMilliseconds(message.Time).LocalDateTime.ToString("MMM d, HH:mm");
        if(mine){bool seen=message.Status.StartsWith("Seen");var ticks=seen||message.Status.StartsWith("Delivered")?"✓✓":"✓";
            var statusText=message.Status;
            if(message.Status=="Queued"&&message.FileName.Length>0&&transferProgress.TryGetValue(message.Id,out var progress)&&progress.total>0)statusText=$"Sending {progress.done*100/progress.total}%";
            label.Text=$"{when}  ·  {ticks} {statusText}";label.ForeColor=seen?SeenBlue:Color.SlateGray;}
        else label.Text=when+(engine.Downloading(message)?transferProgress.TryGetValue(message.Id,out var p)&&p.total>0?$"  ·  Downloading {p.done*100/p.total}%":"  ·  Waiting for sender…":"");
    }
    void UpdateTransferLabels(){if(selected==null)return;foreach(var pair in cards.Values)if(statusLabels.TryGetValue(pair.message.From+"/"+pair.message.Id,out var label))SetStatus(pair.message,label);}
    // Guarded by size: decoding an inline thumbnail means fully decrypting the attachment into
    // memory (ReadAttachment), which must stay off the table for anything near the 1 GB cap —
    // rendering a whole conversation's history would otherwise decrypt every large file in it.
    Image? TryImageThumbnail(PeerEngine.Message message,int maxWidth,int maxHeight){if(message.FileSize>ThumbnailPreviewCap)return null;try{return TryImageThumbnail(engine.ReadAttachment(message),maxWidth,maxHeight);}catch{return null;}}
    // Bounded LRU access for decoded attachment thumbnails (plan W05). Non-image files are
    // skipped without any read attempt. Keys cover owner message, hash, and preview size.
    string ThumbKey(PeerEngine.Message m,int w,int h)=>selected+"/"+m.From+"/"+m.Id+"/"+m.FileHash+"/"+w+"x"+h;
    Image? CachedThumbnail(PeerEngine.Message m,string key,int w,int h)
    {
        if(m.FileName.Length==0||!PeerEngine.IsImageAttachment(m))return null;
        return thumbnails.Acquire(key,selected!,()=>TryImageThumbnail(m,w,h));
    }
    static Image? TryImageThumbnail(byte[] data,int maxWidth,int maxHeight){try{using var stream=new MemoryStream(data);using var source=Image.FromStream(stream);if((long)source.Width*source.Height>32000000)throw new IOException();double scale=Math.Min(1,Math.Min((double)maxWidth/source.Width,(double)maxHeight/source.Height));var result=new Bitmap(Math.Max(1,(int)(source.Width*scale)),Math.Max(1,(int)(source.Height*scale)));using var graphics=Graphics.FromImage(result);graphics.InterpolationMode=System.Drawing.Drawing2D.InterpolationMode.HighQualityBicubic;graphics.DrawImage(source,new Rectangle(0,0,result.Width,result.Height));return result;}catch{return null;}}
    static string FormatSize(long bytes)=>bytes>=1024*1024?$"{bytes/1024.0/1024.0:0.#} MB":$"{bytes/1024.0:0.#} KB";
    static void StyleButtons(Control root){foreach(Control c in root.Controls){if(c is Button b){b.FlatStyle=FlatStyle.Flat;b.FlatAppearance.BorderColor=Color.FromArgb(217,226,239);b.BackColor=Color.White;b.ForeColor=Ink;b.Padding=new Padding(2);b.Cursor=Cursors.Hand;}StyleButtons(c);}}
}
