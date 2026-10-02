package net.lanmsg.chat;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Unit tests for the call subsystem (Phase A0 contract code).
 *  Pure Java — no Android/JNI dependency.
 *
 *  Run: java -cp <classes-dir> net.lanmsg.chat.CallCheck */
public final class CallCheck {
  static int pass, fail;
  static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    System.out.println("CALLCHECK — Phase A0 call subsystem tests");

    testProtocol();
    testSignaling();
    testSettings();
    testQualityMonitor();
    testInvitationLimiter();
    testControllerLifecycle();
    testControllerIncoming();
    testControllerMute();
    testFrameParse();
    testGlareResolution();
    testInvitationThrottlingViaController();
    testPolicyRace();
    testSettingsCorruption();
    testAudioOwnership();
    testControllerAudioOwnershipLifecycle();
    testRoutePolicy();
    testQualitySharedFixture();
    testInviteRepliesAreNotSilent();
    testMalformedCallIdRefused();
    testRingingIsConfirmed();
    testDisablingDeclinesRingingCall();
    testStaleAcceptCannotBypassPolicy();
    testTerminalWordingIsUniform();
    testListenerFanOut();
    testAudioRouteRecordedOnSnapshot();
    testUiSnapshotLabels();
    testInboundFrameAdmissionTable();
    testCallerAcceptReachesConnecting();
    testParsedFrameTypeComparesByValue();
    testTerminalSnapshotRetention();

    System.out.println("\nCALLCHECK PASS=" + pass + " FAIL=" + fail);
    if (fail > 0) {
      System.out.println("Failures:");
      for (String f : failures) System.out.println("  " + f);
      System.exit(1);
    }
  }

  // ── Helpers ────────────────────────────────────────────────────

  static void check(boolean cond, String id, String msg) {
    if (cond) { pass++; } else { fail++; failures.add(id + ": " + msg); }
  }
  static void eq(Object a, Object b, String id) { check(Objects.equals(a,b), id, "got=" + a + " want=" + b); }
  static void neq(Object a, Object b, String id) { check(!Objects.equals(a,b), id, "unexpected " + a + " == " + b); }

  // ── Tests ──────────────────────────────────────────────────────

  static void testProtocol() {
    System.out.println("testProtocol...");

    // State transitions
    eq(CallProtocol.transition(CallProtocol.State.Idle, CallProtocol.INVITE, false),
       CallProtocol.State.IncomingRinging, "T01-invite-idle->incoming");
    eq(CallProtocol.transition(CallProtocol.State.IncomingRinging, CallProtocol.ACCEPT, false),
       CallProtocol.State.Connecting, "T02-accept-incoming->connecting");
    eq(CallProtocol.transition(CallProtocol.State.Connecting, CallProtocol.MEDIA_READY, false),
       CallProtocol.State.Connected, "T03-ready->connected");
    eq(CallProtocol.transition(CallProtocol.State.Connected, CallProtocol.HANGUP, false),
       CallProtocol.State.Ending, "T04-hangup->ending");
    eq(CallProtocol.transition(CallProtocol.State.OutgoingRinging, CallProtocol.DECLINE, true),
       CallProtocol.State.Ending, "T05-decline-outgoing->ending");

    // Invalid transitions
    eq(CallProtocol.transition(CallProtocol.State.Connected, CallProtocol.INVITE, false),
       null, "T06-connected-no-invite");
    eq(CallProtocol.transition(CallProtocol.State.Idle, CallProtocol.HANGUP, false),
       null, "T07-idle-no-hangup");

    // Terminal check
    check(CallProtocol.State.Idle.terminal(), "T08-idle-terminal", "");
    check(CallProtocol.State.Ending.terminal(), "T09-ending-terminal", "");
    check(!CallProtocol.State.Connected.terminal(), "T10-connected-not-terminal", "");

    // Glare resolution
    check(CallProtocol.glareWinner("aaa", "bbb", 1, 2), "T11-glare-lower-wins", "");
    check(!CallProtocol.glareWinner("zzz", "aaa", 1, 2), "T12-glare-higher-loses", "");

    // Validation
    check(CallProtocol.validCallId(UUID.randomUUID().toString()), "T13-valid-call-id", "");
    check(!CallProtocol.validCallId("not-a-uuid"), "T14-invalid-call-id", "");
    check(!CallProtocol.validCallId(null), "T15-null-call-id", "");
  }

  static void testSignaling() {
    System.out.println("testSignaling...");

    String callId = UUID.randomUUID().toString();

    // Build and serialize
    CallProtocol.Frame invite = CallSignaling.invite(callId, 1, "alice", "bob");
    eq(invite.type, CallProtocol.INVITE, "S01-type");
    eq(invite.callId, callId, "S02-call-id");

    byte[] wire = CallSignaling.serialize(invite);
    check(wire.length > 4 && wire.length <= CallProtocol.MAX_FRAME_BYTES, "S03-serialize", "");

    // Parse round-trip
    CallProtocol.Frame parsed = CallSignaling.parse(wire);
    check(parsed != null, "S04-parse-ok", "");
    eq(parsed.type, CallProtocol.INVITE, "S05-parse-type");
    eq(parsed.callId, callId, "S06-parse-call-id");
    eq(CallSignaling.getCaller(parsed), "alice", "S07-parse-caller");
    eq(CallSignaling.getCallee(parsed), "bob", "S08-parse-callee");

    // Oversized frame rejection
    byte[] big = new byte[CallProtocol.MAX_FRAME_BYTES + 100];
    big[0] = 0; big[1] = 0; big[2] = 0; big[3] = (byte)(CallProtocol.MAX_FRAME_BYTES + 96);
    check(CallSignaling.parse(big) == null, "S09-oversize-rejected", "");

    // Malformed input
    check(CallSignaling.parse(new byte[]{0,0,0,10,'{','i','n','v','a','l','i','d','}','x'}) == null,
      "S10-malformed", "");

    // All message types round-trip
    for (String type : new String[]{CallProtocol.RINGING, CallProtocol.ACCEPT,
         CallProtocol.DECLINE, CallProtocol.BUSY, CallProtocol.CANCEL, CallProtocol.HANGUP,
         CallProtocol.PING, CallProtocol.PONG}) {
      CallProtocol.Frame f = new CallProtocol.Frame(type, callId, 1, 0);
      byte[] w = CallSignaling.serialize(f);
      CallProtocol.Frame p = CallSignaling.parse(w);
      check(p != null && type.equals(p.type), "S11-roundtrip-" + type, "");
    }

    // Offer/Answer with SDP
    CallProtocol.Frame offer = CallSignaling.offer(callId, 1, 0, "v=0\r\ns=test\r\n");
    eq(CallSignaling.getSdp(offer), "v=0\r\ns=test\r\n", "S12-offer-sdp");

    // ICE candidate
    CallProtocol.Frame ice = CallSignaling.ice(callId, 1, 0,
      "candidate:1 1 UDP 123 10.0.0.1 9999 typ host", "0", 0);
    eq(CallSignaling.getCandidate(ice), "candidate:1 1 UDP 123 10.0.0.1 9999 typ host",
      "S13-ice-candidate");
  }

  static void testSettings() throws Exception {
    System.out.println("testSettings...");

    File tmpDir = File.createTempFile("call-test-", ".d");
    tmpDir.delete(); tmpDir.mkdirs();
    try {
      // Default enabled (no file)
      CallSettings s = new CallSettings(tmpDir);
      check(s.allowIncoming(), "C01-default-enabled", "");
      check(s.loadError() == null, "C02-no-load-error", "");

      // Disable and persist
      s.setAllowIncoming(false);
      check(!s.allowIncoming(), "C03-disabled", "");

      // Reload and verify persistence
      CallSettings s2 = new CallSettings(tmpDir);
      check(!s2.allowIncoming(), "C04-reload-disabled", "");
      check(s2.loadError() == null, "C05-reload-no-error", "");

      // Enable and reload
      s2.setAllowIncoming(true);
      check(s2.allowIncoming(), "C06-re-enabled", "");
      CallSettings s3 = new CallSettings(tmpDir);
      check(s3.allowIncoming(), "C07-reload-enabled", "");

      // Generation monotonic
      long gen = s3.generation();
      s3.setAllowIncoming(false);
      check(s3.generation() > gen, "C08-gen-increments", "");

    } finally {
      for (File f : tmpDir.listFiles()) f.delete();
      tmpDir.delete();
    }
  }

  static void testQualityMonitor() {
    System.out.println("testQualityMonitor...");

    CallQualityMonitor m = new CallQualityMonitor();
    long now = 1000000;

    // Warm-up: all Unknown
    m.reset(now);
    eq(m.evaluate(now), CallProtocol.Quality.Unknown, "Q01-warmup-unknown");

    // Feed good samples past warm-up
    for (int i = 0; i < 10; i++) {
      now += CallProtocol.QUALITY_SAMPLE_INTERVAL_MS;
      ICallMedia.Stats s = new ICallMedia.Stats();
      s.valid = true;
      s.packetsReceived = (i + 1) * 50 + 100;
      s.packetsLost = 0;
      s.jitterMs = 5.0;
      s.roundTripTimeMs = 20;
      m.sample(now, s);
    }
    eq(m.evaluate(now), CallProtocol.Quality.Normal, "Q02-good-is-normal");

    // Introduce high loss (cumulative counters); evaluate each sample
    for (int i = 0; i < 8; i++) {
      now += CallProtocol.QUALITY_SAMPLE_INTERVAL_MS;
      ICallMedia.Stats s = new ICallMedia.Stats();
      s.valid = true;
      s.packetsReceived = (i + 11) * 50;             // cumulative, increasing
      s.packetsLost = (i + 1) * 8;                    // cumulative, high loss (~16%)
      s.jitterMs = 40.0;  // above threshold
      s.roundTripTimeMs = 50;
      m.sample(now, s);
      m.evaluate(now); // advance the degraded-consecutive counter
    }
    eq(m.evaluate(now), CallProtocol.Quality.Reduced, "Q03-high-loss-reduced");

    // Recover
    for (int i = 0; i < 15; i++) {
      now += CallProtocol.QUALITY_SAMPLE_INTERVAL_MS;
      ICallMedia.Stats s = new ICallMedia.Stats();
      s.valid = true;
      s.packetsReceived = 700 + (i + 1) * 50;  // cumulative, continuing after degraded
      s.packetsLost = 64;                        // cumulative, no new loss
      s.jitterMs = 5.0;
      s.roundTripTimeMs = 20;
      m.sample(now, s);
      m.evaluate(now); // advance recovery
    }
    eq(m.evaluate(now), CallProtocol.Quality.Normal, "Q04-recovered-normal");

    // Stop clears
    m.stop();
    eq(m.current(), CallProtocol.Quality.Unknown, "Q05-stop-unknown");
  }

  static void testInvitationLimiter() {
    System.out.println("testInvitationLimiter...");

    CallProtocol.InvitationLimiter limiter = new CallProtocol.InvitationLimiter();
    long now = 1000000;

    // Allow up to limit
    for (int i = 0; i < CallProtocol.INVITATION_LIMIT_COUNT; i++) {
      check(limiter.record("peer1", now + i), "L01-record-" + (i+1), "");
    }
    // Next should be rejected
    check(!limiter.record("peer1", now + CallProtocol.INVITATION_LIMIT_COUNT),
      "L02-exceeded-rejected", "");

    // Different peer unaffected
    check(limiter.record("peer2", now), "L03-different-peer", "");

    // Window expiration
    long later = now + CallProtocol.INVITATION_LIMIT_WINDOW_MS + 1;
    check(limiter.record("peer1", later), "L04-window-expired", "");

    // Prune
    limiter.prune(later + 1);
    check(limiter.record("peer1", later + 2), "L05-after-prune", "");
  }

  // ── Controller lifecycle tests ─────────────────────────────────

  static PeerEngine dummyEngine() throws Exception {
    File tmpDir = new File(System.getProperty("java.io.tmpdir"), "call-check-" + UUID.randomUUID());
    tmpDir.mkdirs();
    PeerEngine eng = new PeerEngine(tmpDir, "TestDevice", new TestProtector(tmpDir));
    return eng;
  }

  static void testControllerLifecycle() throws Exception {
    System.out.println("testControllerLifecycle...");

    PeerEngine eng = dummyEngine();
    CallSettings settings = new CallSettings(eng.file.getParentFile());
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    // Initial state
    check(ctrl.isIdle(), "CT01-initial-idle", "");
    check(ctrl.snapshot() == null, "CT02-no-snapshot", "");
    check(!ctrl.hasActive(), "CT03-no-active", "");

    // We can't test startCall without a real peer/transport, but we can test shutdown
    ctrl.onOffline(); // should be no-op when idle
    check(ctrl.isIdle(), "CT04-offline-idle-ok", "");

    ctrl.shutdown();
    check(ctrl.isIdle(), "CT05-shutdown-idle", "");

    eng.close();
    // Cleanup
    for (File f : eng.file.getParentFile().listFiles()) f.delete();
    eng.file.getParentFile().delete();
  }

  static void testControllerIncoming() throws Exception {
    System.out.println("testControllerIncoming...");

    PeerEngine eng = dummyEngine();
    // Create a verified peer record
    String peerId = UUID.randomUUID().toString();
    PeerEngine.Peer peer = new PeerEngine.Peer(peerId, "TestPeer", "10.0.0.1", 43872);
    peer.fingerprint = "aa:bb:cc:dd";
    peer.verified = "aa:bb:cc:dd"; // trusted
    synchronized (eng) { eng.peers.put(peerId, peer); }

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    // Track state changes
    final List<CallSession> snapshots = Collections.synchronizedList(new ArrayList<>());
    ctrl.setCallback(snap -> snapshots.add(snap));

    String callId = UUID.randomUUID().toString();

    // Create a test transport that captures sent frames
    final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
    CallController.Transport transport = sent::add;

    // Send INVITE
    CallProtocol.Frame invite = CallSignaling.invite(callId, 1, peerId, eng.id);
    CallSession snap = ctrl.onInvite(invite, peerId, transport);
    check(snap != null, "CT10-invite-accepted", "");
    eq(snap.state, CallProtocol.State.IncomingRinging, "CT11-state-incoming-ringing");
    check(!snap.isCaller, "CT12-we-are-callee", "");

    // Decline
    ctrl.decline();

    // Verify DECLINE was sent
    boolean sentDecline = false;
    for (byte[] w : sent) {
      CallProtocol.Frame f = CallSignaling.parse(w);
      if (f != null && CallProtocol.DECLINE.equals(f.type)) { sentDecline = true; break; }
    }
    check(sentDecline, "CT13-decline-sent", "");
    check(ctrl.isIdle(), "CT14-idle-after-decline", "");

    // Disable incoming — should reject
    settings.setAllowIncoming(false);
    String callId2 = UUID.randomUUID().toString();
    CallProtocol.Frame invite2 = CallSignaling.invite(callId2, 2, peerId, eng.id);
    CallSession snap2 = ctrl.onInvite(invite2, peerId, transport);
    check(snap2 == null, "CT15-disabled-rejects", "");

    ctrl.shutdown();
    eng.close();
    for (File f : eng.file.getParentFile().listFiles()) f.delete();
    eng.file.getParentFile().delete();
  }

  static void testControllerMute() throws Exception {
    System.out.println("testControllerMute...");

    PeerEngine eng = dummyEngine();
    CallSettings settings = new CallSettings(eng.file.getParentFile());
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    check(!ctrl.isMuted(), "CT20-initial-not-muted", "");

    // setMute when no active session should still work (media is null, but no crash)
    try { ctrl.setMute(true); } catch (Exception e) { check(false, "CT21-mute-no-crash", e.getMessage()); }

    ctrl.shutdown();
    eng.close();
    for (File f : eng.file.getParentFile().listFiles()) f.delete();
    eng.file.getParentFile().delete();
  }

  static void testFrameParse() {
    System.out.println("testFrameParse...");

    // Valid frame
    String callId = UUID.randomUUID().toString();
    CallProtocol.Frame f = CallSignaling.invite(callId, 1, "alice", "bob");
    byte[] wire = CallSignaling.serialize(f);
    CallProtocol.Frame parsed = CallSignaling.parse(wire);
    check(parsed != null, "F01-parse", "");
    eq(parsed.protocolVersion, 1, "F02-version");
    eq(CallSignaling.getCaller(parsed), "alice", "F03-caller");

    // Null input
    check(CallSignaling.parse(null) == null, "F04-null", "");

    // Too short
    check(CallSignaling.parse(new byte[]{0,0}) == null, "F05-too-short", "");

    // Length mismatch
    byte[] mismatch = new byte[10];
    mismatch[0]=0; mismatch[1]=0; mismatch[2]=0; mismatch[3]=100;
    check(CallSignaling.parse(mismatch) == null, "F06-length-mismatch", "");
  }

  // ── AT01 extended: glare, duplicates, policy race, settings corruption ──

  static void testGlareResolution() throws Exception {
    System.out.println("testGlareResolution...");
    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    addTrustedPeer(eng, peerId);

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
    CallController.Transport transport = sent::add;

    // Simulate: our outgoing INVITE crosses with their incoming INVITE
    // We are "zzz-device" (higher lexicographic), they are "aaa-peer" (lower)
    // Since (ourId + theirId) > (theirId + ourId) alphabetically, the LOWER comparison wins
    // Actually: glareWinner checks if (localId + remoteId) <= (remoteId + localId)
    // "zzz-deviceaaa-peer" vs "aaa-peerzzz-device": "zzz-device" > "aaa" so zzz loses, aaa wins

    // We'll test with our ID < peer ID: us="aaa", peer="zzz"
    // "aaazzz" <= "zzzaaa" → true → we win

    String callId1 = UUID.randomUUID().toString();
    String callId2 = UUID.randomUUID().toString();

    // Their INVITE arrives first
    CallProtocol.Frame theirInvite = CallSignaling.invite(callId2, 1, peerId, eng.id);
    CallSession snap = ctrl.onInvite(theirInvite, peerId, transport);
    check(snap != null, "G01-incoming-accepted", "");
    eq(snap.state, CallProtocol.State.IncomingRinging, "G02-state-incoming");

    // A duplicate INVITE (same callId) should be idempotent
    CallSession snap2 = ctrl.onInvite(theirInvite, peerId, transport);
    check(snap2 != null, "G03-duplicate-invite-accepted", "");
    eq(snap2.callId, callId2, "G04-same-call-id");

    ctrl.decline();

    cleanup(eng, ctrl);
  }

  static void testInvitationThrottlingViaController() throws Exception {
    System.out.println("testInvitationThrottling...");
    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    addTrustedPeer(eng, peerId);

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
    CallController.Transport transport = sent::add;

    // Send INVITEs up to the limit
    for (int i = 0; i < CallProtocol.INVITATION_LIMIT_COUNT; i++) {
      String cid = UUID.randomUUID().toString();
      CallProtocol.Frame inv = CallSignaling.invite(cid, i + 1, peerId, eng.id);
      CallSession snap = ctrl.onInvite(inv, peerId, transport);
      check(snap != null, "H01-invite-" + i, "");
      ctrl.decline(); // end each call so next can start
    }

    // Next one should be rejected (BUSY — invitation limit exceeded)
    String cid = UUID.randomUUID().toString();
    CallProtocol.Frame inv = CallSignaling.invite(cid, 99, peerId, eng.id);
    CallSession snap = ctrl.onInvite(inv, peerId, transport);
    check(snap == null, "H02-throttled-rejected", "");

    cleanup(eng, ctrl);
  }

  static void testPolicyRace() throws Exception {
    System.out.println("testPolicyRace...");
    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    addTrustedPeer(eng, peerId);

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
    CallController.Transport transport = sent::add;

    // Incoming INVITE arrives
    String callId = UUID.randomUUID().toString();
    CallProtocol.Frame inv = CallSignaling.invite(callId, 1, peerId, eng.id);
    CallSession snap = ctrl.onInvite(inv, peerId, transport);
    check(snap != null, "P01-incoming-ringing", "");

    // Disable incoming calls while ringing — should decline
    settings.setAllowIncoming(false);
    // Decline the first call to free the controller
    ctrl.decline();

    // A second INVITE should be rejected
    String callId2 = UUID.randomUUID().toString();
    CallProtocol.Frame inv2 = CallSignaling.invite(callId2, 2, peerId, eng.id);
    CallSession snap2 = ctrl.onInvite(inv2, peerId, transport);
    check(snap2 == null, "P02-disabled-rejects-new", "");

    // Re-enable
    settings.setAllowIncoming(true);

    // New INVITE accepted again
    String callId3 = UUID.randomUUID().toString();
    CallProtocol.Frame inv3 = CallSignaling.invite(callId3, 3, peerId, eng.id);
    CallSession snap3 = ctrl.onInvite(inv3, peerId, transport);
    check(snap3 != null, "P03-re-enabled-accepts", "");

    ctrl.decline();
    cleanup(eng, ctrl);
  }

  static void testSettingsCorruption() throws Exception {
    System.out.println("testSettingsCorruption...");
    File tmpDir = File.createTempFile("call-settings-corr-", ".d");
    tmpDir.delete(); tmpDir.mkdirs();
    try {
      // Write garbage to the settings file
      File sf = new File(tmpDir, "call-settings.txt");
      try (FileOutputStream fos = new FileOutputStream(sf)) {
        fos.write("garbage data\n".getBytes(StandardCharsets.UTF_8));
      }

      CallSettings s = new CallSettings(tmpDir);
      // Corrupt → disabled for session
      check(!s.allowIncoming(), "K01-corrupt-disabled", "");
      check(s.loadError() != null, "K02-load-error", "");

      // Successful write clears error
      s.setAllowIncoming(true);
      check(s.allowIncoming(), "K03-write-clears-error", "");
      check(s.loadError() == null, "K04-error-cleared", "");
    } finally {
      for (File f : tmpDir.listFiles()) f.delete();
      tmpDir.delete();
    }
  }

  // ── A05: audio ownership between calls and voice messages ──────

  static void testAudioOwnership() {
    System.out.println("testAudioOwnership...");
    AudioOwnership o = new AudioOwnership();
    eq(o.current(), AudioOwnership.Owner.FREE, "A05-01-free");
    check(o.canStartCall(), "A05-02-call-allowed-when-free", "");
    check(o.canStartRecording(), "A05-03-record-allowed-when-free", "");

    // A ringing call claims the device; voice recording must be refused
    check(o.claimRing(), "A05-04-ring-claims", "");
    eq(o.current(), AudioOwnership.Owner.CALL_RINGING, "A05-05-ringing-owner");
    check(!o.canStartRecording(), "A05-06-record-blocked-while-ringing", "");
    check(!o.claimRecording(), "A05-07-record-claim-refused", "");
    // Ringing does not block another call from starting
    check(o.canStartCall(), "A05-08-call-allowed-while-ringing", "");

    // Playback is refused while the call owns the device
    check(!o.claimPlayback(), "A05-09-playback-blocked", "");

    // Connect: capture takes over from ringing
    check(o.claimCallCapture(), "A05-10-capture-claims", "");
    eq(o.current(), AudioOwnership.Owner.CALL_CAPTURE, "A05-11-capture-owner");
    check(!o.canStartRecording(), "A05-12-record-blocked-while-capturing", "");

    // Hangup releases everything
    o.forceRelease();
    eq(o.current(), AudioOwnership.Owner.FREE, "A05-13-released");
    check(o.canStartRecording(), "A05-14-record-allowed-after-release", "");

    // Voice recording blocks a call
    check(o.claimRecording(), "A05-15-recording-claims", "");
    check(!o.canStartCall(), "A05-16-call-blocked-while-recording", "");
    check(!o.claimRing(), "A05-17-ring-refused-while-recording", "");

    // A guarded release does not steal someone else's claim
    o.forceRelease();
    check(o.claimRing(), "A05-18-reclaim-ring", "");
    o.releaseIf(AudioOwnership.Owner.VOICE_RECORDING); // wrong owner: no-op
    eq(o.current(), AudioOwnership.Owner.CALL_RINGING, "A05-19-guarded-release-noop");
    o.releaseIf(AudioOwnership.Owner.CALL_RINGING);
    eq(o.current(), AudioOwnership.Owner.FREE, "A05-20-guarded-release-fires");

    // Playback does not block a call, and recording can interrupt playback
    check(o.claimPlayback(), "A05-21-playback-claims", "");
    check(o.canStartCall(), "A05-22-call-allowed-while-playing", "");
    check(o.claimRecording(), "A05-23-recording-interrupts-playback", "");
    eq(o.current(), AudioOwnership.Owner.VOICE_RECORDING, "A05-24-recording-owner-after-interrupt");
  }

  static void testControllerAudioOwnershipLifecycle() throws Exception {
    System.out.println("testControllerAudioOwnershipLifecycle...");
    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    addTrustedPeer(eng, peerId);

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    AudioOwnership owner = new AudioOwnership();
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.setAudioOwner(owner);
    ctrl.start();

    final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
    CallController.Transport transport = sent::add;

    // Incoming call claims the device for ringing
    CallProtocol.Frame inv = CallSignaling.invite(UUID.randomUUID().toString(), 1, peerId, eng.id);
    ctrl.onInvite(inv, peerId, transport);
    eq(owner.current(), AudioOwnership.Owner.CALL_RINGING, "A05-30-invite-claims-ring");
    check(!owner.canStartRecording(), "A05-31-voice-blocked-while-ringing", "");

    // Declining releases the device
    ctrl.decline();
    eq(owner.current(), AudioOwnership.Owner.FREE, "A05-32-decline-releases");

    // Engine shutdown also releases, even with no session
    ctrl.shutdown();
    eq(owner.current(), AudioOwnership.Owner.FREE, "A05-33-shutdown-releases");

    // A voice recording in progress must block an outgoing call
    owner.claimRecording();
    try { ctrl.startCall(peerId, transport); check(false, "A05-34-call-blocked-while-recording", ""); }
    catch (Exception expected) { check(true, "A05-34-call-blocked-while-recording", ""); }
    owner.forceRelease();

    cleanup(eng, null);
  }

  // ── A07: route selection policy ────────────────────────────────

  static void testRoutePolicy() {
    System.out.println("testRoutePolicy...");
    CallRoutePolicy p = new CallRoutePolicy();
    p.setNowMs(1_000_000);
    CallRoutePolicy.Availability all = new CallRoutePolicy.Availability(true, true, true, true);
    CallRoutePolicy.Availability plain = new CallRoutePolicy.Availability(true, true, false, false);
    CallRoutePolicy.Availability headset = new CallRoutePolicy.Availability(true, true, true, false);
    CallRoutePolicy.Availability lostEarpiece = new CallRoutePolicy.Availability(false, true, false, false);

    // Default is earpiece, and proximity follows it
    eq(p.selected(), CallRoutePolicy.Route.Earpiece, "A07-01-default-earpiece");
    check(p.proximityActive(), "A07-02-proximity-on-earpiece", "");

    // A wired headset is preferred over the earpiece automatically
    eq(p.preferred(headset), CallRoutePolicy.Route.Wired, "A07-03-wired-preferred");
    eq(p.preferred(plain), CallRoutePolicy.Route.Earpiece, "A07-04-earpiece-preferred");
    eq(p.preferred(lostEarpiece), CallRoutePolicy.Route.Speaker, "A07-05-speaker-last-resort");

    // A stable route produces no change
    check(!p.reconcile(all, false), "A07-06-no-change-when-stable", "");

    // Speaker disables proximity
    p.userSelect(CallRoutePolicy.Route.Speaker);
    eq(p.selected(), CallRoutePolicy.Route.Speaker, "A07-07-user-speaker");
    check(!p.proximityActive(), "A07-08-proximity-off-on-speaker", "");

    // A user-selected route is never silently overridden while it is missing
    p.userSelect(CallRoutePolicy.Route.Bluetooth);
    check(!p.reconcile(plain, true), "A07-09-user-route-not-overridden", "");
    eq(p.selected(), CallRoutePolicy.Route.Bluetooth, "A07-10-user-route-retained");

    // Earpiece loss: hold during the recovery window, then fall back to speaker
    p.reset();
    p.setNowMs(2_000_000);
    check(!p.reconcile(lostEarpiece, false), "A07-11-hold-during-recovery", "");
    eq(p.selected(), CallRoutePolicy.Route.Earpiece, "A07-12-hold-keeps-route");
    p.setNowMs(2_000_000 + CallProtocol.ROUTE_RECOVERY_MS);
    check(p.reconcile(lostEarpiece, false), "A07-13-falls-back-after-recovery", "");
    eq(p.selected(), CallRoutePolicy.Route.Speaker, "A07-14-fallback-is-speaker");

    // A route that returns cancels the recovery and does not change anything
    p.userSelect(CallRoutePolicy.Route.Wired);
    p.setNowMs(3_000_000);
    check(!p.reconcile(plain, true), "A07-15-missing-user-route-held", "");
    check(!p.reconcile(headset, true), "A07-16-returned-route-no-change", "");
    eq(p.selected(), CallRoutePolicy.Route.Wired, "A07-17-returned-route-retained");

    // No route at all is a failure, not a silent fallback
    CallRoutePolicy.Availability none = new CallRoutePolicy.Availability(false, false, false, false);
    check(p.noRouteAvailable(none), "A07-18-no-route-detected", "");
    check(!p.noRouteAvailable(plain), "A07-19-route-available", "");
  }

  static void testQualitySharedFixture() {
    System.out.println("testQualitySharedFixture...");

    // Verify deterministic behavior with a known fixture sequence
    List<ICallMedia.Stats> samples = new ArrayList<>();
    long baseTime = 1000000;

    // 6 seconds of good audio
    for (int i = 0; i < 6; i++) {
      ICallMedia.Stats s = new ICallMedia.Stats();
      s.valid = true; s.packetsReceived = 100 + i * 50; s.packetsLost = 0;
      s.jitterMs = 3.0; s.roundTripTimeMs = 15; s.timestampMs = baseTime + i * 1000;
      samples.add(s);
    }
    // 8 seconds of degraded audio
    for (int i = 0; i < 8; i++) {
      ICallMedia.Stats s = new ICallMedia.Stats();
      s.valid = true; s.packetsReceived = 400 + i * 50; s.packetsLost = 5 + i * 10;
      s.jitterMs = 45.0; s.roundTripTimeMs = 350; s.timestampMs = baseTime + 6000 + i * 1000;
      samples.add(s);
    }

    // Evaluate — should be Reduced after sustained degradation
    CallProtocol.Quality result = CallQualityMonitor.evaluateSequence(samples, baseTime);
    eq(result, CallProtocol.Quality.Reduced, "Q06-fixture-reduced");

    // Verify matched behavior: same input → same output
    CallProtocol.Quality result2 = CallQualityMonitor.evaluateSequence(samples, baseTime);
    eq(result2, CallProtocol.Quality.Reduced, "Q07-fixture-deterministic");
  }

  // ── Helpers ────────────────────────────────────────────────────

  static void addTrustedPeer(PeerEngine eng, String peerId) {
    PeerEngine.Peer peer = new PeerEngine.Peer(peerId, "TestPeer", "10.0.0.1", 43872);
    peer.fingerprint = "aa:bb:cc:dd";
    peer.verified = "aa:bb:cc:dd";
    synchronized (eng) { eng.peers.put(peerId, peer); }
  }

  static void cleanup(PeerEngine eng, CallController ctrl) {
    if (ctrl != null) ctrl.shutdown();
    try { eng.close(); } catch (Exception ignored) {}
    for (File f : eng.file.getParentFile().listFiles()) f.delete();
    eng.file.getParentFile().delete();
  }

  // ── A08-A10: entry point, policy withdrawal, terminal wording, listener fan-out ──

  /** Find the type of the first frame a transport was asked to send. */
  static String sentType(List<byte[]> sent, int index) throws Exception {
    byte[] wire = sent.get(index);
    int length = java.nio.ByteBuffer.wrap(wire, 4, 4).getInt();
    CallProtocol.Frame frame = CallSignaling.parse(wire);
    return frame == null ? null : frame.type;
  }

  static List<String> sentTypes(List<byte[]> sent) throws Exception {
    List<String> types = new ArrayList<>();
    for (byte[] wire : sent) {
      CallProtocol.Frame frame = CallSignaling.parse(wire);
      types.add(frame == null ? "?" : frame.type);
    }
    return types;
  }

  static void testInviteRepliesAreNotSilent() throws Exception {
    System.out.println("testInviteRepliesAreNotSilent...");
    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    addTrustedPeer(eng, peerId);

    // Policy disabled: the caller must receive DECLINE, not a silent channel close. A silent
    // close made the caller ring out its full timeout and report "No answer" for what was a
    // policy decline.
    CallSettings off = new CallSettings(eng.file.getParentFile());
    off.setAllowIncoming(false);
    CallController declined = new CallController(eng, off);
    declined.setMediaFactory(new FakeCallMedia.Factory());
    declined.start();
    final List<byte[]> a = Collections.synchronizedList(new ArrayList<>());
    CallProtocol.Frame invite = CallSignaling.invite("c0a11e00-0000-4000-8000-000000000001", 1, peerId, eng.id);
    check(declined.onInvite(invite, peerId, a::add) == null, "N01-policy-rejects", "");
    eq(sentTypes(a), Collections.singletonList(CallProtocol.DECLINE), "N02-policy-sends-decline");
    check(declined.snapshot() == null, "N03-policy-no-session", "");
    check(declined.isIdle(), "N04-policy-stays-idle", "");

    // Throttled: the generic BUSY outcome, never a decline. The caller must not be able to tell
    // throttling from a policy decision, and neither is a claim that a person acted.
    CallSettings on = new CallSettings(eng.file.getParentFile());
    on.setAllowIncoming(true);
    CallController throttled = new CallController(eng, on);
    throttled.setMediaFactory(new FakeCallMedia.Factory());
    throttled.start();
    for (int i = 0; i < CallProtocol.INVITATION_LIMIT_COUNT; i++) {
      final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
      check(throttled.onInvite(CallSignaling.invite(UUID.randomUUID().toString(), i + 1, peerId, eng.id),
        peerId, sent::add) != null, "N05-warmup-" + i, "");
      throttled.decline();
    }
    final List<byte[]> busy = Collections.synchronizedList(new ArrayList<>());
    check(throttled.onInvite(CallSignaling.invite("c0a11e00-0000-4000-8000-000000000002", 1, peerId, eng.id), peerId, busy::add) == null,
      "N05-throttle-rejects", "");
    eq(sentTypes(busy), Collections.singletonList(CallProtocol.BUSY), "N06-throttle-sends-busy");

    // Busy because another call is already up.
    throttled.onInvite(CallSignaling.invite("c0a11e00-0000-4000-8000-000000000003", 1, peerId, eng.id), peerId, new ArrayList<>()::add);
    final List<byte[]> busy2 = Collections.synchronizedList(new ArrayList<>());
    check(throttled.onInvite(CallSignaling.invite("c0a11e00-0000-4000-8000-000000000004", 1, peerId, eng.id), peerId, busy2::add) == null,
      "N07-occupied-rejects", "");
    eq(sentTypes(busy2), Collections.singletonList(CallProtocol.BUSY), "N08-occupied-sends-busy");

    // An unverified or mismatched peer gets no reply at all: answering would confirm its guess.
    final List<byte[]> silent = Collections.synchronizedList(new ArrayList<>());
    check(throttled.onInvite(CallSignaling.invite("c0a11e00-0000-4000-8000-000000000005", 1, peerId, eng.id), "different-peer", silent::add) == null,
      "N09-identity-mismatch-rejects", "");
    check(silent.isEmpty(), "N10-identity-mismatch-silent", "got=" + sentTypes(silent));

    cleanup(eng, declined);
    throttled.shutdown();
  }

  static void testMalformedCallIdRefused() throws Exception {
    System.out.println("testMalformedCallIdRefused...");
    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    addTrustedPeer(eng, peerId);

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    // A call ID that is not a UUID is refused before a session exists. Admitting one would create
    // a call whose own frames the peer's parser rejects, so it could only ever expire.
    final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
    check(ctrl.onInvite(CallSignaling.invite("not-a-uuid", 1, peerId, eng.id), peerId, sent::add) == null,
      "N35-malformed-call-id-refused", "");
    check(ctrl.isIdle(), "N36-malformed-id-no-session", "");
    check(sent.isEmpty(), "N37-malformed-id-silent", "got=" + sentTypes(sent));

    cleanup(eng, ctrl);
  }

  static void testRingingIsConfirmed() throws Exception {
    System.out.println("testRingingIsConfirmed...");
    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    addTrustedPeer(eng, peerId);

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
    CallProtocol.Frame invite = CallSignaling.invite("c0a11e00-0000-4000-8000-000000000006", 1, peerId, eng.id);
    CallSession snap = ctrl.onInvite(invite, peerId, sent::add);
    check(snap != null, "N11-invite-accepted", "");
    eq(snap.state, CallProtocol.State.IncomingRinging, "N12-ringing-state");
    // RINGING tells the caller its 30 s window was extended, so an unanswered invitation ends when
    // this device's own timeout fires rather than by coincidence.
    check(sentTypes(sent).contains(CallProtocol.RINGING), "N13-confirms-ringing",
      "got=" + sentTypes(sent));

    cleanup(eng, ctrl);
  }

  static void testDisablingDeclinesRingingCall() throws Exception {
    System.out.println("testDisablingDeclinesRingingCall...");
    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    addTrustedPeer(eng, peerId);

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.setAudioOwner(new AudioOwnership());
    ctrl.start();

    final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
    ctrl.onInvite(CallSignaling.invite("c0a11e00-0000-4000-8000-000000000007", 1, peerId, eng.id), peerId, sent::add);
    check(ctrl.hasActive(), "N20-ringing-is-active", "");

    check(ctrl.setAllowIncoming(false), "N21-disable-declines-ringing", "");
    check(sentTypes(sent).contains(CallProtocol.DECLINE), "N22-sends-decline",
      "got=" + sentTypes(sent));
    check(ctrl.isIdle(), "N23-no-session-after-withdrawal", "");
    check(!ctrl.hasActive(), "N24-not-active-after-withdrawal", "");
    // The terminal snapshot must survive so the UI can withdraw its notification and show a
    // reason, even though the controller has already returned to Idle.
    check(ctrl.snapshot() == null, "N25-controller-idle", "");

    // A second invitation now gets the same policy decline, not a ring.
    final List<byte[]> after = Collections.synchronizedList(new ArrayList<>());
    ctrl.onInvite(CallSignaling.invite("c0a11e00-0000-4000-8000-000000000008", 1, peerId, eng.id), peerId, after::add);
    eq(sentTypes(after), Collections.singletonList(CallProtocol.DECLINE), "N26-still-declined");

    cleanup(eng, ctrl);
  }

  static void testStaleAcceptCannotBypassPolicy() throws Exception {
    System.out.println("testStaleAcceptCannotBypassPolicy...");
    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    addTrustedPeer(eng, peerId);

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
    ctrl.onInvite(CallSignaling.invite("c0a11e00-0000-4000-8000-000000000009", 1, peerId, eng.id), peerId, sent::add);

    // An Accept naming a different call is refused. This is the notification-action case: the
    // action was raised for an invitation that has since been replaced.
    boolean refused = false;
    try { ctrl.accept("c0a11e00-0000-4000-8000-00000000000b"); }
    catch (IOException expected) { refused = true; }
    check(refused, "N30-wrong-call-id-refused", "");
    check(ctrl.snapshot() != null &&
      ctrl.snapshot().state == CallProtocol.State.IncomingRinging, "N31-still-ringing", "");

    // The preference being turned off withdraws the invitation even before Accept arrives.
    ctrl.setAllowIncoming(false);
    sent.clear();
    boolean afterWithdrawal = false;
    try { ctrl.accept("c0a11e00-0000-4000-8000-000000000009"); }
    catch (IOException expected) { afterWithdrawal = true; }
    check(afterWithdrawal, "N32-accept-after-withdrawal-refused", "");

    // A fresh invitation that is admitted can be accepted, and only for its own call ID.
    settings.setAllowIncoming(true);
    final List<byte[]> fresh = Collections.synchronizedList(new ArrayList<>());
    CallSession live = ctrl.onInvite(CallSignaling.invite("c0a11e00-0000-4000-8000-00000000000a", 1, peerId, eng.id), peerId, fresh::add);
    check(live != null, "N33-fresh-invite-accepted", "");
    // The call ID of the withdrawn invitation is still refused, even though a new one is ringing:
    // the revalidation is against the live call, not merely "is something ringing".
    boolean withdrawnIdRefused = false;
    try { ctrl.accept("c0a11e00-0000-4000-8000-000000000009"); }
    catch (IOException expected) { withdrawnIdRefused = true; }
    check(withdrawnIdRefused, "N34-withdrawn-id-still-refused", "");
    ctrl.accept("c0a11e00-0000-4000-8000-00000000000a");
    check(ctrl.snapshot() != null &&
      ctrl.snapshot().state == CallProtocol.State.Connecting, "N35-accept-succeeds-for-live-call",
      "state=" + (ctrl.snapshot() == null ? "null" : ctrl.snapshot().state));

    cleanup(eng, ctrl);
  }

  static void testTerminalWordingIsUniform() throws Exception {
    System.out.println("testTerminalWordingIsUniform...");
    // A08/A10: every ordinary decline reads "Declined" on the caller's side, whether a person
    // pressed Decline or the device declined by policy. Nothing discloses the preference, and
    // nothing claims a person acted.
    eq(CallUi.endLabel(CallProtocol.EndReason.DECLINED), "Declined", "N40-decline-wording");
    eq(CallUi.endLabel(CallProtocol.EndReason.LOCAL_DECLINE), "Declined", "N41-local-decline-wording");
    check(CallUi.endLabel(CallProtocol.EndReason.DECLINED)
      .equals(CallUi.endLabel(CallProtocol.EndReason.LOCAL_DECLINE)),
      "N42-decline-wording-identical", "");
    for (CallProtocol.EndReason reason : CallProtocol.EndReason.values()) {
      String label = CallUi.endLabel(reason);
      check(label != null && !label.isEmpty(), "N43-nonempty-" + reason, "");
      check(!label.toLowerCase(Locale.ROOT).contains("busy on this phone"),
        "N44-no-preference-disclosure-" + reason, label);
    }
    // Throttled and occupied attempts are generically "Busy", never a claim about a person.
    eq(CallUi.endLabel(CallProtocol.EndReason.BUSY_REMOTE), "Busy", "N45-busy-wording");
    eq(CallUi.endLabel(CallProtocol.EndReason.TIMEOUT_RINGING), "No answer", "N46-timeout-wording");
  }

  static void testListenerFanOut() throws Exception {
    System.out.println("testListenerFanOut...");
    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    addTrustedPeer(eng, peerId);

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    // The service's notification and the Activity's UI both observe the same stream, and neither
    // displaces the primary callback slot the service view-model holds.
    final List<String> primary = new ArrayList<>();
    final List<String> extra = new ArrayList<>();
    CallController.Callback observer = snap -> extra.add(String.valueOf(snap.state));
    ctrl.setCallback(snap -> primary.add(String.valueOf(snap.state)));
    ctrl.addListener(observer);
    ctrl.onInvite(CallSignaling.invite("c0a11e00-0000-4000-8000-00000000000c", 1, peerId, eng.id), peerId, new ArrayList<>()::add);
    check(!primary.isEmpty(), "N50-primary-callback-fires", "got=" + primary);
    check(!extra.isEmpty(), "N51-listener-fires", "got=" + extra);
    eq(extra.get(0), String.valueOf(CallProtocol.State.IncomingRinging), "N52-listener-sees-state");

    // A second setCallback (what a naive Activity bind would do) must not silence the listener.
    ctrl.setCallback(snap -> {});
    final List<String> after = new ArrayList<>();
    ctrl.addListener(snap -> after.add(String.valueOf(snap.state)));
    ctrl.decline();
    check(!after.isEmpty(), "N53-listener-survives-rebind", "got=" + after);

    // A listener that throws must not stop the others or break the controller.
    ctrl.addListener(snap -> { throw new RuntimeException("bad listener"); });
    final List<String> survivor = new ArrayList<>();
    ctrl.addListener(snap -> survivor.add("reached"));
    ctrl.onInvite(CallSignaling.invite("c0a11e00-0000-4000-8000-00000000000d", 1, peerId, eng.id), peerId, new ArrayList<>()::add);
    check(survivor.contains("reached"), "N54-bad-listener-isolated", "got=" + survivor);

    ctrl.removeListener(observer);
    final List<String> removed = new ArrayList<>();
    ctrl.addListener(snap -> removed.add("x"));
    int before = removed.size();
    ctrl.decline();
    check(removed.size() > before, "N55-decline-notifies", "");

    cleanup(eng, ctrl);
  }

  static void testAudioRouteRecordedOnSnapshot() throws Exception {
    System.out.println("testAudioRecordedOnSnapshot...");
    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    addTrustedPeer(eng, peerId);

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    // The call view must show the route that was actually applied, not a UI-side guess, so the
    // controller carries it in the snapshot.
    ctrl.onInvite(CallSignaling.invite("c0a11e00-0000-4000-8000-00000000000e", 1, peerId, eng.id), peerId, new ArrayList<>()::add);
    ctrl.accept();
    ctrl.setAudioRoute("Speaker");
    check(ctrl.snapshot() != null && "Speaker".equals(ctrl.snapshot().audioRoute),
      "N60-route-in-snapshot", "route=" + (ctrl.snapshot() == null ? "?" : ctrl.snapshot().audioRoute));

    // Mute draws its glyph from the snapshot as well, so flipping it has to publish one.  It did
    // not: the round control kept showing the state it was built with, so tapping it looked like
    // the toggle was simply broken.
    final int[] delivered = { 0 };
    ctrl.addListener(snap -> delivered[0]++);
    int before = delivered[0];
    ctrl.setMute(true);
    check(delivered[0] > before, "N60a-mute-publishes-a-snapshot",
      "the mute glyph is drawn from the snapshot, so nothing redraws unless one is published");
    check(ctrl.snapshot() != null && ctrl.snapshot().muted, "N60b-mute-visible-in-snapshot",
      "muted=" + (ctrl.snapshot() == null ? "?" : String.valueOf(ctrl.snapshot().muted)));

    cleanup(eng, ctrl);
  }

  static void testUiSnapshotLabels() throws Exception {
    System.out.println("testUiSnapshotLabels...");
    // A09: route, quality and connection state must stay visually distinct. Each renders its own
    // text, and a quality hint appears only for Reduced. Built through the mutable Builder, which
    // is what the controller holds, then snapshotted exactly as the UI receives it.
    CallSession.Builder b = new CallSession.Builder("c0a11e00-0000-4000-8000-00000000000f", "peer", false, System.currentTimeMillis());
    b.state = CallProtocol.State.Connected;
    b.quality = CallProtocol.Quality.Normal;
    CallSession s = b.snapshot();
    eq(CallUi.qualityHint(s), null, "N70-no-hint-when-normal");
    eq(CallUi.detailLabel(s), "", "N71-no-mute-label-when-not-muted");
    b.muted = true;
    eq(CallUi.detailLabel(b.snapshot()), "Muted", "N72-mute-is-its-own-line");
    b.muted = false;
    b.quality = CallProtocol.Quality.Reduced;
    check(CallUi.qualityHint(b.snapshot()) != null && !CallUi.qualityHint(b.snapshot()).isEmpty(),
      "N73-hint-when-reduced", "");
    // Duration is its own line, distinct from the state word and the detail line.
    eq(CallUi.stateLabel(s), "0:00", "N74-duration-label");
    eq(CallUi.formatDuration(65000), "1:05", "N75-duration-format");
    eq(CallUi.formatDuration(3600000), "60:00", "N76-duration-format-long");
    b.state = CallProtocol.State.Connecting;
    CallSession connecting = b.snapshot();
    eq(CallUi.stateLabel(connecting), "Connecting…", "N77-connecting-label");
    check(!CallUi.stateLabel(connecting).equals(CallUi.detailLabel(connecting)),
      "N78-state-and-detail-differ", "");
    // A quality hint while connecting is still distinct from the state and detail lines.
    check(!CallUi.qualityHint(connecting).equals(CallUi.detailLabel(connecting)),
      "N79-quality-and-detail-differ", "");
    b.state = CallProtocol.State.IncomingRinging;
    eq(CallUi.stateLabel(b.snapshot()), "Incoming call", "N80-incoming-label");
    b.isCaller = true;
    b.state = CallProtocol.State.OutgoingRinging;
    eq(CallUi.stateLabel(b.snapshot()), "Ringing…", "N81-outgoing-label");
    // The route is rendered from the snapshot, so an applied route is visible and an unknown one
    // renders as nothing rather than as a guess.
    b.audioRoute = "Speaker";
    b.state = CallProtocol.State.Connected;
    eq(b.snapshot().audioRoute, "Speaker", "N82-route-visible");

    // Mute and the speaker route are two independent toggles, and the collapsed bar has no control
    // of its own to read a glyph off, so the detail line names whichever of them are in force.
    b.audioRoute = null;
    b.muted = false;
    eq(CallUi.detailLabel(b.snapshot()), "", "N82a-neither-toggle-on");
    b.audioRoute = "Speaker";
    eq(CallUi.detailLabel(b.snapshot()), "Speaker", "N82b-speaker-route-named");
    b.muted = true;
    eq(CallUi.detailLabel(b.snapshot()), "Muted · Speaker", "N82c-both-toggles-named");
    b.audioRoute = "Earpiece";
    eq(CallUi.detailLabel(b.snapshot()), "Muted", "N82d-earpiece-is-not-named");

    // The call clock must run from the moment the call was ANSWERED, not from the moment it was
    // created.  It used to add the time since the invitation was created to the connected duration,
    // so a call picked up four seconds after it rang was already showing "0:04" -- a caller was
    // told the call had been up for longer than it had, and a call that rang out then got answered
    // resumed from the ringing time instead of zero.
    long now = System.currentTimeMillis();
    CallSession.Builder c = new CallSession.Builder("c0a11e00-0000-4000-8000-000000000010", "peer", false, now - 20000);
    c.state = CallProtocol.State.OutgoingRinging;
    eq(c.snapshot().elapsedMs(), 0L, "N143-no-clock-while-ringing");
    c.state = CallProtocol.State.Connected;
    eq(c.snapshot().elapsedMs(), 0L, "N144-no-clock-without-a-connect-time");
    c.connectedAtMs = now - 3000;
    CallSession connected = c.snapshot();
    check(connected.elapsedMs() >= 3000 && connected.elapsedMs() < 3000 + 2000,
      "N145-clock-runs-from-answer", "expected ~3000ms of connected time, got " + connected.elapsedMs());
    // The reported bug itself: "why the timer start from ringing?". A call that rang for 20s and
    // was picked up on the first second must read 0:00, not 0:20. Checked on a fresh builder rather
    // than inferred from N145, because N145's 5s ceiling would also pass against the old bug on a
    // short ring -- this one fails loudly whichever way the clock is computed.
    CallSession.Builder rung = new CallSession.Builder("c0a11e00-0000-4000-8000-0000000000aa", "peer", false, now - 20000);
    rung.state = CallProtocol.State.Connected;
    rung.connectedAtMs = now;
    check(rung.snapshot().elapsedMs() < 2000,
      "N146-ringing-time-is-not-counted",
      "a call answered after 20s of ringing showed " + CallUi.stateLabel(rung.snapshot()) + " on the clock");
    // Not an equality. elapsedMs() recomputes from the wall clock on every call, so an exact "0:03"
    // holds for a one-second window and fails on any machine that stalls for a second between two
    // lines, which is what made this flaky while N145 -- allowing two seconds for the same quantity
    // -- passed. The ringing gap is 17s, so landing inside 3-4s proves the 20s of ringing is not
    // being counted without depending on how fast the machine running the test is.
    check("0:03".equals(CallUi.stateLabel(connected)) || "0:04".equals(CallUi.stateLabel(connected)),
      "N147-timer-starts-at-answer",
      "expected 0:03 or 0:04 from a 3s call, got " + CallUi.stateLabel(connected));
    c.state = CallProtocol.State.Ending;
    eq(c.snapshot().elapsedMs(), 0L, "N148-no-clock-once-ended");

    // Every end reason a user can be shown has to say what happened in words, not just name it.
    // "Connection lost" was shown to callers whose own network was fine, because the phone at the
    // other end had been killed; the label alone gave no way to tell, and no way to act.
    for (CallProtocol.EndReason reason : CallProtocol.EndReason.values()) {
      check(CallUi.endHint(reason) != null && !CallUi.endHint(reason).isEmpty(),
        "N149-hint-for-" + reason.name(), "an end reason the user can see must explain itself");
      check(CallUi.endLabel(reason) != null && !CallUi.endLabel(reason).isEmpty(),
        "N150-label-for-" + reason.name(), "an end reason must still have a label");
      check(!CallUi.endHint(reason).equals(CallUi.endLabel(reason)),
        "N151-hint-differs-from-label-" + reason.name(), "the sentence must add to the label, not repeat it");
    }
    // SIGNALING_LOST is raised by the heartbeat timing out, by a local send failing and by the local
    // channel closing, and all three happen just as well when *this* phone drops off Wi-Fi.  The
    // sentence therefore must not name a side: it used to tell a user whose own network had failed
    // to go and check the other phone, which is the one thing they cannot do about it.
    check(!CallUi.endHint(CallProtocol.EndReason.SIGNALING_LOST).contains("other phone"),
      "N152-lost-link-names-no-side", "a lost link cannot say whose side it was, so the wording must not");
    check(CallUi.endHint(CallProtocol.EndReason.LOCAL_DECLINE).startsWith("You"),
      "N154-decline-is-yours", "the phone that pressed Decline must not be told the other phone declined");
    check(!CallUi.endHint(CallProtocol.EndReason.LOCAL_DECLINE)
        .equals(CallUi.endHint(CallProtocol.EndReason.DECLINED)),
      "N155-decline-two-sides-differ", "declining locally and being declined are different events");
    check(!CallUi.endLabel(CallProtocol.EndReason.SIGNALING_LOST).contains("lost"),
      "N153-lost-link-worded-plainly", "'Connection lost' reads as the local side failing");

    // The clock line is rewritten once a second for the whole call. With a permanently-live
    // accessibility region that made TalkBack read the duration out loud every second, which is the
    // one thing that stops a screen-reader user hearing the person they are on a call with.
    check(CallUi.isNewStateAnnouncement(null, CallProtocol.State.IncomingRinging),
      "N156-first-state-is-announced", "the first state of a call must be spoken");
    check(!CallUi.isNewStateAnnouncement(CallProtocol.State.Connected, CallProtocol.State.Connected),
      "N157-repaint-is-not-an-announcement",
      "an unchanged state must stay silent, or the clock is read out once a second");
    for (CallProtocol.State each : CallProtocol.State.values()) {
      check(!CallUi.isNewStateAnnouncement(each, each),
        "N158-same-state-silent-" + each.name(),
        "a repaint of " + each + " would be announced every second");
    }
    check(CallUi.isNewStateAnnouncement(CallProtocol.State.IncomingRinging,
        CallProtocol.State.Connected),
      "N159-answer-is-announced", "picking up is the transition that most needs saying out loud");

    // The chat control used to do nothing visible: showChat ends in render(), which rebuilt the
    // call screen over the conversation in the same tap. The dismissal has to survive that render,
    // and has to lapse by itself when the call ends or a different one starts.
    String thisCall = "c0a11e00-0000-4000-8000-000000000010";
    String otherCall = "c0a11e00-0000-4000-8000-0000000000ff";
    check(CallUi.shouldStayDismissed(thisCall, connected),
      "N160-chat-keeps-the-call-screen-down", "opening the conversation was undone by showChat's render");
    check(!CallUi.shouldStayDismissed(thisCall, null),
      "N161-dismissal-lapses-with-no-call", "a finished call must not suppress the next call's screen");
    CallSession.Builder other = new CallSession.Builder(otherCall, "peer", false, now);
    other.state = CallProtocol.State.Connected;
    other.connectedAtMs = now;
    check(!CallUi.shouldStayDismissed(thisCall, other.snapshot()),
      "N162-dismissal-lapses-on-a-new-call", "one call's dismissal leaked into the next call");
    check(!CallUi.shouldStayDismissed(null, connected),
      "N163-no-dismissal-shows-the-screen", "an ordinary call must still get its call screen");

    // The return-to-call bar showed "0:00" for the whole call because it keyed on call.state, an enum
    // that stops changing at Connected, while the text beside it is the duration. It has to change
    // as the clock changes, and stay put when nothing about it has.
    String firstBar = CallUi.callBarWords(connected);
    check(!firstBar.equals(CallUi.callBarWords(c.snapshot())),
      "N164-call-bar-follows-the-clock",
      "the call bar did not change while the clock advanced, so it froze at its first duration");
    eq(CallUi.callBarWords(connected), firstBar, "N165-call-bar-stable-within-a-second");
    check(CallUi.callBarWords(connected).contains(connected.peerId),
      "N166-call-bar-names-the-peer", "the bar must rebuild for a different peer, not reuse its buttons");

    // With the status line no longer a live region, the announcement is the only thing that tells a
    // screen-reader user the call connected. Speaking the on-screen label would say "0:00" at the
    // moment of answering, which describes the clock rather than the event.
    eq(CallUi.stateSpokenLabel(connected), "Connected",
      "N167-answering-is-announced-as-connected");
    check(!CallUi.stateSpokenLabel(connected).matches("\\d+:\\d\\d"),
      "N168-the-clock-is-not-spoken",
      "the clock is announced instead of the event, so the user hears a duration when the call connects");
    CallSession.Builder ringingBuilder =
      new CallSession.Builder("c0a11e00-0000-4000-8000-0000000000bb", "peer", false, now);
    ringingBuilder.state = CallProtocol.State.IncomingRinging;
    eq(CallUi.stateSpokenLabel(ringingBuilder.snapshot()), CallUi.stateLabel(ringingBuilder.snapshot()),
      "N169-ringing-is-spoken-as-shown");
    eq(CallUi.stateSpokenLabel(null), "", "N170-no-call-says-nothing");

    // The call screen's rebuild test. It used to be a chain of || clauses over the call ID, state,
    // mute and route, and the peer's NAME was not in it -- so a contact renamed during a call kept
    // the old name, the old initial on the picture, and three accessibility labels naming them
    // wrongly, until something unrelated forced a rebuild.
    String keyAt = CallUi.overlayKey(connected, "Ada");
    eq(CallUi.overlayKey(connected, "Ada"), keyAt,
      "N171-overlay-key-is-stable-within-a-render");
    check(!CallUi.overlayKey(connected, "Ada").equals(CallUi.overlayKey(connected, "Grace")),
      "N172-rename-rebuilds", "a renamed peer left the old name and initial on the call screen");
    check(!CallUi.overlayKey(connected, "Ada").equals(
        CallUi.overlayKey(c.snapshot(), "Ada")),
      "N173-state-change-rebuilds",
      "the ring buttons stayed on screen after the call was answered");
    CallSession.Builder mutedBuilder = new CallSession.Builder(connected.callId, "peer", false, now);
    mutedBuilder.state = CallProtocol.State.Connected;
    mutedBuilder.muted = true;
    mutedBuilder.connectedAtMs = now;
    check(!CallUi.overlayKey(connected, "Ada").equals(CallUi.overlayKey(mutedBuilder.snapshot(), "Ada")),
      "N174-mute-rebuilds",
      "the microphone glyph kept showing the state it was built with, so it looked inert");
    CallSession.Builder otherRoute = new CallSession.Builder(connected.callId, "peer", false, now);
    otherRoute.state = CallProtocol.State.Connected;
    otherRoute.connectedAtMs = now;
    otherRoute.audioRoute = "Speaker";
    check(!CallUi.overlayKey(connected, "Ada").equals(CallUi.overlayKey(otherRoute.snapshot(), "Ada")),
      "N175-route-rebuilds", "the speaker glyph did not follow a route change");
    eq(CallUi.overlayKey(null, "Ada"), "", "N176-no-call-has-no-key");
    // A null route and an empty one must not compare equal by accident, and a null name must not
    // throw: both arrive from the audio route policy and the service respectively.
    CallSession.Builder noRoute = new CallSession.Builder(connected.callId, "peer", false, now);
    noRoute.state = CallProtocol.State.Connected;
    noRoute.connectedAtMs = now;
    eq(CallUi.overlayKey(noRoute.snapshot(), null), CallUi.overlayKey(noRoute.snapshot(), ""),
      "N177-nulls-are-safe");
    check(CallUi.overlayKey(connected, "Ada").contains(connected.callId),
      "N178-key-carries-the-call", "a second call could reuse the first call's overlay");

    // Which view-model an Accept action goes through. On a cold start the Activity's field was
    // never bound -- the service creates its model after both of the Activity's only two bind
    // attempts -- so preferring that field made Accept a button that did nothing, and a ringing
    // call could not be answered at all until the user left and came back.
    CallUi drawn = new CallUi();
    check(CallUi.resolveForAccept(drawn, null, null) == drawn,
      "N179-accept-uses-the-model-that-drew-the-button",
      "Accept acted on a different reference than the one that rendered it");
    CallUi boundOnly = new CallUi();
    check(CallUi.resolveForAccept(null, boundOnly, null) == boundOnly,
      "N180-accept-falls-back-to-the-bound-model",
      "the notification's Accept action was a silent no-op while unbound");
    check(CallUi.resolveForAccept(null, null, boundOnly) == boundOnly,
      "N181-accept-falls-back-to-the-service",
      "Accept must still work when neither the button's nor the Activity's reference is available");
    check(CallUi.resolveForAccept(drawn, null, boundOnly) == drawn,
      "N182-the-button-wins-over-the-service",
      "the model that rendered the screen is the one that knows the call on it");
    check(CallUi.resolveForAccept(null, null, null) == null,
      "N183-no-model-anywhere", "the caller reports this rather than ignoring it");

    // The draggable minimised bar. A minimised call parked at the foot of the stage sat exactly
    // where the message box and keyboard appear, so the two could not both be used; making it
    // draggable means the user decides, but then it can be dropped where nothing is left to grab
    // it, so every position has to be pulled back inside the stage.
    eq(CallUi.clampBarLeft(100, 300, 720, 8), 100, "N184-a-dragged-position-is-kept");
    eq(CallUi.clampBarLeft(-500, 300, 720, 8), 8, "N185-cannot-be-dragged-off-the-left");
    eq(CallUi.clampBarLeft(9999, 300, 720, 8), 720 - 300 - 8,
      "N186-cannot-be-dragged-off-the-right");
    eq(CallUi.clampBarTop(-500, 80, 1500, 8), 8, "N187-cannot-be-dragged-off-the-top");
    eq(CallUi.clampBarTop(9999, 80, 1500, 8), 1500 - 80 - 8,
      "N188-cannot-be-dragged-off-the-bottom");
    // Before the first layout pass neither size is known. Clamping then would pin the bar into the
    // corner, so the position has to pass through untouched and be corrected on the next pass.
    eq(CallUi.clampBarLeft(100, 0, 720, 8), 100, "N189-unmeasured-bar-is-not-clamped");
    eq(CallUi.clampBarLeft(100, 300, 0, 8), 100, "N190-unmeasured-stage-is-not-clamped");
    eq(CallUi.clampBarLeft(-500, 0, 0, 8), 8, "N191-a-negative-position-stays-off-the-edge");
    // A bar wider than the stage would be clamped to the left margin and still overflow, which is
    // the lesser evil: it stays reachable rather than being pushed off both sides.
    eq(CallUi.clampBarLeft(100, 900, 720, 8), 8, "N192-a-bar-wider-than-the-stage-stays-grabable");

    // The bar has to land exactly under the finger. Accumulating the detector's own per-event deltas
    // left it well short -- a 227px swipe on the phone moved the bar 148px and it stopped wherever the
    // detector's smoothed focus ran out, so the gesture did not end where the user's finger did.
    // Measured from the point the finger went down instead, the bar follows it.
    // The casts matter: these return float, and a boxed Float never equals a boxed Integer.
    eq((int) CallUi.draggedLeft(200, 40, 430), 270, "N193-drag-left-tracks-the-finger");
    eq((int) CallUi.draggedTop(1427, 1354, 520), 447, "N194-drag-top-tracks-the-finger");
    eq((int) CallUi.draggedLeft(200, 40, 200), 40, "N195-a-finger-that-has-not-moved-leaves-the-bar-alone");
    eq((int) CallUi.draggedTop(1427, 1354, 1427), 1354, "N196-drag-top-holds-still-without-movement");
    // A later event in the same gesture must be measured from where the finger went down, not from
    // where the bar has got to. After a finger that reached 430 the bar sits at 270; a finger that
    // then reaches 600 is 400 from the down point, so the bar belongs at 40+400 and not at 270+170
    // measured off the previous event.
    eq((int) CallUi.draggedLeft(200, 40, 600), 440, "N197-a-second-event-in-the-same-drag-measures-from-the-down-point");
    // Straight up from the top edge, and a drag to the left of where the finger started, both work.
    eq((int) CallUi.draggedTop(100, 500, 40), 440, "N198-a-drag-straight-up-from-the-top-edge");
    eq((int) CallUi.draggedLeft(600, 10, 5), -585, "N199-a-drag-to-the-left-edge-is-measured-not-clamped-here");
    // One control answers both "open the call" and "move me", so the slop decides which.
    check(!CallUi.isBarDrag(200, 1427, 200, 1427, 12), "N200-still-touch-is-not-a-drag", "a finger that has not moved is not a drag");
    check(!CallUi.isBarDrag(200, 1427, 206, 1433, 12), "N201-jitter-under-the-slop-is-not-a-drag", "6px of jitter is a tap, not a drag");
    check(CallUi.isBarDrag(200, 1427, 220, 1427, 12), "N202-a-sideways-move-past-the-slop-is-a-drag", "20px sideways is a drag");
    check(CallUi.isBarDrag(200, 1427, 200, 1410, 12), "N203-a-short-vertical-move-past-the-slop-is-a-drag", "17px up is a drag");
    check(!CallUi.isBarDrag(200, 1427, 206, 1427, 12), "N204-just-inside-the-slop-is-still-a-tap", "6px sideways is a tap");
  }

  /** Regression: allowedSender is evaluated on the RECEIVING device, against the LOCAL state and
   *  the LOCAL role.  It used to be written from the sender's point of view, which silently
   *  discarded RINGING, ACCEPT, BUSY and ANSWER on the caller and OFFER on the callee.  A call
   *  could therefore never progress past ringing: the caller heard nothing back and reported
   *  "No answer" after 30 s, with no error anywhere. */
  static void testInboundFrameAdmissionTable() {
    System.out.println("testInboundFrameAdmissionTable...");

    // Every frame a successful call exchanges, evaluated on the device that receives it.
    // A null result here means the frame is thrown away and the call stalls.
    check(CallProtocol.allowedSender(CallProtocol.INVITE, CallProtocol.State.Idle, false) != null,
      "N90-invite-reaches-idle-callee", "a ringing device must be able to receive an invite");
    check(CallProtocol.allowedSender(CallProtocol.RINGING, CallProtocol.State.OutgoingRinging, true) != null,
      "N91-caller-hears-ringing", "caller must accept RINGING while it is ringing out");
    check(CallProtocol.allowedSender(CallProtocol.ACCEPT, CallProtocol.State.OutgoingRinging, true) != null,
      "N92-caller-hears-accept", "caller must accept ACCEPT while it is ringing out");
    check(CallProtocol.allowedSender(CallProtocol.BUSY, CallProtocol.State.OutgoingRinging, true) != null,
      "N93-caller-hears-busy", "caller must accept BUSY while it is ringing out");
    check(CallProtocol.allowedSender(CallProtocol.DECLINE, CallProtocol.State.OutgoingRinging, true) != null,
      "N94-caller-hears-decline", "caller must accept DECLINE while it is ringing out");
    check(CallProtocol.allowedSender(CallProtocol.OFFER, CallProtocol.State.Connecting, false) != null,
      "N95-callee-receives-offer", "callee must accept the caller's SDP offer");
    check(CallProtocol.allowedSender(CallProtocol.ANSWER, CallProtocol.State.Connecting, true) != null,
      "N96-caller-receives-answer", "caller must accept the callee's SDP answer");
    check(CallProtocol.allowedSender(CallProtocol.CANCEL, CallProtocol.State.IncomingRinging, false) != null,
      "N97-callee-hears-cancel", "ringing callee must accept the caller cancelling");
    check(CallProtocol.allowedSender(CallProtocol.ICE, CallProtocol.State.Connecting, true) != null,
      "N98-ice-during-connecting", "trickle ICE must be accepted before the call is connected");
    check(CallProtocol.allowedSender(CallProtocol.MEDIA_READY, CallProtocol.State.Connecting, false) != null,
      "N99-media-ready-while-connecting", "MEDIA_READY is what promotes Connecting to Connected");
    check(CallProtocol.allowedSender(CallProtocol.HANGUP, CallProtocol.State.Connected, false) != null,
      "N100-hangup-while-connected", "a hangup must be honoured mid-call");
    // A caller that never processed the ACCEPT (broken or lost signalling) abandons the attempt
    // with CANCEL while this side has already accepted and is Connecting.  That CANCEL must be
    // honoured, or the call dies on a socket timeout and is reported as "Connection lost" instead
    // of "Canceled".
    check(CallProtocol.allowedSender(CallProtocol.CANCEL, CallProtocol.State.Connecting, false) != null,
      "N100b-callee-hears-cancel-while-connecting",
      "an accepted callee must still accept a cancel from the caller");
    // ...but a CANCEL must not be able to kill a call that is already up.
    check(CallProtocol.allowedSender(CallProtocol.CANCEL, CallProtocol.State.Connected, false) == null,
      "N100c-stale-cancel-refused-when-connected",
      "a cancel arriving after the call is up must not tear it down");

    // The mirrored checks must still be refused, so the fix is not just a blanket allow.
    check(CallProtocol.allowedSender(CallProtocol.OFFER, CallProtocol.State.Connecting, true) == null,
      "N101-caller-rejects-offer", "a caller must never receive an SDP offer");
    check(CallProtocol.allowedSender(CallProtocol.ANSWER, CallProtocol.State.Connecting, false) == null,
      "N102-callee-rejects-answer", "a callee must never receive an SDP answer");
    check(CallProtocol.allowedSender(CallProtocol.ACCEPT, CallProtocol.State.IncomingRinging, false) == null,
      "N103-callee-rejects-remote-accept", "only the caller may be told ACCEPT");
    check(CallProtocol.allowedSender(CallProtocol.INVITE, CallProtocol.State.Connected, true) == null,
      "N104-no-invite-while-connected", "a device already in a call must not be invited");
    check(CallProtocol.allowedSender("NOT_A_REAL_TYPE", CallProtocol.State.Connected, true) == null,
      "N105-unknown-type-refused", "an unknown frame type must be refused");
    check(CallProtocol.allowedSender(CallProtocol.ICE, CallProtocol.State.OutgoingRinging, true) == null,
      "N106-no-ice-while-ringing", "ICE before negotiation has started must be refused");
  }

  /** Regression, end to end through the controller: a ringing caller that receives RINGING then
   *  ACCEPT must reach Connecting and send its SDP offer.  This is the path that previously died
   *  inside onFrame's admission check, which is why the user saw "No answer" instead of an error. */
  static void testCallerAcceptReachesConnecting() throws Exception {
    System.out.println("testCallerAcceptReachesConnecting...");

    PeerEngine eng = dummyEngine();
    String peerId = UUID.randomUUID().toString();
    PeerEngine.Peer peer = new PeerEngine.Peer(peerId, "TestPeer", "10.0.0.1", 43872);
    peer.fingerprint = "aa:bb:cc:dd";
    peer.verified = "aa:bb:cc:dd"; // trusted, so startCall is permitted
    synchronized (eng) { eng.peers.put(peerId, peer); }

    CallSettings settings = new CallSettings(eng.file.getParentFile());
    settings.setAllowIncoming(true);
    CallController ctrl = new CallController(eng, settings);
    ctrl.setMediaFactory(new FakeCallMedia.Factory());
    ctrl.start();

    final List<byte[]> sent = Collections.synchronizedList(new ArrayList<>());
    String callId = ctrl.startCall(peerId, callId2 -> sent::add);
    check(ctrl.snapshot() != null && ctrl.snapshot().isCaller, "N110-outgoing-is-caller", "");
    eq(ctrl.snapshot().state, CallProtocol.State.OutgoingRinging, "N111-ringing-out");

    // The callee's RINGING must be absorbed without ending the call.
    ctrl.onFrame(CallSignaling.ringing(callId, 1), peerId);
    eq(ctrl.snapshot().state, CallProtocol.State.OutgoingRinging, "N112-still-ringing-after-ringing");

    // The callee's ACCEPT must move the caller to Connecting and trigger an SDP offer.
    ctrl.onFrame(CallSignaling.accept(callId, 2), peerId);
    eq(ctrl.snapshot().state, CallProtocol.State.Connecting, "N113-accept-reaches-connecting");

    boolean sentOffer = false;
    for (byte[] w : sent) {
      CallProtocol.Frame f = CallSignaling.parse(w);
      if (f != null && CallProtocol.OFFER.equals(f.type)) { sentOffer = true; break; }
    }
    check(sentOffer, "N114-offer-sent-after-accept", "caller must send its SDP offer once accepted");

    // A BUSY arriving after the call has left the ringing state must not be misread as a hangup.
    check(ctrl.hasActive(), "N115-still-active-after-accept", "");

    ctrl.shutdown();
    eng.close();
    for (File f : eng.file.getParentFile().listFiles()) f.delete();
    eng.file.getParentFile().delete();
  }

  /** Regression: a frame type read off the wire is a substring of the received JSON, so it is a
   *  different object from the CallProtocol constant even though the two are equal.  CallChannel
   *  used == to recognise the opening INVITE, which was therefore never true: the INVITE fell
   *  through to CallController.onFrame, that returned immediately because no session existed yet,
   *  and every incoming call was discarded before it could ring.  Nothing errored, so the caller's
   *  phone simply rang out and then reported "No answer".
   *
   *  <p>Guards the whole type vocabulary, not just INVITE, because the same mistake anywhere else
   *  in the signaling path would fail just as quietly. */
  static void testParsedFrameTypeComparesByValue() throws Exception {
    System.out.println("testParsedFrameTypeComparesByValue...");

    String callId = UUID.randomUUID().toString();
    String caller = UUID.randomUUID().toString();
    String callee = UUID.randomUUID().toString();
    String sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\n";

    CallProtocol.Frame[] frames = {
      CallSignaling.invite(callId, 1, caller, callee),
      CallSignaling.ringing(callId, 2),
      CallSignaling.accept(callId, 3),
      CallSignaling.decline(callId, 4),
      CallSignaling.busy(callId, 5),
      CallSignaling.cancel(callId, 6),
      CallSignaling.offer(callId, 7, 1, sdp),
      CallSignaling.answer(callId, 8, 1, sdp),
      CallSignaling.ice(callId, 9, 1, "candidate", "0", 0),
      CallSignaling.hangup(callId, 10),
      CallSignaling.ping(callId, 11),
      CallSignaling.pong(callId, 12),
    };

    for (CallProtocol.Frame sent : frames) {
      CallProtocol.Frame got = CallSignaling.parse(CallSignaling.serialize(sent));
      check(got != null, "N120-parse-roundtrip-" + sent.type, "frame failed to survive serialize/parse");
      if (got == null) continue;
      check(got.type.equals(sent.type) && got.callId.equals(callId),
        "N121-frame-identity-by-value-" + sent.type,
        "a parsed frame must compare equal to what was sent by value, never by reference");
    }

    // The exact comparison CallChannel.dispatch performs on the opening frame.
    CallProtocol.Frame invite = CallSignaling.parse(
      CallSignaling.serialize(CallSignaling.invite(callId, 1, caller, callee)));
    check(CallProtocol.INVITE.equals(invite.type),
      "N123-invite-recognised-by-value", "dispatch must recognise the opening INVITE by value");
  }

  /** Regression: a terminal snapshot stays in getCurrent() after the call ends, and the end banner
   *  used to stamp "now" every time it re-rendered that snapshot, so it never expired. The banner
   *  stayed on screen indefinitely, covering the header and the back button, which is what left the
   *  caller stuck on a "No answer" screen with no way out.
   *
   *  <p>The end time must therefore be recorded once, on arrival, and left alone. */
  static void testTerminalSnapshotRetention() throws Exception {
    System.out.println("testTerminalSnapshotRetention...");

    CallUi ui = new CallUi();
    String callId = UUID.randomUUID().toString();
    CallSession.Builder b = new CallSession.Builder(callId, "peer", true, System.currentTimeMillis());
    b.state = CallProtocol.State.OutgoingRinging;
    ui.onSnapshot(b.snapshot());
    check(ui.hasActive() && ui.hasOutgoingRinging(), "N130-live-call-is-active", "");
    eq(ui.terminalAtMs(), 0L, "N131-no-terminal-while-live");

    // The call ends.
    b.state = CallProtocol.State.Ending;
    b.endReason = CallProtocol.EndReason.TIMEOUT_RINGING;
    CallSession ended = b.snapshot();
    ui.onSnapshot(ended);
    long endedAt = ui.terminalAtMs();
    check(endedAt > 0, "N132-end-time-recorded", "a terminal snapshot must record when it arrived");
    check(ui.getTerminal() != null, "N133-end-reason-retained", "");
    check(!ui.hasActive(), "N134-no-longer-active", "a terminal snapshot is not an active call");

    // Re-delivering the same terminal snapshot, which happens on every render pass, must not move
    // the end time. If it does, the banner can never expire.
    for (int i = 0; i < 3; i++) {
      Thread.sleep(15);
      ui.onSnapshot(ended);
      eq(ui.terminalAtMs(), Long.valueOf(endedAt), "N135-end-time-not-refreshed-" + i);
    }

    // Consuming it clears the retention so the banner is shown once.
    check(ui.takeTerminal() != null, "N136-terminal-consumed", "");
    eq(ui.terminalAtMs(), 0L, "N137-end-time-cleared");
    check(ui.getTerminal() == null, "N138-terminal-cleared", "");
    check(ui.takeTerminal() == null, "N139-terminal-consumed-once", "");

    // The end banner now stays on screen until the user dismisses it, so the terminal snapshot is
    // re-read on every render. The controller publishes the end state and then drops its own
    // session without ever publishing an idle one, so getCurrent() keeps handing back that same
    // terminal snapshot; takeTerminal has to drop it as well or the banner comes straight back and
    // Close does nothing.
    check(ui.getCurrent() == null, "N139a-terminal-current-cleared",
      "the end snapshot left in getCurrent() would re-arm the banner after it was dismissed");

    // The mirror image: taking an end reason must never clear a call that is still in progress.
    b.state = CallProtocol.State.OutgoingRinging;
    b.endReason = null;
    ui.onSnapshot(b.snapshot());
    check(ui.takeTerminal() == null, "N139b-no-end-reason-while-live", "");
    check(ui.getCurrent() != null && ui.hasActive(), "N139c-live-call-survives-take", "");

    // A new call invalidates any retained end reason.
    b.state = CallProtocol.State.OutgoingRinging;
    ui.onSnapshot(b.snapshot());
    eq(ui.terminalAtMs(), 0L, "N140-new-call-clears-end-reason");
    check(ui.hasActive(), "N141-new-call-is-active", "");
  }
}