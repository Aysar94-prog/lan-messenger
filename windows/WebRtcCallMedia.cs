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
                var samples = ToSamples(pcm);
                var encoded = encoder.EncodeAudio(samples, negotiated);
                pc.SendAudio((uint)samples.Length, encoded);
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
    }

    public sealed class Factory : ICallMedia.IFactory
    {
        public ICallMedia Create() => new WebRtcCallMedia();
    }
}
