"""Regression guard for Android's bound-only actual-Offline service lifetime."""
from pathlib import Path


source = Path("android/src/net/lanmsg/chat/MessengerService.java").read_text(encoding="utf-8")

assert "handler.post(this::stopStartedOffline)" in source, (
    "engine-creation failure did not clear the started-service lifetime"
)
assert 'state="Offline";releaseMulticast();stopStartedOffline();' in source, (
    "network bind failure did not clear the started-service lifetime"
)
assert '@Override public boolean onUnbind(Intent intent){if(!"Online".equals(state))stopSelf();' in source, (
    "an unbound service can remain started while actual state is Offline"
)
helper = source.split("void stopStartedOffline(){", 1)[1].split("void releaseMulticast()", 1)[0]
assert "stopForeground(true)" in helper and "stopSelf();" in helper, (
    "Offline failure cleanup must demote foreground state and stop the started lifetime"
)

offline_branch = source.split("}else{", 1)[1].split("void stopStartedOffline(){", 1)[0]
assert 'new Thread(()->{' in offline_branch and '},"lan-network-stop").start();' in offline_branch, (
    "live network teardown must run outside Android's main thread"
)
assert 'if("Stopping".equals(state))' in source and "if(requestedOnline&&!stopping){transition(true);return;}" in source, (
    "Online requests during teardown must be serialized behind the Offline worker"
)

print("PASS: Android Offline teardown is asynchronous, serialized, and bound-only")
