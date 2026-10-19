namespace LanMessenger;

// Purely local preview placement. Ported from android/src/net/lanmsg/chat/CallVideoPlacement.java,
// which is deliberately Android-free so it can be reasoned about (and tested) on its own.
//
// The one invariant that matters: the preview may only ever occupy the video stage, and its position
// is clamped into that stage on every layout. A remembered drag position therefore cannot leave the
// preview off-screen or overlapping the controls after a resize, a display change or a call arriving
// while the window was minimized.
public sealed class CallVideoPlacement
{
    float x = -1, y = -1;
    bool hidden;

    public float[] Position(int width, int height, int previewWidth, int previewHeight, int margin)
    {
        lock (this)
        {
            float maxX = Math.Max(0, width - previewWidth), maxY = Math.Max(0, height - previewHeight);
            if (x < 0 || y < 0) { x = Math.Max(0, maxX - margin); y = Math.Max(0, Math.Min(margin, maxY)); }
            x = Math.Max(0, Math.Min(x, maxX)); y = Math.Max(0, Math.Min(y, maxY));
            return new[] { x, y };
        }
    }

    public void Move(float left, float top)
    {
        lock (this)
        {
            // A non-finite drag coordinate (which a scaled display can produce mid-drag) is ignored
            // rather than allowed to poison the remembered position.
            if (float.IsFinite(left) && float.IsFinite(top)) { x = Math.Max(0, left); y = Math.Max(0, top); }
        }
    }

    public void Reset() { lock (this) { x = y = -1; hidden = false; } }

    public void Hide(bool value) { lock (this) hidden = value; }

    public bool Hidden { get { lock (this) return hidden; } }
}
