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

  private final Socket socket;
  private final String peerId;
  private final CallController controller;
  private final AtomicBoolean closed = new AtomicBoolean(false);
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
      out.write(frameBytes);
      out.flush();
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
      } catch (Exception ignored) {
        // Normal: the socket closed, either because the call ended or because the peer went away.
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
    try { socket.close(); } catch (Exception ignored) {}
    Thread t = reader;
    if (t != null && t != Thread.currentThread()) {
      // A blocked read would otherwise keep this thread alive until the socket times out.
      try { t.interrupt(); } catch (Exception ignored) {}
    }
    reader = null;
  }

  boolean isClosed() { return closed.get(); }
  String callId() { return callId; }
  String peerId() { return peerId; }
}
