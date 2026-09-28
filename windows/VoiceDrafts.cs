using System.Security.Cryptography;
using System.Text;
namespace LanMessenger;

// Voice Messages (Phase 2 / Windows), W02: the durable application-private draft registry.
// Recording/playback device access (W03) and UI (W04) are separate, later tasks; this file
// only persists draft metadata and encrypted PCM/WAV bytes, matching the existing attachment
// store's encryption construction (Conversations.cs StoreAttachmentStream/OpenAttachmentPlaintext).

public enum VoiceDraftState { Recording, Finalized, Invalid }

// Registry fields per tests/voice_messages/contract.md "Draft recovery > Registry fields":
// opaque id, owning conversation identity + type, created/updated timestamps, storage
// identity (Id itself, since VoiceDraftPath is derived from it and validated as a UUID),
// recording/finalization state, expected audio format (always the fixed contract format),
// byte-size/duration metadata, and a Send-transaction association used only during Send.
public sealed record VoiceDraft(
    string Id,string ConversationId,bool IsGroup,long CreatedAt,long UpdatedAt,
    VoiceDraftState State,long ByteSize,long DurationMs,string SendTransactionId="")
{
    public const string ExpectedFormat="pcm16000-16bit-mono";
    const long StaleReviewAgeMs=30L*24*60*60*1000;
    // Age alone never silently deletes a valid finalized draft (Registry rule 5) — this is
    // purely an advisory read, never a trigger for automatic removal.
    public bool IsStale(long nowMs)=>State==VoiceDraftState.Finalized&&nowMs-UpdatedAt>=StaleReviewAgeMs;
}

public sealed partial class PeerEngine
{
    public const int VoiceDraftCap=10;
    static readonly byte[] VoiceDraftMagic=Encoding.ASCII.GetBytes("LMVOICE1");
    readonly Dictionary<string,VoiceDraft> voiceDrafts=[];
    readonly HashSet<string> openVoiceDraftWriters=[];

    string VoiceDraftPath(string draftId){if(!Uuid(draftId))throw new IOException("Invalid draft ID");return Path.Combine(Path.GetDirectoryName(file)!,"voice-drafts",draftId+".sec");}

    public IReadOnlyList<VoiceDraft> VoiceDraftsFor(string conversation){lock(gate)return voiceDrafts.Values.Where(d=>d.ConversationId==conversation).OrderBy(d=>d.CreatedAt).ToArray();}
    public VoiceDraft? GetVoiceDraft(string draftId){lock(gate)return voiceDrafts.TryGetValue(draftId,out var d)?d:null;}

    // Registry rule 7/8: a draft whose conversation can no longer send is Preview/Delete-only
    // and is never retargeted — this only ever reports whether Send is currently allowed; it
    // never changes ConversationId.
    public bool VoiceDraftSendable(string draftId)
    {
        lock(gate){
            if(!voiceDrafts.TryGetValue(draftId,out var d)||d.State!=VoiceDraftState.Finalized)return false;
            return d.IsGroup?groups.ContainsKey(d.ConversationId):peers.ContainsKey(d.ConversationId);
        }
    }

    // Ten-step durable write order, step 1: create and durably register draft ownership before
    // any microphone frame is accepted. The 10-draft cap blocks only new creation; every
    // existing draft stays fully available for Preview/Send/Delete/recovery (Registry rule 3).
    public string CreateVoiceDraft(string conversation,bool isGroup)
    {
        lock(gate){
            if(voiceDrafts.Count>=VoiceDraftCap)throw new IOException($"Voice draft limit reached ({VoiceDraftCap}). Send or delete an existing draft first.");
            var id=Guid.NewGuid().ToString();var at=Now;
            voiceDrafts[id]=new VoiceDraft(id,conversation,isGroup,at,at,VoiceDraftState.Recording,0,0);
            try{Save();}catch{voiceDrafts.Remove(id);throw;}
            return id;
        }
    }

    // Steps 2-3: begin accepting microphone frames and write normalized PCM straight to
    // application-private storage. Exactly one writer per draft (mirrors the one-recorder
    // rule); the caller (W03/W04) drives WriteFrame per assembled VoicePcmFrame.
    public VoiceDraftWriter OpenVoiceDraftWriter(string draftId)
    {
        lock(gate){
            if(!voiceDrafts.TryGetValue(draftId,out var d))throw new IOException("Unknown voice draft.");
            if(d.State!=VoiceDraftState.Recording)throw new IOException("Draft is not in a recording state.");
            if(!openVoiceDraftWriters.Add(draftId))throw new IOException("Draft already has an open writer.");
            var path=VoiceDraftPath(draftId);Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            return new VoiceDraftWriter(this,draftId,path,protector);
        }
    }

    internal void CloseVoiceDraftWriter(string draftId){lock(gate)openVoiceDraftWriters.Remove(draftId);}

    // Keeps the in-memory byte-size figure current (for live UI feedback, e.g. elapsed size)
    // as frames land. Deliberately in-memory only, not persisted: a Recording-state entry is
    // unconditionally diagnosed Invalid by ReconcileVoiceDrafts on the next startup regardless
    // of its recorded byte size (no writer ever survives a process exit), so durably rewriting
    // the whole encrypted store on every ~20 ms frame would buy zero recovery benefit at a
    // real cost — a 5-minute recording is up to ~15,000 frames.
    internal void RecordVoiceDraftProgress(string draftId,long byteSize)
    {
        lock(gate){
            if(!voiceDrafts.TryGetValue(draftId,out var d))return;
            voiceDrafts[draftId]=d with{ByteSize=byteSize};
        }
    }

    // Steps 4-6: safely finalize the WAV, flush and validate the completed file, and mark the
    // draft recoverable only after that validation actually passes. A failure here — including
    // one raised by the caller via Invalidate below, e.g. a terminal PCM DeviceFailure — leaves
    // a diagnosed invalid entry (crash-outcome 3), never a phantom playable/sendable draft.
    public VoiceWavValidation FinalizeVoiceDraft(string draftId)
    {
        lock(gate){
            if(!voiceDrafts.TryGetValue(draftId,out var d))throw new IOException("Unknown voice draft.");
            var before=d;
            var path=VoiceDraftPath(draftId);
            byte[] pcm;
            try{pcm=ReadVoiceDraftPlaintext(path);}
            catch(Exception){voiceDrafts[draftId]=d with{State=VoiceDraftState.Invalid,UpdatedAt=Now};try{Save();}catch{}throw;}
            byte[] wav;
            try{wav=VoiceWav.BuildCanonical(pcm);}
            catch(Exception){voiceDrafts[draftId]=d with{State=VoiceDraftState.Invalid,UpdatedAt=Now};try{Save();}catch{}throw;}
            var validation=VoiceWav.Validate(wav);
            if(!validation.Pass){
                voiceDrafts[draftId]=d with{State=VoiceDraftState.Invalid,UpdatedAt=Now};
                try{Save();}catch{voiceDrafts[draftId]=before;}
                return validation;
            }
            WriteVoiceDraftPlaintextAtomic(path,wav);
            voiceDrafts[draftId]=d with{State=VoiceDraftState.Finalized,ByteSize=wav.Length,DurationMs=validation.Info!.DurationMs,UpdatedAt=Now};
            try{Save();}catch{voiceDrafts[draftId]=before;throw;}
            return validation;
        }
    }

    // Explicit path for a terminal recording failure (PCM DeviceFailure, or the odd-byte-at-end
    // failure from VoicePcmAssembler.End()) that must never be finalized: diagnose the entry as
    // Invalid immediately rather than leaving it stuck in Recording state forever.
    public void InvalidateVoiceDraft(string draftId)
    {
        lock(gate){
            if(!voiceDrafts.TryGetValue(draftId,out var d))return;
            voiceDrafts[draftId]=d with{State=VoiceDraftState.Invalid,UpdatedAt=Now};
            try{Save();}catch{}
        }
    }

    // Preview/Delete-only drafts, drafts the user chooses to discard, and step 10's post-Send
    // cleanup all go through here. Best-effort file delete: the registry row is the durable
    // source of truth, so a leftover encrypted file with no registry row is harmless orphan
    // data, never a recoverable-but-untracked draft.
    public void DeleteVoiceDraft(string draftId)
    {
        lock(gate){
            if(!voiceDrafts.Remove(draftId,out var removed))return;
            try{Save();}catch{voiceDrafts[draftId]=removed;throw;}
            try{File.Delete(VoiceDraftPath(draftId));}catch{}
        }
    }

    // Associates a draft with an in-flight Send so a crash between "queued the message" and
    // "removed the registry entry" (step 10) can be told apart from an ordinary still-recording
    // draft during reconciliation.
    internal void MarkVoiceDraftSendTransaction(string draftId,string transactionId)
    {
        lock(gate){
            if(!voiceDrafts.TryGetValue(draftId,out var d))return;
            voiceDrafts[draftId]=d with{SendTransactionId=transactionId,UpdatedAt=Now};
            try{Save();}catch{}
        }
    }

    // Startup reconciliation: every registry entry must resolve to exactly one of the three
    // allowed crash outcomes (contract.md "Crash-boundary reconciliation"). A Recording entry
    // left open by a crash can never safely resume (no writer survives a process exit), so it
    // is diagnosed Invalid rather than silently treated as finalized or quietly dropped —
    // dropping it would violate "never produce ... an unreachable valid draft" by making a
    // partially-written file impossible to explain to the user. A Finalized entry is re-
    // validated against its actual bytes on disk, since a crash between step 5 (validate) and
    // step 6 (mark recoverable) is exactly the boundary this guards.
    public void ReconcileVoiceDrafts()
    {
        lock(gate){
            var updates=new Dictionary<string,VoiceDraft>();
            foreach(var d in voiceDrafts.Values){
                if(d.State==VoiceDraftState.Recording){updates[d.Id]=d with{State=VoiceDraftState.Invalid,UpdatedAt=Now};continue;}
                if(d.State!=VoiceDraftState.Finalized)continue;
                var path=VoiceDraftPath(d.Id);
                try{
                    var wav=ReadVoiceDraftPlaintext(path);
                    var validation=VoiceWav.Validate(wav);
                    if(!validation.Pass)updates[d.Id]=d with{State=VoiceDraftState.Invalid,UpdatedAt=Now};
                }catch{updates[d.Id]=d with{State=VoiceDraftState.Invalid,UpdatedAt=Now};}
            }
            if(updates.Count==0)return;
            foreach(var kv in updates)voiceDrafts[kv.Key]=kv.Value;
            Save();
        }
    }

    // --- Encrypted plaintext read/write, matching Conversations.cs's attachment-store construction ---

    byte[] ReadVoiceDraftPlaintext(string path)
    {
        using var f=new FileStream(path,FileMode.Open,FileAccess.Read);
        var magic=new byte[VoiceDraftMagic.Length];f.ReadExactly(magic);
        if(!magic.AsSpan().SequenceEqual(VoiceDraftMagic))throw new IOException("Invalid voice draft file.");
        var lengthBytes=new byte[4];f.ReadExactly(lengthBytes);if(BitConverter.IsLittleEndian)Array.Reverse(lengthBytes);int headerLen=BitConverter.ToInt32(lengthBytes);
        if(headerLen<1||headerLen>65536)throw new IOException("Invalid voice draft header.");
        var header=new byte[headerLen];f.ReadExactly(header);
        var raw=protector.Unprotect(header);
        using var aes=Aes.Create();aes.KeySize=256;aes.Key=raw[..32];aes.IV=raw[32..48];
        using var crypto=new CryptoStream(f,aes.CreateDecryptor(),CryptoStreamMode.Read);
        using var plain=new MemoryStream();crypto.CopyTo(plain);
        return plain.ToArray();
    }

    void WriteVoiceDraftPlaintextAtomic(string path,byte[] plaintext)
    {
        var tmp=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        using(var aes=Aes.Create()){
            aes.KeySize=256;aes.GenerateKey();aes.GenerateIV();
            var header=protector.Protect([..aes.Key,..aes.IV]);
            using(var f=new FileStream(tmp,FileMode.Create,FileAccess.Write)){
                f.Write(VoiceDraftMagic);
                var lengthBytes=BitConverter.GetBytes(header.Length);if(BitConverter.IsLittleEndian)Array.Reverse(lengthBytes);
                f.Write(lengthBytes);f.Write(header);
                using(var crypto=new CryptoStream(f,aes.CreateEncryptor(),CryptoStreamMode.Write,leaveOpen:true)){crypto.Write(plaintext);crypto.FlushFinalBlock();}
                f.Flush(true);
            }
        }
        try{File.Move(tmp,path,true);}catch{try{File.Delete(tmp);}catch{}throw;}
    }
}

// An open, in-progress recording session for one draft. One instance per OpenVoiceDraftWriter
// call; the caller (W03/W04) feeds it VoicePcmAssembler frame payloads as they are produced
// and calls CloseAsync() on normal Stop or Abort() on any interruption that must not finalize.
public sealed class VoiceDraftWriter : IDisposable
{
    readonly PeerEngine engine;
    readonly string draftId,path,tmpPath;
    FileStream? file;
    CryptoStream? crypto;
    Aes? aes;
    long written;
    bool closed;

    internal VoiceDraftWriter(PeerEngine engine,string draftId,string path,IStorageProtector protector)
    {
        this.engine=engine;this.draftId=draftId;this.path=path;tmpPath=path+"."+Guid.NewGuid().ToString("N")+".tmp";
        aes=Aes.Create();aes.KeySize=256;aes.GenerateKey();aes.GenerateIV();
        var header=protector.Protect([..aes.Key,..aes.IV]);
        file=new FileStream(tmpPath,FileMode.Create,FileAccess.Write);
        file.Write(Encoding.ASCII.GetBytes("LMVOICE1"));
        var lengthBytes=BitConverter.GetBytes(header.Length);if(BitConverter.IsLittleEndian)Array.Reverse(lengthBytes);
        file.Write(lengthBytes);file.Write(header);
        crypto=new CryptoStream(file,aes.CreateEncryptor(),CryptoStreamMode.Write,leaveOpen:true);
    }

    public async Task WriteFrame(byte[] pcm)
    {
        if(closed)throw new IOException("Draft writer already closed.");
        await crypto!.WriteAsync(pcm);
        written+=pcm.Length;
        engine.RecordVoiceDraftProgress(draftId,written);
    }

    // Recording stopped normally (explicit Stop or a safe lifecycle-triggered stop): flush the
    // encrypted file to disk and publish it atomically. Finalization (WAV build + validation)
    // is a separate step — see PeerEngine.FinalizeVoiceDraft — matching steps 3 and 4 of the
    // ten-step order.
    public async Task CloseAsync()
    {
        if(closed)throw new IOException("Draft writer already closed.");
        closed=true;
        await crypto!.FlushFinalBlockAsync();crypto.Dispose();crypto=null;
        await file!.FlushAsync();file.Flush(true);file.Dispose();file=null;
        aes?.Dispose();aes=null;
        File.Move(tmpPath,path,true);
        engine.CloseVoiceDraftWriter(draftId);
    }

    // A terminal PCM failure (device loss, odd-byte-at-end) or an unrecoverable local error:
    // discard the partial file. The registry entry is separately marked Invalid by the caller
    // via PeerEngine.InvalidateVoiceDraft, since only the caller knows why the writer aborted.
    public void Abort()
    {
        if(closed)return;closed=true;
        try{crypto?.Dispose();}catch{}crypto=null;
        try{file?.Dispose();}catch{}file=null;
        aes?.Dispose();aes=null;
        try{File.Delete(tmpPath);}catch{}
        engine.CloseVoiceDraftWriter(draftId);
    }

    public void Dispose(){if(!closed)Abort();}
}
