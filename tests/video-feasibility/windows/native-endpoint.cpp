// Test-only native endpoint. No production protocol or camera access.
#include <winsock2.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include "api/create_peerconnection_factory.h"
#include "api/audio_codecs/builtin_audio_decoder_factory.h"
#include "api/audio_codecs/builtin_audio_encoder_factory.h"
#include "api/audio/create_audio_device_module.h"
#include "api/environment/environment_factory.h"
#include "api/make_ref_counted.h"
#include "api/video/i420_buffer.h"
#include "pc/video_track_source.h"
#include "api/video/video_broadcaster.h"
#include "api/stats/rtc_stats_collector_callback.h"
#include "api/video_codecs/builtin_video_decoder_factory.h"
#include "api/video_codecs/builtin_video_encoder_factory.h"
#include "rtc_base/ssl_adapter.h"
#include "rtc_base/thread.h"
#include "rtc_base/win/scoped_com_initializer.h"

namespace {
std::mutex runtime_mutex;
int runtime_users = 0;
void AcquireRuntime() {
  std::lock_guard<std::mutex> guard(runtime_mutex);
  if (!runtime_users) {
    WSADATA data;
    if (WSAStartup(MAKEWORD(2, 2), &data) != 0) throw std::runtime_error("WSAStartup");
    if (!webrtc::InitializeSSL()) { WSACleanup(); throw std::runtime_error("SSL init"); }
  }
  ++runtime_users;
}
void ReleaseRuntime() {
  std::lock_guard<std::mutex> guard(runtime_mutex);
  if (--runtime_users == 0) { webrtc::CleanupSSL(); WSACleanup(); }
}
struct Completion {
  std::mutex mutex;
  std::condition_variable changed;
  bool done = false;
  std::string error;
  std::string value;
  void Finish(std::string failure, std::string result = "") {
    std::lock_guard<std::mutex> lock(mutex);
    if (done) return;
    error = std::move(failure); value = std::move(result); done = true;
    changed.notify_all();
  }
  std::string Wait() {
    std::unique_lock<std::mutex> lock(mutex);
    if (!changed.wait_for(lock, std::chrono::seconds(20), [&] { return done; }))
      throw std::runtime_error("Native operation timed out");
    if (!error.empty()) throw std::runtime_error(error);
    return value;
  }
};
class LocalSet : public webrtc::SetLocalDescriptionObserverInterface {
 public:
  LocalSet(std::shared_ptr<Completion> result, std::string sdp)
      : result_(std::move(result)), sdp_(std::move(sdp)) {}
  void OnSetLocalDescriptionComplete(webrtc::RTCError error) override {
    result_->Finish(error.ok() ? "" : error.message(), std::move(sdp_));
  }
 private:
  std::shared_ptr<Completion> result_;
  std::string sdp_;
};
class RemoteSet : public webrtc::SetRemoteDescriptionObserverInterface {
 public:
  explicit RemoteSet(std::shared_ptr<Completion> result) : result_(std::move(result)) {}
  void OnSetRemoteDescriptionComplete(webrtc::RTCError error) override {
    result_->Finish(error.ok() ? "" : error.message(), "remote-set");
  }
 private:
  std::shared_ptr<Completion> result_;
};
class Description : public webrtc::CreateSessionDescriptionObserver {
 public:
  Description(webrtc::scoped_refptr<webrtc::PeerConnectionInterface> pc,
              std::shared_ptr<Completion> result) : pc_(pc), result_(std::move(result)) {}
  void OnSuccess(webrtc::SessionDescriptionInterface* raw) override {
    std::unique_ptr<webrtc::SessionDescriptionInterface> description(raw);
    auto text = description->ToString();
    pc_->SetLocalDescription(std::move(description),
        webrtc::make_ref_counted<LocalSet>(result_, std::move(text)));
  }
  void OnFailure(webrtc::RTCError error) override { result_->Finish(error.message()); }
 private:
  webrtc::scoped_refptr<webrtc::PeerConnectionInterface> pc_;
  std::shared_ptr<Completion> result_;
};
class Stats : public webrtc::RTCStatsCollectorCallback {
 public:
  explicit Stats(std::shared_ptr<Completion> result) : result_(std::move(result)) {}
  void OnStatsDelivered(const webrtc::scoped_refptr<const webrtc::RTCStatsReport>& report) override {
    result_->Finish("", report->ToJson());
  }
 private:
  std::shared_ptr<Completion> result_;
};
class GeneratedSource : public webrtc::VideoTrackSource {
 public:
  GeneratedSource() : VideoTrackSource(false) { SetState(kLive); }
  void Push(const webrtc::VideoFrame& frame) { broadcaster_.OnFrame(frame); }
 protected:
  webrtc::VideoSourceInterface<webrtc::VideoFrame>* source() override { return &broadcaster_; }
 private:
  webrtc::VideoBroadcaster broadcaster_;
};

class Endpoint : public webrtc::PeerConnectionObserver, public webrtc::VideoSinkInterface<webrtc::VideoFrame> {
 public:
  const bool video_only;
  std::mutex commands;
  bool closed = false;
  std::atomic<int> gathering{0};
  std::atomic<int> connection{0};
  std::unique_ptr<webrtc::Thread> network;
  std::unique_ptr<webrtc::Thread> signaling;
  webrtc::scoped_refptr<webrtc::PeerConnectionFactoryInterface> factory;
  webrtc::scoped_refptr<webrtc::PeerConnectionInterface> pc;
  webrtc::scoped_refptr<webrtc::AudioSourceInterface> audio_source;
  webrtc::scoped_refptr<webrtc::AudioTrackInterface> audio_track;
  webrtc::scoped_refptr<webrtc::AudioDeviceModule> audio_device;
  std::unique_ptr<webrtc::ScopedCOMInitializer> audio_com;
  webrtc::scoped_refptr<GeneratedSource> video_source;
  webrtc::scoped_refptr<webrtc::VideoTrackInterface> video_track;
  std::vector<webrtc::scoped_refptr<webrtc::VideoTrackInterface>> remote_tracks;
  std::thread generator;
  std::atomic<bool> generate{false};
  std::atomic<uint64_t> generated{0}, decoded{0}, motion{0};
  std::atomic<int> last_y{-1};
  bool runtime_acquired = false;
  bool signaling_started = false;
  bool network_started = false;
  template <typename F> void Invoke(F&& operation) {
    std::exception_ptr failure;
    signaling->BlockingCall([&] {
      try { operation(); } catch (...) { failure = std::current_exception(); }
    });
    if (failure) std::rethrow_exception(failure);
  }
  template <typename F> void InvokeNetwork(F&& operation) {
    std::exception_ptr failure;
    network->BlockingCall([&] {
      try { operation(); } catch (...) { failure = std::current_exception(); }
    });
    if (failure) std::rethrow_exception(failure);
  }
  explicit Endpoint(bool video_only_mode = false) : video_only(video_only_mode) {
    AcquireRuntime(); runtime_acquired = true;
    try {
      network = webrtc::Thread::CreateWithSocketServer();
      signaling = webrtc::Thread::Create();
      if (!network || !signaling) throw std::runtime_error("Native thread allocation failed");
      network_started = network->Start(); signaling_started = signaling->Start();
      if (!network_started || !signaling_started)
        throw std::runtime_error("Native thread start failed");
      Invoke([&] {
        if (!video_only) InvokeNetwork([&] {
          audio_com = std::make_unique<webrtc::ScopedCOMInitializer>(webrtc::ScopedCOMInitializer::kMTA);
          if (!audio_com->Succeeded()) throw std::runtime_error("Audio COM initialization failed");
          audio_device = webrtc::CreateAudioDeviceModule(webrtc::CreateEnvironment(), webrtc::AudioDeviceModule::kWindowsCoreAudio);
          if (!audio_device) throw std::runtime_error("Native audio device module unavailable");
        });
        // Current core requires the worker and network roles on the same thread.
        factory = webrtc::CreatePeerConnectionFactory(network.get(), network.get(), signaling.get(),
            audio_device, webrtc::CreateBuiltinAudioEncoderFactory(), webrtc::CreateBuiltinAudioDecoderFactory(),
            webrtc::CreateBuiltinVideoEncoderFactory(), webrtc::CreateBuiltinVideoDecoderFactory(),
            nullptr, nullptr);
        if (!factory) throw std::runtime_error("PeerConnection factory failed");
        webrtc::PeerConnectionInterface::RTCConfiguration config;
        config.sdp_semantics = webrtc::SdpSemantics::kUnifiedPlan;
        auto created = factory->CreatePeerConnectionOrError(config, webrtc::PeerConnectionDependencies(this));
        if (!created.ok()) throw std::runtime_error(created.error().message());
        pc = created.MoveValue();
        // Explicit start-audio command is required, not endpoint creation/offer.
        pc->SetAudioRecording(false); pc->SetAudioPlayout(false);
        if (!video_only) {
        audio_source = factory->CreateAudioSource(webrtc::AudioOptions());
        audio_track = factory->CreateAudioTrack("feasibility-audio", audio_source.get());
        auto added = pc->AddTrack(audio_track, {"feasibility-stream"});
        if (!added.ok()) throw std::runtime_error(added.error().message());
        std::vector<webrtc::RtpCodecCapability> selected;
        for (const auto& codec : factory->GetRtpSenderCapabilities(webrtc::MediaType::AUDIO).codecs)
          if (codec.name == "G722") selected.push_back(codec);
        if (selected.empty()) throw std::runtime_error("G722 unavailable");
        for (const auto& transceiver : pc->GetTransceivers())
          if (transceiver->media_type() == webrtc::MediaType::AUDIO) {
            auto result = transceiver->SetCodecPreferences(selected);
            if (!result.ok()) throw std::runtime_error(result.message());
          }
        }
      });
    } catch (...) { Close(); throw; }
  }
  ~Endpoint() override { Close(); }
  void Close() {
    if (closed) return;
    closed = true;
    StopGenerator();
    if (signaling_started) Invoke([&] {
      for (const auto& track : remote_tracks) track->RemoveSink(this);
      remote_tracks.clear();
      if (pc) { pc->SetAudioRecording(false); pc->SetAudioPlayout(false); pc->Close(); }
      pc = nullptr; video_track = nullptr; video_source = nullptr;
      audio_track = nullptr; audio_source = nullptr; factory = nullptr;
      InvokeNetwork([&] {
        if (audio_device) { audio_device->StopRecording(); audio_device->StopPlayout(); }
        audio_device = nullptr; audio_com.reset();
      });
    });
    if (signaling_started) { signaling->Stop(); signaling_started = false; }
    if (network_started) { network->Stop(); network_started = false; }
    if (runtime_acquired) { runtime_acquired = false; ReleaseRuntime(); }
  }
  void StopGenerator() {
    generate = false;
    if (generator.joinable()) generator.join();
  }
  void StartVideo() {
    Invoke([&] {
      if (!video_track) {
        video_source = webrtc::make_ref_counted<GeneratedSource>();
        video_track = factory->CreateVideoTrack(video_source, "feasibility-video");
        auto added = pc->AddTrack(video_track, {"feasibility-stream"});
        if (!added.ok()) throw std::runtime_error(added.error().message());
      }
      std::vector<webrtc::RtpCodecCapability> selected;
      for (const auto& codec : factory->GetRtpSenderCapabilities(webrtc::MediaType::VIDEO).codecs)
        if (codec.name == "VP8") selected.push_back(codec);
      if (selected.empty()) throw std::runtime_error("VP8 unavailable");
      for (const auto& transceiver : pc->GetTransceivers())
        if (transceiver->media_type() == webrtc::MediaType::VIDEO) {
          auto result = transceiver->SetCodecPreferences(selected);
          if (!result.ok()) throw std::runtime_error(result.message());
          result = transceiver->SetDirectionWithError(webrtc::RtpTransceiverDirection::kSendRecv);
          if (!result.ok()) throw std::runtime_error(result.message());
        }
      video_track->set_enabled(true);
    });
    if (generate) return;
    generate = true;
    generator = std::thread([this, source = video_source] {
      try {
        while (generate) {
          auto pixels = webrtc::I420Buffer::Create(320, 240);
          const auto f = ++generated;
          for (int y = 0; y < 240; ++y)
            for (int x = 0; x < 320; ++x)
              pixels->MutableDataY()[y * pixels->StrideY() + x] = static_cast<uint8_t>(32 + (x + y + f * 7) % 192);
          for (int y = 0; y < 120; ++y) {
            std::memset(pixels->MutableDataU() + y * pixels->StrideU(), 80 + f % 80, 160);
            std::memset(pixels->MutableDataV() + y * pixels->StrideV(), 160 - f % 80, 160);
          }
          const auto time = std::chrono::duration_cast<std::chrono::microseconds>(
              std::chrono::steady_clock::now().time_since_epoch()).count();
          source->Push(webrtc::VideoFrame::Builder().set_video_frame_buffer(pixels).set_timestamp_us(time).build());
          std::this_thread::sleep_for(std::chrono::milliseconds(67));
        }
      } catch (...) { generate = false; }
    });
  }
  std::string Run(const std::string& command, const std::string& payload) {
    if (closed) throw std::runtime_error("Closed endpoint");
    if (command == "offer" || command == "answer") {
      auto result = std::make_shared<Completion>();
      Invoke([&] {
        auto observer = webrtc::make_ref_counted<Description>(pc, result);
        webrtc::PeerConnectionInterface::RTCOfferAnswerOptions options;
        if (command == "offer") pc->CreateOffer(observer.get(), options);
        else pc->CreateAnswer(observer.get(), options);
      });
      return result->Wait();
    }
    if (command == "set-offer" || command == "set-answer") {
      if (video_only && payload.find("m=audio") != std::string::npos)
        throw std::runtime_error("Video-only peer rejects audio SDP");
      auto result = std::make_shared<Completion>();
      Invoke([&] {
        auto parsed = webrtc::CreateSessionDescription(command == "set-offer" ?
            webrtc::SdpType::kOffer : webrtc::SdpType::kAnswer, payload);
        if (!parsed) { result->Finish("Invalid remote SDP"); return; }
        pc->SetRemoteDescription(std::move(parsed), webrtc::make_ref_counted<RemoteSet>(result));
      });
      return result->Wait();
    }
    if (command == "sdp") {
      std::string value;
      Invoke([&] { if (pc->local_description()) value = pc->local_description()->ToString(); });
      return value;
    }
    if (command == "state") return "connection=" + std::to_string(connection.load()) +
        " gathering=" + std::to_string(gathering.load());
    if (command == "video") { StartVideo(); return "generated VP8 enabled"; }
    if (command == "video-off") {
      StopGenerator(); Invoke([&] { if (video_track) video_track->set_enabled(false); });
      return "generated video stopped";
    }
    if (command == "counters") return "{\"generated\":" + std::to_string(generated.load()) +
        ",\"decodedSinkFrames\":" + std::to_string(decoded.load()) +
        ",\"decodedMotionChanges\":" + std::to_string(motion.load()) + "}";
    if (command == "audio-status") {
      if (video_only) return "video-only; no audio track";
      std::string value;
      InvokeNetwork([&] {
        value = "initialized=" + std::to_string(audio_device->Initialized()) +
            " recordingInitialized=" + std::to_string(audio_device->RecordingIsInitialized()) +
            " recording=" + std::to_string(audio_device->Recording()) +
            " playout=" + std::to_string(audio_device->Playing()) +
            " recordingDevices=" + std::to_string(audio_device->RecordingDevices());
      });
      return value;
    }
    if (command == "stats") {
      auto result = std::make_shared<Completion>();
      Invoke([&] { pc->GetStats(webrtc::make_ref_counted<Stats>(result).get()); });
      return result->Wait();
    }
    if (command == "rollback") {
      auto result = std::make_shared<Completion>();
      Invoke([&] { pc->SetLocalDescription(webrtc::CreateRollbackSessionDescription(),
          webrtc::make_ref_counted<LocalSet>(result, "rolled-back")); });
      auto value = result->Wait();
      StopGenerator(); Invoke([&] { if (video_track) video_track->set_enabled(false); });
      return value;
    }
    if (command == "start-audio" || command == "stop-audio") {
      if (video_only) throw std::runtime_error("Video-only peer has no audio capture");
      if (command == "start-audio" && connection.load() != static_cast<int>(webrtc::PeerConnectionInterface::PeerConnectionState::kConnected))
        throw std::runtime_error("Audio starts only on a connected test peer");
      Invoke([&] {
        pc->SetAudioRecording(command == "start-audio"); pc->SetAudioPlayout(command == "start-audio");
      });
      if (command == "start-audio") InvokeNetwork([&] {
        // Upstream AudioState ignores the ADM StartRecording return code. Surface it
        // here rather than allowing an apparent connected but one-way test call.
        if (!audio_device->Recording()) {
          const int init = audio_device->InitRecording();
          const int start = init == 0 ? audio_device->StartRecording() : -100;
          if (init != 0 || start != 0) throw std::runtime_error("Native recording startup failed: init=" +
              std::to_string(init) + " start=" + std::to_string(start));
        }
      });
      return command;
    }
    throw std::runtime_error("Unknown endpoint command");
  }
  void OnSignalingChange(webrtc::PeerConnectionInterface::SignalingState) override {}
  void OnDataChannel(webrtc::scoped_refptr<webrtc::DataChannelInterface>) override {}
  void OnIceGatheringChange(webrtc::PeerConnectionInterface::IceGatheringState state) override { gathering = state; }
  void OnConnectionChange(webrtc::PeerConnectionInterface::PeerConnectionState state) override { connection = static_cast<int>(state); }
  void OnIceCandidate(const webrtc::IceCandidate*) override {} // Full gathered SDP via sdp command.
  void OnTrack(webrtc::scoped_refptr<webrtc::RtpTransceiverInterface> transceiver) override {
    auto track = transceiver->receiver()->track();
    if (!track || track->kind() != webrtc::MediaStreamTrackInterface::kVideoKind) return;
    auto video = webrtc::scoped_refptr<webrtc::VideoTrackInterface>(static_cast<webrtc::VideoTrackInterface*>(track.get()));
    for (const auto& existing : remote_tracks) if (existing.get() == video.get()) return;
    video->AddOrUpdateSink(this, webrtc::VideoSinkWants()); remote_tracks.push_back(video);
  }
  void OnFrame(const webrtc::VideoFrame& frame) override {
    auto pixels = frame.video_frame_buffer()->ToI420();
    if (!pixels || !pixels->width() || !pixels->height()) return;
    const int y = pixels->DataY()[0];
    const int previous = last_y.exchange(y);
    if (previous >= 0 && previous != y) ++motion;
    ++decoded;
  }
};
std::mutex registry_mutex;
std::map<uint64_t, std::shared_ptr<Endpoint>> endpoints;
uint64_t next_handle = 1;
size_t pending_creations = 0;
std::shared_ptr<Endpoint> Find(uint64_t handle) {
  std::lock_guard<std::mutex> lock(registry_mutex);
  auto found = endpoints.find(handle);
  return found == endpoints.end() ? nullptr : found->second;
}
int Copy(const std::string& value, char* output, int capacity) {
  if (value.size() >= static_cast<size_t>(capacity)) { output[0] = '\0'; return -3; }
  std::memcpy(output, value.c_str(), value.size() + 1); return 1;
}
}
int CreateEndpoint(uint64_t* handle, bool video_only) {
  if (!handle) return -1;
  *handle = 0;
  {
    std::lock_guard<std::mutex> lock(registry_mutex);
    if (endpoints.size() + pending_creations >= 4 || next_handle == UINT64_MAX) return -2;
    ++pending_creations;
  }
  try {
    auto endpoint = std::make_shared<Endpoint>(video_only);
    std::lock_guard<std::mutex> lock(registry_mutex);
    const auto id = next_handle;
    endpoints.emplace(id, std::move(endpoint));
    ++next_handle;
    --pending_creations;
    *handle = id;
    return 1;
  } catch (...) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    --pending_creations;
    return -4;
  }
}
extern "C" __declspec(dllexport) int lm_endpoint_create(uint64_t* handle) { return CreateEndpoint(handle, false); }
extern "C" __declspec(dllexport) int lm_endpoint_create_video_only(uint64_t* handle) { return CreateEndpoint(handle, true); }
extern "C" __declspec(dllexport) int lm_endpoint_command(uint64_t handle, const char* command,
    const char* payload, char* output, int capacity) {
  if (!command || !payload || !output || capacity < 2 || capacity > 1048576 ||
      strnlen_s(command, 65) > 64 || strnlen_s(payload, 65537) > 65536) return -1;
  output[0] = '\0';
  try {
    auto endpoint = Find(handle); if (!endpoint) return -2;
    std::lock_guard<std::mutex> lock(endpoint->commands);
    return Copy(endpoint->Run(command, payload), output, capacity);
  } catch (const std::exception& error) { Copy(error.what(), output, capacity); return -4; }
    catch (...) { return -4; }
}
extern "C" __declspec(dllexport) int lm_endpoint_destroy(uint64_t handle) {
  try {
    std::shared_ptr<Endpoint> endpoint;
    {
      std::lock_guard<std::mutex> lock(registry_mutex);
      auto found = endpoints.find(handle); if (found == endpoints.end()) return -2;
      endpoint = found->second; endpoints.erase(found);
    }
    std::lock_guard<std::mutex> lock(endpoint->commands);
    endpoint->Close(); return 1;
  } catch (...) { return -4; }
}
