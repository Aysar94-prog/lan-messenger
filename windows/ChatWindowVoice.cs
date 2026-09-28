namespace LanMessenger;
// Voice Messages (Phase 2 / Windows), W04: Record/Stop/Preview/Delete/Send UI and the
// lifecycle-safe-stop wiring (see Program.cs: SelectedIndexChanged, FormClosing, FormClosed,
// the 1 s timer tick). Reuses the existing attachmentDraft/pendingAttachmentRow panel — a
// pending file attachment and an active/pending voice draft are mutually exclusive
// composition states, so sharing one panel is a deliberate simplification, not an oversight.
sealed partial class ChatWindow
{
    string? recordingDraftId;      // draft currently being recorded, if any (one recorder app-wide).
    string? recordingConversation; // conversation the active recording belongs to.
    VoiceRecorder? activeRecorder;
    VoiceDraftWriter? activeWriter;
    DateTime recordingStartedAtUtc;
    bool recordingStopping;
    string lastVoicePanel="";

    const long MaxRecordingSeconds=300;

    async void StartVoiceRecording()
    {
        if(selected==null||recordingDraftId!=null||!recordVoice.Enabled)return;
        var target=selected;var isGroup=engine.Groups.Any(g=>g.Id==target);
        string draftId;
        try{draftId=engine.CreateVoiceDraft(target,isGroup);}
        catch(Exception e){MessageBox.Show(this,e.Message,"Could not start recording");return;}
        VoiceDraftWriter writer;
        try{writer=engine.OpenVoiceDraftWriter(draftId);}
        catch(Exception e){engine.DeleteVoiceDraft(draftId);MessageBox.Show(this,e.Message,"Could not start recording");return;}
        var recorder=new VoiceRecorder();
        recordingDraftId=draftId;recordingConversation=target;activeWriter=writer;activeRecorder=recorder;recordingStartedAtUtc=DateTime.UtcNow;recordingStopping=false;
        recorder.FrameReady+=frame=>{var w=activeWriter;if(w!=null)_=SafeWriteFrame(w,frame.Payload);};
        recorder.DeviceFailed+=()=>{if(!IsDisposed&&IsHandleCreated)try{BeginInvoke(new Action(()=>FailVoiceRecording("The microphone was disconnected or failed.")));}catch{}};
        try{recorder.Start();}
        catch(Exception e){
            recordingDraftId=null;recordingConversation=null;activeWriter=null;activeRecorder=null;
            try{writer.Abort();}catch{}
            engine.DeleteVoiceDraft(draftId);
            MessageBox.Show(this,e.Message,"Could not start recording");
            return;
        }
        RenderVoicePanel();
    }

    // A write failure mid-recording (disk full, etc.) is exactly the same as a device failure
    // from the recorder's point of view: stop and diagnose, never leave a half-written draft
    // sitting in Recording state.
    async Task SafeWriteFrame(VoiceDraftWriter writer,byte[] pcm)
    {
        try{await writer.WriteFrame(pcm);}
        catch{if(!IsDisposed&&IsHandleCreated)try{BeginInvoke(new Action(()=>FailVoiceRecording("The recording could not be written to disk.")));}catch{}}
    }

    // Explicit Stop, or any required safe-stop trigger (conversation exit/switch, recording-UI
    // closure/hide-to-tray, Offline transition, shutdown, device failure, duration limit).
    // Always finalizes rather than silently discarding — the user still gets Preview/Delete on
    // the result, or a clear "could not be saved" message if finalization itself fails.
    async void StopVoiceRecording()
    {
        if(recordingDraftId==null||recordingStopping)return;
        recordingStopping=true;
        var draftId=recordingDraftId;var recorder=activeRecorder;var writer=activeWriter;
        recordingDraftId=null;recordingConversation=null;activeRecorder=null;activeWriter=null;
        try{
            var result=recorder?.Stop();
            if(writer!=null){
                if(result?.evt==VoicePcmEvent.DeviceFailure){writer.Abort();engine.InvalidateVoiceDraft(draftId);}
                else{
                    if(result?.finalFrame!=null)await writer.WriteFrame(result.Value.finalFrame!.Payload);
                    await writer.CloseAsync();
                    var validation=engine.FinalizeVoiceDraft(draftId);
                    if(!validation.Pass&&!IsDisposed)MessageBox.Show(this,"This recording could not be saved: "+validation.FailureReason,"Recording invalid");
                }
            }
            recorder?.Dispose();
        }catch(Exception e){
            try{writer?.Abort();}catch{}
            try{recorder?.Dispose();}catch{}
            engine.InvalidateVoiceDraft(draftId);
            if(!IsDisposed)MessageBox.Show(this,e.Message,"Recording could not be saved");
        }
        recordingStopping=false;
        if(!IsDisposed)RenderVoicePanel();
    }

    void FailVoiceRecording(string message)
    {
        if(recordingDraftId==null)return;
        MessageBox.Show(this,message,"Recording stopped");
        StopVoiceRecording();
    }

    // Called from Program.cs's existing 1 s timer tick: enforces the five-minute limit and
    // stops recording if the app has gone Offline, without a dedicated timer of its own.
    void TickVoiceRecording()
    {
        if(recordingDraftId==null)return;
        if(!engine.Running){StopVoiceRecording();return;}
        if((DateTime.UtcNow-recordingStartedAtUtc).TotalSeconds>=MaxRecordingSeconds){StopVoiceRecording();return;}
        if(recordingConversation==selected)UpdateVoiceClockLabel();
    }

    async void SendVoiceDraftClicked(string draftId)
    {
        var caption=selected==recordingConversationOf(draftId)?composer.Text:"";
        try{await engine.SendVoiceDraft(draftId,caption);if(selected!=null){composer.Clear();drafts.Remove(selected);}}
        catch(Exception e){if(!IsDisposed)MessageBox.Show(this,e.Message,"Could not send recording");}
        if(!IsDisposed){lastVoicePanel="";RenderVoicePanel();Render();}
    }
    string? recordingConversationOf(string draftId)=>engine.GetVoiceDraft(draftId)?.ConversationId;

    void DeleteVoiceDraftClicked(string draftId)
    {
        if(MessageBox.Show(this,"Delete this recording?","Delete recording",MessageBoxButtons.YesNo,MessageBoxIcon.Warning)!=DialogResult.Yes)return;
        engine.DeleteVoiceDraft(draftId);
        lastVoicePanel="";RenderVoicePanel();
    }

    // Rebuilds attachmentDraft's contents for the recording/pending-draft state. A pending
    // file attachment (pendingAttachmentPath) always takes priority, matching how it already
    // owns this panel; voice state only renders when there is no pending file attachment.
    void RenderVoicePanel()
    {
        if(pendingAttachmentPath!=null)return; // RenderPendingAttachment owns the panel here.
        var recordingHere=recordingConversation!=null&&recordingConversation==selected;
        var draft=selected!=null?engine.VoiceDraftsFor(selected).LastOrDefault(d=>d.State==VoiceDraftState.Finalized):null;
        var signature=recordingHere?"rec:"+recordingDraftId:draft!=null?"draft:"+draft.Id+":"+draft.State:"none";
        if(signature==lastVoicePanel&&!recordingHere){return;}
        lastVoicePanel=signature;
        foreach(Control control in attachmentDraft.Controls.Cast<Control>().ToArray())control.Dispose();attachmentDraft.Controls.Clear();
        if(recordingHere){
            attachmentDraft.Visible=true;pendingAttachmentRow.Height=60;
            var row=new FlowLayoutPanel{FlowDirection=FlowDirection.LeftToRight,WrapContents=false,AutoSize=true};
            row.Controls.Add(MessageLabel("Recording…  "+FormatElapsed(DateTime.UtcNow-recordingStartedAtUtc),11,Ink,220,true));
            attachmentDraft.Controls.Add(row);
        }else if(draft!=null){
            attachmentDraft.Visible=true;pendingAttachmentRow.Height=70;
            var row=new FlowLayoutPanel{FlowDirection=FlowDirection.LeftToRight,WrapContents=false,AutoSize=true};
            var playKey="draft:"+draft.Id;
            var durationLabel=MessageLabel(VoicePlaybackText(playKey,draft.DurationMs,engine.VoiceDraftSendable(draft.Id)?"":"  ·  This conversation can no longer receive messages"),10,Ink,240,true);
            row.Controls.Add(durationLabel);
            var playBtn=new Button{Text=activePlayerKey==playKey&&activePlayer!.Playing?"Pause":"Play",AutoSize=true};
            void Refresh(){if(durationLabel.IsDisposed||playBtn.IsDisposed)return;durationLabel.Text=VoicePlaybackText(playKey,draft.DurationMs,engine.VoiceDraftSendable(draft.Id)?"":"  ·  This conversation can no longer receive messages");playBtn.Text=activePlayerKey==playKey&&activePlayer!=null&&activePlayer.Playing?"Pause":"Play";}
            playBtn.Click+=(_,_)=>TogglePlayback(playKey,()=>LoadVoiceContent(engine.ReadVoiceDraftWav(draft.Id)),Refresh);row.Controls.Add(playBtn);
            var back=new Button{Text="-10s",AutoSize=true};back.Click+=(_,_)=>{SeekActivePlayer(playKey,-10000);Refresh();};row.Controls.Add(back);
            var fwd=new Button{Text="+10s",AutoSize=true};fwd.Click+=(_,_)=>{SeekActivePlayer(playKey,10000);Refresh();};row.Controls.Add(fwd);
            var deleteBtn=new Button{Text="Delete",AutoSize=true};deleteBtn.Click+=(_,_)=>DeleteVoiceDraftClicked(draft.Id);row.Controls.Add(deleteBtn);
            if(engine.VoiceDraftSendable(draft.Id)){var sendBtn=new Button{Text="Send",AutoSize=true};sendBtn.Click+=(_,_)=>SendVoiceDraftClicked(draft.Id);row.Controls.Add(sendBtn);}
            StyleButtons(row);
            attachmentDraft.Controls.Add(row);
        }else{
            attachmentDraft.Visible=false;pendingAttachmentRow.Height=0;
        }
    }

    void UpdateVoiceClockLabel()
    {
        if(pendingAttachmentPath!=null||attachmentDraft.Controls.Count==0)return;
        if(attachmentDraft.Controls[0] is FlowLayoutPanel row&&row.Controls.Count>0&&row.Controls[0] is Label label)
            label.Text="Recording…  "+FormatElapsed(DateTime.UtcNow-recordingStartedAtUtc);
    }

    static string FormatElapsed(TimeSpan span)=>span.TotalHours>=1?span.ToString(@"h\:mm\:ss"):span.ToString(@"m\:ss");
}
