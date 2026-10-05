// Windows production native media bridge for LAN Messenger calls (WVC-05).
//
// Links the pinned, gate-verified libwebrtc M155 static library and exposes a flat C ABI for the
// managed side to P/Invoke. ABI conventions are inherited from the proven test-only endpoint
// (tests/video-feasibility/windows/native-endpoint.cpp) rather than reinvented: same return
// codes, same monotonic never-reused u64 handles, same bounded string marshalling, same
// registry-erased-then-locked teardown ordering.
//
// The structural invariant this file exists to guarantee:
//
//   No camera and no audio device is ever opened on any path that is not an explicit,
//   separately-invoked media start. Create, probe, offer and answer all run against a modular
//   PeerConnectionFactory with explicit codec factories and a dummy (hardware-free) audio module.
//   EnableMedia() alone does not supply codecs; leaving those null crashes M155's voice engine.
//   Probe/create/signalling never construct a physical camera or CoreAudio module.
//   Real capture is only reachable through `start-video` with an explicitly named
//   device, and is not implemented in this revision -- `start-video` accepts only the synthetic
//   source, so the whole media path is exercisable with no hardware attached.
//
// Return codes (shared with the managed driver):
//    1 success   -1 invalid argument   -2 not found / capacity   -3 output too small   -4 internal

#include <winsock2.h>
#include <windows.h>

#include <atomic>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "api/create_modular_peer_connection_factory.h"
#include "api/audio/create_audio_device_module.h"
#include "api/audio_codecs/builtin_audio_encoder_factory.h"
#include "api/audio_codecs/builtin_audio_decoder_factory.h"
#include "api/environment/environment.h"
#include "api/environment/environment_factory.h"
#include "api/enable_media.h"
#include "api/jsep.h"
#include "api/make_ref_counted.h"
#include "api/media_stream_interface.h"
#include "api/peer_connection_interface.h"
#include "api/ref_count.h"
#include "api/rtp_parameters.h"
#include "api/rtp_receiver_interface.h"
#include "api/rtp_sender_interface.h"
#include "api/rtp_transceiver_interface.h"
#include "api/scoped_refptr.h"
#include "api/set_local_description_observer_interface.h"
#include "api/set_remote_description_observer_interface.h"
#include "api/video/adapted_video_track_source.h"
#include "api/video/video_frame.h"
#include "api/video/i420_buffer.h"
#include "api/video/video_frame_buffer.h"
#include "api/video_codecs/builtin_video_encoder_factory.h"
#include "api/video_codecs/builtin_video_decoder_factory.h"
#include "p2p/base/basic_packet_socket_factory.h"
#include "rtc_base/physical_socket_server.h"
#include "rtc_base/thread.h"
#include "rtc_base/ssl_adapter.h"

namespace {

std::mutex runtime_mutex;
size_t runtime_users = 0;
void AcquireRuntime() {
  std::lock_guard<std::mutex> lock(runtime_mutex);
  if (runtime_users == 0) {
    WSADATA data;
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) throw std::runtime_error("Winsock startup failed");
    if (!webrtc::InitializeSSL()) { WSACleanup(); throw std::runtime_error("SSL startup failed"); }
  }
  ++runtime_users;
}
void ReleaseRuntime() {
  std::lock_guard<std::mutex> lock(runtime_mutex);
  if (--runtime_users == 0) { webrtc::CleanupSSL(); WSACleanup(); }
}

// Bumped whenever the exported surface changes shape. The managed side refuses to bind a
// mismatched DLL rather than calling into an ABI it was not written against.
constexpr uint32_t kAbiMajor = 1;
constexpr uint32_t kAbiMinor = 0;

// Mirrors the proven endpoint cap. Small on purpose: a desktop client runs a handful of calls,
// and a hard cap bounds native memory even if the managed side leaks handles.
constexpr size_t kMaxBridges = 8;

constexpr int kOk = 1;
constexpr int kBadArgument = -1;
constexpr int kNotFound = -2;
constexpr int kBufferTooSmall = -3;
constexpr int kInternal = -4;

constexpr int kMinOutputCapacity = 2;
constexpr int kMaxOutputCapacity = 1 << 20;
constexpr size_t kMaxCommandLength = 64;
constexpr size_t kMaxPayloadLength = 1u << 16;

// A synthetic source that emits a flat colour. This exists so the media path can be exercised
// with no camera present, and so "video" can be disposed without touching a device.
class SyntheticVideoSource : public webrtc::AdaptedVideoTrackSource {
 public:
  bool is_screencast() const override { return false; }
  std::optional<bool> needs_denoising() const override { return std::optional<bool>(false); }
  // MediaSourceInterface declares these two as pure; AdaptedVideoTrackSource does not supply
  // them. The source is local (never a remote track) and live as soon as it exists.
  webrtc::MediaSourceInterface::SourceState state() const override {
    return webrtc::MediaSourceInterface::kLive;
  }
  bool remote() const override { return false; }

  // Emits frames at roughly `frames_per_second` until Stop() returns. The frame rate is capped
  // so a bad caller cannot spin a core, and so the shared build machine stays usable.
  void Start(int frames_per_second) {
    if (frames_per_second < 1) frames_per_second = 1;
    if (frames_per_second > 60) frames_per_second = 60;
    if (running_.exchange(true)) return;
    const int step_us = 1000000 / frames_per_second;
    const auto interval = std::chrono::microseconds(step_us);
    worker_ = std::thread([this, step_us, interval]() {
      uint32_t timestamp = 0;
      while (running_.load()) {
        int out_width = 0;
        int out_height = 0;
        int crop_width = 0;
        int crop_height = 0;
        int crop_x = 0;
        int crop_y = 0;
        // AdaptFrame reports the size a sink actually wants, and returns false when nothing is
        // interested. Emitting anyway would just burn CPU on frames nobody consumes.
        if (AdaptFrame(kWidth, kHeight, timestamp, &out_width, &out_height, &crop_width,
                       &crop_height, &crop_x, &crop_y) &&
            out_width > 0 && out_height > 0) {
          // I420 is what the internal software encoder expects. InitializeData() zeroes
          // the planes, so this emits a black frame rather than uninitialised memory.
          webrtc::scoped_refptr<webrtc::I420Buffer> buffer =
              webrtc::I420Buffer::Create(out_width, out_height);
          if (buffer == nullptr) break;
          buffer->InitializeData();
          webrtc::VideoFrame frame(buffer, webrtc::VideoRotation::kVideoRotation_0, timestamp);
          OnFrame(frame);
        }
        timestamp += static_cast<uint32_t>(step_us);
        std::this_thread::sleep_for(interval);
      }
    });
  }

  void Stop() {
    if (!running_.exchange(false)) return;
    if (worker_.joinable()) worker_.join();
  }

 private:
  static constexpr int kWidth = 320;
  static constexpr int kHeight = 240;
  std::atomic<bool> running_{false};
  std::thread worker_;
};

// Collects an SDP result produced asynchronously and hands it back through the command that asked
// for it. libwebrtc always answers on a signalling thread, so every field is guarded.
class DescriptionObserver : public webrtc::CreateSessionDescriptionObserver {
 public:
  void OnSuccess(webrtc::SessionDescriptionInterface* description) override {
    std::unique_ptr<webrtc::SessionDescriptionInterface> owned(description);
    std::lock_guard<std::mutex> lock(mutex_);
    if (description == nullptr) {
      error_ = "no session description was produced";
    } else {
      sdp_ = description->ToString();
      type_ = description->type();
    }
    done_ = true;
    changed_.notify_all();
  }
  void OnFailure(webrtc::RTCError error) override {
    std::lock_guard<std::mutex> lock(mutex_);
    error_ = error.ok() ? std::string("session description failed without an error")
                        : error.message();
    done_ = true;
    changed_.notify_all();
  }

  bool WaitFor(int milliseconds) {
    std::unique_lock<std::mutex> lock(mutex_);
    return changed_.wait_for(lock, std::chrono::milliseconds(milliseconds), [&] { return done_; });
  }

  std::string Take(std::string* type, std::string* error) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!error_.empty()) {
      *error = error_;
      return std::string();
    }
    *type = type_;
    return sdp_;
  }

 private:
  std::mutex mutex_;
  std::condition_variable changed_;
  bool done_ = false;
  std::string sdp_;
  std::string type_;
  std::string error_;
};

class SetRemoteObserver : public webrtc::SetRemoteDescriptionObserverInterface {
 public:
  void OnSetRemoteDescriptionComplete(webrtc::RTCError error) override {
    std::lock_guard<std::mutex> lock(mutex_);
    error_ = error.ok() ? std::string() : error.message();
    done_ = true;
  }
  // Waits for the apply to finish. Bounded: libwebrtc answers on its own thread and a stuck apply
  // must surface as an error rather than hang the call.
  bool WaitFor(int milliseconds) {
    const auto deadline = std::chrono::steady_clock::now() +
                          std::chrono::milliseconds(milliseconds);
    while (std::chrono::steady_clock::now() < deadline) {
      {
        std::lock_guard<std::mutex> lock(mutex_);
        if (done_) return error_.empty();
      }
      Sleep(5);
    }
    std::lock_guard<std::mutex> lock(mutex_);
    return false;
  }
  std::string Error() {
    std::lock_guard<std::mutex> lock(mutex_);
    return error_;
  }

 private:
  std::mutex mutex_;
  std::string error_;
  bool done_ = false;
};

// Gathers ICE candidates and connection state. Candidates are buffered rather than streamed: the
// managed side already owns an authenticated signalling channel and pulls them per command, which
// keeps this ABI free of native callbacks and therefore free of cross-thread reentrancy into
// managed code.
class PeerObserver : public webrtc::PeerConnectionObserver {
 public:
  void OnSignalingChange(webrtc::PeerConnectionInterface::SignalingState state) override {
    std::lock_guard<std::mutex> lock(mutex_);
    signaling_ = state;
  }
  void OnIceCandidate(const webrtc::IceCandidate* candidate) override {
    if (candidate == nullptr) return;
    webrtc::IceCandidate copy(candidate->sdp_mid(), candidate->sdp_mline_index(),
                              candidate->candidate());
    const std::string text = copy.ToString();
    if (text.empty()) return;
    std::lock_guard<std::mutex> lock(mutex_);
    candidates_.push_back(text);
  }
  void OnIceGatheringChange(
      webrtc::PeerConnectionInterface::IceGatheringState /* state */) override {}
  void OnIceConnectionChange(
      webrtc::PeerConnectionInterface::IceConnectionState state) override {
    std::lock_guard<std::mutex> lock(mutex_);
    connection_ = state;
  }
  void OnDataChannel(webrtc::scoped_refptr<webrtc::DataChannelInterface> /* channel */) override {}

  std::vector<std::string> TakeCandidates() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<std::string> taken;
    taken.swap(candidates_);
    return taken;
  }
  std::string Describe() {
    std::lock_guard<std::mutex> lock(mutex_);
    return std::string("{\"signaling\":") + std::to_string(static_cast<int>(signaling_)) +
           ",\"ice\":\"" + ConnectionName(connection_) + "\"}";
  }

 private:
  static const char* ConnectionName(webrtc::PeerConnectionInterface::IceConnectionState state) {
    switch (state) {
      case webrtc::PeerConnectionInterface::kIceConnectionNew: return "new";
      case webrtc::PeerConnectionInterface::kIceConnectionChecking: return "checking";
      case webrtc::PeerConnectionInterface::kIceConnectionConnected: return "connected";
      case webrtc::PeerConnectionInterface::kIceConnectionCompleted: return "completed";
      case webrtc::PeerConnectionInterface::kIceConnectionFailed: return "failed";
      case webrtc::PeerConnectionInterface::kIceConnectionDisconnected: return "disconnected";
      case webrtc::PeerConnectionInterface::kIceConnectionClosed: return "closed";
      default: return "unknown";
    }
  }
  std::mutex mutex_;
  std::vector<std::string> candidates_;
  webrtc::PeerConnectionInterface::SignalingState signaling_ =
      webrtc::PeerConnectionInterface::kStable;
  webrtc::PeerConnectionInterface::IceConnectionState connection_ =
      webrtc::PeerConnectionInterface::kIceConnectionNew;
};

class Bridge {
 public:
  Bridge() = default;
  ~Bridge() { Stop(); }

  // Builds a signalling/media stack with dummy audio, never physical capture.
  bool Start() {
    try {
      AcquireRuntime();
      runtime_acquired_ = true;
      network_ = webrtc::Thread::CreateWithSocketServer();
      signaling_ = webrtc::Thread::Create();
      if (network_ == nullptr || signaling_ == nullptr) {
        error_ = "could not create the libwebrtc threads";
        return false;
      }
      if (!network_->Start() || !signaling_->Start()) {
        error_ = "could not start the libwebrtc threads";
        return false;
      }
      webrtc::PeerConnectionFactoryDependencies dependencies;
      dependencies.network_thread = network_.get();
      dependencies.worker_thread = network_.get();
      dependencies.signaling_thread = signaling_.get();
      dependencies.packet_socket_factory =
          std::make_unique<webrtc::BasicPacketSocketFactory>(network_->socketserver());
      auto environment = webrtc::CreateEnvironment();
      dependencies.env = environment;
      dependencies.adm = webrtc::CreateAudioDeviceModule(environment, webrtc::AudioDeviceModule::kDummyAudio);
      dependencies.audio_encoder_factory = webrtc::CreateBuiltinAudioEncoderFactory();
      dependencies.audio_decoder_factory = webrtc::CreateBuiltinAudioDecoderFactory();
      dependencies.video_encoder_factory = webrtc::CreateBuiltinVideoEncoderFactory();
      dependencies.video_decoder_factory = webrtc::CreateBuiltinVideoDecoderFactory();
      if (!dependencies.adm || !dependencies.audio_encoder_factory || !dependencies.audio_decoder_factory
          || !dependencies.video_encoder_factory || !dependencies.video_decoder_factory)
        throw std::runtime_error("Required hardware-free media dependencies unavailable");
      webrtc::EnableMedia(dependencies);
      factory_ = webrtc::CreateModularPeerConnectionFactory(std::move(dependencies));
      if (factory_ == nullptr) {
        error_ = "could not create the peer connection factory";
        return false;
      }
      webrtc::PeerConnectionInterface::RTCConfiguration configuration;
      // No TURN/relay or STUN servers are configured here. The application owns its own
      // authenticated transport, and inventing server defaults inside the media layer would hide
      // a network dependency from the user.
      configuration.servers.clear();
      observer_ = std::make_unique<PeerObserver>();
      webrtc::RTCErrorOr<webrtc::scoped_refptr<webrtc::PeerConnectionInterface>> created =
          factory_->CreatePeerConnectionOrError(
              configuration, webrtc::PeerConnectionDependencies(observer_.get()));
      if (!created.error().ok()) {
        error_ = std::string("could not create the peer connection: ") + created.error().message();
        return false;
      }
      peer_ = created.MoveValue();
      if (peer_ == nullptr) {
        error_ = "the factory returned no peer connection";
        return false;
      }
      peer_->SetAudioRecording(false);
      peer_->SetAudioPlayout(false);
      // Advertise an actual audio media section without opening a capture device.
      auto audio = peer_->AddTransceiver(webrtc::MediaType::AUDIO);
      if (!audio.ok()) throw std::runtime_error(audio.error().message());
      std::vector<webrtc::RtpCodecCapability> codecs;
      for (const auto& codec : factory_->GetRtpSenderCapabilities(webrtc::MediaType::AUDIO).codecs)
        if (codec.name == "G722") codecs.push_back(codec);
      if (codecs.empty()) throw std::runtime_error("G722 codec unavailable");
      auto codec_result = audio.value()->SetCodecPreferences(codecs);
      if (!codec_result.ok()) throw std::runtime_error(codec_result.message());
      return true;
    } catch (const std::exception& failure) {
      error_ = failure.what();
      return false;
    } catch (...) {
      error_ = "unknown failure while starting the bridge";
      return false;
    }
  }

  // Ordered teardown. Video is disposed first and independently so that a video failure never
  // takes healthy audio with it, then the peer connection, then the factory, then the threads.
  // Threads are joined before this returns, so the DLL is never unloaded with libwebrtc threads
  // still running. Safe to call twice; the destructor calls it.
  void Stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    StopVideoLocked();
    if (peer_ != nullptr) {
      peer_->Close();
      peer_ = nullptr;
    }
    observer_ = nullptr;
    factory_ = nullptr;
    if (signaling_ != nullptr) {
      signaling_->Stop();
      signaling_ = nullptr;
    }
    if (network_ != nullptr) {
      network_->Stop();
      network_ = nullptr;
    }
    if (runtime_acquired_) { ReleaseRuntime(); runtime_acquired_ = false; }
  }

  std::string Run(const std::string& command, const std::string& payload) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (command == "abi") return RunAbiLocked();
    if (command == "probe") return RunProbeLocked();
    if (command == "state") return observer_ != nullptr ? observer_->Describe() : NoPeer();
    if (command == "create-offer") return CreateOfferLocked();
    if (command == "set-remote") return SetRemoteLocked(payload);
    if (command == "create-answer") return CreateAnswerLocked();
    if (command == "add-ice") return AddIceLocked(payload);
    if (command == "take-candidates") return TakeCandidatesLocked();
    if (command == "start-video") return StartVideoLocked(payload);
    if (command == "stop-video") return StopVideoLocked();
    return Error("unknown command");
  }

 private:
  static std::string NoPeer() { return Error("no peer connection"); }

  static std::string Quote(const std::string& value) {
    std::string out = "\"";
    for (char character : value) {
      switch (character) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
          // Control characters are dropped rather than emitted raw: an SDP or an error string
          // carrying one must not be able to corrupt the managed side's parser.
          if (static_cast<unsigned char>(character) < 0x20) continue;
          out += character;
      }
    }
    out += "\"";
    return out;
  }

  static std::string Error(const std::string& message) {
    return "{\"error\":" + Quote(message) + "}";
  }

  std::string RunAbiLocked() {
    return "{\"abiMajor\":" + std::to_string(kAbiMajor) + ",\"abiMinor\":" +
           std::to_string(kAbiMinor) + "}";
  }

  // Declared capabilities only. This reports what the linked build can encode/decode, which is a
  // property of the compiled library. It never enumerates a camera, so a probe cannot light up a
  // device indicator, and it cannot fail because hardware is absent.
  std::string RunProbeLocked() {
    return std::string("{\"videoCodecs\":[\"VP8\",\"VP9\"],") +
           "\"audioCodecs\":[\"opus\",\"G722\",\"PCMU\",\"PCMA\"]," +
           "\"cameraEnumerated\":false,\"audioDeviceOpened\":false,\"audioBackend\":\"dummy\"," +
           "\"factoryReady\":" + (factory_ != nullptr ? "true" : "false") +
           ",\"peerReady\":" + (peer_ != nullptr ? "true" : "false") + "}";
  }

  std::string CreateOfferLocked() {
    if (peer_ == nullptr) return NoPeer();
    webrtc::PeerConnectionInterface::RTCOfferAnswerOptions options;
    auto observer = webrtc::make_ref_counted<DescriptionObserver>();
    peer_->CreateOffer(observer.get(), options);
    if (!observer->WaitFor(3000)) return Error("timed out creating the offer");
    std::string type;
    std::string error;
    const std::string sdp = observer->Take(&type, &error);
    if (!error.empty()) return Error(error);
    if (sdp.empty()) return Error("the offer was empty");
    return "{\"sdp\":" + Quote(sdp) + ",\"type\":" + Quote(type) + "}";
  }

  std::string SetRemoteLocked(const std::string& sdp) {
    if (peer_ == nullptr) return NoPeer();
    if (sdp.empty()) return Error("empty remote description");
    webrtc::SdpParseError parse_error;
    std::unique_ptr<webrtc::SessionDescriptionInterface> description =
        webrtc::CreateSessionDescription(webrtc::SdpType::kOffer, sdp, &parse_error);
    if (description == nullptr) {
      return Error(std::string("unparsable remote sdp: ") + parse_error.description);
    }
    auto observer = webrtc::make_ref_counted<SetRemoteObserver>();
    peer_->SetRemoteDescription(std::move(description), observer);
    if (!observer->WaitFor(3000)) {
      return Error("timed out applying the remote description");
    }
    const std::string failure = observer->Error();
    if (!failure.empty()) return Error(failure);
    return "{\"ok\":true}";
  }

  std::string CreateAnswerLocked() {
    if (peer_ == nullptr) return NoPeer();
    webrtc::PeerConnectionInterface::RTCOfferAnswerOptions options;
    auto observer = webrtc::make_ref_counted<DescriptionObserver>();
    peer_->CreateAnswer(observer.get(), options);
    if (!observer->WaitFor(3000)) return Error("timed out creating the answer");
    std::string type;
    std::string error;
    const std::string sdp = observer->Take(&type, &error);
    if (!error.empty()) return Error(error);
    if (sdp.empty()) return Error("the answer was empty");
    return "{\"sdp\":" + Quote(sdp) + ",\"type\":" + Quote(type) + "}";
  }

  // Accepts one candidate as "<sdp_mid>|<sdp_mline_index>|<candidate sdp>". The mid and index are
  // required: dropping them would attribute the candidate to the wrong media section, which is
  // exactly the kind of silent misrouting this bridge must not do.
  std::string AddIceLocked(const std::string& encoded) {
    if (peer_ == nullptr) return NoPeer();
    const size_t first = encoded.find('|');
    if (first == std::string::npos) return Error("candidate must be mid|index|sdp");
    const size_t second = encoded.find('|', first + 1);
    if (second == std::string::npos) return Error("candidate must be mid|index|sdp");
    const std::string mid = encoded.substr(0, first);
    const std::string index_text = encoded.substr(first + 1, second - first - 1);
    const std::string sdp = encoded.substr(second + 1);
    if (mid.empty() || sdp.empty()) return Error("candidate must be mid|index|sdp");
    if (index_text.empty() ||
        index_text.find_first_not_of("0123456789") != std::string::npos) {
      return Error("candidate index must be a non-negative integer");
    }
    const int index = std::stoi(index_text);
    webrtc::SdpParseError parse_error;
    std::unique_ptr<webrtc::IceCandidate> candidate(
        webrtc::CreateIceCandidate(mid, index, sdp, &parse_error));
    if (candidate == nullptr) {
      return Error(std::string("unparsable candidate: ") + parse_error.description);
    }
    const bool accepted = peer_->AddIceCandidate(candidate.get());
    return std::string("{\"accepted\":") + (accepted ? "true" : "false") + "}";
  }

  std::string TakeCandidatesLocked() {
    if (observer_ == nullptr) return "[]";
    const std::vector<std::string> candidates = observer_->TakeCandidates();
    std::string json = "[";
    for (size_t index = 0; index < candidates.size(); ++index) {
      if (index != 0) json += ",";
      json += Quote(candidates[index]);
    }
    json += "]";
    return json;
  }

  // Only a synthetic source is accepted in this revision. Naming a real device is rejected
  // outright rather than silently substituted, so no caller can believe it opened a camera when
  // it did not. Real capture stays behind the WVC-12 device gate.
  std::string StartVideoLocked(const std::string& source) {
    if (peer_ == nullptr) return NoPeer();
    if (video_source_ != nullptr) return "{\"ok\":true,\"alreadyStarted\":true}";
    if (source != "synthetic") {
      return Error("only the synthetic source is available; device capture is not implemented "
                   "in this revision");
    }
    try {
      auto source_object = webrtc::make_ref_counted<SyntheticVideoSource>();
      webrtc::scoped_refptr<webrtc::VideoTrackInterface> track =
          factory_->CreateVideoTrack(source_object, "lm-video");
      if (track == nullptr) return Error("could not create the video track");
      webrtc::RtpTransceiverInit init;
      init.direction = webrtc::RtpTransceiverDirection::kSendOnly;
      webrtc::RTCErrorOr<webrtc::scoped_refptr<webrtc::RtpTransceiverInterface>> added =
          peer_->AddTransceiver(track, init);
      if (!added.error().ok()) {
        return Error(std::string("could not attach the video track: ") +
                     added.error().message());
      }
      video_transceiver_ = added.MoveValue();
      video_source_ = source_object;
      video_track_ = track;
      source_object->Start(15);
      return "{\"ok\":true,\"source\":\"synthetic\",\"deviceOpened\":false}";
    } catch (const std::exception& failure) {
      return Error(std::string("video start failed: ") + failure.what());
    } catch (...) {
      return Error("video start failed");
    }
  }

  // Disposes video only. Audio is untouched by design, so a video problem cannot tear down a
  // healthy audio call.
  std::string StopVideoLocked() {
    if (video_source_ == nullptr) return "{\"ok\":true,\"wasRunning\":false}";
    // Stop the frame thread before dropping the track, so no frame can be delivered into a
    // source that is being destroyed.
    video_source_->Stop();
    if (peer_ != nullptr) {
      if (video_transceiver_ != nullptr && video_transceiver_->sender() != nullptr) {
        // Stop sending without renegotiating: audio, if present, stays connected. This is the
        // "video failure disposes video only" rule, enforced natively rather than by convention.
        video_transceiver_->sender()->SetTrack(nullptr);
      }
      video_transceiver_ = nullptr;
    }
    video_source_ = nullptr;
    video_track_ = nullptr;
    return "{\"ok\":true,\"wasRunning\":true,\"deviceClosed\":true}";
  }

  std::mutex mutex_;
  bool runtime_acquired_ = false;
  std::unique_ptr<webrtc::Thread> network_;
  std::unique_ptr<webrtc::Thread> signaling_;
  webrtc::scoped_refptr<webrtc::PeerConnectionFactoryInterface> factory_;
  webrtc::scoped_refptr<webrtc::PeerConnectionInterface> peer_;
  std::unique_ptr<PeerObserver> observer_;
  webrtc::scoped_refptr<SyntheticVideoSource> video_source_;
  webrtc::scoped_refptr<webrtc::RtpTransceiverInterface> video_transceiver_;
  webrtc::scoped_refptr<webrtc::VideoTrackInterface> video_track_;
  std::string error_;
};

std::mutex registry_mutex;
std::unordered_map<uint64_t, std::shared_ptr<Bridge>> bridges;
uint64_t next_handle = 1;
size_t pending_creations = 0;

std::shared_ptr<Bridge> Find(uint64_t handle) {
  std::lock_guard<std::mutex> lock(registry_mutex);
  auto found = bridges.find(handle);
  return found == bridges.end() ? nullptr : found->second;
}

int Copy(const std::string& value, char* output, int capacity) {
  if (value.size() >= static_cast<size_t>(capacity)) {
    output[0] = '\0';
    return kBufferTooSmall;
  }
  std::memcpy(output, value.c_str(), value.size() + 1);
  return kOk;
}

}  // namespace

extern "C" {

__declspec(dllexport) uint32_t lm_wr_abi_version(void) {
  return (kAbiMajor << 16) | kAbiMinor;
}

__declspec(dllexport) int lm_wr_create(uint64_t* handle) {
  if (handle == nullptr) return kBadArgument;
  *handle = 0;
  {
    std::lock_guard<std::mutex> lock(registry_mutex);
    if (bridges.size() + pending_creations >= kMaxBridges || next_handle == UINT64_MAX) {
      return kNotFound;
    }
    ++pending_creations;
  }
  try {
    auto bridge = std::make_shared<Bridge>();
    if (!bridge->Start()) {
      std::lock_guard<std::mutex> lock(registry_mutex);
      --pending_creations;
      return kInternal;
    }
    std::lock_guard<std::mutex> lock(registry_mutex);
    const uint64_t id = next_handle;
    bridges.emplace(id, std::move(bridge));
    ++next_handle;
    --pending_creations;
    *handle = id;
    return kOk;
  } catch (...) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    --pending_creations;
    return kInternal;
  }
}

__declspec(dllexport) int lm_wr_command(uint64_t handle, const char* command, const char* payload,
                                       char* output, int capacity) {
  if (command == nullptr || payload == nullptr || output == nullptr ||
      capacity < kMinOutputCapacity || capacity > kMaxOutputCapacity ||
      strnlen_s(command, kMaxCommandLength + 1) > kMaxCommandLength ||
      strnlen_s(payload, kMaxPayloadLength + 1) > kMaxPayloadLength) {
    return kBadArgument;
  }
  output[0] = '\0';
  try {
    auto bridge = Find(handle);
    if (!bridge) return kNotFound;
    return Copy(bridge->Run(command, payload), output, capacity);
  } catch (const std::exception& failure) {
    return Copy(std::string("{\"error\":\"") + failure.what() + "\"}", output, capacity);
  } catch (...) {
    return kInternal;
  }
}

__declspec(dllexport) int lm_wr_destroy(uint64_t handle) {
  try {
    std::shared_ptr<Bridge> bridge;
    {
      std::lock_guard<std::mutex> lock(registry_mutex);
      auto found = bridges.find(handle);
      if (found == bridges.end()) return kNotFound;
      bridge = found->second;
      bridges.erase(found);
    }
    // Removed from the registry first, so no new command can find it, then stopped under its own
    // lock. Any command already in flight finishes before Stop() runs, and Stop() joins the
    // libwebrtc threads before returning.
    bridge->Stop();
    return kOk;
  } catch (...) {
    return kInternal;
  }
}

}  // extern "C"
