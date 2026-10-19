package net.lanmsg.chat;

import java.io.IOException;
import java.util.UUID;

/** A04 service-owned command boundary. The Activity requests permission separately;
 * these actions recheck live eligibility and never own or directly open a camera.
 * A07 supplies authenticated signaling/native callbacks through Effects. */
public final class CallVideoActions {
  public interface Eligibility { boolean mayCapture(String expectedCallId); }
  public interface Effects {
    void answer(String callId, boolean video) throws IOException;
    void request(String callId, String requestId) throws IOException;
    void accept(String callId, String requestId) throws IOException;
    void decline(String callId, String requestId) throws IOException;
    void camera(String callId, boolean on) throws IOException;
  }
  private final CallVideoConsent consent;
  private final Eligibility eligibility;
  private final Effects effects;
  public CallVideoActions(CallVideoConsent consent, Eligibility eligibility, Effects effects) {
    if(consent==null||eligibility==null||effects==null)throw new IllegalArgumentException("Missing video boundary");
    this.consent=consent; this.eligibility=eligibility; this.effects=effects;
  }
  public boolean isForCall(String callId) { return consent.isLive(callId); }
  public synchronized CallVideoConsent.Result acceptVideo(String callId) throws IOException {
    CallVideoConsent.Result result=consent.acceptInitial(callId,true,eligibility.mayCapture(callId));
    if(result==CallVideoConsent.Result.Ready) {
      try { effects.answer(callId,true); }
      catch(IOException error) { consent.rollbackInitialAnswer(callId); throw error; }
    }
    return result;
  }
  public synchronized CallVideoConsent.Result answerWithVoice(String callId) throws IOException {
    CallVideoConsent.Result result=consent.acceptInitial(callId,false,false);
    if(result==CallVideoConsent.Result.Voice) {
      try { effects.answer(callId,false); }
      catch(IOException error) { consent.rollbackInitialAnswer(callId); throw error; }
    }
    return result;
  }
  public synchronized CallVideoConsent.Result requestVideo(String callId) throws IOException {
    String request=UUID.randomUUID().toString();
    CallVideoConsent.Result result=consent.requestUpgrade(callId,request,eligibility.mayCapture(callId));
    if(result==CallVideoConsent.Result.Ready) {
      try { effects.request(callId,request); }
      catch(IOException error) { consent.declineUpgrade(callId,request); throw error; }
    }
    return result;
  }
  public synchronized CallVideoConsent.Result acceptUpgrade(String callId,String request) throws IOException {
    CallVideoConsent.Result result=consent.acceptUpgrade(callId,request,eligibility.mayCapture(callId));
    if(result==CallVideoConsent.Result.Ready) {
      try { effects.accept(callId,request); }
      catch(IOException error) { consent.declineUpgrade(callId,request); throw error; }
    }
    return result;
  }
  public synchronized CallVideoConsent.Result declineUpgrade(String callId,String request) throws IOException {
    CallVideoConsent.Result result=consent.declineUpgrade(callId,request);
    if(result==CallVideoConsent.Result.Declined)effects.decline(callId,request);
    return result;
  }
  public synchronized CallVideoConsent.Result turnCameraOn(String callId) throws IOException {
    CallVideoConsent.Result result=consent.cameraOn(callId,eligibility.mayCapture(callId));
    if(result==CallVideoConsent.Result.Ready) {
      try { effects.camera(callId,true); }
      catch(IOException error) { consent.cameraOff(callId); throw error; }
    }
    return result;
  }
  public synchronized void turnCameraOff(String callId) throws IOException {
    if(!consent.isLive(callId))return;
    consent.cameraOff(callId); effects.camera(callId,false);
  }
}
