param(
  [Parameter(Mandatory=$true)][string]$Serial,
  [Parameter(Mandatory=$true)][ValidateSet('init','offer','answer','remote','upgrade','activate','stats','stop','camera-off','loopback')][string]$Command,
  [ValidateSet('a','b')][string]$Node = 'a',
  [ValidateSet('VP8','VP9','H264')][string]$Codec = 'VP8',
  [ValidatePattern('^[a-zA-Z0-9-]{0,32}$')][string]$Profile = '',
  [ValidateSet('audio','inactive','video')][string]$Mode = 'audio',
  [ValidateSet('generated','camera')][string]$Source = 'generated',
  [string]$SdpFile,
  [string]$Adb = 'adb',
  [switch]$Summary,
  [string]$OutputRoot = 'D:\LAN-Messenger\outputs\.build\video-feasibility\evidence'
)
$ErrorActionPreference = 'Stop'
$package = 'net.lanmsg.chat.videofeasibility'
$request = [Guid]::NewGuid().ToString('N')
$adbArgs = @('-s',$Serial,'shell','am','start','-W','-n',"$package/.HarnessActivity",
  '--es','request',$request,'--es','cmd',$Command,'--es','node',$Node,
  '--es','codec',$Codec,'--es','mode',$Mode,'--es','source',$Source)
if ($Profile) { $adbArgs += @('--es','profile',$Profile) }
if ($SdpFile) {
  $bytes = [IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $SdpFile).Path)
  if ($bytes.Length -gt 49152) { throw 'SDP exceeds 48 KiB' }
  $adbArgs += @('--es','sdp',[Convert]::ToBase64String($bytes))
}
& $Adb @adbArgs | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'adb command failed' }
$deadline = [DateTime]::UtcNow.AddSeconds(60)
$json = $null
while ([DateTime]::UtcNow -lt $deadline) {
  $response = & $Adb -s $Serial shell run-as $package cat "files/$request.json" 2>$null
  if ($LASTEXITCODE -eq 0) { $json = ($response -join "`n"); break }
  Start-Sleep -Milliseconds 500
}
if (!$json) { throw "No result within 60 seconds: $request. Inspect adb logcat for the test package." }
$result = $json | ConvertFrom-Json
New-Item -ItemType Directory -Force $OutputRoot | Out-Null
$out = Join-Path $OutputRoot "$request-$Command.json"
# Generated evidence, not source files. Raw SDP stays in test-only files, not console reports.
[IO.File]::WriteAllText($out,$json,(New-Object Text.UTF8Encoding($false)))
if ($result.sdp) {
  [IO.File]::WriteAllText((Join-Path $OutputRoot "$request-$Command.sdp"),$result.sdp,(New-Object Text.UTF8Encoding($false)))
}
Write-Output "EVIDENCE=$out"
if (!$result.ok) { throw $result.error }
if ($result.evidence) {
  if ($Summary) {
    [PSCustomObject]@{
      Passed = $result.evidence.passed
      DecodedFramesA = $result.evidence.afterA.decodedSinkFrames
      DecodedFramesB = $result.evidence.afterB.decodedSinkFrames
      MotionChangesA = $result.evidence.afterA.decodedMotionChanges
      MotionChangesB = $result.evidence.afterB.decodedMotionChanges
      Failure = $result.evidence.failure
    }
  } else { $result.evidence | ConvertTo-Json -Depth 12 }
}
if ($result.evidence -and !$result.evidence.passed) { throw $result.evidence.failure }
if ($result.stats) { $result.stats | ConvertTo-Json -Depth 8 }
if ($result.capabilities) { $result.capabilities | ConvertTo-Json -Depth 8 }
