namespace LanMessenger;

// A04 service-owned command boundary. The UI layer calls these; it never touches consent, the wire or
// the media directly. Every action rechecks live eligibility here, so a command that was queued while
// the call was healthy but issued after it was revoked cannot open a camera.
//
// Ported from android/src/net/lanmsg/chat/CallVideoActions.java.
//
// The Effects delegate is the only way out of this type. Note the rollback on every failure path: if
// the answer/request/accept cannot be sent, the consent mutation that authorized it is undone before
// the exception propagates. Without that, a user could end up believing video was accepted while the
// peer never heard about it.
public sealed class CallVideoActions
{
    public delegate bool IEligibility(string expectedCallId);

    public interface IEffects
    {
        void Answer(string callId, bool video);
        void Request(string callId, string requestId);
        void Accept(string callId, string requestId);
        void Decline(string callId, string requestId);
        void Camera(string callId, bool on);
    }

    readonly CallVideoConsent consent;
    readonly IEligibility eligibility;
    readonly IEffects effects;

    public CallVideoActions(CallVideoConsent consent, IEligibility eligibility, IEffects effects)
    {
        this.consent = consent ?? throw new ArgumentNullException(nameof(consent));
        this.eligibility = eligibility ?? throw new ArgumentNullException(nameof(eligibility));
        this.effects = effects ?? throw new ArgumentNullException(nameof(effects));
    }

    public bool IsForCall(string callId) => consent.IsLive(callId);

    public CallVideoConsent.Result AcceptVideo(string callId)
    {
        lock (this)
        {
            var result = consent.AcceptInitial(callId, true, eligibility(callId));
            if (result == CallVideoConsent.Result.Ready)
            {
                try { effects.Answer(callId, true); }
                catch { consent.RollbackInitialAnswer(callId); throw; }
            }
            return result;
        }
    }

    public CallVideoConsent.Result AnswerWithVoice(string callId)
    {
        lock (this)
        {
            var result = consent.AcceptInitial(callId, false, false);
            if (result == CallVideoConsent.Result.Voice)
            {
                try { effects.Answer(callId, false); }
                catch { consent.RollbackInitialAnswer(callId); throw; }
            }
            return result;
        }
    }

    public CallVideoConsent.Result RequestVideo(string callId)
    {
        lock (this)
        {
            var request = Guid.NewGuid().ToString("D");
            var result = consent.RequestUpgrade(callId, request, eligibility(callId));
            if (result == CallVideoConsent.Result.Ready)
            {
                try { effects.Request(callId, request); }
                catch { consent.DeclineUpgrade(callId, request); throw; }
            }
            return result;
        }
    }

    public CallVideoConsent.Result AcceptUpgrade(string callId, string request)
    {
        lock (this)
        {
            var result = consent.AcceptUpgrade(callId, request, eligibility(callId));
            if (result == CallVideoConsent.Result.Ready)
            {
                try { effects.Accept(callId, request); }
                catch { consent.DeclineUpgrade(callId, request); throw; }
            }
            return result;
        }
    }

    public CallVideoConsent.Result DeclineUpgrade(string callId, string request)
    {
        lock (this)
        {
            var result = consent.DeclineUpgrade(callId, request);
            if (result == CallVideoConsent.Result.Declined) effects.Decline(callId, request);
            return result;
        }
    }

    public CallVideoConsent.Result TurnCameraOn(string callId)
    {
        lock (this)
        {
            var result = consent.CameraOn(callId, eligibility(callId));
            if (result == CallVideoConsent.Result.Ready)
            {
                try { effects.Camera(callId, true); }
                catch { consent.CameraOff(callId); throw; }
            }
            return result;
        }
    }

    public void TurnCameraOff(string callId)
    {
        lock (this)
        {
            if (!consent.IsLive(callId)) return;
            consent.CameraOff(callId); effects.Camera(callId, false);
        }
    }
}
