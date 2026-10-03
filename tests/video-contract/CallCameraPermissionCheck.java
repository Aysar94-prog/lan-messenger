package net.lanmsg.chat;

/** A03 identity/permission boundary tests; no Android runtime or actual camera. */
public final class CallCameraPermissionCheck {
  static int passed;
  static void check(boolean v, String label) { if (!v) throw new AssertionError(label); passed++; }
  public static void main(String[] args) {
    CallCameraPermission gate = new CallCameraPermission();
    int requestCode = CallCameraPermission.nextRequestCode();
    new CallCameraPermission(); // a recreated Activity owns a fresh gate, not a fresh allocator
    check(CallCameraPermission.nextRequestCode()>requestCode,"OS request codes survive Activity/gate recreation");
    check(gate.begin("a","a",true,false,false,false)==CallCameraPermission.Decision.Unavailable,"missing camera");
    check(!gate.hasPending(),"missing camera creates no pending capture");
    check(gate.begin("a","a",true,true,false,true)==CallCameraPermission.Decision.Denied,"permanent denial");
    check(gate.begin("a","b",true,true,true,false)==CallCameraPermission.Decision.Stale,"replaced call");
    check(gate.begin("a","a",false,true,true,false)==CallCameraPermission.Decision.Stale,"Offline");
    check(gate.begin("a","a",true,true,false,false)==CallCameraPermission.Decision.Request,"first request");
    long first=gate.token();
    check(gate.begin("a","a",true,true,false,false)==CallCameraPermission.Decision.Busy,"repeated tap");
    check(gate.complete(first,"a",true,true,false)==CallCameraPermission.Decision.Denied,"denied result");
    check(gate.complete(first,"a",true,true,true)==CallCameraPermission.Decision.Stale,"result consumed once");
    gate.begin("a","a",true,true,false,false); long ended=gate.token();
    gate.reconcile(null,true);
    check(gate.complete(ended,"a",true,true,true)==CallCameraPermission.Decision.Stale,"grant after hangup");
    gate.begin("a","a",true,true,false,false); long old=gate.token(); gate.reconcile("b",true);
    gate.begin("b","b",true,true,false,false); long current=gate.token();
    check(current>old,"new action identity");
    check(gate.complete(old,"b",true,true,true)==CallCameraPermission.Decision.Stale,"old result cannot consume newer request");
    check(gate.hasPending(),"new action survives stale result");
    check(gate.complete(current,"b",true,true,true)==CallCameraPermission.Decision.Ready,"eligible grant once");
    gate.begin("b","b",true,true,true,false); long revoked=gate.token();
    check(gate.complete(revoked,"b",true,true,false)==CallCameraPermission.Decision.Denied,"permission revoked before capture attempt");
    gate.begin("b","b",true,true,false,false); long offline=gate.token(); gate.reconcile("b",false);
    check(gate.complete(offline,"b",true,true,true)==CallCameraPermission.Decision.Stale,"Offline invalidates even if call ID is retained");
    gate.begin("b","b",true,true,false,false); long missing=gate.token();
    check(gate.complete(missing,"b",true,false,true)==CallCameraPermission.Decision.Unavailable,"camera removed during request");
    System.out.println("CallCameraPermissionCheck PASS="+passed+" FAIL=0 (A03; device/UI acceptance pending)");
  }
}
