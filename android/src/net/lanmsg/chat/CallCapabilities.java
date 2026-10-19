package net.lanmsg.chat;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.concurrent.TimeUnit;

/** Separate call capability transaction. No group/attachment capability changes,
 * no persistence/cache and no capability-driven permission or camera acquisition. */
public final class CallCapabilities {
  private CallCapabilities() {}
  public static final String REQUEST="LM4\tCALLCAPS", RESPONSE="LM4\tCALLCAPS\t2\tVP8";
  public static final int MAX_REPLY_BYTES=128, TIMEOUT_MS=10000;
  public interface Clock {long nanos();}
  public interface ReadTimeout {void set(int milliseconds)throws IOException;}
  static int remaining(long started,Clock clock)throws IOException {
    long elapsed=clock.nanos()-started;
    if(elapsed<0||elapsed>=TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS))throw new IOException("Call capability timeout");
    return (int)Math.max(1,(TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS)-elapsed+999999)/1000000);
  }
  static String readReply(InputStream input,long started,Clock clock,ReadTimeout timeout)throws IOException {
    return readLine(input,started,clock,timeout,MAX_REPLY_BYTES);
  }
  private static String readLine(InputStream input,long started,Clock clock,ReadTimeout timeout,int limit)throws IOException {
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    while(true) {
      timeout.set(remaining(started,clock));
      int value=input.read();remaining(started,clock);
      if(value<0)throw new EOFException("No call capability reply");
      if(value==10) {
        try {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();}
        catch(CharacterCodingException invalid) {throw new IOException("Invalid capability encoding");}
      }
      if(bytes.size()>=limit)throw new IOException("Capability reply too large");
      bytes.write(value);
    }
  }
  static String readReply(Socket socket,long started)throws IOException {
    return readReply(socket.getInputStream(),started,System::nanoTime,socket::setSoTimeout);
  }
  static String readHandshake(Socket socket,long started)throws IOException {
    return readLine(socket.getInputStream(),started,System::nanoTime,socket::setSoTimeout,16384);
  }
  public static String response(boolean enabled,boolean legacy) {return enabled&&!legacy?RESPONSE:null;}
  public static boolean supports(String response,boolean verified,boolean legacy,long elapsedMs) {
    return verified&&!legacy&&elapsedMs>=0&&elapsedMs<TIMEOUT_MS&&RESPONSE.equals(response);
  }
}
