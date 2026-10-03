// Test-only ABI/link probe. No device capture, network or PeerConnection.
#include <cstdio>
#include <cstring>
#include "api/audio_codecs/builtin_audio_encoder_factory.h"

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

#ifdef LM_PROBE_RUNNER
int main() {
  char codecs[4096];
  const int result = lm_probe_g722(codecs, sizeof(codecs));
  std::printf("native codec/link probe result=%d codecs=%s\n", result, codecs);
  if (lm_probe_g722(nullptr, 0) != -1 || lm_probe_g722(codecs, 1) != -1) return 2;
  return result == 1 ? 0 : 1;
}
#endif
