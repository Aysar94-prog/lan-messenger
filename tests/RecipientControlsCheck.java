package net.lanmsg.chat;

/** Permission presentation boundaries: unknown, partial, expired and call replacement. */
public final class RecipientControlsCheck {
  static int passed;
  static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);passed++;}
  public static void main(String[] args){
    RecipientControlVisibility state=new RecipientControlVisibility();
    long token=state.begin("call-a",0);
    check(token>=0,"first call queries immediately");check(state.visible("call-a",0)==0,"unknown hidden");
    check(state.begin("call-a",1)==-1,"no duplicate pending query");
    for(int mask=0;mask<16;mask++){
      long now=mask*5000L;state.finish("call-a",token,mask,true,now);
      check(state.visible("call-a",now)==(mask&12),"scope-specific visibility "+mask);
      if(mask<15)token=state.begin("call-a",now+5000);
    }
    check(state.visible("call-a",85000)==0,"expired confirmation hidden");
    token=state.begin("call-a",85000);state.finish("call-a",token,12,false,85001);
    check(state.visible("call-a",85001)==0,"failed query hides cached controls");
    token=state.begin("call-a",90000);state.finish("call-a",token,4,true,90001);
    check(state.visible("call-a",90002)==4,"camera-only grant excludes speaker");
    long oldToken=state.begin("call-a",95000),newToken=state.begin("call-b",95001);
    check(state.visible("call-b",95001)==0,"new call starts hidden");
    state.finish("call-a",oldToken,12,true,95002);check(state.visible("call-b",95002)==0,"old call response ignored");
    state.finish("call-b",newToken,8,true,95003);check(state.visible("call-b",95004)==8,"speaker-only grant excludes camera");
    check(state.visible("call-a",95004)==0,"old call action hidden");
    token=state.begin("call-b",100001);state.finish("call-b",token,0,true,100002);
    check(state.visible("call-b",100002)==0,"revocation hides all controls");
    token=state.begin("call-b",105001);state.begin(null,105002);state.finish("call-b",token,12,true,105003);
    check(state.visible("call-b",105004)==0,"ending call ignores late response");
    System.out.println("RecipientControlsCheck PASS="+passed+" FAIL=0");
  }
}
