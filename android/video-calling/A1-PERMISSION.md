# A03 — camera permission preparation

Source implementation: `CallCameraPermission.java`, `CallUi.cameraPermissionCallId`,
and the call-camera methods/result handler in `MainActivity.java`.

The Activity owns its pending continuation. The pure-Java gate owns only a call ID
and monotonically increasing action token. Camera requests use a separate, never
reused process-wide request code (12000–16000, including Activity recreation); they do not use microphone
or attachment-camera request/result handling. Repeated taps keep the first action.
Call ending, replacement, Offline, service unbinding and Activity destruction
invalidate a pending action. A stale result cannot consume a newer action.

The flow checks camera availability and current CAMERA permission before asking
and again before resuming the local action. Denial/permanent denial and missing
hardware explain that voice remains available. Result completion waits until the
Activity resumes. Permission alone does not authorize video: the future A04
continuation must send a controller command, check current consent/capability,
and A05/A06 must check current permission again at actual capture acquisition.

No current call control invokes this flow. A04 depends on Both A02b, and production
CAMERA/foreground-service declarations belong to A10. Therefore A03 is source
preparation, not a shipped video action or physical permission-flow acceptance.
The production manifest and attachment-camera flow are unchanged.

`tests/video-contract/CallCameraPermissionCheck.java` passes 18 identity/permission
boundary checks without Android/native capture. These supplement A03 and do not
close AT02, which additionally needs A04 consent/media races. Actual OS denial,
permanent denial, missing hardware and revocation UI behavior remain for AT02/AT05
and the later signed candidate; A1 remains incomplete at A04's gate.
