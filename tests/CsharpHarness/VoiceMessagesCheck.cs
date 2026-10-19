using System.Security.Cryptography;
using System.Text.Json;
using LanMessenger;

// Voice Messages Windows Phase 2, WT01: runs the real W01 production classes
// (VoicePcmAssembler, VoiceWav, VoiceSeek, VoiceMarker) against the shared, frozen
// tests/voice_messages/vectors/manifest.json fixtures. This is the actual WT01 test (unlike
// the throwaway Python cross-check used earlier in the same session to sanity-check the
// design before a compiler was available) — invoked via `CsharpHarness --voice-check
// <manifestDir>` so it never needs a live PeerEngine/network setup, matching WT01's "no
// platform-local fixture copy" requirement by reading tests/voice_messages/vectors/ directly.
static class VoiceMessagesCheck
{
    public static int Run(string manifestDir)
    {
        var manifestPath=Path.Combine(manifestDir,"manifest.json");
        using var doc=JsonDocument.Parse(File.ReadAllBytes(manifestPath));
        int pass=0,fail=0,skip=0;
        var failures=new List<string>();
        void Record(bool ok,string id,string detail=""){if(ok)pass++;else{fail++;failures.Add($"{id}: {detail}");}}

        foreach(var v in doc.RootElement.GetProperty("vectors").EnumerateArray()){
            var id=v.GetProperty("id").GetString()!;
            var category=v.GetProperty("category").GetString()!;
            var exp=v.GetProperty("expectations");

            if(category=="wav-validation"){
                var data=FixtureBytes(manifestDir,v);
                if(data==null){skip++;continue;}
                var validation=VoiceWav.Validate(data);
                bool wantPass=exp.GetProperty("validation").GetString()=="pass";
                if(validation.Pass!=wantPass){Record(false,id,$"validation pass={validation.Pass} want={wantPass} (reason={validation.FailureReason})");continue;}
                if(validation.Pass){
                    var info=validation.Info!;var mism=new List<string>();
                    CheckInt(exp,"duration_ms",info.DurationMs,mism);CheckInt(exp,"sample_rate",info.SampleRate,mism);
                    CheckInt(exp,"channels",info.Channels,mism);CheckInt(exp,"bits_per_sample",info.BitsPerSample,mism);
                    CheckInt(exp,"data_bytes",info.DataBytes,mism);CheckInt(exp,"total_bytes",info.TotalBytes,mism);
                    Record(mism.Count==0,id,string.Join("; ",mism));
                }else Record(true,id);
            }else if(category=="pcm-frames"){
                var data=FixtureBytes(manifestDir,v);
                if(data==null){skip++;continue;}
                RunPcmVector(id,data,exp,Record);
            }else if(category=="seek"){
                var info=new VoiceWavInfo{SampleRate=VoiceWav.SampleRate,Channels=VoiceWav.Channels,BitsPerSample=VoiceWav.BitsPerSample,
                    DataBytes=exp.GetProperty("data_bytes").GetInt64(),DataOffset=exp.GetProperty("data_start").GetInt64(),
                    TotalBytes=0,DurationMs=exp.GetProperty("duration_ms").GetInt64()};
                var result=VoiceSeek.Resolve(info,exp.GetProperty("requested_ms").GetInt64());
                var mism=new List<string>();
                CheckLong(exp,"effective_ns",result.EffectiveNs,mism);CheckLong(exp,"aligned_byte",result.AlignedByte,mism);
                if(exp.GetProperty("state").GetString()!=result.State)mism.Add($"state got={result.State} want={exp.GetProperty("state").GetString()}");
                Record(mism.Count==0,id,string.Join("; ",mism));
            }else if(category=="marker"){
                var filename=exp.GetProperty("filename").GetString()!;
                var store=exp.TryGetProperty("store",out var s)?s.GetString()!:"Normal";
                var attachmentId=exp.TryGetProperty("attachment_message_id",out var aid)&&aid.ValueKind==JsonValueKind.String?aid.GetString():null;
                var parses=VoiceMarker.TryParse(filename,out var extracted);
                var classification=VoiceMarker.Classify(filename,store,attachmentId??"");
                var mism=new List<string>();
                if(exp.TryGetProperty("marker_parses",out var mp)&&mp.GetBoolean()!=parses)mism.Add($"marker_parses got={parses} want={mp.GetBoolean()}");
                if(exp.TryGetProperty("extracted_message_id",out var eid)&&eid.ValueKind==JsonValueKind.String&&extracted!=eid.GetString())mism.Add($"extracted_message_id got={extracted} want={eid.GetString()}");
                var wantClass=exp.GetProperty("classification").GetString()switch{"candidate"=>VoiceMarkerClassification.Candidate,_=>VoiceMarkerClassification.OrdinaryAttachment};
                if(classification!=wantClass)mism.Add($"classification got={classification} want={exp.GetProperty("classification").GetString()}");
                Record(mism.Count==0,id,string.Join("; ",mism));
            }else skip++;
        }

        Console.WriteLine($"VOICECHECK\tPASS={pass}\tFAIL={fail}\tSKIP={skip}");
        foreach(var f in failures)Console.WriteLine("VOICECHECK-FAIL\t"+f);
        return fail==0?0:1;
    }

    static void CheckInt(JsonElement exp,string key,long got,List<string> mism){if(exp.TryGetProperty(key,out var e)&&e.GetInt64()!=got)mism.Add($"{key} got={got} want={e.GetInt64()}");}
    static void CheckLong(JsonElement exp,string key,long got,List<string> mism){if(exp.TryGetProperty(key,out var e)&&e.GetInt64()!=got)mism.Add($"{key} got={got} want={e.GetInt64()}");}

    static byte[]? FixtureBytes(string manifestDir,JsonElement vector)
    {
        if(!vector.TryGetProperty("source",out var source)||source.ValueKind==JsonValueKind.Null)return null;
        if(source.TryGetProperty("recipe",out _))return null; // recipe-only (e.g. the 9.6 MB max-boundary case) — not generated here.
        var path=Path.Combine(manifestDir,source.GetProperty("path").GetString()!);
        var offset=source.GetProperty("offset").GetInt64();var length=source.GetProperty("length").GetInt64();
        using var f=File.OpenRead(path);f.Position=offset;var buf=new byte[length];f.ReadExactly(buf);
        if(source.TryGetProperty("sha256",out var wantHash)){
            var actual=Convert.ToHexString(SHA256.HashData(buf)).ToLowerInvariant();
            if(actual!=wantHash.GetString())throw new IOException($"sha256 mismatch reading fixture bytes at {path}[{offset}:{offset+length}]");
        }
        return buf;
    }

    static void RunPcmVector(string id,byte[] data,JsonElement exp,Action<bool,string,string> record)
    {
        var asm=new VoicePcmAssembler();
        var frames=new List<VoicePcmFrame>();
        var events=new List<string>();
        var wantEvents=exp.TryGetProperty("events",out var we)?we.EnumerateArray().Select(x=>x.GetString()!).ToArray():Array.Empty<string>();
        try{
            if(id=="pcm-overflow"){
                frames.AddRange(asm.Push(data));
                if(asm.TerminallyFailed)events.Add("DeviceFailure");
            }else if(wantEvents.Contains("Discontinuity")){
                int half=data.Length/2;
                frames.AddRange(asm.Push(data[..half]));
                events.Add(asm.Discontinuity().ToString());
                frames.AddRange(asm.Push(data[half..]));
                var (final,evt)=asm.End();if(final!=null)frames.Add(final);events.Add(evt.ToString());
            }else if(wantEvents.Contains("Restart")){
                int half=data.Length/2;
                frames.AddRange(asm.Push(data[..half]));
                var (final1,evt1)=asm.End();if(final1!=null)frames.Add(final1);events.Add(evt1.ToString());
                events.Add(asm.Restart().ToString());
                frames.AddRange(asm.Push(data[half..]));
                var (final2,evt2)=asm.End();if(final2!=null)frames.Add(final2);events.Add(evt2.ToString());
            }else if(exp.TryGetProperty("callback_plan",out var plan)){
                int pos=0;
                foreach(var cb in plan.EnumerateArray()){int n=cb.GetProperty("deliver_bytes").GetInt32();frames.AddRange(asm.Push(data[pos..(pos+n)]));pos+=n;}
                if(wantEvents.Length>0){var (final,evt)=asm.End();if(final!=null)frames.Add(final);events.Add(evt.ToString());}
            }else{
                frames.AddRange(asm.Push(data));
                if(wantEvents.Length>0){var (final,evt)=asm.End();if(final!=null)frames.Add(final);events.Add(evt.ToString());}
            }
        }catch(InvalidOperationException e){record(false,id,$"unexpected exception: {e.Message}");return;}

        var mism=new List<string>();
        if(exp.TryGetProperty("frame_count",out var fc)&&fc.GetInt32()!=frames.Count)mism.Add($"frame_count got={frames.Count} want={fc.GetInt32()}");
        var totalBytes=frames.Sum(f=>f.Payload.Length);
        if(exp.TryGetProperty("total_bytes",out var tb)&&tb.GetInt64()!=totalBytes)mism.Add($"total_bytes got={totalBytes} want={tb.GetInt64()}");
        if(exp.TryGetProperty("terminal_failure",out var tf)&&tf.GetBoolean()!=asm.TerminallyFailed)mism.Add($"terminal_failure got={asm.TerminallyFailed} want={tf.GetBoolean()}");
        if(wantEvents.Length>0&&!wantEvents.SequenceEqual(events))mism.Add($"events got=[{string.Join(",",events)}] want=[{string.Join(",",wantEvents)}]");
        if(frames.Count>0){
            if(exp.TryGetProperty("first_sequence",out var fs)&&fs.GetInt32()!=frames[0].Sequence)mism.Add($"first_sequence got={frames[0].Sequence} want={fs.GetInt32()}");
            if(exp.TryGetProperty("last_sequence",out var ls)&&ls.GetInt32()!=frames[^1].Sequence)mism.Add($"last_sequence got={frames[^1].Sequence} want={ls.GetInt32()}");
            if(exp.TryGetProperty("first_timestamp_ns",out var ft)&&ft.GetInt64()!=frames[0].TimestampNs)mism.Add($"first_timestamp_ns got={frames[0].TimestampNs} want={ft.GetInt64()}");
            if(exp.TryGetProperty("last_timestamp_ns",out var lt)&&lt.GetInt64()!=frames[^1].TimestampNs)mism.Add($"last_timestamp_ns got={frames[^1].TimestampNs} want={lt.GetInt64()}");
        }
        record(mism.Count==0,id,string.Join("; ",mism));
    }
}
