# SubnetDesk Android -> Windows voice diagnosis

Date: 2026-10-08. Read-only diagnosis, not an app repair or release.

## Direct device evidence

User connected the phone for diagnostics. ADB identifies SM-S908E, Android 16, with
installed package com.zibochen.subnetdesk versionName 1.3.0 / versionCode 2075.
Package permission report: android.permission.RECORD_AUDIO granted=false.
AppOps report: RECORD_AUDIO ignore. No permission/AppOps settings were changed.

Filtered application log from the installed process records:

```text
10-08 10:01:23.369 D/LOG_AUDIO_RECORD_HANDLE: createAudioRecorder failed, no RECORD_AUDIO permission
10-08 10:01:23.370 E/LOG_AUDIO_RECORD_HANDLE: createAudioRecorder fail
10-08 10:01:23.370 E/mMainActivity: onVoiceCallStarted fail
```

This confirms why that recorded attempt failed: Android denied microphone capture.
It does not prove all other voice paths will work after enabling the permission.
Windows candidate main PID4052 remains at the 1.3.1 dock path; no active CM was present
at inspection. No new call was initiated by the agent, and no audible retest was performed.
Login credentials supplied by the user are intentionally not recorded or used in diagnostics.

## Source correspondence

In the helper checkout, AudioRecordHandle.kt:createAudioRecorder explicitly checks
RECORD_AUDIO and returns false with the matching log message when permission is absent.
MainActivity.kt:onVoiceCallStarted then emits the matching failure and the user-facing
"Failed to start voice call." message. The inspected remote_page.dart call action directly
requests a voice call; this action does not itself request Android microphone permission.
The installed phone log independently confirms the permission failure; this is not merely
a prediction from Windows logs or source inspection.

## Next checkpoint

Ask before enabling microphone access for this app, or have the user grant it in Android
Settings -> Apps -> SubnetDesk -> Permissions -> Microphone -> Allow while using the app.
Then retry Android-originated call, accept on Windows, inspect the app-specific log and
test audible audio in both directions and clean hang-up. Enabling permission is not itself
an audible-quality acceptance test. A future in-app permission prompt would be a separate
implementation task subject to planning-first approval. No app code/build/signing changed.
