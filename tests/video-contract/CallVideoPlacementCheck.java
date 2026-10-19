package net.lanmsg.chat;
public final class CallVideoPlacementCheck {
  static int pass;
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);pass++;}
  public static void main(String[] args){
    CallVideoPlacement p=new CallVideoPlacement();float[] at=p.position(300,400,100,150,12);
    check(at[0]==188&&at[1]==12,"default top-right inside video stage");
    p.move(1000,1000);at=p.position(300,400,100,150,12);check(at[0]==200&&at[1]==250,"drag clamped to safe bounds");
    at=p.position(150,200,100,150,12);check(at[0]==50&&at[1]==50,"orientation/resize reclamps");
    p.move(-40,-50);at=p.position(300,400,100,150,12);check(at[0]==0&&at[1]==0,"negative position clamped");
    p.hide(true);check(p.hidden(),"hide is local state only");p.reset();check(!p.hidden(),"reset restores visible preview");
    at=p.position(300,400,100,150,12);check(at[0]==188&&at[1]==12,"reset restores default corner");
    p.move(Float.NaN,Float.POSITIVE_INFINITY);at=p.position(300,400,100,150,12);check(at[0]==188&&at[1]==12,"invalid drag ignored");
    at=p.position(20,20,100,150,12);check(at[0]==0&&at[1]==0,"tiny stage remains bounded");
    System.out.println("CallVideoPlacementCheck PASS="+pass+" FAIL=0");
  }
}
