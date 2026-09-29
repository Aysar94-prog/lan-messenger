namespace LanMessenger;
// Voice Messages (Phase 2 / Windows), W08: the shared playback controller used by both the
// own-draft Preview row (ChatWindowVoice.cs) and a received Playable message's Play row
// (ChatWindowVoiceCard.cs). Exactly one VoicePlayer exists at a time (contract.md Decision
// 6): starting playback for a different key always stops whatever was playing first.
sealed partial class ChatWindow
{
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

    // Seek is offered as fixed -10 s/+10 s steps rather than a drag scrubber — a deliberate
    // scope simplification for this pass (still exercises the real seven-step VoiceSeek
    // procedure via VoicePlayer.Seek, just without scrubber UI/event-handling risk).
    void SeekActivePlayer(string key,long deltaMs)
    {
        if(activePlayerKey!=key||activePlayer==null)return;
        var newMs=Math.Max(0,activePlayer.PositionNs/1_000_000+deltaMs);
        try{activePlayer.Seek(newMs);}catch{}
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
