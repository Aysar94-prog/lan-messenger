package net.lanmsg.chat;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/** One authenticated call channel: the socket, its single reader thread, and a serialized writer.
 *
 *  Pure Java.  Both sides of a call use this class, so the frame reader and the frame writer are
 *  always paired with the socket that carries them and are always torn down together.
 *
 *  The plan requires one reader and one serialized writer per call channel; the writer is
 *  synchronized on the socket and the reader is a single dedicated thread that stops as soon as
 *  the channel is closed.
 */
final class CallChannel implements CallController.Transport, java.io.Closeable {

  // How long the write side stays half-closed before the socket is closed hard.  Long enough for a
  // terminal frame to be delivered and read by the peer on a LAN, short enough not to hold a socket.
  private static final long GRACEFUL_CLOSE_MS = 1500;

  private final Socket socket;
  private final String peerId;
  private final CallController controller;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final AtomicBoolean socketClosed = new AtomicBoolean(false);
  private final Object writeLock = new Object();

  private volatile Thread reader;
  /** The call this channel belongs to, once known.  For an outgoing call it is known at
   *  construction; for an incoming call it arrives with the INVITE that opens the channel. */
  private volatile String callId;

  private CallChannel(Socket socket, String peerId, CallController controller, String callId) {
    this.socket = socket;
    this.peerId = peerId;
    this.controller = controller;
    this.callId = callId;
  }

  // ── Outgoing ───────────────────────────────────────────────────

  /** Open a channel for an outgoing call and start its reader.
   *
   *  engine.openCallConnection performs the HELLO/READY/CALLCONNECT handshake, so the returned
   *  socket is already authenticated and bound to this call ID. */
  static CallChannel openOutgoing(PeerEngine engine, String peerId, String callId,
                                  CallController controller) throws IOException {
    Socket socket = engine.openCallConnection(peerId, callId);
    CallChannel channel;
    try {
      channel = new CallChannel(socket, peerId, controller, callId);
      socket.setSoTimeout(0);   // a call channel is long-lived; the ordinary 6 s read timeout
                                // belongs to short transactions and would kill an idle call
    } catch (Exception e) {
      try { socket.close(); } catch (Exception ignored) {}
      throw new IOException("Could not prepare the call channel", e);
    }
    channel.startReader();
    return channel;
  }

  // ── Incoming ───────────────────────────────────────────────────

  /** Take over a socket the engine has already authenticated and handed off via CALLCONNECT.
   *  The first frame on the channel must be the INVITE that names this call. */
  static CallChannel adoptIncoming(Socket socket, String peerId, CallController controller) {
    CallLog.i("adopting incoming call channel from " + peerId);
    CallChannel channel = new CallChannel(socket, peerId, controller, null);
    try { socket.setSoTimeout(0); } catch (Exception ignored) {}
    channel.startReader();
    return channel;
  }

  // ── Transport ──────────────────────────────────────────────────

  @Override public void send(byte[] frameBytes) throws IOException {
    if (closed.get()) throw new IOException("The call channel is closed");
    synchronized (writeLock) {
      OutputStream out = socket.getOutputStream();
      CallLog.i("outbound " + CallLog.preview(frameBytes) + " (" + frameBytes.length + " bytes)");
      try {
        out.write(frameBytes);
        out.flush();
      } catch (IOException e) {
        // A write that dies mid-frame is otherwise invisible: the peer simply stops replying and the
        // call is left to expire with no indication of which side dropped the socket.
        CallLog.w("send failed: " + e);
        throw e;
      }
    }
  }

  // ── Reader ─────────────────────────────────────────────────────

  private void startReader() {
    Thread t = new Thread(() -> {
      try {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        byte[] lengthBytes = new byte[4];
        while (!closed.get()) {
          in.readFully(lengthBytes);
          int length = java.nio.ByteBuffer.wrap(lengthBytes).getInt();
          // Reject an oversized length before allocating, and reject a non-positive one.
          if (length <= 0 || length > CallProtocol.MAX_FRAME_BYTES) break;
          byte[] wire = new byte[4 + length];
          System.arraycopy(lengthBytes, 0, wire, 0, 4);
          in.readFully(wire, 4, length);
          CallProtocol.Frame frame = CallSignaling.parse(wire);
          if (frame == null) continue;   // malformed input is dropped, not fatal
          dispatch(frame);
        }
      } catch (Exception e) {
        // Usually just the socket closing because the call ended or the peer went away. It is
        // logged because the alternative is a call that vanishes with no trace: a socket closed
        // out from under the reader is indistinguishable from a peer that sent nothing at all.
        CallLog.i("reader stopped: " + e);
      } finally {
        // A channel that dies while a call is up ends that call. Without this, a dead channel
        // leaves an established call looking live until the heartbeat notices.
        if (!closed.get() && controller != null) {
          try { controller.onSignalingChannelClosed(); } catch (Exception ignored) {}
        }
      }
    }, "call-channel");
    t.setDaemon(true);
    reader = t;
    t.start();
  }

  /** Route one inbound frame into the controller. */
  private void dispatch(CallProtocol.Frame frame) {
    CallLog.i("inbound " + frame.type + " call=" + frame.callId + (callId == null ? " (no call yet)" : ""));
    // Compare the type by value.  CallSignaling.parse builds frame.type as a substring of the
    // received JSON, so it is never the same object as the CallProtocol constant even though the
    // two are equal.  Using == here meant the opening INVITE was never recognised: it fell
    // through to controller.onFrame, which returns immediately because no session exists yet, so
    // every incoming call was discarded before it could ring.  The caller then waited out the
    // 30 s ringing timeout and reported "No answer", and the callee showed nothing at all.
    if (CallProtocol.INVITE.equals(frame.type) && callId == null) {
      // The opening INVITE.  onInvite answers a rejection on this same channel (DECLINE or BUSY)
      // and returns the accepted session, or null when the invitation was refused.
      try {
        controller.onInvite(frame, peerId, this);
      } catch (Exception ignored) {
      }
      return;
    }
    try { controller.onFrame(frame, peerId); }
    catch (Exception ignored) {}
  }

  // ── Teardown ───────────────────────────────────────────────────

  @Override public void close() {
    if (!closed.compareAndSet(false, true)) return;
    hardCloseSocket();
    Thread t = reader;
    if (t != null && t != Thread.currentThread()) {
      // A blocked read would otherwise keep this thread alive until the socket times out.
      try { t.interrupt(); } catch (Exception ignored) {}
    }
    reader = null;
  }

  /** Half-close now and close the socket hard a moment later.
   *
   *  <p>This exists because of what happens when a call ends.  The last thing written is the frame
   *  that tells the peer why — a DECLINE, a HANGUP, a CANCEL — and closing the socket straight
   *  afterwards sends a TCP <em>reset</em> whenever the peer has sent anything we have not read.
   *  A reset can destroy the frame we just wrote, so the peer never learns the call was declined and
   *  keeps ringing until its own timeout expires.  Shutting down the write side first puts our
   *  frame on the wire ahead of a clean FIN, and the reader stays alive across the grace period to
   *  drain whatever the peer sends, so the hard close has nothing left to reset.
   *
   *  <p>Callers that cannot know whether a terminal frame was written may still call close(). */
  void beginGracefulClose() {
    if (!closed.compareAndSet(false, true)) return;
    try { socket.shutdownOutput(); } catch (Exception ignored) {}
    Thread t = reader;
    if (t != null && t != Thread.currentThread()) {
      try { t.interrupt(); } catch (Exception ignored) {}
    }
    reader = null;
    Thread closer = new Thread(() -> {
      try { Thread.sleep(GRACEFUL_CLOSE_MS); } catch (InterruptedException ignored) {}
      hardCloseSocket();
    }, "call-channel-close");
    closer.setDaemon(true);
    closer.start();
  }

  private void hardCloseSocket() {
    if (!socketClosed.compareAndSet(false, true)) return;
    try { socket.close(); } catch (Exception ignored) {}
  }

  boolean isClosed() { return closed.get(); }
  String callId() { return callId; }
  String peerId() { return peerId; }
}
