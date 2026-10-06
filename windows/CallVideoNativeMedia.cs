// Production native video media adapter (WVC-07).
// Implements ICallVideoMedia against the verified C ABI bridge. Enforces structural invariants:
// No device enumeration/opening on probe/create. Physical camera start is consent-gated;
// video disposal does not touch the independent production audio adapter.

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
        private bool _readyReported;
        private bool _cameraEnumerated;
        private bool _captureStalled;
        private long _lastLocalFrame;
        private ICallVideoMediaListener? _listener;
        private System.Threading.Timer? _pollTimer;
        private ICallVideoFrameSink? _remoteSink;
        private ICallVideoFrameSink? _localSink;
        private Func<bool> _captureGate = () => false;
        private readonly CallVideoNativeBridge.FrameCallback _frameCallback;

        public CallVideoNativeMedia(string? dllPath = null)
        {
            _bridge = new CallVideoNativeBridge(dllPath);
            _frameCallback = OnNativeFrame;
            try { CreatePeer(); }
            catch { _bridge.Dispose(); throw; }
        }

        private void CreatePeer()
        {
            _handle = _bridge.CreateBridge();
            try
            {
                _bridge.RegisterFrameCallback(_handle, _frameCallback);
                EnsureOk(_bridge.Command(_handle, "probe"));
            }
            catch { _bridge.DestroyBridge(_handle); _handle = 0; throw; }
        }

        public bool CameraEnumerated => _cameraEnumerated;
        public bool AudioDeviceOpened => false;

        public void Initialize(long generation, Func<bool> captureGate)
        {
            ObjectDisposedException.ThrowIf(_disposed, this);
            if (_handle == 0) CreatePeer();
            _activeGeneration = generation;
            _captureGate = captureGate ?? throw new ArgumentNullException(nameof(captureGate));
            _readyReported = false;
            _pollTimer?.Dispose();
            _pollTimer = new System.Threading.Timer(_ => PollNative(), null, 100, 100);
        }

        public string CreateOffer(long generation)
        {
            if (generation != _activeGeneration) return "";
            var r = _bridge.Command(_handle, "create-offer");
            EnsureOk(r);
                using var j = JsonDocument.Parse(r.Body);
                if (j.RootElement.TryGetProperty("sdp", out var s) && s.ValueKind == JsonValueKind.String)
                {
                    string sdp = s.GetString() ?? "";
                    EnsureOk(_bridge.Command(_handle, "set-local", "offer|" + sdp));
                    return sdp;
                }
            throw new InvalidOperationException("Native offer contains no SDP");
        }

        public string CreateAnswer(long generation, string sdp)
        {
            if (generation != _activeGeneration) return "";
            if (string.IsNullOrEmpty(sdp)) throw new ArgumentException("sdp");
            SetRemoteDescription("offer|" + sdp);
            var r = _bridge.Command(_handle, "create-answer");
            EnsureOk(r);
                using var j = JsonDocument.Parse(r.Body);
                if (j.RootElement.TryGetProperty("sdp", out var s) && s.ValueKind == JsonValueKind.String)
                {
                    string answer = s.GetString() ?? "";
                    EnsureOk(_bridge.Command(_handle, "set-local", "answer|" + answer));
                    return answer;
                }
            throw new InvalidOperationException("Native answer contains no SDP");
        }

        public void SetRemoteAnswer(long generation, string sdp)
        {
            if (generation != _activeGeneration) return;
            if (string.IsNullOrEmpty(sdp)) throw new ArgumentException("sdp");
            SetRemoteDescription("answer|" + sdp);
        }

        private void SetRemoteDescription(string sdp)
        {
            var r = _bridge.Command(_handle, "set-remote", sdp);
            EnsureOk(r);
        }

        private void PollNative()
        {
            if (_disposed || _handle == 0) return;
            var generation = _activeGeneration;
            var handle = _handle;
            try
            {
                var candidates = _bridge.Command(handle, "take-candidates");
                if (_handle != handle || _activeGeneration != generation) return;
                if (candidates.Code == Ok && !string.IsNullOrEmpty(candidates.Body))
                {
                    foreach (var encoded in JsonSerializer.Deserialize<string[]>(candidates.Body) ?? [])
                    {
                        int first = encoded.IndexOf('|'), second = first < 0 ? -1 : encoded.IndexOf('|', first + 1);
                        if (first <= 0 || second <= first + 1 || !int.TryParse(encoded[(first + 1)..second], out int index)) continue;
                        _listener?.OnIce(generation, encoded[(second + 1)..], encoded[..first], index);
                    }
                }
                var state = _bridge.Command(handle, "state");
                if (_handle != handle || _activeGeneration != generation) return;
                if (_videoStarted && !_captureStalled && Environment.TickCount64 - System.Threading.Interlocked.Read(ref _lastLocalFrame) > 5000)
                {
                    _captureStalled = true;
                    // Notify the coordinator; DirectShow teardown stays on the capture-owning worker.
                    _listener?.OnCameraStopped(generation);
                }
                if (!_readyReported && state.Code == Ok &&
                    (state.Body.Contains("\"ice\":\"connected\"", StringComparison.Ordinal) ||
                     state.Body.Contains("\"ice\":\"completed\"", StringComparison.Ordinal)))
                {
                    _readyReported = true;
                    _listener?.OnReady(generation);
                }
            }
            catch (Exception e) { if (!_disposed && _handle == handle && _activeGeneration == generation) _listener?.OnError(generation, e.Message); }
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
            if (_disposed || generation != _activeGeneration || !_captureGate())
                throw new InvalidOperationException("Camera capture is not authorized for this call");
            if (!_videoStarted)
            {
                _cameraEnumerated = true;
                var r = _bridge.Command(_handle, "start-video", "camera:default");
                EnsureOk(r);
                System.Threading.Interlocked.Exchange(ref _lastLocalFrame, Environment.TickCount64);
                _captureStalled = false;
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
            throw new InvalidOperationException("Windows uses the default camera; front/rear selection is unsupported");
        }

        public void AttachLocal(ICallVideoFrameSink? sink) { _localSink = sink; }
        public void DetachLocal(ICallVideoFrameSink? sink) { if (ReferenceEquals(_localSink, sink)) _localSink = null; }
        public void AttachRemote(ICallVideoFrameSink? sink) { _remoteSink = sink; }
        public void DetachRemote(ICallVideoFrameSink? sink) { if (ReferenceEquals(_remoteSink, sink)) _remoteSink = null; }
        public ICallVideoRendererLease? AcquireRendererLease() => null;
        public void SetListener(ICallVideoMediaListener? listener) { _listener = listener; }

        public CallVideoDiagnostics.Snapshot Diagnostics() => CallVideoDiagnostics.Snapshot.Unavailable();
        public bool LocalMirror => false;
        public bool SupportsCameraFacing => false;

        private void OnNativeFrame(ulong handle, int kind, IntPtr pixels, int width, int height, int stride, IntPtr context)
        {
            if (_disposed || handle != _handle || kind is not (0 or 1) || pixels == IntPtr.Zero || width <= 0 || height <= 0 || width > 4096 || height > 4096) return;
            if (kind == 0) System.Threading.Interlocked.Exchange(ref _lastLocalFrame, Environment.TickCount64);
            int length;
            try { length = checked(stride * height); } catch { return; }
            if (stride < width * 4 || length <= 0 || length > 64 * 1024 * 1024) return;
            var copy = new byte[length];
            System.Runtime.InteropServices.Marshal.Copy(pixels, copy, 0, length);
            try { (kind == 0 ? _localSink : _remoteSink)?.OnFrame(new CallVideoFrame(copy, width, height, stride)); } catch { }
        }

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
            using var json = JsonDocument.Parse(r.Body);
            if (json.RootElement.ValueKind == JsonValueKind.Object && json.RootElement.TryGetProperty("error", out var error))
                throw new InvalidOperationException(error.GetString() ?? "Native media operation failed");
        }

        public void Dispose()
        {
            if (_disposed) return;
            Dispose(_activeGeneration);
            _disposed = true;
            _localSink = _remoteSink = null; _listener = null;
            _bridge.Dispose();
        }
        public void Dispose(long generation)
        {
            if (_disposed) return;
            if (generation != _activeGeneration && _activeGeneration != 0) return;
            _pollTimer?.Dispose(); _pollTimer = null;
            try { _bridge.RegisterFrameCallback(_handle, null); } catch { }
            try { StopVideo(); } catch { }
            if (_handle != 0)
            {
                _bridge.DestroyBridge(_handle);
                _handle = 0;
            }
            _videoStarted = false;
            _captureGate = () => false;
        }
    }
}
