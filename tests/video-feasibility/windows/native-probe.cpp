// Test-only ABI/link probe. No device capture, network or PeerConnection.
#include <cstdio>
#include <cstring>
#include <vector>
#include "api/audio_codecs/builtin_audio_encoder_factory.h"
#include "api/environment/environment_factory.h"
#include "api/video/i420_buffer.h"
#include "api/video/video_frame.h"
#include "api/video_codecs/builtin_video_decoder_factory.h"
#include "api/video_codecs/builtin_video_encoder_factory.h"

extern "C" __declspec(dllexport) int lm_probe_g722(char* output, int capacity) {
  if (!output || capacity < 2 || capacity > 65536) return -1;
  output[0] = '\0';
  try {
  const auto factory = webrtc::CreateBuiltinAudioEncoderFactory();
  if (!factory) return -2;
  bool g722 = false;
  size_t used = 0;
  for (const auto& codec : factory->GetSupportedEncoders()) {
    if (codec.format.name == "G722" && codec.format.clockrate_hz == 8000)
      g722 = true; // RTP clock is 8 kHz, not the decoded 16 kHz sample rate.
    const int written = std::snprintf(output + used, capacity - used, "%s%s/%d",
        used ? "," : "", codec.format.name.c_str(), codec.format.clockrate_hz);
    if (written < 0 || written >= capacity - static_cast<int>(used)) {
      output[0] = '\0';
      return -3;
    }
    used += static_cast<size_t>(written);
  }
  return g722 ? 1 : 0;
  } catch (...) {
    output[0] = '\0';
    return -4; // Never let a C++ exception cross the C ABI.
  }
}

namespace {
class DecodedSink final : public webrtc::DecodedImageCallback {
 public:
  int decoded = 0;
  int motion = 0;
  uint64_t last_hash = 0;
  int32_t Decoded(webrtc::VideoFrame& frame) override {
    const auto pixels = frame.video_frame_buffer()->ToI420();
    if (!pixels || pixels->width() != 160 || pixels->height() != 120) return -1;
    uint64_t hash = 14695981039346656037ULL;
    for (int y = 0; y < pixels->height(); ++y)
      for (int x = 0; x < pixels->width(); ++x)
        hash = (hash ^ pixels->DataY()[y * pixels->StrideY() + x]) * 1099511628211ULL;
    if (decoded && hash != last_hash) ++motion;
    last_hash = hash;
    ++decoded;
    return 0;
  }
};
class EncodedSink final : public webrtc::EncodedImageCallback {
 public:
  std::vector<webrtc::EncodedImage> frames;
  size_t bytes = 0;
  int dropped = 0;
  void OnFrameDropped(uint32_t, int, bool) override { ++dropped; }
  Result OnEncodedImage(const webrtc::EncodedImage& image,
                       const webrtc::CodecSpecificInfo*) override {
    // Copy retains the encoded buffer; never borrow a transient callback pointer.
    if (frames.size() >= 32 || image.size() > 1048576)
      return Result(Result::ERROR_SEND_FAILED);
    try {
      frames.push_back(image);
      bytes += image.size();
      return Result(Result::OK);
    } catch (...) { return Result(Result::ERROR_SEND_FAILED); }
  }
};
struct CodecRelease {
  webrtc::VideoEncoder* encoder;
  webrtc::VideoDecoder* decoder;
  ~CodecRelease() {
    if (encoder) encoder->Release();
    if (decoder) decoder->Release();
  }
};
}

extern "C" __declspec(dllexport) int lm_probe_vp8_loopback(char* output, int capacity) {
  if (!output || capacity < 2 || capacity > 65536) return -1;
  output[0] = '\0';
  try {
    const auto env = webrtc::CreateEnvironment();
    auto encoders = webrtc::CreateBuiltinVideoEncoderFactory();
    auto decoders = webrtc::CreateBuiltinVideoDecoderFactory();
    auto encoder = encoders->Create(env, webrtc::SdpVideoFormat("VP8"));
    auto decoder = decoders->Create(env, webrtc::SdpVideoFormat("VP8"));
    if (!encoder || !decoder) return -2;
    EncodedSink encoded;
    DecodedSink decoded;
    // Release codecs while callbacks are still alive, including early returns.
    CodecRelease release{encoder.get(), decoder.get()};
    webrtc::VideoCodec codec{};
    codec.codecType = webrtc::kVideoCodecVP8;
    codec.width = 160;
    codec.height = 120;
    codec.startBitrate = 300;
    codec.minBitrate = 30;
    codec.maxBitrate = 500;
    codec.maxFramerate = 15;
    codec.active = true;
    codec.qpMax = 56;
    codec.mode = webrtc::VideoCodecMode::kRealtimeVideo;
    *codec.VP8() = webrtc::VideoEncoder::GetDefaultVp8Settings();
    codec.VP8()->numberOfTemporalLayers = 1;
    if (encoder->InitEncode(&codec, webrtc::VideoEncoder::Settings(
            webrtc::VideoEncoder::Capabilities(false), 1, 1200)) != 0) return -5;
    webrtc::VideoDecoder::Settings settings;
    settings.set_codec_type(webrtc::kVideoCodecVP8);
    settings.set_number_of_cores(1);
    settings.set_max_render_resolution({160, 120});
    if (!decoder->Configure(settings)) return -6;
    if (encoder->RegisterEncodeCompleteCallback(&encoded) != 0 ||
        decoder->RegisterDecodeCompleteCallback(&decoded) != 0) return -7;
    webrtc::VideoBitrateAllocation allocation;
    allocation.SetBitrate(0, 0, 300000);
    encoder->SetRates(webrtc::VideoEncoder::RateControlParameters(allocation, 15));
    size_t drained = 0;
    for (int i = 0; i < 20; ++i) {
      auto pixels = webrtc::I420Buffer::Create(160, 120);
      for (int y = 0; y < 120; ++y)
        for (int x = 0; x < 160; ++x)
          pixels->MutableDataY()[y * pixels->StrideY() + x] =
              (x >= i * 7 % 140 && x < i * 7 % 140 + 20) ? 220 : 32;
      for (int y = 0; y < 60; ++y) {
        std::memset(pixels->MutableDataU() + y * pixels->StrideU(), 128, 80);
        std::memset(pixels->MutableDataV() + y * pixels->StrideV(), 128, 80);
      }
      const auto frame = webrtc::VideoFrame::Builder().set_video_frame_buffer(pixels)
          .set_timestamp_us(i * 66667LL).set_timestamp_rtp(i * 6000).build();
      const std::vector<webrtc::VideoFrameType> types{
          i ? webrtc::VideoFrameType::kVideoFrameDelta : webrtc::VideoFrameType::kVideoFrameKey};
      if (encoder->Encode(frame, &types) != 0) return -8;
      while (drained < encoded.frames.size()) {
        if (decoder->Decode(encoded.frames[drained++], 0LL) != 0) return -9;
      }
    }
    const int count = std::snprintf(output, capacity,
        "VP8 local encoded=%zu decoded=%d motion=%d bytes=%zu dropped=%d",
        encoded.frames.size(), decoded.decoded, decoded.motion, encoded.bytes, encoded.dropped);
    if (count < 0 || count >= capacity) { output[0] = '\0'; return -3; }
    return encoded.frames.size() >= 18 && decoded.decoded >= 18 && decoded.motion >= 15 ? 1 : 0;
  } catch (...) { output[0] = '\0'; return -4; }
}

#ifdef LM_PROBE_RUNNER
int main() {
  char codecs[4096];
  const int result = lm_probe_g722(codecs, sizeof(codecs));
  std::printf("native codec/link probe result=%d codecs=%s\n", result, codecs);
  if (lm_probe_g722(nullptr, 0) != -1 || lm_probe_g722(codecs, 1) != -1) return 2;
  if (result != 1) return 1;
  for (int i = 0; i < 2; ++i) {
    const int video = lm_probe_vp8_loopback(codecs, sizeof(codecs));
    std::printf("video probe run=%d result=%d %s\n", i, video, codecs);
    if (video != 1) return 3;
  }
  return 0;
}
#endif
