namespace LanMessenger;

// Voice Messages (Phase 2 / Windows). Transport-independent per tests/voice_messages/contract.md
// (I01) and pcm-contract.md (I02). This file must not depend on WinForms, PeerEngine, the
// attachment store, LM4 types, or any device API — only platform adapters (later tasks) may
// touch those. Constants and behavior mirror the frozen contract exactly.

public enum VoicePcmEvent { End, Discontinuity, DeviceFailure, Restart }

public sealed class VoicePcmFrame
{
    public int Sequence;
    public long TimestampNs;
    public byte[] Payload = [];
}

// Assembles raw (possibly fragmented, possibly oversized) device callback bytes into complete
// PCM frames per pcm-contract.md. One instance covers exactly one epoch; Restart() begins a new
// one. Frames are handed to the caller synchronously via Push()'s return value — the "pending
// capacity" backpressure model applies to the caller's own downstream queue, not to this class.
public sealed class VoicePcmAssembler
{
    public const int FrameBytes = 640;
    public const int FrameSamples = 320;
    public const long MaxTotalBytes = 9_600_000;
    const long NsPerSample = 62500; // 1e9 / 16000 Hz

    readonly List<byte> carry = new(FrameBytes);
    int nextSequence;
    long completedSamples;
    long acceptedBytes;
    bool ended;
    bool terminallyFailed;

    public bool TerminallyFailed => terminallyFailed;
    public bool Ended => ended;
    public int PendingOddBytes => carry.Count % 2;

    // Splits `fragment` into complete 640-byte frames, carrying any partial trailing bytes
    // (0..639) over to the next Push. Rejects input beyond the 9,600,000-byte maximum instead
    // of truncating it, per contract.md's "Maximum PCM data" / pcm-contract.md Capacity item 5.
    public List<VoicePcmFrame> Push(byte[] fragment)
    {
        if(terminallyFailed)throw new InvalidOperationException("epoch already terminally failed");
        if(ended)throw new InvalidOperationException("stream already ended; Restart() begins a new epoch");
        if(acceptedBytes+fragment.Length>MaxTotalBytes)throw new InvalidOperationException("input exceeds the 9,600,000-byte PCM maximum; rejected, never truncated");
        acceptedBytes+=fragment.Length;
        var frames=new List<VoicePcmFrame>();
        int i=0;
        while(i<fragment.Length){
            int take=Math.Min(FrameBytes-carry.Count,fragment.Length-i);
            for(int k=0;k<take;k++)carry.Add(fragment[i+k]);
            i+=take;
            if(carry.Count==FrameBytes){
                frames.Add(EmitFrame(carry.ToArray()));
                carry.Clear();
            }
        }
        return frames;
    }

    VoicePcmFrame EmitFrame(byte[] payload)
    {
        int samples=payload.Length/2;
        var frame=new VoicePcmFrame{Sequence=nextSequence++,TimestampNs=(completedSamples+samples)*NsPerSample,Payload=payload};
        completedSamples+=samples;
        return frame;
    }

    // Marks a gap in the sample stream without closing the epoch; sequence/timestamp continue.
    public VoicePcmEvent Discontinuity()
    {
        if(terminallyFailed||ended)throw new InvalidOperationException("epoch not accepting events");
        return VoicePcmEvent.Discontinuity;
    }

    // Normal termination. Any pending even-length partial buffer becomes a final short (but
    // complete-sample) frame; an odd trailing byte (an incomplete sample) is fatal — the epoch
    // fails terminally and no truncated frame is emitted.
    public (VoicePcmFrame? finalFrame,VoicePcmEvent evt) End()
    {
        if(terminallyFailed)throw new InvalidOperationException("epoch already terminally failed");
        if(ended)throw new InvalidOperationException("stream already ended");
        if(carry.Count%2!=0){
            terminallyFailed=true;
            carry.Clear();
            return (null,VoicePcmEvent.DeviceFailure);
        }
        ended=true;
        if(carry.Count==0)return (null,VoicePcmEvent.End);
        var final=EmitFrame(carry.ToArray());
        carry.Clear();
        return (final,VoicePcmEvent.End);
    }

    // Device loss mid-recording: terminal for this epoch; the draft becomes invalid.
    public VoicePcmEvent DeviceFailure()
    {
        terminallyFailed=true;
        carry.Clear();
        return VoicePcmEvent.DeviceFailure;
    }

    // Closes the current epoch and resets sequence/timestamp/carry state for a new one.
    public VoicePcmEvent Restart()
    {
        nextSequence=0;completedSamples=0;acceptedBytes=0;carry.Clear();ended=false;terminallyFailed=false;
        return VoicePcmEvent.Restart;
    }
}

public enum VoiceMarkerClassification { Candidate, OrdinaryAttachment, InvalidMarkedContent }

public static class VoiceMarker
{
    const string Prefix="voice-";
    const string Suffix=".lanvoice.wav";

    public static string FileName(string messageId)=>Prefix+messageId+Suffix;

    // Parses the marker syntax only; does not validate that the id matches the attachment's
    // actual message id (callers cross-check that, and content validation is separate — see
    // contract.md "Candidate classification remains separate from content validation").
    public static bool TryParse(string fileName,out string messageId)
    {
        messageId="";
        if(!fileName.StartsWith(Prefix,StringComparison.Ordinal)||!fileName.EndsWith(Suffix,StringComparison.Ordinal))return false;
        var id=fileName[Prefix.Length..^Suffix.Length];
        if(id.Length==0)return false;
        messageId=id;
        return true;
    }

    // Classifies a received attachment for receiver-UI purposes. `store` must be "Normal";
    // Voice Messages never use Fast storage (contract.md Decision 2).
    public static VoiceMarkerClassification Classify(string fileName,string store,string attachmentMessageId)
    {
        if(!TryParse(fileName,out var extractedId))return VoiceMarkerClassification.OrdinaryAttachment;
        if(store!="Normal")return VoiceMarkerClassification.InvalidMarkedContent;
        if(extractedId!=attachmentMessageId)return VoiceMarkerClassification.InvalidMarkedContent;
        return VoiceMarkerClassification.Candidate;
    }
}

public sealed class VoiceWavInfo
{
    public required int SampleRate;
    public required int Channels;
    public required int BitsPerSample;
    public required long DataBytes;
    public required long DataOffset;
    public required long TotalBytes;
    public required long DurationMs;
}

public sealed class VoiceWavValidation
{
    public required bool Pass;
    public string? FailureReason;
    public VoiceWavInfo? Info;
    public static VoiceWavValidation Fail(string reason)=>new(){Pass=false,FailureReason=reason};
    public static VoiceWavValidation Ok(VoiceWavInfo info)=>new(){Pass=true,Info=info};
}

// Bounded streaming WAV validation and canonical WAV read/write per contract.md's 14-item
// checklist. Only the fixed contract format (16 kHz mono 16-bit PCM) is ever accepted.
public static class VoiceWav
{
    public const int SampleRate=16000;
    public const int Channels=1;
    public const int BitsPerSample=16;
    public const int BlockAlign=2;
    public const int ByteRate=32000;
    public const long MaxDataBytes=9_600_000;
    public const long MaxTotalBytes=9_600_044;
    public const int CanonicalHeaderBytes=44;

    // Builds a canonical 44-byte-header WAV around already-validated contract-format PCM data.
    public static byte[] BuildCanonical(byte[] pcmData)
    {
        if(pcmData.Length>MaxDataBytes)throw new IOException("PCM data exceeds the 9,600,000-byte maximum.");
        if(pcmData.Length%BlockAlign!=0)throw new IOException("PCM data length must be a whole number of samples.");
        var total=CanonicalHeaderBytes+pcmData.Length;
        var buf=new byte[total];
        void Ascii(int offset,string s){for(int i=0;i<s.Length;i++)buf[offset+i]=(byte)s[i];}
        void U32(int offset,uint v){buf[offset]=(byte)v;buf[offset+1]=(byte)(v>>8);buf[offset+2]=(byte)(v>>16);buf[offset+3]=(byte)(v>>24);}
        void U16(int offset,ushort v){buf[offset]=(byte)v;buf[offset+1]=(byte)(v>>8);}
        Ascii(0,"RIFF");U32(4,(uint)(total-8));Ascii(8,"WAVE");
        Ascii(12,"fmt ");U32(16,16);U16(20,1);U16(22,Channels);U32(24,SampleRate);U32(28,ByteRate);U16(32,BlockAlign);U16(34,BitsPerSample);
        Ascii(36,"data");U32(40,(uint)pcmData.Length);
        Array.Copy(pcmData,0,buf,CanonicalHeaderBytes,pcmData.Length);
        return buf;
    }

    // Streaming, bounds-checked validation. Reads only the bytes it needs (never the whole
    // file into memory beyond `data`, which the caller already holds/streams). Every arithmetic
    // step that could overflow is checked; on any structural problem this returns Fail with a
    // stable reason instead of throwing.
    public static VoiceWavValidation Validate(byte[] data)
    {
        if(data.Length==0)return VoiceWavValidation.Fail("empty file");
        if(data.Length<12)return VoiceWavValidation.Fail("missing RIFF identifier");
        if(data[0]!='R'||data[1]!='I'||data[2]!='F'||data[3]!='F')return VoiceWavValidation.Fail("missing RIFF identifier");
        if(data[8]!='W'||data[9]!='A'||data[10]!='V'||data[11]!='E')return VoiceWavValidation.Fail("missing WAVE identifier");
        if(data.Length>MaxTotalBytes)return VoiceWavValidation.Fail("file exceeds the maximum stored size");

        bool haveFmt=false,haveData=false;
        int fmtChannels=0,fmtBits=0;uint fmtRate=0,fmtByteRate=0;ushort fmtBlockAlign=0,fmtTag=0;
        long dataOffset=0,dataLen=0;
        long offset=12;
        while(offset+8<=data.Length){
            var id=System.Text.Encoding.ASCII.GetString(data,(int)offset,4);
            uint declaredSize=BitConverter.ToUInt32(data,(int)offset+4);
            long bodyStart=offset+8;
            long bodyEnd=bodyStart+declaredSize;
            if(declaredSize>int.MaxValue||bodyEnd<bodyStart||bodyEnd>data.Length)return VoiceWavValidation.Fail("truncated data chunk");
            if(id=="fmt "){
                if(haveFmt)return VoiceWavValidation.Fail("duplicate fmt chunk");
                if(haveData)return VoiceWavValidation.Fail("reordered chunks");
                if(declaredSize<16)return VoiceWavValidation.Fail("truncated data chunk");
                fmtTag=BitConverter.ToUInt16(data,(int)bodyStart);
                fmtChannels=BitConverter.ToUInt16(data,(int)bodyStart+2);
                fmtRate=BitConverter.ToUInt32(data,(int)bodyStart+4);
                fmtByteRate=BitConverter.ToUInt32(data,(int)bodyStart+8);
                fmtBlockAlign=BitConverter.ToUInt16(data,(int)bodyStart+12);
                fmtBits=BitConverter.ToUInt16(data,(int)bodyStart+14);
                haveFmt=true;
            }else if(id=="data"){
                if(haveData)return VoiceWavValidation.Fail("duplicate data chunk");
                if(!haveFmt)return VoiceWavValidation.Fail("reordered chunks");
                dataOffset=bodyStart;dataLen=declaredSize;haveData=true;
            }
            // Unknown/optional chunks are skipped via their checked size; RIFF padding (chunks
            // are word-aligned) is honored below.
            long advance=declaredSize+(declaredSize%2);
            long next=bodyStart+advance;
            if(next<bodyStart)return VoiceWavValidation.Fail("truncated data chunk");
            offset=next;
        }
        if(!haveFmt||!haveData)return VoiceWavValidation.Fail(!haveFmt?"missing fmt chunk":"missing data chunk");
        if(fmtTag!=1)return VoiceWavValidation.Fail("unsupported audio format");
        if(fmtChannels!=Channels||fmtRate!=SampleRate||fmtBits!=BitsPerSample)return VoiceWavValidation.Fail("unsupported sample rate");
        if(fmtBlockAlign!=BlockAlign||fmtByteRate!=ByteRate)return VoiceWavValidation.Fail("incorrect block alignment");
        if(dataLen%BlockAlign!=0)return VoiceWavValidation.Fail("data too short for a complete frame");
        if(dataLen==0)return VoiceWavValidation.Fail("data too short for a complete frame");
        if(dataLen>MaxDataBytes)return VoiceWavValidation.Fail("file exceeds the maximum stored size");
        // Trailing bytes after the last parsed chunk (beyond data+padding) are rejected: the
        // fixed contract format never carries extra chunks after `data`.
        long expectedEnd=dataOffset+dataLen+(dataLen%2);
        if(expectedEnd<data.Length)return VoiceWavValidation.Fail("trailing bytes after data chunk");

        long durationMs=dataLen*1000/ByteRate;
        var info=new VoiceWavInfo{SampleRate=(int)fmtRate,Channels=fmtChannels,BitsPerSample=fmtBits,DataBytes=dataLen,DataOffset=dataOffset,TotalBytes=data.Length,DurationMs=durationMs};
        return VoiceWavValidation.Ok(info);
    }
}

public sealed class VoiceSeekResult
{
    public required long EffectiveNs;
    public required long AlignedByte;
    public required string State; // "ready" or "completed"
}

// Implements contract.md's seven-step seek procedure exactly.
public static class VoiceSeek
{
    public static VoiceSeekResult Resolve(VoiceWavInfo info,long requestedMs)
    {
        // 1. Clamp the requested time to the validated duration.
        long clampedMs=Math.Clamp(requestedMs,0,info.DurationMs);
        // 2. Convert time using checked arithmetic.
        long requestedSamples=checked(clampedMs*VoiceWav.SampleRate/1000);
        // 3. Resolve the byte position within the validated WAV data range.
        long byteOffset=checked(requestedSamples*VoiceWav.BlockAlign);
        if(byteOffset>info.DataBytes)byteOffset=info.DataBytes;
        // 4. Align downward to a complete two-byte sample (already even by construction, but
        //    enforced defensively).
        byteOffset-=byteOffset%VoiceWav.BlockAlign;
        long alignedByte=checked(info.DataOffset+byteOffset);
        // 5/6. Invalidate old output and reset playback sequence/timestamp state: caller-side
        // (player) responsibility once given the resolved position below.
        long sampleOffset=byteOffset/VoiceWav.BlockAlign;
        long effectiveNs=checked(sampleOffset*62500);
        var state=byteOffset>=info.DataBytes?"completed":"ready";
        // 7. Report the effective aligned position to the UI.
        return new VoiceSeekResult{EffectiveNs=effectiveNs,AlignedByte=alignedByte,State=state};
    }
}
