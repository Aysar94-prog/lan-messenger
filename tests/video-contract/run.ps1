param(
  [string]$JdkRoot = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [string]$OutputRoot = 'D:\LAN-Messenger\outputs\.build\video-contract'
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
New-Item -ItemType Directory -Force $OutputRoot | Out-Null
$sources = @(
  "$repo\android\src\net\lanmsg\chat\CallProtocol.java",
  "$repo\android\src\net\lanmsg\chat\CallSignaling.java",
  "$repo\android\src\net\lanmsg\chat\CallCapabilities.java",
  "$PSScriptRoot\CallCapabilitiesCheck.java",
  "$repo\android\src\net\lanmsg\chat\CallFrameAdmission.java",
  "$PSScriptRoot\CallFrameAdmissionCheck.java",
  "$PSScriptRoot\DraftVideoContract.java",
  "$PSScriptRoot\DraftVideoContractCheck.java",
  "$repo\android\src\net\lanmsg\chat\CallCameraPermission.java",
  "$PSScriptRoot\CallCameraPermissionCheck.java",
  "$repo\android\src\net\lanmsg\chat\CallVideoConsent.java",
  "$repo\android\src\net\lanmsg\chat\CallVideoProtocol.java",
  "$repo\android\src\net\lanmsg\chat\CallVideoActions.java",
  "$repo\android\src\net\lanmsg\chat\ICallMedia.java",
  "$repo\android\src\net\lanmsg\chat\FakeCallMedia.java",
  "$PSScriptRoot\FakeCallVideoCheck.java",
  "$repo\android\src\net\lanmsg\chat\CallVideoResources.java",
  "$PSScriptRoot\CallVideoResourcesCheck.java",
  "$PSScriptRoot\CallVideoActionsCheck.java",
  "$PSScriptRoot\ConfirmedVideoContractCheck.java",
  "$PSScriptRoot\CallVideoConsentCheck.java"
)
& "$JdkRoot\bin\javac.exe" -encoding UTF-8 -d $OutputRoot @sources
if ($LASTEXITCODE -ne 0) { throw 'Draft contract compile failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.DraftVideoContractCheck
if ($LASTEXITCODE -ne 0) { throw 'Draft contract tests failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.CallCameraPermissionCheck
if ($LASTEXITCODE -ne 0) { throw 'Camera permission identity tests failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.CallVideoConsentCheck
if ($LASTEXITCODE -ne 0) { throw 'Video consent tests failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.ConfirmedVideoContractCheck
if ($LASTEXITCODE -ne 0) { throw 'Confirmed contract tests failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.CallVideoActionsCheck
if ($LASTEXITCODE -ne 0) { throw 'Video command tests failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.CallVideoResourcesCheck
if ($LASTEXITCODE -ne 0) { throw 'Video context ownership tests failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.FakeCallVideoCheck
if ($LASTEXITCODE -ne 0) { throw 'Fake video adapter tests failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.CallFrameAdmissionCheck
if ($LASTEXITCODE -ne 0) { throw 'Call envelope admission tests failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.CallCapabilitiesCheck
if ($LASTEXITCODE -ne 0) { throw 'Call capability boundary tests failed' }
