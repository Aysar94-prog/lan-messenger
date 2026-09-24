package net.lanmsg.chat;

import java.io.*;
import java.net.Socket;

/** Cancelled write guards must not accumulate for 45 seconds during a large transfer. */
public class TransferWatchdogTest {
  public static void main(String[] args)throws Exception {
    final int[] writes={0};final boolean[] closed={false};
    Socket socket=new Socket(){
      public OutputStream getOutputStream(){return new OutputStream(){
        public void write(int value){writes[0]++;}
        public void write(byte[] bytes,int offset,int count){writes[0]++;}
      };}
      public void close(){closed[0]=true;}
    };
    OutputStream stream=new PeerEngine.NetworkTimeoutOutputStream(socket);
    byte[] block=new byte[512];
    for(int i=0;i<10000;i++)stream.write(block);
    if(writes[0]!=10000||closed[0])throw new AssertionError("Normal writes failed");
    if(!PeerEngine.WRITE_WATCHDOGS.getQueue().isEmpty())throw new AssertionError("Cancelled write guards retained in memory");
    System.out.println("PASS: 10,000 completed writes leave no cancelled watchdogs queued");
  }
}
