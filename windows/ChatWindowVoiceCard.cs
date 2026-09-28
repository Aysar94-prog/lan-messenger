namespace LanMessenger;
// Voice Messages (Phase 2 / Windows), W07: Candidate/Fetching/Playable/Invalid marked
// content/Unavailable receiver cards, per tests/voice_messages/contract.md's Receiver
// states table. Called only for received (non-mine) messages whose filename parses as a
// voice marker — see ChatWindowMessages.cs MessageCard.
sealed partial class ChatWindow
{
    enum VoiceCardState { Candidate,Fetching,Playable,Invalid,Unavailable }

    // Classification stays separate from content validation (contract.md): MessageCard's
    // caller-side gate (VoiceMarker.Classify) already guarantees the marker parses and its id
    // matches before this is ever reached, so a genuine Candidate always starts here; only a
    // fully retrieved, WAV-validated file becomes Playable, and only a Candidate whose
    // retrieved bytes fail that validation becomes Invalid marked content — a mismatched or
    // unparsed marker is never routed here at all (it renders as an ordinary attachment from
    // MessageCard directly).
    (VoiceCardState state,VoiceWavValidation? validation) ClassifyVoiceMessage(PeerEngine.Message message)
    {
        if(engine.HasAttachment(message)){
            try{
                var validation=VoiceWav.Validate(engine.ReadAttachment(message));
                return (validation.Pass?VoiceCardState.Playable:VoiceCardState.Invalid,validation);
            }catch{return (VoiceCardState.Invalid,null);}
        }
        if(engine.Downloading(message))return (VoiceCardState.Fetching,null);
        // Not yet retrieved and not currently fetching. Unavailable is reserved for a message
        // whose conversation is definitively gone from the current list (contact forgotten or
        // group left) — everything else, including "no peer online right now", stays a
        // Candidate awaiting the next automatic or manual retrieval attempt.
        var stillReachable=message.GroupId.Length>0?engine.Groups.Any(g=>g.Id==message.GroupId):engine.Peers.Any(p=>p.Id==message.From);
        return (stillReachable?VoiceCardState.Candidate:VoiceCardState.Unavailable,null);
    }

    // Returns true once it has added the card's voice-specific content (the caller still adds
    // the caption text afterward, same as any other attachment message).
    bool AddVoiceCard(Control card,PeerEngine.Message message,int width)
    {
        var (state,validation)=ClassifyVoiceMessage(message);
        var row=new FlowLayoutPanel{FlowDirection=FlowDirection.LeftToRight,WrapContents=false,AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,Margin=new Padding(0,4,0,4)};
        switch(state){
            case VoiceCardState.Candidate:
                row.Controls.Add(MessageLabel("Voice message",11,Ink,width-140,true));
                var retryBtn=new Button{Text="Retrieve",AutoSize=true};retryBtn.Click+=(_,_)=>_=engine.DownloadAttachmentAsync(message);row.Controls.Add(retryBtn);
                break;
            case VoiceCardState.Fetching:
                var pct=transferProgress.TryGetValue(message.Id,out var p)&&p.total>0?$" {p.done*100/p.total}%":"";
                row.Controls.Add(MessageLabel("Voice message · Retrieving…"+pct,11,Ink,width-24));
                break;
            case VoiceCardState.Playable:
                var duration=validation?.Info!.DurationMs??0;
                var playKey="msg:"+message.From+"/"+message.Id;
                var durationLabel=MessageLabel(VoicePlaybackText(playKey,duration,""),11,Ink,width-220,true);
                row.Controls.Add(durationLabel);
                var playBtn=new Button{Text=activePlayerKey==playKey&&activePlayer!.Playing?"Pause":"Play",AutoSize=true};
                void Refresh(){if(durationLabel.IsDisposed||playBtn.IsDisposed)return;durationLabel.Text=VoicePlaybackText(playKey,duration,"");playBtn.Text=activePlayerKey==playKey&&activePlayer!=null&&activePlayer.Playing?"Pause":"Play";}
                playBtn.Click+=(_,_)=>TogglePlayback(playKey,()=>LoadVoiceContent(engine.ReadAttachment(message)),Refresh);row.Controls.Add(playBtn);
                var back=new Button{Text="-10s",AutoSize=true};back.Click+=(_,_)=>{SeekActivePlayer(playKey,-10000);Refresh();};row.Controls.Add(back);
                var fwd=new Button{Text="+10s",AutoSize=true};fwd.Click+=(_,_)=>{SeekActivePlayer(playKey,10000);Refresh();};row.Controls.Add(fwd);
                var saveBtn=new Button{Text="Save",AutoSize=true};saveBtn.Click+=(_,_)=>SaveAttachment(message);row.Controls.Add(saveBtn);
                break;
            case VoiceCardState.Invalid:
                // Falls back to the ordinary attachment treatment, backed by whatever copy is
                // already retrieved — never a second fetch attempt for this reason alone.
                int thumbWidth=Math.Min(420,width-24);
                var fileLabel=MessageLabel($"{message.FileName}  ·  {FormatSize(message.FileSize)}",10,Ink,width-24);fileLabel.Cursor=Cursors.Hand;fileLabel.Click+=async(_,_)=>await FileAction(message);row.Controls.Add(fileLabel);
                var available=engine.HasAttachment(message);var openBtn=new Button{Text=available?"Open":engine.Downloading(message)?"Pause":engine.PendingDestination(message).Length>0?"Resume":"Download",AutoSize=true};
                openBtn.Click+=async(_,_)=>await FileAction(message);row.Controls.Add(openBtn);
                break;
            case VoiceCardState.Unavailable:
                row.Controls.Add(MessageLabel("Voice message · No longer available",11,Color.SlateGray,width-24));
                break;
        }
        StyleButtons(row);
        card.Controls.Add(row);
        return true;
    }
}
