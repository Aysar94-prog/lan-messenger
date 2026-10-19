package net.lanmsg.chat.videofeasibility;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.*;
import org.json.*;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Disposable AP01 libwebrtc endpoint. All wait operations run on the Activity worker,
 * never the WebRTC callback thread. DTLS-SRTP is enabled; ICE uses LAN host candidates. */
final class HarnessMedia {
  private final Context context;
  private final BooleanSupplier visible;
  private final EglBase egl;
  private final JavaAudioDeviceModule adm;
  private final PeerConnectionFactory factory;
  private final Map<String, Node> nodes = new LinkedHashMap<>();
  private final AudioManager audioManager;
  private final int previousMode;

  HarnessMedia(Context context, BooleanSupplier visible) {
    this.context = context.getApplicationContext(); this.visible = visible;
    audioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
    previousMode = audioManager.getMode(); audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
    PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
      .setEnableInternalTracer(false).createInitializationOptions());
    egl = EglBase.create();
    adm = JavaAudioDeviceModule.builder(context).createAudioDeviceModule();
    PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
    options.disableEncryption = false;
    factory = PeerConnectionFactory.builder().setOptions(options).setAudioDeviceModule(adm)
      .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
      .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
      .createPeerConnectionFactory();
  }

  JSONObject init(String id, String codec, String profile, String mode, String source) throws Exception {
    if (!id.matches("[ab]")) throw new IllegalArgumentException("node must be a or b");
    if (!Arrays.asList("VP8", "VP9", "H264").contains(codec)) throw new IllegalArgumentException("codec");
    if (!Arrays.asList("audio", "inactive", "video", "video-only").contains(mode)) throw new IllegalArgumentException("mode");
    if (!Arrays.asList("generated", "camera").contains(source)) throw new IllegalArgumentException("source");
    if (nodes.containsKey(id)) throw new IllegalStateException("Stop before replacing an endpoint");
    Node n = new Node(id, codec, profile, source); nodes.put(id, n);
    if (!mode.equals("video-only")) {
      n.audioSource = factory.createAudioSource(new MediaConstraints());
      n.audioTrack = factory.createAudioTrack("audio-" + id, n.audioSource);
    }
    PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
    config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
    config.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED;
    n.pc = factory.createPeerConnection(config, n.observer);
    if (n.pc == null) throw new IllegalStateException("No peer connection");
    // addTrack-created transceivers can be reused by an incoming Unified-Plan offer.
    // Explicit addTransceiver here leaves the callee's sender on an unassociated
    // m-line and silently negotiates one-way audio.
    if (n.audioTrack != null) n.pc.addTrack(n.audioTrack, Collections.singletonList("audio-stream-" + id));
    for (RtpTransceiver t : n.pc.getTransceivers())
      if (t.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) select(t, "G722", "");
    if (mode.equals("inactive")) {
      n.videoTransceiver = n.pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
        new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.INACTIVE));
      select(n.videoTransceiver, codec, profile);
    } else if (mode.equals("video") || mode.equals("video-only")) video(id);
    JSONObject caps = new JSONObject();
    JSONArray codecs = new JSONArray();
    for (RtpCapabilities.CodecCapability c : factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs)
      codecs.put(new JSONObject().put("name", c.name).put("parameters", new JSONObject(c.parameters)));
    caps.put("videoCodecs", codecs).put("audioCodec", "G722").put("source", source).put("mode", mode);
    return caps;
  }

  private void select(RtpTransceiver transceiver, String codec, String profile) {
    List<RtpCapabilities.CodecCapability> selected = new ArrayList<>();
    for (RtpCapabilities.CodecCapability c : factory.getRtpSenderCapabilities(transceiver.getMediaType()).codecs) {
      if (!codec.equalsIgnoreCase(c.name)) continue;
      String key = codec.equals("H264") ? "profile-level-id" : "profile-id";
      if (!profile.isEmpty() && !profile.equalsIgnoreCase(c.parameters.get(key))) continue;
      selected.add(c);
    }
    if (selected.isEmpty()) throw new IllegalArgumentException("Unavailable codec/profile " + codec + "/" + profile);
    transceiver.setCodecPreferences(selected).throwError();
  }
  private Node node(String id) {
    Node n = nodes.get(id); if (n == null) throw new IllegalStateException("Initialize endpoint first"); return n;
  }
  void closeNode(String id) { Node n = nodes.remove(id); if (n != null) n.close(); }
  boolean videoOnly(String id) { Node n = nodes.get(id); return n != null && n.audioTrack == null; }
  void video(String id) throws Exception {
    if (!visible.getAsBoolean()) throw new IllegalStateException("Activity is backgrounded");
    Node n = node(id);
    if (n.videoTrack == null) {
      n.videoSource = factory.createVideoSource(false);
      n.videoTrack = factory.createVideoTrack("video-" + id, n.videoSource);
      if (n.videoTransceiver == null)
        for (RtpTransceiver t : n.pc.getTransceivers())
          if (t.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && !t.isStopped()) { n.videoTransceiver = t; break; }
      if (n.videoTransceiver == null) {
        // addTrack-created transceivers can be reused by an incoming offer;
        // addTransceiver(track) would leave an unassociated sender on a second m-line.
        n.pc.addTrack(n.videoTrack, Collections.singletonList("video-stream-" + id));
        for (RtpTransceiver t : n.pc.getTransceivers())
          if (t.getMediaType() == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
              && t.getSender().track() != null
              && n.videoTrack.id().equals(t.getSender().track().id())) { n.videoTransceiver = t; break; }
        if (n.videoTransceiver == null) throw new IllegalStateException("No video sender transceiver");
      } else if (!n.videoTransceiver.getSender().setTrack(n.videoTrack, false)) throw new IllegalStateException("setTrack failed");
      select(n.videoTransceiver, n.codec, n.profile);
    }
    if (!n.videoTransceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
      throw new IllegalStateException("Cannot activate transceiver");
    n.videoTrack.setEnabled(true);
    if (n.capturing) return;
    n.capturing = true;
    if (n.source.equals("camera")) {
      if (context.checkSelfPermission("android.permission.CAMERA") != PackageManager.PERMISSION_GRANTED)
        throw new IllegalStateException("Camera permission missing");
      Camera2Enumerator e = new Camera2Enumerator(context);
      String selected = null;
      for (String name : e.getDeviceNames()) { if (selected == null || e.isFrontFacing(name)) selected = name; }
      if (selected == null) throw new IllegalStateException("No camera");
      n.camera = e.createCapturer(selected, null);
      if (n.camera == null) throw new IllegalStateException("Cannot create capturer");
      n.texture = SurfaceTextureHelper.create("feasibility-camera-" + id, egl.getEglBaseContext());
      n.camera.initialize(n.texture, context, n.videoSource.getCapturerObserver());
      n.camera.startCapture(320, 240, 15);
    } else {
      n.framesThread = new HandlerThread("feasibility-generated-" + id); n.framesThread.start();
      n.frames = new Handler(n.framesThread.getLooper());
      n.videoSource.getCapturerObserver().onCapturerStarted(true);
      n.frames.post(n.generator);
    }
  }

  void stopVideo(String id) throws Exception {
    Node n = node(id); n.stopCapture(); if (n.videoTrack != null) n.videoTrack.setEnabled(false);
  }
  String offer(String id) throws Exception { return local(node(id), true); }
  String answer(String id, String sdp) throws Exception {
    Node n = node(id); setRemote(n, SessionDescription.Type.OFFER, sdp);
    // Match an offered transceiver before attaching local video: avoid an extra m-line.
    if (sdp.contains("m=video") && !sdp.contains("a=inactive")) video(id);
    return local(n, false);
  }
  void remoteAnswer(String id, String sdp) throws Exception { setRemote(node(id), SessionDescription.Type.ANSWER, sdp); }
  private void setRemote(Node n, SessionDescription.Type type, String sdp) throws Exception {
    if (sdp == null || !sdp.startsWith("v=0") || sdp.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 49_152)
      throw new IllegalArgumentException("Invalid/oversized SDP");
    if (n.audioTrack == null && sdp.contains("m=audio")) throw new IllegalArgumentException("Video-only endpoint rejects audio SDP");
    SdpWait wait = new SdpWait(); n.pc.setRemoteDescription(wait, new SessionDescription(type, sdp)); wait.await();
  }
  private String local(Node n, boolean offer) throws Exception {
    SdpWait create = new SdpWait();
    if (offer) n.pc.createOffer(create, new MediaConstraints()); else n.pc.createAnswer(create, new MediaConstraints());
    create.await();
    if (create.description == null) throw new IllegalStateException("No SDP");
    SdpWait set = new SdpWait(); n.pc.setLocalDescription(set, create.description); set.await();
    long deadline = SystemClock.elapsedRealtime() + 10_000;
    while (n.pc.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
      if (!visible.getAsBoolean() || SystemClock.elapsedRealtime() >= deadline)
        throw new IllegalStateException("ICE gathering interrupted/timed out");
      Thread.sleep(50);
    }
    // Complete SDP embeds host candidates; external Windows endpoint needs no adb-forward.
    return n.pc.getLocalDescription().description;
  }

  JSONObject stats(String id) throws Exception {
    Node n = node(id); CountDownLatch latch = new CountDownLatch(1);
    final RTCStatsReport[] value = new RTCStatsReport[1];
    n.pc.getStats(r -> { value[0] = r; latch.countDown(); });
    if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("stats timeout");
    JSONObject result = new JSONObject(); JSONArray rtp = new JSONArray();
    Map<String, RTCStats> all = value[0].getStatsMap();
    for (RTCStats stat : all.values()) {
      if (!stat.getType().equals("inbound-rtp") && !stat.getType().equals("outbound-rtp")) continue;
      JSONObject item = new JSONObject(); item.put("type", stat.getType());
      Map<String,Object> fields = stat.getMembers();
      for (String key : Arrays.asList("kind", "mediaType", "packetsSent", "packetsReceived", "packetsLost",
          "bytesSent", "bytesReceived", "framesEncoded", "framesDecoded", "framesSent", "framesReceived",
          "framesPerSecond", "jitter", "totalSamplesReceived", "concealedSamples"))
        if (fields.containsKey(key)) item.put(key, fields.get(key));
      RTCStats codec = all.get(String.valueOf(fields.get("codecId")));
      if (codec != null) {
        item.put("codec", codec.getMembers().get("mimeType"));
        item.put("codecParameters", codec.getMembers().get("sdpFmtpLine"));
      }
      rtp.put(item);
    }
    result.put("rtp", rtp).put("node", id).put("source", n.source)
      .put("connection", n.pc.connectionState().toString()).put("decodedSinkFrames", n.decoded.get())
      .put("decodedMotionChanges", n.motion.get()).put("generatedFrames", n.generated.get())
      .put("timestampMs", SystemClock.elapsedRealtime());
    return result;
  }

  JSONObject loopback(String codec, String profile, String mode) throws Exception {
    if (!nodes.isEmpty()) throw new IllegalStateException("Stop endpoints before loopback");
    if (!mode.equals("audio") && !mode.equals("inactive")) throw new IllegalArgumentException("loopback mode");
    JSONObject evidence = new JSONObject();
    try {
      init("a", codec, profile, mode, "generated"); init("b", codec, profile, "audio", "generated");
      evidence.put("codecCandidate", codec).put("profileCandidate", profile).put("setupMode", mode);
      remoteAnswer("a", answer("b", offer("a")));
      long connectDeadline = SystemClock.elapsedRealtime() + 15_000;
      while (node("a").pc.connectionState() != PeerConnection.PeerConnectionState.CONNECTED
          || node("b").pc.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) {
        if (SystemClock.elapsedRealtime() >= connectDeadline) break;
        waitVisible(100);
      }
      waitVisible(3_000);
      JSONObject a0 = stats("a"), b0 = stats("b");
      evidence.put("beforeA", a0).put("beforeB", b0);
      requireAudio(a0, null); requireAudio(b0, null);
      video("a"); remoteAnswer("a", answer("b", offer("a")));
      waitVisible(5_000);
      JSONObject a1 = stats("a"), b1 = stats("b");
      evidence.put("afterA", a1).put("afterB", b1);
      requireAudio(a1, a0); requireAudio(b1, b0); requireVideo(a1); requireVideo(b1);
      stopVideo("a"); stopVideo("b"); waitVisible(2_000);
      JSONObject a2 = stats("a"), b2 = stats("b");
      evidence.put("cameraOffA", a2).put("cameraOffB", b2);
      requireAudio(a2, a1); requireAudio(b2, b1);
      evidence.put("passed", true).put("scope", "Android-local generated-frame loopback; not Windows interop or audible acceptance");
      return evidence;
    } catch (Exception failure) {
      evidence.put("passed", false).put("failure", failure.getClass().getSimpleName() + ": " + failure.getMessage());
      for (String id : nodes.keySet()) {
        try { evidence.put("failure" + id, stats(id)); } catch (Exception ignored) {}
      }
      return evidence;
    } finally { closeNodes(); }
  }
  private void waitVisible(long ms) throws Exception {
    long end = SystemClock.elapsedRealtime() + ms;
    while (SystemClock.elapsedRealtime() < end) {
      if (!visible.getAsBoolean()) throw new IllegalStateException("Activity backgrounded"); Thread.sleep(50);
    }
  }
  private static long counter(JSONObject sample, String kind, String key) throws JSONException {
    JSONArray rtp = sample.getJSONArray("rtp"); long sum = 0;
    for (int i=0;i<rtp.length();i++) {
      JSONObject item = rtp.getJSONObject(i);
      if (kind.equals(item.optString("kind", item.optString("mediaType")))) sum += item.optLong(key);
    }
    return sum;
  }
  private static void requireAudio(JSONObject current, JSONObject before) throws Exception {
    for (String key : Arrays.asList("packetsSent", "packetsReceived"))
      if (counter(current, "audio", key) <= (before == null ? 0 : counter(before, "audio", key)))
        throw new IllegalStateException("Audio did not progress: " + key);
  }
  private static void requireVideo(JSONObject current) throws Exception {
    for (String key : Arrays.asList("packetsSent", "packetsReceived", "framesEncoded", "framesDecoded"))
      if (counter(current, "video", key) <= 0) throw new IllegalStateException("No real video " + key);
    if (current.getLong("decodedSinkFrames") < 10 || current.getLong("decodedMotionChanges") < 5)
      throw new IllegalStateException("No moving decoded frames");
  }
  private void closeNodes() {
    for (Node n : nodes.values()) n.close(); nodes.clear();
  }
  void close() {
    closeNodes(); factory.dispose(); adm.release(); egl.release();
    audioManager.setMode(previousMode);
  }

  private static final class SdpWait implements SdpObserver {
    final CountDownLatch latch = new CountDownLatch(1); SessionDescription description; String error;
    public void onCreateSuccess(SessionDescription s) { description = s; latch.countDown(); }
    public void onCreateFailure(String s) { error = s; latch.countDown(); }
    public void onSetSuccess() { latch.countDown(); }
    public void onSetFailure(String s) { error = s; latch.countDown(); }
    void await() throws Exception {
      if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("SDP callback timeout");
      if (error != null) throw new IllegalStateException(error);
    }
  }

  private final class Node {
    final String id, codec, profile, source;
    PeerConnection pc; AudioSource audioSource; AudioTrack audioTrack;
    VideoSource videoSource; VideoTrack videoTrack; RtpTransceiver videoTransceiver;
    CameraVideoCapturer camera; SurfaceTextureHelper texture;
    HandlerThread framesThread; Handler frames; volatile boolean capturing;
    final AtomicLong decoded = new AtomicLong(), motion = new AtomicLong(), generated = new AtomicLong();
    final Map<VideoTrack, VideoSink> sinks = new ConcurrentHashMap<>();
    volatile int lastY = -1;
    Node(String id, String codec, String profile, String source) {
      this.id=id; this.codec=codec; this.profile=profile; this.source=source;
    }
    final Runnable generator = new Runnable() {
      public void run() {
        if (!capturing || !visible.getAsBoolean()) return;
        JavaI420Buffer buffer = JavaI420Buffer.allocate(320, 240);
        long f = generated.incrementAndGet();
        // Moving luminance bands and colour changes survive compression and prove motion.
        ByteBuffer y=buffer.getDataY(), u=buffer.getDataU(), v=buffer.getDataV();
        for (int row=0;row<240;row++) for(int col=0;col<320;col++)
          y.put(row*buffer.getStrideY()+col, (byte)(32 + ((col + row + f*7) % 192)));
        for (int row=0;row<120;row++) for(int col=0;col<160;col++) {
          u.put(row*buffer.getStrideU()+col, (byte)(80 + f%80));
          v.put(row*buffer.getStrideV()+col, (byte)(160 - f%80));
        }
        VideoFrame frame = new VideoFrame(buffer, 0, System.nanoTime());
        try { videoSource.getCapturerObserver().onFrameCaptured(frame); } finally { frame.release(); }
        if (capturing) frames.postDelayed(this, 67);
      }
    };
    final PeerConnection.Observer observer = new PeerConnection.Observer() {
      public void onSignalingChange(PeerConnection.SignalingState s) {}
      public void onIceConnectionChange(PeerConnection.IceConnectionState s) {}
      public void onIceConnectionReceivingChange(boolean b) {}
      public void onIceGatheringChange(PeerConnection.IceGatheringState s) {}
      public void onIceCandidate(IceCandidate c) {}
      public void onIceCandidatesRemoved(IceCandidate[] c) {}
      public void onAddStream(MediaStream s) {}
      public void onRemoveStream(MediaStream s) {}
      public void onDataChannel(DataChannel c) { c.dispose(); }
      public void onRenegotiationNeeded() {}
      public void onTrack(RtpTransceiver t) {
        MediaStreamTrack track = t.getReceiver().track();
        if (!(track instanceof VideoTrack)) return;
        VideoTrack video = (VideoTrack)track;
        VideoSink sink = frame -> {
          VideoFrame.I420Buffer buffer = frame.getBuffer().toI420();
          try {
            int value = buffer.getDataY().get(0) & 255;
            if (lastY != -1 && value != lastY) motion.incrementAndGet();
            lastY = value; decoded.incrementAndGet();
          } finally { buffer.release(); }
        };
        if (sinks.putIfAbsent(video, sink) == null) video.addSink(sink);
      }
    };
    void stopCapture() throws Exception {
      capturing = false;
      if (camera != null) { camera.stopCapture(); camera.dispose(); camera = null; }
      if (texture != null) { texture.dispose(); texture = null; }
      if (frames != null) {
        frames.removeCallbacksAndMessages(null);
        CountDownLatch drained = new CountDownLatch(1); frames.post(drained::countDown);
        if (!drained.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Frame thread did not drain");
        framesThread.quitSafely(); framesThread.join(5_000); frames = null; framesThread = null;
      }
      if (videoSource != null) videoSource.getCapturerObserver().onCapturerStopped();
    }
    void close() {
      try { stopCapture(); } catch (Exception e) { android.util.Log.e("VideoFeasibility", "Capture cleanup", e); }
      for (Map.Entry<VideoTrack, VideoSink> sink : sinks.entrySet()) sink.getKey().removeSink(sink.getValue());
      sinks.clear();
      if (pc != null) { pc.close(); pc.dispose(); pc = null; }
      if (videoTrack != null) videoTrack.dispose(); if (videoSource != null) videoSource.dispose();
      if (audioTrack != null) audioTrack.dispose(); if (audioSource != null) audioSource.dispose();
    }
  }
}
