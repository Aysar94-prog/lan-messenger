using SIPSorcery.Net;
using SIPSorcery.Media;
using SIPSorceryMedia.Abstractions;

namespace LanMessenger;

// Real call media: SIPSorcery's RTCPeerConnection for SDP/ICE/DTLS-SRTP/RTP, this project's own
// winmm capture/playback (CallAudioIo.cs) for the actual audio device I/O. No STUN/TURN server is
// configured (matches the plan's "no external STUN/TURN, LAN host candidates only" rule).
//
// Codec: G722 (16 kHz), not Opus. SIPSorcery's bundled AudioEncoder does not include an Opus
// implementation (confirmed by reflecting the actual package rather than assuming); offering the
// codecs it does bundle (PCMU/PCMA/G722/G729/L16) still interoperates with Android's libwebrtc side
// because stock WebRTC's default audio SDP offer includes G722/PCMU/PCMA as fallback options
// alongside Opus, not Opus alone -- the two sides should converge on G722 (clearly better than
// 8kHz PCMU/PCMA) through ordinary SDP codec negotiation. This is the single biggest open risk
// flagged for real-device verification; see the voice-calls plan's 2026-10-02 addendum.
public sealed class WebRtcCallMedia : ICallMedia
{
    public event Action<string, string, int>? OnLocalIceCandidate;
    public event Action? OnMediaReady;
    public event Action? OnMediaFailed;

    readonly RTCPeerConnection pc;
    readonly AudioEncoder encoder = new(true, false);
    readonly List<AudioFormat> formats;
    AudioFormat negotiated;
    readonly CallAudioCapture capture = new();
    readonly CallAudioPlayback playback = new();
    MediaStreamTrack? track;
    volatile bool muted;
    volatile bool mediaReadyFired;
    volatile bool disposed;
    readonly object diagnosticLock = new();
    readonly System.Diagnostics.Stopwatch captureClock = System.Diagnostics.Stopwatch.StartNew();
    long diagnosticFrames, diagnosticSamples, diagnosticSquareSum, diagnosticClipped;
    long diagnosticLastTicks, diagnosticIntervalCount;
    double diagnosticIntervalSumMs, diagnosticIntervalSquareSumMs;
    double diagnosticIntervalMinMs = double.MaxValue, diagnosticIntervalMaxMs;
    int diagnosticPeak;
    double captureGain = 2.25;

    public WebRtcCallMedia()
    {
        // Prefer G722 (16 kHz) first; the rest are listed so negotiation still succeeds against a
        // peer that for some reason doesn't offer G722.
        var all = encoder.SupportedFormats;
        formats = all.Where(f => f.FormatName == "G722").Concat(all.Where(f => f.FormatName != "G722")).ToList();
        negotiated = formats[0];

        pc = new RTCPeerConnection(null);
        pc.onicecandidate += c => { if (c != null) OnLocalIceCandidate?.Invoke(c.candidate, c.sdpMid, c.sdpMLineIndex); };
        pc.OnAudioFormatsNegotiated += negotiatedFormats => { if (negotiatedFormats.Count > 0) negotiated = negotiatedFormats[0]; };
        pc.OnRtpPacketReceived += (ep, mediaType, rtp) =>
        {
            if (mediaType != SDPMediaTypesEnum.audio || disposed) return;
            try
            {
                var pcm = encoder.DecodeAudio(rtp.Payload, negotiated);
                playback.Enqueue(ToBytes(pcm));
            }
            catch { }
        };
        pc.onconnectionstatechange += state =>
        {
            if (state == RTCPeerConnectionState.connected)
            {
                if (!mediaReadyFired) { mediaReadyFired = true; OnMediaReady?.Invoke(); }
            }
            else if (state is RTCPeerConnectionState.failed or RTCPeerConnectionState.closed)
            {
                OnMediaFailed?.Invoke();
            }
        };

        capture.FrameReady += pcm =>
        {
            if (disposed || muted) return;
            try
            {
                TrackCapture(pcm);
                var samples = ToSamples(pcm);
                ApplyCaptureAgc(samples);
                var encoded = encoder.EncodeAudio(samples, negotiated);
                pc.SendAudio(RtpDurationFor(negotiated.FormatName, samples.Length), encoded);
            }
            catch { }
        };
        capture.DeviceFailed += () => OnMediaFailed?.Invoke();
        playback.DeviceFailed += () => OnMediaFailed?.Invoke();

        track = new MediaStreamTrack(formats, MediaStreamStatusEnum.SendRecv);
        pc.addTrack(track);

        try { capture.Start(); } catch { OnMediaFailed?.Invoke(); }
        try { playback.Start(); } catch { OnMediaFailed?.Invoke(); }
    }

    public async Task<string> CreateOfferAsync()
    {
        var offer = pc.createOffer(null);
        await pc.setLocalDescription(offer);
        return offer.sdp;
    }

    public async Task<string> CreateAnswerAsync(string remoteOfferSdp)
    {
        var setResult = pc.setRemoteDescription(new RTCSessionDescriptionInit { sdp = remoteOfferSdp, type = RTCSdpType.offer });
        if (setResult != SetDescriptionResultEnum.OK) throw new IOException("Could not accept the incoming call's media description (" + setResult + ")");
        var answer = pc.createAnswer(null);
        await pc.setLocalDescription(answer);
        return answer.sdp;
    }

    public Task SetRemoteAnswerAsync(string remoteAnswerSdp)
    {
        var result = pc.setRemoteDescription(new RTCSessionDescriptionInit { sdp = remoteAnswerSdp, type = RTCSdpType.answer });
        if (result != SetDescriptionResultEnum.OK) throw new IOException("Could not apply the call's answer (" + result + ")");
        return Task.CompletedTask;
    }

    public void AddRemoteIceCandidate(string candidate, string? sdpMid, int sdpMLineIndex)
    {
        if (string.IsNullOrEmpty(candidate)) return;
        try { pc.addIceCandidate(new RTCIceCandidateInit { candidate = candidate, sdpMid = sdpMid ?? "", sdpMLineIndex = (ushort)sdpMLineIndex }); } catch { }
    }

    public bool IsMuted => muted;
    public void SetMuted(bool value) => muted = value;

    public CallStats GetStats() => new() { Available = false }; // real-metric mapping deferred — see plan addendum

    // G722 is sampled internally at 16 kHz but RFC 3551 fixes its RTP timestamp clock at 8 kHz.
    // A 20 ms capture frame therefore contains 320 PCM samples but advances the RTP timestamp by
    // only 160 units. Sending 320 made Android schedule every packet 40 ms apart even though a new
    // packet arrived every 20 ms, producing regular gaps and the reported choppy Windows mic.
    internal static uint RtpDurationFor(string formatName, int pcmSampleCount)
    {
        if (pcmSampleCount <= 0) return 0;
        return (uint)(string.Equals(formatName, "G722", StringComparison.OrdinalIgnoreCase)
            ? pcmSampleCount / 2
            : pcmSampleCount);
    }

    // The legacy winmm capture path supplies raw PCM and has no WebRTC AGC. Update gain only on
    // frames with meaningful speech energy so silence does not drive it to maximum and amplify the
    // room noise. Gain rises gradually (no pumping) and falls quickly when the speaker gets louder.
    // A hard ceiling plus signed-16-bit saturation prevents overflow distortion.
    internal static double NextCaptureGain(double current, double rms)
    {
        if (rms < 100) return current;
        double desired = Math.Clamp(5000.0 / rms, 1.0, 12.0);
        double blend = desired < current ? 0.35 : 0.08;
        return current + (desired - current) * blend;
    }

    internal static void ApplyGain(short[] samples, double gain)
    {
        for (int i = 0; i < samples.Length; i++)
        {
            int amplified = (int)Math.Round(samples[i] * gain);
            samples[i] = (short)Math.Clamp(amplified, short.MinValue, short.MaxValue);
        }
    }

    void ApplyCaptureAgc(short[] samples)
    {
        if (samples.Length == 0) return;
        double squareSum = 0;
        for (int i = 0; i < samples.Length; i++) squareSum += (double)samples[i] * samples[i];
        double rms = Math.Sqrt(squareSum / samples.Length);
        captureGain = NextCaptureGain(captureGain, rms);
        ApplyGain(samples, captureGain);
    }

    void TrackCapture(byte[] pcm)
    {
        long now = captureClock.ElapsedTicks;
        lock (diagnosticLock)
        {
            if (diagnosticLastTicks != 0)
            {
                double interval = (now - diagnosticLastTicks) * 1000.0 / System.Diagnostics.Stopwatch.Frequency;
                diagnosticIntervalCount++;
                diagnosticIntervalSumMs += interval;
                diagnosticIntervalSquareSumMs += interval * interval;
                diagnosticIntervalMinMs = Math.Min(diagnosticIntervalMinMs, interval);
                diagnosticIntervalMaxMs = Math.Max(diagnosticIntervalMaxMs, interval);
            }
            diagnosticLastTicks = now;
            diagnosticFrames++;
            for (int i = 0; i + 1 < pcm.Length; i += 2)
            {
                int sample = (short)(pcm[i] | pcm[i + 1] << 8);
                int magnitude = Math.Abs(sample == short.MinValue ? short.MaxValue : sample);
                diagnosticPeak = Math.Max(diagnosticPeak, magnitude);
                diagnosticSquareSum += (long)sample * sample;
                diagnosticSamples++;
                if (magnitude >= 32760) diagnosticClipped++;
            }
        }
    }

    void WriteDiagnostics()
    {
        string? path = Environment.GetEnvironmentVariable("LANMESSENGER_AUDIO_DIAGNOSTICS");
        if (string.IsNullOrWhiteSpace(path)) return;
        lock (diagnosticLock)
        {
            double mean = diagnosticIntervalCount == 0 ? 0 : diagnosticIntervalSumMs / diagnosticIntervalCount;
            double variance = diagnosticIntervalCount == 0 ? 0
                : Math.Max(0, diagnosticIntervalSquareSumMs / diagnosticIntervalCount - mean * mean);
            double rms = diagnosticSamples == 0 ? 0 : Math.Sqrt((double)diagnosticSquareSum / diagnosticSamples);
            string line = string.Create(System.Globalization.CultureInfo.InvariantCulture,
                $"{DateTime.UtcNow:O}\tframes={diagnosticFrames}\tsamples={diagnosticSamples}" +
                $"\tintervalMeanMs={mean:F3}\tintervalStdMs={Math.Sqrt(variance):F3}" +
                $"\tintervalMinMs={(diagnosticIntervalCount == 0 ? 0 : diagnosticIntervalMinMs):F3}" +
                $"\tintervalMaxMs={diagnosticIntervalMaxMs:F3}\trms={rms:F1}" +
                $"\tpeak={diagnosticPeak}\tclipped={diagnosticClipped}" +
                $"\tfinalGain={captureGain:F3}{Environment.NewLine}");
            try { File.AppendAllText(path, line); } catch { }
        }
    }

    static short[] ToSamples(byte[] pcm)
    {
        var samples = new short[pcm.Length / 2];
        Buffer.BlockCopy(pcm, 0, samples, 0, samples.Length * 2);
        return samples;
    }
    static byte[] ToBytes(short[] samples)
    {
        var bytes = new byte[samples.Length * 2];
        Buffer.BlockCopy(samples, 0, bytes, 0, bytes.Length);
        return bytes;
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        try { capture.Dispose(); } catch { }
        try { playback.Dispose(); } catch { }
        try { pc.close(); } catch { }
        try { pc.Dispose(); } catch { }
        try { encoder.Dispose(); } catch { }
        WriteDiagnostics();
    }

    public sealed class Factory : ICallMedia.IFactory
    {
        public ICallMedia Create() => new WebRtcCallMedia();
    }
}
