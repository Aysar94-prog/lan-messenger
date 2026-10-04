// Production native video media adapter (WVC-07).
// Implements ICallVideoMedia against the verified C ABI bridge. Enforces structural invariants:
// only 'synthetic' source accepted, no device enumeration/opening on probe/create, video-only
// disposal. ICE candidate gathering may return empty (socket server not driven) by design.

using System;
using System.Text.Json;

namespace LanMessenger.Windows
{
    internal sealed class CallVideoNativeMedia : ICallVideoMedia, IDisposable
    {
        private const int Ok = 1;
        private const int NotFound = -2;
        private readonly CallVideoNativeBridge _bridge;
        private ulong _handle;
        private long _activeGeneration;
        private bool _disposed;
        private bool _videoStarted;
        private ICallVideoMediaListener? _listener;

        public CallVideoNativeMedia()
        {
            _bridge = new CallVideoNativeBridge();
            _handle = _bridge.CreateBridge();
            EnsureOk(_bridge.Command(_handle, "probe"));
        }

        public bool CameraEnumerated => false;
        public bool AudioDeviceOpened => false;

        public void Initialize(long generation, Func<bool> captureGate)
        {
            _activeGeneration = generation;
        }

        public string CreateOffer(long generation)
        {
            if (generation != _activeGeneration) return "";
            var r = _bridge.Command(_handle, "create-offer");
            EnsureOk(r);
            try
            {
                using var j = JsonDocument.Parse(r.Body);
                if (j.RootElement.TryGetProperty("sdp", out var s) && s.ValueKind == JsonValueKind.String)
                    return s.GetString() ?? "";
            }
            catch { }
            return "";
        }

        public string CreateAnswer(long generation, string sdp)
        {
            if (generation != _activeGeneration) return "";
            if (string.IsNullOrEmpty(sdp)) throw new ArgumentException("sdp");
            SetRemoteDescription(sdp);
            var r = _bridge.Command(_handle, "create-answer");
            EnsureOk(r);
            try
            {
                using var j = JsonDocument.Parse(r.Body);
                if (j.RootElement.TryGetProperty("sdp", out var s) && s.ValueKind == JsonValueKind.String)
                    return s.GetString() ?? "";
            }
            catch { }
            return "";
        }

        public void SetRemoteAnswer(long generation, string sdp)
        {
            if (generation != _activeGeneration) return;
            if (string.IsNullOrEmpty(sdp)) throw new ArgumentException("sdp");
            SetRemoteDescription(sdp);
        }

        private void SetRemoteDescription(string sdp)
        {
            var r = _bridge.Command(_handle, "set-remote", sdp);
            EnsureOk(r);
        }

        public void AddIce(long generation, string candidate, string mid, int index)
        {
            if (generation != _activeGeneration || string.IsNullOrEmpty(candidate)) return;
            string payload = mid + "|" + index + "|" + candidate;
            var r = _bridge.Command(_handle, "add-ice", payload);
            EnsureOk(r);
        }

        public void StartCamera(long generation)
        {
            if (generation != _activeGeneration) return;
            if (!_videoStarted)
            {
                var r = _bridge.Command(_handle, "start-video", "synthetic");
                EnsureOk(r);
                _videoStarted = true;
            }
        }

        public void StopCamera(long generation)
        {
            if (generation != _activeGeneration) return;
            StopVideo();
        }

        public void SwitchCamera(long generation)
        {
            if (generation != _activeGeneration) return;
        }

        public void AttachLocal(ICallVideoFrameSink? sink) { }
        public void DetachLocal(ICallVideoFrameSink? sink) { }
        public void AttachRemote(ICallVideoFrameSink? sink) { }
        public void DetachRemote(ICallVideoFrameSink? sink) { }
        public ICallVideoRendererLease? AcquireRendererLease() => null;
        public void SetListener(ICallVideoMediaListener? listener) { _listener = listener; }

        public CallVideoDiagnostics.Snapshot Diagnostics() => CallVideoDiagnostics.Snapshot.Unavailable();
        public bool LocalMirror => true;

        public void StopVideo()
        {
            if (_videoStarted || HandleValid())
            {
                _bridge.Command(_handle, "stop-video");
                _videoStarted = false;
            }
        }

        public string GetState()
        {
            var r = _bridge.Command(_handle, "state");
            if (r.Code == NotFound) return "{\"error\":\"no peer\"}";
            return r.Body;
        }

        private bool HandleValid()
        {
            var r = _bridge.Command(_handle, "state");
            return r.Code == Ok;
        }

        private static void EnsureOk((int Code, string Body) r)
        {
            if (r.Code != Ok)
                throw new InvalidOperationException(string.IsNullOrEmpty(r.Body) ? $"native bridge returned {r.Code}" : r.Body);
        }

        public void Dispose() { Dispose(_activeGeneration); }
        public void Dispose(long generation)
        {
            if (_disposed) return;
            if (generation != _activeGeneration && _activeGeneration != 0) return;
            _disposed = true;
            try { StopVideo(); } catch { }
            if (_handle != 0)
            {
                _bridge.DestroyBridge(_handle);
                _handle = 0;
            }
            _bridge.Dispose();
        }
    }
}
