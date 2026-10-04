param(
  [string]$JdkRoot = 'C:\Program Files (x86)\Android\openjdk\jdk-17.0.14',
  [string]$OutputRoot = 'D:\LAN-Messenger\outputs\.build\video-contract',
  # Optional: the built tests/CsharpHarness output directory. When supplied, the same shared
  # corpora are also run against the Windows implementation and the two verdict files are diffed,
  # which is the only check that can catch the two platforms having drifted apart. Omit it to run
  # the Android-side checks alone (they do not need a .NET toolchain).
  [string]$CsharpHarness = ''
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
  "$repo\android\src\net\lanmsg\chat\CallVideoCoordinator.java",
  "$PSScriptRoot\CallVideoCoordinatorCheck.java",
  "$repo\android\src\net\lanmsg\chat\ICallMedia.java",
  "$repo\android\src\net\lanmsg\chat\CallVideoDiagnostics.java",
  "$PSScriptRoot\CallVideoDiagnosticsCheck.java",
  "$repo\android\src\net\lanmsg\chat\CallVideoPlacement.java",
  "$PSScriptRoot\CallVideoPlacementCheck.java",
  "$repo\android\src\net\lanmsg\chat\FakeCallMedia.java",
  "$PSScriptRoot\FakeCallVideoCheck.java",
  "$repo\android\src\net\lanmsg\chat\CallVideoResources.java",
  "$PSScriptRoot\CallVideoResourcesCheck.java",
  "$PSScriptRoot\CallVideoActionsCheck.java",
  "$PSScriptRoot\ConfirmedVideoContractCheck.java",
  "$PSScriptRoot\CallVideoConsentCheck.java",
  "$PSScriptRoot\SharedFrameFixtureCheck.java",
  "$PSScriptRoot\CallCapabilityFixtureCheck.java"
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
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.CallVideoCoordinatorCheck
if ($LASTEXITCODE -ne 0) { throw 'Video coordinator tests failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.CallVideoDiagnosticsCheck
if ($LASTEXITCODE -ne 0) { throw 'Video diagnostics tests failed' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.CallVideoPlacementCheck
if ($LASTEXITCODE -ne 0) { throw 'Video preview placement tests failed' }

# ── Shared corpora ────────────────────────────────────────────────────────────
# Both checks write a verdict file even when they report failures, because the value of the diff
# below is in seeing every disagreement at once rather than the first one.
$fixtures = Join-Path $PSScriptRoot 'fixtures'
$androidFrames = Join-Path $OutputRoot 'frames.android.txt'
$androidCaps = Join-Path $OutputRoot 'caps.android.txt'

& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.SharedFrameFixtureCheck $fixtures $androidFrames
if ($LASTEXITCODE -ne 0) { throw 'Shared frame fixtures disagree with the hand-authored expectation (Android)' }
& "$JdkRoot\bin\java.exe" -cp $OutputRoot net.lanmsg.chat.CallCapabilityFixtureCheck $fixtures $androidCaps
if ($LASTEXITCODE -ne 0) { throw 'Shared capability fixtures disagree with the hand-authored expectation (Android)' }

if (-not $CsharpHarness) {
  Write-Host 'Skipping the Windows side: pass -CsharpHarness <built CsharpHarness directory> to diff the two platforms.'
  return
}

$dll = Join-Path $CsharpHarness 'CsharpHarness.dll'
if (-not (Test-Path $dll)) { throw "CsharpHarness.dll not found under '$CsharpHarness'. Build tests/CsharpHarness/CsharpHarness.csproj first." }
$windowsFrames = Join-Path $OutputRoot 'frames.windows.txt'
$windowsCaps = Join-Path $OutputRoot 'caps.windows.txt'

& dotnet $dll --call-frame-fixture-check $fixtures $windowsFrames
if ($LASTEXITCODE -ne 0) { throw 'Shared frame fixtures disagree with the hand-authored expectation (Windows)' }
& dotnet $dll --call-capability-fixture-check $fixtures $windowsCaps
if ($LASTEXITCODE -ne 0) { throw 'Shared capability fixtures disagree with the hand-authored expectation (Windows)' }

# The three-way result is the whole point: each side was already checked against a hand-authored
# expectation above, so anything left here is a genuine Android/Windows disagreement rather than a
# bad fixture. $env differs, which makes the diff portable.
function Compare-Verdicts([string]$label, [string]$left, [string]$right) {
  $a = Get-Content $left
  $b = Get-Content $right
  if ($a.Count -ne $b.Count) {
    throw "$label : the two sides reported a different number of records ($($a.Count) vs $($b.Count))"
  }
  $diff = Compare-Object $a $b -SyncWindow 0
  if ($diff) {
    $diff | Select-Object -First 40 | Format-Table -AutoSize | Out-String | Write-Host
    throw "$label : Android and Windows disagree on $($diff.Count) record(s)"
  }
  Write-Host "$label : $($a.Count) records agree between Android and Windows"
}

Compare-Verdicts 'Shared frame corpus' $androidFrames $windowsFrames
Compare-Verdicts 'Shared capability corpus' $androidCaps $windowsCaps
