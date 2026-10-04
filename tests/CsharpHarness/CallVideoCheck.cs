namespace LanMessenger;

// Windows-side behavioural checks for the A02b video layer. These mirror the Android checks in
// tests/video-contract one-for-one -- same scenarios, same expected outcomes -- so that the two
// implementations are proven to make the same DECISIONS, not just to parse the same bytes.
//
// Everything here runs with fake eligibility and FakeCallVideoMedia. No camera, no microphone, no
// network and no window is involved, so these results say nothing about whether a real call works on
// real hardware. They say the managed layer's rules hold, which is what can actually be established
// without devices.
static class CallVideoCheck
{
    static readonly string Call = "00000000-0000-0000-0000-000000000001";
    static readonly string Other = "00000000-0000-0000-0000-000000000002";
    static readonly string Low = "00000000-0000-0000-0000-000000000003";
    static readonly string High = "00000000-0000-0000-0000-000000000004";

    static int passed, failed;
    static string group = "";

    static void Section(string name) { group = name; }
    static void Check(bool ok, string message)
    {
        if (ok) passed++;
        else { failed++; Console.Error.WriteLine($"FAIL [{group}] {message}"); }
    }
    static bool Capture(CallVideoConsent c) => c.CanCapture(Call, true, true, true);

    // A wire that records what the coordinator sent instead of putting it on a socket.
    sealed class RecordingWire
    {
        public readonly List<CallProtocol.Frame> Sent = new();
        public int Sequence = 100;
        public void Send(CallProtocol.Frame frame)
        {
            frame.Seq = Sequence++;
            // Every frame the coordinator produces must satisfy the same validator the peer applies.
            if (!CallVideoProtocol.Valid(frame))
                throw new IOException("coordinator produced an invalid v2 frame: " + frame.T);
            Sent.Add(frame);
        }
        public CallProtocol.Frame? Last(string type)
        {
            for (int i = Sent.Count - 1; i >= 0; i--) if (Sent[i].T == type) return Sent[i];
            return null;
        }
        public int Count(string type) => Sent.Count(f => f.T == type);
    }

    public static int Run()
    {
        OutboundFrameChecks();
        CapabilityHonestyChecks();
        ConsentChecks();
        AdmissionChecks();
        CapabilitiesChecks();
        PlacementChecks();
        DiagnosticsChecks();
        ResourceChecks();
        CameraPermissionChecks();
        CoordinatorUpgradeChecks();
        CoordinatorInitialVideoChecks();
        MediaSeamChecks();

        Console.WriteLine($"Windows call-video checks: {passed} passed, {failed} failed");
        return failed == 0 ? 0 : 1;
    }

    // Every frame the controller can put on a v2 call, taken through the real serializer and the real
    // parser, and required to come back out valid. This is the check that keeps the Windows wire
    // honest: SendFrameTo refuses to send anything CallVideoProtocol would reject, so a builder that
    // produces a frame the validator rejects would turn a working call into an IOException at run time
    // -- on a physical device, with no automated test able to see it.
    static void OutboundFrameChecks()
    {
        Section("outbound-frames");
        var sdp = FakeCallVideoMedia.VideoSdp(2, "actpass", "o");
        var audioSdp = FakeCallVideoMedia.AudioSdp(1, "actpass", "sendrecv");
        var cases = new (string Label, CallProtocol.Frame Frame)[]
        {
            ("VIDEO_INVITE/video", CallSignaling.VideoInvite(Call, 1, Call, Other, "video")),
            ("VIDEO_INVITE/audio", CallSignaling.VideoInvite(Call, 2, Call, Other, "audio")),
            ("VIDEO_ACCEPT/video", CallSignaling.VideoAccept(Call, 3, "video")),
            ("VIDEO_ACCEPT/audio", CallSignaling.VideoAccept(Call, 4, "audio")),
            ("VIDEO_REQUEST", CallSignaling.VideoRequest(Call, 5, 0, Other)),
            ("VIDEO_ACCEPT(upgrade)", CallSignaling.VideoAcceptUpgrade(Call, 6, 0, Other)),
            ("VIDEO_DECLINE", CallSignaling.VideoDecline(Call, 7, 0, Other)),
            ("VIDEO_STATE on", CallSignaling.VideoState(Call, 8, 2, Other, true, 7)),
            ("VIDEO_STATE off", CallSignaling.VideoState(Call, 9, 2, Other, false, 8)),
            ("REMOTE_SPEAKER on", CallSignaling.RemoteSpeaker(Call, 10, true)),
            ("REMOTE_CAMERA on", CallSignaling.RemoteCamera(Call, 11, 2, Other, true, "front")),
            ("OFFER/video", CallSignaling.MediaOffer(Call, 12, 2, "video", Other, sdp)),
            ("ANSWER/video", CallSignaling.MediaAnswer(Call, 13, 2, "video", Other, sdp)),
            ("ICE/video", CallSignaling.MediaIce(Call, 14, 2, "video", Other,
                "candidate:1 1 udp 2130706431 192.168.1.5 54321 typ host", "video", 0)),
            ("MEDIA_READY/video", CallSignaling.MediaReadyFor(Call, 15, 2, "video", Other)),
            ("ERROR/failed", CallSignaling.MediaError(Call, 16, 2, "video", Other, "failed")),
        };
        foreach (var (label, frame) in cases)
        {
            frame.V = 2; // SendFrameTo stamps this before validating, so the check must too.
            Check(CallVideoProtocol.Valid(frame), $"{label} is valid as built");
            var back = CallSignaling.Parse(CallSignaling.Serialize(frame));
            Check(back != null, $"{label} survives the wire");
            Check(back != null && CallVideoProtocol.Valid(back), $"{label} is valid after a round trip");
            Check(back != null && back.T == frame.T && back.Gen == frame.Gen && back.Seq == frame.Seq,
                $"{label} keeps its type, generation and sequence");
        }

        // The AUDIO path on a v2 call uses the ordinary v1 builders, and SendFrameTo adds exactly two
        // things to them: generation 1 and an explicit audio tag. That is not a Windows choice -- the
        // Android validator requires both -- and an audio body must NOT carry a "request" key, so an
        // audio OFFER that grew one would be rejected by the peer and silently break every v2 call.
        var audioFrames = new (string Label, CallProtocol.Frame Frame)[]
        {
            ("audio OFFER", CallSignaling.Offer(Call, 20, 1, audioSdp)),
            ("audio ANSWER", CallSignaling.Answer(Call, 21, 1, audioSdp)),
            ("audio ICE", CallSignaling.Ice(Call, 22, 1, "candidate:1 1 udp 2130706431 192.168.1.5 54321 typ host", "audio", 0)),
            ("audio MEDIA_READY", CallSignaling.MediaReady(Call, 23, 1)),
            ("audio ERROR", CallSignaling.MediaError(Call, 24, 1, "audio", null!, "failed")),
        };
        foreach (var (label, frame) in audioFrames)
        {
            frame.V = 2;
            frame.B ??= new Dictionary<string, object>();       // what SendFrameTo does
            frame.Gen = 1;                                      // what SendFrameTo does
            frame.B["media"] = "audio";                         // what SendFrameTo does
            Check(CallVideoProtocol.Valid(frame), $"{label} is valid once stamped for audio");
            var back = CallSignaling.Parse(CallSignaling.Serialize(frame));
            Check(back != null && CallVideoProtocol.Valid(back), $"{label} survives the wire");
            Check(back != null && back.B != null && !back.B.ContainsKey("request"),
                $"{label} carries no video request id");
        }
        var errorFrame = CallSignaling.MediaError(Call, 25, 2, "video", Other, "failed");
        errorFrame.V = 2;
        Check(CallVideoProtocol.Valid(errorFrame), "a video ERROR is valid");

        // The archived 2.2.42 peer must keep seeing exactly what it saw before any of this existed.
        // A v1 INVITE is caller/callee at generation 0 and nothing else -- no media key, because a v1
        // parser would reject the frame outright rather than ignore the extra field.
        var v1 = CallSignaling.Invite(Call, 1, Call, Other);
        Check(v1.V == 1, "a plain INVITE is still protocol version 1");
        Check(v1.Gen == 0, "a plain INVITE carries generation 0");
        Check(v1.B != null && v1.B.Count == 2 && v1.B.ContainsKey("caller") && v1.B.ContainsKey("callee"),
            "a plain INVITE body is exactly caller and callee");
        var v1Back = CallSignaling.Parse(CallSignaling.Serialize(v1));
        Check(v1Back != null && v1Back.V == 1, "a plain INVITE survives the wire as version 1");
        Check(v1Back != null && v1Back.B != null && v1Back.B.Count == 2,
            "a plain INVITE body reaches the wire unchanged");

        // And the same call as v2: identical except for the two additions, so the only thing a peer
        // has to understand to talk to this build is the version number and one extra key.
        var v2 = CallSignaling.Invite(Call, 1, Call, Other);
        v2.B!["media"] = "video"; v2.V = 2;
        var v2Back = CallSignaling.Parse(CallSignaling.Serialize(v2));
        Check(v2Back != null && CallVideoProtocol.Valid(v2Back), "a v2 INVITE with media is valid");

        // A callee may only claim video in its ACCEPT if video was actually invited, so the audio
        // variant must be the one that survives when nothing was offered.
        var acceptAudio = CallSignaling.Accept(Call, 2);
        acceptAudio.B ??= new Dictionary<string, object>();       // what SendFrameTo does
        acceptAudio.B["media"] = "audio"; acceptAudio.V = 2;
        var acceptAudioBack = CallSignaling.Parse(CallSignaling.Serialize(acceptAudio));
        Check(acceptAudioBack != null && CallVideoProtocol.Valid(acceptAudioBack),
            "a v2 ACCEPT answering with audio is valid");
    }

    // A build must never advertise a capability it cannot deliver.
    //
    // This exists because of a real defect, not a hypothetical one: CallVideoSupport and
    // CallController.VideoEnabled both used to default to true, and the app shell then read the
    // engine's default and made the engine read the result back -- a self-referential pin to true
    // with no path to false. With no production video adapter installed, Windows would have answered
    // CALLCAPS with VP8, invited peers into media=video, and then been unable to send a picture, which
    // is a worse outcome for the caller than never having claimed video. The fix is structural (the
    // getter requires a backend), and these checks exist to stop it regressing back into a default.
    static void CapabilityHonestyChecks()
    {
        Section("capability-honesty");

        // The wire level: a build without video writes no CALLCAPS reply at all, which is how a peer
        // learns to offer voice instead. Silence is the honest answer; a reply claiming VP8 is not.
        Check(CallCapabilities.ResponseFor(false, legacy: false) == null,
            "a build without video advertises no call capability");
        Check(CallCapabilities.ResponseFor(false, legacy: true) == null,
            "a simulated legacy build advertises no call capability either");
        var claimed = CallCapabilities.ResponseFor(true, legacy: false);
        Check(claimed != null && claimed.Contains("VP8"),
            "a build that does have video claims VP8");

        var root = Path.Combine(Path.GetTempPath(), "call-video-honesty-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var engine = new PeerEngine(root, "VideoHonesty", new TestProtector(root));
            var controller = new CallController(engine, new CallSettings(root));

            // The default must be "no video", and must stay that way through assignment, because
            // nothing has installed a backend yet.
            Check(!controller.VideoEnabled, "a controller with no media backend reports no video");
            controller.VideoEnabled = true;
            Check(!controller.VideoEnabled,
                "setting the flag without a media backend still reports no video");

            // The engine default matters on its own for any caller that never wires the shell up.
            Check(!new PeerEngine(root, "VideoHonesty2", new TestProtector(root)).CallVideoSupport(),
                "the engine does not claim call video by default");

            // A backend alone is not enough either -- the flag has to be set as well, so installing an
            // adapter in one place cannot silently start answering probes.
            controller.VideoMediaFactory = () => new FakeCallVideoMedia();
            Check(controller.VideoEnabled,
                "video is claimed once both a media backend and the flag are present");

            // And the app shell's wiring, which reads the controller so there is one switch: with the
            // factory removed again the answer must go back to false rather than stick.
            engine.CallVideoSupport = () => controller.VideoEnabled;
            Check(engine.CallVideoSupport(), "the engine and controller agree when video is available");
            controller.VideoMediaFactory = null;
            Check(!engine.CallVideoSupport(),
                "removing the backend withdraws the claim from the engine too");

            // A v2 INVITE must be refused while video is unavailable, rather than accepted into a call
            // that can never carry a picture. The transport is closed with no reply, which is the same
            // silence an unsupported peer gets.
            Check(!controller.VideoEnabled, "video is unavailable again after teardown");
        }
        finally
        {
            try { Directory.Delete(root, recursive: true); } catch { }
        }
    }

    static void ConsentChecks()
    {
        Section("consent");
        // An incoming invitation is the peer's consent, never local capture authorization.
        var receiveOnly = new CallVideoConsent(Call, true, false, true);
        Check(receiveOnly.AcceptInitialReceiveOnly(Other) == CallVideoConsent.Result.Ignored,
            "receive-only bound to call");
        Check(receiveOnly.AcceptInitialReceiveOnly(Call) == CallVideoConsent.Result.Ready,
            "trusted receive-only answer");
        receiveOnly.SetConnected(Call, true);
        Check(receiveOnly.AuthorizeGeneration(Call, Call, 2), "receive-only permits video negotiation");
        Check(receiveOnly.MarkMediaReady(Call, 2), "receive-only ready");
        Check(!Capture(receiveOnly), "receive-only never authorizes camera implicitly");
        Check(receiveOnly.CameraOn(Call, false) == CallVideoConsent.Result.Denied, "locked new camera denied");
        Check(!Capture(receiveOnly), "denied camera remains off");
        Check(receiveOnly.CameraOn(Call, true) == CallVideoConsent.Result.Ready, "later explicit eligible camera action");
        Check(Capture(receiveOnly), "explicit camera consent works after receive-only answer");
        Check(receiveOnly.AcceptInitialReceiveOnly(Call) == CallVideoConsent.Result.Ignored, "duplicate receive-only ignored");

        var incoming = new CallVideoConsent(Call, true, false, true);
        Check(!Capture(incoming), "invitation does not capture");
        Check(incoming.AcceptInitial(Other, true, true) == CallVideoConsent.Result.Ignored, "foreign call action");
        Check(incoming.AcceptInitial(Call, true, false) == CallVideoConsent.Result.Denied, "denied permission retains choice");
        Check(incoming.AcceptInitial(Call, false, false) == CallVideoConsent.Result.Voice, "answer with voice needs no camera");
        Check(incoming.AcceptInitial(Call, true, true) == CallVideoConsent.Result.Ignored, "repeated initial accept");
        incoming.SetConnected(Call, true);
        Check(!Capture(incoming) && incoming.CurrentPhase == CallVideoConsent.Phase.Voice,
            "voice answer stays camera-free");

        var caller = new CallVideoConsent(Call, true, true, true);
        Check(!Capture(caller), "outgoing video invitation alone cannot capture");
        Check(caller.PeerAnswered(Call, false) == CallVideoConsent.Result.Voice, "callee voice selection clears video");
        caller.SetConnected(Call, true);
        Check(!Capture(caller), "audio connected is not video consent");
        Check(caller.RequestUpgrade(Call, High, false) == CallVideoConsent.Result.Denied, "revoked permission before request");
        Check(caller.RequestUpgrade(Call, High, true) == CallVideoConsent.Result.Ready, "explicit local upgrade");
        Check(caller.RequestUpgrade(Call, High, true) == CallVideoConsent.Result.Busy, "repeated tap serialized");
        Check(!caller.AuthorizeGeneration(Call, High, 2), "peer consent required");
        Check(caller.PeerAccepted(Call, Low) == CallVideoConsent.Result.Ignored, "stale request accept");
        Check(caller.PeerAccepted(Call, High) == CallVideoConsent.Result.Ready, "matching peer accept");
        Check(caller.AuthorizeGeneration(Call, High, 2), "generation authorized once both consent");
        Check(!caller.AuthorizeGeneration(Call, High, 2), "generation cannot be reused");
        Check(!caller.AuthorizeGeneration(Call, High, 3), "generation must advance");
        Check(caller.MarkMediaReady(Call, 2), "media ready for the active generation");
        Check(!caller.MarkMediaReady(Call, 3), "media ready bound to the active generation");

        // Simultaneous requests: the lower UUID wins, deterministically and independently of timing.
        // This is an established VOICE call between two capable peers -- invitedVideo is false, so
        // the request id starts empty. Giving it an initial video answer here would make the call id
        // itself the outstanding request, and the comparison below would then be against the call id
        // rather than between the two competing requests.
        var lowWins = new CallVideoConsent(Call, true, false, false);
        lowWins.SetConnected(Call, true);
        Check(lowWins.ReceiveRequest(Call, High) == CallVideoConsent.Result.Prompt, "first peer request prompts");
        Check(lowWins.ReceiveRequest(Call, High) == CallVideoConsent.Result.Ignored, "repeated request ignored");
        Check(lowWins.RequestId == High, "the outstanding request is the one received");
        Check(lowWins.ReceiveRequest(Call, Low) == CallVideoConsent.Result.Prompt, "the lower UUID takes the collision");
        Check(lowWins.RequestId == Low, "the winning lower request replaces the higher one");
        Check(lowWins.ReceiveRequest(Call, High) == CallVideoConsent.Result.Ignored,
            "a retired request id is refused, so a stale accept cannot revive it");
        Check(!Capture(lowWins), "a prompt is not an acceptance");
        Check(lowWins.AcceptUpgrade(Call, Low, true) == CallVideoConsent.Result.Ready, "local acceptance");
        Check(lowWins.AcceptUpgrade(Call, Low, true) == CallVideoConsent.Result.Ignored, "acceptance is once-only");

        // A local action cannot silently accept a peer's winning UUID.
        var localLoses = new CallVideoConsent(Call, true, false, false);
        localLoses.SetConnected(Call, true);
        Check(localLoses.RequestUpgrade(Call, High, true) == CallVideoConsent.Result.Ready, "local upgrade request");
        localLoses.PeerAccepted(Call, High);
        Check(localLoses.ReceiveRequest(Call, Low) == CallVideoConsent.Result.Prompt, "lower peer request takes over");
        Check(localLoses.RequestId == Low, "the winning request replaces the local one");
        Check(localLoses.RequestId != High, "local upgrade torn down by collision");
        Check(localLoses.AcceptUpgrade(Call, High, true) == CallVideoConsent.Result.Ignored, "losing id cannot be accepted");

        var retired = new CallVideoConsent(Call, true, false, true);
        retired.AcceptInitial(Call, true, true); retired.SetConnected(Call, true);
        retired.DeclineUpgrade(Call, Call);
        Check(retired.CurrentPhase == CallVideoConsent.Phase.Voice, "decline returns to voice");
        Check(retired.DeclineUpgrade(Call, Call) == CallVideoConsent.Result.Ignored, "decline is once-only");
        Check(!retired.AuthorizeGeneration(Call, Call, 2), "no consent, no generation");

        var disconnected = new CallVideoConsent(Call, true, true, true);
        disconnected.PeerAnswered(Call, true); disconnected.SetConnected(Call, true);
        disconnected.AuthorizeGeneration(Call, Call, 2); disconnected.MarkMediaReady(Call, 2);
        Check(Capture(disconnected), "video captures once everything agrees");
        disconnected.SetConnected(Call, false);
        Check(!Capture(disconnected) && disconnected.Generation == 0, "losing the call revokes capture");

        // Remote camera state is information: it must never change local consent or the local camera.
        var remote = new CallVideoConsent(Call, true, false, true);
        remote.AcceptInitialReceiveOnly(Call); remote.SetConnected(Call, true);
        remote.AuthorizeGeneration(Call, Call, 2); remote.MarkMediaReady(Call, 2);
        Check(remote.CameraOn(Call, true) == CallVideoConsent.Result.Ready, "local camera on");
        Check(remote.RemoteCameraState(Call, 2, 1, true), "remote reports camera on");
        Check(remote.RemoteCameraOn, "remote state recorded");
        Check(Capture(remote), "remote state does not affect local capture");
        Check(!remote.RemoteCameraState(Call, 2, 1, false), "revision must strictly increase");
        Check(!remote.RemoteCameraState(Call, 3, 2, false), "bound to the active generation");
        Check(remote.RemoteCameraState(Call, 2, 2, false), "newer revision accepted");
        Check(!Capture(remote) == false, "local camera unaffected by remote state change");
        Check(remote.CameraOn(Call, true) == CallVideoConsent.Result.Busy, "local camera already wanted");

        var ended = new CallVideoConsent(Call, true, false, true);
        ended.End();
        Check(ended.CurrentPhase == CallVideoConsent.Phase.Ended, "ended call");
        Check(!ended.IsLive(Call), "ended call is not live");
        Check(ended.AcceptInitial(Call, true, true) == CallVideoConsent.Result.Ignored, "no consent after end");

        try
        {
            _ = new CallVideoConsent("not-a-uuid", true, false, false);
            Check(false, "invalid call id must be refused");
        }
        catch (ArgumentException) { Check(true, "invalid call id refused"); }

        try
        {
            // Video cannot be invited from a peer that never proved it can parse v2.
            _ = new CallVideoConsent(Call, false, false, true);
            Check(false, "video invitation without capability must be refused");
        }
        catch (ArgumentException) { Check(true, "video invitation without capability refused"); }

        // <= 128 request ids per call, then the upgrade is refused rather than growing without bound.
        // Established voice call, so the outstanding request id starts empty and every comparison
        // below is between a fresh id and whatever is currently outstanding.
        var bounded = new CallVideoConsent(Call, true, false, false);
        bounded.SetConnected(Call, true);
        Check(bounded.ReceiveRequest(Call, Guid.NewGuid().ToString("D")) == CallVideoConsent.Result.Prompt, "first prompt");
        Check(bounded.DeclineUpgrade(Call, bounded.RequestId!) == CallVideoConsent.Result.Declined, "retire first");
        int accepted = 1;
        for (int i = 1; i < 200; i++)
        {
            var id = Guid.NewGuid().ToString("D");
            var result = bounded.ReceiveRequest(Call, id);
            if (result == CallVideoConsent.Result.Prompt)
            {
                accepted++;
                bounded.DeclineUpgrade(Call, id);
            }
            else if (result == CallVideoConsent.Result.Unsupported) break;
        }
        Check(accepted == CallVideoProtocol.MaxRequests,
            $"at most {CallVideoProtocol.MaxRequests} request ids per call, saw {accepted}");
    }

    static void AdmissionChecks()
    {
        Section("admission");
        var admission = new CallFrameAdmission(Call, Other, 2, 0);
        var offer = CallSignaling.MediaOffer(Call, 1, 2, "video", Call,
            FakeCallVideoMedia.VideoSdp(2, "actpass", "o"));

        Check(!admission.Admit(offer, Other, false), "an unpermitted frame is refused");
        Check(admission.LastAcceptedSequence == 0, "a refused frame must not consume the sequence");
        Check(!admission.Admit(offer, Low, true), "a frame from another peer is refused");
        Check(!admission.Admit(offer, Other, true) == false, "the permitted frame is admitted");
        Check(admission.LastAcceptedSequence == 1, "the admitted frame advances the sequence");
        Check(!admission.Admit(offer, Other, true), "a replayed frame is refused");

        var reordered = new CallFrameAdmission(Call, Other, 2, 0);
        var bad = CallSignaling.MediaOffer(Call, 5, 2, "video", Call, "not an sdp");
        Check(!reordered.Admit(bad, Other, true), "a malformed body is refused");
        Check(reordered.LastAcceptedSequence == 0,
            "a malformed body must not consume the sequence, or the real frame after it is lost");

        var v1 = new CallFrameAdmission(Call, Other, 1, 0);
        Check(!v1.Admit(offer, Other, true), "a v2 frame is refused inside a v1 call");
        var legacy = CallSignaling.Ping(Call, 1);
        Check(v1.Admit(legacy, Other, true), "a v1 frame is admitted inside a v1 call");
        Check(!v1.Admit(CallSignaling.Ping(Call, 1), Other, true), "a v1 replay is refused");

        try
        {
            _ = new CallFrameAdmission(Call, Other, 3, 0);
            Check(false, "an unknown protocol version must be refused");
        }
        catch (ArgumentException) { Check(true, "unknown protocol version refused"); }
    }

    static void CapabilitiesChecks()
    {
        Section("capabilities");
        Check(CallCapabilities.Response == "LM4\tCALLCAPS\t2\tVP8", "the capability response is the shared literal");
        Check(CallVideoProtocol.CapabilityResponse(false) == CallCapabilities.Response, "a capable build answers");
        Check(CallVideoProtocol.CapabilityResponse(true) == null, "a legacy build answers nothing at all");
        Check(!CallVideoProtocol.Capable(CallCapabilities.Response, false, false, 1), "an unverified peer is not capable");
        Check(!CallVideoProtocol.Capable(CallCapabilities.Response, true, true, 1), "a legacy build is not capable");
        Check(!CallVideoProtocol.Capable(CallCapabilities.Response, true, false, CallCapabilities.TimeoutMs),
            "a reply at the deadline is too late");
        Check(CallVideoProtocol.Capable(CallCapabilities.Response, true, false, CallCapabilities.TimeoutMs - 1),
            "a reply just inside the deadline is accepted");
        Check(CallProtocol.MaxFrameBytes == 64 * 1024 && CallProtocol.MaxSdpBytes == 48 * 1024
            && CallProtocol.MaxIceCandidates == 128 && CallProtocol.MaxCandidateBytes == 4096
            && CallVideoProtocol.MaxRequests == 128,
            "the shared v2 bounds are the agreed ones");

        // The envelope must reject bytes, not just shapes.
        Check(CallSignaling.Parse(new byte[] { 0, 0, 0 }) == null, "a short header is refused");
        var ping = CallSignaling.Ping(Call, 1);
        var wire = CallSignaling.Serialize(ping);
        var mangled = (byte[])wire.Clone();
        mangled[3] ^= 0xFF;
        Check(CallSignaling.Parse(mangled) == null, "a wrong length prefix is refused");
        Check(CallSignaling.Parse(wire.AsSpan(0, wire.Length - 1).ToArray()) == null, "a truncated frame is refused");

        // A v1 frame round-trips unchanged; this is what an archived 2.2.42 peer still sends.
        var legacyInvite = CallSignaling.Invite(Call, 1, Low, Other);
        var roundTripped = CallSignaling.Parse(CallSignaling.Serialize(legacyInvite));
        Check(roundTripped != null && roundTripped.V == 1, "a v1 invite still parses as v1");
        Check(roundTripped != null && roundTripped.B != null && roundTripped.B.Count == 2,
            "a v1 invite still carries exactly caller/callee");
        Check(roundTripped != null && roundTripped.B!.ContainsKey("media") == false,
            "a v1 invite must not gain a media key");
        Check(roundTripped != null && roundTripped.Gen == 0, "a v1 invite keeps generation zero");
    }

    static void PlacementChecks()
    {
        Section("placement");
        var placement = new CallVideoPlacement();
        var at = placement.Position(1000, 800, 200, 150, 16);
        Check(at[0] == 784 && at[1] == 16, "the preview starts in the unobstructed corner");
        placement.Move(100, 100);
        Check(placement.Position(1000, 800, 200, 150, 16)[0] == 100, "a remembered position is honoured");
        // A window shrunk under a preview the user dragged to the far corner: the remembered position
        // has to be pulled back inside the stage, or the preview would end up over the controls.
        placement.Move(500, 500);
        var clamped = placement.Position(400, 300, 200, 150, 16);
        Check(clamped[0] == 200 && clamped[1] == 150, "a remembered position is clamped into the stage");
        placement.Move(float.NaN, 50);
        var afterBadDrag = placement.Position(1000, 800, 200, 150, 16);
        Check(afterBadDrag[0] == 200 && afterBadDrag[1] == 150, "a non-finite drag is dropped whole, not half-applied");
        placement.Reset();
        Check(placement.Position(100, 100, 200, 150, 16)[0] == 0, "a stage smaller than the preview clamps to zero");
        placement.Hide(true);
        Check(placement.Hidden, "the preview can be hidden");
    }

    static void DiagnosticsChecks()
    {
        Section("diagnostics");
        var unavailable = CallVideoDiagnostics.Snapshot.Unavailable();
        Check(unavailable.Codec == null && unavailable.SentFps == null, "unavailable is genuinely unavailable");
        Check(CallVideoDiagnostics.Display(unavailable).Contains("Unavailable"), "unavailable renders as Unavailable");

        var outOfRange = new CallVideoDiagnostics.Snapshot("H264", 5000, 1, 1, 1, 150, 1);
        Check(outOfRange.Codec == null, "only VP8 is ever named");
        Check(outOfRange.SentFps == null, "an implausible frame rate is dropped");
        Check(outOfRange.LossPercent == null, "loss above 100% is dropped");

        var now = 1_000_000_000L;
        var fresh = new CallVideoDiagnostics.Snapshot("VP8", 30, 29, 500, 480, 0.5, now);
        Check(fresh.Fresh(now + 1_000_000_000L).SentFps == 30, "a recent snapshot stays fresh");
        Check(fresh.Fresh(now + 4_000_000_000L).SentFps == null, "a snapshot older than three seconds is unavailable");
        Check(fresh.Fresh(now - 1).SentFps == null, "a snapshot from the future is unavailable");

        // Nothing in a report may carry free-form text that could contain something sensitive.
        var report = CallVideoDiagnostics.Report(fresh, "2.2.42");
        Check(report.Contains("2.2.42"), "a safe version is reported");
        Check(CallVideoDiagnostics.Report(fresh, "2.2.42-branch-user@host").Contains("Unavailable"),
            "an unsafe version string is replaced");
        Check(!CallVideoDiagnostics.Report(fresh, "2.2.42-branch-user@host").Contains("user@host"),
            "no part of an unsafe version string leaks into the report");

        var sampler = new CallVideoDiagnostics.Sampler();
        // Loss needs a previous packet count to subtract from, so the baseline carries zeros rather than
        // nulls: a null baseline means "no loss measurement", not "no loss".
        var first = new CallVideoDiagnostics.Counters
        { TimestampUs = 1_000_000, Codec = "VP8", SentFrames = 10, ReceivedFrames = 10, SentBytes = 1000, ReceivedBytes = 1000, ReceivedPackets = 0, LostPackets = 0 };
        var second = new CallVideoDiagnostics.Counters
        { TimestampUs = 2_000_000, Codec = "VP8", SentFrames = 40, ReceivedFrames = 38, SentBytes = 5000, ReceivedBytes = 4800, ReceivedPackets = 100, LostPackets = 2 };
        Check(sampler.Sample(first, 1).SentFps == null, "the first sample has no interval");
        var sampled = sampler.Sample(second, 2);
        Check(sampled.SentFps != null && Math.Abs(sampled.SentFps.Value - 30) < 0.001, "30 frames per second over one second");
        Check(sampled.SentKbps != null && Math.Abs(sampled.SentKbps.Value - 32) < 0.001, "4000 bytes per second is 32 kbps");
        // Loss is reported against received+lost (102 packets) rather than against sent (100), so the
        // two numbers must never be quoted as if they shared a denominator.
        Check(sampled.LossPercent != null && Math.Abs(sampled.LossPercent.Value - 200.0 / 102.0) < 0.001,
            "2 lost of 102 received+sent packets");
        var tooClose = new CallVideoDiagnostics.Counters { TimestampUs = 2_100_000, Codec = "VP8", SentFrames = 41 };
        Check(sampler.Sample(tooClose, 3).SentFps == null, "a sub-quarter-second interval yields no rate");
        sampler.Reset();
        Check(sampler.Sample(second, 4).SentFps == null, "reset drops the baseline");
        Check(sampler.Sample(null, 5).SentFps == null, "a null reading yields no rate");
    }

    sealed class CountingRoot : CallVideoResources.IRoot
    {
        public int Closed;
        public object? SharedContext { get; } = new object();
        public void Dispose() => Closed++;
    }

    static void ResourceChecks()
    {
        Section("resources");
        var created = new List<CountingRoot>();
        var resources = new CallVideoResources(new RootProvider(created));
        var media = resources.AcquireMedia();
        Check(created.Count == 1, "the root is opened lazily");
        Check(resources.MediaUsers == 1, "one media user");
        var secondMedia = resources.AcquireMedia();
        Check(secondMedia.SharedContext != null, "a renderer can use the shared context");
        Check(created.Count == 1 && resources.MediaUsers == 2, "a second user shares the one root");

        var renderer = resources.AcquireRenderer();
        Check(resources.RendererUsers == 1, "one renderer user");
        resources.Dispose();
        Check(created[0].Closed == 0, "closing the service must not destroy a root a renderer still holds");
        renderer.Dispose();
        Check(created[0].Closed == 0, "one media lease is still holding the root");
        secondMedia.Dispose();
        Check(created[0].Closed == 0, "still held by the remaining media lease");
        media.Dispose();
        Check(created[0].Closed == 1, "the root is released once the last lease goes");

        try
        {
            using var closing = new CallVideoResources(new RootProvider(created));
            closing.Dispose();
            closing.AcquireMedia();
            Check(false, "a closing service must refuse new users");
        }
        catch (InvalidOperationException) { Check(true, "a closing service refuses new users"); }

        try
        {
            using var orphan = new CallVideoResources(new RootProvider(created));
            orphan.AcquireRenderer();
            Check(false, "a renderer with no media must be refused");
        }
        catch (InvalidOperationException) { Check(true, "a renderer with no media is refused"); }

        var twice = new CallVideoResources(new RootProvider(created));
        var lease = twice.AcquireMedia();
        lease.Dispose();
        lease.Dispose();
        Check(twice.MediaUsers == 0, "a lease released twice is counted once");
        try
        {
            _ = lease.SharedContext;
            Check(false, "a released lease must not hand out a context");
        }
        catch (InvalidOperationException) { Check(true, "a released lease refuses a context"); }

        // The device refusing to open a root must surface as a refusal, not as a null lease the
        // caller then dereferences.
        var starved = new CallVideoResources(new RootProvider(created) { Exhausted = true });
        try
        {
            starved.AcquireMedia();
            Check(false, "an unavailable root must refuse the caller");
        }
        catch (InvalidOperationException) { Check(true, "an unavailable root refuses the caller"); }
    }

    sealed class RootProvider : CallVideoResources.IProvider
    {
        readonly List<CountingRoot> created;
        public bool Exhausted;
        public RootProvider(List<CountingRoot> created) { this.created = created; }
        public CallVideoResources.IRoot? Open()
        {
            // An explicit flag rather than sniffing the shared list: a closed root from an earlier
            // scenario is not the same thing as the device refusing to open one now.
            if (Exhausted) return null;
            var root = new CountingRoot();
            created.Add(root);
            return root;
        }
    }

    static void CameraPermissionChecks()
    {
        Section("camera-permission");
        var permission = new CallCameraPermission();
        Check(permission.Begin(Call, Other, true, true, true, false) == CallCameraPermission.Decision.Stale,
            "an answer for another call is stale");
        Check(permission.Begin(Call, Call, false, true, true, false) == CallCameraPermission.Decision.Stale,
            "an answer while offline is stale");
        Check(permission.Begin(Call, Call, true, false, true, false) == CallCameraPermission.Decision.Unavailable,
            "no camera is unavailable");
        Check(permission.Begin(Call, Call, true, true, false, true) == CallCameraPermission.Decision.Denied,
            "permanently denied is denied");
        Check(permission.Begin(Call, Call, true, true, true, false) == CallCameraPermission.Decision.Ready,
            "already granted is ready");
        // Begin always parks a request so a later asynchronous answer can be matched to it. A Ready
        // answer is simply consumed on the spot, which is what has to happen before another Begin.
        permission.Complete(permission.Token, Call, true, true, true);
        Check(!permission.HasPending, "an already-granted request is satisfied at once");
        Check(permission.Begin(Call, Call, true, true, false, false) == CallCameraPermission.Decision.Request,
            "not granted asks again");
        var token = permission.Token;
        Check(permission.Begin(Call, Call, true, true, true, false) == CallCameraPermission.Decision.Busy,
            "one request at a time");

        // A late answer for a call that has ended must be discarded, not applied to the current one.
        Check(permission.Complete(token, Other, true, true, true) == CallCameraPermission.Decision.Stale,
            "an answer for a finished call is stale");
        Check(!permission.HasPending, "a stale answer still clears the request");

        permission.Begin(Call, Call, true, true, false, false);
        var second = permission.Token;
        Check(permission.Complete(token, Call, true, true, true) == CallCameraPermission.Decision.Stale,
            "a superseded token is stale");
        Check(permission.HasPending, "a stale token does not clear the live request");
        Check(permission.Complete(second, Call, true, true, true) == CallCameraPermission.Decision.Ready,
            "the live token completes");
        Check(!permission.HasPending, "completion clears the request");

        permission.Begin(Call, Call, true, true, false, false);
        permission.Reconcile(Other, true);
        Check(!permission.HasPending, "reconciling to another call cancels the request");
        permission.Begin(Call, Call, true, true, false, false);
        permission.Reconcile(Call, false);
        Check(!permission.HasPending, "going offline cancels the request");
    }

    /// Caller upgrades an established voice call. Exercises the full negotiation: request, accept,
    /// OFFER, ANSWER, MEDIA_READY both ways, then the camera.
    static void CoordinatorUpgradeChecks()
    {
        Section("coordinator-upgrade");
        var wire = new RecordingWire();
        var consent = new CallVideoConsent(Call, true, true, true);
        consent.PeerAnswered(Call, false);          // an ordinary voice call
        consent.SetConnected(Call, true);
        var media = new FakeCallVideoMedia();
        var coordinator = new CallVideoCoordinator(Call, true, consent, _ => true, media, wire.Send);
        coordinator.AudioConnected();
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces");

        coordinator.RequestVideo();
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces after request");
        var request = consent.RequestId;
        Check(request != null && wire.Count(CallProtocol.VIDEO_REQUEST) == 1, "the caller asked once");
        Check(wire.Last(CallProtocol.VIDEO_REQUEST)!.B!["request"] as string == request, "the request names its own id");

        // The peer accepts; the caller -- which always owns the offer -- starts negotiation.
        coordinator.ReceiveAdmitted(CallSignaling.VideoAcceptUpgrade(Call, 1, 0, request!), _ => true);
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces after accept");
        Check(consent.Generation == 2, "the first video generation is 2");
        var offer = wire.Last(CallProtocol.OFFER);
        Check(offer != null && offer.B!["media"] as string == "video", "the offer is a video offer");
        Check(offer != null && offer.B!["request"] as string == request, "the offer is bound to the request");
        Check(offer != null && CallVideoProtocol.ValidSdp(offer.B!["sdp"], true), "the offer SDP is a valid VP8 offer");
        Check(!media.CameraRunning, "negotiation alone never opens a camera");
        Check(consent.CurrentPhase == CallVideoConsent.Phase.Negotiating, "still negotiating");

        coordinator.ReceiveAdmitted(CallSignaling.MediaAnswer(Call, 2, 2, "video", request!,
            FakeCallVideoMedia.VideoSdp(2, "active", "a")), _ => true);
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces after answer");
        Check(!media.CameraRunning, "an answer alone does not open a camera");

        // One side being ready is not enough.
        coordinator.ReceiveAdmitted(CallSignaling.MediaReadyFor(Call, 3, 2, "video", request!), _ => true);
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces after remote ready");
        Check(!media.CameraRunning, "the peer's media ready alone does not open a camera");
        Check(consent.CurrentPhase == CallVideoConsent.Phase.Negotiating, "still negotiating without local ready");

        // Local media ready arrives through the adapter callback, not through the wire.
        media.ReportReadyForTest();
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces after local ready");
        Check(media.CameraRunning, "both sides ready opens the camera");
        Check(consent.CurrentPhase == CallVideoConsent.Phase.Video, "video is live");

        var state = wire.Last(CallProtocol.VIDEO_STATE);
        Check(state != null && state.B!["camera"] is true, "the camera state is reported");
        Check(state != null && state.Gen == 2, "the camera state carries the generation");
        Check(state != null && !state.B!.ContainsKey("media"), "VIDEO_STATE carries no media key");

        coordinator.CameraOff();
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces after camera off");
        Check(!media.CameraRunning, "the camera stops");
        Check(consent.CanCapture(Call, true, true, true) == false, "consent revokes immediately");

        // A revoked permission stops the camera even though the negotiation is still live.
        coordinator.CameraOn();
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces after camera on");
        Check(media.CameraRunning, "an explicit local action restarts the camera");

        coordinator.Dispose();
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces after dispose");
        Check(!media.CameraRunning, "dispose stops the camera");
        Check(!coordinator.IsForCall(Call), "a disposed coordinator belongs to no call");
    }

    /// The callee answered an incoming video invitation. The CALLER always drives negotiation, so this
    /// asserts the reverse shape: the callee answers rather than offers, and a second OFFER for a
    /// different generation is refused by the video-specific boundary rather than restarting video.
    static void CoordinatorInitialVideoChecks()
    {
        Section("coordinator-initial-video");
        var wire = new RecordingWire();
        var consent = new CallVideoConsent(Call, true, false, true);  // incoming video invitation
        Check(consent.AcceptInitial(Call, true, true) == CallVideoConsent.Result.Ready,
            "the user accepted video on an incoming invitation");
        var media = new FakeCallVideoMedia();
        media.EmitReadyOnInitialize = true;   // our own transport becomes ready as soon as it is created
        var coordinator = new CallVideoCoordinator(Call, false, consent, _ => true, media, wire.Send);

        coordinator.AudioConnected();
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces");
        // A callee never offers: without the caller's OFFER there is nothing to answer.
        Check(wire.Count(CallProtocol.OFFER) == 0, "the callee never offers");
        Check(consent.Generation == 0, "no generation without the caller's offer");
        Check(!media.CameraRunning, "answering a video invitation does not open a camera");
        Check(consent.CurrentPhase == CallVideoConsent.Phase.Waiting, "waiting for the caller's offer");

        coordinator.ReceiveAdmitted(CallSignaling.MediaOffer(Call, 1, 2, "video", Call,
            FakeCallVideoMedia.VideoSdp(2, "actpass", "o")), _ => true);
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces after offer");
        Check(consent.Generation == 2, "the callee admits the caller's generation");
        var answer = wire.Last(CallProtocol.ANSWER);
        Check(answer != null && answer.Gen == 2 && answer.B!["media"] as string == "video", "the callee answers video");
        Check(answer != null && CallVideoProtocol.ValidSdp(answer.B!["sdp"], true), "the answer SDP is valid");
        Check(!media.CameraRunning, "answering does not open a camera");

        coordinator.ReceiveAdmitted(CallSignaling.MediaReadyFor(Call, 2, 2, "video", Call), _ => true);
        Check(coordinator.AwaitIdle(5000), "coordinator quiesces after remote ready");
        Check(media.CameraRunning, "both sides ready opens the camera for the answering callee");
        Check(consent.CurrentPhase == CallVideoConsent.Phase.Video, "video is live");

        // A second OFFER, or any frame for a generation that is not the active one, is refused. If a
        // renegotiation were possible here the two peers would silently disagree about which
        // generation their media belongs to.
        Check(!coordinator.Permits(CallSignaling.MediaOffer(Call, 3, 3, "video", Call,
                FakeCallVideoMedia.VideoSdp(3, "actpass", "o"))),
            "an offer for a new generation is refused while one is live");
        Check(!coordinator.Permits(CallSignaling.MediaOffer(Call, 4, 2, "video", Call,
                FakeCallVideoMedia.VideoSdp(2, "actpass", "o"))),
            "a repeated offer for the live generation is refused");
        Check(!coordinator.Permits(CallSignaling.VideoRequest(Call, 5, 0, Other)),
            "a new request is refused while video is live");
        Check(!coordinator.Permits(CallSignaling.MediaOffer(Call, 6, 3, "video", Other,
                FakeCallVideoMedia.VideoSdp(3, "actpass", "o"))),
            "a frame bound to another request is refused");
        Check(consent.Generation == 2 && media.CameraRunning,
            "a refused frame must not disturb the live video");
        coordinator.Dispose();

        // Collision: two outstanding requests, lower UUID wins, and the higher one is declined. The
        // call starts as plain voice (invitedVideo false) so the outstanding request id starts
        // empty; answering an initial video invitation would put the call id in that slot and the
        // collision would be decided against the call id instead of between the two requests.
        var collisionWire = new RecordingWire();
        var collisionConsent = new CallVideoConsent(Call, true, false, false);
        var collisionMedia = new FakeCallVideoMedia();
        var collision = new CallVideoCoordinator(Call, false, collisionConsent, _ => true, collisionMedia, collisionWire.Send);
        collisionConsent.SetConnected(Call, true);
        collision.AudioConnected();
        Check(collision.AwaitIdle(5000), "collision coordinator quiesces");

        collision.ReceiveAdmitted(CallSignaling.VideoRequest(Call, 1, 0, High), _ => true);
        Check(collision.AwaitIdle(5000), "collision coordinator quiesces");
        Check(collisionConsent.RequestId == High, "the first request is pending");
        Check(collisionWire.Count(CallProtocol.VIDEO_DECLINE) == 0, "a first request is not declined");
        collision.ReceiveAdmitted(CallSignaling.VideoRequest(Call, 2, 0, Low), _ => true);
        Check(collision.AwaitIdle(5000), "collision coordinator quiesces");
        Check(collisionConsent.RequestId == Low, "the lower UUID wins the collision");
        Check(collisionWire.Count(CallProtocol.VIDEO_DECLINE) == 1, "the losing request is declined on the wire");
        Check(collisionWire.Count(CallProtocol.VIDEO_ACCEPT) == 0, "nothing is accepted without the user");
        collision.Dispose();
    }

    /// The media seam itself: generation staleness, video-only disposal, and the capture gate.
    static void MediaSeamChecks()
    {
        Section("media-seam");
        var media = new FakeCallVideoMedia();
        try { media.CreateOffer(2); Check(false, "an offer before initialize must fail"); }
        catch (InvalidOperationException) { Check(true, "an offer before initialize fails"); }
        media.Initialize(2, () => true);
        Check(!media.CameraRunning, "initialize never captures");
        Check(media.CreateOffer(2) != null, "an offer for the active generation");
        try { media.CreateOffer(3); Check(false, "a stale generation must fail"); }
        catch (InvalidOperationException) { Check(true, "a stale generation fails"); }
        media.Dispose(3);
        Check(media.Log.Contains("dispose:3") == false, "a stale dispose is ignored");
        media.StopCamera(3);
        Check(media.CameraRunning == false, "a stale stopCamera is ignored");

        media.StartCamera(2);
        Check(media.CameraRunning, "the gate allows capture");
        media.StopCamera(2);
        Check(!media.CameraRunning, "capture stops");

        var gated = new FakeCallVideoMedia();
        gated.Initialize(2, () => false);
        try { gated.StartCamera(2); Check(false, "a closed gate must refuse capture"); }
        catch (InvalidOperationException) { Check(true, "a closed gate refuses capture"); }
        Check(!gated.CameraRunning, "a refused capture leaves the camera off");

        // The SDP the fake produces must satisfy the same validator the peer applies.
        var video = FakeCallVideoMedia.VideoSdp(2, "actpass", "o");
        Check(CallVideoProtocol.ValidSdp(video, true), "the fake's video SDP is valid for video");
        Check(!CallVideoProtocol.ValidSdp(video, false), "a video SDP is not valid for audio");
        var audio = FakeCallVideoMedia.AudioSdp(1, "actpass", "o");
        Check(CallVideoProtocol.ValidSdp(audio, false), "the fake's audio SDP is valid for audio");
        Check(!CallVideoProtocol.ValidSdp(audio, true), "an audio SDP is not valid for video");
        Check(!CallVideoProtocol.ValidSdp("v=0\nm=video 9 UDP/TLS/RTP/SAVPF 96\n", true),
            "an LF-only SDP is refused");
        Check(!CallVideoProtocol.ValidSdp(video + "a=rtpmap:98 H264/90000\r\n", true),
            "an SDP offering a codec outside the VP8 set is refused");
    }
}
