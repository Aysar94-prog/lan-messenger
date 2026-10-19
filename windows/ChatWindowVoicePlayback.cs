namespace LanMessenger;
// Voice Messages (Phase 2 / Windows), W08: the shared playback controller used by both the
// own-draft Preview row (ChatWindowVoice.cs) and a received Playable message's Play row
// (ChatWindowVoiceCard.cs). Exactly one VoicePlayer exists at a time (contract.md Decision
// 6): starting playback for a different key always stops whatever was playing first.
sealed partial class ChatWindow
{
    // WhatsApp-style icon glyphs instead of text labels, matching Android 2.2.3's identical change.
    const string PlayIcon="▶",PauseIcon="⏸";

    VoicePlayer? activePlayer;
    string? activePlayerKey;
    Action? activePlayerUiRefresh;

    void StopActivePlayer()
    {
        if(activePlayer==null)return;
        var p=activePlayer;var oldRefresh=activePlayerUiRefresh;activePlayer=null;activePlayerKey=null;activePlayerUiRefresh=null;
        try{p.Dispose();}catch{}
        // Without this, the row we just stopped keeps showing "Pause" (and a frozen elapsed
        // position) until something unrelated forces a full re-render — Render()'s signature
        // diff is keyed on message content, never on playback state, so nothing else would ever
        // correct it. Found by WT05's real one-active-player test switching between two cards.
        oldRefresh?.Invoke();
    }

    // Starts playback for `key`, or toggles Play/Pause if `key` is already the active player.
    // `load` decrypts and WAV-validates the content; it only runs when starting a NEW key, not
    // on every toggle or tick.
    void TogglePlayback(string key,Func<(byte[] wav,VoiceWavInfo info)> load,Action uiRefresh)
    {
        if(activePlayerKey==key&&activePlayer!=null){
            if(activePlayer.Playing)activePlayer.Pause();else activePlayer.Play();
            uiRefresh();
            return;
        }
        StopActivePlayer();
        (byte[] wav,VoiceWavInfo info) content;
        try{content=load();}
        catch(Exception e){MessageBox.Show(this,e.Message,"Could not play recording");return;}
        var player=new VoicePlayer(content.wav,content.info);
        player.Completed+=()=>{if(!IsDisposed&&IsHandleCreated)try{BeginInvoke(new Action(()=>{if(activePlayerKey==key)uiRefresh();}));}catch{}};
        player.DeviceFailed+=()=>{if(!IsDisposed&&IsHandleCreated)try{BeginInvoke(new Action(()=>{if(activePlayerKey==key){MessageBox.Show(this,"The playback device failed.","Playback");StopActivePlayer();uiRefresh();}}));}catch{}};
        try{player.Play();}
        catch(Exception e){try{player.Dispose();}catch{}MessageBox.Show(this,e.Message,"Could not play recording");return;}
        activePlayer=player;activePlayerKey=key;activePlayerUiRefresh=uiRefresh;
        uiRefresh();
    }


    // A draggable WhatsApp-style scrubber, matching Android 2.2.x's SeekBar. VoicePlayer.Seek(long)
    // already takes an absolute position, so the bar's Value maps onto it directly. The bar's own
    // Tag doubles as a "user is dragging" flag: UpdateSeekBar must not fight the user's mouse by
    // snapping the position back on every tick while a drag is in progress.
    TrackBar BuildSeekBar(string key)
    {
        var bar=new TrackBar{Minimum=0,Maximum=1,TickStyle=TickStyle.None,Width=180,Height=30,AccessibleName="Seek within this voice message"};
        bar.Tag=false;
        bar.MouseDown+=(_,_)=>bar.Tag=true;
        bar.MouseUp+=(_,_)=>{
            bar.Tag=false;
            if(activePlayerKey==key&&activePlayer!=null)try{activePlayer.Seek(bar.Value);}catch{}
        };
        return bar;
    }

    void UpdateSeekBar(string key,TrackBar bar,long durationMs)
    {
        if(bar.IsDisposed||bar.Tag is true)return;
        var max=(int)Math.Max(1,durationMs);
        if(bar.Maximum!=max)bar.Maximum=max;
        var pos=activePlayerKey==key&&activePlayer!=null?(int)Math.Min(max,activePlayer.PositionNs/1_000_000):0;
        bar.Value=Math.Max(bar.Minimum,Math.Min(bar.Maximum,pos));
    }

    // Called from the existing 1 s timer tick so the active player's row (elapsed time, and
    // whichever Play/Pause label reflects its state) stays current without a full re-render.
    void TickVoicePlayback()=>activePlayerUiRefresh?.Invoke();

    static (byte[] wav,VoiceWavInfo info) LoadVoiceContent(byte[] wav)
    {
        var validation=VoiceWav.Validate(wav);
        if(!validation.Pass)throw new IOException("This recording is no longer valid: "+validation.FailureReason);
        return (wav,validation.Info!);
    }

    string VoicePlaybackText(string key,long durationMs,string suffix)
    {
        var total=FormatElapsed(TimeSpan.FromMilliseconds(durationMs));
        if(activePlayerKey==key&&activePlayer!=null){
            var pos=FormatElapsed(TimeSpan.FromMilliseconds(activePlayer.PositionNs/1_000_000));
            return $"Voice message · {pos} / {total}{suffix}";
        }
        return $"Voice message · {total}{suffix}";
    }
}
