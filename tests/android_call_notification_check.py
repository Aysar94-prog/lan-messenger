"""Source wiring guard; physical notification/OS behavior must be tested separately."""
from pathlib import Path

root = Path("android/src/net/lanmsg/chat")
notifier = (root / "CallNotifier.java").read_text(encoding="utf-8")
service = (root / "MessengerService.java").read_text(encoding="utf-8")
controller = (root / "CallController.java").read_text(encoding="utf-8")
assert 'calls_ringing_v2' in notifier and 'NotificationManager.IMPORTANCE_DEFAULT' in notifier
assert 'Notification.CallStyle.forIncomingCall' in notifier
assert 'postCallForeground(NOTIFICATION_RINGING,n)' in notifier, "CallStyle must belong to FGS"
assert 'builder.setPublicVersion(publicBuilder.build())' in notifier
assert '.setData(android.net.Uri.parse(' in notifier, "actions must retain call-bound identity"
active = notifier.split('static void showActive(', 1)[1].split('static void clear(', 1)[0]
assert 'manager.cancel(NOTIFICATION_RINGING)' in active
assert 'callCommands.execute(' in service and 'callCommands.shutdownNow()' in service
accept = service.split('case CallNotifier.ACTION_ACCEPT:', 1)[1].split('case CallNotifier.ACTION_DECLINE:', 1)[0]
assert 'cc.decline()' not in accept, "stale accept must not decline newer call"
assert 'Notification action received:' in service and 'Call action ' in service
visibility = service.split('void callActivityVisible(', 1)[1].split('private boolean cameraEligible(', 1)[0]
assert 'revokeCapture()' not in visibility and 'cameraForeground=false' not in visibility
eligible = service.split('private boolean cameraEligible(', 1)[1].split('boolean prepareCallCamera(', 1)[0]
assert 'isKeyguardLocked()' not in eligible and 'if(!callActivityVisible' not in eligible
prepare = service.split('boolean prepareCallCamera(', 1)[1].split('volatile boolean callMediaReal', 1)[0]
assert 'if(!callActivityVisible' in prepare and 'isKeyguardLocked()' in prepare
assert 'acceptInitialReceiveOnly(session.callId)' in controller
print('PASS: call notification identity/lifecycle/lock-screen actions and capture ownership wiring (device acceptance separate)')
