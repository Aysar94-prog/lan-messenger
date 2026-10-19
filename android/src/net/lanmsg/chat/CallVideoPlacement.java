package net.lanmsg.chat;

/** Pure local placement: preview may occupy only the unobstructed video stage. */
public final class CallVideoPlacement {
  private float x=-1,y=-1;
  private boolean hidden;
  public synchronized float[] position(int width,int height,int previewWidth,int previewHeight,int margin){
    float maxX=Math.max(0,width-previewWidth),maxY=Math.max(0,height-previewHeight);
    if(x<0||y<0){x=Math.max(0,maxX-margin);y=Math.max(0,Math.min(margin,maxY));}
    x=Math.max(0,Math.min(x,maxX));y=Math.max(0,Math.min(y,maxY));return new float[]{x,y};
  }
  public synchronized void move(float left,float top){
    if(Float.isFinite(left)&&Float.isFinite(top)){x=Math.max(0,left);y=Math.max(0,top);}
  }
  public synchronized void reset(){x=y=-1;hidden=false;}
  public synchronized void hide(boolean value){hidden=value;}
  public synchronized boolean hidden(){return hidden;}
}
