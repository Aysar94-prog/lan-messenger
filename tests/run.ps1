param(
  [string]$JdkRoot = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [string]$TestRoot = (Join-Path $PSScriptRoot '..\..\outputs\.build\release-tests'),
  # Compile-only stub, needed only because CallLog.java (a thin android.util.Log wrapper used by
  # the call-handoff path in PeerEngine.java) is pulled into this otherwise-pure-Java compile set.
  # Never affects runtime; this whole invocation only ever runs on the JVM, never on a device.
  [string]$AndroidJar = 'C:\Program Files (x86)\Android\android-sdk\platforms\android-34\android.jar'
)
$ErrorActionPreference = 'Stop'
$TestRoot = [IO.Path]::GetFullPath($TestRoot)
New-Item -ItemType Directory -Force $TestRoot | Out-Null
function Check-Result { if ($LASTEXITCODE -ne 0) { throw "Test/build failed: $LASTEXITCODE" } }
Push-Location (Join-Path $PSScriptRoot '..')
try {
  python tests/android_service_lifecycle.py
  Check-Result
  python tests/android_call_notification_check.py
  Check-Result
  dotnet build tests/CsharpHarness/CsharpHarness.csproj -c Release --configfile NuGet.Config -o "$TestRoot\csharp"
  Check-Result
  dotnet "$TestRoot\csharp\CsharpHarness.dll" --voice-check (Join-Path $PSScriptRoot 'voice_messages/vectors')
  Check-Result
  dotnet "$TestRoot\csharp\CsharpHarness.dll" --voice-device-check
  Check-Result
  dotnet "$TestRoot\csharp\CsharpHarness.dll" --voice-scheduler-check
  Check-Result
  dotnet "$TestRoot\csharp\CsharpHarness.dll" --voice-draft-reconcile-check
  Check-Result
  # Windows video calling (WVC): the shared call-signalling corpora, run against the Windows
  # implementation here and against the Android one by tests/video-contract/run.ps1, which diffs the
  # two verdict files. Both sides are also checked against a hand-authored expectation, so the diff
  # only ever has to catch a genuine Android/Windows disagreement.
  dotnet "$TestRoot\csharp\CsharpHarness.dll" --call-video-check
  Check-Result
  & tests/video-contract/run.ps1 -JdkRoot $JdkRoot -OutputRoot "$TestRoot\video-contract" -CsharpHarness "$TestRoot\csharp"
  Check-Result
  python tests/voice_architecture_check.py
  Check-Result
  python tests/voice_drafts.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/voice_scheduler.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  & "$JdkRoot\bin\javac.exe" -J-Xmx256m -encoding UTF-8 -d "$TestRoot\java" android/src/net/lanmsg/chat/CallCapabilities.java android/src/net/lanmsg/chat/DirectFileTransfer.java android/src/net/lanmsg/chat/DownloadDestination.java android/src/net/lanmsg/chat/PeerEngine.java android/src/net/lanmsg/chat/AttachmentStore.java android/src/net/lanmsg/chat/TransferManager.java android/src/net/lanmsg/chat/GroupSync.java android/src/net/lanmsg/chat/AvatarSync.java android/src/net/lanmsg/chat/ResumeStore.java android/src/net/lanmsg/chat/ResumableTransfer.java android/src/net/lanmsg/chat/DailyUploadPolicy.java android/src/net/lanmsg/chat/SecureIdentity.java android/src/net/lanmsg/chat/VoiceMessages.java android/src/net/lanmsg/chat/VoiceDrafts.java android/src/net/lanmsg/chat/CallLogMarker.java android/src/net/lanmsg/chat/CallLog.java tests/PeerHarness.java tests/TestProtector.java tests/LegacyGroupState.java tests/DailyUploadPolicyTest.java tests/AttachmentRangeTest.java tests/TransferWatchdogTest.java tests/ResumeStoreTest.java tests/MiniJson.java tests/VoiceMessagesCheck.java tests/VoiceDraftReconcileCheck.java tests/VoiceSchedulerCheck.java -classpath "$AndroidJar"
  Check-Result
  & "$JdkRoot\bin\java.exe" -cp "$TestRoot\java" net.lanmsg.chat.VoiceMessagesCheck (Join-Path $PSScriptRoot 'voice_messages/vectors')
  Check-Result
  & "$JdkRoot\bin\java.exe" -cp "$TestRoot\java" net.lanmsg.chat.VoiceDraftReconcileCheck
  Check-Result
  & "$JdkRoot\bin\java.exe" -cp "$TestRoot\java" net.lanmsg.chat.VoiceSchedulerCheck
  Check-Result
  python tests/voice_drafts_android.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/voice_scheduler_android.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/voice_interop.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  & "$JdkRoot\bin\java.exe" -cp "$TestRoot\java" net.lanmsg.chat.AttachmentRangeTest
  Check-Result
  & "$JdkRoot\bin\java.exe" -cp "$TestRoot\java" net.lanmsg.chat.TransferWatchdogTest
  Check-Result
  & "$JdkRoot\bin\java.exe" -cp "$TestRoot\java" net.lanmsg.chat.ResumeStoreTest
  Check-Result
  & "$JdkRoot\bin\java.exe" -cp "$TestRoot\java" net.lanmsg.chat.DailyUploadPolicyTest "$TestRoot\policy-$([Guid]::NewGuid().ToString('N'))"
  Check-Result
  python tests/integration.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/features.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/images.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/upload_policy.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/direct_downloads.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/transfers.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/android_ordinary_transfer.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/android_resume.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/mixed_transfer_sources.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/delete_conversation.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/group_membership.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/group_migration_broadcast.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/ownership_transfer.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  python tests/offline_lifecycle.py "$JdkRoot\bin\java.exe" "$TestRoot\java" "$TestRoot\csharp\CsharpHarness.dll" $TestRoot
  Check-Result
  dotnet build tests/WindowsUi/WindowsUi.csproj -c Release --configfile NuGet.Config -o "$TestRoot\ui"
  Check-Result
  dotnet "$TestRoot\ui\WindowsUi.dll" "$TestRoot\ui-results"
  Check-Result
} finally { Pop-Location }
